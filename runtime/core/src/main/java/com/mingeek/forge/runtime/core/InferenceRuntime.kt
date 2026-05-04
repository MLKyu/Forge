package com.mingeek.forge.runtime.core

import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.Flow

@JvmInline
value class SessionId(val value: String)

data class ModelHandle(
    val modelId: String,
    val modelPath: String,
    val format: ModelFormat,
)

data class LoadConfig(
    val contextLength: Int = 4096,
    val gpuLayers: Int = 0,
    val threads: Int = 0,
    val useNpu: Boolean = false,
    val seed: Long? = null,
    val extras: Map<String, String> = emptyMap(),
)

data class LoadedModel(
    val sessionId: SessionId,
    val handle: ModelHandle,
    val runtimeId: RuntimeId,
    val capabilities: RuntimeCapabilities,
    val chatTemplate: String? = null,
)

data class RuntimeCapabilities(
    val supportsStreaming: Boolean,
    val supportsToolCalls: Boolean,
    val supportsVision: Boolean,
    val supportsKvCacheReuse: Boolean,
    val supportedAccelerators: Set<Accelerator>,
) {
    enum class Accelerator { CPU, GPU, NPU_QNN, NPU_EXYNOS, NPU_MEDIATEK }
}

data class Prompt(
    val text: String,
    val systemPrompt: String? = null,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val stopSequences: List<String> = emptyList(),
)

sealed interface Token {
    data class Text(val piece: String) : Token
    data class ToolCall(val name: String, val argumentsJson: String) : Token
    data class Done(val finishReason: FinishReason, val usage: TokenUsage) : Token

    enum class FinishReason { STOP, MAX_TOKENS, STOP_SEQUENCE, ERROR, CANCELLED }

    @JsonClass(generateAdapter = true)
    data class TokenUsage(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
    )
}

interface InferenceRuntime {
    val id: RuntimeId
    val supportedFormats: Set<ModelFormat>
    val capabilities: RuntimeCapabilities

    suspend fun load(model: ModelHandle, config: LoadConfig): LoadedModel
    fun generate(session: SessionId, prompt: Prompt): Flow<Token>
    suspend fun unload(session: SessionId)
}
