package com.s1mar.kompletions

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConcurrencyBugTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
    }

    private fun createSlowMockClient(): KompletionClient {
        val client = KompletionClient(KompletionConfig.custom("http://localhost"))
        val mockEngine = MockEngine { _ ->
            delay(500)
            respond(
                content = """{"error": "Simulated failure"}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
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
    fun `reproduce rollback bug when addMessage is called during failing sendFull`() = runTest {
        val client = createSlowMockClient()
        val conversation = Conversation(client, "test-model")

        val job = launch {
            try {
                conversation.sendFull("This will fail")
            } catch (e: Exception) {
                // Expected
            }
        }

        delay(100)
        
        val addJob = launch {
            conversation.addMessage("system", "Injected message")
        }

        job.join()
        addJob.join()

        val history = conversation.getHistory()
        
        assertEquals(1, history.size)
        assertEquals("system", history[0].role)
    }
}
