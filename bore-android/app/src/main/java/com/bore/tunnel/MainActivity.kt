package com.bore.tunnel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private data class RowHolder(
        val dot: View,
        val status: TextView,
        val toggle: TextView,
        val edit: TextView,
        val delete: TextView
    )

    private val handler = Handler(Looper.getMainLooper())
    private val rowViews = LinkedHashMap<String, RowHolder>()
    private var channels = mutableListOf<Channel>()
    private var rowsSignature = ""
    private var pendingOverlayRequest = false
    private var updatingSwitch = false
    private var lastLogCount = -1
    private var lastLogs: List<String> = emptyList()

    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnStartAll: TextView
    private lateinit var btnStopAll: TextView
    private lateinit var llChannels: LinearLayout
    private lateinit var switchOverlay: Switch

    private val listener: (TunnelUiState, List<String>) -> Unit = { state, logs ->
        lastLogs = logs
        render(state, logs)
    }

    private val ticker = object : Runnable {
        override fun run() {
            render(TunnelStore.state, lastLogs)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        channels = Prefs.loadChannels(this)
        TunnelStore.syncFromConfig(channels)

        btnStartAll.setOnClickListener { startAll() }
        btnStopAll.setOnClickListener {
            TunnelService.stopAll(this)
            toast("正在停止全部通道")
        }
        findViewById<TextView>(R.id.btnAddChannel).setOnClickListener { showChannelDialog(null) }
        findViewById<TextView>(R.id.btnClearLog).setOnClickListener { TunnelStore.clearLogs() }
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
                    toast("悬浮窗已开启，可查看并单独启停通道")
                }
            } else {
                TunnelService.setOverlay(this, false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TunnelStore.addListener(listener)
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
        Prefs.saveChannels(this, channels)
    }

    override fun onStop() {
        super.onStop()
        TunnelStore.removeListener(listener)
        handler.removeCallbacks(ticker)
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvUptime = findViewById(R.id.tvUptime)
        tvSummary = findViewById(R.id.tvSummary)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        btnStartAll = findViewById(R.id.btnStartAll)
        btnStopAll = findViewById(R.id.btnStopAll)
        llChannels = findViewById(R.id.llChannels)
        switchOverlay = findViewById(R.id.switchOverlay)
    }

    // ----------------------------------------------------------------- render

    private fun render(state: TunnelUiState, logs: List<String>) {
        val total = channels.size
        val running = state.runningCount
        val connecting = state.connectingCount
        val active = state.activeCount

        val color = when {
            running > 0 -> getColor(R.color.ok)
            connecting > 0 -> getColor(R.color.warn)
            state.errorCount > 0 -> getColor(R.color.error)
            else -> getColor(R.color.text_muted)
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color)

        tvStatus.text = when {
            running > 0 -> "在线 $running / $total 个通道"
            connecting > 0 -> "$connecting 个通道连接中"
            total == 0 -> "暂无通道"
            else -> "全部未连接"
        }
        tvUptime.text = "$running/$total"

        val urls = state.channels.filter { it.url.isNotBlank() }.map { it.url }
        tvSummary.text = when {
            urls.isNotEmpty() -> urls.joinToString("  ·  ")
            total == 0 -> "点击右上角「添加通道」开始配置"
            else -> "共 $total 个通道 · 可同时转发多个本地服务"
        }

        btnStartAll.alpha = if (total == 0) 0.4f else 1f
        btnStartAll.isEnabled = total > 0
        btnStopAll.alpha = if (active > 0) 1f else 0.4f
        btnStopAll.isEnabled = active > 0

        renderRows(state)

        if (logs.size != lastLogCount) {
            lastLogCount = logs.size
            tvLog.text = if (logs.isEmpty()) "等待启动…" else logs.joinToString("\n")
            svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun renderRows(state: TunnelUiState) {
        val signature = buildString {
            channels.forEach {
                append(it.id).append(':').append(it.name).append(':')
                    .append(it.summary()).append(':').append(it.enabled).append('|')
            }
            append('#')
            state.channels.forEach { append(it.id).append(',') }
        }
        if (signature != rowsSignature) {
            rowsSignature = signature
            rebuildRows(state)
        } else {
            updateRows(state)
        }
    }

    private fun rebuildRows(state: TunnelUiState) {
        llChannels.removeAllViews()
        rowViews.clear()

        if (channels.isEmpty()) {
            llChannels.addView(TextView(this).apply {
                text = "还没有通道，点击右上角「＋ 添加通道」创建"
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(0, dp(14), 0, dp(6))
            })
            return
        }

        channels.forEachIndexed { index, channel ->
            if (index > 0) {
                llChannels.addView(View(this).apply {
                    setBackgroundColor(0x14FFFFFF)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(2)
                })
            }
            val channelState = state.channels.firstOrNull { it.id == channel.id }
                ?: channel.state()
            llChannels.addView(buildRow(channel, channelState))
        }
    }

    private fun buildRow(channel: Channel, state: ChannelState): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }

        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            setBackgroundResource(R.drawable.ic_dot)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.text_muted))
        }
        line1.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))

        val name = TextView(this).apply {
            text = channel.displayName
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line1.addView(
            name,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        )

        val enableSwitch = Switch(this).apply {
            isChecked = channel.enabled
            setOnCheckedChangeListener { _, checked ->
                val target = channels.firstOrNull { it.id == channel.id } ?: return@setOnCheckedChangeListener
                val index = channels.indexOf(target)
                channels[index] = target.copy(enabled = checked)
                Prefs.saveChannels(this@MainActivity, channels)
                if (!checked) {
                    TunnelService.stopOne(this@MainActivity, channel.id)
                }
                if (checked) {
                    TunnelStore.updateChannel(target.state(message = "未启动"))
                } else {
                    TunnelStore.updateChannel(target.state(message = "已禁用"))
                }
                rowsSignature = ""
                renderRows(TunnelStore.state)
            }
        }
        line1.addView(enableSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(line1)

        val typeText = if (channel.type == ChannelType.LOCAL) "本地隧道" else "服务端"
        val sub = TextView(this).apply {
            text = "$typeText · ${channel.summary()}"
            setTextColor(getColor(R.color.text_muted))
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(3), 0, 0)
        }
        root.addView(sub)

        val line3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), 0, 0)
        }
        val status = TextView(this).apply {
            text = state.message
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line3.addView(
            status,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val toggle = smallAction("启动", getColor(R.color.accent)) {
            val current = TunnelStore.state.channels.firstOrNull { it.id == channel.id }
            if (current?.status == ChannelStatus.RUNNING || current?.status == ChannelStatus.CONNECTING) {
                TunnelService.stopOne(this, channel.id)
            } else {
                ensureNotificationPermission()
                TunnelService.startOne(this, channel.id)
            }
        }
        val edit = smallAction("编辑", getColor(R.color.text_secondary)) {
            showChannelDialog(channel)
        }
        val delete = smallAction("删除", getColor(R.color.error)) {
            confirmDelete(channel)
        }
        line3.addView(toggle)
        line3.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })
        line3.addView(delete, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })
        root.addView(line3)

        rowViews[channel.id] = RowHolder(dot, status, toggle, edit, delete)
        updateRow(channel, state)
        return root
    }

    private fun updateRows(state: TunnelUiState) {
        channels.forEach { channel ->
            val channelState = state.channels.firstOrNull { it.id == channel.id } ?: return@forEach
            updateRow(channel, channelState)
        }
    }

    private fun updateRow(channel: Channel, state: ChannelState) {
        val row = rowViews[channel.id] ?: return
        val color = when (state.status) {
            ChannelStatus.RUNNING -> getColor(R.color.ok)
            ChannelStatus.CONNECTING -> getColor(R.color.warn)
            ChannelStatus.ERROR -> getColor(R.color.error)
            ChannelStatus.IDLE -> getColor(R.color.text_muted)
        }
        row.dot.backgroundTintList = ColorStateList.valueOf(color)
        row.status.setTextColor(color)
        row.status.text = when {
            state.url.isNotBlank() -> state.url
            state.status == ChannelStatus.RUNNING -> "运行中 · ${uptime(state)}"
            else -> state.message
        }
        row.toggle.text = when (state.status) {
            ChannelStatus.RUNNING, ChannelStatus.CONNECTING -> "停止"
            ChannelStatus.ERROR -> "重试"
            ChannelStatus.IDLE -> "启动"
        }
        row.toggle.setTextColor(
            if (state.status == ChannelStatus.RUNNING || state.status == ChannelStatus.CONNECTING) {
                getColor(R.color.error)
            } else {
                getColor(R.color.accent)
            }
        )
        row.delete.visibility = View.VISIBLE
    }

    private fun smallAction(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), dp(2), dp(4), dp(2))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun uptime(state: ChannelState): String {
        if (state.startedAt <= 0L) return "00:00"
        val total = ((System.currentTimeMillis() - state.startedAt) / 1000L).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun startAll() {
        if (channels.isEmpty()) {
            toast("请先添加通道")
            return
        }
        val enabled = channels.count { it.enabled }
        if (enabled == 0) {
            toast("没有启用的通道")
            return
        }
        Prefs.saveChannels(this, channels)
        ensureNotificationPermission()
        if (Prefs.isOverlayEnabled(this) && !Settings.canDrawOverlays(this)) {
            pendingOverlayRequest = true
            openOverlaySettings()
        }
        TunnelService.startAll(this, channels)
        toast("正在启动 $enabled 个通道")
    }

    private fun confirmDelete(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle("删除通道")
            .setMessage("确定删除「${channel.displayName}」吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                channels.removeAll { it.id == channel.id }
                Prefs.saveChannels(this, channels)
                TunnelStore.removeChannel(channel.id)
                TunnelService.stopOne(this, channel.id)
                rowsSignature = ""
                renderRows(TunnelStore.state)
                toast("已删除")
            }
            .show()
    }

    private fun showChannelDialog(existing: Channel?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply {
            addView(form)
            isVerticalScrollBarEnabled = false
        }

        var type = existing?.type ?: ChannelType.LOCAL

        val etName = addField(form, "通道名称", existing?.name ?: "服务 ${channels.size + 1}")
        val etServer = addField(form, "远程服务器 --to", existing?.server ?: "bore.pub")
        val etLocalPort = addField(
            form, "本地端口 LOCAL_PORT",
            (existing?.localPort ?: 8080).toString(), numeric = true
        )
        val etLocalHost = addField(form, "本地地址 --local-host", existing?.localHost ?: "localhost")
        val etRemotePort = addField(
            form, "远程端口 --port（留空由服务端分配）",
            existing?.remotePort?.toString() ?: "", numeric = true
        )

        val serverGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        form.addView(serverGroup)
        val etBindAddr = addField(
            serverGroup, "监听地址 --bind-addr", existing?.bindAddr ?: "0.0.0.0"
        )
        val etMinPort = addField(
            serverGroup, "最小端口 --min-port",
            (existing?.minPort ?: 1024).toString(), numeric = true
        )
        val etMaxPort = addField(
            serverGroup, "最大端口 --max-port",
            (existing?.maxPort ?: 65535).toString(), numeric = true
        )
        val etBindTunnels = addField(
            serverGroup, "隧道监听地址 --bind-tunnels（可选）", existing?.bindTunnels ?: ""
        )

        val etSecret = addField(form, "密钥 --secret（可选）", existing?.secret ?: "", password = true)

        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autoRow.addView(TextView(this).apply {
            text = "断线自动重连"
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val autoSwitch = Switch(this).apply {
            isChecked = existing?.autoRestart ?: true
        }
        autoRow.addView(autoSwitch)
        form.addView(
            autoRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        // 类型选择放在名称下方
        val typeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = getDrawable(R.drawable.bg_segment)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val segLocal = TextView(this).apply {
            text = "本地隧道"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val segServer = TextView(this).apply {
            text = "服务端"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        typeBox.addView(segLocal, LinearLayout.LayoutParams(0, dp(38), 1f))
        typeBox.addView(segServer, LinearLayout.LayoutParams(0, dp(38), 1f))

        fun applyType() {
            val isLocal = type == ChannelType.LOCAL
            segLocal.background = if (isLocal) getDrawable(R.drawable.bg_segment_selected) else null
            segServer.background = if (isLocal) null else getDrawable(R.drawable.bg_segment_selected)
            segLocal.setTextColor(getColor(if (isLocal) R.color.bg else R.color.text_secondary))
            segServer.setTextColor(getColor(if (isLocal) R.color.text_secondary else R.color.bg))
            serverGroup.visibility = if (isLocal) View.GONE else View.VISIBLE
        }
        segLocal.setOnClickListener { type = ChannelType.LOCAL; applyType() }
        segServer.setOnClickListener { type = ChannelType.SERVER; applyType() }

        // 把类型选择插到名称后面（索引 2：名称标签 + 输入框之后）
        val typeLabel = TextView(this).apply {
            text = "通道类型"
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(12), 0, dp(0))
        }
        form.addView(typeLabel, 2)
        form.addView(typeBox, 3)
        applyType()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加通道" else "编辑通道")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = try {
                    parseChannel(
                        existing = existing,
                        type = type,
                        name = etName.text.toString().trim(),
                        server = etServer.text.toString().trim(),
                        localPort = etLocalPort.text.toString().trim(),
                        localHost = etLocalHost.text.toString().trim(),
                        remotePort = etRemotePort.text.toString().trim(),
                        bindAddr = etBindAddr.text.toString().trim(),
                        minPort = etMinPort.text.toString().trim(),
                        maxPort = etMaxPort.text.toString().trim(),
                        bindTunnels = etBindTunnels.text.toString().trim(),
                        secret = etSecret.text.toString(),
                        autoRestart = autoSwitch.isChecked
                    )
                } catch (e: IllegalArgumentException) {
                    toast(e.message ?: "配置有误")
                    return@setOnClickListener
                }

                val index = channels.indexOfFirst { it.id == parsed.id }
                val wasActive = TunnelStore.state.channels
                    .firstOrNull { it.id == parsed.id }
                    ?.let { it.status == ChannelStatus.RUNNING || it.status == ChannelStatus.CONNECTING }
                    ?: false

                if (index >= 0) channels[index] = parsed else channels.add(parsed)
                Prefs.saveChannels(this, channels)
                TunnelStore.syncFromConfig(channels)
                rowsSignature = ""
                renderRows(TunnelStore.state)

                if (wasActive) {
                    TunnelService.startOne(this, parsed.id)
                    toast("已保存并重启该通道")
                } else {
                    toast("通道已保存")
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parseChannel(
        existing: Channel?,
        type: ChannelType,
        name: String,
        server: String,
        localPort: String,
        localHost: String,
        remotePort: String,
        bindAddr: String,
        minPort: String,
        maxPort: String,
        bindTunnels: String,
        secret: String,
        autoRestart: Boolean
    ): Channel {
        fun parse(value: String, label: String, default: Int? = null): Int {
            if (value.isEmpty() && default != null) return default
            return value.toIntOrNull() ?: throw IllegalArgumentException("$label 需要是数字")
        }

        val base = Channel(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            type = type,
            enabled = existing?.enabled ?: true,
            secret = secret,
            autoRestart = autoRestart
        )

        return when (type) {
            ChannelType.LOCAL -> {
                if (server.isEmpty()) throw IllegalArgumentException("请填写远程服务器地址")
                val local = parse(localPort, "本地端口")
                if (local !in 1..65535) throw IllegalArgumentException("本地端口需在 1-65535")
                val remote = if (remotePort.isEmpty()) {
                    null
                } else {
                    val value = parse(remotePort, "远程端口")
                    if (value !in 1..65535) throw IllegalArgumentException("远程端口需在 1-65535")
                    value
                }
                base.copy(
                    server = server,
                    localPort = local,
                    localHost = localHost.ifEmpty { "localhost" },
                    remotePort = remote
                )
            }

            ChannelType.SERVER -> {
                val min = parse(minPort, "最小端口")
                val max = parse(maxPort, "最大端口")
                if (min !in 1..65535 || max !in 1..65535) {
                    throw IllegalArgumentException("端口需在 1-65535")
                }
                if (min > max) throw IllegalArgumentException("最小端口不能大于最大端口")
                base.copy(
                    bindAddr = bindAddr.ifEmpty { "0.0.0.0" },
                    minPort = min,
                    maxPort = max,
                    bindTunnels = bindTunnels
                )
            }
        }
    }

    private fun addField(
        container: LinearLayout,
        label: String,
        value: String,
        numeric: Boolean = false,
        password: Boolean = false
    ): EditText {
        container.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        })
        val edit = EditText(this).apply {
            setText(value)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
            textSize = 14f
            isSingleLine = true
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), 0, dp(12), 0)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        container.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(6) }
        )
        return edit
    }

    // ------------------------------------------------------------- permission

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
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("无法打开电池优化设置")
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
