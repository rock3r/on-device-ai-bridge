// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.ide.RestService
import org.jetbrains.io.response
import java.net.URI

private const val MILLIS_PER_SECOND = 1000L

/**
 * Exposes Apple on-device AI as OpenAI-compatible REST endpoints via the IDE's built-in server.
 *
 * Endpoints:
 * - `GET /api/apple-ai/v1/models` — list available models
 * - `POST /api/apple-ai/v1/chat/completions` — chat completions (streaming & non-streaming)
 * - `GET /api/apple-ai/health` — health check
 */
internal class AppleAiRestService : RestService() {

    companion object {
        private val LOG = logger<AppleAiRestService>()
        private val STREAMING_TIMEOUT = 5.minutes
        private val GENERATION_TIMEOUT = 5.minutes

        private val ALLOWED_HOSTS = setOf("localhost", "127.0.0.1")

        private val SUPPORTED_PATHS =
            setOf(
                "/v1/models",
                "/models",
                "/v1/chat/completions",
                "/chat/completions",
                "/health",
            )

        private const val MAX_REQUEST_BODY_BYTES = 1024 * 1024 // 1 MB

        /** Rate limiting: max failed auth attempts per IP within the time window. */
        private const val MAX_AUTH_FAILURES = 10
        private const val AUTH_FAILURE_WINDOW_MS = 60_000L // 1 minute
        private const val MAX_TRACKED_ADDRESSES = 1_000

        @VisibleForTesting internal val authFailureTracker = ConcurrentHashMap<String, AuthFailureRecord>()

        internal data class AuthFailureRecord(
            val count: AtomicInteger = AtomicInteger(0),
            val windowStart: AtomicLong = AtomicLong(System.currentTimeMillis()),
        )

        /**
         * Returns `true` if the given [origin] is a localhost origin (http/https with localhost or 127.0.0.1, with or
         * without a port).
         */
        @VisibleForTesting
        internal fun isLocalhostOrigin(origin: String): Boolean {
            val uri =
                try {
                    URI(origin)
                } catch (_: Exception) {
                    return false
                }
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return (scheme == "http" || scheme == "https") && host in ALLOWED_HOSTS
        }

        /**
         * Checks whether the given [request] carries a valid `Authorization: Bearer <token>` header matching
         * [expectedKey]. Returns `false` if the header is absent, does not use the Bearer scheme, or the token does
         * not match.
         *
         * Uses constant-time comparison to prevent timing side-channel attacks.
         */
        @VisibleForTesting
        internal fun isAuthorized(request: FullHttpRequest, expectedKey: String): Boolean {
            val authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION)
            if (authHeader == null) {
                LOG.debug("Apple AI auth: no Authorization header present")
                return false
            }
            if (!authHeader.startsWith("Bearer ")) {
                LOG.debug("Apple AI auth: Authorization header does not use Bearer scheme")
                return false
            }
            val token = authHeader.removePrefix("Bearer ").trim()
            val matches =
                MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), expectedKey.toByteArray(Charsets.UTF_8))
            if (!matches) {
                LOG.debug(
                    "Apple AI auth: token mismatch — " +
                        "received=${token.redactedForLog()}, expected=${expectedKey.redactedForLog()}, " +
                        "receivedLen=${token.length}, expectedLen=${expectedKey.length}, " +
                        "rawHeader=${authHeader.redactedForLog()}"
                )
            }
            return matches
        }

        private fun String.redactedForLog(): String =
            when {
                length <= 4 -> "***"
                length <= 8 -> "${take(2)}...${takeLast(2)}"
                else -> "${take(4)}...${takeLast(4)}"
            }

        /** Returns `true` if the remote address is currently rate-limited due to too many auth failures. */
        @VisibleForTesting
        internal fun isRateLimited(remoteAddress: String): Boolean {
            val record = authFailureTracker[remoteAddress] ?: return false
            val now = System.currentTimeMillis()
            if (now - record.windowStart.get() > AUTH_FAILURE_WINDOW_MS) {
                // Window expired — reset
                authFailureTracker.remove(remoteAddress)
                return false
            }
            return record.count.get() >= MAX_AUTH_FAILURES
        }

        /** Records an authentication failure for the given remote address. */
        @VisibleForTesting
        internal fun recordAuthFailure(remoteAddress: String) {
            val now = System.currentTimeMillis()
            // Evict expired entries if tracker is too large
            if (authFailureTracker.size >= MAX_TRACKED_ADDRESSES) {
                authFailureTracker.entries.removeIf { now - it.value.windowStart.get() > AUTH_FAILURE_WINDOW_MS }
            }
            // If still at capacity after eviction, skip tracking this address
            if (authFailureTracker.size >= MAX_TRACKED_ADDRESSES && !authFailureTracker.containsKey(remoteAddress)) {
                return
            }
            val record = authFailureTracker.computeIfAbsent(remoteAddress) { AuthFailureRecord() }
            if (now - record.windowStart.get() > AUTH_FAILURE_WINDOW_MS) {
                record.count.set(1)
                record.windowStart.set(now)
            } else {
                record.count.incrementAndGet()
            }
        }

        /**
         * Sets CORS headers only when the request Origin matches a localhost address. Non-browser clients (Cursor,
         * Continue, etc.) don't send Origin headers and don't need CORS.
         */
        @VisibleForTesting
        internal fun setCorsHeaders(request: FullHttpRequest, response: HttpResponse) {
            val origin = request.headers().get(HttpHeaderNames.ORIGIN) ?: return
            if (!isLocalhostOrigin(origin)) return
            response.headers().set("Access-Control-Allow-Origin", origin)
            response.headers().set("Access-Control-Allow-Headers", "Content-Type, Authorization, Cache-Control")
            response.headers().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }
    }

    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override fun getServiceName(): String = "apple-ai"

    override fun isSupported(request: FullHttpRequest): Boolean {
        if (!isMethodSupported(request.method())) return false

        if (super.isSupported(request)) {
            return true
        }

        val path = QueryStringDecoder(request.uri()).path().removeSuffix("/")
        return path in SUPPORTED_PATHS
    }

    override fun isMethodSupported(method: HttpMethod): Boolean =
        method === HttpMethod.GET || method === HttpMethod.POST || method === HttpMethod.OPTIONS

    override fun isHostTrusted(request: FullHttpRequest, urlDecoder: QueryStringDecoder): Boolean {
        val origin = request.headers().get(HttpHeaderNames.ORIGIN)
        if (origin == null) {
            // Non-browser clients (Cursor, Continue, CLI tools) don't send Origin headers.
            // They are trusted because they are local processes making direct HTTP calls.
            return true
        }
        return isLocalhostOrigin(origin)
    }

    @VisibleForTesting
    internal fun isHostTrustedForTest(request: FullHttpRequest, urlDecoder: QueryStringDecoder): Boolean =
        isHostTrusted(request, urlDecoder)

    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): String? {
        val path = urlDecoder.path().removeSuffix("/")
        val service = AppleAiService.getInstance()

        LOG.debug("Apple AI REST request: ${request.method()} $path")

        // Handle CORS preflight requests
        if (request.method() === HttpMethod.OPTIONS) {
            val response = response("", Unpooled.EMPTY_BUFFER)
            response.status = HttpResponseStatus.NO_CONTENT
            setCorsHeaders(request, response)
            sendResponse(request, context, response)
            return null
        }

        // Rate limiting check
        val remoteAddress = context.channel().remoteAddress()?.toString() ?: "unknown"
        if (isRateLimited(remoteAddress)) {
            sendErrorJson(
                HttpResponseStatus.TOO_MANY_REQUESTS,
                "Too many failed authentication attempts",
                request,
                context,
            )
            return null
        }

        // All endpoints require authentication
        if (!isAuthorized(request, AppleAiSettings.getInstance().ensureApiKey())) {
            recordAuthFailure(remoteAddress)
            sendErrorJson(HttpResponseStatus.UNAUTHORIZED, "Invalid or missing API key", request, context)
            return null
        }

        if (path == "/api/apple-ai/health" || path == "/health") {
            val httpResponse = response("application/json", Unpooled.wrappedBuffer("""{"status":"ok"}""".toByteArray()))
            setCorsHeaders(request, httpResponse)
            sendResponse(request, context, httpResponse)
            return null
        }

        if (
            request.method() === HttpMethod.GET &&
                (path == "/v1/models" || path == "/api/apple-ai/v1/models" || path == "/models")
        ) {
            return handleModels(request, context, service)
        }

        if (
            request.method() === HttpMethod.POST &&
                (path == "/v1/chat/completions" ||
                    path == "/api/apple-ai/v1/chat/completions" ||
                    path == "/chat/completions")
        ) {
            return handleChatCompletions(request, context, service)
        }

        return "Unknown endpoint: $path"
    }

    private fun handleModels(
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        service: AppleAiService,
    ): String? {
        val models =
            if (service.isRunning) {
                listOf(
                    ModelInfo(
                        id = APPLE_ON_DEVICE_MODEL,
                        created = System.currentTimeMillis() / 1000,
                        ownedBy = "apple-on-device",
                    )
                )
            } else {
                emptyList()
            }

        val response = ModelsResponse(data = models)
        sendJson(LOG, request, context, mapper.writeValueAsBytes(response)) { req, ctx, resp ->
            sendResponse(req, ctx, resp)
        }
        return null
    }

    private fun handleChatCompletions(
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        service: AppleAiService,
    ): String? {
        if (!service.isRunning) {
            sendErrorJson(
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                "Apple AI helper is not running. Start it in Settings > Apple On-Device AI.",
                request,
                context,
            )
            return null
        }

        val chatRequest = parseChatRequest(request, context) ?: return null

        if (chatRequest.messages.isEmpty()) {
            sendErrorJson(HttpResponseStatus.BAD_REQUEST, "No messages provided", request, context)
            return null
        }

        if (chatRequest.stream == true) {
            return handleStreamingCompletions(chatRequest, request, context, service)
        }

        return handleNonStreamingCompletions(chatRequest, request, context, service)
    }

    private fun parseChatRequest(request: FullHttpRequest, context: ChannelHandlerContext): ChatCompletionRequest? {
        val contentLength = request.content().readableBytes()
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            sendErrorJson(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large", request, context)
            return null
        }
        val bodyBytes =
            try {
                ByteBufInputStream(request.content()).readAllBytes()
            } catch (e: IOException) {
                LOG.debug("Apple AI: failed to read request body", e)
                sendErrorJson(HttpResponseStatus.BAD_REQUEST, "Invalid request body", request, context)
                return null
            }
        val chatRequest =
            try {
                mapper.readValue<ChatCompletionRequest>(bodyBytes)
            } catch (e: JsonProcessingException) {
                LOG.debug("Apple AI: invalid request body", e)
                sendErrorJson(HttpResponseStatus.BAD_REQUEST, "Invalid request body", request, context)
                return null
            }
        LOG.debug(
            "Apple AI chat completions request: " +
                "model=${chatRequest.model}, messages=${chatRequest.messages.size}, " +
                "stream=${chatRequest.stream}, temperature=${chatRequest.temperature}"
        )
        return chatRequest
    }

    private fun handleNonStreamingCompletions(
        chatRequest: ChatCompletionRequest,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        service: AppleAiService,
    ): String? {
        service.coroutineScope.launch(Dispatchers.IO) {
            try {
                val result =
                    withTimeout(GENERATION_TIMEOUT) {
                        service.generate(chatRequest.messages, chatRequest.temperature, chatRequest.maxTokens)
                    }
                LOG.debug(
                    "Apple AI chat completions: received result from helper: " +
                        "type=${result.type}, contentLen=${result.content?.length ?: 0}"
                )

                if (result.type == SwiftResponseType.ERROR) {
                    sendErrorJson(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        result.error ?: "Unknown error",
                        request,
                        context,
                    )
                    return@launch
                }

                sendChatCompletionResponse(chatRequest, result, request, context)
            } catch (_: TimeoutCancellationException) {
                LOG.warn("Apple AI generation timed out after $GENERATION_TIMEOUT")
                sendErrorJson(
                    HttpResponseStatus.GATEWAY_TIMEOUT,
                    "Generation timed out",
                    request,
                    context,
                )
            } catch (e: CancellationException) {
                LOG.warn("Apple AI generation cancelled", e)
                throw e
            } catch (e: IllegalStateException) {
                LOG.error("Error generating from Apple AI helper", e)
                sendErrorJson(
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    e.message ?: "Generation error",
                    request,
                    context,
                )
            }
        }
        return null
    }

    private fun sendChatCompletionResponse(
        chatRequest: ChatCompletionRequest,
        result: SwiftResponse,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ) {
        val chatResponse =
            ChatCompletionResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                created = nowEpochSeconds(),
                model = chatRequest.model ?: APPLE_ON_DEVICE_MODEL,
                choices =
                    listOf(
                        ChatCompletionChoice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = result.content ?: ""),
                            finishReason = "stop",
                        )
                    ),
            )

        val responseBytes = mapper.writeValueAsBytes(chatResponse)
        LOG.debug(
            "Apple AI chat completions response: " + "id=${chatResponse.id}, contentLen=${result.content?.length ?: 0}"
        )
        sendJson(LOG, request, context, responseBytes) { req, ctx, resp -> sendResponse(req, ctx, resp) }
    }

    private fun handleStreamingCompletions(
        chatRequest: ChatCompletionRequest,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        service: AppleAiService,
    ): String? {
        val channel = context.channel()
        val streamContext =
            StreamContext(
                responseId = "chatcmpl-${UUID.randomUUID()}",
                created = nowEpochSeconds(),
                model = chatRequest.model ?: APPLE_ON_DEVICE_MODEL,
            )

        prepareStreamingResponse(request, channel)

        val streamJob =
            service.coroutineScope.launch(Dispatchers.IO) {
                streamResponses(channel, streamContext, chatRequest, service)
            }

        channel.closeFuture().addListener { streamJob.cancel() }
        return null
    }

    private suspend fun streamResponses(
        channel: Channel,
        streamContext: StreamContext,
        chatRequest: ChatCompletionRequest,
        service: AppleAiService,
    ) {
        try {
            withTimeout(STREAMING_TIMEOUT) {
                var isFirst = true
                var chunkCount = 0
                @Suppress("NoStreamApiInKotlin")
                service.stream(chatRequest.messages, chatRequest.temperature, chatRequest.maxTokens).collect { resp ->
                    if (!channel.isActive) {
                        LOG.debug("Apple AI streaming: client disconnected after $chunkCount chunks, cancelling")
                        currentCoroutineContext().cancel()
                        return@collect
                    }

                    LOG.debug(
                        "Received response from Swift helper: " +
                            "type=${resp.type}, contentLen=${resp.content?.length ?: 0}"
                    )
                    when (resp.type) {
                        SwiftResponseType.STREAM_DELTA -> {
                            chunkCount++
                            val delta =
                                if (isFirst) {
                                    isFirst = false
                                    ChatCompletionDelta(role = "assistant", content = resp.content)
                                } else {
                                    ChatCompletionDelta(content = resp.content)
                                }
                            sendStreamDelta(mapper, LOG, channel, streamContext, delta, chunkCount)
                        }
                        SwiftResponseType.STREAM_DONE -> {
                            LOG.debug("Apple AI streaming completed after $chunkCount chunks")
                            sendStreamDone(mapper, LOG, channel, streamContext)
                        }
                        SwiftResponseType.ERROR ->
                            sendSseErrorAndClose(mapper, LOG, channel, resp.error ?: "Unknown error")
                        else -> LOG.debug("Apple AI streaming: ignored response type: ${resp.type}")
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            LOG.warn("Apple AI streaming timed out after $STREAMING_TIMEOUT")
            if (channel.isActive) {
                sendSseErrorAndClose(mapper, LOG, channel, "Stream timed out")
            }
        } catch (e: CancellationException) {
            LOG.debug("Apple AI streaming cancelled (client likely disconnected)")
            throw e
        } catch (e: IllegalStateException) {
            LOG.error("Error streaming from Apple AI helper", e)
            if (channel.isActive) {
                sendSseErrorAndClose(mapper, LOG, channel, e.message ?: "Stream error")
            }
        }
    }

    private fun sendErrorJson(
        status: HttpResponseStatus,
        message: String,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ) {
        LOG.warn("Apple AI: sending error response (status=$status): $message")
        val error = OpenAiError(error = OpenAiErrorDetail(message = message, type = "invalid_request_error"))
        val bytes = mapper.writeValueAsBytes(error)
        val httpResponse = response("application/json", Unpooled.wrappedBuffer(bytes))
        httpResponse.status = status
        setCorsHeaders(request, httpResponse)
        sendResponse(request, context, httpResponse)
    }
}

private fun sendJson(
    logger: Logger,
    request: FullHttpRequest,
    context: ChannelHandlerContext,
    jsonBytes: ByteArray,
    sendResponse: (FullHttpRequest, ChannelHandlerContext, HttpResponse) -> Unit,
) {
    logger.debug("Apple AI: sending JSON response (${jsonBytes.size} bytes)")
    val httpResponse = response("application/json", Unpooled.wrappedBuffer(jsonBytes))
    AppleAiRestService.setCorsHeaders(request, httpResponse)
    sendResponse(request, context, httpResponse)
}

private fun prepareStreamingResponse(request: FullHttpRequest, channel: Channel) {
    val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
    response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive")
    response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
    AppleAiRestService.setCorsHeaders(request, response)
    HttpUtil.setKeepAlive(response, true)
    channel.writeAndFlush(response)

    runCatching { channel.config().setOption(ChannelOption.TCP_NODELAY, true) }
    runCatching { channel.config().setOption(ChannelOption.SO_KEEPALIVE, true) }
}

private fun sendStreamDelta(
    mapper: ObjectMapper,
    logger: Logger,
    channel: Channel,
    streamContext: StreamContext,
    delta: ChatCompletionDelta,
    chunkCount: Int,
) {
    val chunk =
        ChatCompletionStreamResponse(
            id = streamContext.responseId,
            created = streamContext.created,
            model = streamContext.model,
            choices = listOf(ChatCompletionStreamChoice(index = 0, delta = delta, finishReason = null)),
        )
    val sseData = "data: ${mapper.writeValueAsString(chunk)}\n\n"
    logger.trace("Apple AI streaming chunk #$chunkCount: $sseData")
    channel.writeAndFlush(Unpooled.copiedBuffer(sseData, CharsetUtil.UTF_8))
}

private fun sendStreamDone(
    mapper: ObjectMapper,
    logger: Logger,
    channel: Channel,
    streamContext: StreamContext,
) {
    val finalChunk =
        ChatCompletionStreamResponse(
            id = streamContext.responseId,
            created = streamContext.created,
            model = streamContext.model,
            choices =
                listOf(ChatCompletionStreamChoice(index = 0, delta = ChatCompletionDelta(), finishReason = "stop")),
        )
    val finalSse = "data: ${mapper.writeValueAsString(finalChunk)}\n\n"
    channel.writeAndFlush(Unpooled.copiedBuffer(finalSse, CharsetUtil.UTF_8))
    sendDoneAndClose(channel, logger)
}

private fun nowEpochSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

private data class StreamContext(val responseId: String, val created: Long, val model: String)

private fun sendSseErrorAndClose(
    mapper: ObjectMapper,
    logger: Logger,
    channel: Channel,
    errorMessage: String,
) {
    val errorJson =
        mapper.writeValueAsString(
            OpenAiError(error = OpenAiErrorDetail(message = errorMessage, type = "internal_error"))
        )
    val errorSse = "data: $errorJson\n\n"
    logger.error("Apple AI streaming error: $errorMessage")
    channel.writeAndFlush(Unpooled.copiedBuffer(errorSse, CharsetUtil.UTF_8))
    sendDoneAndClose(channel, logger)
}

private fun sendDoneAndClose(channel: Channel, logger: Logger) {
    channel.writeAndFlush(Unpooled.copiedBuffer("data: [DONE]\n\n", CharsetUtil.UTF_8))
    channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener {
        if (!it.isSuccess) {
            logger.error("Apple AI streaming: failed to close stream", it.cause())
        }
    }
}
