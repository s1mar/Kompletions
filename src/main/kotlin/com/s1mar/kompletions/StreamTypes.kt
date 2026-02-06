package com.s1mar.kompletions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallChunkDelta>? = null
)

@Serializable
data class ToolCallChunkDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallChunkFunction? = null
)

@Serializable
data class ToolCallChunkFunction(
    val name: String? = null,
    val arguments: String? = null
)