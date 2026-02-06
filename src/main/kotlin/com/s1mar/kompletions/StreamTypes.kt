package com.s1mar.kompletions

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
    val finish_reason: String? = null
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null
)
