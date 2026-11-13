package com.mercury.yktwx.ui.components

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mercury.yktwx.HellobikeViewModel

@Composable
internal fun HellobikeLocationPermissionEffect(requestId: Long?, enabled: Boolean, model: HellobikeViewModel) {
    val context = LocalContext.current
    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun granted() = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun blockedAfterDenial(): Boolean {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() ?: return false
        return permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }
    var pendingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var resultGranted by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var resultBlocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        resultGranted = permissions.any { result[it] == true }
        resultBlocked = resultGranted == false && blockedAfterDenial()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resultGranted = granted()
        // Settings can select "ask every time" without granting permission now.
        resultBlocked = false
    }
    LaunchedEffect(enabled, resultGranted) {
        val id = pendingId
        val permissionGranted = resultGranted
        if (enabled && id != null && permissionGranted != null) {
            pendingId = null; resultGranted = null
            model.locationPermissionReturned(id, permissionGranted, resultBlocked)
        }
    }
    LaunchedEffect(requestId, enabled, pendingId, resultGranted) {
        // A cancelled logical request can be replaced while its native dialog is still open.
        // Drain that dialog's result before assigning a new request to the same launcher.
        if (requestId != null && enabled && pendingId == null && resultGranted == null && model.claimLocationPermission(requestId)) {
            pendingId = requestId
            if (granted()) { resultGranted = true; resultBlocked = false }
            else if (model.locationSettingsOpening(requestId)) {
                settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            } else {
                launcher.launch(permissions)
            }
        }
    }
}
