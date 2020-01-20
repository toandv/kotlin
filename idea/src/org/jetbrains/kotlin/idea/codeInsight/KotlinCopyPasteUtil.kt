/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.codeInsight

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.ui.LightweightHint
import com.intellij.util.ArrayUtil
import org.jetbrains.kotlin.idea.imports.KotlinImportOptimizer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.ImportPath
import java.util.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

fun reviewAddedImports(
    project: Project,
    editor: Editor,
    file: KtFile,
    imported: TreeSet<String>
) {
    if (CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE == CodeInsightSettings.YES &&
        !imported.isEmpty()
    ) {
        val notificationText = CodeInsightBundle
            .message("copy.paste.reference.notification", imported.size)
        ApplicationManager.getApplication().invokeLater(
            Runnable {
                showHint(
                    editor,
                    notificationText,
                    HyperlinkListener { e: HyperlinkEvent ->
                        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                            reviewImports(project, file, imported)
                        }
                    }
                )
            }, ModalityState.NON_MODAL
        )
    }
}

private fun showHint(
    editor: Editor,
    info: String,
    hyperlinkListener: HyperlinkListener
) {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    val hint = LightweightHint(HintUtil.createInformationLabel(info, hyperlinkListener, null, null))
    val flags = HintManager.HIDE_BY_ANY_KEY or HintManager.HIDE_BY_TEXT_CHANGE
    HintManagerImpl.getInstanceImpl().showEditorHint(hint, editor, HintManager.UNDER, flags, 0, false)
}

private fun reviewImports(
    project: Project,
    file: KtFile,
    importedClasses: Set<String>
) {
    val dialog = RestoreReferencesDialog(project, ArrayUtil.toObjectArray(importedClasses))
    dialog.title = CodeInsightBundle.message("dialog.import.on.paste.title3")
    if (dialog.showAndGet()) {
        val selectedElements = dialog.selectedElements
        if (selectedElements.isNotEmpty()) {
            WriteCommandAction.runWriteCommandAction(project, "", null, Runnable {
                removeImports(
                    file,
                    selectedElements.map { it as String }.toSortedSet()
                )
            })
        }
    }
}

private fun removeImports(file: KtFile, imports: Set<String>) {
    val newImports = file.importDirectives.mapNotNull {
        val importedFqName = it.importedFqName ?: return@mapNotNull null
        if (imports.contains(importedFqName.asString())) return@mapNotNull null
        ImportPath(importedFqName, it.isAllUnder, it.importedName)
    }
    KotlinImportOptimizer.replaceImports(file, newImports)
}