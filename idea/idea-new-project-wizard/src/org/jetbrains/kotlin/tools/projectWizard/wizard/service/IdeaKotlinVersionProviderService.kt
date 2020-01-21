/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tools.projectWizard.wizard.service

import com.intellij.ide.util.PropertiesComponent
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
        val version = getCurrentKotlinVersion() ?: return listOf(getLatestStableOrDefault())
        if (version.kotlinVersionKind == KotlinVersionKind.STABLE) return listOf(version)
        return listOf(version, getLatestStableOrDefault())
    }

    override fun getCurrentKotlinVersion(): Version? =
        KotlinLatestStableVersionService.getCurrentKotlinVersion()?.let(Version.Companion::fromString)

    private fun getLatestStableOrDefault() =
        KotlinLatestStableVersionService.getLatestStableKotlinVersion()
            ?.let(Version.Companion::fromString)
            ?: KotlinVersionProviderServiceImpl.DEFAULT
}

private object KotlinLatestStableVersionService {
    private const val LATEST_STABLE_KOTLIN_VERSION_OPTION_NAME = "kotlin.wizard.latest.stable.version"
    private const val LATEST_STABLE_KOTLIN_VERSION_SAVE_ON_VERSION_OPTION_NAME = "kotlin.wizard.latest.stable.version.saved.on"
    private const val MINIMUM_KOTLIN_VERSION_TO_LOAD = "1.3.61"

    fun getLatestStableKotlinVersion(): String? {
        val cached = PropertiesComponent.getInstance()
            .getValue(LATEST_STABLE_KOTLIN_VERSION_OPTION_NAME)
            .takeIf { isCachedValueValid() }
        if (cached != null) return cached
        val latestStable = getListOfVersions()?.firstOrNull() ?: return null
        PropertiesComponent.getInstance().setValue(LATEST_STABLE_KOTLIN_VERSION_OPTION_NAME, latestStable)
        getCurrentKotlinVersion()?.let { current ->
            PropertiesComponent.getInstance().setValue(LATEST_STABLE_KOTLIN_VERSION_SAVE_ON_VERSION_OPTION_NAME, current)
        }
        return latestStable
    }

    fun getCurrentKotlinVersion(): String? {
        if (KotlinPluginUtil.isSnapshotVersion()) return null
        return KotlinPluginUtil.getPluginVersion()
    }

    private fun getListOfVersions() = safe {
        ConfigureDialogWithModulesAndVersion.loadVersions(MINIMUM_KOTLIN_VERSION_TO_LOAD)
    }.asNullable

    private fun isCachedValueValid(): Boolean {
        val pluginVersion = getCurrentKotlinVersion() ?: return false
        val cachedVersionSavedOn = PropertiesComponent.getInstance()
            .getValue(LATEST_STABLE_KOTLIN_VERSION_SAVE_ON_VERSION_OPTION_NAME)
            ?: return false
        return cachedVersionSavedOn == pluginVersion
    }
}