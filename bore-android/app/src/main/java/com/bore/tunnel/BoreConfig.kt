package com.bore.tunnel

enum class Mode { LOCAL, SERVER }

/**
 * 与 bore v0.6.0 命令行参数一一对应。
 *
 * local:  bore local <local_port> --to <server> [--port N] [--local-host H] [--secret S]
 * server: bore server [--min-port N] [--max-port N] [--bind-addr A] [--bind-tunnels T] [--secret S]
 */
data class BoreConfig(
    val mode: Mode = Mode.LOCAL,
    val server: String = "bore.pub",
    val localPort: Int = 8080,
    val localHost: String = "localhost",
    val remotePort: Int? = null,
    val secret: String = "",
    val bindAddr: String = "0.0.0.0",
    val minPort: Int = 1024,
    val maxPort: Int = 65535,
    val bindTunnels: String = "",
    val autoRestart: Boolean = true
) {
    fun args(): List<String> = when (mode) {
        Mode.LOCAL -> buildList {
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

        Mode.SERVER -> buildList {
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

    /** 用于界面展示，隐藏密钥内容 */
    fun displayCommand(): String {
        val masked = args().joinToString(" ") { arg ->
            if (arg == secret && secret.isNotBlank()) "***" else arg
        }
        return "bore $masked"
    }

    fun summary(): String = when (mode) {
        Mode.LOCAL -> {
            val host = localHost.trim().ifEmpty { "localhost" }
            val remote = remotePort?.takeIf { it > 0 }?.toString() ?: "随机端口"
            "本地 $host:$localPort → ${server.trim()}:$remote"
        }

        Mode.SERVER -> "服务端 · 端口范围 $minPort-$maxPort · 监听 ${bindAddr.ifBlank { "0.0.0.0" }}"
    }
}
