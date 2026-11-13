package com.bore.tunnel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object Prefs {
    private const val FILE = "bore_settings"

    private const val K_CHANNELS = "channels"
    private const val K_OVERLAY = "overlay_enabled"
    private const val K_WANT_RUNNING = "want_running"

    // 旧版本单通道配置键，用于升级迁移
    private const val K_MODE = "mode"
    private const val K_SERVER = "server"
    private const val K_LOCAL_PORT = "local_port"
    private const val K_LOCAL_HOST = "local_host"
    private const val K_REMOTE_PORT = "remote_port"
    private const val K_SECRET = "secret"
    private const val K_BIND_ADDR = "bind_addr"
    private const val K_MIN_PORT = "min_port"
    private const val K_MAX_PORT = "max_port"
    private const val K_BIND_TUNNELS = "bind_tunnels"
    private const val K_AUTO_RESTART = "auto_restart"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun loadChannels(context: Context): MutableList<Channel> {
        val prefs = sp(context)
        val raw = prefs.getString(K_CHANNELS, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(Channel.fromJson(item))
                    }
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed.toMutableList()
        }

        val migrated = mutableListOf(legacyChannel(prefs))
        saveChannels(context, migrated)
        return migrated
    }

    fun saveChannels(context: Context, channels: List<Channel>) {
        val array = JSONArray()
        channels.forEach { array.put(it.toJson()) }
        sp(context).edit().putString(K_CHANNELS, array.toString()).apply()
    }

    private fun legacyChannel(prefs: SharedPreferences): Channel {
        val type = if (prefs.getString(K_MODE, "LOCAL") == "SERVER") {
            ChannelType.SERVER
        } else {
            ChannelType.LOCAL
        }
        val channel = Channel(
            name = if (type == ChannelType.LOCAL) "我的服务" else "服务端",
            type = type,
            server = prefs.getString(K_SERVER, "bore.pub") ?: "bore.pub",
            localPort = prefs.getInt(K_LOCAL_PORT, 8080),
            localHost = prefs.getString(K_LOCAL_HOST, "localhost") ?: "localhost",
            remotePort = prefs.getInt(K_REMOTE_PORT, 0).takeIf { it > 0 },
            secret = prefs.getString(K_SECRET, "") ?: "",
            bindAddr = prefs.getString(K_BIND_ADDR, "0.0.0.0") ?: "0.0.0.0",
            minPort = prefs.getInt(K_MIN_PORT, 1024),
            maxPort = prefs.getInt(K_MAX_PORT, 65535),
            bindTunnels = prefs.getString(K_BIND_TUNNELS, "") ?: "",
            autoRestart = prefs.getBoolean(K_AUTO_RESTART, true)
        )
        return channel
    }

    fun isOverlayEnabled(context: Context): Boolean =
        sp(context).getBoolean(K_OVERLAY, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(K_OVERLAY, enabled).apply()
    }

    fun isWantRunning(context: Context): Boolean =
        sp(context).getBoolean(K_WANT_RUNNING, false)

    fun setWantRunning(context: Context, running: Boolean) {
        sp(context).edit().putBoolean(K_WANT_RUNNING, running).apply()
    }
}
