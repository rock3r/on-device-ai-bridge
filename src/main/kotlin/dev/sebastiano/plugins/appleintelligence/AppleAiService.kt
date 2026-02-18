// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.system.OS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

private val LOG = logger<AppleAiService>()

internal const val MIN_MACOS_MAJOR_VERSION = 26

@JvmSynthetic
internal fun parseMacOSMajorVersion(osVersion: String): Int? = osVersion.substringBefore('.').toIntOrNull()

private fun reportProgress(@Nls text: String) {
    ProgressManager.getInstance().progressIndicator?.text = text
}

/**
 * Central service managing the Apple on-device AI integration. Coordinates the Swift helper process lifecycle and
 * provides the API for the REST endpoint.
 */
@Suppress("IO_FILE_USAGE")
@Service(Service.Level.APP)
internal class AppleAiService(val coroutineScope: CoroutineScope) : Disposable {

    @Volatile private var swiftHelper: SwiftHelperProcess? = null
    private val httpServer = AppleAiHttpServer()

    @get:Nls
    val unavailabilityReason: String?
        get() =
            when {
                OS.CURRENT != OS.macOS -> AppleAiBundle.message("apple.ai.requirement.macos")
                !isMacOSVersionSufficient() ->
                    AppleAiBundle.message("apple.ai.requirement.macos.version", MIN_MACOS_MAJOR_VERSION)
                else -> null
            }

    val isAvailable: Boolean
        get() = unavailabilityReason == null

    val isRunning: Boolean
        get() = swiftHelper?.isRunning == true

    suspend fun startHelper(): Boolean {
        if (!isAvailable) {
            notify(AppleAiBundle.message("apple.ai.notification.macos.only"), NotificationType.WARNING)
            return false
        }

        val settings = AppleAiSettings.getInstance().state
        val binaryPath = settings.swiftHelperPath.ifBlank { findDefaultHelperPath()?.toString() ?: "" }

        LOG.info("Starting Apple AI helper with binary: $binaryPath")
        if (binaryPath.isBlank()) {
            LOG.warn("Swift helper binary path is blank")
            notify(AppleAiBundle.message("apple.ai.notification.helper.not.found"), NotificationType.ERROR)
            return false
        }

        val binaryFile = Path.of(binaryPath).toFile()
        if (!binaryFile.exists()) {
            LOG.warn("Swift helper binary does not exist: $binaryPath")
            notify(AppleAiBundle.message("apple.ai.notification.helper.not.found"), NotificationType.ERROR)
            return false
        }

        val helper = SwiftHelperProcess(Path.of(binaryPath))
        return if (helper.start()) {
            swiftHelper = helper
            try {
                httpServer.start(settings.host, settings.port)
            } catch (e: Exception) {
                LOG.error("Failed to start HTTP server, stopping helper", e)
                helper.stop()
                swiftHelper = null
                notify(
                    AppleAiBundle.message("apple.ai.notification.error", e.message ?: "Failed to bind server port"),
                    NotificationType.ERROR,
                )
                return false
            }
            notify(
                AppleAiBundle.message("apple.ai.notification.started", settings.host, settings.port.toString()),
                NotificationType.INFORMATION,
            )
            true
        } else {
            val binary = Path.of(binaryPath)
            val exists = Files.exists(binary)
            val executable = Files.isExecutable(binary)
            LOG.error("Failed to start Swift helper. Path: $binaryPath, exists: $exists, executable: $executable")
            notify(
                AppleAiBundle.message(
                    "apple.ai.notification.error",
                    "Failed to start helper process. Check IDE logs for details.",
                ),
                NotificationType.ERROR,
            )
            false
        }
    }

    fun stopHelper() {
        httpServer.stop()
        swiftHelper?.stop()
        swiftHelper = null
        notify(AppleAiBundle.message("apple.ai.notification.stopped"), NotificationType.INFORMATION)
    }

    suspend fun buildHelper(destination: Path): Boolean {
        if (!isAvailable) return false

        reportProgress(AppleAiBundle.message("apple.ai.build.progress.preparing"))
        val tempDir = createTempDir() ?: return false

        try {
            reportProgress(AppleAiBundle.message("apple.ai.build.progress.extracting"))
            if (!extractSwiftSources(tempDir)) {
                notify(
                    AppleAiBundle.message("apple.ai.notification.build.failed.source.not.found"),
                    NotificationType.ERROR,
                )
                return false
            }

            reportProgress(AppleAiBundle.message("apple.ai.build.progress.building"))
            val buildOutput = runSwiftBuild(tempDir) ?: return false
            if (buildOutput.exitCode != 0) {
                LOG.error("Swift build failed with exit code ${buildOutput.exitCode}:\n${buildOutput.output}")
                notify(
                    AppleAiBundle.message("apple.ai.notification.build.failed", buildOutput.exitCode),
                    NotificationType.ERROR,
                )
                return false
            }

            reportProgress(AppleAiBundle.message("apple.ai.build.progress.copying"))
            if (!copyBuiltBinary(tempDir, destination)) {
                notify(
                    AppleAiBundle.message("apple.ai.notification.build.failed.binary.not.found"),
                    NotificationType.ERROR,
                )
                return false
            }

            notify(
                AppleAiBundle.message("apple.ai.notification.build.success", destination),
                NotificationType.INFORMATION,
            )
            return true
        } finally {
            FileUtil.delete(tempDir.toFile())
        }
    }

