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
)
