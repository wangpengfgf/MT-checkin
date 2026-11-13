package com.bore.tunnel

import org.json.JSONObject
import java.util.UUID

enum class ChannelType { LOCAL, SERVER }

enum class ChannelStatus { IDLE, CONNECTING, RUNNING, ERROR }

/**
 * 一个通道对应一个独立的 bore 进程，可以同时转发多个本地服务。
 */
data class Channel(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: ChannelType = ChannelType.LOCAL,
    val enabled: Boolean = true,
    val server: String = "bore.pub",
    val localHost: String = "localhost",
    val localPort: Int = 8080,
    val remotePort: Int? = null,
    val secret: String = "",
    val bindAddr: String = "0.0.0.0",
    val minPort: Int = 1024,
    val maxPort: Int = 65535,
    val bindTunnels: String = "",
    val autoRestart: Boolean = true
) {
    val displayName: String
        get() = name.ifBlank {
            if (type == ChannelType.LOCAL) "本地 $localPort" else "服务端"
        }

    fun args(): List<String> = when (type) {
        ChannelType.LOCAL -> buildList {
            add("local")
            add(localPort.toString())
            add("--to")
            add(server.trim())
            if (remotePort != null && remotePort > 0) {
                add("--port")
                add(remotePort.toString())
            }
            val host = localHost.trim()
            if (host.isNotEmpty() && host != "localhost") {
                add("--local-host")
                add(host)
            }
            if (secret.isNotBlank()) {
                add("--secret")
                add(secret)
            }
        }

        ChannelType.SERVER -> buildList {
            add("server")
            add("--min-port")
            add(minPort.toString())
            add("--max-port")
            add(maxPort.toString())
            add("--bind-addr")
            add(bindAddr.ifBlank { "0.0.0.0" })
            if (bindTunnels.isNotBlank()) {
                add("--bind-tunnels")
                add(bindTunnels.trim())
            }
            if (secret.isNotBlank()) {
                add("--secret")
                add(secret)
            }
        }
    }

    fun displayCommand(): String {
        val masked = args().joinToString(" ") { arg ->
            if (arg == secret && secret.isNotBlank()) "***" else arg
        }
        return "bore $masked"
    }

    fun summary(): String = when (type) {
        ChannelType.LOCAL -> {
            val host = localHost.trim().ifEmpty { "localhost" }
            val remote = remotePort?.takeIf { it > 0 }?.toString() ?: "随机端口"
            "$host:$localPort → ${server.trim()}:$remote"
        }

        ChannelType.SERVER -> "服务端 · $minPort-$maxPort · ${bindAddr.ifBlank { "0.0.0.0" }}"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("enabled", enabled)
        put("server", server)
        put("localHost", localHost)
        put("localPort", localPort)
        put("remotePort", remotePort ?: 0)
        put("secret", secret)
        put("bindAddr", bindAddr)
        put("minPort", minPort)
        put("maxPort", maxPort)
        put("bindTunnels", bindTunnels)
        put("autoRestart", autoRestart)
    }

    fun state(
        status: ChannelStatus = ChannelStatus.IDLE,
        message: String = if (enabled) "未启动" else "已禁用",
        url: String = "",
        startedAt: Long = 0L,
        attempts: Int = 0
    ): ChannelState = ChannelState(
        id = id,
        name = displayName,
        summary = summary(),
        status = status,
        message = message,
        url = url,
        startedAt = startedAt,
        attempts = attempts
    )

    companion object {
        fun fromJson(json: JSONObject): Channel = Channel(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name", ""),
            type = if (json.optString("type", "LOCAL") == "SERVER") ChannelType.SERVER else ChannelType.LOCAL,
            enabled = json.optBoolean("enabled", true),
            server = json.optString("server", "bore.pub"),
            localHost = json.optString("localHost", "localhost"),
            localPort = json.optInt("localPort", 8080),
            remotePort = json.optInt("remotePort", 0).takeIf { it > 0 },
            secret = json.optString("secret", ""),
            bindAddr = json.optString("bindAddr", "0.0.0.0"),
            minPort = json.optInt("minPort", 1024),
            maxPort = json.optInt("maxPort", 65535),
            bindTunnels = json.optString("bindTunnels", ""),
            autoRestart = json.optBoolean("autoRestart", true)
        )
    }
}

data class ChannelState(
    val id: String,
    val name: String,
    val summary: String,
    val status: ChannelStatus = ChannelStatus.IDLE,
    val message: String = "未启动",
    val url: String = "",
    val startedAt: Long = 0L,
    val attempts: Int = 0
)
