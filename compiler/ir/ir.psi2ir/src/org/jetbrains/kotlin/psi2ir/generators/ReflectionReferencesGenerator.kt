/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.psi2ir.generators

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedValueParameterDescriptor
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.referenceClassifier
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.util.withScope
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments
import org.jetbrains.kotlin.psi2ir.intermediate.CallBuilder
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.expressions.DoubleColonLHS

class ReflectionReferencesGenerator(statementGenerator: StatementGenerator) : StatementGeneratorExtension(statementGenerator) {

    fun generateClassLiteral(ktClassLiteral: KtClassLiteralExpression): IrExpression {
        val ktArgument = ktClassLiteral.receiverExpression!!
        val lhs = getOrFail(BindingContext.DOUBLE_COLON_LHS, ktArgument)
        val resultType = getTypeInferredByFrontendOrFail(ktClassLiteral).toIrType()

        return if (lhs is DoubleColonLHS.Expression && !lhs.isObjectQualifier) {
            IrGetClassImpl(
                ktClassLiteral.startOffsetSkippingComments, ktClassLiteral.endOffset, resultType,
                ktArgument.genExpr()
            )
        } else {
            val typeConstructorDeclaration = lhs.type.constructor.declarationDescriptor
            val typeClass = typeConstructorDeclaration
                ?: throw AssertionError("Unexpected type constructor for ${lhs.type}: $typeConstructorDeclaration")
            IrClassReferenceImpl(
                ktClassLiteral.startOffsetSkippingComments, ktClassLiteral.endOffset, resultType,
                context.symbolTable.referenceClassifier(typeClass), lhs.type.toIrType()
            )
        }
    }

    fun generateCallableReference(ktCallableReference: KtCallableReferenceExpression): IrExpression {
        val resolvedCall = getResolvedCall(ktCallableReference.callableReference)!!
        val resolvedDescriptor = resolvedCall.resultingDescriptor

        val callBuilder = unwrapCallableDescriptorAndTypeArguments(resolvedCall, context.extensions.samConversion)

        if (resolvedCall.valueArguments.isNotEmpty()) {
            val adaptedCallableReference = generateAdaptedCallableReference(ktCallableReference, callBuilder)
            if (adaptedCallableReference.hasResultAdaptation ||
                !isTrivialArgumentAdaptation(adaptedCallableReference.irAdapteeCall)
            ) {
                return adaptedCallableReference.irReferenceExpression
            }
        }

        return statementGenerator.generateCallReceiver(
            ktCallableReference,
            resolvedDescriptor,
            resolvedCall.dispatchReceiver, resolvedCall.extensionReceiver,
            isSafe = false
        ).call { dispatchReceiverValue, extensionReceiverValue ->
            generateCallableReference(
                ktCallableReference,
                getTypeInferredByFrontendOrFail(ktCallableReference),
                callBuilder.descriptor,
                callBuilder.typeArguments
            ).also { irCallableReference ->
                irCallableReference.dispatchReceiver = dispatchReceiverValue?.loadIfExists()
                irCallableReference.extensionReceiver = extensionReceiverValue?.loadIfExists()
            }
        }
    }

    private fun isTrivialArgumentAdaptation(irAdapteeCall: IrFunctionAccessExpression): Boolean {
        for (i in 0 until irAdapteeCall.valueArgumentsCount) {
            val irValueArgument = irAdapteeCall.getValueArgument(i) ?: return false
            if (irValueArgument is IrVararg) {
                val irVarargElements = irValueArgument.elements
                if (irVarargElements.size != 1 || irVarargElements[0] !is IrSpreadElement) return false
            }
        }
        return true
    }

    private class AdaptedCallableReference(
        val irReferenceExpression: IrExpression,
        val irAdapteeCall: IrFunctionAccessExpression,
        val hasResultAdaptation: Boolean
    )

