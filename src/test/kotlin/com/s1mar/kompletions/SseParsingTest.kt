package com.s1mar.kompletions

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SseParsingTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
    }

    private fun createMockClient(responseContent: String): KompletionClient {
        val client = KompletionClient(KompletionConfig.custom("http://localhost"))
        val mockEngine = MockEngine { _ ->
            respond(
                content = responseContent,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(testJson) }
        }
        val field = KompletionClient::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(client, mockHttpClient)
        return client
    }

    @Test
    fun `parse standard single-line SSE`() = runTest {
        val sseData = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"Hello"}}],"object":"chat.completion.chunk","created":1,"model":"test"}
            
            data: {"id":"2","choices":[{"index":0,"delta":{"content":" World"}}],"object":"chat.completion.chunk","created":1,"model":"test"}
            
            data: [DONE]
        """.trimIndent()

        val client = createMockClient(sseData)
        val request = ChatRequest(model = "test", messages = listOf(Message("user", Content.Text("hi"))), stream = true)
        
        val chunks = client.streamChat(request).toList()
        
        assertEquals(2, chunks.size)
        assertEquals("Hello", chunks[0].choices[0].delta.content)
        assertEquals(" World", chunks[1].choices[0].delta.content)
    }

    @Test
    fun `parse multi-line JSON in SSE`() = runTest {
        val sseData = """
            data: {
            data:   "id": "1",
            data:   "choices": [
            data:     { "index": 0, "delta": { "content": "Line1" } }
            data:   ],
            data:   "object": "chat.completion.chunk",
            data:   "created": 1,
            data:   "model": "test"
            data: }
            
            data: [DONE]
        """.trimIndent()

        val client = createMockClient(sseData)
        val request = ChatRequest(model = "test", messages = listOf(Message("user", Content.Text("hi"))), stream = true)
        
        val chunks = client.streamChat(request).toList()
        
        assertEquals(1, chunks.size)
        assertEquals("Line1", chunks[0].choices[0].delta.content)
    }
}