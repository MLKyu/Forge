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
 * for MLC-compiled models, but load() throws because MLC LLM doesn't
 * publish a Maven artifact — the canonical distribution is `mlc4j` from
 * the mlc-ai/mlc-llm repository, built from source against TVM. Wiring
 * this runtime requires:
 *
 * 1. Cloning mlc-llm + TVM + emsdk and building mlc4j locally
 * 2. Running TVM model compilation per (model, target architecture)
 *    to produce the compiled binary the runtime expects
 * 3. Hosting the resulting AAR somewhere consumable
 *
 * That toolchain work is intentionally out of scope here — it's a
 * developer-environment setup task, not "drop in a Maven dep". When
 * mlc4j ships to Maven Central or JitPack the stub becomes a 50-line
 * implementation mirroring [ExecuTorchRuntime].
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
            "MLC LLM runtime requires mlc4j built from source — see class-level " +
                "documentation. Stubbed so RuntimeRegistry can advertise the runtime " +
                "without pulling in the build toolchain.",
        )
    }

    override fun generate(session: SessionId, prompt: Prompt): Flow<Token> = flow {
        throw NotImplementedError("MLC generate() requires load() to succeed first.")
    }

    override suspend fun unload(session: SessionId) {
        // No-op until load is wired.
    }
}
