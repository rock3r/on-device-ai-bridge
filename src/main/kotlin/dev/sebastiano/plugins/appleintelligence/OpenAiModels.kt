// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ArrayNode

internal const val APPLE_ON_DEVICE_MODEL = "apple-on-device"

// Swift helper response type constants
internal object SwiftResponseType {
    const val RESULT = "result"
    const val ERROR = "error"
    const val STREAM_DELTA = "stream_delta"
    const val STREAM_DONE = "stream_done"
    const val STATUS = "status"
    const val READY = "ready"
}

// region Request models

internal data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = null,
)

internal data class ChatMessage(
    val role: String,
    @JsonDeserialize(using = MessageContentDeserializer::class) @JsonProperty("content") val content: String,
)

internal class MessageContentDeserializer : JsonDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String {
        val node: JsonNode = p.codec.readTree(p)
        if (node.isTextual) {
            return node.asText()
        }
        if (node.isArray) {
            val sb = StringBuilder()
            for (part in node as ArrayNode) {
                if (part.isObject) {
                    val type = part.get("type")?.asText()
                    if (type == "text") {
                        sb.append(part.get("text")?.asText() ?: "")
                    }
                } else if (part.isTextual) {
                    sb.append(part.asText())
                }
            }
            return sb.toString()
        }
        return node.toString()
    }
}

// endregion

// region Response models

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ChatCompletionResponse(
    val id: String,
    @JsonProperty("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChoice>,
    val usage: UsageInfo? = null,
    @JsonProperty("system_fingerprint") val systemFingerprint: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ChatCompletionChoice(
    val index: Int,
    val message: ChatMessage,
    @JsonProperty("finish_reason") val finishReason: String?,
    val logprobs: Any? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class UsageInfo(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
    @JsonProperty("total_tokens") val totalTokens: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ChatCompletionStreamResponse(
    val id: String,
    @JsonProperty("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionStreamChoice>,
    @JsonProperty("system_fingerprint") val systemFingerprint: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ChatCompletionStreamChoice(
    val index: Int,
    val delta: ChatCompletionDelta,
    @JsonProperty("finish_reason") val finishReason: String?,
    val logprobs: Any? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ChatCompletionDelta(
    val role: String? = null,
    @JsonDeserialize(using = MessageContentDeserializer::class) @JsonProperty("content") val content: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ModelsResponse(@JsonProperty("object") val objectType: String = "list", val data: List<ModelInfo>)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ModelInfo(
    val id: String,
    @JsonProperty("object") val objectType: String = "model",
    val created: Long,
    @JsonProperty("owned_by") val ownedBy: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL) internal data class OpenAiError(val error: OpenAiErrorDetail)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class OpenAiErrorDetail(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null,
)

// endregion

// region Swift helper protocol models

internal data class SwiftRequest(
    val action: String,
    val messages: List<ChatMessage>? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SwiftResponse(
    val type: String, // "result", "error", "stream_delta", "stream_done", "status", "ready"
    val content: String? = null,
    val error: String? = null,
    val modelAvailable: Boolean? = null,
    val reason: String? = null,
    val finishReason: String? = null,
)

// endregion
