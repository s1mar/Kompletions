package com.s1mar.kompletions

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun `ChatRequest serializes to JSON correctly`() {
        val request = ChatRequest(
            model = "gpt-4",
            messages = listOf(Message("user", "Hello")),
            temperature = 0.7,
            max_tokens = 100
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)
        val decoded = json.decodeFromString(ChatRequest.serializer(), encoded)

        assertEquals(request.model, decoded.model)
        assertEquals(request.messages, decoded.messages)
        assertEquals(request.temperature, decoded.temperature)
        assertEquals(request.max_tokens, decoded.max_tokens)
    }

    @Test
    fun `ChatRequest omits null fields when encodeDefaults is false`() {
        val request = ChatRequest(
            model = "gpt-4",
            messages = listOf(Message("user", "Hello"))
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)

        assert(!encoded.contains("temperature")) { "Should not contain temperature" }
        assert(!encoded.contains("max_tokens")) { "Should not contain max_tokens" }
        assert(!encoded.contains("stream")) { "Should not contain stream" }
    }

    @Test
    fun `ChatResponse deserializes from JSON correctly`() {
        val responseJson = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1700000000,
            "model": "gpt-4",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Hello!"
                    },
                    "finish_reason": "stop"
                }
            ],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15
            }
        }
        """.trimIndent()

        val response = json.decodeFromString(ChatResponse.serializer(), responseJson)

        assertEquals("chatcmpl-123", response.id)
        assertEquals("chat.completion", response.`object`)
        assertEquals(1700000000L, response.created)
        assertEquals("gpt-4", response.model)
        assertEquals(1, response.choices.size)
        assertEquals("assistant", response.choices[0].message.role)
        assertEquals("Hello!", response.choices[0].message.content)
        assertEquals("stop", response.choices[0].finish_reason)
        assertEquals(10, response.usage?.prompt_tokens)
        assertEquals(5, response.usage?.completion_tokens)
        assertEquals(15, response.usage?.total_tokens)
    }

    @Test
    fun `ChatResponse handles missing usage`() {
        val responseJson = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1700000000,
            "model": "gpt-4",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Hello!"
                    },
                    "finish_reason": "stop"
                }
            ]
        }
        """.trimIndent()

        val response = json.decodeFromString(ChatResponse.serializer(), responseJson)
        assertNull(response.usage)
    }

    @Test
    fun `ChatResponse handles unknown fields gracefully`() {
        val responseJson = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1700000000,
            "model": "gpt-4",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Hello!"
                    },
                    "finish_reason": "stop",
                    "logprobs": null
                }
            ],
            "system_fingerprint": "fp_abc123"
        }
        """.trimIndent()

        val response = json.decodeFromString(ChatResponse.serializer(), responseJson)
        assertEquals("chatcmpl-123", response.id)
    }

    @Test
    fun `ChatCompletionChunk deserializes correctly`() {
        val chunkJson = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion.chunk",
            "created": 1700000000,
            "model": "gpt-4",
            "choices": [
                {
                    "index": 0,
                    "delta": {
                        "content": "Hello"
                    },
                    "finish_reason": null
                }
            ]
        }
        """.trimIndent()

        val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), chunkJson)

        assertEquals("chatcmpl-123", chunk.id)
        assertEquals("Hello", chunk.choices[0].delta.content)
        assertNull(chunk.choices[0].finish_reason)
    }

    @Test
    fun `Message serializes with optional name field`() {
        val msg = Message("user", "Hello", name = "John")
        val encoded = json.encodeToString(Message.serializer(), msg)
        val decoded = json.decodeFromString(Message.serializer(), encoded)

        assertEquals("John", decoded.name)
    }

    @Test
    fun `Message handles null content`() {
        val msgJson = """{"role": "assistant"}"""
        val msg = json.decodeFromString(Message.serializer(), msgJson)
        assertNull(msg.content)
        assertEquals("assistant", msg.role)
    }

    @Test
    fun `round-trip ChatRequest with tools`() {
        val request = ChatRequest(
            model = "gpt-4",
            messages = listOf(Message("user", "What's the weather?")),
            tools = listOf(
                Tool(
                    type = "function",
                    function = FunctionDef(
                        name = "get_weather",
                        description = "Get current weather"
                    )
                )
            ),
            tool_choice = "auto"
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)
        val decoded = json.decodeFromString(ChatRequest.serializer(), encoded)

        assertEquals(1, decoded.tools?.size)
        assertEquals("get_weather", decoded.tools?.first()?.function?.name)
        assertEquals("auto", decoded.tool_choice)
    }
}
