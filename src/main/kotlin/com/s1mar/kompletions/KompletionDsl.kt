package com.s1mar.kompletions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val top_p: Double? = null,
    val frequency_penalty: Double? = null,
    val presence_penalty: Double? = null,
    val stop: List<String>? = null,
    val n: Int? = null,
    val stream: Boolean? = null,
    val user: String? = null,
    val response_format: ResponseFormat? = null,
    val tools: List<Tool>? = null,
    val tool_choice: String? = null
)

@Serializable
data class Message(
    val role: String,
    val content: String? = null,
    val name: String? = null
)

@Serializable
data class ResponseFormat(
    val type: String
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionDef
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null
)

@Serializable
data class ChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    val finish_reason: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

/**
 * Simple extension function for easy chat completions.
 */
suspend fun KompletionClient.sendMessage(
    model: String,
    message: String,
    systemPrompt: String? = null,
    temperature: Double? = null,
    maxTokens: Int? = null
): ChatResponse {
    val messages = buildList {
        systemPrompt?.let { add(Message("system", it)) }
        add(Message("user", message))
    }

    val request = ChatRequest(
        model = model,
        messages = messages,
        temperature = temperature,
        max_tokens = maxTokens
    )

    return chat(request)
}

/**
 * DSL builder for chat requests.
 *
 * ```
 * client.chatCompletion {
 *     model = "gpt-4"
 *     temperature = 0.7
 *
 *     system("You are a helpful assistant.")
 *     user("Explain Kotlin coroutines.")
 * }
 * ```
 */
class ChatRequestBuilder {
    var model: String = ""
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null
    var frequencyPenalty: Double? = null
    var presencePenalty: Double? = null
    var stop: List<String>? = null
    /** End-user identifier sent to the API. Not to be confused with [user] which adds a user message. */
    var endUser: String? = null
    var n: Int? = null
    var responseFormat: ResponseFormat? = null
    var tools: List<Tool>? = null
    var toolChoice: String? = null

    private val messageList = mutableListOf<Message>()

    /** Prepopulate the message list from an existing history (e.g., from a previous conversation). */
    fun messages(history: List<Message>) {
        messageList.addAll(history)
    }

    fun system(content: String) {
        messageList.add(Message("system", content))
    }

    fun user(content: String) {
        messageList.add(Message("user", content))
    }

    fun assistant(content: String) {
        messageList.add(Message("assistant", content))
    }

    fun message(role: String, content: String? = null, name: String? = null) {
        messageList.add(Message(role, content, name))
    }

    internal fun build(): ChatRequest {
        require(model.isNotEmpty()) { "Model must be specified" }
        require(messageList.isNotEmpty()) { "At least one message is required" }

        return ChatRequest(
            model = model,
            messages = messageList,
            temperature = temperature,
            max_tokens = maxTokens,
            top_p = topP,
            frequency_penalty = frequencyPenalty,
            presence_penalty = presencePenalty,
            stop = stop,
            user = endUser,
            n = n,
            response_format = responseFormat,
            tools = tools,
            tool_choice = toolChoice
        )
    }
}

/**
 * DSL function to build and send a chat request.
 */
suspend fun KompletionClient.chatCompletion(
    block: ChatRequestBuilder.() -> Unit
): ChatResponse {
    val request = ChatRequestBuilder().apply(block).build()
    return chat(request)
}

/**
 * Multi-turn conversation with automatic message history management.
 *
 * Thread safety: Concurrent calls to [send]/[sendFull] are serialized via a mutex.
 * [addMessage], [clearHistory], and [getHistory] are not synchronized with send operations.
 *
 * @param client The KompletionClient to use for API calls.
 * @param model The model name to use for completions.
 * @param systemPrompt Optional system prompt prepended to the history.
 * @param initialHistory Optional list of messages to seed the conversation with.
 *   If both [systemPrompt] and [initialHistory] are provided, the system prompt
 *   is prepended only if [initialHistory] doesn't already start with a system message.
 */
class Conversation(
    private val client: KompletionClient,
    private val model: String,
    systemPrompt: String? = null,
    initialHistory: List<Message>? = null
) {
    private val messages = mutableListOf<Message>()
    private val sendMutex = Mutex()

    init {
        if (initialHistory != null) {
            if (systemPrompt != null && (initialHistory.isEmpty() || initialHistory[0].role != "system")) {
                messages.add(Message("system", systemPrompt))
            }
            messages.addAll(initialHistory)
        } else {
            systemPrompt?.let { messages.add(Message("system", it)) }
        }
    }

    /**
     * Send a user message and return the assistant's text reply.
     * Returns an empty string if the assistant's content is null (e.g., tool calls).
     */
    suspend fun send(message: String): String {
        return sendFull(message).choices.first().message.content ?: ""
    }

    /**
     * Send a user message and return the full [ChatResponse].
     * Both the user message and the assistant reply are appended to the history.
     * On failure, the user message is rolled back from history.
     */
    suspend fun sendFull(message: String): ChatResponse = sendMutex.withLock {
        messages.add(Message("user", message))
        try {
            val request = ChatRequest(model = model, messages = messages.toList())
            val response: ChatResponse = client.chat(request)
            val assistantMsg = response.choices.firstOrNull()?.message
                ?: throw KompletionException("No choices returned in API response")
            messages.add(Message(assistantMsg.role, assistantMsg.content, assistantMsg.name))
            response
        } catch (e: Exception) {
            messages.removeAt(messages.lastIndex)
            throw e
        }
    }

    /** Inject a message into the history without making an API call. */
    fun addMessage(role: String, content: String? = null, name: String? = null) {
        messages.add(Message(role, content, name))
    }

    /** Remove all messages from the history. */
    fun clearHistory() {
        messages.clear()
    }

    /** Returns a snapshot of the current message history. */
    fun getHistory(): List<Message> = messages.toList()
}

/**
 * Start a new conversation.
 */
fun KompletionClient.conversation(
    model: String,
    systemPrompt: String? = null,
    initialHistory: List<Message>? = null
): Conversation {
    return Conversation(this, model, systemPrompt, initialHistory)
}
