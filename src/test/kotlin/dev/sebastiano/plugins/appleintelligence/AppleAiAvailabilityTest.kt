// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import dev.sebastiano.plugins.appleintelligence.MIN_MACOS_MAJOR_VERSION
import dev.sebastiano.plugins.appleintelligence.parseMacOSMajorVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [parseMacOSMajorVersion] and the version-related availability logic. */
class AppleAiAvailabilityTest {

    // region parseMacOSMajorVersion — typical macOS versions

    @Test
    fun `parses macOS 15 dot 2`() {
        assertEquals(15, parseMacOSMajorVersion("15.2"))
    }

    @Test
    fun `parses macOS 26 dot 0`() {
        assertEquals(26, parseMacOSMajorVersion("26.0"))
    }

    @Test
    fun `parses macOS 26 dot 1`() {
        assertEquals(26, parseMacOSMajorVersion("26.1"))
    }

    @Test
    fun `parses macOS 27 dot 0`() {
        assertEquals(27, parseMacOSMajorVersion("27.0"))
    }

    @Test
    fun `parses macOS 14 dot 5`() {
        assertEquals(14, parseMacOSMajorVersion("14.5"))
    }

    // endregion

    // region parseMacOSMajorVersion — patch versions

    @Test
    fun `parses three-component version`() {
        assertEquals(26, parseMacOSMajorVersion("26.1.2"))
    }

    @Test
    fun `parses four-component version`() {
        assertEquals(15, parseMacOSMajorVersion("15.3.1.0"))
    }

    // endregion

    // region parseMacOSMajorVersion — major-only

    @Test
    fun `parses major-only version string`() {
        assertEquals(26, parseMacOSMajorVersion("26"))
    }

    // endregion

    // region parseMacOSMajorVersion — edge cases

    @Test
    fun `returns null for empty string`() {
        assertNull(parseMacOSMajorVersion(""))
    }

    @Test
    fun `returns null for non-numeric string`() {
        assertNull(parseMacOSMajorVersion("abc"))
    }

    @Test
    fun `returns null for non-numeric major with valid minor`() {
        assertNull(parseMacOSMajorVersion("abc.1"))
    }

    @Test
    fun `returns null for whitespace-only string`() {
        assertNull(parseMacOSMajorVersion("   "))
    }

    @Test
    fun `parses version with leading zeros`() {
        // "026" parses to 26 in Kotlin's toIntOrNull
        assertEquals(26, parseMacOSMajorVersion("026.0"))
    }

    @Test
    fun `returns zero for zero major version`() {
        assertEquals(0, parseMacOSMajorVersion("0.1"))
    }

    // endregion

    // region MIN_MACOS_MAJOR_VERSION constant

    @Test
    fun `minimum macOS major version is 26`() {
        assertEquals(26, MIN_MACOS_MAJOR_VERSION)
    }

    // endregion

    // region version sufficiency boundary

    @Test
    fun `version 25 is below minimum`() {
        val major = parseMacOSMajorVersion("25.0")!!
        assert(major < MIN_MACOS_MAJOR_VERSION) { "25 should be below the minimum" }
    }

    @Test
    fun `version 26 meets minimum`() {
        val major = parseMacOSMajorVersion("26.0")!!
        assert(major >= MIN_MACOS_MAJOR_VERSION) { "26 should meet the minimum" }
    }

    @Test
    fun `version 27 exceeds minimum`() {
        val major = parseMacOSMajorVersion("27.0")!!
        assert(major >= MIN_MACOS_MAJOR_VERSION) { "27 should exceed the minimum" }
    }

    // endregion
}
