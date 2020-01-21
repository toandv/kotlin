/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jvm.internal

import kotlin.reflect.*

@SinceKotlin("1.4")
public class TypeReference(
    override val classifier: KClassifier,
    override val arguments: List<KTypeProjection>,
    override val isMarkedNullable: Boolean
) : KType {
    override val annotations: List<Annotation>
        get() = emptyList()

    override fun equals(other: Any?): Boolean =
        other is TypeReference &&
                classifier == other.classifier && arguments == other.arguments && isMarkedNullable == other.isMarkedNullable

    override fun hashCode(): Int =
        (classifier.hashCode() * 31 + arguments.hashCode()) * 31 + isMarkedNullable.hashCode()

    override fun toString(): String {
        val javaClass = (classifier as? KClass<*>)?.java
        val klass = when {
            javaClass == null -> classifier.toString()
            javaClass.isArray -> javaClass.arrayClassName
            else -> javaClass.name
        }
        val args =
            if (arguments.isEmpty()) ""
            else arguments.joinToString(", ", "<", ">") { it.toString().removeSuffix(Reflection.REFLECTION_NOT_AVAILABLE) }
        val nullable = if (isMarkedNullable) "?" else ""

        return klass + args + nullable + Reflection.REFLECTION_NOT_AVAILABLE
    }

    private val Class<*>.arrayClassName
        get() = when (this) {
            BooleanArray::class.java -> "kotlin.BooleanArray"
            CharArray::class.java -> "kotlin.CharArray"
            ByteArray::class.java -> "kotlin.ByteArray"
            ShortArray::class.java -> "kotlin.ShortArray"
            IntArray::class.java -> "kotlin.IntArray"
            FloatArray::class.java -> "kotlin.FloatArray"
            LongArray::class.java -> "kotlin.LongArray"
            DoubleArray::class.java -> "kotlin.DoubleArray"
            else -> "kotlin.Array"
        }
}
