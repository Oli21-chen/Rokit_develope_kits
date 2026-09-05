package com.rokid.glassesbaredevsample.link

/**
 * Maps low-level socket errors to short glasses-HUD hints.
 */
object MouseLinkErrors {
    fun format(raw: String?, host: String, port: Int): String {
        val message = raw?.trim().orEmpty().ifEmpty { "unknown" }
        return when {
            message.contains("ENETUNREACH", ignoreCase = true) ->
                "网络不可达：眼镜需连与笔记本同一 Wi‑Fi；link_host 用笔记本 IP（如 192.168.0.100），不要用路由器 .1"
            message.contains("EHOSTUNREACH", ignoreCase = true) ->
                "主机不可达：确认笔记本 IP=$host 且 mouse_agent 在 $port 监听"
            message.contains("ECONNREFUSED", ignoreCase = true) ->
                "连接被拒：在笔记本启动 mouse_agent.py --port $port"
            message.contains("Network is unreachable", ignoreCase = true) ->
                "网络不可达：先在眼镜上连接 Wi‑Fi，再重试"
            host.endsWith(".1") ->
                "$message（提示：$host 多为路由器，请改为笔记本局域网 IP）"
            else -> message
        }
    }
}
