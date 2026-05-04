package com.mingeek.forge.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mingeek.forge.core.ui.R
import kotlinx.coroutines.launch

/**
 * Small (?) help glyph that, on tap, surfaces a Material3 rich tooltip
 * carrying [text] (and an optional [title]).
 *
 * Designed to live inline next to a feature label so the screen can
 * stay terse for power users while first-timers get a one-tap
 * explanation. The tooltip is `isPersistent = true` so a quick tap
 * doesn't auto-dismiss before the user can read; tapping outside the
 * tooltip closes it. We size the icon down to ~18dp and rely on the
 * surrounding layout for tap-target padding — the standard
 * `IconButton` 48dp box would dominate any inline header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTooltip(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    iconSize: Dp = 18.dp,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                title = if (title != null) ({ Text(title) }) else null,
                text = { Text(text) },
            )
        },
        state = tooltipState,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.ui_info_tooltip_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(iconSize)
                .clickable { scope.launch { tooltipState.show() } },
        )
    }
}
