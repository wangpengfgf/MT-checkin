package com.bore.tunnel

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "bore_settings"

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
    private const val K_OVERLAY = "overlay_enabled"
    private const val K_WANT_RUNNING = "want_running"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): BoreConfig {
        val p = sp(context)
        return BoreConfig(
            mode = if (p.getString(K_MODE, "LOCAL") == "SERVER") Mode.SERVER else Mode.LOCAL,
            server = p.getString(K_SERVER, "bore.pub") ?: "bore.pub",
            localPort = p.getInt(K_LOCAL_PORT, 8080),
            localHost = p.getString(K_LOCAL_HOST, "localhost") ?: "localhost",
            remotePort = p.getInt(K_REMOTE_PORT, 0).takeIf { it > 0 },
            secret = p.getString(K_SECRET, "") ?: "",
            bindAddr = p.getString(K_BIND_ADDR, "0.0.0.0") ?: "0.0.0.0",
            minPort = p.getInt(K_MIN_PORT, 1024),
            maxPort = p.getInt(K_MAX_PORT, 65535),
            bindTunnels = p.getString(K_BIND_TUNNELS, "") ?: "",
            autoRestart = p.getBoolean(K_AUTO_RESTART, true)
        )
    }

    fun save(context: Context, config: BoreConfig) {
        sp(context).edit()
            .putString(K_MODE, config.mode.name)
            .putString(K_SERVER, config.server)
            .putInt(K_LOCAL_PORT, config.localPort)
            .putString(K_LOCAL_HOST, config.localHost)
            .putInt(K_REMOTE_PORT, config.remotePort ?: 0)
            .putString(K_SECRET, config.secret)
            .putString(K_BIND_ADDR, config.bindAddr)
            .putInt(K_MIN_PORT, config.minPort)
            .putInt(K_MAX_PORT, config.maxPort)
            .putString(K_BIND_TUNNELS, config.bindTunnels)
            .putBoolean(K_AUTO_RESTART, config.autoRestart)
            .apply()
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
