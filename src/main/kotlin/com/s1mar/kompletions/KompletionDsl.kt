package com.s1mar.kompletions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    val stop: List<String>? = null,
    val n: Int? = null,
    val stream: Boolean? = null,
    val user: String? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val seed: Int? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null
)

@Serializable
data class Message(
    val role: String,
    val content: Content? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class ResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonSchema? = null
)

@Serializable
data class Tool(
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @EncodeDefault val type: String = "function",
    val function: FunctionDef
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null
)

@Serializable
data class ToolCall(
    val id: String,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @EncodeDefault val type: String = "function",
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class JsonSchema(
    val name: String,
    val schema: JsonElement,
    val strict: Boolean? = null,
    val description: String? = null
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean
)

/**
 * Represents message content that can be either plain text or multi-part (text + images).
 * Serializes as a JSON string for [Text] or a JSON array for [Parts].
 */
@Serializable(with = ContentSerializer::class)
sealed interface Content {
    /** Plain text content — serializes as a JSON string. */
    data class Text(val text: String) : Content
    /** Multi-part content (text + images) — serializes as a JSON array. */
    data class Parts(val parts: List<ContentPart>) : Content
}

/** Extract the text from any [Content] variant. */
val Content.text: String?
    get() = when (this) {
        is Content.Text -> text
        is Content.Parts -> parts.filterIsInstance<ContentPart.TextPart>()
            .joinToString("") { it.text }.ifEmpty { null }
    }

sealed interface ContentPart {
    @Serializable
    data class TextPart(val text: String) : ContentPart

    @Serializable
    data class ImagePart(
        @SerialName("image_url") val imageUrl: ImageUrl
    ) : ContentPart
}

@Serializable
data class ImageUrl(
    val url: String,
    val detail: String? = null
)

object ContentSerializer : KSerializer<Content> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Content")

    override fun serialize(encoder: Encoder, value: Content) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is Content.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is Content.Parts -> {
                val array = buildJsonArray {
                    for (part in value.parts) {
                        when (part) {
                            is ContentPart.TextPart -> addJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            }
                            is ContentPart.ImagePart -> addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", part.imageUrl.url)
                                    part.imageUrl.detail?.let { put("detail", it) }
                                }
                            }
                        }
                    }
                }
                jsonEncoder.encodeJsonElement(array)
            }
        }
    }

    override fun deserialize(decoder: Decoder): Content {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> Content.Text(element.content)
            is JsonArray -> {
                val parts = element.map { partElement ->
                    val obj = partElement.jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "text" -> ContentPart.TextPart(
                            text = obj["text"]!!.jsonPrimitive.content
                        )
                        "image_url" -> ContentPart.ImagePart(
                            imageUrl = ImageUrl(
                                url = obj["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
                                detail = obj["image_url"]!!.jsonObject["detail"]?.jsonPrimitive?.contentOrNull
                            )
                        )
                        else -> throw IllegalArgumentException(
                            "Unknown content part type: ${obj["type"]}"
                        )
                    }
                }
                Content.Parts(parts)
            }
            else -> throw IllegalArgumentException(
                "Unexpected JSON element for content: ${element::class.simpleName}"
            )
        }
    }
}

