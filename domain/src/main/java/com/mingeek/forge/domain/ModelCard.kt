package com.mingeek.forge.domain

data class ModelCard(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val sizeBytes: Long,
    val quantization: Quant,
    val format: ModelFormat,
    val contextLength: Int,
    val capabilities: Set<Capability>,
    val license: License,
    val source: Source,
    val recommendedRuntimes: List<RuntimeId>,
    val benchmarks: BenchmarkSnapshot? = null,
)
