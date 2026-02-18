// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
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
import java.net.URI
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

/**
 * Exposes Apple on-device AI as OpenAI-compatible REST endpoints via a standalone Netty HTTP server.
 *
 * Endpoints:
 * - `GET /v1/models` — list available models
 * - `POST /v1/chat/completions` — chat completions (streaming & non-streaming)
 * - `GET /health` — health check
 */
internal class AppleAiHttpHandler : SimpleChannelInboundHandler<FullHttpRequest>() {

    companion object {
        private val LOG = logger<AppleAiHttpHandler>()
        private val STREAMING_TIMEOUT = 5.minutes
        private val GENERATION_TIMEOUT = 5.minutes

        private const val MILLIS_PER_SECOND = 1000L
        private val ALLOWED_HOSTS = setOf("localhost", "127.0.0.1")

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

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val urlDecoder = QueryStringDecoder(request.uri())
        val path = urlDecoder.path().removeSuffix("/")
        val service = AppleAiService.getInstance()

        LOG.debug("Apple AI REST request: ${request.method()} $path")

        // Host trust check
        val origin = request.headers().get(HttpHeaderNames.ORIGIN)
        if (origin != null && !isLocalhostOrigin(origin)) {
            sendErrorJson(HttpResponseStatus.FORBIDDEN, "Untrusted origin", request, ctx)
            return
        }

        // Handle CORS preflight requests
        if (request.method() === HttpMethod.OPTIONS) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT)
            setCorsHeaders(request, response)
            ctx.writeAndFlush(response)
            return
        }

        // Check supported method
        if (request.method() !== HttpMethod.GET && request.method() !== HttpMethod.POST) {
            sendErrorJson(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed", request, ctx)
            return
        }

        // Rate limiting check
        val remoteAddress = ctx.channel().remoteAddress()?.toString() ?: "unknown"
        if (isRateLimited(remoteAddress)) {
            sendErrorJson(
                HttpResponseStatus.TOO_MANY_REQUESTS,
                "Too many failed authentication attempts",
                request,
                ctx,
            )
            return
        }

        // All endpoints require authentication
        if (!isAuthorized(request, AppleAiSettings.getInstance().ensureApiKey())) {
            recordAuthFailure(remoteAddress)
            sendErrorJson(HttpResponseStatus.UNAUTHORIZED, "Invalid or missing API key", request, ctx)
            return
        }

        when {
            path == "/health" -> {
                val body = """{"status":"ok"}""".toByteArray()
                val response = jsonResponse(HttpResponseStatus.OK, body)
                setCorsHeaders(request, response)
                ctx.writeAndFlush(response)
            }

            request.method() === HttpMethod.GET && path in setOf("/v1/models", "/models") -> {
                handleModels(request, ctx, service)
            }

            request.method() === HttpMethod.POST && path in setOf("/v1/chat/completions", "/chat/completions") -> {
                handleChatCompletions(request, ctx, service)
            }

            else -> {
                sendErrorJson(HttpResponseStatus.NOT_FOUND, "Unknown endpoint: $path", request, ctx)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        LOG.error("Apple AI HTTP handler error", cause)
        if (ctx.channel().isActive) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR)
            ctx.writeAndFlush(response)
        }
    }

    private fun handleModels(
        request: FullHttpRequest,
        ctx: ChannelHandlerContext,
        service: AppleAiService,
    ) {
        val models =
            if (service.isRunning) {
                listOf(
                    ModelInfo(
                        id = APPLE_ON_DEVICE_MODEL,
                        created = System.currentTimeMillis() / MILLIS_PER_SECOND,
                        ownedBy = "apple-on-device",
                    )
                )
            } else {
                emptyList()
            }

        val response = ModelsResponse(data = models)
        sendJson(request, ctx, mapper.writeValueAsBytes(response))
    }

    private fun handleChatCompletions(
        request: FullHttpRequest,
        ctx: ChannelHandlerContext,
        service: AppleAiService,
    ) {
        if (!service.isRunning) {
            sendErrorJson(
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                "Apple AI helper is not running. Start it in Settings > Apple On-Device AI.",
                request,
                ctx,
            )
            return
        }

        val chatRequest = parseChatRequest(request, ctx) ?: return

        if (chatRequest.messages.isEmpty()) {
            sendErrorJson(HttpResponseStatus.BAD_REQUEST, "No messages provided", request, ctx)
            return
        }

        if (chatRequest.stream == true) {
            handleStreamingCompletions(chatRequest, request, ctx, service)
        } else {
            handleNonStreamingCompletions(chatRequest, request, ctx, service)
        }
    }

    private fun parseChatRequest(request: FullHttpRequest, ctx: ChannelHandlerContext): ChatCompletionRequest? {
        val contentLength = request.content().readableBytes()
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            sendErrorJson(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Request body too large", request, ctx)
            return null
        }
        val bodyBytes =
            try {
                ByteBufInputStream(request.content()).readAllBytes()
            } catch (e: IOException) {
                LOG.debug("Apple AI: failed to read request body", e)
                sendErrorJson(HttpResponseStatus.BAD_REQUEST, "Invalid request body", request, ctx)
                return null
            }
        val chatRequest =
            try {
                mapper.readValue<ChatCompletionRequest>(bodyBytes)
            } catch (e: JsonProcessingException) {
                LOG.debug("Apple AI: invalid request body", e)
                sendErrorJson(HttpResponseStatus.BAD_REQUEST, "Invalid request body", request, ctx)
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
        ctx: ChannelHandlerContext,
        service: AppleAiService,
    ) {
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
                        ctx,
                    )
                    return@launch
                }

                sendChatCompletionResponse(chatRequest, result, request, ctx)
            } catch (_: TimeoutCancellationException) {
                LOG.warn("Apple AI generation timed out after $GENERATION_TIMEOUT")
                sendErrorJson(
                    HttpResponseStatus.GATEWAY_TIMEOUT,
                    "Generation timed out",
                    request,
                    ctx,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                LOG.error("Error generating from Apple AI helper", e)
                sendErrorJson(
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    e.message ?: "Generation error",
                    request,
                    ctx,
                )
            }
        }
    }

    private fun sendChatCompletionResponse(
        chatRequest: ChatCompletionRequest,
        result: SwiftResponse,
        request: FullHttpRequest,
        ctx: ChannelHandlerContext,
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
        sendJson(request, ctx, responseBytes)
    }

    private fun handleStreamingCompletions(
        chatRequest: ChatCompletionRequest,
        request: FullHttpRequest,
        ctx: ChannelHandlerContext,
        service: AppleAiService,
    ) {
        val channel = ctx.channel()
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
                            sendStreamDelta(channel, streamContext, delta, chunkCount)
                        }
                        SwiftResponseType.STREAM_DONE -> {
                            LOG.debug("Apple AI streaming completed after $chunkCount chunks")
                            sendStreamDone(channel, streamContext)
                        }
                        SwiftResponseType.ERROR ->
                            sendSseErrorAndClose(channel, resp.error ?: "Unknown error")
                        else -> LOG.debug("Apple AI streaming: ignored response type: ${resp.type}")
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            LOG.warn("Apple AI streaming timed out after $STREAMING_TIMEOUT")
            if (channel.isActive) {
                sendSseErrorAndClose(channel, "Stream timed out")
            }
        } catch (e: CancellationException) {
            LOG.debug("Apple AI streaming cancelled (client likely disconnected)")
            throw e
        } catch (e: IllegalStateException) {
            LOG.error("Error streaming from Apple AI helper", e)
            if (channel.isActive) {
                sendSseErrorAndClose(channel, e.message ?: "Stream error")
            }
        }
    }

    private fun sendErrorJson(
        status: HttpResponseStatus,
        message: String,
        request: FullHttpRequest,
        ctx: ChannelHandlerContext,
    ) {
        LOG.warn("Apple AI: sending error response (status=$status): $message")
        val error = OpenAiError(error = OpenAiErrorDetail(message = message, type = "invalid_request_error"))
        val bytes = mapper.writeValueAsBytes(error)
        val response = jsonResponse(status, bytes)
        setCorsHeaders(request, response)
        ctx.writeAndFlush(response)
    }

    private fun jsonResponse(status: HttpResponseStatus, body: ByteArray): DefaultFullHttpResponse {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.size)
        return response
    }

    private fun sendJson(
        request: FullHttpRequest,
        ctx: ChannelHandlerContext,
        jsonBytes: ByteArray,
    ) {
        LOG.debug("Apple AI: sending JSON response (${jsonBytes.size} bytes)")
        val response = jsonResponse(HttpResponseStatus.OK, jsonBytes)
        setCorsHeaders(request, response)
        ctx.writeAndFlush(response)
    }

    private fun prepareStreamingResponse(request: FullHttpRequest, channel: Channel) {
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive")
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        setCorsHeaders(request, response)
        HttpUtil.setKeepAlive(response, true)
        channel.writeAndFlush(response)

        runCatching { channel.config().setOption(ChannelOption.TCP_NODELAY, true) }
        runCatching { channel.config().setOption(ChannelOption.SO_KEEPALIVE, true) }
    }

    private fun sendStreamDelta(
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
        LOG.trace("Apple AI streaming chunk #$chunkCount: $sseData")
        channel.writeAndFlush(Unpooled.copiedBuffer(sseData, CharsetUtil.UTF_8))
    }

    private fun sendStreamDone(channel: Channel, streamContext: StreamContext) {
        val finalChunk =
            ChatCompletionStreamResponse(
                id = streamContext.responseId,
                created = streamContext.created,
                model = streamContext.model,
                choices =
                    listOf(
                        ChatCompletionStreamChoice(index = 0, delta = ChatCompletionDelta(), finishReason = "stop")
                    ),
            )
        val finalSse = "data: ${mapper.writeValueAsString(finalChunk)}\n\n"
        channel.writeAndFlush(Unpooled.copiedBuffer(finalSse, CharsetUtil.UTF_8))
        sendDoneAndClose(channel)
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    private data class StreamContext(val responseId: String, val created: Long, val model: String)

    private fun sendSseErrorAndClose(channel: Channel, errorMessage: String) {
        val errorJson =
            mapper.writeValueAsString(
                OpenAiError(error = OpenAiErrorDetail(message = errorMessage, type = "internal_error"))
            )
        val errorSse = "data: $errorJson\n\n"
        LOG.error("Apple AI streaming error: $errorMessage")
        channel.writeAndFlush(Unpooled.copiedBuffer(errorSse, CharsetUtil.UTF_8))
        sendDoneAndClose(channel)
    }

    private fun sendDoneAndClose(channel: Channel) {
        channel.writeAndFlush(Unpooled.copiedBuffer("data: [DONE]\n\n", CharsetUtil.UTF_8))
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener {
            if (!it.isSuccess) {
                LOG.error("Apple AI streaming: failed to close stream", it.cause())
            }
        }
    }
}
