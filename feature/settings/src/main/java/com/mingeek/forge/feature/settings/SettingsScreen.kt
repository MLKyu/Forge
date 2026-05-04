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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.mingeek.forge.core.ui.permissions.rememberPermissionRequester
import com.mingeek.forge.feature.settings.R
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
        SectionCard(title = stringResource(R.string.settings_section_huggingface)) {
            val hfContext = LocalContext.current
            Text(
                stringResource(R.string.settings_huggingface_optional_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = {
                    tokenDraft = it
                    viewModel.onTokenChanged(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_access_token)) },
                placeholder = { Text("hf_…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                if (state.tokenSet) stringResource(R.string.settings_token_set) else stringResource(R.string.settings_token_missing),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.tokenSet) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (state.tokenSet) FontWeight.Medium else FontWeight.Normal,
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
            ) { Text(stringResource(R.string.settings_get_a_token)) }
        }

        SectionCard(title = stringResource(R.string.settings_section_inference_defaults)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_use_npu), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_use_npu_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.npuEnabled, onCheckedChange = viewModel::onNpuChanged)
            }
            HorizontalDivider()
            Text(stringResource(R.string.settings_default_temperature, state.temperature), fontWeight = FontWeight.Medium)
            Slider(
                value = state.temperature,
                onValueChange = viewModel::onTemperatureChanged,
                valueRange = 0f..1.5f,
                steps = 14,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_enable_tools), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_enable_tools_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.toolsEnabled, onCheckedChange = viewModel::onToolsEnabledChanged)
            }
            if (state.toolsEnabled) {
                Text(
                    stringResource(R.string.settings_tool_max_iterations, state.toolMaxIterations),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.settings_tool_max_iterations_desc),
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
                    Text(stringResource(R.string.settings_auto_cleanup), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_auto_cleanup_desc),
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
                    stringResource(R.string.settings_budget_gb, state.autoCleanupBudgetGb),
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
            val notifPermission = rememberPermissionRequester(
                permission = android.Manifest.permission.POST_NOTIFICATIONS,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_discovery_notifications), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_discovery_notifications_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.discoveryNotificationsEnabled && notifPermission.isGranted,
                    onCheckedChange = { wantOn ->
                        if (wantOn && !notifPermission.isGranted) {
                            notifPermission.request()
                            // Persist the intent to enable; once permission is
                            // granted on the next composition, the worker
                            // observer will pick it up.
                            viewModel.onDiscoveryNotificationsChanged(true)
                        } else {
                            viewModel.onDiscoveryNotificationsChanged(wantOn)
                        }
                    },
                )
            }
            if (state.discoveryNotificationsEnabled && !notifPermission.isGranted) {
                Text(
                    if (notifPermission.justDenied)
                        stringResource(R.string.settings_notification_permission_denied)
                    else
                        stringResource(R.string.settings_notification_permission_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.discoveryNotificationsEnabled) {
                Text(
                    stringResource(R.string.settings_poll_every_hours, state.discoveryNotificationsIntervalHours),
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = state.discoveryNotificationsIntervalHours.toFloat(),
                    onValueChange = { viewModel.onDiscoveryNotificationsIntervalChanged(it.toInt()) },
                    valueRange = 1f..24f,
                    steps = 22,
                )
            }
        }

        SectionCard(title = stringResource(R.string.settings_section_device)) {
            KeyValue(stringResource(R.string.settings_device_model), state.deviceProfile.deviceModel)
            KeyValue(stringResource(R.string.settings_device_soc), "${state.deviceProfile.socManufacturer ?: "?"} / ${state.deviceProfile.socModel ?: "?"}")
            KeyValue(stringResource(R.string.settings_device_cpu_cores), state.deviceProfile.cpuCoreCount.toString())
            KeyValue(stringResource(R.string.settings_device_total_ram), formatBytes(state.deviceProfile.totalRamBytes))
            KeyValue(stringResource(R.string.settings_device_available_ram), formatBytes(state.deviceProfile.availableRamBytes))
            KeyValue(stringResource(R.string.settings_device_accelerators), state.deviceProfile.accelerators.joinToString { it.name })
        }

        SectionCard(title = stringResource(R.string.settings_section_storage)) {
            val storageContext = LocalContext.current
            val modelsDir = remember(storageContext) {
                java.io.File(storageContext.filesDir, "models").absolutePath
            }
            KeyValue(stringResource(R.string.settings_storage_installed_models), state.storage.installedCount.toString())
            KeyValue(stringResource(R.string.settings_storage_used_by_models), formatBytes(state.storage.totalBytes))
            KeyValue(stringResource(R.string.settings_storage_free_disk), formatBytes(state.storage.freeBytes))
            Text(
                stringResource(R.string.settings_storage_models_directory),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modelsDir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.settings_section_about)) {
            val context = LocalContext.current
            val versionName = remember(context) {
                runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?"
            }
            KeyValue(stringResource(R.string.settings_about_app_version), versionName)
            KeyValue(stringResource(R.string.settings_about_llama_cpp), "master @ FetchContent")
            KeyValue(stringResource(R.string.settings_about_mediapipe_genai), "0.10.21")
            KeyValue(stringResource(R.string.settings_about_ndk), "27.0.12077973")
            KeyValue(stringResource(R.string.settings_about_compose_bom), "2024.09.00")
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.padding(end = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
        )
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
