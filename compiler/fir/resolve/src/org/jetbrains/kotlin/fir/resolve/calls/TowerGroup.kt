/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

sealed class TowerGroupKind(private val index: Int) : Comparable<TowerGroupKind> {
    object Member : TowerGroupKind(0)

    object InvokeExtension : TowerGroupKind(1)

    class Local(val depth: Int) : TowerGroupKind(2)

    class Implicit(val depth: Int) : TowerGroupKind(3)

    class Top(val depth: Int): TowerGroupKind(4)

    object Static : TowerGroupKind(5)

    override fun compareTo(other: TowerGroupKind): Int {
        return index.compareTo(other.index)
    }
}

class TowerGroup private constructor(private val list: List<TowerGroupKind>) : Comparable<TowerGroup> {
    companion object {
        private fun kindOf(kind: TowerGroupKind): TowerGroup = TowerGroup(listOf(kind))

        val Member = kindOf(TowerGroupKind.Member)

        val InvokeExtension = kindOf(TowerGroupKind.InvokeExtension)

        fun Local(depth: Int) = kindOf(TowerGroupKind.Local(depth))

        fun Implicit(depth: Int) = kindOf(TowerGroupKind.Implicit(depth))

        fun Top(depth: Int) = kindOf(TowerGroupKind.Top(depth))

        val Static = kindOf(TowerGroupKind.Static)
    }

    fun kindOf(kind: TowerGroupKind): TowerGroup = TowerGroup(list + kind)

    val Member get() = kindOf(TowerGroupKind.Member)

    val InvokeExtension get() = kindOf(TowerGroupKind.InvokeExtension)

    fun Local(depth: Int) = kindOf(TowerGroupKind.Local(depth))

    fun Implicit(depth: Int) = kindOf(TowerGroupKind.Implicit(depth))

    fun Top(depth: Int) = kindOf(TowerGroupKind.Top(depth))

    val Static get() = kindOf(TowerGroupKind.Static)

    override fun compareTo(other: TowerGroup): Int {
        var index = 0
        while (index < list.size) {
            if (index >= other.list.size) return -1
            when {
                list[index] < other.list[index] -> return -1
                list[index] > other.list[index] -> return 1
            }
            index++
        }
        if (index < other.list.size) return 1
        return 0
    }
}

fun test() {
    TowerGroup.Implicit(1).Implicit(2)
    TowerGroup.Implicit(3).Member
}