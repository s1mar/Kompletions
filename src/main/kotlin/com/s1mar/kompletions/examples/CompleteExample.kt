package com.s1mar.kompletions.examples

import com.s1mar.kompletions.*
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main() = runBlocking {
    KompletionClient.ollama().use { client ->
        runExample("Example 1: Simple message") { simpleMessage(client) }
        runExample("Example 2: DSL builder") { dslBuilder(client) }
        runExample("Example 3: Conversation (with history)") { conversationExample(client) }
        runExample("Example 4: Streaming") { streamingExample(client) }
        runExample("Example 5: Direct request/response") { directRequestResponse(client) }
    }

    val env = dotenv()
    val apiKey = env["OPEN_ROUTER_KEY"]
    KompletionClient.openRouter(apiKey).use { client ->
        runExample("Example 6: Tool calling") { toolCallingExample(client) }
        runExample("Example 7: Structured output") { structuredOutputExample(client) }
        runExample("Example 8: Vision / multi-modal") { visionExample(client) }
    }
}

private suspend fun runExample(name: String, block: suspend () -> Unit) {
    println("\n=== $name ===")
    try {
        block()
    } catch (e: KompletionException) {
        println("ERROR: ${e.message}")
    } catch (e: Exception) {
        println("ERROR: ${e::class.simpleName}: ${e.message}")
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

    println("Response: ${response.choices.first().message.textContent}")
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

    println("Response: ${response.choices.first().message.textContent}")
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
        println("${msg.role}: ${msg.textContent}")
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
            Message("system", Content.Text("You are a JSON expert")),
            Message("user", Content.Text("Create a simple JSON object with name and age"))
        ),
        temperature = 0.5,
        maxTokens = 100,
        responseFormat = ResponseFormat("json_object")
    )

    val response = client.chat(request)

    println("ID: ${response.id}")
    println("Model: ${response.model}")
    println("Response: ${response.choices.first().message.textContent}")
    println("Finish reason: ${response.choices.first().finishReason}")
    println("Usage: ${response.usage}")
}

// Tool calling requires a model that supports function calling.
// OpenRouter: openai/gpt-4o-mini, anthropic/claude-3.5-sonnet, mistralai/mistral-large, etc.
suspend fun toolCallingExample(client: KompletionClient) {
    val weatherParams = Json.parseToJsonElement(
        """
        {
            "type": "object",
            "properties": {
                "location": { "type": "string", "description": "City name" }
            },
            "required": ["location"]
        }
    """.trimIndent()
    )

    // Step 1: Send a request with tool definitions
    val response = client.chatCompletion {
        model = "openai/gpt-4o-mini"
        user("What's the weather in London?")
        tool("get_weather", "Get the current weather for a location", weatherParams)
        toolChoice = "auto"
    }

    val assistantMsg = response.choices.first().message
    val toolCall = assistantMsg.toolCalls?.firstOrNull()

    if (toolCall != null) {
        println("Model wants to call: ${toolCall.function.name}")
        println("Arguments: ${toolCall.function.arguments}")

        // Step 2: Provide the tool result and get the final answer
        val followUp = client.chatCompletion {
            model = "openai/gpt-4o-mini"
            user("What's the weather in London?")
            assistantToolCalls(assistantMsg.toolCalls.orEmpty())
            toolResult(toolCall.id, """{"temperature": 18, "condition": "cloudy"}""")
        }

        println("Final answer: ${followUp.choices.first().message.textContent}")
    } else {
        println("Model responded directly: ${assistantMsg.textContent}")
    }
}

// Structured outputs require a model that supports json_schema response format.
// OpenRouter: openai/gpt-4o-mini, openai/gpt-4o, etc.
suspend fun structuredOutputExample(client: KompletionClient) {
    val schema = Json.parseToJsonElement(
        """
        {
            "type": "object",
            "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer" }
            },
            "required": ["name", "age"],
            "additionalProperties": false
        }
    """.trimIndent()
    )

    val response = client.chatCompletion {
        model = "openai/gpt-4o-mini"
        system("Extract person information from the text.")
        user("John Smith is 35 years old and lives in New York.")
        structuredOutput("person", schema)
    }

    println("Structured output: ${response.choices.first().message.textContent}")
}

// Vision requires a model that supports image inputs.
// OpenRouter: openai/gpt-4o-mini, openai/gpt-4o, google/gemini-2.0-flash-exp:free, etc.
suspend fun visionExample(client: KompletionClient) {
    val response = client.chatCompletion {
        model = "openai/gpt-4o-mini"
        userWithImages(
            "What do you see in this image? Respond in one sentence.",
            listOf("https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png"),
            detail = "low"
        )
        maxTokens = 100
    }

    println("Vision response: ${response.choices.first().message.textContent}")
}
