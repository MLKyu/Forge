package com.mingeek.forge.domain

import java.time.Instant

data class BenchmarkSnapshot(
    val runtimeId: RuntimeId,
    val tokensPerSecond: Float,
    val firstTokenLatencyMs: Long,
    val peakMemoryBytes: Long,
    val deviceModel: String,
    val measuredAt: Instant,
)
