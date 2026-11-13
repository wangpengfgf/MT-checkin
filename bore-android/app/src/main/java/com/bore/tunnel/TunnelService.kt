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
import java.net.InetAddress
import kotlin.math.min

/**
 * 多通道前台服务：每个通道一个独立的 bore 子进程，独立解析域名、独立重连，
 * 同时持有 WakeLock / WifiLock 和悬浮窗，尽量降低后台掉线概率。
 */
class TunnelService : Service() {

    companion object {
        private const val TAG = "TunnelService"
        const val ACTION_START_ALL = "com.bore.tunnel.action.START_ALL"
        const val ACTION_STOP_ALL = "com.bore.tunnel.action.STOP_ALL"
        const val ACTION_START_ONE = "com.bore.tunnel.action.START_ONE"
        const val ACTION_STOP_ONE = "com.bore.tunnel.action.STOP_ONE"
        const val ACTION_OVERLAY = "com.bore.tunnel.action.OVERLAY"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_OVERLAY = "overlay_enabled"

        private const val CHANNEL_ID = "bore_tunnel"
        private const val NOTIFICATION_ID = 1001
        private const val BINARY_NAME = "libbore.so"

        fun startAll(context: Context, channels: List<Channel>) {
            Prefs.saveChannels(context, channels)
            Prefs.setWantRunning(context, true)
            dispatch(context, Intent(context, TunnelService::class.java).setAction(ACTION_START_ALL))
        }

        fun startAllSaved(context: Context) {
            Prefs.setWantRunning(context, true)
            dispatch(context, Intent(context, TunnelService::class.java).setAction(ACTION_START_ALL))
        }

        fun startOne(context: Context, id: String) {
            Prefs.setWantRunning(context, true)
            dispatch(
                context,
                Intent(context, TunnelService::class.java)
                    .setAction(ACTION_START_ONE)
                    .putExtra(EXTRA_CHANNEL_ID, id)
            )
        }

        fun stopOne(context: Context, id: String) {
            dispatch(
                context,
                Intent(context, TunnelService::class.java)
                    .setAction(ACTION_STOP_ONE)
                    .putExtra(EXTRA_CHANNEL_ID, id)
            )
        }

        fun stopAll(context: Context) {
            dispatch(context, Intent(context, TunnelService::class.java).setAction(ACTION_STOP_ALL))
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

    private class Runtime(var channel: Channel) {
        var process: Process? = null
        var reader: Thread? = null
        var attempts = 0
        var generation = 0
        var restartRunnable: Runnable? = null
        var userStopped = true
    }

    private val main = Handler(Looper.getMainLooper())
    private val ansi = Regex("\u001B\\[[;\\d]*m")
    private val runtimes = LinkedHashMap<String, Runtime>()

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
        TunnelStore.syncFromConfig(Prefs.loadChannels(this))
        TunnelStore.addListener(storeListener)
        registerNetworkCallback()
        if (Prefs.isOverlayEnabled(this)) overlay?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        val channels = Prefs.loadChannels(this)
        TunnelStore.syncFromConfig(channels)

        when (intent?.action) {
            ACTION_START_ALL -> {
                Prefs.setWantRunning(this, true)
                startEnabled(channels)
            }

            ACTION_START_ONE -> {
                val id = intent.getStringExtra(EXTRA_CHANNEL_ID)
                val channel = channels.firstOrNull { it.id == id }
                if (channel != null) {
                    Prefs.setWantRunning(this, true)
                    startChannel(channel, resetAttempts = true)
                    refreshNotification(TunnelStore.state, force = true)
                } else if (runtimes.isEmpty() && !Prefs.isOverlayEnabled(this)) {
                    stopSelfSafely()
                }
            }

            ACTION_STOP_ONE -> {
                intent.getStringExtra(EXTRA_CHANNEL_ID)?.let { stopChannel(it) }
                if (runtimes.isEmpty() && !Prefs.isOverlayEnabled(this)) stopSelfSafely()
            }

            ACTION_STOP_ALL -> stopAllChannels()

            ACTION_OVERLAY -> {
                val enabled = intent.getBooleanExtra(EXTRA_OVERLAY, Prefs.isOverlayEnabled(this))
                Prefs.setOverlayEnabled(this, enabled)
                if (enabled) overlay?.show() else overlay?.hide()
                refreshNotification(TunnelStore.state, force = true)
                if (!enabled && runtimes.isEmpty()) stopSelfSafely()
            }

            null -> {
                when {
                    Prefs.isWantRunning(this) -> startEnabled(channels)
                    Prefs.isOverlayEnabled(this) -> overlay?.show()
                    else -> stopSelfSafely()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        generationStopAll()
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

    // ---------------------------------------------------------------- channels

    private fun startEnabled(channels: List<Channel>) {
        val ids = channels.map { it.id }.toSet()
        runtimes.keys.filter { it !in ids }.toList().forEach {
            stopChannel(it, "已移除")
        }

        channels.forEach { channel ->
            if (channel.enabled) {
                startChannel(channel, resetAttempts = true)
            } else {
                stopChannel(channel.id, "已禁用")
                patchState(
                    channel,
                    status = ChannelStatus.IDLE,
                    message = "已禁用",
                    url = "",
                    startedAt = 0L,
                    attempts = 0
                )
            }
        }
        refreshNotification(TunnelStore.state, force = true)
    }

    private fun startChannel(channel: Channel, resetAttempts: Boolean) {
        val rt = runtimes.getOrPut(channel.id) { Runtime(channel) }
        rt.channel = channel
        if (resetAttempts) rt.attempts = 0
        rt.userStopped = false
        cancelRestart(rt)
        killProcess(rt)
        rt.generation += 1
        val gen = rt.generation

        patchState(
            channel,
            status = ChannelStatus.CONNECTING,
            message = "正在连接…",
            url = "",
            startedAt = 0L,
            attempts = rt.attempts
        )

        // 静态 musl 程序在 Android 上可能读不到 /etc/resolv.conf，
        // 先由系统解析域名，再把 IP 交给 bore（纯 TCP，不需要保留域名）。
        Thread({
            val resolved = resolveServer(channel)
            main.post {
                if (rt.generation != gen || rt.userStopped) return@post
                launch(rt, channel, resolved)
            }
        }, "bore-dns-${channel.id.take(6)}").apply {
            isDaemon = true
            start()
        }
    }

    private fun resolveServer(channel: Channel): String? {
        if (channel.type != ChannelType.LOCAL) return null
        val host = stripPort(channel.server.trim())
        if (host.isEmpty()) return null
        return try {
            val addresses = InetAddress.getAllByName(host)
            val best = addresses.firstOrNull { it.hostAddress?.contains(':') == false }
                ?: addresses.firstOrNull()
            val ip = best?.hostAddress
            if (ip != null && ip != host) {
                TunnelStore.appendLog("[${channel.displayName}] DNS $host → $ip")
            }
            ip
        } catch (t: Throwable) {
            TunnelStore.appendLog("[${channel.displayName}] DNS 解析失败：${t.message ?: "未知错误"}")
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

    private fun launch(rt: Runtime, channel: Channel, resolved: String?) {
        val binary = File(applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            failChannel(rt, channel, "内置 bore 组件缺失")
            return
        }
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
        }

        val actual = if (channel.type == ChannelType.LOCAL && !resolved.isNullOrBlank()) {
            channel.copy(server = resolved)
        } else {
            channel
        }
        TunnelStore.appendLog("[${channel.displayName}] \$ ${channel.displayCommand()}")

        try {
            val command = mutableListOf(binary.absolutePath)
            command.addAll(actual.args())
            val builder = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(filesDir)
            builder.environment()["RUST_LOG"] = "info"
            val process = builder.start()
            rt.process = process
            patchState(
                channel,
                status = ChannelStatus.RUNNING,
                message = "隧道运行中",
                url = "",
                startedAt = System.currentTimeMillis(),
                attempts = rt.attempts
            )
            rt.reader = Thread({ readLoop(rt, channel, process) }, "bore-${channel.id.take(6)}").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            failChannel(rt, channel, "启动失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readLoop(rt: Runtime, channel: Channel, process: Process) {
        try {
            InputStreamReader(process.inputStream).buffered().use { input ->
                while (true) {
                    val raw = input.readLine() ?: break
                    val line = raw.replace(ansi, "").trimEnd()
                    if (line.isBlank()) continue
                    TunnelStore.appendLog("[${channel.displayName}] $line")
                    main.post { handleLine(channel, line) }
                }
            }
        } catch (_: Throwable) {
        }
        val code = try {
            process.waitFor()
        } catch (_: Throwable) {
            -1
        }
        main.post { onProcessExit(rt, channel, process, code) }
    }

    private fun handleLine(channel: Channel, line: String) {
        when {
            line.contains("connected to server") -> patchState(
                channel,
                message = "已连接服务端"
            )

            line.contains("listening at") -> {
                val url = line.substringAfter("listening at").trim()
                patchState(
                    channel,
                    status = ChannelStatus.RUNNING,
                    message = "隧道已建立",
                    url = url
                )
            }

            line.contains("ERROR") || line.contains("invalid secret") ||
                line.contains("permission denied") -> patchState(
                channel,
                message = "服务端返回错误，准备重试"
            )
        }
    }

    private fun onProcessExit(rt: Runtime, channel: Channel, process: Process, code: Int) {
        if (rt.process !== process) return
        rt.process = null

        if (code == 126 || code == 127) {
            rt.userStopped = true
            failChannel(rt, channel, "组件无法执行（code $code），请确认设备为 arm64")
            return
        }

        if (!rt.userStopped && channel.autoRestart) {
            rt.attempts += 1
            val delaySec = min(30, 3 * rt.attempts)
            TunnelStore.appendLog(
                "[${channel.displayName}] ✖ 进程退出 (code $code)，${delaySec}s 后自动重连（第 ${rt.attempts} 次）"
            )
            patchState(
                channel,
                status = ChannelStatus.CONNECTING,
                message = "中断 · ${delaySec}s 后重连",
                url = "",
                startedAt = 0L,
                attempts = rt.attempts
            )
            val runnable = Runnable {
                rt.restartRunnable = null
                if (!rt.userStopped && rt.process == null) {
                    startChannel(channel, resetAttempts = false)
                }
            }
            rt.restartRunnable = runnable
            main.postDelayed(runnable, delaySec * 1000L)
        } else {
            patchState(
                channel,
                status = ChannelStatus.IDLE,
                message = "已停止",
                url = "",
                startedAt = 0L,
                attempts = 0
            )
        }
        refreshNotification(TunnelStore.state, force = true)
    }

    private fun failChannel(rt: Runtime, channel: Channel, message: String) {
        rt.userStopped = true
        rt.process = null
        TunnelStore.appendLog("[${channel.displayName}] ✖ $message")
        patchState(
            channel,
            status = ChannelStatus.ERROR,
            message = message,
            url = "",
            startedAt = 0L,
            attempts = rt.attempts
        )
        refreshNotification(TunnelStore.state, force = true)
    }

    private fun stopChannel(id: String, message: String = "已停止") {
        val rt = runtimes.remove(id) ?: return
        rt.userStopped = true
        rt.generation += 1
        cancelRestart(rt)
        killProcess(rt)
        val channel = rt.channel
        TunnelStore.appendLog("[${channel.displayName}] ■ $message")
        // 通道可能已在界面中被删除，此时不再回写状态，避免出现“幽灵通道”
        if (Prefs.loadChannels(this).any { it.id == id }) {
            patchState(
                channel,
                status = ChannelStatus.IDLE,
                message = message,
                url = "",
                startedAt = 0L,
                attempts = 0
            )
        }
        refreshNotification(TunnelStore.state, force = true)
    }

    private fun stopAllChannels() {
        runtimes.keys.toList().forEach { stopChannel(it) }
        Prefs.setWantRunning(this, false)
        if (!Prefs.isOverlayEnabled(this)) stopSelfSafely()
    }

    private fun generationStopAll() {
        runtimes.values.forEach { rt ->
            rt.userStopped = true
            rt.generation += 1
            cancelRestart(rt)
            killProcess(rt)
        }
        runtimes.clear()
    }

    private fun killProcess(rt: Runtime) {
        val process = rt.process ?: return
        rt.process = null
        try {
            process.destroy()
        } catch (_: Throwable) {
        }
        main.postDelayed({
            try {
                process.destroyForcibly()
            } catch (_: Throwable) {
            }
        }, 800)
    }

    private fun cancelRestart(rt: Runtime) {
        rt.restartRunnable?.let { main.removeCallbacks(it) }
        rt.restartRunnable = null
    }

    private fun patchState(
        channel: Channel,
        status: ChannelStatus? = null,
        message: String? = null,
        url: String? = null,
        startedAt: Long? = null,
        attempts: Int? = null
    ) {
        val current = TunnelStore.state.channels.firstOrNull { it.id == channel.id }
            ?: channel.state()
        TunnelStore.updateChannel(
            current.copy(
                name = channel.displayName,
                summary = channel.summary(),
                status = status ?: current.status,
                message = message ?: current.message,
                url = url ?: current.url,
                startedAt = startedAt ?: current.startedAt,
                attempts = attempts ?: current.attempts
            )
        )
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
                        if (!Prefs.isWantRunning(this@TunnelService)) return@post
                        val channels = Prefs.loadChannels(this@TunnelService)
                        channels.filter { it.enabled }.forEach { channel ->
                            val rt = runtimes[channel.id]
                            if (rt == null || (rt.process == null && !rt.userStopped)) {
                                TunnelStore.appendLog("[${channel.displayName}] 网络恢复，重新连接…")
                                startChannel(channel, resetAttempts = false)
                            }
                        }
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
            description = "保持 bore 多通道隧道在后台运行"
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

    private fun notificationKey(state: TunnelUiState): String = buildString {
        append(Prefs.isOverlayEnabled(this@TunnelService))
        state.channels.forEach {
            append('|').append(it.id).append(it.status).append(it.url).append(it.message)
        }
    }

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
            Intent(this, TunnelService::class.java).setAction(ACTION_STOP_ALL),
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

        val total = state.channels.size
        val running = state.channels.filter { it.status == ChannelStatus.RUNNING }
        val connecting = state.channels.count { it.status == ChannelStatus.CONNECTING }
        val title = when {
            running.isNotEmpty() -> "Bore · ${running.size}/$total 通道在线"
            connecting > 0 -> "Bore · $connecting 个通道连接中"
            else -> "Bore 隧道待机"
        }
        val text = when {
            running.isNotEmpty() -> running.mapNotNull { it.url.ifBlank { null } }
                .ifEmpty { running.map { it.name } }
                .joinToString(" · ")
            else -> "共 $total 个通道 · 点击查看详情"
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
                    "全部停止",
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
