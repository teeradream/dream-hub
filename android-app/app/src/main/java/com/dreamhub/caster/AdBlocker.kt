package com.dreamhub.caster

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

object AdBlocker {

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var blockBanners: Boolean = true

    @Volatile
    var blockPopups: Boolean = true

    @Volatile
    var filterShortAdClips: Boolean = true

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
        "casino",
        "slotxo",
        "1xbet",
        "dafabet",
        "w88",
        "fun88",
        "b2y",
        "syndication.realsrv.com",
        "onclickads.net",
        "acint.net",
        "yandex.ru/ads"
    )

    private val adPathKeywords = listOf(
        "/ads/",
        "/adserver",
        "/popunder",
        "/popup.js",
        "vipx-frontend",
        "advertisement.js",
        "vast.js",
        "preroll",
        "banner_ad",
        "floating-ads"
    )

    private val ispBlockRedirects = listOf(
        "mdes.go.th",
        "tcsd.go.th",
        "blocked.trueinternet",
        "3bb.co.th/block",
        "ais.th/block"
    )

    fun isIspBlockRedirect(url: String): Boolean {
        val lower = url.lowercase()
        return ispBlockRedirects.any { lower.contains(it) }
    }

    fun shouldBlockNavigation(targetUrl: String, currentPageUrl: String): Boolean {
        if (!isEnabled) return false
        val lower = targetUrl.lowercase()

        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            blockedCount.incrementAndGet()
            return true
        }

        if (shouldBlock(targetUrl, currentPageUrl)) {
            return true
        }

        if (blockPopups && currentPageUrl.isNotBlank()) {
            try {
                val curHost = Uri.parse(currentPageUrl).host?.lowercase() ?: ""
                val newHost = Uri.parse(targetUrl).host?.lowercase() ?: ""
                if (curHost.isNotBlank() && newHost.isNotBlank() && curHost != newHost) {
                    // Block known shopping/gambling redirect domains triggered on play click
                    if (newHost.contains("shopee") || newHost.contains("lazada") ||
                        newHost.contains("line.me") || newHost.contains("t.me") ||
                        adDomains.any { newHost.contains(it) }
                    ) {
                        blockedCount.incrementAndGet()
                        return true
                    }
                }
            } catch (e: Exception) {}
        }

        return false
    }

    fun shouldBlock(url: String, pageHost: String?): Boolean {
        if (!isEnabled || !blockBanners) return false
        val lower = url.lowercase()

        // Never block legitimate video streams or CDNs unless it's a known ad clip
        if ((lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains("master.txt") || lower.contains(".ts")) &&
            !lower.contains("preroll") && !lower.contains("vast") && !lower.contains("/ads/")
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

    /**
     * Injected into movie websites to hide gambling GIF banners & remove transparent click-hijack overlays
     * placed over video players.
     */
    const val JS_COSMETIC_AD_SHIELD = """
        (function() {
            if (window.__dhAdShieldApplied) return;
            window.__dhAdShieldApplied = true;

            // Override window.open to block pop-under tabs
            window.open = function() { return null; };

            var style = document.createElement('style');
            style.innerHTML = `
                a[href*="ufabet"], a[href*="betflix"], a[href*="pgslot"], a[href*="casino"],
                a[href*="line.me/R/ti"], a[href*="bit.ly/"], a[href*="ibit.ly"],
                div[class*="ads-"], div[id*="ads-"], div[class*="banner-ad"],
                .floating-ads, .sticky-ads, #floatads, .ad-overlay {
                    display: none !important;
                    visibility: hidden !important;
                    pointer-events: none !important;
                    height: 0 !important;
                }
            `;
            if (document.head) document.head.appendChild(style);
        })();
    """
}
