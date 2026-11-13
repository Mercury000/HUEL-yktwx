package com.mercury.yktwx.data.life

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlin.coroutines.resume
import kotlin.math.*

/** Foreground-only Android location. The service decides precision and operating-area requirements. */
class HellobikeLocation(context: Context) : HellobikeLocationService {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    override suspend fun current(): HellobikePosition {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw HellobikeLocationPermissionRequired()
        val providers = manager.getProviders(true).filter { it in setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER) }
        if (providers.isEmpty()) throw HellobikeException("请打开手机定位后重试")
        return withTimeoutOrNull(20_000) {
            supervisorScope {
                val candidates = providers.map { provider -> async {
                    try { current(provider)?.validated() } catch (_: Exception) { null }
                } }.toMutableList()
                try {
                    while (candidates.isNotEmpty()) {
                        val (task, location) = select<Pair<Deferred<HellobikePosition?>, HellobikePosition?>> {
                            candidates.forEach { task -> task.onAwait { task to it } }
                        }
                        candidates.remove(task)
                        if (location != null) return@supervisorScope location
                    }
                    null
                } finally { candidates.forEach { it.cancel() } }
            }
        } ?: throw HellobikeException("手机未返回位置或获取位置超时")
    }
    private suspend fun current(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            manager.getCurrentLocation(provider, cancellation, app.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        } catch (e: Exception) { continuation.cancel(e) }
    }
    private fun Location.validated(): HellobikePosition? {
        val point = HellobikePosition(latitude, longitude, if (hasAccuracy()) accuracy else 0f, elapsedRealtimeNanos / 1_000_000)
        if (!point.fresh(SystemClock.elapsedRealtime())) return null
        val (lat, lng) = HellobikeCoordinates.toGcj02(latitude, longitude)
        return point.copy(lat = lat, lng = lng)
    }
}

internal object HellobikeCoordinates {
    /** Same WGS84 -> GCJ-02 calculation as the prototype and public official H5. */
    fun toGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (lng !in 72.004..137.8347 || lat !in .8293..55.8271) return lat to lng
        val x = lng - 105; val y = lat - 35
        var dLat = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * sqrt(abs(x))
        dLat += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLat += (20 * sin(y * PI) + 40 * sin(y / 3 * PI)) * 2 / 3
        dLat += (160 * sin(y / 12 * PI) + 320 * sin(y * PI / 30)) * 2 / 3
        var dLng = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * sqrt(abs(x))
        dLng += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLng += (20 * sin(x * PI) + 40 * sin(x / 3 * PI)) * 2 / 3
        dLng += (150 * sin(x / 12 * PI) + 300 * sin(x / 30 * PI)) * 2 / 3
        val rad = lat / 180 * PI; val ee = .00669342162296594323; val a = 6378245.0
        val magic = 1 - ee * sin(rad).pow(2); val root = sqrt(magic)
        return lat + dLat * 180 / (a * (1 - ee) / (magic * root) * PI) to
            lng + dLng * 180 / (a / root * cos(rad) * PI)
    }
    fun distance(a: HellobikePosition, b: HellobikePosition): Double {
        val lat = (b.lat - a.lat) * PI / 180; val lng = (b.lng - a.lng) * PI / 180
        val h = sin(lat / 2).pow(2) + cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(lng / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
