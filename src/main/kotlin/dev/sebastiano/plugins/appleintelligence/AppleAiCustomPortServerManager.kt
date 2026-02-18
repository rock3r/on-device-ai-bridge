// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import org.jetbrains.io.CustomPortServerManagerBase

internal class AppleAiCustomPortServerManager : CustomPortServerManagerBase() {
    override val port: Int
        get() {
            val service = AppleAiService.getInstance()
            return if (service.isRunning) AppleAiSettings.getInstance().state.port else -1
        }

    override val isAvailableExternally: Boolean
        get() = AppleAiSettings.getInstance().state.host != "127.0.0.1"

    override fun cannotBind(e: Exception, port: Int) {
        // Port binding failed — logged by the built-in server infrastructure
    }
}
