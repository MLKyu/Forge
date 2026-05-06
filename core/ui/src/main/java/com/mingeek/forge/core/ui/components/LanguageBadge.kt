package com.mingeek.forge.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mingeek.forge.domain.LanguageHints
import java.util.Locale

/**
 * Compact label showing which language(s) the model claims, color-coded
 * against the user's primary locale so the answer to "will this model
 * understand me?" is readable at a glance:
 *
 * - **Green** — model explicitly supports [userLocale] (or is tagged as
 *   multilingual). Label is the native language name (e.g., "한국어") or
 *   [multilingualLabel].
 * - **Amber** — model has language tags but [userLocale] isn't among
 *   them. Label still names what the model *does* support so the user
 *   understands why it's flagged (e.g., "English" for an English-only
 *   model on a Korean device).
 * - **Hidden** — [languages] is empty. Unknown is not the same as
 *   "English-only"; we don't want to make confident-but-wrong claims.
 *
 * Color is layered on top of the text label so this stays color-blind
 * safe — the label itself carries the same information.
 */
@Composable
fun LanguageBadge(
    languages: Set<String>,
    multilingualLabel: String,
    userLocale: String = LanguageHints.currentLocale(),
    modifier: Modifier = Modifier,
) {
    if (languages.isEmpty()) return
    val supported = LanguageHints.supportsLocale(languages, userLocale) &&
        // supportsLocale returns true for empty `languages` too — guard
        // against that path so the badge doesn't claim "supported" for
        // unknown sets. (We already returned for empty above, but the
        // double-check keeps the call site obvious.)
        languages.isNotEmpty()
    val label = when {
        LanguageHints.MULTILINGUAL in languages -> multilingualLabel
        languages.size == 1 -> displayName(languages.first())
        else -> languages.take(2).joinToString(" · ") { displayName(it) }
    }
    val (bg, fg) = if (supported) SUPPORTED_BG to SUPPORTED_FG
    else UNSUPPORTED_BG to UNSUPPORTED_FG
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun displayName(code: String): String {
    val nice = Locale.forLanguageTag(code).displayLanguage
    return nice.ifBlank { code.uppercase() }
}

// Saturated enough to read at small sizes against the surface, muted
// enough not to compete with DeviceFitBadge's RED tier when both sit in
// the same row.
private val SUPPORTED_BG = Color(0xFF1B5E20)   // dark green
private val SUPPORTED_FG = Color.White
private val UNSUPPORTED_BG = Color(0xFFE65100) // amber / deep orange
private val UNSUPPORTED_FG = Color.White
