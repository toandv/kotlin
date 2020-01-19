/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tools.projectWizard.wizard.ui.firstStep

import org.jetbrains.kotlin.tools.projectWizard.core.ValuesReadingContext
import org.jetbrains.kotlin.tools.projectWizard.core.entity.DropDownSettingType
import org.jetbrains.kotlin.tools.projectWizard.core.entity.VersionSettingType
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.KotlinPlugin
import org.jetbrains.kotlin.tools.projectWizard.settings.version.Version
import org.jetbrains.kotlin.tools.projectWizard.wizard.ui.setting.UIComponentDelegatingSettingComponent
import org.jetbrains.kotlin.tools.projectWizard.core.entity.reference
import org.jetbrains.kotlin.tools.projectWizard.wizard.ui.components.DropDownComponent
import org.jetbrains.kotlin.tools.projectWizard.wizard.ui.components.UIComponent

class KotlinVersionSettingComponent(
    valuesReadingContext: ValuesReadingContext
) : UIComponentDelegatingSettingComponent<Version, VersionSettingType>(
    KotlinPlugin::version.reference,
    valuesReadingContext
) {
    override val uiComponent: DropDownComponent<Version> = DropDownComponent(
        valuesReadingContext,
        labelText = setting.title,
        onValueUpdate = { value = it }
    )

    override fun onInit() {
        super.onInit()
        val versions = read { KotlinPlugin::kotlinVersions.propertyValue }
        uiComponent.setValues(versions)
        if (value == null && versions.isNotEmpty()) {
            value = versions.first()
        }
    }
}
