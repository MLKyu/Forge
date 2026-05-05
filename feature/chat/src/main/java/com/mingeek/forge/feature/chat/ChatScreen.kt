package com.mingeek.forge.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.forge.data.storage.InstalledModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) viewModel.export(uri, resolver)
    }

    // From a loaded chat, back should land on the model picker (the
    // virtual "previous screen" within this tab) rather than popping the
    // whole nav stack — auto-load jumps Idle → Ready on tab entry, so
    // without this the picker is unreachable via back.
    BackHandler(enabled = state.sessionState is SessionState.Ready) {
        viewModel.unload()
    }
    Column(modifier = modifier.fillMaxSize()) {
        when (val s = state.sessionState) {
            SessionState.Idle, is SessionState.Failed ->
                ModelPicker(
                    installed = installed,
                    pastConversations = state.pastConversations,
                    failed = s as? SessionState.Failed,
                    onSelect = viewModel::loadModel,
                    onResumeWith = viewModel::loadModelAndResume,
                    onDeleteConversation = viewModel::deleteConversation,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is SessionState.Ready -> ChatBody(
                ready = s,
                state = state,
                installed = installed,
                onDraftChanged = viewModel::onDraftChanged,
                onSend = viewModel::send,
                onCancel = viewModel::cancelGeneration,
                onClear = viewModel::clearMessages,
                onUnload = viewModel::unload,
                onExport = { exportLauncher.launch("chat.md") },
                onSwap = viewModel::swapModel,
                onNewConversation = viewModel::newConversation,
                onResumeConversation = viewModel::resumeConversation,
                onDeleteConversation = viewModel::deleteConversation,
            )
        }
    }
}

@Composable
private fun ModelPicker(
    installed: List<InstalledModel>,
    pastConversations: List<PastConversation>,
    failed: SessionState.Failed?,
    onSelect: (InstalledModel) -> Unit,
    onResumeWith: (InstalledModel, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(stringResource(R.string.chat_select_a_model), style = MaterialTheme.typography.headlineSmall)
        if (failed != null) {
            val msg = if (failed.arg != null) {
                stringResource(failed.messageRes, failed.arg)
            } else {
                stringResource(failed.messageRes)
            }
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (installed.isEmpty()) {
            Text(
                stringResource(R.string.chat_no_models_installed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }
        // Most-recently-used floats to the top; never-used models are
        // ordered by install time. Index per model id makes the
        // per-card filtering O(1) and isolates the sort policy from
        // ModelPickerCard's render logic.
        val sorted = remember(installed) {
            installed.sortedByDescending { it.lastUsedEpochSec ?: 0L }
        }
        val convsByModel = remember(pastConversations) {
            pastConversations.groupBy { it.lastModelId }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sorted, key = { it.id }) { model ->
                ModelPickerCard(
                    model = model,
                    conversations = convsByModel[model.id].orEmpty(),
                    onLoad = { onSelect(model) },
                    onResume = { convId -> onResumeWith(model, convId) },
                    onDelete = onDeleteConversation,
                )
            }
        }
    }
}

@Composable
private fun ModelPickerCard(
    model: InstalledModel,
    conversations: List<PastConversation>,
    onLoad: () -> Unit,
    onResume: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(model.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                "${model.fileName} · ${model.quantization.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                lastUsedLabel(model),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onLoad) {
                    Text(stringResource(R.string.chat_start_new))
                }
                if (conversations.isNotEmpty()) {
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(R.string.chat_history_count, conversations.size))
                    }
                }
            }
            if (expanded && conversations.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp))
                // Most recent first. Cap to 10 — anyone with a long
                // history can still find older chats via the in-chat
                // history sheet, which doesn't truncate.
                val ordered = conversations.sortedByDescending { it.updatedAtEpochSec }.take(10)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (conv in ordered) {
                        ConversationRow(
                            conv = conv,
                            onResume = { onResume(conv.id) },
                            onDelete = { onDelete(conv.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conv: PastConversation,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onResume)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                conv.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.chat_message_count, conv.messageCount) +
                    " · " + relativeTime(conv.updatedAtEpochSec),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.chat_delete))
        }
    }
}

@Composable
private fun lastUsedLabel(model: InstalledModel): String {
    val sec = model.lastUsedEpochSec
    return if (sec == null) {
        stringResource(R.string.chat_never_used)
    } else {
        stringResource(R.string.chat_last_used, relativeTime(sec))
    }
}

