package com.mercury.yktwx.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.mercury.yktwx.HellobikeViewModel

@Composable
internal fun HellobikeLocationPermissionEffect(requestId: Long?, enabled: Boolean, model: HellobikeViewModel) {
    var pendingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val id = pendingId
        pendingId = null
        if (id != null) model.locationPermissionReturned(id, result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }
    LaunchedEffect(requestId, enabled) {
        if (requestId != null && enabled && model.claimLocationPermission(requestId)) {
            pendingId = requestId
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