    private fun generateAdaptedCallableReference(
        ktCallableReference: KtCallableReferenceExpression,
        callBuilder: CallBuilder
    ): AdaptedCallableReference {
        val adapteeDescriptor = callBuilder.descriptor
        if (adapteeDescriptor !is FunctionDescriptor) {
            throw AssertionError("Function descriptor expected in adapted callable reference: $adapteeDescriptor")
        }

        val startOffset = ktCallableReference.startOffsetSkippingComments
        val endOffset = ktCallableReference.endOffset

        val adapteeSymbol = context.symbolTable.referenceFunction(adapteeDescriptor.original)

        val ktFunctionalType = getTypeInferredByFrontendOrFail(ktCallableReference)
        val irFunctionalType = ktFunctionalType.toIrType()

        val ktFunctionalTypeArguments = ktFunctionalType.arguments
        val ktExpectedReturnType = ktFunctionalTypeArguments.last().type
        val ktExpectedParameterTypes = ktFunctionalTypeArguments.take(ktFunctionalTypeArguments.size - 1).map { it.type }

        val irAdapterFun = createAdapterFun(startOffset, endOffset, adapteeDescriptor, ktExpectedParameterTypes, ktExpectedReturnType)
        val (irCall, irAdapteeCallInner) =
            createAdapteeCall(startOffset, endOffset, ktCallableReference, adapteeSymbol, callBuilder, irAdapterFun)

        irAdapterFun.body = IrBlockBodyImpl(startOffset, endOffset).apply {
            if (KotlinBuiltIns.isUnit(ktExpectedReturnType))
                statements.add(irCall)
            else
                statements.add(IrReturnImpl(startOffset, endOffset, context.irBuiltIns.nothingType, irAdapterFun.symbol, irCall))
        }

        val irBlock = IrBlockImpl(startOffset, endOffset, irFunctionalType).apply {
            statements.add(irAdapterFun)
            statements.add(
                IrFunctionReferenceImpl(
                    startOffset, endOffset,
                    irFunctionalType,
                    irAdapterFun.symbol,
                    0,
                    irAdapterFun.valueParameters.size
                )
            )
        }

        return AdaptedCallableReference(
            irBlock, irAdapteeCallInner,
            KotlinBuiltIns.isUnit(ktExpectedReturnType) && !KotlinBuiltIns.isUnit(adapteeDescriptor.returnType!!)
        )
    }

    private fun createAdapteeCall(
        startOffset: Int,
        endOffset: Int,
        ktCallableReference: KtCallableReferenceExpression,
        adapteeSymbol: IrFunctionSymbol,
        callBuilder: CallBuilder,
        irAdapterFun: IrSimpleFunction
    ): Pair<IrExpression, IrFunctionAccessExpression> {
        val resolvedCall = callBuilder.original
        val resolvedDescriptor = resolvedCall.resultingDescriptor

        var irAdapteeCall: IrFunctionAccessExpression? = null
        val irCall = statementGenerator.generateCallReceiver(
            ktCallableReference,
            resolvedDescriptor,
            resolvedCall.dispatchReceiver, resolvedCall.extensionReceiver,
            isSafe = false
        ).call { dispatchReceiverValue, extensionReceiverValue ->
            val irType = resolvedDescriptor.returnType!!.toIrType()

            val irAdapteeCallInner =
                if (resolvedDescriptor is ConstructorDescriptor)
                    IrConstructorCallImpl.fromSymbolDescriptor(
                        startOffset, endOffset, irType,
                        adapteeSymbol as IrConstructorSymbol
                    )
                else
                    IrCallImpl(
                        startOffset, endOffset, irType,
                        adapteeSymbol,
                        origin = null, superQualifierSymbol = null
                    )

            context.callToSubstitutedDescriptorMap[irAdapteeCallInner] = resolvedDescriptor

            irAdapteeCallInner.dispatchReceiver = dispatchReceiverValue?.load()
            irAdapteeCallInner.extensionReceiver = extensionReceiverValue?.load()

            irAdapteeCallInner.putTypeArguments(callBuilder.typeArguments) { it.toIrType() }

            putAdaptedValueArguments(startOffset, endOffset, irAdapteeCallInner, irAdapterFun, resolvedCall)

            irAdapteeCall = irAdapteeCallInner

            irAdapteeCallInner
        }

        return Pair(irCall, irAdapteeCall!!)
    }

    private fun putAdaptedValueArguments(
        startOffset: Int,
        endOffset: Int,
        irAdapteeCall: IrFunctionAccessExpression,
        irAdapterFun: IrSimpleFunction,
        resolvedCall: ResolvedCall<*>
    ) {
        val adaptedArguments = resolvedCall.valueArguments
        if (adaptedArguments.isEmpty()) {
            throw AssertionError("Callable reference with adapted arguments expected: ${resolvedCall.call.callElement.text}")
        }

        for ((valueParameter, valueArgument) in adaptedArguments) {
            irAdapteeCall.putValueArgument(
                valueParameter.index,
                adaptResolvedValueArgument(startOffset, endOffset, valueArgument, irAdapterFun, valueParameter)
            )
        }
    }

