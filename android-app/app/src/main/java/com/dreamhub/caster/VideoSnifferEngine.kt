package com.dreamhub.caster

import android.webkit.CookieManager
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class SniffedVideo(
    val id: String,
    val url: String,
    val title: String,
    val pageUrl: String,
    var format: String,
    var badgeText: String,
    var resolution: String,
    var verified: Boolean,
    var isMasterOrPrimary: Boolean,
    var sizeBytes: Long,
    val referer: String,
    val userAgent: String,
    val cookie: String,
    val timestamp: Long
)

object VideoSnifferEngine {

    val detectedVideos = CopyOnWriteArrayList<SniffedVideo>()
    var onVideoDetectedListener: ((SniffedVideo, Int) -> Unit)? = null
    private val probeExecutor = Executors.newFixedThreadPool(3)

    private val videoExtensions = listOf(
        ".m3u8", ".mp4", ".mkv", ".webm", ".mpd", "master.txt", "index.txt", "playlist.txt", "/hls/"
    )

    private val ignoredPatterns = listOf(
        ".ts", ".m4s", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".css", ".js",
        ".woff", ".ttf", ".svg", "vast.xml", "preroll", "ads", "doubleclick", "googlesyndication"
    )

    fun inspectUrl(
        url: String,
        pageTitle: String,
        pageUrl: String,
        headers: Map<String, String>?,
        defaultUserAgent: String
    ) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) return

        val lower = cleanUrl.lowercase()

        // Ignore individual .ts segments or static assets unless it's an m3u8/mp4/master.txt playlist
        if (lower.contains(".ts") && !lower.contains(".m3u8") && !lower.contains("master.txt")) return
        for (ignore in ignoredPatterns) {
            if (lower.endsWith(ignore) || lower.contains("$ignore?")) return
        }

        val isVideo = videoExtensions.any { lower.contains(it) } ||
                lower.contains("dramabox-stream.narto-drama.com") ||
                lower.contains("24playerhd") ||
                lower.contains("mime=video") ||
                lower.contains("type=video")

        if (!isVideo) return

        // Avoid duplicates
        if (detectedVideos.any { it.url == cleanUrl }) return

        val isDisguisedTxt = lower.contains("master.txt") || lower.contains("index.txt") || lower.contains("playlist.txt")
        val initialFormat = when {
            isDisguisedTxt -> "m3u8 (adaptive)"
            lower.contains(".m3u8") || lower.contains("/hls/") -> "m3u8 (HLS)"
            lower.contains(".mp4") || lower.contains("dramabox-stream") -> "mp4 (HD)"
            lower.contains(".mkv") -> "mkv"
            else -> "video stream"
        }

        val initialBadge = when {
            isDisguisedTxt -> "m3u8 (แปลงจากไฟล์ .txt อัตโนมัติ)"
            lower.contains(".m3u8") -> "m3u8 (adaptive)"
            lower.contains(".mp4") -> "mp4 (Direct Stream)"
            else -> initialFormat
        }

        val referer = headers?.get("Referer")
            ?: headers?.get("referer")
            ?: pageUrl.ifEmpty { cleanUrl }

        val ua = headers?.get("User-Agent")
            ?: headers?.get("user-agent")
            ?: defaultUserAgent

        val cookie = try {
            CookieManager.getInstance().getCookie(cleanUrl) ?: ""
        } catch (e: Exception) {
            ""
        }

        val displayTitle = pageTitle.ifBlank {
            cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Video Stream #${detectedVideos.size + 1}" }
        }

        val video = SniffedVideo(
            id = "vid_${System.currentTimeMillis()}_${detectedVideos.size}",
            url = cleanUrl,
            title = displayTitle,
            pageUrl = pageUrl,
            format = initialFormat,
            badgeText = initialBadge,
            resolution = if (isDisguisedTxt) "adaptive" else "HD",
            verified = isDisguisedTxt || lower.contains(".m3u8") || lower.contains(".mp4"),
            isMasterOrPrimary = true,
            sizeBytes = 0L,
            referer = referer,
            userAgent = ua,
            cookie = cookie,
            timestamp = System.currentTimeMillis()
        )

        detectedVideos.add(0, video)
        onVideoDetectedListener?.invoke(video, detectedVideos.size)

        // Probe stream header in background to verify playability on Samsung TV & extract variants
        probeExecutor.execute {
            verifyAndExpandStream(video)
        }
    }

    private fun verifyAndExpandStream(video: SniffedVideo) {
        try {
            val reqBuilder = Request.Builder()
                .url(video.url)
                .header("User-Agent", video.userAgent)
                .header("Range", "bytes=0-8191")
            if (video.referer.isNotBlank()) reqBuilder.header("Referer", video.referer)
            if (video.cookie.isNotBlank()) reqBuilder.header("Cookie", video.cookie)

            DoHNetworkClient.probeClient.newCall(reqBuilder.build()).execute().use { res ->
                if (!res.isSuccessful && res.code != 206) return

                val contentType = (res.header("Content-Type") ?: "").lowercase()
                val contentRange = res.header("Content-Range") ?: ""
                val contentLength = res.header("Content-Length")?.toLongOrNull() ?: 0L

                val bodyBytes = res.body?.bytes() ?: ByteArray(0)
                val headText = String(bodyBytes, 0, minOf(bodyBytes.size, 4096), Charsets.UTF_8)

                // Check if content is an HLS M3U8 playlist (even if URL is master.txt!)
                if (headText.trimStart().startsWith("#EXTM3U") || contentType.contains("mpegurl")) {
                    video.verified = true
                    val isMaster = headText.contains("#EXT-X-STREAM-INF")
                    val wasTxt = video.url.lowercase().contains(".txt")
                    if (isMaster) {
                        video.isMasterOrPrimary = true
                        video.format = "m3u8 (adaptive)"
                        video.resolution = "adaptive"
                        video.badgeText = if (wasTxt) {
                            "m3u8 (แปลงจาก master.txt • เล่นได้ชัวร์)"
                        } else {
                            "m3u8 (adaptive • เล่นได้ชัวร์)"
                        }
                        parseMasterVariants(headText, video)
                    } else {
                        if (video.resolution == "HD") video.resolution = "1080p"
                        video.format = "m3u8 (${video.resolution})"
                        video.badgeText = if (wasTxt) {
                            "m3u8 (${video.resolution} • แปลงจาก .txt)"
                        } else {
                            "m3u8 (${video.resolution})"
                        }
                    }
                    onVideoDetectedListener?.invoke(video, detectedVideos.size)
                    return
                }

                // Check if content is an MP4 / MKV / WebM file
                val isMp4Header = bodyBytes.size >= 12 &&
                        bodyBytes[4] == 'f'.code.toByte() &&
                        bodyBytes[5] == 't'.code.toByte() &&
                        bodyBytes[6] == 'y'.code.toByte() &&
                        bodyBytes[7] == 'p'.code.toByte()

                if (isMp4Header || contentType.contains("video/mp4") || video.url.lowercase().contains(".mp4")) {
                    val totalBytes = if (contentRange.contains("/")) {
                        contentRange.substringAfter("/").trim().toLongOrNull() ?: contentLength
                    } else {
                        contentLength
                    }

                    // Filter out tiny pre-roll ad clips (< 2 MB) if filterShortAdClips is active
                    if (AdBlocker.filterShortAdClips && totalBytes in 1..1_800_000L) {
                        detectedVideos.remove(video)
                        onVideoDetectedListener?.invoke(video, detectedVideos.size)
                        return
                    }

                    video.verified = true
                    video.isMasterOrPrimary = true
                    video.sizeBytes = totalBytes
                    video.resolution = "MP4"
                    val sizeStr = formatFileSize(totalBytes)
                    video.format = if (sizeStr.isNotEmpty()) "mp4 ($sizeStr)" else "mp4 (Direct)"
                    video.badgeText = if (sizeStr.isNotEmpty()) {
                        "mp4 ($sizeStr • รองรับ Samsung TV)"
                    } else {
                        "mp4 (รองรับกรอเวลาบน Samsung TV)"
                    }
                    onVideoDetectedListener?.invoke(video, detectedVideos.size)
                }
            }
        } catch (e: Exception) {
            // Keep initial entry
        }
    }

    private fun parseMasterVariants(m3u8Text: String, parent: SniffedVideo) {
        val baseUri = try { URI(parent.url) } catch (e: Exception) { null }
        val lines = m3u8Text.lines()
        var pendingResolution = ""

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val resMatch = Regex("""RESOLUTION=(\d+x\d+)""", RegexOption.IGNORE_CASE).find(line)
                pendingResolution = resMatch?.groupValues?.getOrNull(1) ?: "Sub-stream"
            } else if (line.isNotEmpty() && !line.startsWith("#") && pendingResolution.isNotEmpty()) {
                val variantUrl = resolveUrl(baseUri, parent.url, line)
                if (detectedVideos.none { it.url == variantUrl }) {
                    val isFullHd = pendingResolution.contains("1920") || pendingResolution.contains("1080") || pendingResolution.contains("1280")
                    val subVideo = SniffedVideo(
                        id = "vid_${System.currentTimeMillis()}_${detectedVideos.size}",
                        url = variantUrl,
                        title = parent.title,
                        pageUrl = parent.pageUrl,
                        format = "m3u8 ($pendingResolution)",
                        badgeText = "m3u8 ($pendingResolution)",
                        resolution = pendingResolution,
                        verified = true,
                        isMasterOrPrimary = isFullHd,
                        sizeBytes = 0L,
                        referer = parent.referer,
                        userAgent = parent.userAgent,
                        cookie = parent.cookie,
                        timestamp = System.currentTimeMillis()
                    )
                    detectedVideos.add(subVideo)
                }
                pendingResolution = ""
            }
        }
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

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }

    fun clear() {
        detectedVideos.clear()
    }

    fun toJsonArray(): String {
        // Sort so verified + isMasterOrPrimary appear at the very top
        val sorted = detectedVideos.sortedWith(
            compareByDescending<SniffedVideo> { it.verified && it.isMasterOrPrimary }
                .thenByDescending { it.verified }
                .thenByDescending { it.timestamp }
        )
        val arr = JSONArray()
        for (v in sorted) {
            val obj = JSONObject().apply {
                put("id", v.id)
                put("url", v.url)
                put("title", v.title)
                put("pageUrl", v.pageUrl)
                put("format", v.format)
                put("badgeText", v.badgeText)
                put("resolution", v.resolution)
                put("verified", v.verified)
                put("isMasterOrPrimary", v.isMasterOrPrimary)
                put("sizeBytes", v.sizeBytes)
                put("referer", v.referer)
                put("userAgent", v.userAgent)
                put("timestamp", v.timestamp)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * Dual-stage JS sniffer injected into webpages:
     * 1. Pre-Play HTML/DOM/Script scanner (finds embedded .m3u8, .mp4, master.txt before Play is even clicked)
     * 2. On-Play XHR / fetch / <video> hook (captures dynamically requested streams immediately upon clicking Play)
     */
    const val JS_SNIFFER_HOOK = """
        (function() {
            if (window.__dhSnifferInjected) return;
            window.__dhSnifferInjected = true;

            var reported = {};
            function reportUrl(u) {
                try {
                    if (!u || typeof u !== 'string') return;
                    if (u.startsWith('blob:') || u.startsWith('data:')) return;
                    var full = new URL(u, window.location.href).href;
                    if (reported[full]) return;
                    var low = full.toLowerCase();
                    if (low.includes('.m3u8') || low.includes('.mp4') || low.includes('master.txt') ||
                        low.includes('index.txt') || low.includes('playlist.txt') ||
                        low.includes('/hls/') || low.includes('24playerhd') || low.includes('dramabox-stream')) {
                        reported[full] = true;
                        if (window.NativeBridge && window.NativeBridge.onJsVideoSniffed) {
                            window.NativeBridge.onJsVideoSniffed(full, document.title || '', window.location.href);
                        }
                    }
                } catch(e) {}
            }

            function scanDomAndScripts() {
                try {
                    var vids = document.querySelectorAll('video, source, iframe');
                    for (var i = 0; i < vids.length; i++) {
                        if (vids[i].src) reportUrl(vids[i].src);
                        if (vids[i].currentSrc) reportUrl(vids[i].currentSrc);
                        var dataSrc = vids[i].getAttribute('data-src') || vids[i].getAttribute('data-url');
                        if (dataSrc) reportUrl(dataSrc);
                    }
                    // Scan inline scripts for direct stream URLs (for sites that embed m3u8/master.txt on load)
                    var scripts = document.querySelectorAll('script:not([src])');
                    var regex = /https?:\/\/[^\s"'<>\\]+(\.m3u8|\.mp4|master\.txt|index\.txt|\/hls\/[^\s"'<>\\]+)/gi;
                    for (var j = 0; j < scripts.length; j++) {
                        var txt = scripts[j].textContent || '';
                        var matches = txt.match(regex);
                        if (matches) {
                            for (var k = 0; k < matches.length; k++) {
                                reportUrl(matches[k].replace(/\\/g, ''));
                            }
                        }
                    }
                } catch(e) {}
            }

            scanDomAndScripts();
            setInterval(scanDomAndScripts, 1500);

            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                reportUrl(url);
                return origOpen.apply(this, arguments);
            };

            if (window.fetch) {
                var origFetch = window.fetch;
                window.fetch = function(input) {
                    try {
                        var u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        reportUrl(u);
                    } catch(e) {}
                    return origFetch.apply(this, arguments);
                };
            }
        })();
    """
}
