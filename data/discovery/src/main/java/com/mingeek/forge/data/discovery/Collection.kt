package com.mingeek.forge.data.discovery

/**
 * A named, ordered grouping of model ids. Lives separate from
 * [DiscoverySource] because the access pattern is different — collections
 * are intent-shaped ("things that fit a use case") rather than
 * source-shaped ("trending on HF"). Same UI surface, different
 * narrative.
 */
data class Collection(
    val id: String,
    val title: String,
    val description: String,
    val modelIds: List<String>,
    val tags: Set<String> = emptySet(),
)

/**
 * Pluggable source of collections. Today only the static seed source
 * exists; future sources can pull from a remote feed (community
 * curation), generate from local usage analytics, or merge multiple
 * sources via [CollectionRepository].
 */
interface CollectionSource {
    val sourceId: String
    suspend fun fetch(): List<Collection>
}

class CollectionRepository(
    private val sources: List<CollectionSource>,
) {
    suspend fun fetchAll(): List<Collection> = sources.flatMap { src ->
        runCatching { src.fetch() }.getOrDefault(emptyList())
    }
}
