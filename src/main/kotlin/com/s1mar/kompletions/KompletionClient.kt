package com.s1mar.kompletions

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Base exception for all Kompletion errors.
 */
open class KompletionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when the API returns a non-success HTTP status.
 */
class KompletionApiException(
    val statusCode: Int,
    val responseBody: String
) : KompletionException("API returned HTTP $statusCode: $responseBody")

/**
 * Universal Kotlin client for OpenAI-compatible chat completion APIs.
 * Works with: OpenAI, Ollama, OpenRouter, and any OpenAI-compatible service.
 */
class KompletionClient(
    val config: KompletionConfig
) : java.io.Closeable {

    private val baseUrl = config.baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        prettyPrint = false
        coerceInputValues = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout
            connectTimeoutMillis = config.timeout
            socketTimeoutMillis = config.timeout
        }
        expectSuccess = false
    }

    /**
     * Send a chat completion request and return the response.
     */
    suspend fun chat(request: ChatRequest): ChatResponse {
        val response = httpClient.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw KompletionApiException(response.status.value, body)
        }

        return response.body()
    }

    /**
     * Stream a chat completion as a [Flow] of [ChatCompletionChunk].
     * The request must have `stream = true` set.
     */
    fun streamChat(request: ChatRequest): Flow<ChatCompletionChunk> = flow {
        httpClient.preparePost("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(request)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                throw KompletionApiException(response.status.value, body)
            }

            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.startsWith("data: ")) {
                    val data = trimmed.removePrefix("data: ").trim()
                    if (data == "[DONE]") return@execute
                    val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), data)
                    emit(chunk)
                }
            }
        }
    }

    private fun HttpRequestBuilder.applyAuth() {
        config.apiKey?.let { key ->
            headers {
                when (config.provider) {
                    Provider.OPENAI, Provider.CUSTOM -> append("Authorization", "Bearer $key")
                    Provider.OPENROUTER -> {
                        append("Authorization", "Bearer $key")
                        append("HTTP-Referer", config.appUrl ?: "https://github.com/s1mar/kompletion")
                        config.appName?.let { append("X-Title", it) }
                    }
                    Provider.OLLAMA -> {}
                }
            }
        }
        config.headers.forEach { (key, value) ->
            headers.append(key, value)
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        fun openai(apiKey: String): KompletionClient {
            return KompletionClient(KompletionConfig.openai(apiKey))
        }

        fun ollama(baseUrl: String = "http://localhost:11434/v1"): KompletionClient {
            return KompletionClient(KompletionConfig.ollama(baseUrl))
        }

        fun openRouter(apiKey: String): KompletionClient {
            return KompletionClient(KompletionConfig.openRouter(apiKey))
        }

        fun custom(baseUrl: String, apiKey: String? = null): KompletionClient {
            return KompletionClient(KompletionConfig.custom(baseUrl, apiKey))
        }
    }
}

/**
 * DSL function to stream chat completions.
 * Returns a [Flow] of [ChatCompletionChunk].
 */
fun KompletionClient.streamChatCompletion(
    block: ChatRequestBuilder.() -> Unit
): Flow<ChatCompletionChunk> {
    val request = ChatRequestBuilder().apply(block).build().copy(stream = true)
    return streamChat(request)
}
