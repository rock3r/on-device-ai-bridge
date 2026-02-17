// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private val LOG = logger<SwiftHelperProcess>()

/**
 * Manages the lifecycle of the Swift helper subprocess that wraps Apple's FoundationModels framework. Communication
 * happens via JSON lines over stdin/stdout.
 */
internal class SwiftHelperProcess(private val binaryPath: Path) : Disposable {

    private companion object {
        private const val READY_TIMEOUT_MS = 5_000L
    }

    private val mapper: ObjectMapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var readerThread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private var pendingResult: CompletableDeferred<SwiftResponse>? = null
    private var pendingResultAction: String? = null
    private var pendingStreamChannel: Channel<SwiftResponse>? = null

    suspend fun start(): Boolean {
        if (isRunning) return true
        if (!binaryPath.exists() || !binaryPath.isExecutable()) {
            LOG.warn("Swift helper binary not found or not executable: $binaryPath")
            return false
        }

        val proc = startProcess() ?: return false
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))

        val readySignal = CompletableDeferred<Unit>()
        readerThread =
            createReaderThread(readySignal).apply {
                isDaemon = true
                start()
            }

        if (!awaitReady(proc, readySignal)) {
            stop()
            return false
        }

        LOG.info("Swift helper process started successfully")
        return true
    }

    private fun startProcess(): Process? =
        try {
            val pb = ProcessBuilder(binaryPath.toString()).redirectErrorStream(true)
            LOG.info("Executing Swift helper: $binaryPath")
            pb.start()
        } catch (e: IOException) {
            LOG.error("Failed to execute Swift helper binary: $binaryPath", e)
            null
        } catch (e: SecurityException) {
            LOG.error("Failed to execute Swift helper binary: $binaryPath", e)
            null
        }

    private fun createReaderThread(readySignal: CompletableDeferred<Unit>): Thread =
        Thread(
            {
                try {
                    val r = reader ?: return@Thread
                    while (true) {
                        val line = r.readLine() ?: break
                        handleReaderLine(line, readySignal)
                    }
                } catch (e: IOException) {
                    LOG.warn("Swift helper reader thread stopped", e)
                } finally {
                    isRunning = false
                    val exitCode =
                        try {
                            process?.exitValue()
                        } catch (_: IllegalThreadStateException) {
                            null
                        }
                    readySignal.completeExceptionally(
                        IllegalStateException("Swift helper process terminated with code $exitCode")
                    )
                    LOG.info("Swift helper process reader thread finished with exit code $exitCode")
                }
            },
            "AppleAI-SwiftHelper-Reader",
        )

    private fun handleReaderLine(line: String, readySignal: CompletableDeferred<Unit>) {
        if (line.isBlank()) return
        if (!line.trim().startsWith("{")) {
            LOG.info("Swift helper plain text output: $line")
            return
        }
        val response = parseResponse(line) ?: return
        if (response.type == SwiftResponseType.STATUS) {
            LOG.trace("Swift helper output: $line")
        } else {
            LOG.info("Swift helper output: $line")
        }
        if (response.type == SwiftResponseType.READY) {
            isRunning = true
            readySignal.complete(Unit)
            LOG.info("Swift helper sent ready signal. isRunning is now $isRunning")
            return
        }
        handleResponse(response)
    }

    private fun parseResponse(line: String): SwiftResponse? =
        try {
            mapper.readValue<SwiftResponse>(line)
        } catch (e: JsonProcessingException) {
            LOG.warn("Failed to parse JSON from Swift helper: $line", e)
            null
        }

    private suspend fun awaitReady(proc: Process, readySignal: CompletableDeferred<Unit>): Boolean =
        try {
            withTimeout(READY_TIMEOUT_MS) { readySignal.await() }
            true
        } catch (e: TimeoutCancellationException) {
            if (proc.isAlive) {
                LOG.warn("Swift helper did not send ready signal within timeout", e)
            } else {
                val exitCode = proc.exitValue()
                LOG.warn("Swift helper process exited prematurely with code $exitCode", e)
            }
            false
        } catch (e: CancellationException) {
            LOG.warn("Swift helper startup cancelled", e)
            false
        } catch (e: IllegalStateException) {
            LOG.error("Failed while waiting for Swift helper ready signal", e)
            false
        }

    fun stop() {
        isRunning = false
        try {
            writer?.close()
        } catch (_: IOException) {}
        try {
            process?.destroyForcibly()
        } catch (_: SecurityException) {}
        process = null
        writer = null
        reader = null
        readerThread?.interrupt()
        readerThread = null
        pendingResult?.cancel()
        pendingResult = null
        pendingResultAction = null
        pendingStreamChannel?.close()
        pendingStreamChannel = null
    }

    @Synchronized
    private fun handleResponse(response: SwiftResponse) {
        LOG.debug("handleResponse: type=${response.type}, pendingResultAction=$pendingResultAction")
        when (response.type) {
            SwiftResponseType.STATUS -> {
                if (pendingResultAction == SwiftResponseType.STATUS) {
                    pendingResult?.complete(response)
                    pendingResult = null
                    pendingResultAction = null
                }
            }
            SwiftResponseType.RESULT -> {
                pendingResult?.complete(response)
                pendingResult = null
                pendingResultAction = null
            }
            SwiftResponseType.ERROR -> {
                pendingResult?.complete(response)
                pendingResult = null
                pendingResultAction = null
                pendingStreamChannel?.trySend(response)
                pendingStreamChannel?.close()
                pendingStreamChannel = null
            }
            SwiftResponseType.STREAM_DELTA -> {
                pendingStreamChannel?.trySend(response)
            }
            SwiftResponseType.STREAM_DONE -> {
                pendingStreamChannel?.trySend(response)
                pendingStreamChannel?.close()
                pendingStreamChannel = null
            }
            SwiftResponseType.READY -> {
                isRunning = true
                LOG.info("Swift helper sent ready signal")
            }
            else -> LOG.warn("Unknown response type: ${response.type}")
        }
    }

    private fun sendRequestInternal(request: SwiftRequest) {
        val w = checkNotNull(writer) { "Swift helper not running" }
        val json = mapper.writeValueAsString(request)
        if (request.action == "status") {
            LOG.trace("Sending request to Swift helper: action=${request.action}")
        } else {
            LOG.debug(
                "Sending request to Swift helper: " +
                    "action=${request.action}, messageCount=${request.messages?.size ?: 0}"
            )
        }
        w.write(json)
        w.newLine()
        w.flush()
    }

    suspend fun checkStatus(): SwiftResponse {
        if (!isRunning) {
            return SwiftResponse(type = SwiftResponseType.STATUS, modelAvailable = false, reason = "Process not running")
        }

        val deferred =
            synchronized(this) {
                if (pendingResult != null || pendingStreamChannel != null) {
                    LOG.debug("checkStatus: another request is in progress, returning unknown status")
                    return@synchronized null
                }
                val d = CompletableDeferred<SwiftResponse>()
                pendingResult = d
                pendingResultAction = SwiftResponseType.STATUS
                sendRequestInternal(SwiftRequest(action = "status"))
                d
            } ?: return SwiftResponse(type = SwiftResponseType.STATUS, reason = "Another request is in progress")

        return deferred.await()
    }

    suspend fun generate(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): SwiftResponse {
        if (!isRunning) {
            return SwiftResponse(type = SwiftResponseType.ERROR, error = "Process not running")
        }

        val deferred =
            synchronized(this) {
                if (pendingResult != null || pendingStreamChannel != null) {
                    LOG.warn("generate: another request is already in progress, rejecting")
                    return@synchronized null
                }
                val d = CompletableDeferred<SwiftResponse>()
                pendingResult = d
                pendingResultAction = "generate"
                sendRequestInternal(
                    SwiftRequest(
                        action = "generate",
                        messages = messages,
                        temperature = temperature,
                        maxTokens = maxTokens,
                    )
                )
                d
            }
                ?: return SwiftResponse(
                    type = SwiftResponseType.ERROR,
                    error = "Another request is already in progress",
                )

        return deferred.await()
    }

    fun stream(messages: List<ChatMessage>, temperature: Double? = null, maxTokens: Int? = null): Flow<SwiftResponse> =
        callbackFlow {
            if (!isRunning) {
                send(SwiftResponse(type = SwiftResponseType.ERROR, error = "Process not running"))
                close()
                return@callbackFlow
            }

            val channel =
                synchronized(this@SwiftHelperProcess) {
                    if (pendingResult != null || pendingStreamChannel != null) {
                        LOG.warn("stream: another request is already in progress, rejecting")
                        null
                    } else {
                        val ch = Channel<SwiftResponse>(Channel.UNLIMITED)
                        pendingStreamChannel = ch
                        sendRequestInternal(
                            SwiftRequest(
                                action = "stream",
                                messages = messages,
                                temperature = temperature,
                                maxTokens = maxTokens,
                            )
                        )
                        ch
                    }
                }

            if (channel == null) {
                send(
                    SwiftResponse(
                        type = SwiftResponseType.ERROR,
                        error = "Another request is already in progress",
                    )
                )
                close()
                return@callbackFlow
            }

            val forwardJob = launch {
                for (response in channel) {
                    send(response)
                    if (response.type == SwiftResponseType.STREAM_DONE ||
                        response.type == SwiftResponseType.ERROR
                    ) {
                        break
                    }
                }
            }

            awaitClose {
                forwardJob.cancel()
                channel.close()
            }
        }

    override fun dispose() {
        stop()
    }
}
