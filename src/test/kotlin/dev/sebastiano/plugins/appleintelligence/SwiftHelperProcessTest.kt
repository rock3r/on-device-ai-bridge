// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import dev.sebastiano.plugins.appleintelligence.ChatMessage
import dev.sebastiano.plugins.appleintelligence.SwiftHelperProcess
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Unit tests for [SwiftHelperProcess] validation, lifecycle, and error handling. */
class SwiftHelperProcessTest {

    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("swift-helper-test")
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // region start() validation

    @Test
    fun `start returns false for non-existent binary`() = runBlocking {
        val nonExistent = tempDir.resolve("does-not-exist")
        val process = SwiftHelperProcess(nonExistent)
        assertFalse("start() should return false for non-existent binary", process.start())
        assertFalse(process.isRunning)
    }

    @Test
    fun `start returns false for non-executable file`() = runBlocking {
        val file = Files.createFile(tempDir.resolve("not-executable"))
        Files.write(file, "not a binary".toByteArray())
        file.toFile().setExecutable(false)
        val process = SwiftHelperProcess(file)
        assertFalse("start() should return false for non-executable file", process.start())
        assertFalse(process.isRunning)
    }

    // endregion

    // region isRunning initial state

    @Test
    fun `isRunning is false initially`() {
        val process = SwiftHelperProcess(tempDir.resolve("anything"))
        assertFalse(process.isRunning)
    }

    // endregion

    // region stop() cleanup

    @Test
    fun `stop sets isRunning to false`() {
        val process = SwiftHelperProcess(tempDir.resolve("anything"))
        // Even without starting, stop should not throw and should leave isRunning false
        process.stop()
        assertFalse(process.isRunning)
    }

    @Test
    fun `stop is idempotent`() {
        val process = SwiftHelperProcess(tempDir.resolve("anything"))
        process.stop()
        process.stop() // second call should not throw
        assertFalse(process.isRunning)
    }

    // endregion

    // region generate() when not running

    @Test
    fun `generate returns error when not running`() = runBlocking {
        val process = SwiftHelperProcess(tempDir.resolve("anything"))
        val response = process.generate(listOf(ChatMessage(role = "user", content = "hello")))
        assertEquals("error", response.type)
        assertEquals("Process not running", response.error)
    }

    // endregion

    // region checkStatus() when not running

    @Test
    fun `checkStatus returns status with modelAvailable false when not running`() = runBlocking {
        val process = SwiftHelperProcess(tempDir.resolve("anything"))
        val response = process.checkStatus()
        assertEquals("status", response.type)
        assertEquals(false, response.modelAvailable)
        assertEquals("Process not running", response.reason)
    }

    // endregion

    // region dispose()

    @Test
    fun `dispose calls stop without throwing`() {
        val process = SwiftHelperProcess(tempDir.resolve("anything"))
        process.dispose() // should not throw
        assertFalse(process.isRunning)
    }

    // endregion
}
