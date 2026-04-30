package com.mingeek.forge.runtime.registry

import android.os.Build
import com.mingeek.forge.data.storage.BenchmarkRecord
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.LoadConfig
import com.mingeek.forge.runtime.core.ModelHandle
import com.mingeek.forge.runtime.core.Prompt
import com.mingeek.forge.runtime.core.Token

class BenchmarkRunner(
    private val registry: RuntimeRegistry,
) {

    suspend fun run(model: InstalledModel, maxTokens: Int = 64): BenchmarkRecord? {
        val runtimeId = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(model.format, runtimeId) ?: return null

        val handle = ModelHandle(
            modelId = model.id,
            modelPath = model.filePath,
            format = model.format,
        )
        val loaded = runtime.load(handle, LoadConfig(contextLength = 1024))

        val startNanos = System.nanoTime()
        var firstTokenNanos = 0L
        var lastTokenNanos = 0L
        var emittedTokens = 0

        try {
            runtime.generate(
                loaded.sessionId,
                Prompt(text = BENCHMARK_PROMPT, maxTokens = maxTokens, temperature = 0.7f, topP = 0.95f),
            ).collect { token ->
                when (token) {
                    is Token.Text -> {
                        if (firstTokenNanos == 0L) firstTokenNanos = System.nanoTime()
                        emittedTokens++
                        lastTokenNanos = System.nanoTime()
                    }
                    else -> {}
                }
            }
        } finally {
            runtime.unload(loaded.sessionId)
        }

        if (emittedTokens < 2 || firstTokenNanos == 0L) return null

        val decodeSec = (lastTokenNanos - firstTokenNanos) / 1_000_000_000.0
        val tps = if (decodeSec > 0) (emittedTokens - 1) / decodeSec else 0.0
        val ttftMs = (firstTokenNanos - startNanos) / 1_000_000

        return BenchmarkRecord(
            modelId = model.id,
            runtimeId = runtime.id.name,
            tokensPerSecond = tps.toFloat(),
            firstTokenLatencyMs = ttftMs,
            promptTokensPerSecond = null,
            deviceModel = Build.MODEL ?: "unknown",
            measuredAtEpochSec = System.currentTimeMillis() / 1000,
        )
    }

    private companion object {
        const val BENCHMARK_PROMPT =
            "Below is a paragraph used to measure inference speed:\n\n" +
                "The quick brown fox jumps over the lazy dog. Foxes are known for their cunning. " +
                "They live in dens and hunt at night. The lazy dog sleeps under the sun all day. " +
                "These two characters appear together in classic typing exercises.\n\n" +
                "Continue the paragraph briefly:"
    }
}
