# Kompletions

A lightweight Kotlin client for OpenAI-compatible chat completion APIs. Works with OpenAI, Ollama, OpenRouter, and any provider that implements the OpenAI API format.

## Features

- **Kotlin DSL** for building chat requests
- **Multi-provider** support (OpenAI, Ollama, OpenRouter, custom)
- **Streaming** via `Flow<ChatCompletionChunk>` (SSE)
- **Conversation management** with message history, rollback on failure, and concurrency safety
- **Custom headers** for authentication proxies, observability, etc.
- **Minimal dependencies** — Ktor + kotlinx-serialization only
- **Generated OpenAPI models** from the official OpenAI spec

## Setup

### Prerequisites

- JDK 17+
- Gradle 8+

### Build

```bash
./gradlew build
```

This downloads the OpenAI spec and generates type-safe models automatically.

### Gradle dependency (local)

Add as a composite build or publish to a local Maven repo:

```kotlin
// settings.gradle.kts
includeBuild("path/to/Kompletions")
```

## Quick Start

### One-liner

```kotlin
KompletionClient.ollama().use { client ->
    val response = client.sendMessage("llama2", "What is Kotlin?")
    println(response.choices.first().message.content)
}
```

### DSL Builder

```kotlin
KompletionClient.openai("api-key").use { client ->
    val response = client.chatCompletion {
        model = "gpt-4"
        temperature = 0.7
        maxTokens = 200

        system("You are a helpful assistant.")
        user("Explain Kotlin coroutines in two sentences.")
    }
    println(response.choices.first().message.content)
}
```

### Conversation (multi-turn)

```kotlin
KompletionClient.ollama().use { client ->
    val conversation = client.conversation("llama2", systemPrompt = "You are a tutor.")

    val a1 = conversation.send("What is a data class?")
    val a2 = conversation.send("Show me an example.")

    // Access full response
    val fullResponse = conversation.sendFull("Explain inheritance.")
    println("Tokens: ${fullResponse.usage?.total_tokens}")

    // Inspect history
    conversation.getHistory().forEach { msg ->
        println("${msg.role}: ${msg.content}")
    }
}
```

### Resuming a conversation

```kotlin
// Save history from a previous session
val savedHistory = previousConversation.getHistory()

// Resume later
val conversation = client.conversation(
    model = "llama2",
    initialHistory = savedHistory
)
conversation.send("Where were we?")
```

### Streaming

```kotlin
KompletionClient.openai("api-key").use { client ->
    client.streamChatCompletion {
        model = "gpt-4"
        user("Write a haiku about Kotlin.")
    }.collect { chunk ->
        chunk.choices.firstOrNull()?.delta?.content?.let { print(it) }
    }
    println()
}
```

## Provider Setup

### OpenAI

```kotlin
val client = KompletionClient.openai("api-key")
```

### Ollama (local)

```kotlin
val client = KompletionClient.ollama()                          // default localhost:11434
val client = KompletionClient.ollama("http://192.168.1.5:11434/v1")  // custom host
```

### OpenRouter

```kotlin
val client = KompletionClient.openRouter("api-key")
```

### Custom provider

```kotlin
val client = KompletionClient.custom(
    baseUrl = "https://api.example.com/v1",
    apiKey = "your-key"
)
```

### Custom headers and timeout

```kotlin
val client = KompletionClient(
    KompletionConfig.openai("api-key").copy(
        timeout = 120_000,
        headers = mapOf("X-Request-Id" to "abc-123")
    )
)
```

## Error Handling

All Kompletions errors extend `KompletionException`:

```kotlin
try {
    val response = client.chat(request)
} catch (e: KompletionApiException) {
    // HTTP error from the API (e.g., 401, 429, 500)
    println("HTTP ${e.statusCode}: ${e.responseBody}")
} catch (e: KompletionException) {
    // Other Kompletions errors (e.g., no choices returned)
    println("Error: ${e.message}")
}
```

Conversation methods automatically roll back the user message from history on failure, keeping the conversation state clean.

## API Reference

### ChatRequest parameters (DSL)

| DSL Property        | Wire Name          | Type           | Description                        |
|---------------------|--------------------|----------------|------------------------------------|
| `model`             | `model`            | `String`       | Model name (required)              |
| —                   | `messages`         | `List<Message>`| Conversation messages (required)   |
| `temperature`       | `temperature`      | `Double?`      | Randomness (0.0–2.0)              |
| `maxTokens`         | `max_tokens`       | `Int?`         | Maximum response length            |
| `topP`              | `top_p`            | `Double?`      | Nucleus sampling                   |
| `frequencyPenalty`  | `frequency_penalty`| `Double?`      | Reduce repetition                  |
| `presencePenalty`   | `presence_penalty` | `Double?`      | Encourage new topics               |
| `stop`              | `stop`             | `List<String>?`| Stop sequences                     |
| `n`                 | `n`                | `Int?`         | Number of completions              |
| `endUser`           | `user`             | `String?`      | End-user identifier                |
| `responseFormat`    | `response_format`  | `ResponseFormat?` | Response format (e.g. JSON)     |
| `tools`             | `tools`            | `List<Tool>?`  | Function calling tools             |
| `toolChoice`        | `tool_choice`      | `String?`      | Tool selection strategy            |

### ChatResponse structure

```
ChatResponse
├── id: String
├── object: String
├── created: Long
├── model: String
├── choices: List<Choice>
│   ├── index: Int
│   ├── message: Message (role, content?, name?)
│   └── finish_reason: String?
└── usage: Usage?
    ├── prompt_tokens: Int
    ├── completion_tokens: Int
    └── total_tokens: Int
```

### Conversation API

| Method                  | Returns         | Description                                    |
|-------------------------|-----------------|------------------------------------------------|
| `send(message)`         | `String`        | Send message, get assistant text (or `""`)     |
| `sendFull(message)`     | `ChatResponse`  | Send message, get full API response            |
| `addMessage(role, ...)`  | —               | Inject a message without an API call           |
| `getHistory()`          | `List<Message>` | Snapshot of current message history            |
| `clearHistory()`        | —               | Remove all messages                            |

## OpenAPI Generated Models

On build, Kompletions generates Kotlin data classes from the official OpenAI OpenAPI spec using `kotlinx.serialization`. These models live in `com.s1mar.openai.models` and cover the full API surface. For most use cases, the built-in DSL types (`ChatRequest`, `ChatResponse`) are simpler and sufficient.

## License

[MIT](LICENSE)
