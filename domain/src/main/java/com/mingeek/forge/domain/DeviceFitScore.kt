package com.mingeek.forge.domain

data class DeviceFitScore(
    val tier: Tier,
    val estimatedTokensPerSecond: Float?,
    val estimatedMemoryBytes: Long,
    val reasons: List<String>,
) {
    enum class Tier { GREEN, YELLOW, RED, UNSUPPORTED }
}
