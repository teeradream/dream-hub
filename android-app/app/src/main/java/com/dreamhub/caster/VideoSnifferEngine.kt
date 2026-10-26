package com.dreamhub.caster

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

data class SniffedVideo(
    val id: String,
    val url: String,
    val title: String,
    val pageUrl: String,
    val format: String, // "HLS (M3U8)", "MP4", "MKV", "WEBM", "STREAM"
    val referer: String,
    val userAgent: String,
    val cookie: String,
    val timestamp: Long
)

object VideoSnifferEngine {

    val detectedVideos = CopyOnWriteArrayList<SniffedVideo>()
    var onVideoDetectedListener: ((SniffedVideo, Int) -> Unit)? = null

    private val videoExtensions = listOf(
        ".m3u8", ".mp4", ".mkv", ".webm", ".mpd", "master.txt", "/hls/"
    )

    private val ignoredPatterns = listOf(
        ".ts", ".m4s", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".css", ".js",
        ".woff", ".ttf", ".svg", "vast.xml", "preroll", "ads"
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

        // Ignore individual .ts segments or static assets unless it's an m3u8/mp4 playlist
        if (lower.contains(".ts") && !lower.contains(".m3u8")) return
        for (ignore in ignoredPatterns) {
            if (lower.endsWith(ignore) || lower.contains("$ignore?")) return
        }

        val isVideo = videoExtensions.any { lower.contains(it) } ||
                lower.contains("dramabox-stream.narto-drama.com") ||
                lower.contains("mime=video") ||
                lower.contains("type=video")

        if (!isVideo) return

        // Avoid duplicates
        if (detectedVideos.any { it.url == cleanUrl }) return

        val format = when {
            lower.contains(".m3u8") || lower.contains("master.txt") || lower.contains("/hls/") -> "HLS (M3U8)"
            lower.contains(".mp4") || lower.contains("dramabox-stream") -> "MP4 HD"
            lower.contains(".mkv") -> "MKV"
            lower.contains(".mpd") -> "DASH (MPD)"
            else -> "VIDEO STREAM"
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
            format = format,
            referer = referer,
            userAgent = ua,
            cookie = cookie,
            timestamp = System.currentTimeMillis()
        )

        detectedVideos.add(0, video) // Newest first
        onVideoDetectedListener?.invoke(video, detectedVideos.size)
    }

    fun clear() {
        detectedVideos.clear()
    }

    fun toJsonArray(): String {
        val arr = JSONArray()
        for (v in detectedVideos) {
            val obj = JSONObject().apply {
                put("id", v.id)
                put("url", v.url)
                put("title", v.title)
                put("pageUrl", v.pageUrl)
                put("format", v.format)
                put("referer", v.referer)
                put("userAgent", v.userAgent)
                put("timestamp", v.timestamp)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * JavaScript snippet injected into every webpage to hook <video>, fetch(), and XMLHttpRequest
     * so even dynamically generated stream URLs are captured immediately!
     */
    const val JS_SNIFFER_HOOK = """
        (function() {
            if (window.__dhSnifferInjected) return;
            window.__dhSnifferInjected = true;

            function reportUrl(u) {
                try {
                    if (!u || typeof u !== 'string') return;
                    if (u.startsWith('blob:')) return;
                    var full = new URL(u, window.location.href).href;
                    var low = full.toLowerCase();
                    if (low.includes('.m3u8') || low.includes('.mp4') || low.includes('master.txt') || low.includes('/hls/') || low.includes('dramabox-stream')) {
                        if (window.NativeBridge && window.NativeBridge.onJsVideoSniffed) {
                            window.NativeBridge.onJsVideoSniffed(full, document.title || '', window.location.href);
                        }
                    }
                } catch(e) {}
            }

            // Scan <video> and <source> elements periodically
            setInterval(function() {
                try {
                    var vids = document.querySelectorAll('video, source');
                    for (var i = 0; i < vids.length; i++) {
                        if (vids[i].src) reportUrl(vids[i].src);
                        if (vids[i].currentSrc) reportUrl(vids[i].currentSrc);
                    }
                } catch(e) {}
            }, 1500);

            // Hook XMLHttpRequest
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                reportUrl(url);
                return origOpen.apply(this, arguments);
            };

            // Hook fetch
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
