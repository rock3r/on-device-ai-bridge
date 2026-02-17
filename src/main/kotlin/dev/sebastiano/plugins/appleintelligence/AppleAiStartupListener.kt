// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.launch

private val LOG = logger<AppleAiStartupListener>()

/** Automatically starts the Apple AI helper on IDE startup if configured to do so. Only activates on macOS 26+. */
internal class AppleAiStartupListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        val service = AppleAiService.getInstance()
        if (!service.isAvailable) return

        val settings = AppleAiSettings.getInstance()
        if (!settings.state.autoStart) return

        if (service.isRunning) return

        LOG.info("Auto-starting Apple AI helper...")
        service.coroutineScope.launch { service.startHelper() }
    }
}
