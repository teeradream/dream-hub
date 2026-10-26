package com.dreamhub.caster

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class ProxySessionConfig(
    val targetUrl: String,
    val referer: String,
    val userAgent: String,
    val cookie: String
)

object LocalStreamProxyServer {

    private const val PORT = 8765
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, ProxySessionConfig>()

    fun startServer() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(PORT)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                isRunning = false
            }
        }
    }

    fun registerStream(url: String, referer: String, userAgent: String, cookie: String, context: Context): String {
        startServer()
        val id = "s_${System.currentTimeMillis()}"
        sessions[id] = ProxySessionConfig(url, referer, userAgent, cookie)
        val lanIp = getLocalLanIp(context)
        val ext = if (url.lowercase().contains(".m3u8") || url.lowercase().contains("master.txt") || url.lowercase().contains("/hls/")) {
            "stream.m3u8"
        } else {
            "stream.mp4"
        }
        return "http://$lanIp:$PORT/$ext?sid=$id"
    }

    fun getLocalLanIp(context: Context): String {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {}
        return "127.0.0.1"
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val fullPath = parts[1]

                var rangeHeader: String? = null
                var hostHeader: String? = null
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val l = line!!
                    if (l.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = l.substringAfter(":").trim()
                    } else if (l.startsWith("Host:", ignoreCase = true)) {
                        hostHeader = l.substringAfter(":").trim()
                    }
                }

                val out = s.getOutputStream()

                if (method == "OPTIONS") {
                    val resp = "HTTP/1.1 200 OK\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: *\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    out.write(resp.toByteArray())
                    out.flush()
                    return
                }

                val query = fullPath.substringAfter("?", "")
                val params = parseQuery(query)
                val sid = params["sid"]
                val u64 = params["u"]

                val config: ProxySessionConfig
                val targetUrl: String

                if (u64 != null && sid != null && sessions.containsKey(sid)) {
                    val baseCfg = sessions[sid]!!
                    targetUrl = String(Base64.decode(u64, Base64.URL_SAFE or Base64.NO_WRAP))
                    config = baseCfg.copy(targetUrl = targetUrl)
                } else if (sid != null && sessions.containsKey(sid)) {
                    config = sessions[sid]!!
                    targetUrl = config.targetUrl
                } else {
                    sendError(out, 404, "Stream session not found")
                    return
                }

                val reqBuilder = Request.Builder().url(targetUrl)
                if (config.referer.isNotBlank()) reqBuilder.header("Referer", config.referer)
                if (config.userAgent.isNotBlank()) reqBuilder.header("User-Agent", config.userAgent)
                if (config.cookie.isNotBlank()) reqBuilder.header("Cookie", config.cookie)
                if (rangeHeader != null) reqBuilder.header("Range", rangeHeader)

                DoHNetworkClient.client.newCall(reqBuilder.build()).execute().use { upstream ->
                    val code = upstream.code
                    val contentType = upstream.header("Content-Type") ?: ""
                    val isM3u8 = targetUrl.lowercase().contains(".m3u8") ||
                            targetUrl.lowercase().contains("master.txt") ||
                            contentType.contains("mpegurl", ignoreCase = true)

                    if (isM3u8 && upstream.body != null) {
                        val rawPlaylist = upstream.body!!.string()
                        val proxyBase = "http://${hostHeader ?: "127.0.0.1:$PORT"}"
                        val rewritten = rewriteM3u8(rawPlaylist, targetUrl, proxyBase, sid ?: "")
                        val bytes = rewritten.toByteArray(Charsets.UTF_8)

                        val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        if (method != "HEAD") {
                            out.write(bytes)
                        }
                        out.flush()
                    } else {
                        val statusText = if (code == 206) "206 Partial Content" else "$code OK"
                        val sb = StringBuilder()
                        sb.append("HTTP/1.1 $statusText\r\n")
                        sb.append("Access-Control-Allow-Origin: *\r\n")
                        val cType = when {
                            targetUrl.lowercase().contains(".ts") -> "video/mp2t"
                            contentType.isNotBlank() -> contentType
                            else -> "video/mp4"
                        }
                        sb.append("Content-Type: $cType\r\n")
                        upstream.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
                        upstream.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
                        sb.append("Accept-Ranges: bytes\r\n")
                        sb.append("Connection: close\r\n\r\n")
                        out.write(sb.toString().toByteArray())

                        if (method != "HEAD" && upstream.body != null) {
                            val inStream = upstream.body!!.byteStream()
                            val buf = ByteArray(16384)
                            var read: Int
                            while (inStream.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                            }
                        }
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                // Client disconnected or upstream timeout
            }
        }
    }

    private fun rewriteM3u8(raw: String, baseUrl: String, proxyBase: String, sid: String): String {
        val baseUri = try { URI(baseUrl) } catch (e: Exception) { null }
        val sb = StringBuilder()

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                sb.append("\n")
                continue
            }
            if (line.startsWith("#")) {
                // Also rewrite URI="..." inside #EXT-X-KEY or #EXT-X-MAP if present
                if (line.contains("URI=\"")) {
                    val rewrittenTag = Regex("""URI="([^"]+)"""").replace(line) { m ->
                        val abs = resolveUrl(baseUri, baseUrl, m.groupValues[1])
                        val b64 = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                        """URI="$proxyBase/seg?sid=$sid&u=$b64""""
                    }
                    sb.append(rewrittenTag).append("\n")
                } else {
                    sb.append(line).append("\n")
                }
            } else {
                val absUrl = resolveUrl(baseUri, baseUrl, line)
                val b64 = Base64.encodeToString(absUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                val pathName = if (absUrl.lowercase().contains(".m3u8")) "sub.m3u8" else "seg.ts"
                sb.append("$proxyBase/$pathName?sid=$sid&u=$b64\n")
            }
        }
        return sb.toString()
    }

    private fun resolveUrl(baseUri: URI?, baseUrl: String, relativeOrAbs: String): String {
        if (relativeOrAbs.startsWith("http://") || relativeOrAbs.startsWith("https://")) {
            return relativeOrAbs
        }
        return try {
            baseUri?.resolve(relativeOrAbs)?.toString()
                ?: (baseUrl.substringBeforeLast("/") + "/" + relativeOrAbs.trimStart('/'))
        } catch (e: Exception) {
            baseUrl.substringBeforeLast("/") + "/" + relativeOrAbs.trimStart('/')
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        val resp = "HTTP/1.1 $code $msg\r\nContent-Type: text/plain\r\nContent-Length: ${msg.length}\r\n\r\n$msg"
        out.write(resp.toByteArray())
        out.flush()
    }
}
