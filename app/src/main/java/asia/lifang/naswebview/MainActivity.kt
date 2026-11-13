package asia.lifang.naswebview

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.net.HttpURLConnection
import java.net.URL

/**
 * NAS WebView v1.2：启动即全屏 Web；控制台/浏览/设置与线路切换收纳于左侧抽屉。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_URL_LAN = "http://192.168.31.15:8758"
        const val DEFAULT_URL_WAN = "https://nas.lifang.asia:8758"

        private const val PREFS_NAME = "nas_webview_prefs"
        private const val KEY_URL_LAN = "url_lan"
        private const val KEY_URL_WAN = "url_wan"
        private const val KEY_START_MODE = "start_mode"   // auto | lan | wan
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_LAST_LINE = "last_line"
        private const val KEY_STATE_URL = "state_url"
        private const val KEY_STATE_LINE = "state_line"
        private const val KEY_AUTO_PROBE = "auto_probe"

        private const val LINE_LAN = "lan"
        private const val LINE_WAN = "wan"

        private const val EXIT_INTERVAL_MS = 2000L
    }

    // ---------- 抽屉 ----------
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerStatusDot: TextView
    private lateinit var drawerStatusText: TextView
    private lateinit var drawerSub: TextView
    private lateinit var drawerAwakeState: TextView

    // ---------- 三屏 ----------
    private lateinit var screenHome: View
    private lateinit var screenBrowse: View
    private lateinit var screenSettings: View
    private var screenMode = "browse"

    // ---------- 首页 ----------
    private lateinit var bannerTitle: TextView
    private lateinit var bannerSub: TextView
    private lateinit var addrLan: TextView
    private lateinit var addrWan: TextView
    private lateinit var metaLan: TextView
    private lateinit var metaWan: TextView
    private lateinit var checkLan: ImageView
    private lateinit var checkWan: ImageView
    private lateinit var tileAwakeLabel: TextView

    // ---------- 浏览 ----------
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorMessage: TextView

    // ---------- 设置 ----------
    private lateinit var segAuto: Button
    private lateinit var segLan: Button
    private lateinit var segWan: Button
    private lateinit var switchProbe: Switch
    private lateinit var switchAwake: Switch
    private lateinit var etLan: EditText
    private lateinit var etWan: EditText

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val urlLan: String get() = prefs.getString(KEY_URL_LAN, DEFAULT_URL_LAN) ?: DEFAULT_URL_LAN
    private val urlWan: String get() = prefs.getString(KEY_URL_WAN, DEFAULT_URL_WAN) ?: DEFAULT_URL_WAN

    private var currentLine = LINE_LAN
    private var currentUrl = DEFAULT_URL_LAN
    private var browseInitialized = false
    private var lastBackPressedAt = 0L

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingDownloadAction: (() -> Unit)? = null

    // ---------- 文件选择 ----------
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(extractUris(result.resultCode, result.data))
        }

    private fun extractUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) return null
        val clip = data?.clipData
        if (clip != null && clip.itemCount > 0) {
            val list = ArrayList<Uri>()
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { list.add(it) }
            }
            if (list.isNotEmpty()) return list.toTypedArray()
        }
        return data?.data?.let { arrayOf(it) }
    }

    // ---------- 摄像头/麦克风授权 ----------
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request == null) return@registerForActivityResult
            if (results.isNotEmpty() && results.values.all { it }) {
                request.grant(request.resources)
            } else {
                request.deny()
                toast("未授予摄像头 / 麦克风权限")
            }
        }

    // ---------- 下载存储权限 ----------
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDownloadAction
            pendingDownloadAction = null
            if (granted) action?.invoke() else toast("未授予存储权限，无法下载")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupDrawer()
        setupHome()
        setupBrowse()
        setupSettings()
        setupBackNavigation()
        applyKeepAwake()

        if (savedInstanceState != null) {
            currentUrl = savedInstanceState.getString(KEY_STATE_URL) ?: currentUrl
            currentLine = savedInstanceState.getString(KEY_STATE_LINE) ?: currentLine
            browseInitialized = true
            webView.restoreState(savedInstanceState)
            showScreen("browse")
            updateDrawerStatus(probeNow = false)
        } else {
            startAccordingToSettings()
        }
    }

    /** 启动即进入全屏 Web：按「默认启动线路」设置选路。 */
    private fun startAccordingToSettings() {
        when (prefs.getString(KEY_START_MODE, "auto")) {
            "lan" -> enterLine(LINE_LAN)
            "wan" -> enterLine(LINE_WAN)
            else -> {
                if (prefs.getBoolean(KEY_AUTO_PROBE, true)) {
                    autoSelectAndEnter(silent = true)
                } else {
                    enterLine(prefs.getString(KEY_LAST_LINE, LINE_LAN) ?: LINE_LAN)
                }
            }
        }
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        drawerStatusDot = findViewById(R.id.drawerStatusDot)
        drawerStatusText = findViewById(R.id.drawerStatusText)
        drawerSub = findViewById(R.id.drawerSub)
        drawerAwakeState = findViewById(R.id.drawerAwakeState)

        screenHome = findViewById(R.id.screenHomeRoot)
        screenBrowse = findViewById(R.id.screenBrowseRoot)
        screenSettings = findViewById(R.id.screenSettingsRoot)

        bannerTitle = findViewById(R.id.bannerTitle)
        bannerSub = findViewById(R.id.bannerSub)
        addrLan = findViewById(R.id.addrLan)
        addrWan = findViewById(R.id.addrWan)
        metaLan = findViewById(R.id.metaLan)
        metaWan = findViewById(R.id.metaWan)
        checkLan = findViewById(R.id.checkLan)
        checkWan = findViewById(R.id.checkWan)
        tileAwakeLabel = findViewById(R.id.tileAwakeLabel)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorMessage = findViewById(R.id.errorMessage)

        segAuto = findViewById(R.id.segAuto)
        segLan = findViewById(R.id.segLan)
        segWan = findViewById(R.id.segWan)
        switchProbe = findViewById(R.id.switchProbe)
        switchAwake = findViewById(R.id.switchAwake)
        etLan = findViewById(R.id.etLan)
        etWan = findViewById(R.id.etWan)
    }

    // ==================== 抽屉 ====================

    private fun setupDrawer() {
        findViewById<TextView>(R.id.drawerVersion).text = "v${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.edgeHandle).setOnClickListener { openDrawer() }
        findViewById<View>(R.id.itemHome).setOnClickListener {
            closeDrawer(); showScreen("home")
        }
        findViewById<View>(R.id.itemBrowse).setOnClickListener {
            closeDrawer()
            if (browseInitialized) showScreen("browse")
            else startAccordingToSettings()
        }
        findViewById<View>(R.id.itemSettings).setOnClickListener {
            closeDrawer(); showScreen("settings")
        }
        findViewById<View>(R.id.itemRefresh).setOnClickListener {
            closeDrawer()
            when (screenMode) {
                "home" -> refreshHomeStatus(force = true)
                "settings" -> {
                    fillSettings()
                    toast("设置已刷新")
                }
                else -> retry()
            }
        }
        findViewById<View>(R.id.itemBrowser).setOnClickListener {
            closeDrawer(); openInBrowser()
        }
        findViewById<View>(R.id.itemCopy).setOnClickListener {
            closeDrawer(); copyCurrentUrl()
        }
        findViewById<View>(R.id.itemAwake).setOnClickListener {
            toggleAwake()
        }
        findViewById<Button>(R.id.btnDrawerLan).setOnClickListener {
            closeDrawer(); enterLine(LINE_LAN)
        }
        findViewById<Button>(R.id.btnDrawerWan).setOnClickListener {
            closeDrawer(); enterLine(LINE_WAN)
        }
        findViewById<Button>(R.id.btnDrawerAuto).setOnClickListener {
            closeDrawer(); autoSelectAndEnter()
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                updateDrawerStatus(probeNow = true)
            }
        })
    }

    private fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun closeDrawer() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun updateDrawerStatus(probeNow: Boolean) {
        val host = Uri.parse(currentUrl).host ?: currentUrl
        drawerStatusText.text = "${if (currentLine == LINE_LAN) "内网" else "外网"} · $host"
        drawerStatusDot.setBackgroundResource(
            if (currentLine == LINE_LAN) R.drawable.bg_dot else R.drawable.bg_dot_blue
        )
        if (probeNow) {
            drawerSub.text = "延迟检测中…"
            probe(currentUrl) { ms ->
                drawerSub.text = if (ms >= 0) "延迟 ${ms}ms · 刚刚检测" else "当前网络不可达"
            }
        } else {
            drawerSub.text = "打开侧边栏检测延迟"
        }
    }

    // ==================== 屏幕切换 ====================

    private fun showScreen(mode: String) {
        screenMode = mode
        screenHome.isVisible = mode == "home"
        screenBrowse.isVisible = mode == "browse"
        screenSettings.isVisible = mode == "settings"
        when (mode) {
            "home" -> refreshHomeStatus()
            "settings" -> fillSettings()
        }
    }

    // ==================== 首页 ====================

    private fun setupHome() {
        findViewById<Button>(R.id.btnEnterLan).setOnClickListener { enterLine(LINE_LAN) }
        findViewById<Button>(R.id.btnEnterWan).setOnClickListener { enterLine(LINE_WAN) }
        findViewById<View>(R.id.tileAuto).setOnClickListener { autoSelectAndEnter() }
        findViewById<View>(R.id.tileRefresh).setOnClickListener {
            toast("重新检测中…")
            refreshHomeStatus(force = true)
        }
        findViewById<View>(R.id.tileAwake).setOnClickListener { toggleAwake() }
    }

    private fun refreshHomeStatus(force: Boolean = false) {
        addrLan.text = urlLan
        addrWan.text = urlWan
        if (!force && !prefs.getBoolean(KEY_AUTO_PROBE, true)) {
            bannerTitle.text = "自动检测已关闭"
            bannerSub.text = "点击「重新检测」手动检测线路"
            return
        }
        bannerTitle.text = "正在检测线路…"
        bannerSub.text = "请稍候"
        probe(urlLan) { lanMs ->
            probe(urlWan) { wanMs ->
                if (isFinishing || isDestroyed) return@probe
                updateHomeStatus(lanMs, wanMs)
            }
        }
    }

    private fun updateHomeStatus(lanMs: Int, wanMs: Int) {
        val activeLan = currentLine == LINE_LAN
        checkLan.isVisible = activeLan
        checkWan.isVisible = !activeLan

        metaLan.text = lineMeta(lanMs, activeLan)
        metaLan.setTextColor(ContextCompat.getColor(this, if (lanMs >= 0) R.color.green else R.color.text_muted))
        metaWan.text = lineMeta(wanMs, !activeLan)
        metaWan.setTextColor(ContextCompat.getColor(this, if (wanMs >= 0) R.color.green else R.color.text_muted))

        bannerTitle.text = when {
            lanMs >= 0 -> "服务运行中 · 内网连接稳定"
            wanMs >= 0 -> "内网不可达 · 可使用外网线路"
            else -> "两条线路均不可达"
        }
        bannerSub.text = "内网 ${fmtMs(lanMs)} · 外网 ${fmtMs(wanMs)} · 刚刚检测"
    }

    private fun lineMeta(ms: Int, active: Boolean): String {
        val s = if (ms >= 0) "延迟 ${ms}ms" else "当前网络不可达"
        return if (active) "$s · 当前使用" else s
    }

    private fun fmtMs(ms: Int): String = if (ms >= 0) "${ms}ms" else "不可达"

    /** 探测 URL 延迟（毫秒），失败返回 -1。 */
    private fun probe(url: String, cb: (Int) -> Unit) {
        Thread {
            val start = System.currentTimeMillis()
            val ms = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) (System.currentTimeMillis() - start).toInt() else -1
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) cb(ms)
            }
        }.start()
    }

    private fun enterLine(line: String) {
        currentLine = line
        currentUrl = if (line == LINE_WAN) urlWan else urlLan
        prefs.edit().putString(KEY_LAST_LINE, line).apply()
        showScreen("browse")
        loadUrl(currentUrl)
    }

    private fun autoSelectAndEnter(silent: Boolean = false) {
        if (!silent) toast("正在探测最优线路…")
        probe(urlLan) { lanMs ->
            probe(urlWan) { wanMs ->
                when {
                    lanMs >= 0 -> enterLine(LINE_LAN)
                    wanMs >= 0 -> enterLine(LINE_WAN)
                    else -> {
                        if (!silent) toast("两条线路均不可达，进入上次使用的线路")
                        enterLine(prefs.getString(KEY_LAST_LINE, LINE_LAN) ?: LINE_LAN)
                    }
                }
            }
        }
    }

    private fun toggleAwake() {
        val on = !prefs.getBoolean(KEY_KEEP_AWAKE, true)
        prefs.edit().putBoolean(KEY_KEEP_AWAKE, on).apply()
        applyKeepAwake()
        toast(if (on) "已开启屏幕常亮" else "已关闭屏幕常亮")
    }

    private fun applyKeepAwake() {
        val on = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tileAwakeLabel.text = if (on) "常亮·开" else "常亮·关"
        drawerAwakeState.text = if (on) "开" else "关"
    }

    // ==================== 浏览 ====================

    private fun setupBrowse() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // 禁止 HTTPS 页面加载 HTTP 子资源（如需要可改回 MIXED_CONTENT_COMPATIBILITY_MODE）
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    else -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: ActivityNotFoundException) {
                            toast("没有可以打开此链接的应用")
                        }
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.isVisible = true
                progressBar.progress = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.isVisible = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                val desc = error?.description?.toString().orEmpty()
                if (desc.contains("ERR_ABORTED")) return
                showError(desc.ifBlank { getString(R.string.error_title) })
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.isVisible = newProgress in 0..99
                progressBar.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    toast("没有可用的文件管理器")
                    true
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val needed = mutableListOf<String>()
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    needed.add(Manifest.permission.CAMERA)
                }
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    needed.add(Manifest.permission.RECORD_AUDIO)
                }
                if (needed.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingPermissionRequest?.deny()
                    pendingPermissionRequest = request
                    permissionLauncher.launch(needed.toTypedArray())
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener { retry() }

        findViewById<Button>(R.id.btnRetry).setOnClickListener { retry() }
        findViewById<Button>(R.id.btnSwitchLine).setOnClickListener {
            enterLine(if (currentLine == LINE_LAN) LINE_WAN else LINE_LAN)
        }
    }

    private fun loadUrl(url: String) {
        currentUrl = url
        browseInitialized = true
        errorView.isVisible = false
        swipeRefresh.isVisible = true
        swipeRefresh.isRefreshing = true
        webView.loadUrl(url)
        updateDrawerStatus(probeNow = false)
    }

    private fun retry() {
        errorView.isVisible = false
        swipeRefresh.isVisible = true
        swipeRefresh.isRefreshing = true
        val url = webView.url
        if (url.isNullOrEmpty() || url == "about:blank") webView.loadUrl(currentUrl) else webView.reload()
    }

    private fun showError(message: String) {
        progressBar.isVisible = false
        swipeRefresh.isRefreshing = false
        swipeRefresh.isVisible = false
        errorView.isVisible = true
        errorMessage.text = message
        findViewById<Button>(R.id.btnSwitchLine).text =
            getString(if (currentLine == LINE_LAN) R.string.switch_to_wan else R.string.switch_to_lan)
    }

    private fun currentDisplayUrl(): String =
        webView.url?.takeIf { it != "about:blank" } ?: currentUrl

    private fun openInBrowser() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentDisplayUrl())))
        } catch (e: ActivityNotFoundException) {
            toast("没有可以打开此链接的应用")
        }
    }

    private fun copyCurrentUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", currentDisplayUrl()))
        toast("已复制地址")
    }

    // ==================== 设置 ====================

    private fun setupSettings() {
        segAuto.setOnClickListener { saveStartMode("auto") }
        segLan.setOnClickListener { saveStartMode("lan") }
        segWan.setOnClickListener { saveStartMode("wan") }

        switchProbe.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_PROBE, checked).apply()
        }
        switchAwake.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_AWAKE, checked).apply()
            applyKeepAwake()
        }

        findViewById<Button>(R.id.btnSaveAddr).setOnClickListener { saveAddresses() }
        findViewById<Button>(R.id.btnAbout).setOnClickListener { showAbout() }
    }

    private fun fillSettings() {
        when (prefs.getString(KEY_START_MODE, "auto")) {
            "lan" -> saveStartMode("lan", persist = false)
            "wan" -> saveStartMode("wan", persist = false)
            else -> saveStartMode("auto", persist = false)
        }
        switchProbe.isChecked = prefs.getBoolean(KEY_AUTO_PROBE, true)
        switchAwake.isChecked = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        etLan.setText(urlLan)
        etWan.setText(urlWan)
        findViewById<TextView>(R.id.aboutVersion).text = "NAS WebView v${BuildConfig.VERSION_NAME}"
    }

    private fun saveStartMode(mode: String, persist: Boolean = true) {
        if (persist) prefs.edit().putString(KEY_START_MODE, mode).apply()
        val sel = ContextCompat.getColor(this, R.color.accent)
        val unsel = ContextCompat.getColor(this, R.color.surface2)
        val white = ContextCompat.getColor(this, R.color.white)
        val sub = ContextCompat.getColor(this, R.color.text_primary)
        segAuto.setBackgroundColor(if (mode == "auto") sel else unsel)
        segLan.setBackgroundColor(if (mode == "lan") sel else unsel)
        segWan.setBackgroundColor(if (mode == "wan") sel else unsel)
        segAuto.setTextColor(if (mode == "auto") white else sub)
        segLan.setTextColor(if (mode == "lan") white else sub)
        segWan.setTextColor(if (mode == "wan") white else sub)
    }

    private fun saveAddresses() {
        val lan = etLan.text.toString().trim()
        val wan = etWan.text.toString().trim()
        fun valid(u: String) = u.startsWith("http://") || u.startsWith("https://")
        if (!valid(lan) || !valid(wan)) {
            toast("地址需以 http:// 或 https:// 开头")
            return
        }
        prefs.edit().putString(KEY_URL_LAN, lan).putString(KEY_URL_WAN, wan).apply()
        addrLan.text = lan
        addrWan.text = wan
        toast("地址已保存")
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, urlLan, urlWan))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ==================== 下载 ====================

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val action = {
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("User-Agent", userAgent ?: "")
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    setMimeType(mimeType ?: "application/octet-stream")
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                toast("已开始下载：$fileName")
            } catch (e: Exception) {
                toast("下载失败：${e.message}")
            }
            Unit
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    // ==================== 返回键 / 生命周期 ====================

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Web 优先的返回语义：抽屉 → 网页历史 → 双按退出
                // （控制台/设置只从侧边栏进入，避免左缘返回手势误触）
                when {
                    drawerLayout.isDrawerOpen(GravityCompat.START) -> closeDrawer()
                    screenMode != "browse" && browseInitialized -> showScreen("browse")
                    screenMode == "browse" && webView.canGoBack() && !errorView.isVisible -> webView.goBack()
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressedAt < EXIT_INTERVAL_MS) {
                            finish()
                        } else {
                            lastBackPressedAt = now
                            toast(getString(R.string.exit_hint))
                        }
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_URL, currentUrl)
        outState.putString(KEY_STATE_LINE, currentLine)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
