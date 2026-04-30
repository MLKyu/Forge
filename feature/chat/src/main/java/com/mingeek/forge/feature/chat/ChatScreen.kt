package com.mingeek.forge.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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

    Column(modifier = modifier.fillMaxSize()) {
        when (val s = state.sessionState) {
            SessionState.Idle, is SessionState.Failed ->
                ModelPicker(
                    installed = installed,
                    failedMessage = (s as? SessionState.Failed)?.message,
                    onSelect = viewModel::loadModel,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is SessionState.Ready -> ChatBody(
                ready = s,
                state = state,
                onDraftChanged = viewModel::onDraftChanged,
                onSend = viewModel::send,
                onCancel = viewModel::cancelGeneration,
                onClear = viewModel::clearMessages,
                onUnload = viewModel::unload,
                onExport = { exportLauncher.launch("chat.md") },
            )
        }
    }
}

@Composable
private fun ModelPicker(
    installed: List<InstalledModel>,
    failedMessage: String?,
    onSelect: (InstalledModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Select a model", style = MaterialTheme.typography.headlineSmall)
        if (failedMessage != null) {
            Text(
                "Load failed: $failedMessage",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (installed.isEmpty()) {
            Text(
                "No models installed. Visit the Catalog tab to download GGUF models from HuggingFace.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(installed, key = { it.id }) { model ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(model.displayName, fontWeight = FontWeight.Medium)
                        Text(
                            "${model.fileName} · ${model.quantization.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { onSelect(model) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Load")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBody(
    ready: SessionState.Ready,
    state: ChatUiState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onUnload: () -> Unit,
    onExport: () -> Unit,
) {
    val model = ready.model
    val template = ready.template
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(model.displayName, fontWeight = FontWeight.Medium)
                Text(
                    "${model.quantization.name} · ${model.recommendedRuntime} · template: ${template.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onExport, enabled = state.messages.isNotEmpty()) { Text("Export") }
            TextButton(onClick = onClear, enabled = state.messages.isNotEmpty()) { Text("Clear") }
            TextButton(onClick = onUnload) { Text("Unload") }
        }
        HorizontalDivider()

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
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") },
                enabled = !state.isGenerating,
            )
            if (state.isGenerating) {
                OutlinedButton(onClick = onCancel) { Text("Stop") }
            } else {
                Button(onClick = onSend, enabled = state.draft.isNotBlank()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
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

private val HM_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())

private fun formatHm(epochMs: Long): String =
    HM_FORMATTER.format(java.time.Instant.ofEpochMilli(epochMs))
