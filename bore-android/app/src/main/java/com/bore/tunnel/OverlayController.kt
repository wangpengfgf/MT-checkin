package com.bore.tunnel

import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * 可拖动悬浮窗：收起时是一条状态胶囊，展开后显示状态、日志尾部与启停按钮。
 * 依附于前台服务，可在任意界面操作，避免切回应用才能保活/重连。
 */
class OverlayController(private val service: Service) {

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
    private var btnStart: TextView? = null
    private var btnStop: TextView? = null
    private var btnCollapse: TextView? = null

    private var expanded = false
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
            renderUptime()
            main.postDelayed(this, 1000L)
        }
    }

    fun isShown(): Boolean = shown

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
        expanded = false
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
        val cardWidth = (286 * metrics.density).toInt()
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
        pillView.addView(
            dot,
            LinearLayout.LayoutParams(dp(8), dp(8))
        )
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

        // 头部（可拖动）
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
        btnCollapse = TextView(service).apply {
            text = "收起"
            setTextColor(service.getColor(R.color.text_muted))
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { collapse() }
        }
        header.addView(btnCollapse)
        val close = TextView(service).apply {
            text = "×"
            setTextColor(service.getColor(R.color.text_muted))
            textSize = 17f
            setPadding(dp(8), 0, dp(2), dp(2))
            setOnClickListener { hideFromUser() }
        }
        header.addView(close)
        attachDrag(header) { collapse() }
        cardView.addView(header)

        // 状态行
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
            text = ""
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
            ).apply { topMargin = dp(4) }
        )

        val logView = TextView(service).apply {
            text = "等待启动…"
            setTextColor(0xFF8FA6B8.toInt())
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 8
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

        // 操作按钮
        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnStart = actionButton("启动", 0xFF22D3EE.toInt(), 0xFF041218.toInt()) {
            TunnelService.startSaved(service)
        }
        btnStop = actionButton("停止", 0xFF2A1216.toInt(), 0xFFF87171.toInt()) {
            TunnelService.stop(service)
        }
        val btnOpen = actionButton("打开", 0xFF101821.toInt(), 0xFFA7B6C2.toInt()) {
            val intent = Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            service.startActivity(intent)
        }
        actions.addView(btnStart, LinearLayout.LayoutParams(0, dp(36), 1f))
        actions.addView(btnStop, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
        actions.addView(btnOpen, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
        cardView.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        cardDot = dot
        cardStatus = status
        cardDetail = detail
        cardLog = logView
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
        expanded = true
        pill?.visibility = View.GONE
        card?.visibility = View.VISIBLE
        clampToScreen()
    }

    private fun collapse() {
        expanded = false
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
        val active = state.running || state.connecting
        val color = when {
            state.running -> service.getColor(R.color.ok)
            state.connecting -> service.getColor(R.color.warn)
            else -> service.getColor(R.color.text_muted)
        }

        pillDot?.backgroundTintList = ColorStateList.valueOf(color)
        cardDot?.backgroundTintList = ColorStateList.valueOf(color)

        pillLabel?.text = when {
            state.running -> "BORE · 已连接"
            state.connecting -> "BORE · 连接中"
            else -> "BORE · 未连接"
        }

        val statusText = when {
            state.tunnelUrl.isNotBlank() -> "隧道已建立"
            state.running -> "隧道运行中"
            state.connecting -> state.message
            else -> state.message
        }
        cardStatus?.text = if (state.running) "$statusText · ${uptime(state)}" else statusText

        cardDetail?.text = when {
            state.tunnelUrl.isNotBlank() -> state.tunnelUrl
            state.attempts > 0 -> "第 ${state.attempts} 次重连 · ${state.configSummary}"
            else -> state.configSummary
        }

        cardLog?.text = if (lastLogs.isEmpty()) {
            "等待启动…"
        } else {
            lastLogs.takeLast(8).joinToString("\n")
        }

        btnStart?.apply {
            alpha = if (active) 0.4f else 1f
            isEnabled = !active
        }
        btnStop?.apply {
            alpha = if (active) 1f else 0.4f
            isEnabled = active
        }
    }

    private fun renderUptime() {
        val state = lastState
        if (state.running) {
            cardStatus?.text = "隧道运行中 · ${uptime(state)}"
        }
    }

    private fun uptime(state: TunnelUiState): String {
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
