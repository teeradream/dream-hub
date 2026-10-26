package com.dreamhub.caster

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

object AdBlocker {

    @Volatile
    var isEnabled: Boolean = true

    val blockedCount = AtomicInteger(0)

    private val adDomains = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google",
        "adsok.co",
        "histats.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "clickadu.com",
        "hilltopads.net",
        "ibit.ly",
        "bit.ly/sbo",
        "ufabet",
        "sbobet",
        "betflix",
        "pgslot",
        "joker123",
        "casino"
    )

    private val adPathKeywords = listOf(
        "/ads/",
        "/adserver",
        "/popunder",
        "/popup.js",
        "vipx-frontend",
        "advertisement.js",
        "vast.js"
    )

    fun shouldBlock(url: String, pageHost: String?): Boolean {
        if (!isEnabled) return false
        val lower = url.lowercase()

        // Never block video streams or CDNs
        if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".ts") ||
            lower.contains("mvshd.com") || lower.contains("narto-drama") ||
            lower.contains("crazymaplestudios") || lower.contains("teeradream.github.io")
        ) {
            return false
        }

        for (domain in adDomains) {
            if (lower.contains(domain)) {
                blockedCount.incrementAndGet()
                return true
            }
        }

        for (kw in adPathKeywords) {
            if (lower.contains(kw)) {
                blockedCount.incrementAndGet()
                return true
            }
        }

        return false
    }

    fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
