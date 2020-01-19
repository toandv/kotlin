/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.statistics.metrics

import org.jetbrains.kotlin.statistics.metrics.NumberAnonymizationPolicy.*
import org.jetbrains.kotlin.statistics.metrics.NumberOverridePolicy.*


enum class NumericalMetrics(val type: NumberOverridePolicy, val anonymization: NumberAnonymizationPolicy, val perProject: Boolean = false) {


    // User environment
    // Number of CPU cores. No other information (e.g. env.PROCESSOR_IDENTIFIER is not reported)
    CPU_NUMBER_OF_CORES(OVERRIDE, SAFE),

    //TODO which artefact?
    ARTEFACTS_DOWNLOAD_SPEED(OVERRIDE, RANDOM_10_PERCENT),

    // Build script


    // Component versions


    // Enabled features

    // Build performance


    // Unprocessed



    // duration of the whole gradle build
    GRADLE_BUILD_DURATION(OVERRIDE, SAFE),

    //duration of the execution gradle phase
    GRADLE_EXECUTION_DURATION(OVERRIDE, SAFE),

    NUMBER_OF_SUBPROJECTS(OVERRIDE, RANDOM_10_PERCENT),

    GRADLE_DAEMON_HEAP_SIZE(OVERRIDE, RANDOM_10_PERCENT),

    GRADLE_BUILD_NUMBER_IN_CURRENT_DAEMON(OVERRIDE, SAFE),

    GRADLE_NUMBER_OF_TASKS(OVERRIDE, SAFE),

    GRADLE_NUMBER_OF_UNCONFIGURED_TASKS(OVERRIDE, SAFE),

    SOME_LONG_METRIC(SUM, RANDOM_10_PERCENT),

    TEST_LONG_METRIC(OVERRIDE, SAFE)
}

