// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for Apple AI plugin models and serialization. */
class AppleAiPluginTest {

    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `deserialize ChatMessage with string content`() {
        val json = """{"role": "user", "content": "Hello"}"""
        val message = mapper.readValue<ChatMessage>(json)
        assertEquals("user", message.role)
        assertEquals("Hello", message.content)
    }

    @Test
    fun `deserialize ChatMessage with array content containing text objects`() {
        val json =
            """{"role": "user", "content": [{"type": "text", "text": "Hello"}, {"type": "text", "text": " world"}]}"""
        val message = mapper.readValue<ChatMessage>(json)
        assertEquals("user", message.role)
        assertEquals("Hello world", message.content)
    }

    @Test
    fun `deserialize ChatMessage with array content containing plain strings`() {
        val json = """{"role": "user", "content": ["Hello", " world"]}"""
        val message = mapper.readValue<ChatMessage>(json)
        assertEquals("user", message.role)
        assertEquals("Hello world", message.content)
    }

    @Test
    fun `deserialize ChatMessage with non-text node fallback`() {
        val json = """{"role": "user", "content": 42}"""
        val message = mapper.readValue<ChatMessage>(json)
        assertEquals("user", message.role)
        assertEquals("42", message.content)
    }

    @Test
    fun `deserialize ChatCompletionRequest`() {
        val json = """{"messages": [{"role": "user", "content": "Hi"}]}"""
        val request = mapper.readValue<ChatCompletionRequest>(json)
        assertNotNull(request.messages)
        assertEquals(1, request.messages.size)
        assertEquals("user", request.messages[0].role)
        assertEquals("Hi", request.messages[0].content)
    }

    @Test
    fun `APPLE_ON_DEVICE_MODEL constant`() {
        assertEquals("apple-on-device", APPLE_ON_DEVICE_MODEL)
    }

    // region ChatCompletionResponse round-trip

