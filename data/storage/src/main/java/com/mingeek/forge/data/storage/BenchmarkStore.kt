package com.mingeek.forge.data.storage

import android.content.Context
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

class BenchmarkStore(context: Context) {

    private val rootDir: File = File(context.filesDir, "benchmarks").apply { mkdirs() }
    private val indexFile: File = File(rootDir, "benchmarks.json")

    private val mutex = Mutex()

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val mapAdapter: JsonAdapter<Map<String, BenchmarkRecord>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, BenchmarkRecord::class.java)
    )

    private val _records = MutableStateFlow(loadIndex())
    val records: StateFlow<Map<String, BenchmarkRecord>> = _records.asStateFlow()

    suspend fun put(record: BenchmarkRecord) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = _records.value + (record.modelId to record)
            _records.value = updated
            persist(updated)
        }
    }

    suspend fun remove(modelId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = _records.value - modelId
            _records.value = updated
            persist(updated)
        }
    }

    private fun loadIndex(): Map<String, BenchmarkRecord> {
        if (!indexFile.exists()) return emptyMap()
        return try {
            val text = indexFile.readText()
            if (text.isBlank()) emptyMap() else mapAdapter.fromJson(text).orEmpty()
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun persist(records: Map<String, BenchmarkRecord>) {
        indexFile.writeText(mapAdapter.toJson(records))
    }
}
