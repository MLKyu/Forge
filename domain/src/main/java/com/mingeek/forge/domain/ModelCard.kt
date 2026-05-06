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
    /**
     * Languages the model claims to handle, as lowercase ISO 639-1 codes
     * (`"en"`, `"ko"`, ...). The special token [LanguageHints.MULTILINGUAL]
     * means "explicitly trained for many languages" — treat it as covering
     * any locale. Empty means unknown; callers should not penalize.
     */
    val languages: Set<String> = emptySet(),
)
