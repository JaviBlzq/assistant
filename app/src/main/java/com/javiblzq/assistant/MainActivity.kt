package com.javiblzq.assistant

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.LocalStore
import com.javiblzq.assistant.power.DisplayMode
import com.javiblzq.assistant.power.PowerStatePolicy
import com.javiblzq.assistant.providers.DeviceGnssProvider
import com.javiblzq.assistant.providers.SyncCoordinator
import com.javiblzq.assistant.providers.TelegramBotService
import com.javiblzq.assistant.server.LocalConfigServer
import com.javiblzq.assistant.ui.DashboardRenderer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Calendar
import java.util.Collections
import java.util.TimeZone

class MainActivity : Activity() {

    private lateinit var store: LocalStore
    private lateinit var deviceGnssProvider: DeviceGnssProvider
    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var renderer: DashboardRenderer
    private var configServer: LocalConfigServer? = null
    private var telegramBotService: TelegramBotService? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastTouchWakeMs: Long = 0L
    private var lastPeriodicSyncMs: Long = 0L
    private var lastSatelliteRecalcMs: Long = 0L
    private var nextCommuteSyncMs: Long = 0L
    private var scheduledTrafficIntervalMinutes: Int = -1
    private var scheduledTrafficTimezone: String = ""
    private var currentMode: DisplayMode? = null
    private var lastBrightnessApplied: Float = -1f