    private fun adaptResolvedValueArgument(
        startOffset: Int,
        endOffset: Int,
        resolvedValueArgument: ResolvedValueArgument,
        irAdapterFun: IrSimpleFunction,
        valueParameter: ValueParameterDescriptor
    ): IrExpression? =
        when (resolvedValueArgument) {
            is DefaultValueArgument ->
                null
            is VarargValueArgument ->
                IrVarargImpl(
                    startOffset, endOffset,
                    valueParameter.type.toIrType(), valueParameter.varargElementType!!.toIrType(),
                    resolvedValueArgument.arguments.map {
                        adaptValueArgument(startOffset, endOffset, it, irAdapterFun)
                    }
                )
            is ExpressionValueArgument -> {
                val valueArgument = resolvedValueArgument.valueArgument!!

                adaptValueArgument(startOffset, endOffset, valueArgument, irAdapterFun) as IrExpression
            }
            else ->
                throw AssertionError("Unexpected ResolvedValueArgument: $resolvedValueArgument")
        }

    private fun adaptValueArgument(
        startOffset: Int,
        endOffset: Int,
        valueArgument: ValueArgument,
        irAdapterFun: IrSimpleFunction
    ): IrVarargElement =
        when (valueArgument) {
            is FakeImplicitSpreadValueArgumentForCallableReference ->
                IrSpreadElementImpl(
                    startOffset, endOffset,
                    adaptValueArgument(startOffset, endOffset, valueArgument.expression, irAdapterFun) as IrExpression
                )

            is FakePositionalValueArgumentForCallableReference -> {
                val irAdapterParameter = irAdapterFun.valueParameters[valueArgument.index]
                IrGetValueImpl(startOffset, endOffset, irAdapterParameter.type, irAdapterParameter.symbol)
            }

            else ->
                throw AssertionError("Unexpected ValueArgument: $valueArgument")
        }

    private fun createAdapterFun(
        startOffset: Int,
        endOffset: Int,
        adapteeDescriptor: FunctionDescriptor,
        ktExpectedParameterTypes: List<KotlinType>,
        ktExpectedReturnType: KotlinType
    ): IrSimpleFunction {
        val adapterFunctionDescriptor = WrappedSimpleFunctionDescriptor()
        val adapterOrigin = IrDeclarationOrigin.DEFINED // TODO special declaration origin for callable reference adapter?

        return context.symbolTable.declareSimpleFunction(
            startOffset, endOffset, adapterOrigin, adapterFunctionDescriptor
        ) { irAdapterSymbol ->
            IrFunctionImpl(
                startOffset, endOffset, adapterOrigin,
                irAdapterSymbol,
                adapteeDescriptor.name,
                Visibilities.LOCAL,
                Modality.FINAL,
                ktExpectedReturnType.toIrType(),
                isInline = adapteeDescriptor.isInline, // TODO ?
                isExternal = false,
                isTailrec = false,
                isSuspend = adapteeDescriptor.isSuspend, // TODO ?
                isOperator = adapteeDescriptor.isOperator, // TODO ?
                isExpect = false,
                isFakeOverride = false
            ).also { irAdapterFun ->
                adapterFunctionDescriptor.bind(irAdapterFun)

                context.symbolTable.withScope(adapterFunctionDescriptor) {
                    irAdapterFun.metadata = MetadataSource.Function(adapteeDescriptor)

                    irAdapterFun.dispatchReceiverParameter = null
                    irAdapterFun.extensionReceiverParameter = null

                    ktExpectedParameterTypes.mapIndexedTo(irAdapterFun.valueParameters) { index, ktExpectedParameterType ->
                        val adapterValueParameterDescriptor = WrappedValueParameterDescriptor()
                        context.symbolTable.declareValueParameter(
                            startOffset, endOffset, adapterOrigin, adapterValueParameterDescriptor,
                            ktExpectedParameterType.toIrType()
                        ) { irAdapterParameterSymbol ->
                            IrValueParameterImpl(
                                startOffset, endOffset, adapterOrigin,
                                irAdapterParameterSymbol,
                                Name.identifier("p$index"),
                                index,
                                ktExpectedParameterType.toIrType(),
                                varargElementType = null, isCrossinline = false, isNoinline = false
                            ).also { irAdapterValueParameter ->
                                adapterValueParameterDescriptor.bind(irAdapterValueParameter)
                            }
                        }
                    }
                }
            }
        }
    }

