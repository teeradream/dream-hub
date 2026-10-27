package com.dreamhub.caster

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

data class BrowserTabItem(
    val id: String,
    var title: String,
    var url: String
)

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME_URI = "about:home"
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val REQ_PICK_M3U = 2001
        const val REQ_PICK_SRT = 2002
    }

    private lateinit var topBarContainer: LinearLayout
    private lateinit var miniPlayerBar: LinearLayout
    private lateinit var miniPlayerTitle: TextView
    private lateinit var browserWebView: WebView
    private lateinit var hudWebView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var yellowPlayBadgeView: YellowPlayBadgeView
    private lateinit var tabsCountBoxView: TabsCountBoxView
    private lateinit var castIconView: VectorIconView

    private val openTabs = mutableListOf<BrowserTabItem>()
    private var activeTabId: String = ""

    @Volatile
    private var currentBrowserTitle: String = "หน้าเริ่มต้น"
    @Volatile
    private var currentBrowserUrl: String = HOME_URI
    @Volatile
    private var currentHudScreen: String = "home"
    @Volatile
    private var isVideoOrCastActive: Boolean = false
    private var bypassedUrlsInSession = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        LocalStreamProxyServer.startServer()

        // Initial tab starts on Start Page (Most Visited & Bookmarks — NOT Dream Hub)
        val firstTab = BrowserTabItem(
            id = "tab_${System.currentTimeMillis()}",
            title = "หน้าเริ่มต้น",
            url = HOME_URI
        )
        openTabs.add(firstTab)
        activeTabId = firstTab.id

        thread {
            DlnaCaster.discoverDevices(applicationContext, 2800)
            runOnUiThread { updateTopBarState() }
        }

        setupLiquidGlassLayout()
        configureBrowserWebView()
        configureHudWebView()

        VideoSnifferEngine.onVideoDetectedListener = { _, count ->
            runOnUiThread {
                updateTopBarState()
                notifyHud("window.onNativeVideoDetected && window.onNativeVideoDetected($count);")
            }
        }

        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrBlank() && (incomingUrl.startsWith("http://") || incomingUrl.startsWith("https://"))) {
            openUrlInCurrentTab(incomingUrl)
        } else {
            showHudScreen("home")
        }
    }

    private fun setupLiquidGlassLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070A12"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ─── TOP LIQUID GLASS OMNIBOX BAR (Web Video Caster Layout, Pure Vector Icons) ───
        topBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0E1525"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        }

        // 1. Hamburger Menu Button (Left)
        val menuBtn = VectorIconView(this, VectorIconView.TYPE_HAMBURGER).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                hideKeyboard()
                showHudScreen("drawer")
            }
        }
        topBarContainer.addView(menuBtn)

        // 2. OmniBox Search / URL Bar
        val omniContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#172236"))
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), Color.parseColor("#2A3B56"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }

        urlEditText = EditText(this).apply {
            hint = "เรียกดู หรือพิมพ์ URL"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    openUrlInCurrentTab(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        omniContainer.addView(urlEditText)

        // Refresh Icon inside OmniBox
        val refreshBtn = VectorIconView(this, VectorIconView.TYPE_REFRESH).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener {
                hideKeyboard()
                if (currentBrowserUrl != HOME_URI) {
                    VideoSnifferEngine.clear()
                    updateTopBarState()
                    browserWebView.reload()
                } else {
                    showHudScreen("home")
                }
            }
        }
        omniContainer.addView(refreshBtn)
        topBarContainer.addView(omniContainer)

        // 3. Yellow Play Circle Badge (Appears ONLY when video streams are detected!)
        yellowPlayBadgeView = YellowPlayBadgeView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(4)
            }
            setOnClickListener {
                hideKeyboard()
                showHudScreen("videolist")
            }
        }
        topBarContainer.addView(yellowPlayBadgeView)

        // 4. Tabs Counter Button [N]
        tabsCountBoxView = TabsCountBoxView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(2)
            }
            setOnClickListener {
                hideKeyboard()
                showHudScreen("tabs")
            }
        }
        topBarContainer.addView(tabsCountBoxView)

        // 5. Cast Button
        castIconView = VectorIconView(this, VectorIconView.TYPE_CAST).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                hideKeyboard()
                showHudScreen("devices")
            }
        }
        topBarContainer.addView(castIconView)

        // 6. 3-Dots Overflow Button (Settings & Ad-Shield)
        val moreBtn = VectorIconView(this, VectorIconView.TYPE_MORE_VERT).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(38))
            setOnClickListener {
                hideKeyboard()
                showHudScreen("settings")
            }
        }
        topBarContainer.addView(moreBtn)

        root.addView(topBarContainer)

        // Thin Top Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            progressDrawable.setColorFilter(Color.parseColor("#38BDF8"), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        root.addView(progressBar)

        // ─── CENTER CONTENT FRAME (Browser WebView + Liquid Glass HUD WebView) ───
        val centerFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        browserWebView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#070A12"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        hudWebView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#070A12"))
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        centerFrame.addView(browserWebView)
        centerFrame.addView(hudWebView)
        root.addView(centerFrame)

        // ─── CONDITIONAL MINI PLAYER BAR (Hidden unless video/cast is actively playing!) ───
        miniPlayerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111B2E"))
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            setOnClickListener {
                showHudScreen("player")
            }
        }

        miniPlayerTitle = TextView(this).apply {
            text = "กำลังเล่นวิดีโอ • แตะเพื่อเปิดหน้าคอนโทรล"
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        miniPlayerBar.addView(miniPlayerTitle)

        val stopMiniBtn = TextView(this).apply {
            text = "หยุดเล่น"
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                isVideoOrCastActive = false
                thread { DlnaCaster.stop() }
                notifyHud("window.onNativeStopAll && window.onNativeStopAll();")
                updateTopBarState()
            }
        }
        miniPlayerBar.addView(stopMiniBtn)

        root.addView(miniPlayerBar)
        setContentView(root)
    }

    private fun showHudScreen(screen: String) {
        currentHudScreen = screen
        if (screen == "browser") {
            if (currentBrowserUrl == HOME_URI) {
                currentHudScreen = "home"
                topBarContainer.visibility = View.VISIBLE
                browserWebView.visibility = View.GONE
                hudWebView.visibility = View.VISIBLE
                notifyHud("window.showScreen && window.showScreen('home');")
            } else {
                topBarContainer.visibility = View.VISIBLE
                hudWebView.visibility = View.GONE
                browserWebView.visibility = View.VISIBLE
            }
        } else {
            // Hide native top bar on full-screen subpages (videolist, iptv, settings, player, subtitles)
            val hideNativeTop = screen in listOf("videolist", "iptv", "settings", "player", "subtitles")
            topBarContainer.visibility = if (hideNativeTop) View.GONE else View.VISIBLE
            browserWebView.visibility = View.GONE
            hudWebView.visibility = View.VISIBLE
            notifyHud("window.showScreen && window.showScreen('$screen');")
        }
        updateTopBarState()
    }

    private fun openUrlInCurrentTab(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        hideKeyboard()
        if (input == HOME_URI) {
            currentBrowserUrl = HOME_URI
            currentBrowserTitle = "หน้าเริ่มต้น"
            urlEditText.setText("")
            updateActiveTabRecord(HOME_URI, "หน้าเริ่มต้น")
            showHudScreen("home")
            return
        }

        val targetUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }

        VideoSnifferEngine.clear()
        currentBrowserUrl = targetUrl
        urlEditText.setText(targetUrl.removePrefix("https://").removePrefix("http://"))
        updateActiveTabRecord(targetUrl, targetUrl)
        saveLastVisitedUrl(targetUrl)

        topBarContainer.visibility = View.VISIBLE
        hudWebView.visibility = View.GONE
        browserWebView.visibility = View.VISIBLE
        currentHudScreen = "browser"
        updateTopBarState()

        browserWebView.loadUrl(targetUrl)
    }

    private fun updateActiveTabRecord(url: String, title: String) {
        val tab = openTabs.find { it.id == activeTabId }
        if (tab != null) {
            tab.url = url
            tab.title = title
        }
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
        settings.setSupportMultipleWindows(true)
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

                // If ISP redirects to a government/ISP block page, trigger DoH Deep Bypass
                if (DoHNetworkClient.isDohEnabled && AdBlocker.isIspBlockRedirect(url)) {
                    bypassIspBlockForUrl(currentBrowserUrl)
                    return true
                }

                if (AdBlocker.shouldBlockNavigation(url, currentBrowserUrl)) {
                    runOnUiThread { updateTopBarState() }
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                if (AdBlocker.shouldBlock(url, currentBrowserUrl)) {
                    return AdBlocker.emptyResponse()
                }

                VideoSnifferEngine.inspectUrl(
                    url = url,
                    pageTitle = currentBrowserTitle,
                    pageUrl = currentBrowserUrl,
                    headers = request.requestHeaders,
                    defaultUserAgent = DEFAULT_UA
                )

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    currentBrowserUrl = url
                    urlEditText.setText(url.removePrefix("https://").removePrefix("http://"))
                    view?.evaluateJavascript(AdBlocker.JS_COSMETIC_AD_SHIELD, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val title = view?.title?.takeIf { it.isNotBlank() } ?: currentBrowserUrl
                currentBrowserTitle = title
                if (!url.isNullOrBlank() && url != "about:blank") {
                    currentBrowserUrl = url
                    urlEditText.setText(url.removePrefix("https://").removePrefix("http://"))
                    updateActiveTabRecord(url, title)
                    recordHistoryAndMostVisited(url, title)
                }
                view?.evaluateJavascript(AdBlocker.JS_COSMETIC_AD_SHIELD, null)
                view?.evaluateJavascript(VideoSnifferEngine.JS_SNIFFER_HOOK, null)
                updateTopBarState()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && DoHNetworkClient.isDohEnabled && DoHNetworkClient.deepWebViewBypass) {
                    val failedUrl = request.url?.toString() ?: return
                    if (!bypassedUrlsInSession.contains(failedUrl)) {
                        bypassedUrlsInSession.add(failedUrl)
                        bypassIspBlockForUrl(failedUrl)
                    }
                }
            }
        }

        browserWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                if (newProgress > 45) {
                    view?.evaluateJavascript(AdBlocker.JS_COSMETIC_AD_SHIELD, null)
                    view?.evaluateJavascript(VideoSnifferEngine.JS_SNIFFER_HOOK, null)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    currentBrowserTitle = title
                    updateActiveTabRecord(currentBrowserUrl, title)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Block pop-up ad tabs
                AdBlocker.blockedCount.incrementAndGet()
                return false
            }
        }
    }

    /**
     * Fetches the webpage HTML via DoH (Cloudflare 1.1.1.1 / Google 8.8.8.8) when ISP DNS/SNI blocks it.
     */
    private fun bypassIspBlockForUrl(targetUrl: String) {
        if (!targetUrl.startsWith("http")) return
        thread {
            val cookie = try { CookieManager.getInstance().getCookie(targetUrl) ?: "" } catch (e: Exception) { "" }
            val html = DoHNetworkClient.fetchBlockedPageHtml(targetUrl, DEFAULT_UA, cookie)
            if (!html.isNullOrBlank()) {
                runOnUiThread {
                    browserWebView.loadDataWithBaseURL(
                        targetUrl,
                        html,
                        "text/html",
                        "UTF-8",
                        targetUrl
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureHudWebView() {
        val s = hudWebView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.allowFileAccess = true
        s.allowContentAccess = true

        hudWebView.addJavascriptInterface(NativeJsBridge(), "NativeBridge")
        hudWebView.webChromeClient = WebChromeClient()
        hudWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                notifyHud("window.showScreen && window.showScreen('$currentHudScreen');")
            }
        }
        hudWebView.loadUrl("file:///android_asset/cyber_ui.html")
    }

    private fun updateTopBarState() {
        val count = VideoSnifferEngine.detectedVideos.size
        if (count > 0) {
            if (yellowPlayBadgeView.visibility != View.VISIBLE) {
                yellowPlayBadgeView.visibility = View.VISIBLE
                yellowPlayBadgeView.scaleX = 0.3f
                yellowPlayBadgeView.scaleY = 0.3f
                yellowPlayBadgeView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator(2.0f))
                    .start()
            }
            yellowPlayBadgeView.setCount(count)
        } else {
            yellowPlayBadgeView.visibility = View.GONE
        }

        tabsCountBoxView.setTabCount(openTabs.size)
        castIconView.setHighlighted(DlnaCaster.activeDevice != null || isVideoOrCastActive)

        val showMini = isVideoOrCastActive && currentHudScreen != "player" && currentHudScreen != "subtitles"
        miniPlayerBar.visibility = if (showMini) View.VISIBLE else View.GONE
        if (showMini) {
            val tvName = DlnaCaster.activeDevice?.name
            val title = DlnaCaster.currentStreamTitle.ifBlank { currentBrowserTitle }
            miniPlayerTitle.text = if (tvName != null && DlnaCaster.isPlaying) {
                "กำลังแคสต์ไปยัง $tvName: $title"
            } else {
                "กำลังเล่น: $title • แตะเพื่อเปิดหน้าคอนโทรล"
            }
        }
    }

    private fun notifyHud(jsCode: String) {
        if (::hudWebView.isInitialized) {
            hudWebView.evaluateJavascript(jsCode, null)
        }
    }

    private fun saveLastVisitedUrl(url: String) {
        if (url == HOME_URI || url.startsWith("about:")) return
        getSharedPreferences("dreamhub_browser_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_session_url", url)
            .apply()
    }

    private fun recordHistoryAndMostVisited(url: String, title: String) {
        if (url == HOME_URI || url.startsWith("about:") || url.startsWith("file:")) return
        val prefs = getSharedPreferences("dreamhub_browser_prefs", Context.MODE_PRIVATE)
        try {
            val histRaw = prefs.getString("history_json", "[]") ?: "[]"
            val histArr = JSONArray(histRaw)
            val newArr = JSONArray()
            newArr.put(JSONObject().apply {
                put("url", url)
                put("title", title.take(80))
                put("time", System.currentTimeMillis())
            })
            val limit = minOf(histArr.length(), 40)
            for (i in 0 until limit) {
                val item = histArr.getJSONObject(i)
                if (item.optString("url") != url) {
                    newArr.put(item)
                }
            }
            prefs.edit().putString("history_json", newArr.toString()).apply()
        } catch (e: Exception) {}
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try {
            val rawText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
            val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "imported_file"
            if (requestCode == REQ_PICK_M3U) {
                val safeJson = JSONObject().apply {
                    put("fileName", fileName)
                    put("rawContent", rawText)
                }.toString()
                notifyHud("window.onNativeM3uFilePicked && window.onNativeM3uFilePicked($safeJson);")
            } else if (requestCode == REQ_PICK_SRT) {
                SubtitleEngine.loadLocalSubtitleContent(rawText, fileName)
                val safeName = JSONObject.quote(fileName)
                notifyHud("window.onNativeSubtitleAttached && window.onNativeSubtitleAttached($safeName);")
                Toast.makeText(this, "แอดซับไตเติ้ล $fileName สำเร็จ", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่สามารถอ่านไฟล์ได้", Toast.LENGTH_SHORT).show()
        }
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
        fun getSniffedVideosJson(): String = VideoSnifferEngine.toJsonArray()

        @JavascriptInterface
        fun clearSniffedVideos() {
            VideoSnifferEngine.clear()
            runOnUiThread { updateTopBarState() }
        }

        @JavascriptInterface
        fun navigateToUrl(url: String) {
            runOnUiThread { openUrlInCurrentTab(url) }
        }

        @JavascriptInterface
        fun switchScreenFromJs(screen: String) {
            runOnUiThread { showHudScreen(screen) }
        }

        @JavascriptInterface
        fun getLastSessionUrl(): String {
            return getSharedPreferences("dreamhub_browser_prefs", Context.MODE_PRIVATE)
                .getString("last_session_url", "") ?: ""
        }

        @JavascriptInterface
        fun getTabsAndHistoryJson(): String {
            val prefs = getSharedPreferences("dreamhub_browser_prefs", Context.MODE_PRIVATE)
            val histRaw = prefs.getString("history_json", "[]") ?: "[]"
            val tabsArr = JSONArray()
            for (t in openTabs) {
                tabsArr.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("url", t.url)
                    put("active", t.id == activeTabId)
                })
            }
            return JSONObject().apply {
                put("tabs", tabsArr)
                put("history", JSONArray(histRaw))
            }.toString()
        }

        @JavascriptInterface
        fun createNewTab() {
            runOnUiThread {
                val newTab = BrowserTabItem(
                    id = "tab_${System.currentTimeMillis()}",
                    title = "หน้าเริ่มต้น",
                    url = HOME_URI
                )
                openTabs.add(0, newTab)
                activeTabId = newTab.id
                openUrlInCurrentTab(HOME_URI)
            }
        }

        @JavascriptInterface
        fun switchToTab(tabId: String) {
            runOnUiThread {
                val tab = openTabs.find { it.id == tabId } ?: return@runOnUiThread
                activeTabId = tab.id
                openUrlInCurrentTab(tab.url)
            }
        }

        @JavascriptInterface
        fun closeTab(tabId: String): String {
            openTabs.removeAll { it.id == tabId }
            if (openTabs.isEmpty()) {
                val fallback = BrowserTabItem("tab_${System.currentTimeMillis()}", "หน้าเริ่มต้น", HOME_URI)
                openTabs.add(fallback)
                activeTabId = fallback.id
            } else if (activeTabId == tabId) {
                activeTabId = openTabs.first().id
            }
            runOnUiThread { updateTopBarState() }
            return getTabsAndHistoryJson()
        }

        @JavascriptInterface
        fun clearBrowserHistory() {
            getSharedPreferences("dreamhub_browser_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("history_json", "[]")
                .apply()
        }

        @JavascriptInterface
        fun scanTvDevices(): String {
            DlnaCaster.discoverDevices(applicationContext, 2600)
            runOnUiThread { updateTopBarState() }
            return DlnaCaster.devicesToJson()
        }

        @JavascriptInterface
        fun getTvDevicesJson(): String = DlnaCaster.devicesToJson()

        @JavascriptInterface
        fun addManualTvIp(ip: String): String {
            DlnaCaster.probeManualTvIp(ip)
            runOnUiThread { updateTopBarState() }
            return DlnaCaster.devicesToJson()
        }

        @JavascriptInterface
        fun selectTvDevice(deviceId: String): Boolean {
            val dev = DlnaCaster.devices[deviceId] ?: return false
            DlnaCaster.activeDevice = dev
            runOnUiThread { updateTopBarState() }
            return true
        }

        @JavascriptInterface
        fun prepareProxiedStreamUrl(url: String, referer: String): String {
            val cookie = try { CookieManager.getInstance().getCookie(url) ?: "" } catch (e: Exception) { "" }
            return LocalStreamProxyServer.registerStream(
                url = url,
                referer = referer.ifBlank { currentBrowserUrl },
                userAgent = DEFAULT_UA,
                cookie = cookie,
                context = applicationContext
            )
        }

        @JavascriptInterface
        fun castVideo(url: String, title: String, referer: String, useProxy: Boolean): Boolean {
            var dev = DlnaCaster.activeDevice
            if (dev == null) {
                DlnaCaster.discoverDevices(applicationContext, 2000)
                dev = DlnaCaster.activeDevice
            }
            if (dev == null) {
                return false
            }

            val cookie = try { CookieManager.getInstance().getCookie(url) ?: "" } catch (e: Exception) { "" }
            val lower = url.lowercase()
            // Always route master.txt / index.txt or useProxy streams through Smart Proxy so Samsung TV plays them
            val mustProxy = useProxy || lower.contains("master.txt") || lower.contains("index.txt") || lower.contains("playlist.txt")
            val finalStreamUrl = if (mustProxy) {
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

            val subUrl = if (SubtitleEngine.hasActiveSubtitle()) {
                LocalStreamProxyServer.getSubtitleUrl(applicationContext, "srt")
            } else {
                null
            }

            val ok = DlnaCaster.castToTv(
                device = dev,
                streamUrl = finalStreamUrl,
                title = title.ifBlank { currentBrowserTitle.ifBlank { "DreamHub Video" } },
                subtitleSrtUrl = subUrl
            )

            isVideoOrCastActive = true
            runOnUiThread {
                startCastForegroundService()
                updateTopBarState()
                Toast.makeText(
                    this@MainActivity,
                    "กำลังส่งวิดีโอไปยัง ${dev.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return ok
        }

        @JavascriptInterface
        fun setVideoActiveState(active: Boolean) {
            isVideoOrCastActive = active
            runOnUiThread { updateTopBarState() }
        }

        @JavascriptInterface
        fun remotePlayPause(play: Boolean): Boolean = DlnaCaster.playOrPause(play)

        @JavascriptInterface
        fun remoteStop(): Boolean {
            isVideoOrCastActive = false
            val ok = DlnaCaster.stop()
            try {
                val intent = Intent(this@MainActivity, CastForegroundService::class.java).apply {
                    action = CastForegroundService.ACTION_STOP
                }
                startService(intent)
            } catch (e: Exception) {}
            runOnUiThread { updateTopBarState() }
            return ok
        }

        @JavascriptInterface
        fun remoteSeek(seconds: Int): Boolean = DlnaCaster.seekToSeconds(seconds)

        @JavascriptInterface
        fun remoteSeekRelative(deltaSeconds: Int): Int = DlnaCaster.seekRelativeSeconds(deltaSeconds)

        @JavascriptInterface
        fun getTvPositionJson(): String = DlnaCaster.queryPositionInfo().toString()

        @JavascriptInterface
        fun remoteVolume(vol: Int): Boolean = DlnaCaster.setVolume(vol)

        // ─── ONLINE SUBTITLES BRIDGE ──────────────────────────────────
        @JavascriptInterface
        fun searchSubtitlesOnline(query: String, lang: String): String {
            val effectiveQuery = query.ifBlank { currentBrowserTitle }
            return SubtitleEngine.searchOnlineSubtitles(effectiveQuery, lang)
        }

        @JavascriptInterface
        fun attachOnlineSubtitle(downloadUrl: String, title: String, lang: String): String {
            val ok = SubtitleEngine.downloadAndAttachSubtitle(downloadUrl, title, lang)
            // If currently casting to Samsung TV, refresh stream with subtitle attached
            val dev = DlnaCaster.activeDevice
            if (ok && dev != null && DlnaCaster.isPlaying && DlnaCaster.currentStreamUrl.isNotBlank()) {
                val srtUrl = LocalStreamProxyServer.getSubtitleUrl(applicationContext, "srt")
                DlnaCaster.castToTv(dev, DlnaCaster.currentStreamUrl, DlnaCaster.currentStreamTitle, srtUrl)
            }
            return JSONObject().apply {
                put("success", ok)
                put("title", SubtitleEngine.activeSubtitleTitle)
                put("vttUrl", LocalStreamProxyServer.getSubtitleUrl(applicationContext, "vtt"))
                put("srtUrl", LocalStreamProxyServer.getSubtitleUrl(applicationContext, "srt"))
            }.toString()
        }

        @JavascriptInterface
        fun adjustSubtitleTiming(deltaMs: Long): Long {
            return SubtitleEngine.adjustOffset(deltaMs)
        }

        @JavascriptInterface
        fun openLocalFilePicker(type: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    this.type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                val code = if (type == "srt") REQ_PICK_SRT else REQ_PICK_M3U
                startActivityForResult(Intent.createChooser(intent, "เลือกไฟล์"), code)
            }
        }

        // ─── 2-TIER IPTV BRIDGE ──────────────────────────────────────
        @JavascriptInterface
        fun getIptvPreset(preset: String): String {
            val channels = when (preset) {
                "thaitv" -> M3uParser.loadAssetPlaylist(applicationContext, "thai_live_tv.m3u8", "ทีวีดิจิทัลไทย")
                "thailand_cable" -> M3uParser.loadAssetPlaylist(applicationContext, "thailand_channels.m3u", "ทีวีและเคเบิลไทย")
                "dreamhub" -> M3uParser.fetchRemotePlaylist("https://teeradream.github.io/dream-hub/playlist.m3u", "คลังซีรีส์พากย์ไทย")
                else -> emptyList()
            }
            return M3uParser.channelsToJson(channels)
        }

        @JavascriptInterface
        fun fetchCustomM3uUrl(url: String, playlistName: String): String {
            val list = M3uParser.fetchRemotePlaylist(url, playlistName.ifBlank { "เพลย์ลิสต์ M3U" })
            return M3uParser.channelsToJson(list)
        }

        @JavascriptInterface
        fun parseCustomM3uRaw(rawText: String, playlistName: String): String {
            val list = M3uParser.parseM3uContent(rawText, playlistName.ifBlank { "ไฟล์ M3U ในเครื่อง" })
            return M3uParser.channelsToJson(list)
        }

        // ─── SETTINGS, DOH ISP BYPASS & AD-SHIELD BRIDGE ─────────────
        @JavascriptInterface
        fun getSystemStatusJson(): String {
            return JSONObject().apply {
                put("dohEnabled", DoHNetworkClient.isDohEnabled)
                put("dohProvider", DoHNetworkClient.dohProvider)
                put("deepWebViewBypass", DoHNetworkClient.deepWebViewBypass)
                put("adBlockEnabled", AdBlocker.isEnabled)
                put("blockBanners", AdBlocker.blockBanners)
                put("blockPopups", AdBlocker.blockPopups)
                put("filterShortAdClips", AdBlocker.filterShortAdClips)
                put("blockedAdsCount", AdBlocker.blockedCount.get())
                put("lanIp", LocalStreamProxyServer.getLocalLanIp(applicationContext))
                put("webReceiverUrl", LocalStreamProxyServer.getWebReceiverUrl(applicationContext))
                put("activeTvName", DlnaCaster.activeDevice?.name ?: "ยังไม่ได้เชื่อมต่อ")
                put("activeTvIp", DlnaCaster.activeDevice?.ipAddress ?: "-")
                put("currentStreamTitle", DlnaCaster.currentStreamTitle.ifBlank { currentBrowserTitle })
                put("currentStreamUrl", DlnaCaster.currentStreamUrl)
                put("isPlaying", DlnaCaster.isPlaying || isVideoOrCastActive)
                put("sniffedCount", VideoSnifferEngine.detectedVideos.size)
                put("currentBrowserUrl", currentBrowserUrl)
                put("currentBrowserTitle", currentBrowserTitle)
                put("activeSubtitleTitle", SubtitleEngine.activeSubtitleTitle)
                put("subtitleOffsetMs", SubtitleEngine.offsetMs)
            }.toString()
        }

        @JavascriptInterface
        fun updateSettingsJson(jsonStr: String) {
            try {
                val obj = JSONObject(jsonStr)
                if (obj.has("dohEnabled")) DoHNetworkClient.isDohEnabled = obj.getBoolean("dohEnabled")
                if (obj.has("dohProvider")) DoHNetworkClient.dohProvider = obj.getString("dohProvider")
                if (obj.has("deepWebViewBypass")) DoHNetworkClient.deepWebViewBypass = obj.getBoolean("deepWebViewBypass")
                if (obj.has("adBlockEnabled")) AdBlocker.isEnabled = obj.getBoolean("adBlockEnabled")
                if (obj.has("blockBanners")) AdBlocker.blockBanners = obj.getBoolean("blockBanners")
                if (obj.has("blockPopups")) AdBlocker.blockPopups = obj.getBoolean("blockPopups")
                if (obj.has("filterShortAdClips")) AdBlocker.filterShortAdClips = obj.getBoolean("filterShortAdClips")
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun openExternalPlayer(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, "เลือกแอปเล่นวิดีโอ"))
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "ไม่พบแอปเล่นวิดีโอภายนอก", Toast.LENGTH_SHORT).show()
                }
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
        if (currentHudScreen != "browser" && currentHudScreen != "home") {
            if (currentBrowserUrl != HOME_URI) {
                showHudScreen("browser")
            } else {
                showHudScreen("home")
            }
            return
        }
        if (currentHudScreen == "browser" && browserWebView.canGoBack()) {
            browserWebView.goBack()
        } else if (currentHudScreen == "browser" && currentBrowserUrl != HOME_URI) {
            openUrlInCurrentTab(HOME_URI)
        } else {
            super.onBackPressed()
        }
    }
}

/**
 * Pure Vector Canvas Icon View (Zero Emojis) for Top Bar buttons.
 */
class VectorIconView(context: Context, private val iconType: Int) : View(context) {

    companion object {
        const val TYPE_HAMBURGER = 1
        const val TYPE_REFRESH = 2
        const val TYPE_CAST = 3
        const val TYPE_MORE_VERT = 4
    }

    private var highlighted = false
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.9f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.FILL
    }

    fun setHighlighted(active: Boolean) {
        highlighted = active
        val c = if (active) Color.parseColor("#38BDF8") else Color.parseColor("#E2E8F0")
        strokePaint.color = c
        fillPaint.color = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        when (iconType) {
            TYPE_HAMBURGER -> {
                val halfW = dpF(8.5f)
                val gap = dpF(5f)
                canvas.drawLine(cx - halfW, cy - gap, cx + halfW, cy - gap, strokePaint)
                canvas.drawLine(cx - halfW, cy, cx + halfW, cy, strokePaint)
                canvas.drawLine(cx - halfW, cy + gap, cx + halfW, cy + gap, strokePaint)
            }
            TYPE_REFRESH -> {
                val r = dpF(6.5f)
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, 35f, 290f, false, strokePaint)
                // Arrowhead
                val ax = cx + r * 0.7f
                val ay = cy - r * 0.65f
                canvas.drawLine(ax, ay - dpF(3f), ax + dpF(2.5f), ay + dpF(0.5f), strokePaint)
                canvas.drawLine(ax - dpF(3f), ay + dpF(1f), ax + dpF(2.5f), ay + dpF(0.5f), strokePaint)
            }
            TYPE_CAST -> {
                val rw = dpF(9f)
                val rh = dpF(7f)
                val rect = RectF(cx - rw, cy - rh, cx + rw, cy + rh)
                // Top & right TV frame
                val path = Path().apply {
                    moveTo(rect.left, rect.top + dpF(3f))
                    lineTo(rect.left, rect.top)
                    lineTo(rect.right, rect.top)
                    lineTo(rect.right, rect.bottom)
                    lineTo(rect.left + dpF(9f), rect.bottom)
                }
                canvas.drawPath(path, strokePaint)
                // Wi-Fi cast waves at bottom-left
                canvas.drawCircle(rect.left + dpF(1f), rect.bottom - dpF(1f), dpF(1.2f), fillPaint)
                val wave1 = RectF(rect.left - dpF(3.5f), rect.bottom - dpF(5.5f), rect.left + dpF(5.5f), rect.bottom + dpF(3.5f))
                canvas.drawArc(wave1, 270f, 90f, false, strokePaint)
                val wave2 = RectF(rect.left - dpF(6.5f), rect.bottom - dpF(8.5f), rect.left + dpF(8.5f), rect.bottom + dpF(6.5f))
                canvas.drawArc(wave2, 270f, 90f, false, strokePaint)
            }
            TYPE_MORE_VERT -> {
                val r = dpF(1.7f)
                val gap = dpF(5.2f)
                canvas.drawCircle(cx, cy - gap, r, fillPaint)
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy + gap, r, fillPaint)
            }
        }
    }

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}

