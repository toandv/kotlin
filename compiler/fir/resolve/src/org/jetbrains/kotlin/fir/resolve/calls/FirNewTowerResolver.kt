/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.impl.FirImportImpl
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedImportImpl
import org.jetbrains.kotlin.fir.declarations.isInner
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.transformers.ReturnTypeCalculator
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.FirExplicitSimpleImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirLocalScope
import org.jetbrains.kotlin.fir.scopes.impl.FirStaticScope
import org.jetbrains.kotlin.fir.types.ConeIntegerLiteralType
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.descriptorUtil.HIDES_MEMBERS_NAME_LIST

class FirNewTowerResolver(
    val typeCalculator: ReturnTypeCalculator,
    val components: BodyResolveComponents,
    resolutionStageRunner: ResolutionStageRunner,
    private val topLevelScopes: List<FirScope>,
    private val localScopes: List<FirLocalScope>
) {

    private val session: FirSession get() = components.session
    private val collector = CandidateCollector(components, resolutionStageRunner)
    private val manager = TowerResolveManager(this)
    private lateinit var implicitReceiverValues: List<ImplicitReceiverValue<*>>

    private data class ImplicitReceiver(val receiver: ImplicitReceiverValue<*>, val usableAsValue: Boolean, val depth: Int)

    private val implicitReceivers: Sequence<ImplicitReceiver>
        get() {
            var depth = 0
            var firstDispatchValue = true
            return implicitReceiverValues.asSequence().map {
                val usableAsValue = when (it) {
                    is ImplicitExtensionReceiverValue -> true
                    is ImplicitDispatchReceiverValue -> if (firstDispatchValue) {
                        if ((it.boundSymbol.fir as? FirRegularClass)?.isInner == false) {
                            firstDispatchValue = false
                        }
                        true
                    } else {
                        it.boundSymbol.fir.classKind == ClassKind.OBJECT
                    }
                }
                ImplicitReceiver(it, usableAsValue, depth++)
            }
        }

    private fun runResolverForQualifierReceiver(
        info: CallInfo,
        collector: CandidateCollector,
        resolvedQualifier: FirResolvedQualifier,
        manager: TowerResolveManager
    ): CandidateCollector {
        val qualifierScope = if (resolvedQualifier.classId == null) {
            FirExplicitSimpleImportingScope(
                listOf(
                    FirResolvedImportImpl(
                        FirImportImpl(source = null, importedFqName = FqName.topLevel(info.name), isAllUnder = false, aliasName = null),
                        resolvedQualifier.packageFqName,
                        relativeClassName = null
                    )
                ), session, components.scopeSession
            )
        } else {
            QualifierReceiver(resolvedQualifier).qualifierScope(session, components.scopeSession)
        }

        if (qualifierScope != null) {
            manager.processLevel(
                ScopeTowerLevel(session, components, qualifierScope), info, TowerGroup.Qualifier
            )
            if (collector.isSuccess()) return collector
        }

        // TODO: check we have a value
        if (resolvedQualifier.classId != null) {
            runResolverForExpressionReceiver(info, collector, resolvedQualifier, manager)
        }

        manager.processQueuedLevelsForInvoke(groupLimit = TowerGroup.Last)
        return collector
    }

    private fun runResolverForNoReceiver(
        info: CallInfo,
        collector: CandidateCollector,
        manager: TowerResolveManager
    ): CandidateCollector {
        val shouldProcessExtensionsBeforeMembers =
            info.callKind == CallKind.Function && info.name in HIDES_MEMBERS_NAME_LIST
        if (shouldProcessExtensionsBeforeMembers) {
            // Special case (extension hides member)
            for ((index, topLevelScope) in topLevelScopes.withIndex()) {
                manager.processLevel(
                    ScopeTowerLevel(
                        session, components, topLevelScope, extensionsOnly = true
                    ), info, TowerGroup.TopPrioritized(index)
                )
                if (collector.isSuccess()) return collector
            }
        }
        for ((index, localScope) in localScopes.withIndex()) {
            manager.processLevel(
                ScopeTowerLevel(session, components, localScope), info, TowerGroup.Local(index)
            )
            if (collector.isSuccess()) return collector
        }
        for ((implicitReceiverValue, usableAsValue, depth) in implicitReceivers) {
            // NB: companions are processed via implicitReceiverValues!
            val parentGroup = TowerGroup.Implicit(depth)

            if (usableAsValue) {
                manager.processLevel(
                    MemberScopeTowerLevel(
                        session, components, dispatchReceiver = implicitReceiverValue, scopeSession = components.scopeSession
                    ), info, parentGroup.Member
                )
                if (collector.isSuccess()) return collector
                for ((localIndex, localScope) in localScopes.withIndex()) {
                    manager.processLevel(
                        ScopeTowerLevel(
                            session, components, localScope, extensionReceiver = implicitReceiverValue
                        ), info, parentGroup.Local(localIndex)
                    )
                    if (collector.isSuccess()) return collector
                }
                for ((implicitDispatchReceiverValue, usable, dispatchDepth) in implicitReceivers) {
                    if (!usable) continue
                    val implicitDispatchReceiverScope = implicitDispatchReceiverValue.scope(session, components.scopeSession)
                    if (implicitDispatchReceiverScope != null) {
                        manager.processLevel(
                            MemberScopeTowerLevel(
                                session,
                                components,
                                dispatchReceiver = implicitDispatchReceiverValue,
                                extensionReceiver = implicitReceiverValue,
                                scopeSession = components.scopeSession
                            ), info, parentGroup.Implicit(dispatchDepth)
                        )
                    }
                }
                for ((topIndex, topLevelScope) in topLevelScopes.withIndex()) {
                    manager.processLevel(
                        ScopeTowerLevel(
                            session, components, topLevelScope, extensionReceiver = implicitReceiverValue
                        ), info, parentGroup.Top(topIndex)
                    )
                    if (collector.isSuccess()) return collector
                }
            }

            if (implicitReceiverValue is ImplicitDispatchReceiverValue) {
                val scope = implicitReceiverValue.scope(session, components.scopeSession)
                if (scope != null) {
                    manager.processLevel(
                        ScopeTowerLevel(
                            session,
                            components,
                            FirStaticScope(scope)
                        ), info, parentGroup.Static(depth)
                    )
                }
            }
        }

        for ((index, topLevelScope) in topLevelScopes.withIndex()) {
            manager.processLevel(
                ScopeTowerLevel(session, components, topLevelScope), info, TowerGroup.Top(index)
            )
            if (collector.isSuccess()) return collector
        }

        manager.processQueuedLevelsForInvoke(groupLimit = TowerGroup.Last)
        return collector
    }

    private fun runResolverForExpressionReceiver(
        info: CallInfo,
        collector: CandidateCollector,
        receiver: FirExpression,
        manager: TowerResolveManager
    ): CandidateCollector {
        val explicitReceiverValue = ExpressionReceiverValue(receiver)

        val shouldProcessExtensionsBeforeMembers =
            info.callKind == CallKind.Function && info.name in HIDES_MEMBERS_NAME_LIST
        if (shouldProcessExtensionsBeforeMembers) {
            // Special case (extension hides member)
            for ((index, topLevelScope) in topLevelScopes.withIndex()) {
                manager.processLevel(
                    ScopeTowerLevel(
                        session, components, topLevelScope, extensionReceiver = explicitReceiverValue, extensionsOnly = true
                    ), info, TowerGroup.TopPrioritized(index), ExplicitReceiverKind.EXTENSION_RECEIVER
                )
                if (collector.isSuccess()) return collector
            }
        }

        manager.processLevel(
            MemberScopeTowerLevel(
                session, components, dispatchReceiver = explicitReceiverValue, scopeSession = components.scopeSession
            ), info, TowerGroup.Member, ExplicitReceiverKind.DISPATCH_RECEIVER
        )
        if (collector.isSuccess()) return collector

        val shouldProcessExplicitReceiverScopeOnly =
            info.callKind == CallKind.Function && info.explicitReceiver?.typeRef?.coneTypeSafe<ConeIntegerLiteralType>() != null
        if (shouldProcessExplicitReceiverScopeOnly) {
            // Special case (integer literal type)
            return collector
        }

        for ((index, localScope) in localScopes.withIndex()) {
            manager.processLevel(
                ScopeTowerLevel(
                    session, components, localScope, extensionReceiver = explicitReceiverValue
                ), info, TowerGroup.Local(index), ExplicitReceiverKind.EXTENSION_RECEIVER
            )
            if (collector.isSuccess()) return collector
        }
        for ((implicitReceiverValue, usable, depth) in implicitReceivers) {
            if (!usable) continue
            // NB: companions are processed via implicitReceiverValues!
            val parentGroup = TowerGroup.Implicit(depth)
            manager.processLevel(
                MemberScopeTowerLevel(
                    session, components,
                    dispatchReceiver = implicitReceiverValue, extensionReceiver = explicitReceiverValue,
                    scopeSession = components.scopeSession
                ), info, parentGroup.Member, ExplicitReceiverKind.EXTENSION_RECEIVER
            )
            if (collector.isSuccess()) return collector
        }
        for ((index, topLevelScope) in topLevelScopes.withIndex()) {
            manager.processLevel(
                ScopeTowerLevel(
                    session, components, topLevelScope, extensionReceiver = explicitReceiverValue
                ), info, TowerGroup.Top(index), ExplicitReceiverKind.EXTENSION_RECEIVER
            )
            if (collector.isSuccess()) return collector
        }

        manager.processQueuedLevelsForInvoke(groupLimit = TowerGroup.Last)
        return collector
    }

    internal fun enqueueResolverForInvoke(
        info: CallInfo,
        invokeReceiverValue: ExpressionReceiverValue,
        manager: TowerResolveManager
    ) {
        manager.enqueueLevelForInvoke(
            MemberScopeTowerLevel(
                session, components, dispatchReceiver = invokeReceiverValue, scopeSession = components.scopeSession
            ), info, TowerGroup.Member, ExplicitReceiverKind.DISPATCH_RECEIVER
        )
        for ((index, localScope) in localScopes.withIndex()) {
            manager.enqueueLevelForInvoke(
                ScopeTowerLevel(
                    session, components, localScope, extensionReceiver = invokeReceiverValue
                ), info, TowerGroup.Local(index), ExplicitReceiverKind.EXTENSION_RECEIVER
            )
        }
        for ((implicitReceiverValue, usable, depth) in implicitReceivers) {
            if (!usable) continue
            // NB: companions are processed via implicitReceiverValues!
            val parentGroup = TowerGroup.Implicit(depth)
            manager.enqueueLevelForInvoke(
                MemberScopeTowerLevel(
                    session, components,
                    dispatchReceiver = implicitReceiverValue, extensionReceiver = invokeReceiverValue,
                    scopeSession = components.scopeSession
                ), info, parentGroup.Member, ExplicitReceiverKind.EXTENSION_RECEIVER
            )
        }
        for ((index, topLevelScope) in topLevelScopes.withIndex()) {
            manager.enqueueLevelForInvoke(
                ScopeTowerLevel(
                    session, components, topLevelScope, extensionReceiver = invokeReceiverValue
                ), info, TowerGroup.Top(index), ExplicitReceiverKind.EXTENSION_RECEIVER
            )
        }
    }

    internal fun enqueueResolverForBuiltinInvokeExtension(
        info: CallInfo,
        invokeReceiverValue: ExpressionReceiverValue,
        manager: TowerResolveManager
    ) {
        // TODO: review carefully!
        manager.enqueueLevelForInvoke(
            MemberScopeTowerLevel(
                session, components, dispatchReceiver = invokeReceiverValue,
                scopeSession = components.scopeSession
            ), info, TowerGroup.Member, ExplicitReceiverKind.DISPATCH_RECEIVER
        )
        for ((implicitReceiverValue, usable, depth) in implicitReceivers) {
            if (!usable) continue
            val parentGroup = TowerGroup.Implicit(depth)
            manager.enqueueLevelForInvoke(
                MemberScopeTowerLevel(
                    session, components, dispatchReceiver = invokeReceiverValue,
                    extensionReceiver = implicitReceiverValue,
                    implicitExtensionInvokeMode = true,
                    scopeSession = components.scopeSession
                ), info, parentGroup.InvokeExtension, ExplicitReceiverKind.DISPATCH_RECEIVER
            )
        }
    }

    fun runResolver(
        implicitReceiverValues: List<ImplicitReceiverValue<*>>,
        info: CallInfo,
        collector: CandidateCollector = this.collector,
        manager: TowerResolveManager = this.manager
    ): CandidateCollector {
        // TODO: add flag receiver / non-receiver position
        this.implicitReceiverValues = implicitReceiverValues
        manager.candidateFactory = CandidateFactory(components, info)
        if (info.callKind == CallKind.CallableReference && info.stubReceiver != null) {
            manager.stubReceiverCandidateFactory = CandidateFactory(components, info.replaceExplicitReceiver(info.stubReceiver))
        }
        manager.resultCollector = collector
        if (info.callKind == CallKind.Function) {
            manager.invokeReceiverCollector = CandidateCollector(components, components.resolutionStageRunner)
        }

        return when (val receiver = info.explicitReceiver) {
            is FirResolvedQualifier -> runResolverForQualifierReceiver(info, collector, receiver, manager)
            null -> runResolverForNoReceiver(info, collector, manager)
            else -> runResolverForExpressionReceiver(info, collector, receiver, manager)
        }
    }

    fun reset() {
        collector.newDataSet()
        manager.reset()
    }
}