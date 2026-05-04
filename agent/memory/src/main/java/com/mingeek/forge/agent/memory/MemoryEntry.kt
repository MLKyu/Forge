package com.mingeek.forge.agent.memory

import com.squareup.moshi.JsonClass

/**
 * One unit of remembered text. Implementations of [MemoryStore] decide how
 * entries are persisted, indexed, and ranked — this type is intentionally
 * minimal so we can swap a keyword-based store for an embedding-based one
 * without touching call sites.
 */
@JsonClass(generateAdapter = true)
data class MemoryEntry(
    /**
     * Caller-assigned id. Stable across put/get/remove. The store does not
     * mint ids; the caller picks something meaningful (e.g. `chat:{conv}:{turn}`)
     * so updates can rewrite the same entry.
     */
    val id: String,

    /** The actual remembered text. Used for retrieval and for prompt injection. */
    val content: String,

    /**
     * Wall-clock seconds since epoch when this entry was created. Used by
     * stores that want recency-weighted ranking and by callers that display
     * a timeline.
     */
    val createdAtEpochSec: Long,

    /**
     * Free-form labels. Conventionally namespaced with `prefix:value`, e.g.
     * `chat:abc123` to scope memories to a conversation, or `agent:debate`
     * to scope to a workflow type. Stores filter by tag exact-match.
     */
    val tags: Set<String> = emptySet(),

    /**
     * Caller-defined extras the retrieval layer doesn't interpret.
     * Useful for re-rendering the entry in context (e.g. role, model id).
     */
    val metadata: Map<String, String> = emptyMap(),
)
