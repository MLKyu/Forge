package com.mingeek.forge.data.agents

import com.mingeek.forge.agent.memory.MemoryEntry
import com.mingeek.forge.agent.memory.MemoryStore
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-disk [MemoryStore]. Persists entries as a single JSON array file using
 * Moshi codegen adapters (no reflection, so no kotlin-reflect on the runtime
 * classpath). Suitable for hundreds-to-low-thousands of entries — we rewrite
 * the whole file on every put so it's not a fit for high-write workloads.
 * Workflow runs / completed chats are well within budget.
 *
 * Scoring matches `InMemoryStore`: keyword overlap with recency tiebreak.
 * Storage and ranking are independent — switching to a vector store later
 * means dropping in a different [MemoryStore] implementation, not editing
 * call sites.
 */
class FileMemoryStore(private val file: File) : MemoryStore {

    private val mutex = Mutex()
    private val moshi: Moshi = Moshi.Builder().build()
    private val listAdapter: JsonAdapter<List<MemoryEntry>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, MemoryEntry::class.java),
    )

    private var cache: MutableMap<String, MemoryEntry>? = null

    init {
        file.parentFile?.mkdirs()
    }

    override suspend fun put(entry: MemoryEntry) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = ensureLoaded()
            map[entry.id] = entry
            persist(map)
        }
    }

    override suspend fun get(id: String): MemoryEntry? = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded()[id]
        }
    }

    override suspend fun query(text: String, limit: Int, tags: Set<String>): List<MemoryEntry> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val all = ensureLoaded().values
                val filtered = if (tags.isEmpty()) all else all.filter { it.tags.containsAll(tags) }

                if (text.isBlank()) {
                    return@withContext filtered
                        .sortedByDescending { it.createdAtEpochSec }
                        .take(limit)
                }

                val terms = text.lowercase()
                    .split(Regex("\\W+"))
                    .filter { it.length >= 2 }
                    .toSet()
                if (terms.isEmpty()) {
                    return@withContext filtered
                        .sortedByDescending { it.createdAtEpochSec }
                        .take(limit)
                }

                filtered
                    .map { it to score(it, terms) }
                    .filter { it.second > 0 }
                    .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                        .thenByDescending { it.first.createdAtEpochSec })
                    .take(limit)
                    .map { it.first }
            }
        }

    override suspend fun all(): List<MemoryEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded().values.sortedBy { it.createdAtEpochSec }
        }
    }

    override suspend fun remove(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = ensureLoaded()
            if (map.remove(id) != null) persist(map)
            Unit
        }
    }

    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            cache = mutableMapOf()
            if (file.exists()) file.delete()
        }
    }

    private fun ensureLoaded(): MutableMap<String, MemoryEntry> {
        cache?.let { return it }
        val loaded = if (file.exists()) {
            try {
                val text = file.readText()
                if (text.isBlank()) emptyList() else listAdapter.fromJson(text).orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        } else emptyList()
        val map = loaded.associateBy { it.id }.toMutableMap()
        cache = map
        return map
    }

    private fun persist(map: Map<String, MemoryEntry>) {
        file.writeText(listAdapter.toJson(map.values.sortedBy { it.createdAtEpochSec }))
    }

    private fun score(entry: MemoryEntry, terms: Set<String>): Int {
        val content = entry.content.lowercase()
        return terms.count { content.contains(it) }
    }
}
