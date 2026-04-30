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
import com.mingeek.forge.domain.License

@Composable
fun LicenseChip(
    license: License,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, prefix) = when {
        license.spdxId.equals("unknown", ignoreCase = true) ->
            Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, "?")
        license.commercialUseAllowed ->
            Triple(Color(0xFF1B5E20), Color.White, "✓")
        else ->
            Triple(Color(0xFFF9A825), Color.Black, "⚠")
    }
    Text(
        "$prefix ${license.spdxId}",
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
