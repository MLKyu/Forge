package com.mingeek.forge.data.storage

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BenchmarkRecord(
    val modelId: String,
    val runtimeId: String,
    val tokensPerSecond: Float,
    val firstTokenLatencyMs: Long,
    val promptTokensPerSecond: Float?,
    val deviceModel: String,
    val measuredAtEpochSec: Long,
)
