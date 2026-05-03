package com.mingeek.forge.runtime.executorch

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
 * ExecuTorch runtime stub.
 *
 * The class exists so the rest of the app can pin .pte models to a
 * specific runtime variant in the registry. Loads currently throw —
 * the actual integration (PyTorch ExecuTorch Android lib + delegate
 * selection per [variant]) ships in a follow-up. The variant tag is
 * surfaced in [id] so RuntimeRegistry can route GREEN devices to QNN
 * / Exynos / CPU as PLANNING §10 specifies.
 */
class ExecuTorchRuntime(
    private val variant: Variant,
) : InferenceRuntime {

    enum class Variant {
        /** Qualcomm Snapdragon NPU (QNN delegate). PLANNING §11 Phase 2. */
        QNN,

        /** Samsung Exynos NPU. PLANNING §11 Phase 4. */
        EXYNOS,

        /** Pure-CPU fallback. Always available. */
        CPU,
    }

    override val id: RuntimeId = when (variant) {
        Variant.QNN -> RuntimeId.EXECUTORCH_QNN
        Variant.EXYNOS -> RuntimeId.EXECUTORCH_EXYNOS
        Variant.CPU -> RuntimeId.EXECUTORCH_CPU
    }

    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.EXECUTORCH_PTE)

    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsStreaming = true,
        supportsToolCalls = false,
        supportsVision = false,
        supportsKvCacheReuse = true,
        supportedAccelerators = setOf(
            when (variant) {
                Variant.QNN -> RuntimeCapabilities.Accelerator.NPU_QNN
                Variant.EXYNOS -> RuntimeCapabilities.Accelerator.NPU_EXYNOS
                Variant.CPU -> RuntimeCapabilities.Accelerator.CPU
            },
        ),
    )

    override suspend fun load(model: ModelHandle, config: LoadConfig): LoadedModel {
        throw NotImplementedError(
            "ExecuTorch ($variant) runtime not yet integrated. " +
                "Register the matching native delegate to enable .pte models.",
        )
    }

    override fun generate(session: SessionId, prompt: Prompt): Flow<Token> = flow {
        throw NotImplementedError("ExecuTorch generate() requires load() to succeed first.")
    }

    override suspend fun unload(session: SessionId) {
        // No-op until load is wired.
    }
}
