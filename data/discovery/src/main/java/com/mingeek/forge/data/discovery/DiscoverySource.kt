package com.mingeek.forge.data.discovery

import com.mingeek.forge.domain.DiscoveredModel

interface DiscoverySource {
    val sourceId: String
    val displayName: String

    suspend fun fetchSignals(): List<DiscoveredModel>
}
