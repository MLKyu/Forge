package com.mingeek.forge.data.download

import java.io.File

/**
 * Public view of a download tracked by [DownloadQueue]. Every state
 * carries the originating [DownloadRequest] so notification + UI code
 * can render display name / size hint / target file without a separate
 * lookup.
 */
sealed interface DownloadState {
    val request: DownloadRequest

    /** Submitted; waiting on a free concurrency slot. */
    data class Queued(override val request: DownloadRequest) : DownloadState

    /** Bytes are flowing. */
    data class Running(
        override val request: DownloadRequest,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : DownloadState {
        val fraction: Float? get() = totalBytes?.let { if (it > 0) bytesDownloaded.toFloat() / it else null }
    }

    /** Bytes are in, SHA-256 is being computed. Terminal-soon. */
    data class Verifying(
        override val request: DownloadRequest,
        val totalBytes: Long?,
    ) : DownloadState

    /**
     * User-initiated halt. The `.part` file is preserved on disk so
     * [DownloadQueue.resume] can pick up via HTTP Range. Holds the last
     * known progress so the UI doesn't flash to zero.
     */
    data class Paused(
        override val request: DownloadRequest,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : DownloadState {
        val fraction: Float? get() = totalBytes?.let { if (it > 0) bytesDownloaded.toFloat() / it else null }
    }

    /** Bytes saved + verified + (if requested) registered. Terminal. */
    data class Completed(
        override val request: DownloadRequest,
        val file: File,
    ) : DownloadState

    /** HTTP/IO/SHA failure. The `.part` file is left on disk so a retry
     *  can resume from where it died. Terminal until the user retries. */
    data class Failed(
        override val request: DownloadRequest,
        val message: String,
    ) : DownloadState
}

/** True for states where bytes still need to arrive (queue keeps the FG service alive). */
val DownloadState.isActive: Boolean
    get() = this is DownloadState.Queued || this is DownloadState.Running || this is DownloadState.Verifying
