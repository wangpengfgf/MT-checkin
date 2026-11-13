package com.bore.tunnel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.LOCAL
    private var pendingOverlayRequest = false
    private var updatingSwitch = false
    private var lastLogCount = -1

    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var tvCmd: TextView
    private lateinit var btnStart: TextView
    private lateinit var btnStop: TextView
    private lateinit var segLocal: TextView
    private lateinit var segServer: TextView
    private lateinit var groupLocal: View
    private lateinit var groupServer: View
    private lateinit var etServer: EditText
    private lateinit var etLocalPort: EditText
    private lateinit var etLocalHost: EditText
    private lateinit var etRemotePort: EditText
    private lateinit var etBindAddr: EditText
    private lateinit var etMinPort: EditText
    private lateinit var etMaxPort: EditText
    private lateinit var etBindTunnels: EditText
    private lateinit var etSecret: EditText
    private lateinit var switchAutoRestart: Switch
    private lateinit var switchOverlay: Switch

    private val uiListener: (TunnelUiState, List<String>) -> Unit = { state, logs ->
        render(state, logs)
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderUptime(TunnelStore.state)
            handler.postDelayed(this, 1000L)
        }
    }

    private val previewWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            updatePreview()
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadConfig(Prefs.load(this))

        segLocal.setOnClickListener { selectMode(Mode.LOCAL) }
        segServer.setOnClickListener { selectMode(Mode.SERVER) }

        listOf(
            etServer, etLocalPort, etLocalHost, etRemotePort,
            etBindAddr, etMinPort, etMaxPort, etBindTunnels, etSecret
        ).forEach { it.addTextChangedListener(previewWatcher) }

        btnStart.setOnClickListener { startTunnel() }
        btnStop.setOnClickListener {
            TunnelService.stop(this)
            toast("正在停止隧道")
        }
        findViewById<TextView>(R.id.btnClearLog).setOnClickListener {
            TunnelStore.clearLogs()
        }
        findViewById<TextView>(R.id.btnBattery).setOnClickListener { requestBatteryWhitelist() }

        switchOverlay.isChecked = Prefs.isOverlayEnabled(this) && Settings.canDrawOverlays(this)
        switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    pendingOverlayRequest = true
                    updatingSwitch = true
                    switchOverlay.isChecked = false
                    updatingSwitch = false
                    openOverlaySettings()
                } else {
                    TunnelService.setOverlay(this, true)
                    toast("悬浮窗已开启，可拖动查看状态")
                }
            } else {
                TunnelService.setOverlay(this, false)
            }
        }

        selectMode(Prefs.load(this).mode)
        updatePreview()
    }

    override fun onStart() {
        super.onStart()
        TunnelStore.addListener(uiListener)
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        if (pendingOverlayRequest && Settings.canDrawOverlays(this)) {
            pendingOverlayRequest = false
            updatingSwitch = true
            switchOverlay.isChecked = true
            updatingSwitch = false
            Prefs.setOverlayEnabled(this, true)
            TunnelService.setOverlay(this, true)
            toast("悬浮窗权限已授予")
        } else {
            updatingSwitch = true
            switchOverlay.isChecked =
                Prefs.isOverlayEnabled(this) && Settings.canDrawOverlays(this)
            updatingSwitch = false
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { Prefs.save(this, collectConfig()) }
    }

    override fun onStop() {
        super.onStop()
        TunnelStore.removeListener(uiListener)
        handler.removeCallbacks(ticker)
    }

    // -------------------------------------------------------------------- ui

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvUptime = findViewById(R.id.tvUptime)
        tvSummary = findViewById(R.id.tvSummary)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        tvCmd = findViewById(R.id.tvCmd)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        segLocal = findViewById(R.id.segLocal)
        segServer = findViewById(R.id.segServer)
        groupLocal = findViewById(R.id.groupLocal)
        groupServer = findViewById(R.id.groupServer)
        etServer = findViewById(R.id.etServer)
        etLocalPort = findViewById(R.id.etLocalPort)
        etLocalHost = findViewById(R.id.etLocalHost)
        etRemotePort = findViewById(R.id.etRemotePort)
        etBindAddr = findViewById(R.id.etBindAddr)
        etMinPort = findViewById(R.id.etMinPort)
        etMaxPort = findViewById(R.id.etMaxPort)
        etBindTunnels = findViewById(R.id.etBindTunnels)
        etSecret = findViewById(R.id.etSecret)
        switchAutoRestart = findViewById(R.id.switchAutoRestart)
        switchOverlay = findViewById(R.id.switchOverlay)
    }

    private fun loadConfig(config: BoreConfig) {
        etServer.setText(config.server)
        etLocalPort.setText(config.localPort.toString())
        etLocalHost.setText(config.localHost)
        etRemotePort.setText(config.remotePort?.toString() ?: "")
        etBindAddr.setText(config.bindAddr)
        etMinPort.setText(config.minPort.toString())
        etMaxPort.setText(config.maxPort.toString())
        etBindTunnels.setText(config.bindTunnels)
        etSecret.setText(config.secret)
        switchAutoRestart.isChecked = config.autoRestart
    }

    private fun selectMode(newMode: Mode) {
        mode = newMode
        val isLocal = mode == Mode.LOCAL
        groupLocal.visibility = if (isLocal) View.VISIBLE else View.GONE
        groupServer.visibility = if (isLocal) View.GONE else View.VISIBLE

        val selectedBg = getDrawable(R.drawable.bg_segment_selected)
        segLocal.background = if (isLocal) selectedBg else null
        segServer.background = if (isLocal) null else getDrawable(R.drawable.bg_segment_selected)
        segLocal.setTextColor(getColor(if (isLocal) R.color.bg else R.color.text_secondary))
        segServer.setTextColor(getColor(if (isLocal) R.color.text_secondary else R.color.bg))
        updatePreview()
    }

    private fun updatePreview() {
        runCatching { tvCmd.text = collectConfig().displayCommand() }
    }

    private fun render(state: TunnelUiState, logs: List<String>) {
        val color = when {
            state.running -> getColor(R.color.ok)
            state.connecting -> getColor(R.color.warn)
            state.message.contains("失败") || state.message.contains("无法") -> getColor(R.color.error)
            else -> getColor(R.color.text_muted)
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        tvStatus.text = state.message

        tvSummary.text = when {
            state.tunnelUrl.isNotBlank() -> "公网地址  ${state.tunnelUrl}"
            else -> state.configSummary
        }

        btnStart.alpha = if (state.running || state.connecting) 0.4f else 1f
        btnStart.isEnabled = !(state.running || state.connecting)
        btnStop.alpha = if (state.running || state.connecting) 1f else 0.4f
        btnStop.isEnabled = state.running || state.connecting

        if (logs.size != lastLogCount) {
            lastLogCount = logs.size
            tvLog.text = if (logs.isEmpty()) "等待启动…" else logs.joinToString("\n")
            svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
        }
        renderUptime(state)
    }

    private fun renderUptime(state: TunnelUiState) {
        if (!state.running || state.startedAt <= 0L) {
            tvUptime.text = "00:00"
            return
        }
        val total = ((System.currentTimeMillis() - state.startedAt) / 1000L).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        tvUptime.text = if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun startTunnel() {
        val config = try {
            collectConfig()
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "配置有误")
            return
        }
        Prefs.save(this, config)
        updatePreview()
        ensureNotificationPermission()

        if (Prefs.isOverlayEnabled(this) && !Settings.canDrawOverlays(this)) {
            pendingOverlayRequest = true
            openOverlaySettings()
        }
        TunnelService.start(this, config)
        toast("隧道已提交启动")
    }

    private fun collectConfig(): BoreConfig {
        val secret = etSecret.text.toString()
        val autoRestart = switchAutoRestart.isChecked

        fun number(field: EditText, label: String, default: Int? = null): Int {
            val text = field.text.toString().trim()
            if (text.isEmpty() && default != null) return default
            return text.toIntOrNull()
                ?: throw IllegalArgumentException("$label 需要是数字")
        }

        return when (mode) {
            Mode.LOCAL -> {
                val server = etServer.text.toString().trim()
                if (server.isEmpty()) throw IllegalArgumentException("请填写服务器地址")
                val localPort = number(etLocalPort, "本地端口")
                if (localPort !in 1..65535) throw IllegalArgumentException("本地端口需在 1-65535")
                val remoteText = etRemotePort.text.toString().trim()
                val remotePort = if (remoteText.isEmpty()) {
                    null
                } else {
                    val value = remoteText.toIntOrNull()
                        ?: throw IllegalArgumentException("远程端口需要是数字")
                    if (value !in 1..65535) throw IllegalArgumentException("远程端口需在 1-65535")
                    value
                }
                BoreConfig(
                    mode = Mode.LOCAL,
                    server = server,
                    localPort = localPort,
                    localHost = etLocalHost.text.toString().trim().ifEmpty { "localhost" },
                    remotePort = remotePort,
                    secret = secret,
                    autoRestart = autoRestart
                )
            }

            Mode.SERVER -> {
                val minPort = number(etMinPort, "最小端口")
                val maxPort = number(etMaxPort, "最大端口")
                if (minPort !in 1..65535 || maxPort !in 1..65535) {
                    throw IllegalArgumentException("端口需在 1-65535")
                }
                if (minPort > maxPort) throw IllegalArgumentException("最小端口不能大于最大端口")
                BoreConfig(
                    mode = Mode.SERVER,
                    minPort = minPort,
                    maxPort = maxPort,
                    bindAddr = etBindAddr.text.toString().trim().ifEmpty { "0.0.0.0" },
                    bindTunnels = etBindTunnels.text.toString().trim(),
                    secret = secret,
                    autoRestart = autoRestart
                )
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            toast("请到系统设置中授予悬浮窗权限")
        }
    }

    private fun requestBatteryWhitelist() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("已在电池优化白名单中")
                return
            }
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("无法打开电池优化设置")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
