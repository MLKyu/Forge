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

    private companion object {
        val HF_TOKEN: Preferences.Key<String> = stringPreferencesKey("hf_token")
        val NPU_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("npu_enabled")
        val TEMPERATURE: Preferences.Key<String> = stringPreferencesKey("default_temperature")
    }
}
