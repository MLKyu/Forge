package com.mingeek.forge.data.download

import java.io.File

/**
 * Self-contained description of a download.
 *
 * Carries everything [DownloadQueue] needs to start, label notifications,
 * and perform terminal-state side effects without holding refs to UI
 * scope. [onCompleted] runs on a queue-owned coroutine after byte
 * delivery + (optional) SHA-256 verification, so callers can register
 * the file in their own catalog without surviving a ViewModel.
 *
 * @property key Stable identifier — typically the resolve URL. Used as
 *               the map key in [DownloadQueue.state] and as the
 *               notification id seed, so it must be unique per logical
 *               download. Re-enqueueing the same key is a no-op while
 *               the previous job is active or paused.
 * @property displayName Short label rendered in the notification. Should
 *                      already be localized.
 * @property sizeBytesHint Pre-fetch byte total used to render a
 *                        determinate progress bar before HTTP responds
 *                        with `Content-Length`. Null is fine.
 * @property onCompleted Optional terminal hook. Captures whatever the
 *                      caller wants to persist (e.g. an InstalledModel
 *                      record). Runs on the queue's IO scope, so it
 *                      survives the calling ViewModel's death.
 */
data class DownloadRequest(
    val key: String,
    val url: String,
    val target: File,
    val expectedSha256: String? = null,
    val displayName: String,
    val sizeBytesHint: Long? = null,
    val onCompleted: (suspend (File) -> Unit)? = null,
)
