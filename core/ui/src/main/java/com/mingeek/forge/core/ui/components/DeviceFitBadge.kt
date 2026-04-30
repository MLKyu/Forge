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
import com.mingeek.forge.domain.DeviceFitScore

@Composable
fun DeviceFitBadge(
    score: DeviceFitScore,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, label) = when (score.tier) {
        DeviceFitScore.Tier.GREEN ->
            Triple(Color(0xFF1B5E20), Color.White, "GREEN")
        DeviceFitScore.Tier.YELLOW ->
            Triple(Color(0xFFF9A825), Color.Black, "YELLOW")
        DeviceFitScore.Tier.RED ->
            Triple(Color(0xFFB71C1C), Color.White, "RED")
        DeviceFitScore.Tier.UNSUPPORTED ->
            Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, "N/A")
    }
    val tps = score.estimatedTokensPerSecond
    val text = if (tps != null && tps > 0) {
        "$label · ~%.0f tok/s".format(tps)
    } else label

    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
