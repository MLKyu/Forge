package com.mingeek.forge.domain

import java.time.Instant

data class DiscoverySignals(
    val downloadCount: Long? = null,
    val likes: Long? = null,
    val publishedAt: Instant? = null,
    val updatedAt: Instant? = null,
    val trendingRank: Int? = null,
    val mentions: Int? = null,
)

data class Curation(
    val curatorId: String,
    val summary: String,
    val score: Float?,
    val tags: Set<String> = emptySet(),
)

data class DiscoveredModel(
    val card: ModelCard,
    val discoveredAt: Instant,
    val sourceId: String,
    val signals: DiscoverySignals,
    val curation: Curation? = null,
    val deviceFit: DeviceFitScore,
    val userRelevance: Float,
    val seen: Boolean = false,
    val dismissed: Boolean = false,
)