    fun generateCallableReference(
        ktElement: KtElement,
        type: KotlinType,
        callableDescriptor: CallableDescriptor,
        typeArguments: Map<TypeParameterDescriptor, KotlinType>?,
        origin: IrStatementOrigin? = null
    ): IrCallableReference {
        val startOffset = ktElement.startOffsetSkippingComments
        val endOffset = ktElement.endOffset
        return when (callableDescriptor) {
            is FunctionDescriptor -> {
                val symbol = context.symbolTable.referenceFunction(callableDescriptor.original)
                generateFunctionReference(startOffset, endOffset, type, symbol, callableDescriptor, typeArguments, origin)
            }
            is PropertyDescriptor -> {
                val mutable = get(BindingContext.VARIABLE, ktElement)?.isVar ?: true
                generatePropertyReference(startOffset, endOffset, type, callableDescriptor, typeArguments, origin, mutable)
            }
            else ->
                throw AssertionError("Unexpected callable reference: $callableDescriptor")
        }
    }

    fun generateLocalDelegatedPropertyReference(
        startOffset: Int,
        endOffset: Int,
        type: KotlinType,
        variableDescriptor: VariableDescriptorWithAccessors,
        irDelegateSymbol: IrVariableSymbol,
        origin: IrStatementOrigin?
    ): IrLocalDelegatedPropertyReference {
        val getterDescriptor =
            variableDescriptor.getter ?: throw AssertionError("Local delegated property should have a getter: $variableDescriptor")
        val setterDescriptor = variableDescriptor.setter

        val getterSymbol = context.symbolTable.referenceSimpleFunction(getterDescriptor)
        val setterSymbol = setterDescriptor?.let { context.symbolTable.referenceSimpleFunction(it) }

        return IrLocalDelegatedPropertyReferenceImpl(
            startOffset, endOffset, type.toIrType(),
            context.symbolTable.referenceLocalDelegatedProperty(variableDescriptor),
            irDelegateSymbol, getterSymbol, setterSymbol,
            origin
        ).apply {
            context.callToSubstitutedDescriptorMap[this] = variableDescriptor
        }
    }

    private fun generatePropertyReference(
        startOffset: Int,
        endOffset: Int,
        type: KotlinType,
        propertyDescriptor: PropertyDescriptor,
        typeArguments: Map<TypeParameterDescriptor, KotlinType>?,
        origin: IrStatementOrigin?,
        mutable: Boolean
    ): IrPropertyReference {
        val originalProperty = propertyDescriptor.original
        val originalGetter = originalProperty.getter?.original
        val originalSetter = if (mutable) originalProperty.setter?.original else null
        val originalSymbol = context.symbolTable.referenceProperty(originalProperty)

        return IrPropertyReferenceImpl(
            startOffset, endOffset, type.toIrType(),
            originalSymbol,
            propertyDescriptor.typeParametersCount,
            getFieldForPropertyReference(originalProperty),
            originalGetter?.let { context.symbolTable.referenceSimpleFunction(it) },
            originalSetter?.let { context.symbolTable.referenceSimpleFunction(it) },
            origin
        ).apply {
            context.callToSubstitutedDescriptorMap[this] = propertyDescriptor
            putTypeArguments(typeArguments) { it.toIrType() }
        }
    }

    private fun getFieldForPropertyReference(originalProperty: PropertyDescriptor) =
        // NB this is a hack, we really don't know if an arbitrary property has a backing field or not
        when {
            @Suppress("DEPRECATION")
            originalProperty.isDelegated -> null
            originalProperty.getter != null -> null
            else -> context.symbolTable.referenceField(originalProperty)
        }

    private fun generateFunctionReference(
        startOffset: Int,
        endOffset: Int,
        type: KotlinType,
        symbol: IrFunctionSymbol,
        descriptor: FunctionDescriptor,
        typeArguments: Map<TypeParameterDescriptor, KotlinType>?,
        origin: IrStatementOrigin?
    ): IrFunctionReference =
        IrFunctionReferenceImpl(
            startOffset, endOffset, type.toIrType(),
            symbol, descriptor.typeParametersCount, origin
        ).apply {
            context.callToSubstitutedDescriptorMap[this] = descriptor
            putTypeArguments(typeArguments) { it.toIrType() }
        }
}