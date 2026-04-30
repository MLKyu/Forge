package com.mingeek.forge.core.hardware

import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId

class DeviceFitScorer(
    private val profile: DeviceProfile,
) {

    fun score(
        sizeBytes: Long,
        contextLength: Int,
        format: ModelFormat,
        runtime: RuntimeId,
        measuredTokensPerSecond: Float? = null,
    ): DeviceFitScore {
        val reasons = mutableListOf<String>()

        if (!isFormatSupportedByRuntime(format, runtime)) {
            return DeviceFitScore(
                tier = DeviceFitScore.Tier.UNSUPPORTED,
                estimatedTokensPerSecond = null,
                estimatedMemoryBytes = 0L,
                reasons = listOf("Runtime ${runtime.name} does not support $format"),
            )
        }

        if (sizeBytes <= 0) {
            return DeviceFitScore(
                tier = DeviceFitScore.Tier.UNSUPPORTED,
                estimatedTokensPerSecond = null,
                estimatedMemoryBytes = 0L,
                reasons = listOf("Model size unknown — open variant details to refine"),
            )
        }

        val kvFactor = 1.1 + contextLength.coerceAtLeast(512).toDouble() / 10240.0
        val estimatedMem = (sizeBytes * kvFactor).toLong()

        var tier = DeviceFitScore.Tier.GREEN

        if (sizeBytes > profile.freeStorageBytes) {
            tier = DeviceFitScore.Tier.RED
            reasons += "Not enough free storage (${formatBytes(sizeBytes)} > ${formatBytes(profile.freeStorageBytes)} free)"
        } else if (sizeBytes > profile.freeStorageBytes * 0.8) {
            tier = worse(tier, DeviceFitScore.Tier.YELLOW)
            reasons += "Storage tight after install"
        }

        when {
            estimatedMem > profile.availableRamBytes -> {
                tier = worse(tier, DeviceFitScore.Tier.RED)
                reasons += "Estimated RAM usage ${formatBytes(estimatedMem)} exceeds available ${formatBytes(profile.availableRamBytes)}"
            }
            estimatedMem > profile.availableRamBytes * 0.7 -> {
                tier = worse(tier, DeviceFitScore.Tier.YELLOW)
                reasons += "Estimated RAM usage close to available (${formatBytes(estimatedMem)} vs ${formatBytes(profile.availableRamBytes)})"
            }
        }

        if (tier == DeviceFitScore.Tier.GREEN) {
            reasons += "Fits comfortably (~${formatBytes(estimatedMem)} estimated)"
        }

        val estTokPerSec = measuredTokensPerSecond ?: estimateTokensPerSecond(sizeBytes, runtime)

        return DeviceFitScore(
            tier = tier,
            estimatedTokensPerSecond = estTokPerSec,
            estimatedMemoryBytes = estimatedMem,
            reasons = reasons,
        )
    }

    private fun isFormatSupportedByRuntime(format: ModelFormat, runtime: RuntimeId): Boolean = when (runtime) {
        RuntimeId.LLAMA_CPP -> format == ModelFormat.GGUF
        RuntimeId.MEDIAPIPE -> format == ModelFormat.MEDIAPIPE_TASK
        RuntimeId.EXECUTORCH_QNN,
        RuntimeId.EXECUTORCH_EXYNOS,
        RuntimeId.EXECUTORCH_CPU -> format == ModelFormat.EXECUTORCH_PTE
        RuntimeId.MLC -> format == ModelFormat.MLC
    }

    private fun estimateTokensPerSecond(sizeBytes: Long, runtime: RuntimeId): Float? {
        // Crude heuristic until we have real benchmarks (Phase 2.5).
        // Anchored on observed numbers: SD8 Elite + 3B Q4_K_M ≈ 10 tok/s on llama.cpp CPU.
        if (sizeBytes <= 0) return null
        val gb = sizeBytes / 1_073_741_824.0
        val baseline = when (runtime) {
            RuntimeId.LLAMA_CPP -> 25.0 / (gb + 0.4)
            RuntimeId.MLC -> 35.0 / (gb + 0.4)
            RuntimeId.MEDIAPIPE -> 30.0 / (gb + 0.4)
            RuntimeId.EXECUTORCH_QNN,
            RuntimeId.EXECUTORCH_EXYNOS -> 60.0 / (gb + 0.4)
            RuntimeId.EXECUTORCH_CPU -> 22.0 / (gb + 0.4)
        }
        val coreScale = (profile.cpuCoreCount.coerceIn(4, 16)) / 8.0
        return (baseline * coreScale).toFloat()
    }

    private fun worse(a: DeviceFitScore.Tier, b: DeviceFitScore.Tier): DeviceFitScore.Tier {
        val order = listOf(
            DeviceFitScore.Tier.GREEN,
            DeviceFitScore.Tier.YELLOW,
            DeviceFitScore.Tier.RED,
            DeviceFitScore.Tier.UNSUPPORTED,
        )
        return if (order.indexOf(b) > order.indexOf(a)) b else a
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(v, units[i])
    }
}
