package com.bore.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.InputStreamReader
import kotlin.math.min

/**
 * 前台服务：持有 bore 子进程、唤醒锁、Wi-Fi 锁和悬浮窗，
 * 进程异常退出时自动重连，尽量降低息屏/后台被回收导致的掉线。
 */
class TunnelService : Service() {

    companion object {
        private const val TAG = "TunnelService"
        const val ACTION_START = "com.bore.tunnel.action.START"
        const val ACTION_START_SAVED = "com.bore.tunnel.action.START_SAVED"
        const val ACTION_STOP = "com.bore.tunnel.action.STOP"
        const val ACTION_OVERLAY = "com.bore.tunnel.action.OVERLAY"
        const val EXTRA_OVERLAY = "overlay_enabled"

        private const val CHANNEL_ID = "bore_tunnel"
        private const val NOTIFICATION_ID = 1001
        private const val BINARY_NAME = "libbore.so"

        fun start(context: Context, config: BoreConfig) {
            Prefs.save(context, config)
            Prefs.setWantRunning(context, true)
            dispatch(context, Intent(context, TunnelService::class.java).setAction(ACTION_START))
        }

        fun startSaved(context: Context) {
            dispatch(context, Intent(context, TunnelService::class.java).setAction(ACTION_START_SAVED))
        }

        fun stop(context: Context) {
            dispatch(context, Intent(context, TunnelService::class.java).setAction(ACTION_STOP))
        }

        fun setOverlay(context: Context, enabled: Boolean) {
            Prefs.setOverlayEnabled(context, enabled)
            dispatch(
                context,
                Intent(context, TunnelService::class.java)
                    .setAction(ACTION_OVERLAY)
                    .putExtra(EXTRA_OVERLAY, enabled)
            )
        }

        private fun dispatch(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to start service", t)
                TunnelStore.appendLog("无法启动后台服务：${t.message}")
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val ansi = Regex("\u001B\\[[;\\d]*m")

    private var process: Process? = null
    private var reader: Thread? = null
    private var currentConfig: BoreConfig? = null
    private var userStopped = true
    private var attempts = 0
    private var restartRunnable: Runnable? = null
    private var generation = 0
    private var isForeground = false
    private var lastNotificationKey = ""

    private var overlay: OverlayController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val storeListener: (TunnelUiState, List<String>) -> Unit = { state, _ ->
        refreshNotification(state)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        overlay = OverlayController(this)
        TunnelStore.addListener(storeListener)
        registerNetworkCallback()
        if (Prefs.isOverlayEnabled(this)) overlay?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        when (intent?.action) {
            ACTION_START -> {
                val config = Prefs.load(this)
                startTunnel(config)
            }

            ACTION_START_SAVED -> startTunnel(Prefs.load(this))

            ACTION_STOP -> stopTunnel()

            ACTION_OVERLAY -> {
                val enabled = intent.getBooleanExtra(EXTRA_OVERLAY, Prefs.isOverlayEnabled(this))
                Prefs.setOverlayEnabled(this, enabled)
                if (enabled) overlay?.show() else overlay?.hide()
                refreshNotification(TunnelStore.state, force = true)
                if (!enabled && !TunnelStore.state.running) stopSelfSafely()
            }

            null -> {
                if (Prefs.isWantRunning(this)) {
                    TunnelStore.appendLog("服务被系统重建，恢复上次隧道…")
                    startTunnel(Prefs.load(this))
                } else if (Prefs.isOverlayEnabled(this)) {
                    overlay?.show()
                } else {
                    stopSelfSafely()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉时不结束服务，由 START_STICKY 继续守护
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        userStopped = true
        generation += 1
        cancelRestart()
        killProcess()
        try {
            overlay?.hide()
        } catch (_: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        try {
            networkCallback?.let {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        } catch (_: Throwable) {
        }
        TunnelStore.removeListener(storeListener)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- process

    private fun startTunnel(config: BoreConfig) {
        Prefs.save(this, config)
        Prefs.setWantRunning(this, true)
        currentConfig = config
        userStopped = false
        attempts = 0
        generation += 1
        val gen = generation
        cancelRestart()
        killProcess()

        val binary = File(applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            fail("内置 bore 组件缺失：${binary.absolutePath}")
            return
        }
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
        }

        TunnelStore.setState(
            TunnelStore.state.copy(
                running = false,
                connecting = true,
                message = "正在连接…",
                startedAt = 0L,
                attempts = 0,
                configSummary = config.summary(),
                tunnelUrl = ""
            )
        )

        // 静态 musl 可执行文件在 Android 上可能无法读取 /etc/resolv.conf，
        // 先由系统解析域名，再把 IP 交给 bore，避免 DNS 失败。
        Thread({
            val resolved = resolveServer(config)
            main.post {
                if (gen != generation || userStopped) return@post
                launchProcess(binary, config, resolved)
            }
        }, "bore-dns").apply {
            isDaemon = true
            start()
        }
    }

    private fun resolveServer(config: BoreConfig): String? {
        if (config.mode != Mode.LOCAL) return null
        val host = stripPort(config.server.trim())
        if (host.isEmpty()) return null
        return try {
            val addresses = java.net.InetAddress.getAllByName(host)
            val best = addresses.firstOrNull { !it.hostAddress.contains(':') } ?: addresses.firstOrNull()
            val ip = best?.hostAddress
            if (ip != null && ip != host) {
                TunnelStore.appendLog("DNS $host → $ip")
            }
            ip
        } catch (t: Throwable) {
            TunnelStore.appendLog("DNS 解析失败：${t.message ?: "未知错误"}")
            null
        }
    }

    private fun stripPort(server: String): String {
        if (server.startsWith("[") && server.contains("]")) {
            return server.substringAfter("[").substringBefore("]")
        }
        val colonCount = server.count { it == ':' }
        if (colonCount == 1) {
            val port = server.substringAfter(":")
            if (port.isNotEmpty() && port.all { it.isDigit() }) {
                return server.substringBefore(":")
            }
        }
        return server
    }

    private fun launchProcess(binary: File, config: BoreConfig, resolvedServer: String?) {
        val actualConfig = if (config.mode == Mode.LOCAL && !resolvedServer.isNullOrBlank()) {
            config.copy(server = resolvedServer)
        } else {
            config
        }
        TunnelStore.appendLog("$ ${config.displayCommand()}")

        try {
            val command = mutableListOf(binary.absolutePath)
            command.addAll(actualConfig.args())
            val builder = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(filesDir)
            builder.environment()["RUST_LOG"] = "info"
            val p = builder.start()
            process = p
            TunnelStore.setState(
                TunnelStore.state.copy(
                    running = true,
                    connecting = false,
                    message = "隧道运行中",
                    startedAt = System.currentTimeMillis(),
                    configSummary = config.summary()
                )
            )
            reader = Thread({ readLoop(p) }, "bore-reader").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            fail("启动失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readLoop(p: Process) {
        try {
            InputStreamReader(p.inputStream).buffered().use { input ->
                while (true) {
                    val raw = input.readLine() ?: break
                    val line = raw.replace(ansi, "").trimEnd()
                    if (line.isBlank()) continue
                    TunnelStore.appendLog(line)
                    handleLine(line)
                }
            }
        } catch (_: Throwable) {
        }
        val code = try {
            p.waitFor()
        } catch (_: Throwable) {
            -1
        }
        main.post { onProcessExit(p, code) }
    }

    private fun handleLine(line: String) {
        when {
            line.contains("connected to server") -> {
                if (TunnelStore.state.message != "隧道已建立") {
                    TunnelStore.setState(
                        TunnelStore.state.copy(message = "已连接服务端", connecting = true, attempts = 0)
                    )
                }
            }

            line.contains("listening at") -> {
                val url = line.substringAfter("listening at").trim()
                TunnelStore.setState(
                    TunnelStore.state.copy(
                        running = true,
                        connecting = false,
                        message = "隧道已建立",
                        tunnelUrl = url
                    )
                )
            }

            line.contains("ERROR") || line.contains("invalid secret") ||
                line.contains("permission denied") -> {
                TunnelStore.setState(TunnelStore.state.copy(message = "服务端返回错误，准备重试"))
            }
        }
    }

    private fun onProcessExit(p: Process, code: Int) {
        if (process !== p) return
        process = null
        val config = currentConfig

        if (code == 126 || code == 127) {
            userStopped = true
            Prefs.setWantRunning(this, false)
            TunnelStore.appendLog("✖ 无法执行内置 bore（code $code），请确认设备为 arm64 架构")
            TunnelStore.setState(
                TunnelStore.state.copy(
                    running = false,
                    connecting = false,
                    message = "组件无法执行（code $code）",
                    startedAt = 0L
                )
            )
            refreshNotification(TunnelStore.state, force = true)
            return
        }

        if (!userStopped && config?.autoRestart == true) {
            attempts += 1
            val delaySec = min(30, 3 * attempts)
            TunnelStore.appendLog("✖ bore 进程退出 (code $code)，${delaySec}s 后自动重连（第 $attempts 次）")
            TunnelStore.setState(
                TunnelStore.state.copy(
                    running = false,
                    connecting = true,
                    message = "连接中断 · ${delaySec}s 后自动重连",
                    startedAt = 0L,
                    attempts = attempts,
                    tunnelUrl = ""
                )
            )
            scheduleRestart(delaySec)
        } else {
            TunnelStore.setState(
                TunnelStore.state.copy(
                    running = false,
                    connecting = false,
                    message = "已停止",
                    startedAt = 0L,
                    attempts = 0,
                    tunnelUrl = ""
                )
            )
        }
        refreshNotification(TunnelStore.state, force = true)
    }

    private fun scheduleRestart(delaySec: Int) {
        cancelRestart()
        val runnable = Runnable {
            restartRunnable = null
            val config = currentConfig
            if (!userStopped && Prefs.isWantRunning(this) && config != null) {
                startTunnel(config)
            }
        }
        restartRunnable = runnable
        main.postDelayed(runnable, delaySec * 1000L)
    }

    private fun cancelRestart() {
        restartRunnable?.let { main.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun stopTunnel() {
        userStopped = true
        generation += 1
        cancelRestart()
        killProcess()
        Prefs.setWantRunning(this, false)
        TunnelStore.appendLog("■ 隧道已停止")
        TunnelStore.setState(
            TunnelStore.state.copy(
                running = false,
                connecting = false,
                message = "已停止",
                startedAt = 0L,
                attempts = 0,
                tunnelUrl = ""
            )
        )
        refreshNotification(TunnelStore.state, force = true)
        if (!Prefs.isOverlayEnabled(this)) stopSelfSafely()
    }

    private fun killProcess() {
        val p = process ?: return
        process = null
        try {
            p.destroy()
        } catch (_: Throwable) {
        }
        main.postDelayed({
            try {
                p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }, 800)
    }

    private fun fail(message: String) {
        userStopped = true
        Prefs.setWantRunning(this, false)
        TunnelStore.appendLog("✖ $message")
        TunnelStore.setState(
            TunnelStore.state.copy(
                running = false,
                connecting = false,
                message = message,
                startedAt = 0L
            )
        )
        refreshNotification(TunnelStore.state, force = true)
    }

    // -------------------------------------------------------------- keep alive

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "bore:tunnel"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
        }
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "bore:wifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        if (userStopped || !Prefs.isWantRunning(this@TunnelService)) return@post
                        if (process != null) return@post
                        val config = currentConfig ?: return@post
                        if (restartRunnable != null) {
                            cancelRestart()
                            TunnelStore.appendLog("网络已恢复，立即重连…")
                        }
                        startTunnel(config)
                    }
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bore 隧道",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 bore 隧道在后台运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground() {
        try {
            val notification = buildNotification(TunnelStore.state)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            lastNotificationKey = notificationKey(TunnelStore.state)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun refreshNotification(state: TunnelUiState, force: Boolean = false) {
        if (!isForeground) return
        val key = notificationKey(state)
        if (!force && key == lastNotificationKey) return
        lastNotificationKey = key
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: Throwable) {
        }
    }

    private fun notificationKey(state: TunnelUiState) = listOf(
        state.running,
        state.connecting,
        state.message,
        state.configSummary,
        state.tunnelUrl,
        Prefs.isOverlayEnabled(this)
    ).joinToString("|")

    private fun buildNotification(state: TunnelUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val overlayEnabled = Prefs.isOverlayEnabled(this)
        val overlayIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TunnelService::class.java)
                .setAction(ACTION_OVERLAY)
                .putExtra(EXTRA_OVERLAY, !overlayEnabled),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            state.running -> "Bore 隧道运行中"
            state.connecting -> "Bore 正在连接"
            else -> "Bore 隧道待机"
        }
        val text = when {
            state.tunnelUrl.isNotBlank() -> state.tunnelUrl
            else -> "${state.message} · ${state.configSummary}"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    "停止",
                    stopIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    if (overlayEnabled) "隐藏悬浮窗" else "显示悬浮窗",
                    overlayIntent
                ).build()
            )
            .build()
    }

    private fun stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
        isForeground = false
        stopSelf()
    }
}
