package com.mingeek.forge.data.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Process-singleton download manager.
 *
 * Architectural rationale: a `viewModelScope.launch { downloader.download() }`
 * dies the moment the user navigates away, which makes large model
 * downloads unusable. The queue moves ownership to a long-lived scope
 * (the application's), exposes a single [state] flow that any screen
 * (Catalog, Library, notifications) can subscribe to, caps concurrency
 * with a [Semaphore] so we don't thrash the network on a phone, and
 * supports user-initiated pause/resume by cancelling the job while
 * keeping the `.part` file on disk for HTTP Range to pick up later.
 *
 * The actual byte plumbing stays in [ModelDownloader] — the queue is a
 * scheduling + state layer on top.
 *
 * @param parentScope Scope that should outlive ViewModels. Typically the
 *   Application's `appScope`. The queue creates a child SupervisorJob so
 *   one broken download doesn't tear down the others.
 * @param concurrency How many byte-flowing downloads run at once.
 *   Two is a sane phone default — disk and Wi-Fi both hate parallel
 *   contention past that.
 */
class DownloadQueue(
    private val downloader: ModelDownloader,
    parentScope: CoroutineScope,
    concurrency: Int = 2,
) {
    private val scope = parentScope + SupervisorJob() + Dispatchers.IO
    private val semaphore = Semaphore(concurrency)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Keys whose jobs were cancelled via [pause] (and so should land in
     *  Paused state instead of being removed). */
    private val pausing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Serializes the (read state → check job → write state → launch
     * coroutine → store job) sequence in [enqueue] and the
     * (read state → mark pausing/cancelled → cancel job → write state)
     * sequences in [pause]/[cancel]/[resume]/[dismiss]. Without this
     * lock two simultaneous taps (UI button + notification action,
     * both running on Main) can both pass the active-job guard and
     * spawn parallel writers to the same `.part` file, producing
     * interleaved bytes and a SHA-256 mismatch on completion.
     *
     * Held only across non-suspending ops; the actual byte work runs
     * after the launch returns and never holds the lock.
     */
    private val mutationLock = Any()

    private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = _state.asStateFlow()

    /**
     * Schedule [request] for download. No-op if the same key is already
     * queued, running, or verifying. Resumes from a `.part` file
     * automatically via [ModelDownloader]'s Range support.
     */
    fun enqueue(request: DownloadRequest) {
        synchronized(mutationLock) {
            val current = _state.value[request.key]
            if (current is DownloadState.Queued ||
                current is DownloadState.Running ||
                current is DownloadState.Verifying
            ) return
            if (jobs[request.key]?.isActive == true) return

            // Optimistic Queued state — the FG service uses this to know it
            // should stay alive even before the semaphore lets us start.
            _state.update { it + (request.key to DownloadState.Queued(request)) }

            val job = scope.launch {
                try {
                    semaphore.withPermit { runDownload(request) }
                } catch (ce: CancellationException) {
                    handleCancellation(request)
                    throw ce
                }
            }
            jobs[request.key] = job
            job.invokeOnCompletion {
                jobs.remove(request.key, job)
            }
        }
    }

    /**
     * Atomic gate for collector-emitted state transitions: only
     * mutates the entry if it still belongs to *this* request — i.e.
     * the user hasn't cancelled the key (state removed) or kicked off
     * a new request with the same key (different DownloadRequest
     * instance). Without this, a [cancel] on an in-flight key would
     * race the next progress emission and resurrect the entry into a
     * Running state the user already dismissed.
     */
    private inline fun mutateIfStillOurs(
        request: DownloadRequest,
        crossinline transform: (DownloadState?) -> DownloadState?,
    ) {
        _state.update { current ->
            val existing = current[request.key]
            // Same-instance check — Queued was put here by *our* enqueue
            // call and any subsequent transitions inherit the same
            // request reference, so reference equality is the right
            // identity test.
            if (existing != null && existing.request !== request) return@update current
            if (existing == null && current.containsKey(request.key).not()) {
                // Cancelled / dismissed; do not resurrect.
                return@update current
            }
            val next = transform(existing) ?: return@update current - request.key
            current + (request.key to next)
        }
    }

    private suspend fun runDownload(request: DownloadRequest) {
        downloader.download(request.url, request.target, request.expectedSha256).collect { p ->
            when (p) {
                DownloadProgress.Started -> mutateIfStillOurs(request) {
                    DownloadState.Running(request, 0L, request.sizeBytesHint)
                }
                is DownloadProgress.Progress -> mutateIfStillOurs(request) {
                    DownloadState.Running(
                        request,
                        p.bytesDownloaded,
                        p.totalBytes ?: request.sizeBytesHint,
                    )
                }
                is DownloadProgress.Verifying -> mutateIfStillOurs(request) { existing ->
                    val total = (existing as? DownloadState.Running)?.totalBytes
                        ?: request.sizeBytesHint
                    DownloadState.Verifying(request, total)
                }
                is DownloadProgress.Completed -> {
                    // Skip onCompleted side effect if the user already
                    // cancelled — the rare-but-possible case where bytes
                    // landed at the same instant as a cancel tap.
                    val stillOurs = _state.value[request.key]?.request === request
                    if (stillOurs) {
                        runCatching { request.onCompleted?.invoke(p.file) }
                    }
                    mutateIfStillOurs(request) { DownloadState.Completed(request, p.file) }
                }
                is DownloadProgress.Failed -> mutateIfStillOurs(request) {
                    DownloadState.Failed(request, p.message)
                }
            }
        }
    }

    private fun handleCancellation(request: DownloadRequest) {
        if (pausing.remove(request.key)) {
            val current = _state.value[request.key]
            val (bytes, total) = when (current) {
                is DownloadState.Running -> current.bytesDownloaded to current.totalBytes
                is DownloadState.Verifying -> (request.sizeBytesHint ?: 0L) to current.totalBytes
                else -> 0L to request.sizeBytesHint
            }
            _state.update { it + (request.key to DownloadState.Paused(request, bytes, total)) }
        } else {
            _state.update { it - request.key }
            scope.launch { deletePartial(request.target) }
        }
    }

    /** Pause a running/queued download. Keeps `.part` on disk for resume. */
    fun pause(key: String) {
        synchronized(mutationLock) {
            val st = _state.value[key] ?: return
            if (st !is DownloadState.Queued &&
                st !is DownloadState.Running &&
                st !is DownloadState.Verifying
            ) return
            pausing.add(key)
            jobs[key]?.cancel()
        }
    }

    /** Resume a paused download. No-op for any other state. */
    fun resume(key: String) {
        val req = synchronized(mutationLock) {
            (_state.value[key] as? DownloadState.Paused)?.request
        } ?: return
        enqueue(req)
    }

    /**
     * Abort + remove. Deletes the `.part` file so a future enqueue
     * starts from zero. Safe to call from any state — completed
     * downloads are simply dropped from [state] without touching the
     * (already-renamed) target file.
     */
    fun cancel(key: String) {
        val st = synchronized(mutationLock) {
            val current = _state.value[key]
            pausing.remove(key)
            jobs[key]?.cancel()
            // Removing state synchronously (under the lock) is what
            // makes [mutateIfStillOurs] correctly drop any in-flight
            // emission from the cancelled job.
            _state.update { it - key }
            current
        }
        if (st != null && st !is DownloadState.Completed) {
            scope.launch { deletePartial(st.request.target) }
        }
    }

    /** Forget terminal state (Completed / Failed) so the row clears from UI. */
    fun dismiss(key: String) {
        synchronized(mutationLock) {
            val st = _state.value[key] ?: return
            if (st is DownloadState.Completed || st is DownloadState.Failed) {
                _state.update { it - key }
            }
        }
    }

    private fun deletePartial(target: File) {
        runCatching {
            val partial = File(target.parentFile, "${target.name}.part")
            if (partial.exists()) partial.delete()
        }
    }
}
