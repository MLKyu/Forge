package com.mingeek.forge.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mingeek.forge.domain.LanguageHints
import java.util.Locale

/**
 * Compact label showing which language(s) the model claims. The native
 * label uses the device's display locale (so Korean users see "한국어"
 * rather than "Korean"). Renders nothing when [languages] is empty —
 * unknown is not the same as English-only.
 */
@Composable
fun LanguageBadge(
    languages: Set<String>,
    multilingualLabel: String,
    modifier: Modifier = Modifier,
) {
    if (languages.isEmpty()) return
    val label = when {
        LanguageHints.MULTILINGUAL in languages -> multilingualLabel
        languages.size == 1 -> displayName(languages.first())
        else -> languages.take(2).joinToString(" · ") { displayName(it) }
    }
    Text(
        label,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun displayName(code: String): String {
    val nice = Locale.forLanguageTag(code).displayLanguage
    return nice.ifBlank { code.uppercase() }
}
