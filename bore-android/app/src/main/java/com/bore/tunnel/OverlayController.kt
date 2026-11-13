package com.bore.tunnel

import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 可拖动悬浮窗：收起时显示通道在线数，展开后展示每个通道的状态与日志尾部，
 * 支持全部启停或点击单个通道启停。
 */
class OverlayController(private val service: Service) {

    private data class RowRef(
        val dot: View,
        val name: TextView,
        val detail: TextView,
        val badge: TextView
    )

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val metrics = service.resources.displayMetrics

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var pill: LinearLayout? = null
    private var pillDot: View? = null
    private var pillLabel: TextView? = null
    private var card: LinearLayout? = null
    private var cardDot: View? = null
    private var cardStatus: TextView? = null
    private var cardDetail: TextView? = null
    private var cardLog: TextView? = null
    private var channelContainer: LinearLayout? = null
    private var channelScroll: ScrollView? = null
    private var btnStartAll: TextView? = null
    private var btnStopAll: TextView? = null

    private val rows = LinkedHashMap<String, RowRef>()
    private var rowsSignature = ""
    private var shown = false
    private var lastState = TunnelStore.state
    private var lastLogs: List<String> = emptyList()

    private val listener: (TunnelUiState, List<String>) -> Unit = { state, logs ->
        lastState = state
        lastLogs = logs
        render()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!shown) return
            render()
            main.postDelayed(this, 1000L)
        }
    }

    fun show() {
        if (shown) return
        if (!Settings.canDrawOverlays(service)) {
            TunnelStore.appendLog("未授予悬浮窗权限，悬浮窗未显示")
            return
        }
        try {
            build()
            shown = true
            lastState = TunnelStore.state
            lastLogs = TunnelStore.snapshot()
            TunnelStore.addListener(listener)
            render()
            main.postDelayed(ticker, 1000L)
        } catch (t: Throwable) {
            shown = false
            TunnelStore.appendLog("悬浮窗创建失败：${t.message}")
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        TunnelStore.removeListener(listener)
        main.removeCallbacks(ticker)
        try {
            root?.let { wm.removeView(it) }
        } catch (_: Throwable) {
        }
        root = null
        params = null
        pill = null
        card = null
        channelContainer = null
        channelScroll = null
        rows.clear()
        rowsSignature = ""
    }

    // ------------------------------------------------------------------- view

    private fun build() {
        val container = FrameLayout(service)
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(140)
        }

        pill = buildPill()
        card = buildCard()
        card?.visibility = View.GONE
        container.addView(pill)
        val cardWidth = (300 * metrics.density).toInt()
            .coerceAtMost(metrics.widthPixels - dp(16))
        container.addView(
            card,
            FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        root = container
        params = windowParams
        wm.addView(container, windowParams)
    }

    private fun buildPill(): LinearLayout {
        val pillView = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xF20C1219.toInt(), 24f, 0x5522D3EE)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(10), dp(16), dp(10))
        }
        val dot = View(service).apply {
            setBackgroundResource(R.drawable.ic_dot)
            backgroundTintList = ColorStateList.valueOf(service.getColor(R.color.text_muted))
        }
        pillView.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
        val label = TextView(service).apply {
            text = "BORE · 未连接"
            setTextColor(service.getColor(R.color.text_primary))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.04f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        pillView.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(9) }
        )

        pillDot = dot
        pillLabel = label
        attachDrag(pillView) { expand() }
        return pillView
    }

    private fun buildCard(): LinearLayout {
        val cardView = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF50C1219.toInt(), 18f, 0x663B82F6)
            elevation = dp(10).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(service).apply {
            text = "BORE TUNNEL"
            setTextColor(service.getColor(R.color.accent))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.12f
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(service).apply {
            text = "收起"
            setTextColor(service.getColor(R.color.text_muted))
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { collapse() }
        })
        header.addView(TextView(service).apply {
            text = "×"
            setTextColor(service.getColor(R.color.text_muted))
            textSize = 17f
            setPadding(dp(8), 0, dp(2), dp(2))
            setOnClickListener { hideFromUser() }
        })
        attachDrag(header) { collapse() }
        cardView.addView(header)

        val statusRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(service).apply {
            setBackgroundResource(R.drawable.ic_dot)
            backgroundTintList = ColorStateList.valueOf(service.getColor(R.color.text_muted))
        }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
        val status = TextView(service).apply {
            text = "未连接"
            setTextColor(service.getColor(R.color.text_primary))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusRow.addView(
            status,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        )
        cardView.addView(
            statusRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        val detail = TextView(service).apply {
            setTextColor(service.getColor(R.color.text_secondary))
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        cardView.addView(
            detail,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        )

        // 通道列表
        val scroll = ScrollView(service).apply {
            isVerticalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        cardView.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val logView = TextView(service).apply {
            text = "等待启动…"
            setTextColor(0xFF8FA6B8.toInt())
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.START
            background = rounded(0xFF070B0F.toInt(), 10f, 0xFF1B2530.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        cardView.addView(
            logView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val actions = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        btnStartAll = actionButton("全部启动", 0xFF22D3EE.toInt(), 0xFF041218.toInt()) {
            TunnelService.startAllSaved(service)
        }
        btnStopAll = actionButton("全部停止", 0xFF2A1216.toInt(), 0xFFF87171.toInt()) {
            TunnelService.stopAll(service)
        }
        val btnOpen = actionButton("打开", 0xFF101821.toInt(), 0xFFA7B6C2.toInt()) {
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        actions.addView(btnStartAll, LinearLayout.LayoutParams(0, dp(36), 1f))
        actions.addView(btnStopAll, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
        actions.addView(btnOpen, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
        cardView.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        cardDot = dot
        cardStatus = status
        cardDetail = detail
        cardLog = logView
        channelContainer = list
        channelScroll = scroll
        return cardView
    }

    private fun actionButton(
        text: String,
        background: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView = TextView(service).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        this.background = rounded(background, 10f, 0x3322D3EE)
        isClickable = true
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- behavior

    private fun expand() {
        pill?.visibility = View.GONE
        card?.visibility = View.VISIBLE
        clampToScreen()
    }

    private fun collapse() {
        card?.visibility = View.GONE
        pill?.visibility = View.VISIBLE
        clampToScreen()
    }

    private fun hideFromUser() {
        Prefs.setOverlayEnabled(service, false)
        service.startService(
            Intent(service, TunnelService::class.java)
                .setAction(TunnelService.ACTION_OVERLAY)
                .putExtra(TunnelService.EXTRA_OVERLAY, false)
        )
        hide()
    }

    private fun clampToScreen() {
        val p = params ?: return
        val view = root ?: return
        val width = if (view.width > 0) view.width else dp(300)
        val maxX = max(0, metrics.widthPixels - width)
        val maxY = max(0, metrics.heightPixels - dp(80))
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
        try {
            wm.updateViewLayout(view, p)
        } catch (_: Throwable) {
        }
    }

    private fun attachDrag(view: View, onTap: (() -> Unit)? = null) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    p.x = startX + dx
                    p.y = startY + dy
                    try {
                        root?.let { wm.updateViewLayout(it, p) }
                    } catch (_: Throwable) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke() else clampToScreen()
                    true
                }

                else -> false
            }
        }
    }

    // ----------------------------------------------------------------- render

    private fun render() {
        val state = lastState
        val total = state.channels.size
        val running = state.runningCount
        val connecting = state.connectingCount

        val color = when {
            running > 0 -> service.getColor(R.color.ok)
            connecting > 0 -> service.getColor(R.color.warn)
            state.errorCount > 0 -> service.getColor(R.color.error)
            else -> service.getColor(R.color.text_muted)
        }
        pillDot?.backgroundTintList = ColorStateList.valueOf(color)
        cardDot?.backgroundTintList = ColorStateList.valueOf(color)

        pillLabel?.text = when {
            running > 0 -> "BORE · $running/$total 在线"
            connecting > 0 -> "BORE · 连接中"
            else -> "BORE · 未连接"
        }

        cardStatus?.text = when {
            running > 0 -> "在线 $running/$total" +
                if (connecting > 0) " · $connecting 连接中" else ""
            connecting > 0 -> "$connecting 个通道连接中"
            total == 0 -> "暂无通道"
            else -> "全部未连接"
        }
        cardDetail?.text = if (state.channels.any { it.url.isNotBlank() }) {
            state.channels.firstNotNullOfOrNull { it.url.ifBlank { null } } ?: ""
        } else {
            "点击通道可单独启停"
        }

        renderChannels(state)

        cardLog?.text = if (lastLogs.isEmpty()) {
            "等待启动…"
        } else {
            lastLogs.takeLast(6).joinToString("\n")
        }

        btnStartAll?.alpha = if (total > 0) 1f else 0.4f
        btnStartAll?.isEnabled = total > 0
        btnStopAll?.alpha = if (state.activeCount > 0) 1f else 0.4f
        btnStopAll?.isEnabled = state.activeCount > 0
    }

    private fun renderChannels(state: TunnelUiState) {
        val signature = state.channels.joinToString("|") { it.id + it.name }
        if (signature != rowsSignature) {
            rowsSignature = signature
            rebuildRows(state)
        }
        state.channels.forEach { channel ->
            val row = rows[channel.id] ?: return@forEach
            val color = when (channel.status) {
                ChannelStatus.RUNNING -> service.getColor(R.color.ok)
                ChannelStatus.CONNECTING -> service.getColor(R.color.warn)
                ChannelStatus.ERROR -> service.getColor(R.color.error)
                ChannelStatus.IDLE -> service.getColor(R.color.text_muted)
            }
            row.dot.backgroundTintList = ColorStateList.valueOf(color)
            row.name.text = channel.name
            row.detail.text = when {
                channel.url.isNotBlank() -> channel.url
                channel.status == ChannelStatus.RUNNING -> "运行中 · ${uptime(channel)}"
                else -> channel.message
            }
            row.badge.text = when (channel.status) {
                ChannelStatus.RUNNING -> "停止"
                ChannelStatus.CONNECTING -> "取消"
                ChannelStatus.ERROR -> "重试"
                ChannelStatus.IDLE -> "启动"
            }
            row.badge.setTextColor(color)
        }
        val rowHeight = dp(38)
        channelScroll?.layoutParams?.let { lp ->
            lp.height = min(max(state.channels.size, 1), 4) * rowHeight
            channelScroll?.layoutParams = lp
        }
    }

    private fun rebuildRows(state: TunnelUiState) {
        val list = channelContainer ?: return
        list.removeAllViews()
        rows.clear()

        if (state.channels.isEmpty()) {
            list.addView(TextView(service).apply {
                text = "暂无通道，打开应用添加"
                setTextColor(service.getColor(R.color.text_muted))
                textSize = 10f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        state.channels.forEachIndexed { index, channel ->
            if (index > 0) {
                list.addView(View(service).apply {
                    setBackgroundColor(0x1AFFFFFF)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                })
            }

            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                isClickable = true
                setOnClickListener { toggleChannel(channel) }
            }
            val dot = View(service).apply {
                setBackgroundResource(R.drawable.ic_dot)
                backgroundTintList = ColorStateList.valueOf(service.getColor(R.color.text_muted))
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))

            val texts = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(service).apply {
                text = channel.name
                setTextColor(service.getColor(R.color.text_primary))
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val detail = TextView(service).apply {
                text = channel.summary
                setTextColor(service.getColor(R.color.text_muted))
                textSize = 9f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            texts.addView(name)
            texts.addView(detail)
            row.addView(
                texts,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
            )

            val badge = TextView(service).apply {
                text = "启动"
                textSize = 9f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = rounded(0x1A22D3EE, 8f, 0x3322D3EE)
                isClickable = true
                setOnClickListener { toggleChannel(channel) }
            }
            row.addView(badge, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) })

            list.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            rows[channel.id] = RowRef(dot, name, detail, badge)
        }
    }

    private fun toggleChannel(channel: ChannelState) {
        when (channel.status) {
            ChannelStatus.RUNNING, ChannelStatus.CONNECTING ->
                TunnelService.stopOne(service, channel.id)

            ChannelStatus.IDLE, ChannelStatus.ERROR ->
                TunnelService.startOne(service, channel.id)
        }
    }

    private fun uptime(channel: ChannelState): String {
        if (channel.startedAt <= 0L) return "00:00"
        val total = ((System.currentTimeMillis() - channel.startedAt) / 1000L).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            strokeColor?.let { setStroke(dp(1).coerceAtLeast(1), it) }
        }

    private fun dp(value: Int): Int = (value * metrics.density).toInt()

    private fun dp(value: Float): Int = (value * metrics.density).toInt()
}
