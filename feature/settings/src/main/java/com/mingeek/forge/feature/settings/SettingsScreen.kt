package com.mingeek.forge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tokenDraft by remember { mutableStateOf(state.hfToken) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = "HuggingFace") {
            val hfContext = LocalContext.current
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = {
                    tokenDraft = it
                    viewModel.onTokenChanged(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Access token") },
                placeholder = { Text("hf_…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                if (state.tokenSet) "Token is set" else "No token (gated repos and rate limits will hit anonymous quota)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://huggingface.co/settings/tokens"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { hfContext.startActivity(intent) }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("Get a token →") }
        }

        SectionCard(title = "Inference defaults") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use NPU when available", fontWeight = FontWeight.Medium)
                    Text(
                        "Falls back to CPU/GPU when no NPU runtime supports the model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.npuEnabled, onCheckedChange = viewModel::onNpuChanged)
            }
            HorizontalDivider()
            Text("Default temperature: %.2f".format(state.temperature), fontWeight = FontWeight.Medium)
            Slider(
                value = state.temperature,
                onValueChange = viewModel::onTemperatureChanged,
                valueRange = 0f..1.5f,
                steps = 14,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enable tools in Chat", fontWeight = FontWeight.Medium)
                    Text(
                        "Lets the model call calculator and current_time. Smaller models may not follow the call format.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.toolsEnabled, onCheckedChange = viewModel::onToolsEnabledChanged)
            }
            if (state.toolsEnabled) {
                Text(
                    "Tool loop max iterations: ${state.toolMaxIterations}",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "How many times the model can call a tool in one turn before we stop the loop. Higher = more chained tool use, but a confused model can burn tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.toolMaxIterations.toFloat(),
                    onValueChange = { viewModel.onToolMaxIterationsChanged(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-cleanup over budget", fontWeight = FontWeight.Medium)
                    Text(
                        "When total Library size exceeds the budget, evict non-pinned models oldest-used first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoCleanupEnabled,
                    onCheckedChange = viewModel::onAutoCleanupEnabledChanged,
                )
            }
            if (state.autoCleanupEnabled) {
                Text(
                    "Budget: ${state.autoCleanupBudgetGb} GB",
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = state.autoCleanupBudgetGb.toFloat(),
                    onValueChange = { viewModel.onAutoCleanupBudgetChanged(it.toInt()) },
                    valueRange = 1f..50f,
                    steps = 48,
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discovery notifications", fontWeight = FontWeight.Medium)
                    Text(
                        "Background polls every ~6 hours and notifies you when new models surface from Trending / Recent / Liked / HF Blog / r/LocalLLaMA. Requires the Notifications permission on Android 13+.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.discoveryNotificationsEnabled,
                    onCheckedChange = viewModel::onDiscoveryNotificationsChanged,
                )
            }
        }

        SectionCard(title = "Device") {
            KeyValue("Model", state.deviceProfile.deviceModel)
            KeyValue("SoC", "${state.deviceProfile.socManufacturer ?: "?"} / ${state.deviceProfile.socModel ?: "?"}")
            KeyValue("CPU cores", state.deviceProfile.cpuCoreCount.toString())
            KeyValue("Total RAM", formatBytes(state.deviceProfile.totalRamBytes))
            KeyValue("Available RAM", formatBytes(state.deviceProfile.availableRamBytes))
            KeyValue("Accelerators", state.deviceProfile.accelerators.joinToString { it.name })
        }

        SectionCard(title = "Storage") {
            val storageContext = LocalContext.current
            val modelsDir = remember(storageContext) {
                java.io.File(storageContext.filesDir, "models").absolutePath
            }
            KeyValue("Installed models", state.storage.installedCount.toString())
            KeyValue("Used by models", formatBytes(state.storage.totalBytes))
            KeyValue("Free disk", formatBytes(state.storage.freeBytes))
            Text(
                "Models directory",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modelsDir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "About") {
            val context = LocalContext.current
            val versionName = remember(context) {
                runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?"
            }
            KeyValue("App version", versionName)
            KeyValue("llama.cpp", "master @ FetchContent")
            KeyValue("MediaPipe genai", "0.10.21")
            KeyValue("NDK", "27.0.12077973")
            KeyValue("Compose BOM", "2024.09.00")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "%.1f %s".format(v, units[i])
}
