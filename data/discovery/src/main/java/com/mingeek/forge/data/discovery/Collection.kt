package com.mingeek.forge.data.discovery

/**
 * One model recommendation inside a [Collection]. Carries enough metadata
 * for the UI to (a) pre-filter against device fit before the user clicks,
 * and (b) warn about gated repos that need a HuggingFace token + license
 * acceptance before download will work.
 *
 * - [approxSizeBytes] is a rough Q4_K_M figure baked in at curation time;
 *   it lets [com.mingeek.forge.data.discovery.DiscoveryRepository] consumers
 *   score the entry without first hitting the HF detail endpoint.
 * - [gated] flags repos whose detail/download will 401/403 without a
 *   configured HF token (Llama, Gemma, Mistral families). When false, the
 *   repo is expected to be publicly downloadable.
 */
data class CollectionEntry(
    val modelId: String,
    val approxSizeBytes: Long? = null,
    val gated: Boolean = false,
)

/**
 * A named, ordered grouping of model recommendations. Lives separate from
 * [DiscoverySource] because the access pattern is different — collections
 * are intent-shaped ("things that fit a use case") rather than
 * source-shaped ("trending on HF"). Same UI surface, different
 * narrative.
 */
data class Collection(
    val id: String,
    val title: String,
    val description: String,
    val entries: List<CollectionEntry>,
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
