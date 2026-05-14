package com.mingeek.forge.remoteconfig

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

internal object RemoteConfigBootstrap {

    private const val TAG = "ForgeRemoteConfig"

    // Placeholder keys — replace / extend with real parameters as the app grows.
    // Adding a default here lets the SDK return a sensible value before the
    // first fetch lands, so the UI never has to special-case "no config yet".
    private val DEFAULTS: Map<String, Any> = mapOf(
        "chat_default_temperature" to 0.7,
        "feature_npu_enabled" to false,
        "feature_mlc_enabled" to false,
    )

    fun init(context: Context) {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val config = FirebaseRemoteConfig.getInstance()
        // 0s in dev so console edits show up instantly; 1h in release.
        val settings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(if (isDebuggable) 0 else 3_600)
            .build()
        // Chain so fetchAndActivate sees the new fetch interval and defaults
        // — kicking all three off in parallel races and can throttle the first
        // fetch against the SDK's default 12h interval.
        config.setConfigSettingsAsync(settings)
            .continueWithTask { config.setDefaultsAsync(DEFAULTS) }
            .continueWithTask { config.fetchAndActivate() }
            .addOnFailureListener { Log.w(TAG, "Remote Config init failed", it) }
    }
}