    @Test
    fun `ChatCompletionResponse round-trip serialization`() {
        val response =
            ChatCompletionResponse(
                id = "chatcmpl-1",
                created = 1_700_000_000L,
                model = APPLE_ON_DEVICE_MODEL,
                choices =
                    listOf(
                        ChatCompletionChoice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = "Hi there"),
                            finishReason = "stop",
                        )
                    ),
                usage = UsageInfo(promptTokens = 5, completionTokens = 3, totalTokens = 8),
            )
        val json = mapper.writeValueAsString(response)
        val deserialized = mapper.readValue<ChatCompletionResponse>(json)
        assertEquals(response.id, deserialized.id)
        assertEquals(response.objectType, deserialized.objectType)
        assertEquals(response.created, deserialized.created)
        assertEquals(response.model, deserialized.model)
        assertEquals(1, deserialized.choices.size)
        assertEquals("Hi there", deserialized.choices[0].message.content)
        assertEquals("stop", deserialized.choices[0].finishReason)
        assertNotNull(deserialized.usage)
        assertEquals(8, deserialized.usage!!.totalTokens)
    }

    // endregion

    // region ChatCompletionStreamResponse round-trip

    @Test
    fun `ChatCompletionStreamResponse round-trip serialization`() {
        val response =
            ChatCompletionStreamResponse(
                id = "chatcmpl-stream-1",
                created = 1_700_000_000L,
                model = APPLE_ON_DEVICE_MODEL,
                choices =
                    listOf(
                        ChatCompletionStreamChoice(
                            index = 0,
                            delta = ChatCompletionDelta(role = "assistant", content = "token"),
                            finishReason = null,
                        )
                    ),
            )
        val json = mapper.writeValueAsString(response)
        val deserialized = mapper.readValue<ChatCompletionStreamResponse>(json)
        assertEquals(response.id, deserialized.id)
        assertEquals("chat.completion.chunk", deserialized.objectType)
        assertEquals(1, deserialized.choices.size)
        assertEquals("token", deserialized.choices[0].delta.content)
        assertNull(deserialized.choices[0].finishReason)
    }

    // endregion

    // region SwiftRequest serialization

    @Test
    fun `SwiftRequest serialization`() {
        val request =
            SwiftRequest(
                action = "generate",
                messages = listOf(ChatMessage(role = "user", content = "Hello")),
                temperature = 0.7,
                maxTokens = 100,
                stream = false,
            )
        val json = mapper.writeValueAsString(request)
        val deserialized = mapper.readValue<SwiftRequest>(json)
        assertEquals("generate", deserialized.action)
        assertNotNull(deserialized.messages)
        assertEquals(1, deserialized.messages!!.size)
        assertEquals(0.7, deserialized.temperature!!, 0.001)
        assertEquals(100, deserialized.maxTokens)
        assertEquals(false, deserialized.stream)
    }

    // endregion

    // region SwiftResponse deserialization for each type

    @Test
    fun `SwiftResponse deserialization - result`() {
        val json = """{"type": "result", "content": "Hello from AI"}"""
        val response = mapper.readValue<SwiftResponse>(json)
        assertEquals("result", response.type)
        assertEquals("Hello from AI", response.content)
    }

    @Test
    fun `SwiftResponse deserialization - error`() {
        val json = """{"type": "error", "error": "Something went wrong"}"""
        val response = mapper.readValue<SwiftResponse>(json)
        assertEquals("error", response.type)
        assertEquals("Something went wrong", response.error)
    }

    @Test
    fun `SwiftResponse deserialization - stream_delta`() {
        val json = """{"type": "stream_delta", "content": "tok"}"""
        val response = mapper.readValue<SwiftResponse>(json)
        assertEquals("stream_delta", response.type)
        assertEquals("tok", response.content)
    }

    @Test
    fun `SwiftResponse deserialization - stream_done`() {
        val json = """{"type": "stream_done", "finishReason": "stop"}"""
        val response = mapper.readValue<SwiftResponse>(json)
        assertEquals("stream_done", response.type)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `SwiftResponse deserialization - status`() {
        val json = """{"type": "status", "modelAvailable": true}"""
        val response = mapper.readValue<SwiftResponse>(json)
        assertEquals("status", response.type)
        assertTrue(response.modelAvailable!!)
    }

    @Test
    fun `SwiftResponse deserialization - ready`() {
        val json = """{"type": "ready"}"""
        val response = mapper.readValue<SwiftResponse>(json)
        assertEquals("ready", response.type)
    }

    // endregion

    // region ModelsResponse serialization

    @Test
    fun `ModelsResponse round-trip serialization`() {
        val response =
            ModelsResponse(
                data = listOf(ModelInfo(id = APPLE_ON_DEVICE_MODEL, created = 1_700_000_000L, ownedBy = "apple"))
            )
        val json = mapper.writeValueAsString(response)
        val deserialized = mapper.readValue<ModelsResponse>(json)
        assertEquals("list", deserialized.objectType)
        assertEquals(1, deserialized.data.size)
        assertEquals(APPLE_ON_DEVICE_MODEL, deserialized.data[0].id)
        assertEquals("apple", deserialized.data[0].ownedBy)
    }

    // endregion

    // region OpenAiError serialization

    @Test
    fun `OpenAiError round-trip serialization`() {
        val error =
            OpenAiError(
                error =
                    OpenAiErrorDetail(
                        message = "Model not found",
                        type = "invalid_request_error",
                        param = "model",
                        code = "model_not_found",
                    )
            )
        val json = mapper.writeValueAsString(error)
        val deserialized = mapper.readValue<OpenAiError>(json)
        assertEquals("Model not found", deserialized.error.message)
        assertEquals("invalid_request_error", deserialized.error.type)
        assertEquals("model", deserialized.error.param)
        assertEquals("model_not_found", deserialized.error.code)
    }

    // endregion
}
