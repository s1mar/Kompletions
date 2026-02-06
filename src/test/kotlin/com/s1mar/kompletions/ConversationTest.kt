package com.s1mar.kompletions

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun mockResponse(content: String, id: String = "test-id"): String = """
        {
            "id": "$id",
            "object": "chat.completion",
            "created": 1700000000,
            "model": "test-model",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "$content"
                    },
                    "finish_reason": "stop"
                }
            ]
        }
    """.trimIndent()

    private fun createMockClient(vararg responses: String): KompletionClient {
        val responseIterator = responses.iterator()
        val client = KompletionClient(KompletionConfig.custom("http://localhost"))

        val mockEngine = MockEngine { _ ->
            respond(
                content = if (responseIterator.hasNext()) responseIterator.next() else """{"error":"no more responses"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(testJson)
            }
        }

        val field = KompletionClient::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(client, mockHttpClient)

        return client
    }

    private fun createFailingClient(statusCode: HttpStatusCode = HttpStatusCode.InternalServerError): KompletionClient {
        val client = KompletionClient(KompletionConfig.custom("http://localhost"))

        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"error": "server error"}""",
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(testJson)
            }
        }

        val field = KompletionClient::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(client, mockHttpClient)

        return client
    }

    @Test
    fun `conversation maintains message history`() = runTest {
        val client = createMockClient(
            mockResponse("I'm an AI assistant."),
            mockResponse("Sure, here's an example.")
        )

        val conversation = client.conversation("test-model", systemPrompt = "Be helpful")

        conversation.send("Who are you?")
        conversation.send("Show me an example.")

        val history = conversation.getHistory()
        assertEquals(5, history.size)
        assertEquals("system", history[0].role)
        assertEquals("Be helpful", history[0].content)
        assertEquals("user", history[1].role)
        assertEquals("assistant", history[2].role)
        assertEquals("I'm an AI assistant.", history[2].content)
        assertEquals("user", history[3].role)
        assertEquals("assistant", history[4].role)
        assertEquals("Sure, here's an example.", history[4].content)
    }

    @Test
    fun `conversation without system prompt`() = runTest {
        val client = createMockClient(mockResponse("Hello!"))

        val conversation = client.conversation("test-model")
        conversation.send("Hi")

        val history = conversation.getHistory()
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("assistant", history[1].role)
    }

    @Test
    fun `conversation returns assistant content`() = runTest {
        val client = createMockClient(mockResponse("42"))

        val conversation = client.conversation("test-model")
        val result = conversation.send("What is the meaning of life?")

        assertEquals("42", result)
    }

    @Test
    fun `sendFull returns full ChatResponse`() = runTest {
        val client = createMockClient(mockResponse("Hello!", id = "resp-42"))

        val conversation = client.conversation("test-model")
        val response = conversation.sendFull("Hi")

        assertEquals("resp-42", response.id)
        assertEquals("Hello!", response.choices.first().message.content)
        assertEquals("stop", response.choices.first().finishReason)
    }

    @Test
    fun `initialHistory seeds the conversation`() = runTest {
        val client = createMockClient(mockResponse("The answer is 42."))

        val savedHistory = listOf(
            Message("system", "You are a math tutor."),
            Message("user", "What is 6 times 7?"),
            Message("assistant", "42"),
        )

        val conversation = client.conversation("test-model", initialHistory = savedHistory)
        conversation.send("Are you sure?")

        val history = conversation.getHistory()
        assertEquals(5, history.size)
        assertEquals("system", history[0].role)
        assertEquals("You are a math tutor.", history[0].content)
        assertEquals("What is 6 times 7?", history[1].content)
        assertEquals("42", history[2].content)
        assertEquals("Are you sure?", history[3].content)
        assertEquals("The answer is 42.", history[4].content)
    }

    @Test
    fun `initialHistory with systemPrompt does not duplicate system message`() = runTest {
        val history = listOf(
            Message("system", "Existing system prompt"),
            Message("user", "Hello"),
        )

        // systemPrompt should be ignored since initialHistory already starts with system
        val conversation = Conversation(
            client = KompletionClient(KompletionConfig.custom("http://localhost")),
            model = "test",
            systemPrompt = "New system prompt",
            initialHistory = history
        )

        val result = conversation.getHistory()
        assertEquals(2, result.size)
        assertEquals("Existing system prompt", result[0].content)
    }

    @Test
    fun `initialHistory without system message gets systemPrompt prepended`() = runTest {
        val history = listOf(
            Message("user", "Hello"),
            Message("assistant", "Hi!"),
        )

        val conversation = Conversation(
            client = KompletionClient(KompletionConfig.custom("http://localhost")),
            model = "test",
            systemPrompt = "Be helpful",
            initialHistory = history
        )

        val result = conversation.getHistory()
        assertEquals(3, result.size)
        assertEquals("system", result[0].role)
        assertEquals("Be helpful", result[0].content)
        assertEquals("user", result[1].role)
    }

    @Test
    fun `addMessage injects message without API call`() = runTest {
        val conversation = Conversation(
            client = KompletionClient(KompletionConfig.custom("http://localhost")),
            model = "test"
        )

        conversation.addMessage("user", "Hello")
        conversation.addMessage("assistant", "Hi there")
        conversation.addMessage("tool", """{"temp": 72}""")

        val history = conversation.getHistory()
        assertEquals(3, history.size)
        assertEquals("tool", history[2].role)
    }

    @Test
    fun `clearHistory removes all messages`() = runTest {
        val conversation = Conversation(
            client = KompletionClient(KompletionConfig.custom("http://localhost")),
            model = "test",
            systemPrompt = "System"
        )

        conversation.addMessage("user", "Hello")
        assertEquals(2, conversation.getHistory().size)

        conversation.clearHistory()
        assertTrue(conversation.getHistory().isEmpty())
    }

    @Test
    fun `sendFull rolls back user message on API failure`() = runTest {
        val client = createFailingClient()

        val conversation = client.conversation("test-model", systemPrompt = "Be helpful")

        assertThrows<KompletionApiException> {
            conversation.sendFull("This should fail")
        }

        val history = conversation.getHistory()
        assertEquals(1, history.size) // only system message remains
        assertEquals("system", history[0].role)
    }

    @Test
    fun `send rolls back user message on API failure`() = runTest {
        val client = createFailingClient()

        val conversation = client.conversation("test-model")

        assertThrows<KompletionApiException> {
            conversation.send("This should fail")
        }

        val history = conversation.getHistory()
        assertTrue(history.isEmpty())
    }
}
