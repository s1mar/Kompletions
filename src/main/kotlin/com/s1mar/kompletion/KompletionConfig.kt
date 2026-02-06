package com.s1mar.kompletion

/**
 * Configuration for Kompletion client.
 */
data class KompletionConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val provider: Provider = Provider.CUSTOM,
    val appUrl: String? = null,
    val appName: String? = null,
    val timeout: Long = 60_000,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * OpenAI configuration
         */
        fun openai(apiKey: String): KompletionConfig {
            return KompletionConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = apiKey,
                provider = Provider.OPENAI
            )
        }

        /**
         * Ollama configuration
         */
        fun ollama(baseUrl: String = "http://localhost:11434/v1"): KompletionConfig {
            return KompletionConfig(
                baseUrl = baseUrl,
                provider = Provider.OLLAMA
            )
        }

        /**
         * OpenRouter configuration
         */
        fun openRouter(
            apiKey: String,
            appUrl: String? = null,
            appName: String? = null
        ): KompletionConfig {
            return KompletionConfig(
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = apiKey,
                provider = Provider.OPENROUTER,
                appUrl = appUrl,
                appName = appName
            )
        }

        /**
         * Custom OpenAI-compatible provider
         */
        fun custom(baseUrl: String, apiKey: String? = null): KompletionConfig {
            return KompletionConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                provider = Provider.CUSTOM
            )
        }
    }
}

/**
 * Supported providers
 */
enum class Provider {
    OPENAI,
    OLLAMA,
    OPENROUTER,
    CUSTOM
}
