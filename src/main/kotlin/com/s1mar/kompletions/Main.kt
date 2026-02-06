package com.s1mar.kompletions

import kotlinx.coroutines.runBlocking

/**
 * Quick demo of Kompletion.
 * Connects to a local Ollama instance by default.
 */
fun main() = runBlocking {
    KompletionClient.ollama().use { client ->
        print("Streaming: ")
        client.streamChatCompletion {
            model = "gemma3:4b"

            system("You are a helpful assistant. Keep responses concise.")
            user("Write a 100 word essay")
        }.collect { chunk ->
            chunk.choices.firstOrNull()?.delta?.content?.let {
                print(it)
                System.out.flush()
            }
        }
        println()
    }
}
