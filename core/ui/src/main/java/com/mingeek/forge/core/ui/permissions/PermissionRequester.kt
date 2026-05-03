package com.mingeek.forge.core.ui.permissions

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Lightweight, reusable runtime permission helper for Compose. Wraps the
 * standard [ActivityResultContracts.RequestPermission] launcher and exposes
 * a [PermissionState] every screen can drive consistently.
 *
 * Why not Accompanist Permissions? It works, but it pulls a separate
 * dependency for what's now a one-launcher boilerplate. Owning this in
 * [:core:ui] keeps every feature using the same flow and lets us layer
 * "permanently denied → settings deeplink" later without bumping a
 * third-party version.
 *
 * Usage:
 * ```
 * val notif = rememberPermissionRequester(android.Manifest.permission.POST_NOTIFICATIONS)
 * Switch(
 *     checked = enabled,
 *     onCheckedChange = { wantOn ->
 *         if (wantOn && !notif.isGranted) notif.request()
 *         else viewModel.set(wantOn)
 *     },
 * )
 * if (notif.justDenied) Text("Permission needed", color = ...)
 * ```
 *
 * Permissions that are implicitly granted (e.g. POST_NOTIFICATIONS on
 * Android 12 and below) report [PermissionState.isGranted] = true and
 * [PermissionState.request] is a no-op. Callers don't need to check the
 * SDK version themselves.
 */
@Composable
fun rememberPermissionRequester(
    permission: String,
    minSdkRequired: Int = Build.VERSION_CODES.TIRAMISU,
): PermissionState {
    val context = LocalContext.current
    val implicitlyGranted = Build.VERSION.SDK_INT < minSdkRequired
    var granted by remember(permission) {
        mutableStateOf(
            implicitlyGranted ||
                ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var justDenied by remember(permission) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        justDenied = !isGranted
    }

    // Re-check permission when the screen recomposes after returning from
    // system settings — the user may have flipped the switch.
    LaunchedEffect(permission) {
        if (!implicitlyGranted) {
            granted = ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    return remember(permission, granted, justDenied) {
        PermissionState(
            permission = permission,
            isGranted = granted,
            justDenied = justDenied,
            request = {
                if (implicitlyGranted) {
                    granted = true
                } else {
                    justDenied = false
                    launcher.launch(permission)
                }
            },
            clearJustDenied = { justDenied = false },
        )
    }
}

class PermissionState internal constructor(
    val permission: String,
    val isGranted: Boolean,
    /** Set after the user denies the most recent request. Reset on next request. */
    val justDenied: Boolean,
    val request: () -> Unit,
    val clearJustDenied: () -> Unit,
)