    private val storeListener: () -> Unit = {
        handler.post { refreshUi() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val cfg = store.config
            val trafficInterval = cfg.trafficIntervalMinutes.coerceIn(1, 60)
            if (nextCommuteSyncMs == 0L ||
                scheduledTrafficIntervalMinutes != trafficInterval ||
                scheduledTrafficTimezone != cfg.timezone
            ) {
                scheduleNextCommuteSync(now, cfg)
            }

            val intervalMs = cfg.syncIntervalMinutes * 60_000L
            val fullSyncDue = now - lastPeriodicSyncMs >= intervalMs
            val commuteSyncDue = now >= nextCommuteSyncMs

            if (commuteSyncDue) {
                scheduleNextCommuteSync(now, cfg)
            }

            if (fullSyncDue) {
                lastPeriodicSyncMs = now
                syncCoordinator.triggerSyncAsync()
            } else if (commuteSyncDue) {
                syncCoordinator.triggerCommuteSyncAsync()
            }

            if (now - lastSatelliteRecalcMs >= 5_000L) {
                lastSatelliteRecalcMs = now
                syncCoordinator.refreshSatellitesFast(now)
            }
            refreshUi()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun scheduleNextCommuteSync(now: Long, cfg: AssistantConfig) {
        val interval = cfg.trafficIntervalMinutes.coerceIn(1, 60)
        scheduledTrafficIntervalMinutes = interval
        scheduledTrafficTimezone = cfg.timezone
        nextCommuteSyncMs = nextClockAlignedCommuteSyncMs(now, interval, TimeZone.getTimeZone(cfg.timezone))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowFlags()

        store = (application as AssistantApp).store
        deviceGnssProvider = DeviceGnssProvider(applicationContext) {
            handler.post {
                lastSatelliteRecalcMs = System.currentTimeMillis()
                syncCoordinator.refreshSatellitesFast(lastSatelliteRecalcMs)
            }
        }
        syncCoordinator = SyncCoordinator(store, deviceGnssProvider = deviceGnssProvider)
        deviceGnssProvider.start()

        renderer = DashboardRenderer(
            context = this,
            onRequestWake = { wakeByTouch() },
            onRequestSync = {
                wakeByTouch()
                lastPeriodicSyncMs = System.currentTimeMillis()
                syncCoordinator.triggerSyncAsync()
            },
            onRequestCommuteSync = { target ->
                wakeByTouch()
                val syncTarget = when (target) {
                    DashboardRenderer.CommuteTarget.WORK -> SyncCoordinator.CommuteSyncTarget.WORK
                    DashboardRenderer.CommuteTarget.GYM -> SyncCoordinator.CommuteSyncTarget.GYM
                }
                syncCoordinator.triggerCommuteSyncAsync(syncTarget)
            },
            onToggleTask = { taskId ->
                wakeByTouch()
                store.updateConfig { cfg ->
                    cfg.copy(tasks = cfg.tasks.map { if (it.id == taskId) it.copy(done = !it.done) else it })
                }
            },
            onToggleBulb = { bulbId ->
                wakeByTouch()
                syncCoordinator.toggleSmartBulbAsync(bulbId)
            },
            onTapSatelliteRadar = {
                wakeByTouch()
                lastSatelliteRecalcMs = System.currentTimeMillis()
                syncCoordinator.refreshSatellitesFast(lastSatelliteRecalcMs)
            }
        )
        setContentView(renderer.rootView)

        telegramBotService = TelegramBotService(
            store = store,
            onMessageProcessed = { handler.post { refreshUi() } },
            onCalendarUpdated = { syncCoordinator.triggerSyncAsync() }
        ).also { it.start() }

        configServer = LocalConfigServer(
            context = applicationContext,
            store = store,
            syncCoordinator = syncCoordinator,
            port = PORT,
            onRemoteWake = { handler.post { wakeByTouch() } },
            getLanIp = { resolveLanIp() }
        ).also { it.start() }

        store.addListener(storeListener)
        wakeByTouch()
        val now = System.currentTimeMillis()
        lastSatelliteRecalcMs = now
        syncCoordinator.refreshSatellitesFast(now)
        syncCoordinator.triggerSyncAsync()
        lastPeriodicSyncMs = now
        scheduleNextCommuteSync(now, store.config)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        enterKioskLockTaskIfDeviceOwner()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        store.removeListener(storeListener)
        handler.removeCallbacks(ticker)
        deviceGnssProvider.stop()
        telegramBotService?.stop()
        configServer?.stop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val wasSleeping = currentMode != DisplayMode.FULL_DASHBOARD
            wakeByTouch()
            if (wasSleeping) {
                // Consume the wake tap so sleeping screen does not accidentally trigger a button
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onBackPressed() {
        // Kiosk Home Launcher: swallow back button
    }

    private fun wakeByTouch() {
        lastTouchWakeMs = System.currentTimeMillis()
        refreshUi()
    }

    private fun refreshUi() {
        val cfg = store.config
        val eval = PowerStatePolicy.evaluate(cfg, lastTouchWakeMs)
        currentMode = eval.mode

        applyBrightness(eval.targetBrightness)

        val (batPct, charging) = readBatteryInfo()
        renderer.render(
            mode = eval.mode,
            config = cfg,
            snapshot = store.snapshot,
            batteryPct = batPct,
            isCharging = charging,
            lanIp = resolveLanIp(),
            serverPort = PORT
        )
    }

    private fun setupWindowFlags() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun applyBrightness(value: Float) {
        if (kotlin.math.abs(value - lastBrightnessApplied) < 0.005f) return
        lastBrightnessApplied = value
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun enterKioskLockTaskIfDeviceOwner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            if (dpm.isDeviceOwnerApp(packageName)) {
                val admin = android.content.ComponentName(
                    this,
                    com.javiblzq.assistant.kiosk.AssistantDeviceAdminReceiver::class.java
                )
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                startLockTask()
            }
        } catch (_: Exception) {
        }
    }

    private fun readBatteryInfo(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun resolveLanIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    companion object {
        const val PORT = 8080

        fun nextClockAlignedCommuteSyncMs(
            nowMs: Long,
            intervalMinutes: Int = 10,
            timeZone: TimeZone = TimeZone.getDefault()
        ): Long {
            val stepMin = intervalMinutes.coerceIn(1, 60)
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMs
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val currentMinute = cal.get(Calendar.MINUTE)
            val nextMinute = ((currentMinute / stepMin) + 1) * stepMin
            if (nextMinute < 60) {
                cal.set(Calendar.MINUTE, nextMinute)
            } else {
                cal.set(Calendar.MINUTE, 0)
                cal.add(Calendar.HOUR_OF_DAY, 1)
            }
            return cal.timeInMillis
        }
    }
}
