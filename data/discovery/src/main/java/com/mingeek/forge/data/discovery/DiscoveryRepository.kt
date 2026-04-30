package com.mingeek.forge.data.discovery

import com.mingeek.forge.domain.DiscoveredModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class DiscoveryRepository(
    private val sources: List<DiscoverySource>,
) {

    data class Feed(
        val sourceId: String,
        val displayName: String,
        val items: List<DiscoveredModel>,
        val error: String? = null,
    )

    suspend fun fetchAll(): List<Feed> = coroutineScope {
        sources.map { source ->
            async {
                try {
                    Feed(
                        sourceId = source.sourceId,
                        displayName = source.displayName,
                        items = source.fetchSignals(),
                    )
                } catch (t: Throwable) {
                    Feed(
                        sourceId = source.sourceId,
                        displayName = source.displayName,
                        items = emptyList(),
                        error = t.message ?: "fetch failed",
                    )
                }
            }
        }.map { it.await() }
    }
}
