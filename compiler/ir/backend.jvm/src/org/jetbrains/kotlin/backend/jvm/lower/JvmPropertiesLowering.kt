/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.transformDeclarationsFlat
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.Name
import java.util.ArrayList

class PropertiesLowering2(
    private val context: BackendContext,
    private val originOfSyntheticMethodForAnnotations: IrDeclarationOrigin? = null,
    private val skipExternalProperties: Boolean = false,
    private val computeSyntheticMethodName: ((IrProperty) -> String)? = null
) : IrElementTransformerVoid(), FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.accept(this, null)
    }

    override fun visitFile(declaration: IrFile): IrFile {
        declaration.transformChildrenVoid(this)
        declaration.transformDeclarationsFlat { lowerProperty(it, ClassKind.CLASS) }
        return declaration
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        declaration.transformChildrenVoid(this)
        declaration.transformDeclarationsFlat { lowerProperty(it, declaration.kind) }
        return declaration
    }

    override fun visitScript(declaration: IrScript): IrStatement {
        declaration.transformChildrenVoid(this)
        declaration.transformDeclarationsFlat { lowerProperty(it, ClassKind.CLASS) }
        return declaration
    }

    private fun lowerProperty(declaration: IrDeclaration, kind: ClassKind): List<IrDeclaration>? =
        if (declaration is IrProperty)
            if (skipExternalProperties && declaration.isEffectivelyExternal()) listOf(declaration) else {
                ArrayList<IrDeclaration>(4).apply {
                    // JvmFields in a companion object refer to companion's owners and should not be generated within companion.
                    if (declaration.backingField?.hasAnnotation(JvmAbi.JVM_FIELD_ANNOTATION_FQ_NAME) != true) {
                        add(declaration)
                        if (kind != ClassKind.ANNOTATION_CLASS && declaration.backingField?.parent != declaration.parent) {
                            declaration.backingField = null
                        }
                    }

                    if (declaration.annotations.isNotEmpty() && originOfSyntheticMethodForAnnotations != null
                        && computeSyntheticMethodName != null
                    ) {
                        val methodName = computeSyntheticMethodName.invoke(declaration) // Workaround KT-4113
                        add(createSyntheticMethodForAnnotations(declaration, originOfSyntheticMethodForAnnotations, methodName))
                    }
                }
            }
        else
            null

    private fun createSyntheticMethodForAnnotations(declaration: IrProperty, origin: IrDeclarationOrigin, name: String): IrFunctionImpl {
        val descriptor = WrappedSimpleFunctionDescriptor(declaration.descriptor.annotations)
        val symbol = IrSimpleFunctionSymbolImpl(descriptor)
        return IrFunctionImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, symbol, Name.identifier(name),
            declaration.visibility, Modality.OPEN, context.irBuiltIns.unitType,
            isInline = false, isExternal = false, isTailrec = false, isSuspend = false, isExpect = false, isFakeOverride = false,
            isOperator = false
        ).apply {
            descriptor.bind(this)

            val extensionReceiver = declaration.getter?.extensionReceiverParameter
            if (extensionReceiver != null) {
                // Use raw type of extension receiver to avoid generic signature, which would be useless for this method.
                extensionReceiverParameter = extensionReceiver.copyTo(this, type = extensionReceiver.type.classifierOrFail.typeWith())
            }

            body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

            annotations.addAll(declaration.annotations)
            metadata = declaration.metadata
        }
    }
}
