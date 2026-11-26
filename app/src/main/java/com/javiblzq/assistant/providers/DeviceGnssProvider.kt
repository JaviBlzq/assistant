package com.javiblzq.assistant.providers

import android.content.Context
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.DeviceLocationFix
import com.javiblzq.assistant.data.HardwareGnssSatellite
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Safely reads hardware GPS/Network location and raw GNSS satellite telemetry
 * via Android 5.0.2 (SDK 21) LocationManager + GpsStatus.Listener.
 */
@Suppress("DEPRECATION")
class DeviceGnssProvider(
    context: Context?,
    private val onTelemetryUpdated: () -> Unit = {}
) {
    private val appContext: Context? = context?.applicationContext
    private val started = AtomicBoolean(false)
    private var locationManager: LocationManager? = null

    @Volatile
    private var latestFix: DeviceLocationFix? = null

    @Volatile
    private var latestSatellites: List<HardwareGnssSatellite> = emptyList()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            acceptLocation(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val gpsStatusListener = GpsStatus.Listener { event ->
        if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS || event == GpsStatus.GPS_EVENT_FIRST_FIX) {
            readGpsStatusSafely()
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val ctx = appContext ?: return
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = lm

            // Seed with last known locations immediately
            try {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { acceptLocation(it, notify = false) }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
            try {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { acceptLocation(it, notify = false) }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }

            // Subscribe to GPS updates
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        GPS_MIN_TIME_MS,
                        GPS_MIN_DIST_M,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "GPS permission missing: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "GPS requestLocationUpdates failed: ${e.message}")
            }

            // Subscribe to Network location as indoor fallback
            try {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        NET_MIN_TIME_MS,
                        NET_MIN_DIST_M,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }

            // Subscribe to raw hardware GNSS satellite status (SDK 21 GpsStatus)
            try {
                lm.addGpsStatusListener(gpsStatusListener)
                readGpsStatusSafely()
            } catch (e: SecurityException) {
                Log.w(TAG, "GpsStatus permission missing: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "addGpsStatusListener failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "DeviceGnssProvider start error: ${e.message}")
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        try {
            lm.removeGpsStatusListener(gpsStatusListener)
        } catch (_: Exception) {
        }
    }

    fun currentLocation(): DeviceLocationFix? = latestFix

    fun currentHardwareSatellites(): List<HardwareGnssSatellite> = latestSatellites

    /**
     * Resolves the observer/origin coordinates: prefers live hardware GPS/Network fix when valid,
     * falling back to config.homeLat / config.homeLon.
     */
    fun resolveOrigin(config: AssistantConfig): ResolvedOrigin {
        return resolveOrigin(config, latestFix)
    }

    /**
     * Returns an AssistantConfig with homeLat/homeLon/homeLabel overridden by the device's live
     * GPS fix when available, for direct use in CommuteProvider.
     */
    fun applyOriginToConfig(config: AssistantConfig): AssistantConfig {
        return applyOriginToConfig(config, latestFix)
    }

    private fun acceptLocation(location: Location, notify: Boolean = true) {
        if (!isValidCoordinates(location.latitude, location.longitude)) return
        val incoming = DeviceLocationFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
            provider = location.provider ?: "",
            timestampMs = if (location.time > 0L) location.time else System.currentTimeMillis()
        )
        val prev = latestFix
        if (shouldReplaceFix(prev, incoming)) {
            latestFix = incoming
            if (notify) onTelemetryUpdated()
        }
    }

    private fun readGpsStatusSafely() {
        val lm = locationManager ?: return
        try {
            val status = lm.getGpsStatus(null) ?: return
            val list = mutableListOf<HardwareGnssSatellite>()
            for (sat in status.satellites) {
                val el = sat.elevation
                val az = sat.azimuth
                if (el.isFinite() && az.isFinite() && (sat.snr > 0f || sat.usedInFix() || el > 0f)) {
                    list.add(
                        HardwareGnssSatellite(
                            prn = sat.prn,
                            azimuthDeg = ((az % 360f) + 360f) % 360f,
                            elevationDeg = el.coerceIn(0f, 90f),
                            snr = sat.snr.coerceAtLeast(0f),
                            usedInFix = sat.usedInFix()
                        )
                    )
                }
            }
            latestSatellites = list
            onTelemetryUpdated()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    data class ResolvedOrigin(
        val lat: Double,
        val lon: Double,
        val label: String,
        val isHardwareFix: Boolean
    )

    companion object {
        private const val TAG = "DeviceGnssProvider"
        private const val GPS_MIN_TIME_MS = 15_000L
        private const val GPS_MIN_DIST_M = 25f
        private const val NET_MIN_TIME_MS = 60_000L
        private const val NET_MIN_DIST_M = 50f

        fun isValidCoordinates(lat: Double, lon: Double): Boolean {
            if (!lat.isFinite() || !lon.isFinite()) return false
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false
            if (kotlin.math.abs(lat) < 1e-5 && kotlin.math.abs(lon) < 1e-5) return false
            return true
        }

        internal fun shouldReplaceFix(prev: DeviceLocationFix?, incoming: DeviceLocationFix): Boolean {
            if (prev == null) return true
            val incomingIsGps = incoming.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)
            val prevIsGps = prev.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)
            if (incomingIsGps && !prevIsGps) return true
            if (!incomingIsGps && prevIsGps && (incoming.timestampMs - prev.timestampMs) < 5 * 60_000L) {
                return false
            }
            return incoming.timestampMs >= prev.timestampMs
        }

        fun resolveOrigin(config: AssistantConfig, fix: DeviceLocationFix?): ResolvedOrigin {
            if (fix != null && isValidCoordinates(fix.lat, fix.lon)) {
                val tag = if (fix.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)) {
                    "GPS"
                } else {
                    "UBICACION"
                }
                return ResolvedOrigin(
                    lat = fix.lat,
                    lon = fix.lon,
                    label = tag,
                    isHardwareFix = true
                )
            }
            return ResolvedOrigin(
                lat = config.homeLat,
                lon = config.homeLon,
                label = config.homeLabel.ifBlank { "CASA" },
                isHardwareFix = false
            )
        }

        fun applyOriginToConfig(config: AssistantConfig, fix: DeviceLocationFix?): AssistantConfig {
            val origin = resolveOrigin(config, fix)
            if (!origin.isHardwareFix) return config
            return config.copy(
                homeLat = origin.lat,
                homeLon = origin.lon,
                homeLabel = origin.label
            )
        }
    }
}
