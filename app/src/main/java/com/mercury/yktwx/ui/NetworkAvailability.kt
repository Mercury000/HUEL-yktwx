package com.mercury.yktwx.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import com.mercury.yktwx.ui.components.FeedbackText

/** Block only a known offline entry. Connected networks can still fail at the service. */
internal fun allowOnlineEntry(context: Context, preview: Boolean = false): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity?.activeNetwork
    val offline = !preview && connectivity != null &&
        (network == null || connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == false)
    if (offline) Toast.makeText(context, FeedbackText.Offline, Toast.LENGTH_SHORT).show()
    return !offline
}
