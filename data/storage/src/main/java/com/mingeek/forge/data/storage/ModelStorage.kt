package com.mingeek.forge.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ModelStorage(private val context: Context) {

    val rootDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val indexFile: File = File(rootDir, "index.json")
    private val mutex = Mutex()

    private val moshi: Moshi = Moshi.Builder().build()

    private val listAdapter: JsonAdapter<List<InstalledModel>> =
        moshi.adapter(Types.newParameterizedType(List::class.java, InstalledModel::class.java))

    private val _installed = MutableStateFlow(loadIndex())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    fun fileFor(modelId: String, fileName: String): File {
        val safeId = modelId.replace('/', '_').replace(':', '_')
        val dir = File(rootDir, safeId).apply { mkdirs() }
        return File(dir, fileName)
    }

    suspend fun register(model: InstalledModel) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = (_installed.value.filterNot { it.id == model.id }) + model
            _installed.value = updated
            persist(updated)
        }
    }

    suspend fun unregister(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = _installed.value.filterNot { it.id == id }
            _installed.value = updated
            persist(updated)
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = _installed.value.firstOrNull { it.id == id } ?: return@withContext
            val file = File(target.filePath)
            if (file.exists()) file.delete()
            val parent = file.parentFile
            if (parent != null && parent.exists() && parent.list().isNullOrEmpty()) parent.delete()
            val updated = _installed.value.filterNot { it.id == id }
            _installed.value = updated
            persist(updated)
        }
    }

    /** Stamp [id] as just-loaded so the LRU pass keeps it. No-op for unknown ids. */
    suspend fun markUsed(id: String, epochSec: Long = System.currentTimeMillis() / 1000) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _installed.value
            val target = current.firstOrNull { it.id == id } ?: return@withContext
            // Skip the disk write if the timestamp would advance by less than
            // a minute — typical chat sessions hit markUsed many times per
            // turn and we don't need second-level precision here.
            val previous = target.lastUsedEpochSec ?: 0
            if (epochSec - previous < 60) return@withContext
            val updated = current.map { if (it.id == id) it.copy(lastUsedEpochSec = epochSec) else it }
            _installed.value = updated
            persist(updated)
        }
    }

    /**
     * Evict non-pinned models, oldest-used first, until total size on disk is
     * under [budgetBytes]. Returns the ids that were deleted. No-op when total
     * is already under budget or every remaining model is pinned.
     */
    suspend fun cleanupIfOverBudget(
        budgetBytes: Long,
        pinnedIds: Set<String>,
    ): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _installed.value
            var totalSize = current.sumOf { it.sizeBytes }
            if (totalSize <= budgetBytes) return@withContext emptyList()
            val candidates = current
                .filterNot { it.id in pinnedIds }
                .sortedBy { it.effectiveLastUsedEpochSec() }
            val deleted = mutableListOf<String>()
            for (candidate in candidates) {
                if (totalSize <= budgetBytes) break
                val file = File(candidate.filePath)
                if (file.exists()) file.delete()
                val parent = file.parentFile
                if (parent != null && parent.exists() && parent.list().isNullOrEmpty()) parent.delete()
                totalSize -= candidate.sizeBytes
                deleted += candidate.id
            }
            if (deleted.isNotEmpty()) {
                val updated = current.filterNot { it.id in deleted }
                _installed.value = updated
                persist(updated)
            }
            deleted
        }
    }

    /**
     * Copy the file referenced by [uri] into the models dir and return its [File] location
     * along with a display filename. Caller is responsible for [register]ing an [InstalledModel].
     */
    suspend fun importFromUri(uri: Uri, modelId: String): Pair<File, String> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "imported.gguf"

        val target = fileFor(modelId, displayName)
        if (target.exists()) target.delete()
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open input stream for $uri" }
            target.outputStream().use { out -> input.copyTo(out, bufferSize = 64 * 1024) }
        }
        target to displayName
    }

    private fun loadIndex(): List<InstalledModel> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val text = indexFile.readText()
            if (text.isBlank()) emptyList() else listAdapter.fromJson(text).orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun persist(models: List<InstalledModel>) {
        indexFile.writeText(listAdapter.toJson(models))
    }
}
