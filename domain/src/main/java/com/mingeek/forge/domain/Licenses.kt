package com.mingeek.forge.domain

/**
 * Centralized license inference. Discovery sources, the catalog, and any
 * future ingest path should call [Licenses.fromTags] (or [Licenses.fromSpdx])
 * so the commercial-use signal is consistent everywhere.
 *
 * The classification is intentionally conservative — anything we don't
 * recognise lands in `unknown`. False negatives are fine; we'd rather warn
 * a user about a model with a permissive license than silently allow a
 * non-commercial model into a commercial workflow.
 */
object Licenses {

    /**
     * Inspect a list of HuggingFace tags (`["license:apache-2.0", ...]`) and
     * produce a [License]. Returns an "unknown" license when no tag matches.
     */
    fun fromTags(tags: List<String>): License {
        val raw = tags.firstOrNull { it.startsWith("license:") }
            ?.substringAfter("license:")
            ?.lowercase()
        return fromSpdx(raw)
    }

    fun fromSpdx(spdxId: String?): License {
        if (spdxId.isNullOrBlank()) {
            return License(spdxId = "unknown", displayName = "Unknown", commercialUseAllowed = false)
        }
        val normalized = spdxId.lowercase()
        val pretty = DISPLAY_NAMES[normalized] ?: normalized
        return License(
            spdxId = normalized,
            displayName = pretty,
            commercialUseAllowed = normalized in COMMERCIAL_OK,
        )
    }

    /** Permissive licenses we trust for commercial use without further review. */
    private val COMMERCIAL_OK: Set<String> = setOf(
        // Common open source
        "apache-2.0",
        "mit",
        "mit-0",
        "bsd-2-clause",
        "bsd-3-clause",
        "isc",
        "unlicense",
        "0bsd",
        // Documentation-style permissive
        "cc-by-4.0",
        "cc0-1.0",
        // OpenRAIL family is permissive enough for commercial use
        "openrail",
        "bigscience-openrail-m",
    )

    /** Pretty names for the SPDX ids we know. */
    private val DISPLAY_NAMES: Map<String, String> = mapOf(
        "apache-2.0" to "Apache-2.0",
        "mit" to "MIT",
        "mit-0" to "MIT-0",
        "bsd-2-clause" to "BSD-2-Clause",
        "bsd-3-clause" to "BSD-3-Clause",
        "isc" to "ISC",
        "unlicense" to "Unlicense",
        "0bsd" to "0BSD",
        "cc-by-4.0" to "CC-BY-4.0",
        "cc-by-nc-4.0" to "CC-BY-NC-4.0 (non-commercial)",
        "cc-by-sa-4.0" to "CC-BY-SA-4.0",
        "cc0-1.0" to "CC0-1.0",
        "gpl-3.0" to "GPL-3.0",
        "lgpl-2.1" to "LGPL-2.1",
        "agpl-3.0" to "AGPL-3.0",
        "mpl-2.0" to "MPL-2.0",
        "openrail" to "OpenRAIL",
        "bigscience-openrail-m" to "BigScience OpenRAIL-M",
        "llama2" to "Llama-2 (custom)",
        "llama3" to "Llama-3 (custom)",
        "llama3.1" to "Llama-3.1 (custom)",
        "llama3.2" to "Llama-3.2 (custom)",
        "llama3.3" to "Llama-3.3 (custom)",
        "gemma" to "Gemma (custom)",
        "qwen" to "Qwen (custom)",
        "deepseek-license" to "DeepSeek (custom)",
        "other" to "Other (review required)",
        "proprietary" to "Proprietary (review required)",
    )
}
