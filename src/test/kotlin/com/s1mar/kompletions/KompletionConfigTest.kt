package com.s1mar.kompletions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KompletionConfigTest {

    @Test
    fun `openai factory sets correct defaults`() {
        val config = KompletionConfig.openai("sk-test")
        assertEquals("https://api.openai.com/v1", config.baseUrl)
        assertEquals("sk-test", config.apiKey)
        assertEquals(Provider.OPENAI, config.provider)
        assertNull(config.appUrl)
        assertNull(config.appName)
        assertEquals(60_000, config.timeout)
    }

    @Test
    fun `ollama factory sets correct defaults`() {
        val config = KompletionConfig.ollama()
        assertEquals("http://localhost:11434/v1", config.baseUrl)
        assertNull(config.apiKey)
        assertEquals(Provider.OLLAMA, config.provider)
    }

    @Test
    fun `ollama factory accepts custom baseUrl`() {
        val config = KompletionConfig.ollama("http://192.168.1.5:11434/v1")
        assertEquals("http://192.168.1.5:11434/v1", config.baseUrl)
    }

    @Test
    fun `openRouter factory sets correct defaults`() {
        val config = KompletionConfig.openRouter("sk-or-test", appUrl = "https://myapp.com", appName = "MyApp")
        assertEquals("https://openrouter.ai/api/v1", config.baseUrl)
        assertEquals("sk-or-test", config.apiKey)
        assertEquals(Provider.OPENROUTER, config.provider)
        assertEquals("https://myapp.com", config.appUrl)
        assertEquals("MyApp", config.appName)
    }

    @Test
    fun `custom factory sets correct defaults`() {
        val config = KompletionConfig.custom("https://api.example.com/v1", "key-123")
        assertEquals("https://api.example.com/v1", config.baseUrl)
        assertEquals("key-123", config.apiKey)
        assertEquals(Provider.CUSTOM, config.provider)
    }

    @Test
    fun `custom factory allows null apiKey`() {
        val config = KompletionConfig.custom("https://api.example.com/v1")
        assertNull(config.apiKey)
    }

    @Test
    fun `default timeout is 60 seconds`() {
        val config = KompletionConfig.openai("key")
        assertEquals(60_000L, config.timeout)
    }

    @Test
    fun `headers default to empty map`() {
        val config = KompletionConfig.openai("key")
        assertEquals(emptyMap(), config.headers)
    }

    @Test
    fun `custom headers can be set via copy`() {
        val config = KompletionConfig.openai("key").copy(headers = mapOf("X-Custom" to "value"))
        assertEquals("value", config.headers["X-Custom"])
    }
}