/**
 * Glowing Yellow Play Circle with Count Badge (Matches Web Video Caster Screenshot 4).
 */
class YellowPlayBadgeView(context: Context) : View(context) {
    private var streamCount = 0
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FACC15")
        style = Paint.Style.FILL
    }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#090D16")
        style = Paint.Style.FILL
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#04130D")
        textSize = dpF(9.5f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun setCount(count: Int) {
        streamCount = count
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = dpF(14f)
        canvas.drawCircle(cx, cy, radius, circlePaint)

        val tri = Path().apply {
            moveTo(cx - dpF(3.5f), cy - dpF(5.5f))
            lineTo(cx + dpF(6f), cy)
            lineTo(cx - dpF(3.5f), cy + dpF(5.5f))
            close()
        }
        canvas.drawPath(tri, playPaint)

        if (streamCount > 0) {
            val bx = cx + dpF(10f)
            val by = cy - dpF(10f)
            canvas.drawCircle(bx, by, dpF(7.5f), badgeBgPaint)
            val fm = badgeTextPaint.fontMetrics
            val ty = by - (fm.ascent + fm.descent) / 2f
            canvas.drawText(streamCount.coerceAtMost(99).toString(), bx, ty, badgeTextPaint)
        }
    }

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}

/**
 * Browser Tabs Counter Box [1] (Matches Web Video Caster Top Bar).
 */
class TabsCountBoxView(context: Context) : View(context) {
    private var tabCount = 1
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.8f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = dpF(10.5f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun setTabCount(count: Int) {
        tabCount = count.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val half = dpF(8.5f)
        val rect = RectF(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(rect, dpF(3.5f), dpF(3.5f), boxPaint)
        val fm = textPaint.fontMetrics
        val ty = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(tabCount.toString(), cx, ty, textPaint)
    }

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