    private fun createTempDir(): Path? =
        try {
            Files.createTempDirectory("apple-ai-swift-build")
        } catch (e: IOException) {
            LOG.error("Failed to create temp directory", e)
            notify(
                AppleAiBundle.message("apple.ai.notification.build.failed.error", e.message ?: "Unknown error"),
                NotificationType.ERROR,
            )
            null
        } catch (e: SecurityException) {
            LOG.error("Failed to create temp directory", e)
            notify(
                AppleAiBundle.message("apple.ai.notification.build.failed.error", e.message ?: "Unknown error"),
                NotificationType.ERROR,
            )
            null
        }

    private suspend fun runSwiftBuild(tempDir: Path): BuildOutput? {
        val processBuilder = ProcessBuilder("swift", "build", "-c", "release")
        processBuilder.directory(tempDir.toFile())
        processBuilder.redirectErrorStream(true)
        return try {
            val process = withContext(Dispatchers.IO) { processBuilder.start() }
            val output = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
            BuildOutput(exitCode, output)
        } catch (e: IOException) {
            LOG.error("Failed to run Swift build", e)
            notify(
                AppleAiBundle.message("apple.ai.notification.build.failed.error", e.message ?: "Unknown error"),
                NotificationType.ERROR,
            )
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.error("Swift build interrupted", e)
            notify(
                AppleAiBundle.message("apple.ai.notification.build.failed.error", e.message ?: "Unknown error"),
                NotificationType.ERROR,
            )
            null
        }
    }

    private suspend fun copyBuiltBinary(tempDir: Path, destination: Path): Boolean {
        val builtBinary = tempDir.resolve(".build/release/AppleAIHelper")
        if (!builtBinary.toFile().exists()) {
            return false
        }
        return try {
            withContext(Dispatchers.IO) {
                destination.parent?.toFile()?.mkdirs()
                Files.copy(builtBinary, destination, StandardCopyOption.REPLACE_EXISTING)
                destination.toFile().setExecutable(true)
            }
            true
        } catch (e: IOException) {
            LOG.error("Failed to copy Swift helper binary", e)
            notify(
                AppleAiBundle.message("apple.ai.notification.build.failed.error", e.message ?: "Unknown error"),
                NotificationType.ERROR,
            )
            false
        }
    }

    private fun extractSwiftSources(destination: Path): Boolean {
        val classLoader = AppleAiBundle::class.java.classLoader
        val sources = listOf("swift-sources/Package.swift", "swift-sources/Sources/main.swift")

        try {
            for (source in sources) {
                val inputStream = classLoader.getResourceAsStream(source) ?: return false
                val targetFile = destination.resolve(source.removePrefix("swift-sources/"))
                Files.createDirectories(targetFile.parent)
                Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } catch (e: IOException) {
            LOG.error("Failed to extract Swift sources", e)
            return false
        }
    }

    suspend fun checkModelStatus(): SwiftResponse? = swiftHelper?.checkStatus()

    suspend fun generate(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): SwiftResponse {
        val helper = checkNotNull(swiftHelper) { "Apple AI helper is not running" }
        return helper.generate(messages, temperature, maxTokens)
    }

    fun stream(messages: List<ChatMessage>, temperature: Double? = null, maxTokens: Int? = null): Flow<SwiftResponse> {
        val helper = checkNotNull(swiftHelper) { "Apple AI helper is not running" }
        @Suppress("NoStreamApiInKotlin")
        return helper.stream(messages, temperature, maxTokens)
    }

    private fun findDefaultHelperPath(): Path? {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.home"), ".build/debug/AppleAIHelper"),
                Path.of(System.getProperty("user.home"), ".build/release/AppleAIHelper"),
            )
        return candidates.firstOrNull { it.toFile().exists() && it.toFile().canExecute() }
    }

    private fun notify(@Nls content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("AppleOnDeviceAI")
                .createNotification(content, type)
                .notify(null)
        } catch (e: IllegalStateException) {
            LOG.error(content, e)
        } catch (e: IllegalArgumentException) {
            LOG.error(content, e)
        }
    }

    private data class BuildOutput(val exitCode: Int, val output: String)

    override fun dispose() {
        httpServer.stop()
        swiftHelper?.stop()
        swiftHelper = null
    }

    companion object {
        @JvmStatic fun getInstance(): AppleAiService = service()

        private fun isMacOSVersionSufficient(): Boolean {
            val major = parseMacOSMajorVersion(SystemInfo.OS_VERSION) ?: return false
            return major >= MIN_MACOS_MAJOR_VERSION
        }
    }
}
