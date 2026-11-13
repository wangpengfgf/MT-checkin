package com.bore.tunnel

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

data class TunnelUiState(
    val running: Boolean = false,
    val connecting: Boolean = false,
    val message: String = "未连接",
    val startedAt: Long = 0L,
    val attempts: Int = 0,
    val configSummary: String = "尚未启动 · 配置后点击下方按钮建立隧道",
    val tunnelUrl: String = ""
)

/** 进程内共享的运行状态与日志，供 Activity / 前台服务 / 悬浮窗订阅。 */
object TunnelStore {
    private const val MAX_LOGS = 400

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(TunnelUiState, List<String>) -> Unit>()
    private val logs = ArrayDeque<String>()

    @Volatile
    var state = TunnelUiState()
        private set

    fun addListener(listener: (TunnelUiState, List<String>) -> Unit) {
        listeners.add(listener)
        val snapshot = snapshot()
        main.post { listener(state, snapshot) }
    }

    fun removeListener(listener: (TunnelUiState, List<String>) -> Unit) {
        listeners.remove(listener)
    }

    fun setState(newState: TunnelUiState) {
        state = newState
        dispatch()
    }

    fun appendLog(line: String) {
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > MAX_LOGS) logs.removeFirst()
        }
        dispatch()
    }

    fun clearLogs() {
        synchronized(logs) { logs.clear() }
        dispatch()
    }

    fun snapshot(): List<String> = synchronized(logs) { logs.toList() }

    private fun dispatch() {
        val currentState = state
        val currentLogs = snapshot()
        main.post {
            listeners.forEach { it(currentState, currentLogs) }
        }
    }
}
