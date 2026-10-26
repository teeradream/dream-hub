package com.dreamhub.caster

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val DREAM_HUB_URL = "https://teeradream.github.io/dream-hub/"
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var browserWebView: WebView
    private lateinit var hudWebView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var snifferBadgeBtn: TextView
    private lateinit var tvStatusBtn: TextView
    private val navButtons = mutableMapOf<String, TextView>()
    private var currentActiveTab = "dreamhub"

    @Volatile
    private var currentBrowserTitle: String = "DreamHub"
    @Volatile
    private var currentBrowserUrl: String = DREAM_HUB_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        LocalStreamProxyServer.startServer()

        // Start background discovery of Samsung / DLNA TVs on Wi-Fi
        thread {
            DlnaCaster.discoverDevices(applicationContext, 3000)
            runOnUiThread { updateTopBarBadges() }
        }

        setupCyberLayout()
        configureBrowserWebView()
        configureHudWebView()

        VideoSnifferEngine.onVideoDetectedListener = { video, count ->
            runOnUiThread {
                updateTopBarBadges()
                notifyHudWebView("window.onNativeVideoDetected && window.onNativeVideoDetected($count);")
            }
        }

        // Load initial URL (check if launched from external link)
        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrBlank() && (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://"))) {
            browserWebView.loadUrl(incomingUrl)
            switchUiTab("browser")
        } else {
            browserWebView.loadUrl(DREAM_HUB_URL)
            switchUiTab("dreamhub")
        }
    }

    private fun setupCyberLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#06080F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ─── 1. TOP CYBER OMNIBOX BAR ─────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F1C"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        }

        // Back Button
        val backBtn = TextView(this).apply {
            text = "◀"
            setTextColor(Color.parseColor("#00F0FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                if (browserWebView.canGoBack()) browserWebView.goBack()
            }
        }
        topBar.addView(backBtn)

        // URL / Search Input Box
        urlEditText = EditText(this).apply {
            hint = "🔍 พิมพ์ URL เว็บหนัง หรือค้นหา Google (DoH ทะลุบล็อก)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#131B2E"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(8)
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    navigateFromOmniBox(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        topBar.addView(urlEditText)

        // Sniffer Detected Videos Badge Button
        snifferBadgeBtn = TextView(this).apply {
            text = "🎯 0"
            setTextColor(Color.parseColor("#00F0FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#112233"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                switchUiTab("sniffer")
            }
        }
        topBar.addView(snifferBadgeBtn)

        // TV Cast Button
        tvStatusBtn = TextView(this).apply {
            text = "📺 Cast"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF0055"))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
            setOnClickListener {
                switchUiTab("remote")
            }
        }
        topBar.addView(tvStatusBtn)

        root.addView(topBar)

        // Thin Cyber Neon Progress Line
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            progressDrawable.setColorFilter(Color.parseColor("#00F0FF"), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        root.addView(progressBar)

        // ─── 2. CENTER STACK (BROWSER WEBVIEW + CYBER HUD WEBVIEW) ────
        val centerFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        browserWebView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#06080F"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        hudWebView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#06080F"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        centerFrame.addView(browserWebView)
        centerFrame.addView(hudWebView)
        root.addView(centerFrame)

        // ─── 3. BOTTOM CYBER NAVIGATION BAR ───────────────────────────
        val bottomDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        root.addView(bottomDivider)

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#090D18"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        }

        val tabs = listOf(
            Triple("dreamhub", "🎬", "DreamHub"),
            Triple("browser", "🌐", "เบราว์เซอร์"),
            Triple("iptv", "📡", "IPTV สด"),
            Triple("sniffer", "🎯", "ดักวิดีโอ"),
            Triple("remote", "🎛️", "รีโมท/ตั้งค่า")
        )

        for ((key, icon, label) in tabs) {
            val tabView = TextView(this).apply {
                text = "$icon\n$label"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#94A3B8"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { switchUiTab(key) }
            }
            navButtons[key] = tabView
            bottomNav.addView(tabView)
        }

        root.addView(bottomNav)
        setContentView(root)
    }

    private fun switchUiTab(tabKey: String) {
        currentActiveTab = tabKey
        for ((k, btn) in navButtons) {
            if (k == tabKey) {
                btn.setTextColor(Color.parseColor("#00F0FF"))
                btn.typeface = Typeface.DEFAULT_BOLD
                btn.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#132038"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#00F0FF"))
                }
            } else {
                btn.setTextColor(Color.parseColor("#94A3B8"))
                btn.typeface = Typeface.DEFAULT
                btn.background = null
            }
        }

        when (tabKey) {
            "dreamhub" -> {
                hudWebView.visibility = View.GONE
                browserWebView.visibility = View.VISIBLE
                if (!currentBrowserUrl.contains("teeradream.github.io/dream-hub")) {
                    browserWebView.loadUrl(DREAM_HUB_URL)
                }
            }
            "browser" -> {
                hudWebView.visibility = View.GONE
                browserWebView.visibility = View.VISIBLE
                notifyHudWebView("window.showHudSection && window.showHudSection('browser');")
            }
            "iptv", "sniffer", "remote" -> {
                hudWebView.visibility = View.VISIBLE
                browserWebView.visibility = View.GONE
                notifyHudWebView("window.showHudSection && window.showHudSection('$tabKey');")
            }
        }
    }

    private fun navigateFromOmniBox(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        hideKeyboard()
        val targetUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }

        VideoSnifferEngine.clear()
        updateTopBarBadges()
        switchUiTab("browser")
        browserWebView.loadUrl(targetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureBrowserWebView() {
        val settings = browserWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = DEFAULT_UA
        settings.setSupportMultipleWindows(true) // Intercept popups in WebChromeClient
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browserWebView, true)

        browserWebView.addJavascriptInterface(NativeJsBridge(), "NativeBridge")

        browserWebView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Block non-http schemes like intent:// or market:// ads
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }
                if (AdBlocker.shouldBlock(url, currentBrowserUrl)) {
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // 1. Check AdBlocker
                if (AdBlocker.shouldBlock(url, currentBrowserUrl)) {
                    return AdBlocker.emptyResponse()
                }

                // 2. Check Video Sniffer
                VideoSnifferEngine.inspectUrl(
                    url = url,
                    pageTitle = currentBrowserTitle,
                    pageUrl = currentBrowserUrl,
                    headers = request.requestHeaders,
                    defaultUserAgent = DEFAULT_UA
                )

                // 3. If DoH is enabled and this is a main-frame HTML navigation to an external site,
                // we can optionally proxy via DoH if direct DNS fails
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) {
                    currentBrowserUrl = url
                    urlEditText.setText(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentBrowserTitle = view?.title ?: "Web Video"
                if (!url.isNullOrBlank()) {
                    currentBrowserUrl = url
                    urlEditText.setText(url)
                }
                view?.evaluateJavascript(VideoSnifferEngine.JS_SNIFFER_HOOK, null)
                updateTopBarBadges()
            }
        }

        browserWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                if (newProgress > 60) {
                    view?.evaluateJavascript(VideoSnifferEngine.JS_SNIFFER_HOOK, null)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    currentBrowserTitle = title
                }
            }

            // Block popup windows opened by ad scripts
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                return false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureHudWebView() {
        val s = hudWebView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.allowFileAccess = true
        s.allowContentAccess = true

        hudWebView.addJavascriptInterface(NativeJsBridge(), "NativeBridge")
        hudWebView.webChromeClient = WebChromeClient()
        hudWebView.webViewClient = WebViewClient()
        hudWebView.loadUrl("file:///android_asset/cyber_ui.html")
    }

    private fun updateTopBarBadges() {
        val count = VideoSnifferEngine.detectedVideos.size
        snifferBadgeBtn.text = "🎯 $count"
        if (count > 0) {
            snifferBadgeBtn.setTextColor(Color.WHITE)
            snifferBadgeBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF0055"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
        } else {
            snifferBadgeBtn.setTextColor(Color.parseColor("#00F0FF"))
            snifferBadgeBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#112233"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
        }

        val activeTv = DlnaCaster.activeDevice
        if (activeTv != null) {
            tvStatusBtn.text = "📺 ${activeTv.name.take(10)}"
            tvStatusBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#059669"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#00F0FF"))
            }
        } else {
            tvStatusBtn.text = "📺 Cast"
            tvStatusBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF0055"))
                cornerRadius = dp(16).toFloat()
            }
        }
    }

    private fun notifyHudWebView(jsCode: String) {
        if (::hudWebView.isInitialized) {
            hudWebView.evaluateJavascript(jsCode, null)
        }
    }

    private fun startCastForegroundService() {
        try {
            val intent = Intent(this, CastForegroundService::class.java).apply {
                action = CastForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {}
    }

    inner class NativeJsBridge {

        @JavascriptInterface
        fun onJsVideoSniffed(url: String, title: String, pageUrl: String) {
            VideoSnifferEngine.inspectUrl(
                url = url,
                pageTitle = title.ifBlank { currentBrowserTitle },
                pageUrl = pageUrl.ifBlank { currentBrowserUrl },
                headers = mapOf("Referer" to pageUrl),
                defaultUserAgent = DEFAULT_UA
            )
        }

        @JavascriptInterface
        fun getSniffedVideosJson(): String {
            return VideoSnifferEngine.toJsonArray()
        }

        @JavascriptInterface
        fun clearSniffedVideos() {
            VideoSnifferEngine.clear()
            runOnUiThread { updateTopBarBadges() }
        }

        @JavascriptInterface
        fun scanTvDevices(): String {
            DlnaCaster.discoverDevices(applicationContext, 3000)
            runOnUiThread { updateTopBarBadges() }
            return DlnaCaster.devicesToJson()
        }

        @JavascriptInterface
        fun getTvDevicesJson(): String {
            return DlnaCaster.devicesToJson()
        }

        @JavascriptInterface
        fun addManualTvIp(ip: String): String {
            DlnaCaster.probeManualTvIp(ip)
            runOnUiThread { updateTopBarBadges() }
            return DlnaCaster.devicesToJson()
        }

        @JavascriptInterface
        fun selectTvDevice(deviceId: String): Boolean {
            val dev = DlnaCaster.devices[deviceId] ?: return false
            DlnaCaster.activeDevice = dev
            runOnUiThread { updateTopBarBadges() }
            return true
        }

        @JavascriptInterface
        fun castVideo(url: String, title: String, referer: String, useProxy: Boolean): Boolean {
            var dev = DlnaCaster.activeDevice
            if (dev == null) {
                DlnaCaster.discoverDevices(applicationContext, 2200)
                dev = DlnaCaster.activeDevice
            }
            if (dev == null) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "⚠️ ยังไม่ได้เชื่อมต่อทีวี กรุณาสแกนเลือกทีวีก่อน", Toast.LENGTH_LONG).show()
                    switchUiTab("remote")
                }
                return false
            }

            val cookie = try { CookieManager.getInstance().getCookie(url) ?: "" } catch (e: Exception) { "" }
            val finalStreamUrl = if (useProxy) {
                LocalStreamProxyServer.registerStream(
                    url = url,
                    referer = referer.ifBlank { currentBrowserUrl },
                    userAgent = DEFAULT_UA,
                    cookie = cookie,
                    context = applicationContext
                )
            } else {
                url
            }

            val ok = DlnaCaster.castToTv(dev, finalStreamUrl, title.ifBlank { "DreamHub Stream" })
            runOnUiThread {
                startCastForegroundService()
                updateTopBarBadges()
                Toast.makeText(
                    this@MainActivity,
                    "📺 ส่งขึ้น ${dev.name} แล้ว! สามารถล็อกหน้าจอมือถือได้เลย",
                    Toast.LENGTH_LONG
                ).show()
            }
            return ok
        }

        @JavascriptInterface
        fun remotePlayPause(play: Boolean): Boolean {
            return DlnaCaster.playOrPause(play)
        }

        @JavascriptInterface
        fun remoteStop(): Boolean {
            val ok = DlnaCaster.stop()
            try {
                val intent = Intent(this@MainActivity, CastForegroundService::class.java).apply {
                    action = CastForegroundService.ACTION_STOP
                }
                startService(intent)
            } catch (e: Exception) {}
            return ok
        }

        @JavascriptInterface
        fun remoteSeek(seconds: Int): Boolean {
            return DlnaCaster.seekToSeconds(seconds)
        }

        @JavascriptInterface
        fun remoteVolume(vol: Int): Boolean {
            return DlnaCaster.setVolume(vol)
        }

        @JavascriptInterface
        fun getIptvPreset(preset: String): String {
            val channels = when (preset) {
                "thaitv" -> M3uParser.loadAssetPlaylist(applicationContext, "thai_live_tv.m3u8", "ทีวีดิจิทัลไทย")
                "thailand_cable" -> M3uParser.loadAssetPlaylist(applicationContext, "thailand_channels.m3u", "เคเบิลทีวีไทย")
                "dreamhub" -> M3uParser.fetchRemotePlaylist("https://teeradream.github.io/dream-hub/playlist.m3u", "DreamHub ซีรีส์")
                else -> emptyList()
            }
            return M3uParser.channelsToJson(channels)
        }

        @JavascriptInterface
        fun fetchCustomM3uUrl(url: String): String {
            val list = M3uParser.fetchRemotePlaylist(url, "เพลย์ลิสต์กำหนดเอง")
            return M3uParser.channelsToJson(list)
        }

        @JavascriptInterface
        fun parseCustomM3uRaw(rawText: String): String {
            val list = M3uParser.parseM3uContent(rawText, "เพลย์ลิสต์นำเข้า")
            return M3uParser.channelsToJson(list)
        }

        @JavascriptInterface
        fun getSystemStatusJson(): String {
            return JSONObject().apply {
                put("dohEnabled", DoHNetworkClient.isDohEnabled)
                put("dohProvider", DoHNetworkClient.dohProvider)
                put("adBlockEnabled", AdBlocker.isEnabled)
                put("blockedAdsCount", AdBlocker.blockedCount.get())
                put("lanIp", LocalStreamProxyServer.getLocalLanIp(applicationContext))
                put("activeTvName", DlnaCaster.activeDevice?.name ?: "ยังไม่ได้เลือกทีวี")
                put("activeTvIp", DlnaCaster.activeDevice?.ipAddress ?: "-")
                put("currentStreamTitle", DlnaCaster.currentStreamTitle)
                put("currentStreamUrl", DlnaCaster.currentStreamUrl)
                put("isPlaying", DlnaCaster.isPlaying)
                put("sniffedCount", VideoSnifferEngine.detectedVideos.size)
                put("currentBrowserUrl", currentBrowserUrl)
            }.toString()
        }

        @JavascriptInterface
        fun setDohConfig(enabled: Boolean, provider: String) {
            DoHNetworkClient.isDohEnabled = enabled
            DoHNetworkClient.dohProvider = provider
        }

        @JavascriptInterface
        fun setAdBlockEnabled(enabled: Boolean) {
            AdBlocker.isEnabled = enabled
        }

        @JavascriptInterface
        fun openUrlInBrowser(url: String) {
            runOnUiThread {
                navigateFromOmniBox(url)
            }
        }

        @JavascriptInterface
        fun switchTab(tabKey: String) {
            runOnUiThread {
                switchUiTab(tabKey)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlEditText.windowToken, 0)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentActiveTab != "dreamhub" && currentActiveTab != "browser") {
            switchUiTab("browser")
            return
        }
        if (browserWebView.canGoBack()) {
            browserWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
