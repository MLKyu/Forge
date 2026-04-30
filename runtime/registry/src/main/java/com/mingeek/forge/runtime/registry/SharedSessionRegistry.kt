package com.mingeek.forge.runtime.registry

import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.InferenceRuntime
import com.mingeek.forge.runtime.core.LoadConfig
import com.mingeek.forge.runtime.core.LoadedModel
import com.mingeek.forge.runtime.core.ModelHandle
import com.mingeek.forge.runtime.core.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches loaded model sessions keyed by modelId so that a workflow with several
 * steps using the same model only loads it once. Lifetime is owned by the caller:
 * create one per workflow run, call [releaseAll] in a `finally` block.
 *
 * Thread-safe. [acquire] is idempotent for the same modelId — the second caller
 * receives the existing session.
 */
class SharedSessionRegistry(
    private val registry: RuntimeRegistry,
) {

    private data class Entry(val runtime: InferenceRuntime, val loaded: LoadedModel)

    private val sessions = mutableMapOf<String, Entry>()
    private val mutex = Mutex()

    /**
     * Returns the (runtime, loaded model) pair for [model], loading it on first
     * call and reusing on subsequent calls. Returns null if no runtime supports
     * the model's format.
     */
    suspend fun acquire(model: InstalledModel, config: LoadConfig): Pair<InferenceRuntime, LoadedModel>? =
        mutex.withLock {
            sessions[model.id]?.let { return it.runtime to it.loaded }

            val runtimeId = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
                .getOrDefault(RuntimeId.LLAMA_CPP)
            val runtime = registry.pick(model.format, runtimeId) ?: return null

            val handle = ModelHandle(
                modelId = model.id,
                modelPath = model.filePath,
                format = model.format,
            )
            val loaded = runtime.load(handle, config)
            val entry = Entry(runtime, loaded)
            sessions[model.id] = entry
            runtime to loaded
        }

    /**
     * Look up the cached session for a previously [acquire]d model. Returns null
     * if it hasn't been loaded yet.
     */
    suspend fun peek(modelId: String): Pair<InferenceRuntime, SessionId>? = mutex.withLock {
        sessions[modelId]?.let { it.runtime to it.loaded.sessionId }
    }

    /**
     * Unloads every session that's been acquired through this registry.
     * Idempotent — safe to call multiple times.
     */
    suspend fun releaseAll() = mutex.withLock {
        for ((_, entry) in sessions) {
            runCatching { entry.runtime.unload(entry.loaded.sessionId) }
        }
        sessions.clear()
    }
}
