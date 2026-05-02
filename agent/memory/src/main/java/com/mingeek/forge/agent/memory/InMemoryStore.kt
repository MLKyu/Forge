package com.mingeek.forge.agent.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process [MemoryStore]. No persistence — every entry vanishes when the
 * process dies. Use for tests and for short-lived agents that don't need
 * memory beyond their workflow.
 *
 * Ranking heuristic for [query]:
 * 1. **Keyword overlap**: count of distinct query terms (>= 2 chars,
 *    lowercased) that appear in entry content.
 * 2. **Recency tiebreak**: among entries with equal overlap, newer entries
 *    rank higher.
 *
 * The scorer is deliberately simple. It exists so we can validate the
 * surrounding plumbing (puts, tag filtering, retrieval into prompts)
 * without committing to a particular embedding stack. A vector-backed
 * store can replace this class without touching call sites.
 */
class InMemoryStore : MemoryStore {

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, MemoryEntry>()

    override suspend fun put(entry: MemoryEntry) = mutex.withLock {
        entries[entry.id] = entry
    }

    override suspend fun get(id: String): MemoryEntry? = mutex.withLock {
        entries[id]
    }

    override suspend fun query(text: String, limit: Int, tags: Set<String>): List<MemoryEntry> =
        mutex.withLock {
            val candidates = if (tags.isEmpty()) entries.values
            else entries.values.filter { it.tags.containsAll(tags) }

            if (text.isBlank()) {
                return@withLock candidates
                    .sortedByDescending { it.createdAtEpochSec }
                    .take(limit)
            }

            val queryTerms = text.lowercase()
                .split(Regex("\\W+"))
                .filter { it.length >= 2 }
                .toSet()
            if (queryTerms.isEmpty()) {
                return@withLock candidates
                    .sortedByDescending { it.createdAtEpochSec }
                    .take(limit)
            }

            candidates
                .map { it to score(it, queryTerms) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.createdAtEpochSec })
                .take(limit)
                .map { it.first }
        }

    override suspend fun all(): List<MemoryEntry> = mutex.withLock {
        entries.values.sortedBy { it.createdAtEpochSec }
    }

    override suspend fun remove(id: String) = mutex.withLock {
        entries.remove(id)
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        entries.clear()
    }

    private fun score(entry: MemoryEntry, queryTerms: Set<String>): Int {
        val content = entry.content.lowercase()
        return queryTerms.count { term -> content.contains(term) }
    }
}
