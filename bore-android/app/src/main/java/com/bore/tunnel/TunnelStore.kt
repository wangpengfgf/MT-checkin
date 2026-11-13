package com.bore.tunnel

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

data class TunnelUiState(
    val channels: List<ChannelState> = emptyList()
) {
    val runningCount: Int get() = channels.count { it.status == ChannelStatus.RUNNING }
    val connectingCount: Int get() = channels.count { it.status == ChannelStatus.CONNECTING }
    val errorCount: Int get() = channels.count { it.status == ChannelStatus.ERROR }
    val activeCount: Int get() = channels.count {
        it.status == ChannelStatus.RUNNING || it.status == ChannelStatus.CONNECTING
    }
}

/** 进程内共享的多通道状态与日志，供 Activity / 前台服务 / 悬浮窗订阅。 */
object TunnelStore {
    private const val MAX_LOGS = 500

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

    /** 用持久化配置对齐通道列表，保留已有同 id 通道的实时状态。 */
    fun syncFromConfig(channels: List<Channel>) {
        val current = state.channels.associateBy { it.id }
        val merged = channels.map { channel ->
            current[channel.id]?.copy(
                name = channel.displayName,
                summary = channel.summary()
            ) ?: channel.state(
                message = if (channel.enabled) "未启动" else "已禁用"
            )
        }
        setChannels(merged)
    }

    fun setChannels(channels: List<ChannelState>) {
        state = TunnelUiState(channels)
        dispatch()
    }

    fun updateChannel(channel: ChannelState) {
        val channels = state.channels.toMutableList()
        val index = channels.indexOfFirst { it.id == channel.id }
        if (index >= 0) {
            channels[index] = channel
        } else {
            channels.add(channel)
        }
        state = TunnelUiState(channels)
        dispatch()
    }

    fun removeChannel(id: String) {
        state = TunnelUiState(state.channels.filterNot { it.id == id })
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
