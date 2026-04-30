package com.mingeek.forge.domain

data class License(
    val spdxId: String,
    val displayName: String,
    val commercialUseAllowed: Boolean,
    val sourceUrl: String? = null,
)