@Composable
private fun relativeTime(epochSec: Long): String {
    val now = System.currentTimeMillis() / 1000
    val deltaSec = (now - epochSec).coerceAtLeast(0)
    val daysAgo = (deltaSec / 86_400).toInt()
    val hm = HM_FORMATTER.format(java.time.Instant.ofEpochSecond(epochSec))
    return when {
        daysAgo == 0 -> stringResource(R.string.chat_relative_today, hm)
        daysAgo == 1 -> stringResource(R.string.chat_relative_yesterday, hm)
        else -> stringResource(R.string.chat_relative_days, daysAgo)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatBody(
    ready: SessionState.Ready,
    state: ChatUiState,
    installed: List<InstalledModel>,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onUnload: () -> Unit,
    onExport: () -> Unit,
    onSwap: (InstalledModel) -> Unit,
    onNewConversation: () -> Unit,
    onResumeConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
) {
    val model = ready.model
    val template = ready.template
    val listState = rememberLazyListState()
    var showSwapSheet by remember { mutableStateOf(false) }
    var showConversationsSheet by remember { mutableStateOf(false) }

    // Stick to the *bottom* of the last message — not its top. While the
    // assistant is streaming a long answer, `animateScrollToItem(lastIndex)`
    // alone aligns the bubble's top with the viewport top, so any text
    // beyond viewport height stays clipped below until generation ends. We
    // first ensure the item is on screen, then push the viewport forward
    // by however much the bubble's bottom overflows.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        val lastIndex = state.messages.lastIndex
        val visibleLast = listState.layoutInfo.visibleItemsInfo.find { it.index == lastIndex }
        if (visibleLast == null) {
            listState.animateScrollToItem(lastIndex)
        }
        // Re-read layout info after potential scroll above; the item might
        // be visible now but its bottom can still overflow the viewport.
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.find { it.index == lastIndex } ?: return@LaunchedEffect
        val overflow = (item.offset + item.size) - info.viewportEndOffset
        if (overflow > 0) {
            listState.scrollBy(overflow.toFloat())
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    "${model.quantization.name} · ${model.recommendedRuntime} · ${template.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // The most-used action stays a primary tap target. Everything
            // else collapses into the overflow menu — six TextButtons in
            // a phone-width Row was wrapping into a multi-line header
            // that pushed the input row past the bottom navigation bar.
            IconButton(onClick = onNewConversation) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_new))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_chats)) },
                        enabled = state.pastConversations.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            showConversationsSheet = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_swap)) },
                        enabled = installed.size > 1,
                        onClick = {
                            menuExpanded = false
                            showSwapSheet = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_export)) },
                        enabled = state.messages.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_clear)) },
                        enabled = state.messages.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            onClear()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_unload)) },
                        onClick = {
                            menuExpanded = false
                            onUnload()
                        },
                    )
                }
            }
        }
        HorizontalDivider()

        if (showSwapSheet) {
            ModalBottomSheet(onDismissRequest = { showSwapSheet = false }) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.chat_swap_model_title), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.chat_swap_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val others = installed.filter { it.id != model.id }
                        items(others, key = { it.id }) { other ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSwapSheet = false
                                        onSwap(other)
                                    },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(other.displayName, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${other.fileName} · ${other.quantization.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showConversationsSheet) {
            ModalBottomSheet(onDismissRequest = { showConversationsSheet = false }) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.chat_past_chats_title), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.chat_past_chats_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.pastConversations, key = { it.id }) { conv ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showConversationsSheet = false
                                            onResumeConversation(conv.id)
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(conv.title, fontWeight = FontWeight.Medium)
                                        Text(
                                            stringResource(R.string.chat_message_count, conv.messageCount) +
                                                (conv.lastModelId?.let { " · $it" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { onDeleteConversation(conv.id) }) {
                                        Text(stringResource(R.string.chat_delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(state.messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Material3 OutlinedTextField has a hard 56dp min height baked
            // into its layout; for a chat composer that feels oversized.
            // Drop down to BasicTextField with a thin tinted background +
            // our own padding to land at ~40dp single-line, growing to
            // ~120dp (≈5 lines) before scrolling internally.
            CompactChatField(
                value = state.draft,
                onValueChange = onDraftChanged,
                placeholder = stringResource(R.string.chat_type_a_message),
                enabled = !state.isGenerating,
                modifier = Modifier.weight(1f),
            )
            // Disable the 48dp minimum-touch-target enforcement so the
            // button's layout height equals its visible 40dp surface —
            // otherwise Bottom alignment makes the input look ~4dp lower
            // than the button (the gap is invisible touch-target padding).
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                if (state.isGenerating) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.chat_stop)) }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = state.draft.isNotBlank(),
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(stringResource(R.string.chat_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactChatField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 5,
        modifier = modifier
            .heightIn(min = 40.dp, max = 120.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = placeholderColor,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    if (msg.role == ChatMessage.Role.SYSTEM) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "— ${msg.content} —",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val isUser = msg.role == ChatMessage.Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Tool calls render above the assistant bubble in invocation order.
        for (entry in msg.toolCalls) {
            ToolCallCard(entry)
        }

        val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
        val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    if (msg.isStreaming && msg.content.isEmpty()) "…" else msg.content,
                    color = fg,
                )
            }
        }
        val caption = buildString {
            append(formatHm(msg.timestampMs))
            msg.usage?.let { u ->
                append(" · P:${u.promptTokens} C:${u.completionTokens}")
            }
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun ToolCallCard(entry: ToolCallEntry) {
    val accent = if (entry.isError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                "🔧 " + entry.toolName + if (entry.isError) " · error" else "",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            Text(
                "args: ${entry.argumentsJson}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "result: ${entry.resultJson}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private val HM_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())

private fun formatHm(epochMs: Long): String =
    HM_FORMATTER.format(java.time.Instant.ofEpochMilli(epochMs))