/** Convenience: get the text content of a message as a plain String. */
val Message.textContent: String?
    get() = content?.text

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
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
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
        systemPrompt?.let { add(Message("system", Content.Text(it))) }
        add(Message("user", Content.Text(message)))
    }

    val request = ChatRequest(
        model = model,
        messages = messages,
        temperature = temperature,
        maxTokens = maxTokens
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
    var seed: Int? = null
    var streamOptions: StreamOptions? = null
    var parallelToolCalls: Boolean? = null

    private val messageList = mutableListOf<Message>()
    private val toolList = mutableListOf<Tool>()

    /** Prepopulate the message list from an existing history (e.g., from a previous conversation). */
    fun messages(history: List<Message>) {
        messageList.addAll(history)
    }

    fun system(content: String) {
        messageList.add(Message("system", Content.Text(content)))
    }

    fun user(content: String) {
        messageList.add(Message("user", Content.Text(content)))
    }

    fun assistant(content: String) {
        messageList.add(Message("assistant", Content.Text(content)))
    }

    fun message(role: String, content: String? = null, name: String? = null) {
        messageList.add(Message(role, content?.let { Content.Text(it) }, name))
    }

    /** Add a user message with both text and image content. */
    fun userWithImages(text: String, imageUrls: List<String>, detail: String? = null) {
        val parts = buildList<ContentPart> {
            add(ContentPart.TextPart(text))
            imageUrls.forEach { url ->
                add(ContentPart.ImagePart(ImageUrl(url, detail)))
            }
        }
        messageList.add(Message("user", Content.Parts(parts)))
    }

    /** Define a tool (function) the model can call. */
    fun tool(name: String, description: String? = null, parameters: JsonElement? = null) {
        toolList.add(Tool(function = FunctionDef(name = name, description = description, parameters = parameters)))
    }

    /** Add a tool-result message to provide the output of a tool call back to the model. */
    fun toolResult(toolCallId: String, content: String) {
        messageList.add(Message(role = "tool", content = Content.Text(content), toolCallId = toolCallId))
    }

    /** Add an assistant message containing tool calls (for replaying a tool-calling turn). */
    fun assistantToolCalls(toolCalls: List<ToolCall>) {
        messageList.add(Message(role = "assistant", toolCalls = toolCalls))
    }

    /** Enable JSON mode (response_format: {"type": "json_object"}). */
    fun jsonMode() {
        responseFormat = ResponseFormat(type = "json_object")
    }

    /** Enable structured output with a JSON Schema. */
    fun structuredOutput(
        name: String,
        schema: JsonElement,
        strict: Boolean? = true,
        description: String? = null
    ) {
        responseFormat = ResponseFormat(
            type = "json_schema",
            jsonSchema = JsonSchema(name = name, schema = schema, strict = strict, description = description)
        )
    }

    internal fun build(): ChatRequest {
        require(model.isNotEmpty()) { "Model must be specified" }
        require(messageList.isNotEmpty()) { "At least one message is required" }

        val allTools = when {
            toolList.isNotEmpty() && tools != null -> tools!! + toolList
            toolList.isNotEmpty() -> toolList.toList()
            else -> tools
        }

        return ChatRequest(
            model = model,
            messages = messageList,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            stop = stop,
            user = endUser,
            n = n,
            responseFormat = responseFormat,
            tools = allTools,
            toolChoice = toolChoice,
            seed = seed,
            streamOptions = streamOptions,
            parallelToolCalls = parallelToolCalls
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
 * Thread safety: All mutating operations are serialized via a mutex.
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
                messages.add(Message("system", Content.Text(systemPrompt)))
            }
            messages.addAll(initialHistory)
        } else {
            systemPrompt?.let { messages.add(Message("system", Content.Text(it))) }
        }
    }

    /**
     * Send a user message and return the assistant's text reply.
     * Returns an empty string if the assistant's content is null (e.g., tool calls).
     */
    suspend fun send(message: String): String {
        return sendFull(message).choices.first().message.textContent ?: ""
    }

    /**
     * Send a user message and return the full [ChatResponse].
     * Both the user message and the assistant reply are appended to the history.
     * On failure, the user message is rolled back from history.
     */
    suspend fun sendFull(message: String): ChatResponse = sendMutex.withLock {
        messages.add(Message("user", Content.Text(message)))
        try {
            val request = ChatRequest(model = model, messages = messages.toList())
            val response: ChatResponse = client.chat(request)
            val assistantMsg = response.choices.firstOrNull()?.message
                ?: throw KompletionException("No choices returned in API response")
            messages.add(assistantMsg)
            response
        } catch (e: Exception) {
            messages.removeAt(messages.lastIndex)
            throw e
        }
    }

    /**
     * Add a tool result and send the conversation to the model for a follow-up response.
     * Returns the assistant's text reply (or empty string for another tool call).
     */
    suspend fun addToolResult(toolCallId: String, content: String): String {
        return addToolResultFull(toolCallId, content).choices.first().message.textContent ?: ""
    }

    /**
     * Add a tool result and get the full API response.
     */
    suspend fun addToolResultFull(toolCallId: String, content: String): ChatResponse = sendMutex.withLock {
        messages.add(Message(role = "tool", content = Content.Text(content), toolCallId = toolCallId))
        try {
            val request = ChatRequest(model = model, messages = messages.toList())
            val response: ChatResponse = client.chat(request)
            val assistantMsg = response.choices.firstOrNull()?.message
                ?: throw KompletionException("No choices returned in API response")
            messages.add(assistantMsg)
            response
        } catch (e: Exception) {
            messages.removeAt(messages.lastIndex)
            throw e
        }
    }

    /** Inject a message into the history without making an API call. */
    suspend fun addMessage(role: String, content: String? = null, name: String? = null) = sendMutex.withLock {
        messages.add(Message(role, content?.let { Content.Text(it) }, name))
    }

    /** Inject a pre-built message into the history without making an API call. */
    suspend fun addMessage(message: Message) = sendMutex.withLock {
        messages.add(message)
    }

    /** Remove all messages from the history. */
    suspend fun clearHistory() = sendMutex.withLock {
        messages.clear()
    }

    /** Returns a snapshot of the current message history. */
    suspend fun getHistory(): List<Message> = sendMutex.withLock {
        messages.toList()
    }
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