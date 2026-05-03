package com.mingeek.forge.runtime.mlc

import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.InferenceRuntime
import com.mingeek.forge.runtime.core.LoadConfig
import com.mingeek.forge.runtime.core.LoadedModel
import com.mingeek.forge.runtime.core.ModelHandle
import com.mingeek.forge.runtime.core.Prompt
import com.mingeek.forge.runtime.core.RuntimeCapabilities
import com.mingeek.forge.runtime.core.SessionId
import com.mingeek.forge.runtime.core.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MLC LLM runtime stub. PLANNING §11 Phase 5.
 *
 * Surfaces the runtime id + format mapping so the registry can pick it
 * for MLC-compiled models, but load() throws until the MLC LLM Android
 * library and TVM compiled artifacts are wired in. Vulkan GPU
 * acceleration is the headline reason for adding this runtime — the
 * device fit scorer can already attribute YELLOW/GREEN tiers based on
 * Vulkan support once load() returns real numbers.
 */
class MlcLlmRuntime : InferenceRuntime {

    override val id: RuntimeId = RuntimeId.MLC

    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.MLC)

    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsStreaming = true,
        supportsToolCalls = false,
        supportsVision = false,
        supportsKvCacheReuse = true,
        supportedAccelerators = setOf(RuntimeCapabilities.Accelerator.GPU),
    )

    override suspend fun load(model: ModelHandle, config: LoadConfig): LoadedModel {
        throw NotImplementedError(
            "MLC LLM runtime not yet integrated. Add the mlc4j Android library and a TVM model loader to enable.",
        )
    }

    override fun generate(session: SessionId, prompt: Prompt): Flow<Token> = flow {
        throw NotImplementedError("MLC generate() requires load() to succeed first.")
    }

    override suspend fun unload(session: SessionId) {
        // No-op until load is wired.
    }
}
