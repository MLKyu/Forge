package com.mingeek.forge.core.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse

/**
 * Single-line text that picks the largest font size in [[minFontSize], [maxFontSize]]
 * for which [text] fits the available width. Used in narrow slots like a 6-item
 * NavigationBar where labels of different lengths would otherwise wrap (and skew
 * icon alignment) or ellipsize.
 *
 * Measurement happens in a [remember] block keyed by text + width so we don't
 * recompose-flicker. Falls back to ellipsis if even [minFontSize] overflows.
 */
@Composable
fun AutoShrinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxFontSize: TextUnit = style.fontSize.takeOrElse { 12.sp },
    minFontSize: TextUnit = 9.sp,
    stepSize: TextUnit = 0.5.sp,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val resolvedFontSize = remember(text, maxWidthPx, style, maxFontSize, minFontSize, stepSize) {
            require(maxFontSize.isSpecified && minFontSize.isSpecified && stepSize.isSpecified) {
                "AutoShrinkText requires specified text units"
            }
            var size = maxFontSize.value
            val floor = minFontSize.value
            val step = stepSize.value
            while (size > floor) {
                val result = measurer.measure(
                    text = text,
                    style = style.copy(fontSize = size.sp),
                    maxLines = 1,
                    softWrap = false,
                )
                if (result.size.width <= maxWidthPx) break
                size -= step
            }
            size.coerceAtLeast(floor).sp
        }
        Text(
            text = text,
            color = color,
            style = style.copy(fontSize = resolvedFontSize),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
