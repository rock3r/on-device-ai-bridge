// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking

internal class AppleAiToggleServerAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = AppleAiService.getInstance()
        e.presentation.isEnabledAndVisible = service.isAvailable
        if (service.isAvailable) {
            e.presentation.text =
                if (service.isRunning) {
                    AppleAiBundle.message("apple.ai.action.toggleServer.stop")
                } else {
                    AppleAiBundle.message("apple.ai.action.toggleServer.start")
                }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = AppleAiService.getInstance()
        if (service.isRunning) {
            service.stopHelper()
        } else {
            val project = e.project ?: ProjectManager.getInstance().defaultProject
            runWithModalProgressBlocking(project, AppleAiBundle.message("apple.ai.settings.start")) {
                service.startHelper()
            }
        }
    }
}
