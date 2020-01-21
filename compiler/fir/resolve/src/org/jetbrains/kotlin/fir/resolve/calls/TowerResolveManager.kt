/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.fir.expressions.impl.FirQualifiedAccessExpressionImpl
import org.jetbrains.kotlin.fir.resolve.transformQualifiedAccessUsingSmartcastInfo
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.firUnsafe
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.isExtensionFunctionType
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.util.*

class TowerResolveManager internal constructor(private val towerResolver: FirNewTowerResolver) {
    private val queue = PriorityQueue<TowerInvokeResolveQuery>()

    private var group = TowerGroup.Start

    lateinit var candidateFactory: CandidateFactory

    lateinit var invokeReceiverCandidateFactory: CandidateFactory

    lateinit var invokeBuiltinExtensionReceiverCandidateFactory: CandidateFactory

    lateinit var stubReceiverCandidateFactory: CandidateFactory

    lateinit var resultCollector: CandidateCollector

    lateinit var invokeReceiverCollector: CandidateCollector

    private class TowerScopeLevelProcessor(
        val explicitReceiverKind: ExplicitReceiverKind,
        val resultCollector: CandidateCollector,
        val candidateFactory: CandidateFactory,
        val group: TowerGroup
    ) : TowerScopeLevel.TowerScopeLevelProcessor<AbstractFirBasedSymbol<*>> {
        fun weaken(depth: Int = 1): TowerScopeLevelProcessor =
            TowerScopeLevelProcessor(explicitReceiverKind, resultCollector, candidateFactory, group.Weakened(depth))

        override fun consumeCandidate(
            symbol: AbstractFirBasedSymbol<*>,
            dispatchReceiverValue: ReceiverValue?,
            implicitExtensionReceiverValue: ImplicitReceiverValue<*>?,
            builtInExtensionFunctionReceiverValue: ReceiverValue?
        ) {
            // TODO: check extension receiver for default package members (?!)
            // See TowerLevelKindTowerProcessor
            resultCollector.consumeCandidate(
                group, candidateFactory.createCandidate(
                    symbol,
                    explicitReceiverKind,
                    dispatchReceiverValue,
                    implicitExtensionReceiverValue,
                    builtInExtensionFunctionReceiverValue
                )
            )
        }
    }


