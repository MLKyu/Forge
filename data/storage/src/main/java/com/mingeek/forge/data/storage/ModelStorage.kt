package com.mingeek.forge.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

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
