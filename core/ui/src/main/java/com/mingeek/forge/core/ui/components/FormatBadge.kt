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
import com.mingeek.forge.domain.ModelFormat

@Composable
fun FormatBadge(format: ModelFormat, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (format) {
        ModelFormat.GGUF -> Triple(Color(0xFF1A237E), Color.White, "GGUF")
        ModelFormat.MEDIAPIPE_TASK -> Triple(Color(0xFF004D40), Color.White, "MediaPipe")
        ModelFormat.EXECUTORCH_PTE -> Triple(Color(0xFF311B92), Color.White, "ExecuTorch")
        ModelFormat.MLC -> Triple(Color(0xFFBF360C), Color.White, "MLC")
        ModelFormat.SAFETENSORS,
        ModelFormat.ONNX -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurface,
            format.name,
        )
    }
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
