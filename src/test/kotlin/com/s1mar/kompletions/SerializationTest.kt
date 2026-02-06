package com.s1mar.kompletions

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun `ChatRequest serializes to JSON correctly`() {
        val request = ChatRequest(
            model = "gpt-4",
            messages = listOf(Message("user", Content.Text("Hello"))),
            temperature = 0.7,
            maxTokens = 100
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)
        val decoded = json.decodeFromString(ChatRequest.serializer(), encoded)

        assertEquals(request.model, decoded.model)
        assertEquals(request.messages, decoded.messages)
        assertEquals(request.temperature, decoded.temperature)
        assertEquals(request.maxTokens, decoded.maxTokens)
    }

    @Test
    fun `ChatRequest omits null fields when encodeDefaults is false`() {
        val request = ChatRequest(
            model = "gpt-4",
            messages = listOf(Message("user", Content.Text("Hello")))
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)

        assert(!encoded.contains("temperature")) { "Should not contain temperature" }
        assert(!encoded.contains("max_tokens")) { "Should not contain max_tokens" }
        assert(!encoded.contains("stream")) { "Should not contain stream" }
        assert(!encoded.contains("seed")) { "Should not contain seed" }
        assert(!encoded.contains("stream_options")) { "Should not contain stream_options" }
        assert(!encoded.contains("parallel_tool_calls")) { "Should not contain parallel_tool_calls" }
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
        assertEquals("Hello!", response.choices[0].message.textContent)
        assertEquals("stop", response.choices[0].finishReason)
        assertEquals(10, response.usage?.promptTokens)
        assertEquals(5, response.usage?.completionTokens)
        assertEquals(15, response.usage?.totalTokens)
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
        assertNull(chunk.choices[0].finishReason)
    }

    @Test
    fun `Message serializes with optional name field`() {
        val msg = Message("user", Content.Text("Hello"), name = "John")
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
            messages = listOf(Message("user", Content.Text("What's the weather?"))),
            tools = listOf(
                Tool(
                    type = "function",
                    function = FunctionDef(
                        name = "get_weather",
                        description = "Get current weather"
                    )
                )
            ),
            toolChoice = "auto"
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)
        val decoded = json.decodeFromString(ChatRequest.serializer(), encoded)

        assertEquals(1, decoded.tools?.size)
        assertEquals("get_weather", decoded.tools?.first()?.function?.name)
        assertEquals("auto", decoded.toolChoice)
    }

    // --- Tool call tests ---

    @Test
    fun `Message with tool_calls serializes correctly`() {
        val msg = Message(
            role = "assistant",
            toolCalls = listOf(
                ToolCall(
                    id = "call_123",
                    function = ToolCallFunction(name = "get_weather", arguments = """{"city":"London"}""")
                )
            )
        )
        val encoded = json.encodeToString(Message.serializer(), msg)
        assert(encoded.contains("tool_calls"))
        assert(encoded.contains("call_123"))

        val decoded = json.decodeFromString(Message.serializer(), encoded)
        val decodedCalls = decoded.toolCalls
        assertEquals(1, decodedCalls?.size)
        assertEquals("call_123", decodedCalls?.get(0)?.id)
        assertEquals("get_weather", decodedCalls?.get(0)?.function?.name)
    }

    @Test
    fun `ChatResponse with tool_calls deserializes correctly`() {
        val responseJson = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1700000000,
            "model": "gpt-4",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [{
                        "id": "call_abc",
                        "type": "function",
                        "function": {
                            "name": "get_weather",
                            "arguments": "{\"location\":\"Paris\"}"
                        }
                    }]
                },
                "finish_reason": "tool_calls"
            }]
        }
        """.trimIndent()

        val response = json.decodeFromString(ChatResponse.serializer(), responseJson)
        val msg = response.choices[0].message
        assertNull(msg.content)
        val calls = msg.toolCalls
        assertEquals(1, calls?.size)
        assertEquals("call_abc", calls?.get(0)?.id)
        assertEquals("get_weather", calls?.get(0)?.function?.name)
        assertEquals("{\"location\":\"Paris\"}", calls?.get(0)?.function?.arguments)
    }

    @Test
    fun `tool result message serializes with tool_call_id`() {
        val msg = Message(role = "tool", content = Content.Text("""{"temp": 72}"""), toolCallId = "call_abc")
        val encoded = json.encodeToString(Message.serializer(), msg)
        assert(encoded.contains("tool_call_id"))
        assert(encoded.contains("call_abc"))

        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals("tool", decoded.role)
        assertEquals("call_abc", decoded.toolCallId)
    }

    // --- Structured output tests ---

    @Test
    fun `ResponseFormat with json_schema serializes correctly`() {
        val schema = json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"}}}""")
        val format = ResponseFormat(
            type = "json_schema",
            jsonSchema = JsonSchema(name = "person", schema = schema, strict = true)
        )
        val encoded = json.encodeToString(ResponseFormat.serializer(), format)
        assert(encoded.contains("json_schema"))
        assert(encoded.contains("\"strict\":true"))

        val decoded = json.decodeFromString(ResponseFormat.serializer(), encoded)
        assertEquals("json_schema", decoded.type)
        assertEquals("person", decoded.jsonSchema?.name)
        assertEquals(true, decoded.jsonSchema?.strict)
    }

    @Test
    fun `ResponseFormat without json_schema omits it`() {
        val format = ResponseFormat(type = "json_object")
        val encoded = json.encodeToString(ResponseFormat.serializer(), format)
        assert(!encoded.contains("json_schema"))
    }

    // --- Streaming tool call tests ---

    @Test
    fun `ChunkDelta with tool_calls deserializes correctly`() {
        val chunkJson = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion.chunk",
            "created": 1700000000,
            "model": "gpt-4",
            "choices": [{
                "index": 0,
                "delta": {
                    "tool_calls": [{
                        "index": 0,
                        "id": "call_abc",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": ""}
                    }]
                },
                "finish_reason": null
            }]
        }
        """.trimIndent()

        val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), chunkJson)
        assertEquals(1, chunk.choices[0].delta.toolCalls?.size)
        assertEquals("call_abc", chunk.choices[0].delta.toolCalls!![0].id)
        assertEquals("get_weather", chunk.choices[0].delta.toolCalls!![0].function?.name)
    }

    // --- New params tests ---

    @Test
    fun `ChatRequest with seed and stream_options serializes correctly`() {
        val request = ChatRequest(
            model = "gpt-4",
            messages = listOf(Message("user", Content.Text("Hello"))),
            seed = 42,
            streamOptions = StreamOptions(includeUsage = true),
            parallelToolCalls = false
        )

        val encoded = json.encodeToString(ChatRequest.serializer(), request)
        assert(encoded.contains("\"seed\":42"))
        assert(encoded.contains("\"stream_options\""))
        assert(encoded.contains("\"include_usage\":true"))
        assert(encoded.contains("\"parallel_tool_calls\":false"))

        val decoded = json.decodeFromString(ChatRequest.serializer(), encoded)
        assertEquals(42, decoded.seed)
        assertEquals(true, decoded.streamOptions?.includeUsage)
        assertEquals(false, decoded.parallelToolCalls)
    }

    // --- Content serialization tests ---

    @Test
    fun `Content Text serializes as plain string`() {
        val msg = Message("user", Content.Text("Hello"))
        val encoded = json.encodeToString(Message.serializer(), msg)
        assert(encoded.contains(""""content":"Hello""""))
    }

    @Test
    fun `Content Parts serializes as array`() {
        val msg = Message("user", Content.Parts(listOf(
            ContentPart.TextPart("Describe this"),
            ContentPart.ImagePart(ImageUrl("https://example.com/img.png", "high"))
        )))
        val encoded = json.encodeToString(Message.serializer(), msg)
        assert(encoded.contains(""""type":"text""""))
        assert(encoded.contains(""""type":"image_url""""))
        assert(encoded.contains("https://example.com/img.png"))
        assert(encoded.contains(""""detail":"high""""))
    }

    @Test
    fun `Content deserializes string as Content Text`() {
        val msgJson = """{"role":"user","content":"Hello"}"""
        val msg = json.decodeFromString(Message.serializer(), msgJson)
        assertEquals("Hello", msg.textContent)
        assertTrue(msg.content is Content.Text)
    }

    @Test
    fun `Content deserializes array as Content Parts`() {
        val msgJson = """{"role":"user","content":[{"type":"text","text":"Look at this"},{"type":"image_url","image_url":{"url":"https://example.com/img.png"}}]}"""
        val msg = json.decodeFromString(Message.serializer(), msgJson)
        val content = msg.content
        assertTrue(content is Content.Parts)
        assertEquals(2, content.parts.size)
    }

    @Test
    fun `Content Parts round-trip preserves all fields`() {
        val original = Message("user", Content.Parts(listOf(
            ContentPart.TextPart("Hello"),
            ContentPart.ImagePart(ImageUrl("https://example.com/img.png", "low"))
        )))
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)

        val decodedContent = decoded.content
        assertTrue(decodedContent is Content.Parts)
        val parts = decodedContent.parts
        assertEquals(2, parts.size)
        assertEquals("Hello", (parts[0] as ContentPart.TextPart).text)
        assertEquals("https://example.com/img.png", (parts[1] as ContentPart.ImagePart).imageUrl.url)
        assertEquals("low", (parts[1] as ContentPart.ImagePart).imageUrl.detail)
    }

    @Test
    fun `null content remains null after deserialization`() {
        val msgJson = """{"role":"assistant"}"""
        val msg = json.decodeFromString(Message.serializer(), msgJson)
        assertNull(msg.content)
        assertNull(msg.textContent)
    }

    @Test
    fun `textContent extracts text from Parts`() {
        val msg = Message("user", Content.Parts(listOf(
            ContentPart.TextPart("Hello "),
            ContentPart.ImagePart(ImageUrl("https://example.com/img.png")),
            ContentPart.TextPart("world")
        )))
        assertEquals("Hello world", msg.textContent)
    }
}
