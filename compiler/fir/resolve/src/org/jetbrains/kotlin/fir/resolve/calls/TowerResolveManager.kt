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
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.isExtensionFunctionType
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.util.*

internal class TowerResolveManager(private val towerResolver: FirNewTowerResolver) {
    private val queue = PriorityQueue<TowerResolveQuery>()

    private var group = TowerGroup.Start

    lateinit var candidateFactory: CandidateFactory

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
                // NB: this should fix problem in DFA (KT-36014)
                safe = info.isSafeCall
                // ---
                typeRef = towerResolver.typeCalculator.tryCalculateReturnType(symbol.firUnsafe())
            }
        }

        fun SessionBasedTowerLevel.handleLevel(explicitInvokes: Boolean = false): LevelHandler {
            val processor =
                TowerScopeLevelProcessor(
                    explicitReceiverKind,
                    resultCollector,
                    // TODO: performance?
                    if (explicitInvokes) CandidateFactory(towerResolver.components, info) else candidateFactory,
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
                    processElementsByName(TowerScopeLevel.Token.Functions, info.name, processor)
                    if (explicitInvokes || resultCollector.isSuccess()) return this@LevelHandler

                    val invokeReceiverCollectorWasSuccess = invokeReceiverCollector.isSuccess()
                    if (!invokeReceiverCollector.isSuccess()) {
                        val invokeReceiverCandidateFactory = CandidateFactory(
                            towerResolver.components,
                            info.replaceWithVariableAccess()
                        )
                        val invokeReceiverProcessor = TowerScopeLevelProcessor(
                            explicitReceiverKind,
                            invokeReceiverCollector,
                            invokeReceiverCandidateFactory,
                            group
                        )
                        processElementsByName(TowerScopeLevel.Token.Properties, info.name, invokeReceiverProcessor)
                    }

                    if (!invokeReceiverCollectorWasSuccess && invokeReceiverCollector.isSuccess()) {
                        // TODO: record already processed candidates
                        for (invokeReceiverCandidate in invokeReceiverCollector.bestCandidates()) {
                            val extensionReceiverExpression = invokeReceiverCandidate.extensionReceiverExpression()

                            val symbol = invokeReceiverCandidate.symbol as FirVariableSymbol<*>
                            val useExtensionReceiverAsArgument =
                                symbol.fir.receiverTypeRef == null &&
                                        invokeReceiverCandidate.explicitReceiverKind == ExplicitReceiverKind.EXTENSION_RECEIVER &&
                                        symbol.fir.returnTypeRef.isExtensionFunctionType()
                            val invokeReceiverExpression = createExplicitReceiverForInvoke(invokeReceiverCandidate).let {
                                if (!useExtensionReceiverAsArgument) {
                                    it.extensionReceiver = extensionReceiverExpression
                                    // NB: this should fix problem in DFA (KT-36014)
                                    it.explicitReceiver = info.explicitReceiver
                                }
                                towerResolver.components.transformQualifiedAccessUsingSmartcastInfo(it)
                            }

                            val invokeFunctionInfo =
                                info.copy(explicitReceiver = invokeReceiverExpression, name = OperatorNameConventions.INVOKE).let {
                                    if (useExtensionReceiverAsArgument) it.withReceiverAsArgument(extensionReceiverExpression)
                                    else it
                                }

                            val explicitReceiver = ExpressionReceiverValue(invokeReceiverExpression)
                            // TODO: not all groups should be created here?
                            if (useExtensionReceiverAsArgument) {
                                towerResolver.enqueueResolverForBuiltinInvokeExtension(invokeFunctionInfo, explicitReceiver)
                            } else {
                                towerResolver.enqueueResolverForInvoke(invokeFunctionInfo, explicitReceiver)
                            }
                            processQueuedLevelsForInvoke()
                        }
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

    fun add(query: TowerResolveQuery) {
        queue.add(query)
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
        explicitReceiverKind: ExplicitReceiverKind
    ) {
        queue.add(TowerResolveQuery(towerLevel, callInfo, explicitReceiverKind, group))
    }

    fun processQueuedLevelsForInvoke(groupLimit: TowerGroup = this.group) {
        while (queue.isNotEmpty()) {
            if (queue.peek().group > groupLimit) {
                break
            }
            val query = queue.poll()
            with(LevelHandler(query.callInfo, query.explicitReceiverKind, resultCollector, query.group)) {
                query.towerLevel.handleLevel(explicitInvokes = true)
            }
        }
    }
}