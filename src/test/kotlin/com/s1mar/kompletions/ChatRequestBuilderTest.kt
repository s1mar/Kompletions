package com.s1mar.kompletions

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRequestBuilderTest {

    @Test
    fun `build creates request with required fields`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            user("Hello")
        }.build()

        assertEquals("gpt-4", request.model)
        assertEquals(1, request.messages.size)
        assertEquals("user", request.messages[0].role)
        assertEquals("Hello", request.messages[0].textContent)
    }

    @Test
    fun `build throws when model is empty`() {
        assertThrows<IllegalArgumentException> {
            ChatRequestBuilder().apply {
                user("Hello")
            }.build()
        }
    }

    @Test
    fun `build throws when no messages`() {
        assertThrows<IllegalArgumentException> {
            ChatRequestBuilder().apply {
                model = "gpt-4"
            }.build()
        }
    }

    @Test
    fun `build includes all optional parameters`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            temperature = 0.5
            maxTokens = 100
            topP = 0.9
            frequencyPenalty = 0.1
            presencePenalty = 0.2
            stop = listOf("END")
            endUser = "user-123"
            n = 2
            responseFormat = ResponseFormat("json_object")
            toolChoice = "auto"
            seed = 42
            system("System prompt")
            user("User message")
        }.build()

        assertEquals(0.5, request.temperature)
        assertEquals(100, request.maxTokens)
        assertEquals(0.9, request.topP)
        assertEquals(0.1, request.frequencyPenalty)
        assertEquals(0.2, request.presencePenalty)
        assertEquals(listOf("END"), request.stop)
        assertEquals("user-123", request.user)
        assertEquals(2, request.n)
        assertEquals("json_object", request.responseFormat?.type)
        assertEquals("auto", request.toolChoice)
        assertEquals(42, request.seed)
    }

    @Test
    fun `messages are added in order`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            system("Be helpful")
            user("Hello")
            assistant("Hi there!")
            user("Follow-up")
        }.build()

        assertEquals(4, request.messages.size)
        assertEquals("system", request.messages[0].role)
        assertEquals("user", request.messages[1].role)
        assertEquals("assistant", request.messages[2].role)
        assertEquals("user", request.messages[3].role)
    }

    @Test
    fun `message function adds arbitrary role with optional name`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            message("tool", "result data", name = "get_weather")
        }.build()

        assertEquals("tool", request.messages[0].role)
        assertEquals("result data", request.messages[0].textContent)
        assertEquals("get_weather", request.messages[0].name)
    }

    @Test
    fun `defaults are null for optional fields`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            user("Hello")
        }.build()

        assertNull(request.temperature)
        assertNull(request.maxTokens)
        assertNull(request.topP)
        assertNull(request.frequencyPenalty)
        assertNull(request.presencePenalty)
        assertNull(request.stop)
        assertNull(request.n)
        assertNull(request.stream)
        assertNull(request.user)
        assertNull(request.responseFormat)
        assertNull(request.tools)
        assertNull(request.toolChoice)
        assertNull(request.seed)
        assertNull(request.streamOptions)
        assertNull(request.parallelToolCalls)
    }

    @Test
    fun `messages() prepopulates history before new messages`() {
        val history = listOf(
            Message("system", Content.Text("You are helpful")),
            Message("user", Content.Text("Previous question")),
            Message("assistant", Content.Text("Previous answer"))
        )
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            messages(history)
            user("Follow-up question")
        }.build()

        assertEquals(4, request.messages.size)
        assertEquals("system", request.messages[0].role)
        assertEquals("Previous question", request.messages[1].textContent)
        assertEquals("Previous answer", request.messages[2].textContent)
        assertEquals("Follow-up question", request.messages[3].textContent)
    }

    // --- Tool DSL tests ---

    @Test
    fun `tool() adds tool to request`() {
        val params = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}}}""")
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            user("What's the weather?")
            tool("get_weather", "Get weather for a city", params)
        }.build()

        val tools = request.tools
        assertEquals(1, tools?.size)
        assertEquals("get_weather", tools?.get(0)?.function?.name)
        assertEquals("function", tools?.get(0)?.type)
        assertEquals("Get weather for a city", tools?.get(0)?.function?.description)
    }

    @Test
    fun `tool() merges with explicitly set tools`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            user("Hello")
            tools = listOf(Tool(function = FunctionDef(name = "existing_tool")))
            tool("new_tool", "A new tool")
        }.build()

        val mergedTools = request.tools
        assertEquals(2, mergedTools?.size)
        assertEquals("existing_tool", mergedTools?.get(0)?.function?.name)
        assertEquals("new_tool", mergedTools?.get(1)?.function?.name)
    }

    @Test
    fun `toolResult() adds tool message with tool_call_id`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            toolResult("call_123", """{"temp": 72}""")
        }.build()

        assertEquals("tool", request.messages[0].role)
        assertEquals("call_123", request.messages[0].toolCallId)
        assertEquals("""{"temp": 72}""", request.messages[0].textContent)
    }

    @Test
    fun `assistantToolCalls() adds assistant message with tool calls`() {
        val calls = listOf(
            ToolCall(id = "call_1", function = ToolCallFunction("get_weather", """{"city":"NYC"}"""))
        )
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            assistantToolCalls(calls)
            toolResult("call_1", """{"temp": 65}""")
        }.build()

        assertEquals("assistant", request.messages[0].role)
        assertNull(request.messages[0].content)
        assertEquals(1, request.messages[0].toolCalls?.size)
        assertEquals("tool", request.messages[1].role)
    }

    // --- Structured output DSL tests ---

    @Test
    fun `jsonMode() sets response format`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            user("Give me JSON")
            jsonMode()
        }.build()

        assertEquals("json_object", request.responseFormat?.type)
        assertNull(request.responseFormat?.jsonSchema)
    }

    @Test
    fun `structuredOutput() sets json_schema response format`() {
        val schema = Json.parseToJsonElement("""{"type":"object"}""")
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            user("Extract data")
            structuredOutput("my_schema", schema)
        }.build()

        assertEquals("json_schema", request.responseFormat?.type)
        assertEquals("my_schema", request.responseFormat?.jsonSchema?.name)
        assertEquals(true, request.responseFormat?.jsonSchema?.strict)
    }

    // --- Vision DSL tests ---

    @Test
    fun `userWithImages() creates multipart content`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4o"
            userWithImages("What's in this image?", listOf("https://example.com/img.png"), detail = "high")
        }.build()

        assertEquals("user", request.messages[0].role)
        assertTrue(request.messages[0].content is Content.Parts)
        val parts = (request.messages[0].content as Content.Parts).parts
        assertEquals(2, parts.size)
        assertTrue(parts[0] is ContentPart.TextPart)
        assertTrue(parts[1] is ContentPart.ImagePart)
        assertEquals("high", (parts[1] as ContentPart.ImagePart).imageUrl.detail)
    }

    @Test
    fun `userWithImages() supports multiple images`() {
        val request = ChatRequestBuilder().apply {
            model = "gpt-4o"
            userWithImages("Compare these", listOf("https://a.com/1.png", "https://b.com/2.png"))
        }.build()

        val parts = (request.messages[0].content as Content.Parts).parts
        assertEquals(3, parts.size) // 1 text + 2 images
    }
}
