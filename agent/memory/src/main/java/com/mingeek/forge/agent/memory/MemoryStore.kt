package com.mingeek.forge.agent.memory

/**
 * Pluggable memory backend used by Chat (conversation history beyond the
 * context window), Agents (run history), and AI Curator (notes about
 * evaluated models).
 *
 * Implementations:
 * - [InMemoryStore] — process-local, no persistence, good for one-shot agents
 *   and tests
 * - File / DataStore-backed: see :data:agents (planned) for chat + run history
 *
 * The interface is keyword-friendly but does not preclude embedding-based
 * stores: a future implementation can index content with a local LLM and
 * still satisfy [query] by returning the top-k cosine matches.
 */
interface MemoryStore {

    /** Insert or replace an entry with the same id. */
    suspend fun put(entry: MemoryEntry)

    suspend fun get(id: String): MemoryEntry?

    /**
     * Retrieve the most relevant entries for [text]. Implementations decide
     * the scoring strategy — keyword, embeddings, recency, or a blend.
     * Returns at most [limit] entries, most-relevant first.
     *
     * When [text] is blank, returns the most recent entries (no relevance
     * signal — useful for "show me last N items").
     *
     * If [tags] is non-empty, only entries whose tag set is a superset of
     * [tags] are considered (intersection semantics).
     */
    suspend fun query(text: String, limit: Int = 8, tags: Set<String> = emptySet()): List<MemoryEntry>

    /** Every entry, oldest first. Use for export / debug / migration. */
    suspend fun all(): List<MemoryEntry>

    suspend fun remove(id: String)

    suspend fun clear()
}
