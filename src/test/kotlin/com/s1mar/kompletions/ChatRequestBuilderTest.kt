package com.s1mar.kompletions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals("Hello", request.messages[0].content)
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
        assertEquals("result data", request.messages[0].content)
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
    }

    @Test
    fun `messages() prepopulates history before new messages`() {
        val history = listOf(
            Message("system", "You are helpful"),
            Message("user", "Previous question"),
            Message("assistant", "Previous answer")
        )
        val request = ChatRequestBuilder().apply {
            model = "gpt-4"
            messages(history)
            user("Follow-up question")
        }.build()

        assertEquals(4, request.messages.size)
        assertEquals("system", request.messages[0].role)
        assertEquals("Previous question", request.messages[1].content)
        assertEquals("Previous answer", request.messages[2].content)
        assertEquals("Follow-up question", request.messages[3].content)
    }
}