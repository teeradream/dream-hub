package com.dreamhub.caster

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import okhttp3.Request
import java.io.BufferedInputStream
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
    val cookie: String,
    val forceHls: Boolean = false
)

object LocalStreamProxyServer {

    const val PORT = 8765
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, ProxySessionConfig>()

    @Volatile
    var latestSessionId: String = ""

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

    fun registerStream(
        url: String,
        referer: String,
        userAgent: String,
        cookie: String,
        context: Context
    ): String {
        startServer()
        val id = "s_${System.currentTimeMillis()}"
        val lower = url.lowercase()
        val isHlsLike = lower.contains(".m3u8") ||
                lower.contains("master.txt") ||
                lower.contains("index.txt") ||
                lower.contains("playlist.txt") ||
                lower.contains("/hls/")

        sessions[id] = ProxySessionConfig(
            targetUrl = url,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            forceHls = isHlsLike
        )
        latestSessionId = id

        val lanIp = getLocalLanIp(context)
        val ext = if (isHlsLike) "stream.m3u8" else "stream.mp4"
        return "http://$lanIp:$PORT/$ext?sid=$id"
    }

    fun registerLocalInAppStream(
        url: String,
        referer: String,
        userAgent: String,
        cookie: String
    ): String {
        startServer()
        val id = "s_${System.currentTimeMillis()}"
        val lower = url.lowercase()
        val isMp4 = lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".webm")
        val isHlsLike = !isMp4
        sessions[id] = ProxySessionConfig(
            targetUrl = url,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            forceHls = isHlsLike
        )
        latestSessionId = id
        val ext = if (isHlsLike) "stream.m3u8" else "stream.mp4"
        return "http://127.0.0.1:$PORT/$ext?sid=$id"
    }

    fun getSubtitleUrl(context: Context, format: String = "srt"): String {
        val lanIp = getLocalLanIp(context)
        return "http://$lanIp:$PORT/subtitle.$format?t=${System.currentTimeMillis()}"
    }

    fun getWebReceiverUrl(context: Context): String {
        val lanIp = getLocalLanIp(context)
        return "http://$lanIp:$PORT/tv"
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
                s.soTimeout = 35000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val fullPath = parts[1]
                val pathOnly = fullPath.substringBefore("?")

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

                // 1. Subtitle endpoints (/subtitle.srt & /subtitle.vtt)
                if (pathOnly.endsWith("/subtitle.srt")) {
                    val srtBytes = SubtitleEngine.getFormattedSrt().toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/srt; charset=utf-8\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Length: ${srtBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(header.toByteArray())
                    if (method != "HEAD") out.write(srtBytes)
                    out.flush()
                    return
                }

                if (pathOnly.endsWith("/subtitle.vtt")) {
                    val vttBytes = SubtitleEngine.getFormattedVtt().toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/vtt; charset=utf-8\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Length: ${vttBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(header.toByteArray())
                    if (method != "HEAD") out.write(vttBytes)
                    out.flush()
                    return
                }

                // 2. Smart TV Web Browser Receiver (/tv)
                if (pathOnly == "/tv") {
                    val html = buildWebReceiverHtml(hostHeader ?: "127.0.0.1:$PORT")
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(header.toByteArray())
                    if (method != "HEAD") out.write(bytes)
                    out.flush()
                    return
                }

                val query = fullPath.substringAfter("?", "")
                val params = parseQuery(query)
                val sid = params["sid"] ?: latestSessionId
                val u64 = params["u"]

                val config: ProxySessionConfig
                val targetUrl: String

                if (u64 != null && sessions.containsKey(sid)) {
                    val baseCfg = sessions[sid]!!
                    targetUrl = String(Base64.decode(u64, Base64.URL_SAFE or Base64.NO_WRAP))
                    config = baseCfg.copy(targetUrl = targetUrl)
                } else if (sessions.containsKey(sid)) {
                    config = sessions[sid]!!
                    targetUrl = config.targetUrl
                } else {
                    sendError(out, 404, "Stream session not found")
                    return
                }

                val isPlaylistEndpoint = pathOnly.endsWith(".m3u8")
                val isSegmentEndpoint = pathOnly.endsWith(".ts")

                // Build upstream request
                val reqBuilder = Request.Builder().url(targetUrl)
                if (config.referer.isNotBlank()) reqBuilder.header("Referer", config.referer)
                if (config.userAgent.isNotBlank()) reqBuilder.header("User-Agent", config.userAgent)
                if (config.cookie.isNotBlank()) reqBuilder.header("Cookie", config.cookie)

                // If Samsung TV sends a HEAD request on an MP4 stream, probe with bytes=0-0 if needed
                if (method == "HEAD" && !isPlaylistEndpoint) {
                    reqBuilder.head()
                } else if (rangeHeader != null && !isPlaylistEndpoint) {
                    reqBuilder.header("Range", rangeHeader)
                }

                DoHNetworkClient.client.newCall(reqBuilder.build()).execute().use { upstream ->
                    val code = upstream.code
                    val contentType = upstream.header("Content-Type") ?: ""

                    // Peek first bytes if not a HEAD request to detect #EXTM3U in master.txt / index.txt
                    val bodyStream = upstream.body?.byteStream()?.let { BufferedInputStream(it, 16384) }
                    var startsWithExtM3u = false
                    if (bodyStream != null && method != "HEAD" && !isSegmentEndpoint) {
                        bodyStream.mark(64)
                        val peekBuf = ByteArray(32)
                        val peekRead = bodyStream.read(peekBuf)
                        bodyStream.reset()
                        if (peekRead > 0) {
                            val peekStr = String(peekBuf, 0, peekRead, Charsets.UTF_8).trimStart()
                            startsWithExtM3u = peekStr.startsWith("#EXTM3U")
                        }
                    }

                    val treatAsM3u8 = startsWithExtM3u ||
                            isPlaylistEndpoint ||
                            targetUrl.lowercase().contains(".m3u8") ||
                            targetUrl.lowercase().contains("master.txt") ||
                            targetUrl.lowercase().contains("index.txt") ||
                            contentType.contains("mpegurl", ignoreCase = true)

                    if (treatAsM3u8) {
                        if (method == "HEAD") {
                            val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "transferMode.dlna.org: Streaming\r\n" +
                                    "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray())
                            out.flush()
                            return
                        }

                        val rawPlaylist = bodyStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                        val proxyBase = "http://${hostHeader ?: "127.0.0.1:$PORT"}"
                        val rewritten = rewriteM3u8(rawPlaylist, targetUrl, proxyBase, sid)
                        val bytes = rewritten.toByteArray(Charsets.UTF_8)

                        val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "transferMode.dlna.org: Streaming\r\n" +
                                "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(bytes)
                        out.flush()
                    } else {
                        val statusText = if (code == 206) "206 Partial Content" else "200 OK"
                        val sb = StringBuilder()
                        sb.append("HTTP/1.1 $statusText\r\n")
                        sb.append("Access-Control-Allow-Origin: *\r\n")
                        val cType = when {
                            isSegmentEndpoint || targetUrl.lowercase().contains(".ts") -> "video/mp2t"
                            contentType.startsWith("video/", ignoreCase = true) -> contentType
                            else -> "video/mp4"
                        }
                        sb.append("Content-Type: $cType\r\n")
                        upstream.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
                        upstream.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
                        sb.append("Accept-Ranges: bytes\r\n")
                        sb.append("transferMode.dlna.org: Streaming\r\n")
                        sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                        sb.append("Connection: close\r\n\r\n")
                        out.write(sb.toString().toByteArray())

                        if (method != "HEAD" && bodyStream != null) {
                            val buf = ByteArray(32768)
                            var read: Int
                            while (bodyStream.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                            }
                        }
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                // Client closed socket or timeout
            }
        }
    }

    /**
     * Rewrites all URLs inside an M3U8 playlist (including disguised master.txt & sub-playlists).
     */
    private fun rewriteM3u8(raw: String, baseUrl: String, proxyBase: String, sid: String): String {
        val baseUri = try { URI(baseUrl) } catch (e: Exception) { null }
        val sb = StringBuilder()
        var expectSubPlaylistOnNextLine = false

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                sb.append("\n")
                continue
            }
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    expectSubPlaylistOnNextLine = true
                }
                if (line.contains("URI=\"")) {
                    val rewrittenTag = Regex("""URI="([^"]+)"""").replace(line) { m ->
                        val abs = resolveUrl(baseUri, baseUrl, m.groupValues[1])
                        val b64 = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                        val isSub = line.startsWith("#EXT-X-MEDIA", ignoreCase = true) || abs.lowercase().contains(".m3u8")
                        val endp = if (isSub) "sub.m3u8" else "seg.ts"
                        """URI="$proxyBase/$endp?sid=$sid&u=$b64""""
                    }
                    sb.append(rewrittenTag).append("\n")
                } else {
                    sb.append(line).append("\n")
                }
            } else {
                val absUrl = resolveUrl(baseUri, baseUrl, line)
                val b64 = Base64.encodeToString(absUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                val lowerAbs = absUrl.lowercase()
                val isSubPlaylist = expectSubPlaylistOnNextLine ||
                        lowerAbs.contains(".m3u8") ||
                        lowerAbs.contains("master.txt") ||
                        lowerAbs.contains("index.txt") ||
                        lowerAbs.contains("playlist.txt") ||
                        lowerAbs.contains("chunklist")
                expectSubPlaylistOnNextLine = false

                val pathName = if (isSubPlaylist) "sub.m3u8" else "seg.ts"
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

    private fun buildWebReceiverHtml(hostPort: String): String {
        val cfg = sessions[latestSessionId]
        val isHls = cfg?.forceHls ?: true
        val streamPath = if (isHls) "stream.m3u8" else "stream.mp4"
        val streamUrl = "http://$hostPort/$streamPath?sid=$latestSessionId"
        val vttUrl = "http://$hostPort/subtitle.vtt?t=${System.currentTimeMillis()}"
        return """<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>DreamHub TV Receiver</title>
  <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
  <style>
    body { margin: 0; background: #000; overflow: hidden; display: flex; align-items: center; justify-content: center; height: 100vh; }
    video { width: 100vw; height: 100vh; object-fit: contain; }
    video::cue { background: rgba(0,0,0,0.65); color: #fff; font-size: 1.4em; }
  </style>
</head>
<body>
  <video id="v" controls autoplay crossorigin="anonymous">
    <track kind="subtitles" label="Thai / Custom" srclang="th" src="$vttUrl" default>
  </video>
  <script>
    var video = document.getElementById('v');
    var src = "$streamUrl";
    if (src.indexOf('.m3u8') !== -1 && window.Hls && Hls.isSupported()) {
      var hls = new Hls();
      hls.loadSource(src);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, function() { video.play(); });
    } else {
      video.src = src;
      video.play();
    }
  </script>
</body>
</html>"""
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
