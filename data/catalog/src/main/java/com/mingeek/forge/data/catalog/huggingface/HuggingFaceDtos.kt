package com.mingeek.forge.data.catalog.huggingface

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HfModelSummary(
    val id: String,
    @Json(name = "modelId") val modelId: String? = null,
    val downloads: Long? = null,
    val likes: Long? = null,
    @Json(name = "lastModified") val lastModified: String? = null,
    val tags: List<String> = emptyList(),
    val pipeline_tag: String? = null,
    val library_name: String? = null,
)

@JsonClass(generateAdapter = true)
data class HfModelDetail(
    val id: String,
    val sha: String? = null,
    val downloads: Long? = null,
    val likes: Long? = null,
    @Json(name = "lastModified") val lastModified: String? = null,
    val tags: List<String> = emptyList(),
    val pipeline_tag: String? = null,
    val library_name: String? = null,
    val siblings: List<HfFile> = emptyList(),
    val cardData: Map<String, Any?>? = null,
)

@JsonClass(generateAdapter = true)
data class HfFile(
    val rfilename: String,
    val size: Long? = null,
    val lfs: HfLfsInfo? = null,
)

@JsonClass(generateAdapter = true)
data class HfLfsInfo(
    val sha256: String? = null,
    val size: Long? = null,
    val pointerSize: Long? = null,
)
