/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tools.projectWizard.wizard.service

import org.jetbrains.kotlin.idea.KotlinPluginUtil
import org.jetbrains.kotlin.idea.framework.ui.ConfigureDialogWithModulesAndVersion
import org.jetbrains.kotlin.tools.projectWizard.core.asNullable
import org.jetbrains.kotlin.tools.projectWizard.core.safe
import org.jetbrains.kotlin.tools.projectWizard.core.service.KotlinVersionKind
import org.jetbrains.kotlin.tools.projectWizard.core.service.KotlinVersionProviderService
import org.jetbrains.kotlin.tools.projectWizard.core.service.KotlinVersionProviderServiceImpl
import org.jetbrains.kotlin.tools.projectWizard.core.service.kotlinVersionKind
import org.jetbrains.kotlin.tools.projectWizard.settings.version.Version

class IdeaKotlinVersionProviderService : KotlinVersionProviderService, IdeaWizardService {
    override fun getKotlinVersions(): List<Version> {
        if (KotlinPluginUtil.isSnapshotVersion()) {
            return listOf(getLatestStableOrDefault())
        }
        val version = Version.fromString(KotlinPluginUtil.getPluginVersion())
        if (version.kotlinVersionKind == KotlinVersionKind.STABLE) return listOf(version)
        return listOf(version, getLatestStableOrDefault())
    }

    private fun getListOfVersions() = safe {
        ConfigureDialogWithModulesAndVersion
            .loadVersions(MINIMUM_KOTLIN_VERSION_TO_LOAD)
            .map(Version.Companion::fromString)
    }.asNullable

    private fun getLatestStableOrDefault() =
        getListOfVersions()?.firstOrNull()
            ?: KotlinVersionProviderServiceImpl.DEFAULT

    companion object {
        private const val MINIMUM_KOTLIN_VERSION_TO_LOAD = "1.3.61"
    }
}