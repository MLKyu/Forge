package com.mingeek.forge.data.storage

import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.Quant
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class InstalledModel(
    val id: String,
    val displayName: String,
    val sourceId: String,
    val sourceRepoId: String?,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val quantization: Quant,
    val format: ModelFormat,
    val contextLength: Int,
    val recommendedRuntime: String,
    val installedAtEpochSec: Long,
    val licenseSpdxId: String = "unknown",
    val commercialUseAllowed: Boolean = false,
    /**
     * Epoch seconds of last successful load by Chat / Agents / Compare /
     * Benchmark. Null on records persisted before this field existed — callers
     * should fall back to [installedAtEpochSec] for ordering. Used by the LRU
     * auto-cleanup pass.
     */
    val lastUsedEpochSec: Long? = null,
    /**
     * Free-form model description captured at install time (typically the
     * catalog source's README summary). Empty for imported-from-disk records
     * and for downloads that pre-date this field. Surfaced in the library
     * detail sheet.
     */
    val description: String = "",
    /**
     * Languages the model claims to handle, captured at install time from
     * the catalog source. Lowercase ISO 639-1 codes plus the special
     * `multi` token; see `com.mingeek.forge.domain.LanguageHints`. Empty
     * means unknown.
     */
    val languages: Set<String> = emptySet(),
)

fun InstalledModel.effectiveLastUsedEpochSec(): Long = lastUsedEpochSec ?: installedAtEpochSec
