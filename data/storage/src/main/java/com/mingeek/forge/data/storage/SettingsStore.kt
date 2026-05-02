package com.mingeek.forge.data.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.workbenchSettings by preferencesDataStore(name = "forge_settings")

class SettingsStore(context: Context) {

    private val ds = context.workbenchSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val hfToken: StateFlow<String?> = ds.data
        .map { prefs -> prefs[HF_TOKEN]?.takeIf { it.isNotBlank() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val npuEnabled: StateFlow<Boolean> = ds.data
        .map { it[NPU_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val defaultTemperature: StateFlow<Float> = ds.data
        .map { it[TEMPERATURE]?.toFloatOrNull() ?: 0.7f }
        .stateIn(scope, SharingStarted.Eagerly, 0.7f)

    val toolsEnabled: StateFlow<Boolean> = ds.data
        .map { it[TOOLS_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val toolMaxIterations: StateFlow<Int> = ds.data
        .map { (it[TOOL_MAX_ITERATIONS] ?: DEFAULT_TOOL_MAX_ITERATIONS).coerceIn(1, 10) }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_TOOL_MAX_ITERATIONS)

    val agentsMode: StateFlow<String?> = ds.data
        .map { it[AGENTS_MODE] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val agentsPipelineJson: StateFlow<String?> = ds.data
        .map { it[AGENTS_PIPELINE_JSON] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val agentsRouterJson: StateFlow<String?> = ds.data
        .map { it[AGENTS_ROUTER_JSON] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val agentsDebateJson: StateFlow<String?> = ds.data
        .map { it[AGENTS_DEBATE_JSON] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val pinnedModelIds: StateFlow<Set<String>> = ds.data
        .map { it[PINNED_MODEL_IDS] ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val autoCleanupEnabled: StateFlow<Boolean> = ds.data
        .map { it[AUTO_CLEANUP_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Cleanup budget in gigabytes (1..50). */
    val autoCleanupBudgetGb: StateFlow<Int> = ds.data
        .map { (it[AUTO_CLEANUP_BUDGET_GB] ?: 5).coerceIn(1, 50) }
        .stateIn(scope, SharingStarted.Eagerly, 5)

    val discoveryNotificationsEnabled: StateFlow<Boolean> = ds.data
        .map { it[DISCOVERY_NOTIFICATIONS_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Set of DiscoveredModel.card.id values we've already surfaced as a notification. */
    val seenDiscoveryIds: StateFlow<Set<String>> = ds.data
        .map { it[SEEN_DISCOVERY_IDS] ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun setHfToken(token: String?) {
        ds.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(HF_TOKEN) else prefs[HF_TOKEN] = token
        }
    }

    suspend fun setNpuEnabled(enabled: Boolean) {
        ds.edit { it[NPU_ENABLED] = enabled }
    }

    suspend fun setDefaultTemperature(value: Float) {
        ds.edit { it[TEMPERATURE] = value.toString() }
    }

    suspend fun setToolsEnabled(enabled: Boolean) {
        ds.edit { it[TOOLS_ENABLED] = enabled }
    }

    suspend fun setToolMaxIterations(value: Int) {
        ds.edit { it[TOOL_MAX_ITERATIONS] = value.coerceIn(1, 10) }
    }

    suspend fun setAgentsMode(mode: String?) {
        ds.edit { if (mode == null) it.remove(AGENTS_MODE) else it[AGENTS_MODE] = mode }
    }

    suspend fun setAgentsPipelineJson(json: String?) {
        ds.edit { if (json == null) it.remove(AGENTS_PIPELINE_JSON) else it[AGENTS_PIPELINE_JSON] = json }
    }

    suspend fun setAgentsRouterJson(json: String?) {
        ds.edit { if (json == null) it.remove(AGENTS_ROUTER_JSON) else it[AGENTS_ROUTER_JSON] = json }
    }

    suspend fun setAgentsDebateJson(json: String?) {
        ds.edit { if (json == null) it.remove(AGENTS_DEBATE_JSON) else it[AGENTS_DEBATE_JSON] = json }
    }

    suspend fun togglePinnedModel(id: String) {
        ds.edit { prefs ->
            val current = prefs[PINNED_MODEL_IDS] ?: emptySet()
            prefs[PINNED_MODEL_IDS] = if (id in current) current - id else current + id
        }
    }

    suspend fun unpinModel(id: String) {
        ds.edit { prefs ->
            val current = prefs[PINNED_MODEL_IDS] ?: return@edit
            if (id in current) prefs[PINNED_MODEL_IDS] = current - id
        }
    }

    suspend fun setAutoCleanupEnabled(enabled: Boolean) {
        ds.edit { it[AUTO_CLEANUP_ENABLED] = enabled }
    }

    suspend fun setAutoCleanupBudgetGb(value: Int) {
        ds.edit { it[AUTO_CLEANUP_BUDGET_GB] = value.coerceIn(1, 50) }
    }

    suspend fun setDiscoveryNotificationsEnabled(enabled: Boolean) {
        ds.edit { it[DISCOVERY_NOTIFICATIONS_ENABLED] = enabled }
    }

    /** Cap to avoid the set growing unboundedly — drops oldest seen ids. */
    suspend fun markDiscoveryIdsSeen(ids: Set<String>, retain: Int = 500) {
        ds.edit { prefs ->
            val current = prefs[SEEN_DISCOVERY_IDS] ?: emptySet()
            val merged = (current + ids).toList()
            prefs[SEEN_DISCOVERY_IDS] = if (merged.size <= retain) merged.toSet()
            else merged.takeLast(retain).toSet()
        }
    }

    private companion object {
        const val DEFAULT_TOOL_MAX_ITERATIONS = 4
        val HF_TOKEN: Preferences.Key<String> = stringPreferencesKey("hf_token")
        val NPU_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("npu_enabled")
        val TEMPERATURE: Preferences.Key<String> = stringPreferencesKey("default_temperature")
        val TOOLS_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("tools_enabled")
        val TOOL_MAX_ITERATIONS: Preferences.Key<Int> = intPreferencesKey("tool_max_iterations")
        val AGENTS_MODE: Preferences.Key<String> = stringPreferencesKey("agents_mode")
        val AGENTS_PIPELINE_JSON: Preferences.Key<String> = stringPreferencesKey("agents_pipeline_json")
        val AGENTS_ROUTER_JSON: Preferences.Key<String> = stringPreferencesKey("agents_router_json")
        val AGENTS_DEBATE_JSON: Preferences.Key<String> = stringPreferencesKey("agents_debate_json")
        val PINNED_MODEL_IDS: Preferences.Key<Set<String>> = stringSetPreferencesKey("pinned_model_ids")
        val AUTO_CLEANUP_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("auto_cleanup_enabled")
        val AUTO_CLEANUP_BUDGET_GB: Preferences.Key<Int> = intPreferencesKey("auto_cleanup_budget_gb")
        val DISCOVERY_NOTIFICATIONS_ENABLED: Preferences.Key<Boolean> =
            booleanPreferencesKey("discovery_notifications_enabled")
        val SEEN_DISCOVERY_IDS: Preferences.Key<Set<String>> =
            stringSetPreferencesKey("seen_discovery_ids")
    }
}
