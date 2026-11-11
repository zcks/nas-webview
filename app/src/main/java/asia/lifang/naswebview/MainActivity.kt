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
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.net.HttpURLConnection
import java.net.URL

/**
 * NAS WebView：手机上的 Web 界面壳应用。
 *
 * - 内网地址（明文 HTTP）与外网地址（HTTPS）一键切换
 * - 启动时自动探测内网可达性，可达走内网，否则走外网
 * - WebView 支持 JS / 本地存储 / Cookie / 文件上传下载 / 摄像头麦克风
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val URL_LAN = "http://192.168.31.15:8758"
        const val URL_WAN = "https://nas.lifang.asia:8758"

        private const val PREFS_NAME = "nas_webview_prefs"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_STATE_URL = "state_url"
        private const val EXIT_INTERVAL_MS = 2000L

        private const val MENU_REFRESH = 1
        private const val MENU_AUTO = 2
        private const val MENU_BROWSER = 3
        private const val MENU_COPY = 4
        private const val MENU_KEEP_AWAKE = 5
        private const val MENU_ABOUT = 6
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorMessage: TextView
    private lateinit var btnLan: Button
    private lateinit var btnWan: Button
    private lateinit var btnSwitch: Button
    private lateinit var titleText: TextView

    private var currentUrl: String = URL_WAN
    private var lastBackPressedAt = 0L
    private var keepAwake = true

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingDownloadAction: (() -> Unit)? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // ---------- 文件选择（网页里的上传按钮） ----------
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

    // ---------- 网页摄像头 / 麦克风授权 ----------
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

    // ---------- Android 9 及以下的下载存储权限 ----------
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
        setupWebView()
        setupBackNavigation()

        keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        applyKeepAwake()

        if (savedInstanceState != null) {
            currentUrl = savedInstanceState.getString(KEY_STATE_URL)
                ?: prefs.getString(KEY_LAST_URL, URL_WAN)
                ?: URL_WAN
            updateModeButtons()
            webView.restoreState(savedInstanceState)
        } else {
            autoDetectAndLoad()
        }
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorMessage = findViewById(R.id.errorMessage)
        btnLan = findViewById(R.id.btnLan)
        btnWan = findViewById(R.id.btnWan)
        btnSwitch = findViewById(R.id.btnSwitch)
        titleText = findViewById(R.id.titleText)

        btnLan.setOnClickListener { loadUrl(URL_LAN) }
        btnWan.setOnClickListener { loadUrl(URL_WAN) }
        findViewById<Button>(R.id.btnAuto).setOnClickListener { autoDetectAndLoad() }
        findViewById<Button>(R.id.btnRetry).setOnClickListener { retry() }
        btnSwitch.setOnClickListener {
            loadUrl(if (currentUrl == URL_LAN) URL_WAN else URL_LAN)
        }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { retry() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }
    }

    // ==================== WebView ====================

    private fun setupWebView() {
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
            // 禁止 HTTPS 页面加载 HTTP 子资源（如页面出现混合内容被拦截，
            // 可改回 WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE）
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // 调试构建允许 chrome://inspect 远程调试
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false // 应用内加载
                    else -> {
                        // tel: / mailto: / intent: 等交给系统处理
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
                // 主动中断的加载（如点击了下载链接）不是真正的错误
                if (desc.contains("ERR_ABORTED")) return
                showError(desc.ifBlank { getString(R.string.error_title) })
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.isVisible = newProgress in 0..99
                progressBar.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) titleText.text = title
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
                    // 回调已消费（onReceiveValue(null)），按契约返回 true
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

        swipeRefresh.setColorSchemeResources(R.color.primary)
        swipeRefresh.setOnRefreshListener { retry() }
    }

    // ==================== 加载 / 切换 ====================

    private fun loadUrl(url: String) {
        currentUrl = url
        prefs.edit().putString(KEY_LAST_URL, url).apply()
        errorView.isVisible = false
        swipeRefresh.isVisible = true
        swipeRefresh.isRefreshing = true
        titleText.text = Uri.parse(url).host ?: url
        updateModeButtons()
        webView.loadUrl(url)
    }

    private fun retry() {
        errorView.isVisible = false
        swipeRefresh.isVisible = true
        swipeRefresh.isRefreshing = true
        val url = webView.url
        if (url.isNullOrEmpty() || url == "about:blank") {
            webView.loadUrl(currentUrl)
        } else {
            webView.reload()
        }
    }

    /** 启动/点“自动”：探测内网可达性，可达走内网，否则走外网。 */
    private fun autoDetectAndLoad() {
        errorView.isVisible = false
        swipeRefresh.isVisible = true
        swipeRefresh.isRefreshing = true
        titleText.text = getString(R.string.detecting)
        Thread {
            val lanOk = isUrlReachable(URL_LAN)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadUrl(if (lanOk) URL_LAN else URL_WAN)
                toast(if (lanOk) "已连接内网地址" else "内网不可达，已使用外网地址")
            }
        }.start()
    }

    private fun isUrlReachable(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            false
        }
    }

    private fun updateModeButtons() {
        btnLan.isSelected = currentUrl == URL_LAN
        btnWan.isSelected = currentUrl == URL_WAN
    }

    private fun showError(message: String) {
        progressBar.isVisible = false
        swipeRefresh.isRefreshing = false
        swipeRefresh.isVisible = false
        errorView.isVisible = true
        errorMessage.text = message
        btnSwitch.text = getString(
            if (currentUrl == URL_LAN) R.string.switch_to_wan else R.string.switch_to_lan
        )
    }

    // ==================== 菜单 ====================

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, MENU_REFRESH, 0, "刷新页面")
            add(0, MENU_AUTO, 1, "自动选择地址")
            add(0, MENU_BROWSER, 2, "用系统浏览器打开")
            add(0, MENU_COPY, 3, "复制当前地址")
            add(0, MENU_KEEP_AWAKE, 4, "屏幕常亮").apply {
                isCheckable = true
                isChecked = keepAwake
            }
            add(0, MENU_ABOUT, 5, "关于")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REFRESH -> retry()
                MENU_AUTO -> autoDetectAndLoad()
                MENU_BROWSER -> openInBrowser()
                MENU_COPY -> copyCurrentUrl()
                MENU_KEEP_AWAKE -> {
                    keepAwake = !keepAwake
                    prefs.edit().putBoolean(KEY_KEEP_AWAKE, keepAwake).apply()
                    applyKeepAwake()
                    toast(if (keepAwake) "已开启屏幕常亮" else "已关闭屏幕常亮")
                }
                MENU_ABOUT -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun currentDisplayUrl(): String = webView.url?.takeIf { it != "about:blank" } ?: currentUrl

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

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                getString(
                    R.string.about_message,
                    BuildConfig.VERSION_NAME,
                    URL_LAN,
                    URL_WAN
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun applyKeepAwake() {
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
                if (webView.canGoBack() && !errorView.isVisible) {
                    webView.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressedAt < EXIT_INTERVAL_MS) {
                        finish()
                    } else {
                        lastBackPressedAt = now
                        toast(getString(R.string.exit_hint))
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_URL, currentUrl)
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
