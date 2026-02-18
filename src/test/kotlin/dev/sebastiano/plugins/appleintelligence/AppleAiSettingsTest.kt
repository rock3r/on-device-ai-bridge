// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Unit tests for [AppleAiSettings], focusing on state persistence (API key tests require platform fixtures). */
class AppleAiSettingsTest {

    private lateinit var settings: AppleAiSettings

    @Before
    fun setUp() {
        settings = AppleAiSettings()
    }

    // region Default state values

    @Test
    fun `default host is localhost`() {
        assertEquals("127.0.0.1", settings.state.host)
    }

    @Test
    fun `default port is 8080`() {
        assertEquals(8080, settings.state.port)
    }

    @Test
    fun `default autoStart is false`() {
        assertFalse(settings.state.autoStart)
    }

    @Test
    fun `default swiftHelperPath is empty`() {
        assertEquals("", settings.state.swiftHelperPath)
    }

    // endregion

    // region loadState / getState round-trip

    @Test
    fun `loadState and getState round-trip preserves all fields`() {
        val custom =
            AppleAiSettings.State(
                host = "0.0.0.0",
                port = 9090,
                autoStart = true,
                swiftHelperPath = "/usr/local/bin/helper",
            )
        settings.loadState(custom)
        val retrieved = settings.state

        assertEquals(custom.host, retrieved.host)
        assertEquals(custom.port, retrieved.port)
        assertEquals(custom.autoStart, retrieved.autoStart)
        assertEquals(custom.swiftHelperPath, retrieved.swiftHelperPath)
    }

    @Test
    fun `loadState replaces previous state entirely`() {
        val newState = AppleAiSettings.State(host = "10.0.0.1", port = 3000)
        settings.loadState(newState)

        assertEquals("10.0.0.1", settings.state.host)
        assertEquals(3000, settings.state.port)
    }

    // endregion
}
