// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareToggleAction

internal class AppleAiToggleDebugLogsAction : DumbAwareToggleAction() {

    private val pluginLoggers =
        listOf(
            Logger.getInstance(AppleAiRestService::class.java),
            Logger.getInstance(AppleAiService::class.java),
            Logger.getInstance(AppleAiStartupListener::class.java),
            Logger.getInstance(SwiftHelperProcess::class.java),
        )

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = pluginLoggers.first().isTraceEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val level = if (state) LogLevel.TRACE else LogLevel.INFO
        for (logger in pluginLoggers) {
            logger.setLevel(level)
        }
    }
}
