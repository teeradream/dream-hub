package com.dreamhub.caster

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object DoHNetworkClient {

    @Volatile
    var isDohEnabled: Boolean = true

    @Volatile
    var dohProvider: String = "cloudflare" // "cloudflare", "google", "quad9"

    @Volatile
    var deepWebViewBypass: Boolean = true

    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    private val bootstrapClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    private val cloudflareDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111")
            )
            .includeIPv6(false)
            .build()
    }

    private val googleDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4")
            )
            .includeIPv6(false)
            .build()
    }

    private val quad9Doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.quad9.net/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("9.9.9.9"),
                InetAddress.getByName("149.112.112.112")
            )
            .includeIPv6(false)
            .build()
    }

    val smartDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val cleanHost = hostname.trim().lowercase()
            if (cleanHost == "localhost" ||
                cleanHost.startsWith("192.168.") ||
                cleanHost.startsWith("10.") ||
                cleanHost.startsWith("172.") ||
                cleanHost.startsWith("127.")
            ) {
                return Dns.SYSTEM.lookup(hostname)
            }

            if (!isDohEnabled) {
                return Dns.SYSTEM.lookup(hostname)
            }

            dnsCache[cleanHost]?.let { cached ->
                if (cached.isNotEmpty()) return cached
            }

            val primary = when (dohProvider) {
                "google" -> googleDoh
                "quad9" -> quad9Doh
                else -> cloudflareDoh
            }

            val resolved = try {
                primary.lookup(hostname)
            } catch (e: Exception) {
                try {
                    googleDoh.lookup(hostname)
                } catch (e2: Exception) {
                    try {
                        cloudflareDoh.lookup(hostname)
                    } catch (e3: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            }

            if (resolved.isNotEmpty()) {
                dnsCache[cleanHost] = resolved
            }
            return resolved
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(smartDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    val probeClient: OkHttpClient = OkHttpClient.Builder()
        .dns(smartDns)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    /**
     * Fetches a webpage via DoH + Custom TLS when an ISP blocks standard WebView navigation.
     */
    fun fetchBlockedPageHtml(url: String, userAgent: String, cookie: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "th-TH,th;q=0.9,en-US;q=0.8,en;q=0.7")
                .apply {
                    if (cookie.isNotBlank()) header("Cookie", cookie)
                }
                .build()

            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: return null
                if (body.isBlank()) return null
                // Inject <base href="..."> so all relative scripts, styles, and players resolve properly
                val baseTag = "<base href=\"$url\">"
                if (body.contains("<head>", ignoreCase = true)) {
                    body.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$baseTag")
                } else {
                    "$baseTag\n$body"
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
