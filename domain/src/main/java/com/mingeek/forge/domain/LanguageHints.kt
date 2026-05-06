package com.mingeek.forge.domain

import java.util.Locale

/**
 * Helpers for inferring and reasoning about which spoken languages a model
 * can handle. Most catalogs (HuggingFace especially) carry `language:xx`
 * tags or a `cardData.language` field, but GGUF re-uploads frequently
 * strip them — so [parseHf] layers (1) HF tags, (2) HF cardData,
 * (3) a small repo-name heuristic for known multilingual / Korean-specific
 * families.
 *
 * Codes are lowercase ISO 639-1 (`"en"`, `"ko"`, `"ja"`, ...). The special
 * token [MULTILINGUAL] means "explicitly trained for many languages" —
 * treat it as covering any locale.
 */
object LanguageHints {

    const val MULTILINGUAL = "multi"

    /**
     * True when [languages] is empty (unknown — don't penalize), [locale]
     * is blank, or the model's tags include [locale] / [MULTILINGUAL].
     */
    fun supportsLocale(languages: Set<String>, locale: String): Boolean {
        if (languages.isEmpty()) return true
        if (locale.isBlank()) return true
        val l = locale.lowercase()
        return l in languages || MULTILINGUAL in languages
    }

    /** System locale's primary language tag, lowercased ISO 639-1. */
    fun currentLocale(): String = Locale.getDefault().language.lowercase()

    /**
     * Parse from the union of all available HuggingFace metadata.
     *
     * - [tags] entries like `language:ko`, `language:multilingual`, or a
     *   bare `multilingual`.
     * - [cardData]`["language"]` per HF spec is either a `String` or a
     *   `List<String>` of ISO 639 codes (or `multilingual`).
     * - [repoId] feeds a name-based fallback for cards stripped of YAML.
     */
    fun parseHf(tags: List<String>, cardData: Map<String, Any?>?, repoId: String): Set<String> {
        val out = mutableSetOf<String>()
        for (raw in tags) {
            val t = raw.lowercase()
            when {
                t == "multilingual" -> out += MULTILINGUAL
                t.startsWith("language:") -> {
                    val code = t.removePrefix("language:").trim()
                    if (code == "multilingual") out += MULTILINGUAL
                    else if (code.isNotEmpty()) out += code
                }
            }
        }
        when (val lang = cardData?.get("language")) {
            is String -> addLangToken(out, lang)
            is List<*> -> for (item in lang) addLangToken(out, item as? String ?: continue)
            else -> Unit
        }
        if (out.isEmpty()) out += inferFromName(repoId)
        return out
    }

    /**
     * Best-effort fallback for repos whose card has no language metadata.
     * Recognized: known Korean-trained families (returns `{"ko"}`), and
     * a conservative set of multilingual flagship families (returns
     * `{MULTILINGUAL}`). Anything else returns empty so we don't make
     * confident-but-wrong claims.
     */
    fun inferFromName(repoId: String): Set<String> {
        val n = repoId.lowercase()
        if (KOREAN_PATTERNS.any { it.containsMatchIn(n) }) return setOf("ko")
        if (MULTILINGUAL_PATTERNS.any { it.containsMatchIn(n) }) return setOf(MULTILINGUAL)
        return emptySet()
    }

    private fun addLangToken(out: MutableSet<String>, raw: String) {
        val v = raw.lowercase().trim()
        if (v.isEmpty()) return
        if (v == "multilingual") out += MULTILINGUAL else out += v
    }

    private val KOREAN_PATTERNS: List<Regex> = listOf(
        Regex("\\bpolyglot-?ko\\b"),
        Regex("\\bkullm\\b"),
        Regex("\\bko[a-z]*alpaca\\b"),
        Regex("\\bkanana\\b"),
        Regex("\\beeve\\b"),
        Regex("\\bsolar.*ko\\b"),
    )

    /**
     * Conservative — only families that ship explicit multilingual training.
     * Hits trim by major version where post-1.x added multilingual data
     * (e.g. Llama-3.0 was English-heavy; 3.1+ shipped multilingual).
     */
    private val MULTILINGUAL_PATTERNS: List<Regex> = listOf(
        Regex("\\bgemma-?[23]\\b"),
        Regex("\\bqwen-?[23](?:\\.\\d+)?\\b"),
        Regex("\\bllama-?3\\.[1-9]\\b"),
        Regex("\\bexaone\\b"),
        Regex("\\baya-(?:23|101)\\b"),
        Regex("\\bbloom\\b"),
        Regex("\\bcommand-r\\b"),
        Regex("\\bmistral-nemo\\b"),
    )
}
