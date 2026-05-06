package com.mingeek.forge.domain

/**
 * Open / close string pair that brackets a model's internal reasoning in
 * its raw output stream. DeepSeek-R1 and downstream distills emit
 * `<think>...</think>`. Bound to [ChatTemplate] so we only attempt to
 * parse when the active template's model actually uses them — avoids
 * mistaking literal `<think>` text in unrelated models for state.
 */
data class ReasoningMarkers(val open: String, val close: String) {
    companion object {
        /** R1 / Distill / QwQ-style reasoning markers. */
        val THINK = ReasoningMarkers(open = "<think>", close = "</think>")
    }
}

/**
 * Splits a streaming text feed into reasoning (between markers) and
 * regular content. Robust against markers split across pieces — a small
 * lookback buffer holds suffix chars that *could* still grow into the
 * marker until either the marker completes or the model emits something
 * that can't continue the prefix.
 *
 * Single-pass per piece. Caller feeds raw text deltas as they arrive
 * from the runtime; the returned [Delta] carries the post-split deltas
 * to append to the message's reasoning / content fields, plus a flag
 * exposing whether the parser is currently inside a reasoning block
 * (UI state for "thinking…" indicators).
 */
class ReasoningStreamParser(private val markers: ReasoningMarkers) {

    private var inside: Boolean = false
    private val pending = StringBuilder()

    val isReasoning: Boolean get() = inside

    data class Delta(
        val reasoningDelta: String,
        val contentDelta: String,
        val isReasoning: Boolean,
    )

    fun feed(piece: String): Delta {
        pending.append(piece)
        val reasoning = StringBuilder()
        val content = StringBuilder()

        while (pending.isNotEmpty()) {
            val marker = if (inside) markers.close else markers.open
            val markerIdx = indexOfPotentialMarker(pending, marker)

            if (markerIdx < 0) {
                // No marker prefix found anywhere — flush everything to
                // the current sink and we're done with this piece.
                flushTo(if (inside) reasoning else content, pending.length)
                break
            }

            // Flush the prefix before the (potential) marker.
            if (markerIdx > 0) {
                flushTo(if (inside) reasoning else content, markerIdx)
            }

            // Is the buffered region a *complete* marker? If so, consume
            // it and flip state. Otherwise it's a partial prefix and we
            // wait for the next piece.
            if (pending.length >= marker.length &&
                pending.substring(0, marker.length) == marker
            ) {
                pending.delete(0, marker.length)
                inside = !inside
            } else {
                break
            }
        }

        return Delta(
            reasoningDelta = reasoning.toString(),
            contentDelta = content.toString(),
            isReasoning = inside,
        )
    }

    /**
     * Called when the runtime stops generating, so any leftover pending
     * chars (incomplete marker prefixes that the model never finished)
     * land in the current sink rather than vanishing.
     */
    fun flush(): Delta {
        val reasoning = StringBuilder()
        val content = StringBuilder()
        if (pending.isNotEmpty()) {
            flushTo(if (inside) reasoning else content, pending.length)
        }
        return Delta(reasoning.toString(), content.toString(), inside)
    }

    private fun flushTo(sink: StringBuilder, length: Int) {
        sink.append(pending, 0, length)
        pending.delete(0, length)
    }

    /**
     * Index of the first character that *could* be the start of [marker]
     * in [s]. Returns the position of the full marker if present;
     * otherwise the position of the longest tail of [s] that's also a
     * prefix of [marker] (the "partial-marker reservation"). Returns -1
     * when [s] has no chars that need to be reserved.
     */
    private fun indexOfPotentialMarker(s: CharSequence, marker: String): Int {
        val full = s.indexOf(marker)
        if (full >= 0) return full
        // Reserve a trailing prefix of marker so a split like
        // "<thi" + "nk>" doesn't leak the partial as content.
        val maxPrefixLen = minOf(marker.length - 1, s.length)
        for (len in maxPrefixLen downTo 1) {
            val start = s.length - len
            if (marker.startsWith(s.substring(start))) return start
        }
        return -1
    }
}
