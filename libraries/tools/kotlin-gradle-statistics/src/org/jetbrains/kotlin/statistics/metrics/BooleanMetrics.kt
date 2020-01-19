/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.statistics.metrics

import org.jetbrains.kotlin.statistics.metrics.BooleanAnonymizationPolicy.*
import org.jetbrains.kotlin.statistics.metrics.BooleanOverridePolicy.*


enum class BooleanMetrics(val type: BooleanOverridePolicy, val anonymization: BooleanAnonymizationPolicy, val perProject: Boolean = false) {

    // User environment

    // whether the build is executed from IDE or from console
    EXECUTED_FROM_IDE(OVERRIDE, SAFE),


    // Build script

    //annotation processors

    //TODO a lot of annotation processors
    ENABLED_KAPT(OR, SAFE),
    ENABLED_KOTLIN_ANDROID_EXTENSIOONS(OR, SAFE), // includes, e.g. Parcelize
    ENABLES_DAGGER(OR, SAFE),
    //kapt "com.android.databinding:compiler:3.0.1"
    //TODO annotation processors: Kapt, Dagger, Data Binding, Parselize,
    /*
    https://awesomeopensource.com/project/androidannotations/androidannotations
    https://awesomeopensource.com/project/airbnb/DeepLinkDispatch
    https://awesomeopensource.com/project/johncarl81/parceler
    https://awesomeopensource.com/project/immutables/immutables
    https://awesomeopensource.com/project/mapstruct/mapstruct
    https://awesomeopensource.com/project/janishar/PlaceHolderView
    https://awesomeopensource.com/project/MatthiasRobbers/shortbread
     */
    //TODO a lot of annotation processors

    //TODO compiler plugins:
    //All-open
    //no-arg
    //jpa-support
    //SAM-with-receiver

    HMPP_ENABLED(OR, SAFE),


    // Component versions


    // Enabled features

    // Build performance


    // Unprocessed

}
