package com.mingeek.forge.data.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    private companion object {
        val HF_TOKEN: Preferences.Key<String> = stringPreferencesKey("hf_token")
        val NPU_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("npu_enabled")
        val TEMPERATURE: Preferences.Key<String> = stringPreferencesKey("default_temperature")
        val TOOLS_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("tools_enabled")
        val AGENTS_MODE: Preferences.Key<String> = stringPreferencesKey("agents_mode")
        val AGENTS_PIPELINE_JSON: Preferences.Key<String> = stringPreferencesKey("agents_pipeline_json")
        val AGENTS_ROUTER_JSON: Preferences.Key<String> = stringPreferencesKey("agents_router_json")
        val AGENTS_DEBATE_JSON: Preferences.Key<String> = stringPreferencesKey("agents_debate_json")
    }
}