    private inner class LevelHandler(
        val info: CallInfo,
        val explicitReceiverKind: ExplicitReceiverKind,
        val resultCollector: CandidateCollector,
        val group: TowerGroup
    ) {
        private fun createExplicitReceiverForInvoke(candidate: Candidate): FirQualifiedAccessExpressionImpl {
            val symbol = candidate.symbol as FirCallableSymbol<*>
            return FirQualifiedAccessExpressionImpl(null).apply {
                calleeReference = FirNamedReferenceWithCandidate(
                    null,
                    symbol.callableId.callableName,
                    candidate
                )
                dispatchReceiver = candidate.dispatchReceiverExpression()
                typeRef = towerResolver.typeCalculator.tryCalculateReturnType(symbol.firUnsafe())
            }
        }

        fun SessionBasedTowerLevel.handleLevel(invokeResolveMode: InvokeResolveMode? = null): LevelHandler {
            val processor =
                TowerScopeLevelProcessor(
                    explicitReceiverKind,
                    resultCollector,
                    // TODO: performance?
                    if (invokeResolveMode == InvokeResolveMode.IMPLICIT_CALL_ON_GIVEN_RECEIVER) {
                        CandidateFactory(towerResolver.components, info)
                    } else candidateFactory,
                    group
                )
            val weakProcessor = processor.weaken(1)
            when (info.callKind) {
                CallKind.VariableAccess -> {
                    processElementsByName(TowerScopeLevel.Token.Properties, info.name, processor)
                    // TODO: more accurate condition, or process properties/object in some other way
                    if (!resultCollector.isSuccess() &&
                        (this !is ScopeTowerLevel || this.extensionReceiver !is AbstractExplicitReceiver<*>)
                    ) {
                        processElementsByName(TowerScopeLevel.Token.Objects, info.name, weakProcessor)
                    }
                }
                CallKind.Function -> {
                    val invokeBuiltinExtensionMode =
                        invokeResolveMode == InvokeResolveMode.RECEIVER_FOR_INVOKE_BUILTIN_EXTENSION
                    if (!invokeBuiltinExtensionMode) {
                        processElementsByName(TowerScopeLevel.Token.Functions, info.name, processor)
                    }
                    if (invokeResolveMode == InvokeResolveMode.IMPLICIT_CALL_ON_GIVEN_RECEIVER ||
                        resultCollector.isSuccess()
                    ) {
                        return this@LevelHandler
                    }

                    val invokeReceiverProcessor = TowerScopeLevelProcessor(
                        explicitReceiverKind,
                        invokeReceiverCollector,
                        if (invokeBuiltinExtensionMode) invokeBuiltinExtensionReceiverCandidateFactory
                        else invokeReceiverCandidateFactory,
                        group
                    )
                    invokeReceiverCollector.newDataSet()
                    processElementsByName(TowerScopeLevel.Token.Properties, info.name, invokeReceiverProcessor)

                    if (invokeReceiverCollector.isSuccess()) {
                        for (invokeReceiverCandidate in invokeReceiverCollector.bestCandidates()) {

                            val symbol = invokeReceiverCandidate.symbol as FirCallableSymbol<*>
                            val isExtensionFunctionType = symbol.fir.returnTypeRef.isExtensionFunctionType()
                            if (invokeBuiltinExtensionMode && !isExtensionFunctionType) {
                                continue
                            }
                            val extensionReceiverExpression = invokeReceiverCandidate.extensionReceiverExpression()
                            val useImplicitReceiverAsBuiltinInvokeArgument =
                                !invokeBuiltinExtensionMode && isExtensionFunctionType &&
                                        invokeReceiverCandidate.explicitReceiverKind == ExplicitReceiverKind.NO_EXPLICIT_RECEIVER

                            val invokeReceiverExpression = createExplicitReceiverForInvoke(invokeReceiverCandidate).let {
                                if (!invokeBuiltinExtensionMode) {
                                    it.extensionReceiver = extensionReceiverExpression
                                    // NB: this should fix problem in DFA (KT-36014)
                                    it.explicitReceiver = info.explicitReceiver
                                    it.safe = info.isSafeCall
                                }
                                towerResolver.components.transformQualifiedAccessUsingSmartcastInfo(it)
                            }

                            val invokeFunctionInfo =
                                info.copy(explicitReceiver = invokeReceiverExpression, name = OperatorNameConventions.INVOKE).let {
                                    when {
                                        invokeBuiltinExtensionMode -> it.withReceiverAsArgument(info.explicitReceiver!!)
                                        else -> it
                                    }
                                }

                            val explicitReceiver = ExpressionReceiverValue(invokeReceiverExpression)
                            when {
                                invokeBuiltinExtensionMode -> {
                                    towerResolver.enqueueResolverForBuiltinInvokeExtensionWithExplicitArgument(
                                        invokeFunctionInfo, explicitReceiver, this@TowerResolveManager
                                    )
                                }
                                useImplicitReceiverAsBuiltinInvokeArgument -> {
                                    towerResolver.enqueueResolverForBuiltinInvokeExtensionWithImplicitArgument(
                                        invokeFunctionInfo, explicitReceiver, this@TowerResolveManager
                                    )
                                }
                                else -> {
                                    towerResolver.enqueueResolverForInvoke(
                                        invokeFunctionInfo, explicitReceiver, this@TowerResolveManager
                                    )
                                }
                            }
                        }
                        processQueuedLevelsForInvoke()
                    }
                }
                CallKind.CallableReference -> {
                    val stubReceiver = info.stubReceiver
                    if (stubReceiver != null) {
                        val stubReceiverValue = ExpressionReceiverValue(stubReceiver)
                        val stubProcessor = TowerScopeLevelProcessor(
                            if (this is MemberScopeTowerLevel && dispatchReceiver is AbstractExplicitReceiver<*>) {
                                ExplicitReceiverKind.DISPATCH_RECEIVER
                            } else {
                                ExplicitReceiverKind.EXTENSION_RECEIVER
                            },
                            resultCollector,
                            stubReceiverCandidateFactory, group
                        )
                        val towerLevelWithStubReceiver = replaceReceiverValue(stubReceiverValue)
                        with(towerLevelWithStubReceiver) {
                            processElementsByName(TowerScopeLevel.Token.Functions, info.name, stubProcessor)
                            processElementsByName(TowerScopeLevel.Token.Properties, info.name, stubProcessor)
                        }
                        processElementsByName(TowerScopeLevel.Token.Functions, info.name, weakProcessor)
                        processElementsByName(TowerScopeLevel.Token.Properties, info.name, weakProcessor)
                    } else {
                        processElementsByName(TowerScopeLevel.Token.Functions, info.name, processor)
                        processElementsByName(TowerScopeLevel.Token.Properties, info.name, processor)
                    }
                }
                else -> {
                    throw AssertionError("Unsupported call kind in tower resolver: ${info.callKind}")
                }
            }
            return this@LevelHandler
        }
    }

    fun reset() {
        queue.clear()
        group = TowerGroup.Start
    }

    fun processLevel(
        towerLevel: SessionBasedTowerLevel,
        callInfo: CallInfo,
        group: TowerGroup,
        explicitReceiverKind: ExplicitReceiverKind = ExplicitReceiverKind.NO_EXPLICIT_RECEIVER
    ) {
        if (group <= this.group) {
            throw AssertionError("Incorrect level processing order (c) Mikhail Glukhikh")
        }
        this.group = group
        with(LevelHandler(callInfo, explicitReceiverKind, resultCollector, group)) {
            towerLevel.handleLevel()
        }
        processQueuedLevelsForInvoke()
    }

    fun enqueueLevelForInvoke(
        towerLevel: SessionBasedTowerLevel,
        callInfo: CallInfo,
        group: TowerGroup,
        mode: InvokeResolveMode,
        explicitReceiverKind: ExplicitReceiverKind = ExplicitReceiverKind.NO_EXPLICIT_RECEIVER
    ) {
        if (callInfo.callKind == CallKind.Function) {
            queue.add(TowerInvokeResolveQuery(towerLevel, callInfo, explicitReceiverKind, group, mode))
        }
    }

    fun processQueuedLevelsForInvoke(groupLimit: TowerGroup = this.group) {
        while (queue.isNotEmpty()) {
            if (queue.peek().group > groupLimit) {
                break
            }
            val query = queue.poll()
            with(LevelHandler(query.callInfo, query.explicitReceiverKind, resultCollector, query.group)) {
                query.towerLevel.handleLevel(invokeResolveMode = query.mode)
            }
        }
    }
}