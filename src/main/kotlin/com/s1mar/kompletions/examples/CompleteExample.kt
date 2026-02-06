package com.s1mar.kompletions.examples

import com.s1mar.kompletions.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    KompletionClient.ollama().use { client ->
        println("=== Example 1: Simple message ===")
        simpleMessage(client)

        println("\n=== Example 2: DSL builder ===")
        dslBuilder(client)

        println("\n=== Example 3: Conversation (with history) ===")
        conversationExample(client)

        println("\n=== Example 4: Streaming ===")
        streamingExample(client)

        println("\n=== Example 5: Direct request/response ===")
        directRequestResponse(client)
    }
}

suspend fun simpleMessage(client: KompletionClient) {
    val response = client.sendMessage(
        model = "gemma3:4b",
        message = "What is Kotlin?",
        systemPrompt = "You are a helpful programming assistant",
        temperature = 0.7,
        maxTokens = 100
    )

    println("Response: ${response.choices.first().message.content}")
    println("Tokens used: ${response.usage?.totalTokens}")
}

suspend fun dslBuilder(client: KompletionClient) {
    val response = client.chatCompletion {
        model = "gemma3:4b"
        temperature = 0.8
        maxTokens = 150

        system("You are a Kotlin expert")
        user("Write a hello world function in Kotlin")
    }

    println("Response: ${response.choices.first().message.content}")
}

suspend fun conversationExample(client: KompletionClient) {
    val conv = client.conversation(
        model = "gemma3:4b",
        systemPrompt = "You are a helpful coding assistant. Keep responses concise."
    )

    val response1 = conv.send("What is a data class in Kotlin?")
    println("Assistant: $response1")

    val response2 = conv.send("Can you show me an example?")
    println("Assistant: $response2")

    println("\nFull conversation history:")
    conv.getHistory().forEach { msg ->
        println("${msg.role}: ${msg.content}")
    }
}

suspend fun streamingExample(client: KompletionClient) {
    print("Streaming: ")
    client.streamChatCompletion {
        model = "gemma3:4b"
        maxTokens = 100

        system("You are a helpful assistant. Keep responses concise.")
        user("Explain what coroutines are in Kotlin.")
    }.collect { chunk ->
        chunk.choices.firstOrNull()?.delta?.content?.let {
            print(it)
            System.out.flush()
        }
    }
    println()
}

suspend fun directRequestResponse(client: KompletionClient) {
    val request = ChatRequest(
        model = "gemma3:4b",
        messages = listOf(
            Message("system", "You are a JSON expert"),
            Message("user", "Create a simple JSON object with name and age")
        ),
        temperature = 0.5,
        maxTokens = 100,
        responseFormat = ResponseFormat("json_object")
    )

    val response = client.chat(request)

    println("ID: ${response.id}")
    println("Model: ${response.model}")
    println("Response: ${response.choices.first().message.content}")
    println("Finish reason: ${response.choices.first().finishReason}")
    println("Usage: ${response.usage}")
}