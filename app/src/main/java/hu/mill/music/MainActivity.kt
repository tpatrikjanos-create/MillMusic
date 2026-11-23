package hu.mill.music

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val MATCH = -1
private const val WRAP = -2
private val BG = 0xFF0F0F14.toInt()

/** Közvetítő a lejátszó-szolgáltatás (értesítés, lezárt képernyő, fülhallgató gombok) és a felület között. */
object Bus {
    var activity: MainActivity? = null
    fun command(c: String, arg: Long = 0) {
        activity?.mediaCommand(c, arg)
    }
}

/**
 * WebView, ami mindig "láthatónak" hiszi magát.
 * Ettől nem áll le a YouTube lejátszó lezárt / kikapcsolt képernyőnél.
 */
class KeepAliveWebView(ctx: Context) : WebView(ctx) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var ui: KeepAliveWebView
    private lateinit var yt: KeepAliveWebView
    private lateinit var ytBox: LinearLayout
    private lateinit var browseBar: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var uiReady = false
    private var pendingShare: String? = null
    private var ytMode = "hidden"

    companion object {
        const val REQ_TREE = 11
        const val REQ_FILES = 12
        val AUDIO_EXT = Regex(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav|wma)$", RegexOption.IGNORE_CASE)
        val YT_ID = Regex("[A-Za-z0-9_-]{6,20}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bus.activity = this
        WebView.setWebContentsDebuggingEnabled(true)
        window.decorView.setBackgroundColor(BG)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }

        ui = KeepAliveWebView(this)
        setupUi()
        root.addView(ui, FrameLayout.LayoutParams(MATCH, MATCH))

        ytBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        yt = KeepAliveWebView(this)
        setupYt()
        ytBox.addView(yt, LinearLayout.LayoutParams(MATCH, 0, 1f))
        browseBar = makeBrowseBar()
        browseBar.visibility = View.GONE
        ytBox.addView(browseBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(ytBox, FrameLayout.LayoutParams(1, 1))

        setContentView(root)

        // Android 15+ (edge-to-edge): ne csússzon a felület az állapotsor / navigációs sáv / billentyűzet alá
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            } else ins
        }

        ui.loadUrl("https://${LocalServer.HOST}/index.html")
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    // ---------- WebView beállítások ----------

    private fun setupUi() {
        ui.setBackgroundColor(BG)
        ui.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // http-s rádióadók miatt
        }
        ui.addJavascriptInterface(Bridge(), "Android")
        ui.webChromeClient = WebChromeClient()
        ui.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.let { LocalServer.handle(this@MainActivity, it) }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                if (u.host == LocalServer.HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                uiReady = true
                pendingShare?.let { deliverShare(it) }
                pendingShare = null
            }
        }
    }

    private fun setupYt() {
        yt.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // "wv" jelölés nélkül a YouTube teljes mobil oldalt ad (és nagyobb eséllyel enged bejelentkezni)
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(yt, true)
        }
        yt.addJavascriptInterface(YtBridge(), "MMYT")
        yt.webChromeClient = WebChromeClient()
        yt.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val s = request?.url?.scheme ?: return true
                return !(s == "http" || s == "https") // intent:// és vnd.youtube: tiltva (ne nyissa a YouTube appot)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                view?.evaluateJavascript(YtScript.JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(YtScript.JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                view?.evaluateJavascript(YtScript.JS, null)
            }
        }
    }

    private fun makeBrowseBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1C1C27.toInt())
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        fun add(text: String, onClick: () -> Unit) {
            val b = Button(this).apply {
                this.text = text
                isAllCaps = false
                textSize = 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF2E2E40.toInt())
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            val m = (4 * resources.displayMetrics.density).toInt()
            lp.setMargins(m, 0, m, 0)
            bar.addView(b, lp)
        }
        add("←  Vissza") { if (yt.canGoBack()) yt.goBack() else closeBrowse() }
        add("＋  Listához") {
            val u = yt.url ?: ""
            val t = yt.title ?: ""
            setYtMode("hidden")
            js("MM.onBrowseAdd(${q(u)},${q(t)})")
        }
        add("✕  Bezár") { closeBrowse() }
        return bar
    }

    private fun closeBrowse() {
        setYtMode("hidden")
        js("MM.onBrowseClose()")
    }

    /** hidden = 1 px (szól, de nem látszik) · video = 16:9 felül · browse = teljes képernyő */
    private fun setYtMode(mode: String) {
        ytMode = mode
        val lp = when (mode) {
            "browse" -> FrameLayout.LayoutParams(MATCH, MATCH)
            "video" -> {
                val w = if (root.width > 0) root.width - root.paddingLeft - root.paddingRight
                else resources.displayMetrics.widthPixels
                FrameLayout.LayoutParams(MATCH, w * 9 / 16, Gravity.TOP)
            }
            else -> FrameLayout.LayoutParams(1, 1)
        }
        ytBox.layoutParams = lp
        browseBar.visibility = if (mode == "browse") View.VISIBLE else View.GONE
        ytBox.bringToFront()
    }

    // ---------- Életciklus ----------
    // Szándékosan NINCS ui.onPause()/yt.onPause(): a lejátszásnak háttérben is mennie kell.

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (ytMode == "browse") {
            if (yt.canGoBack()) yt.goBack() else closeBrowse()
            return
        }
        ui.evaluateJavascript("(window.MM&&MM.back())?'y':'n'") { r ->
            if (r != "\"y\"") moveTaskToBack(true) // nem zárjuk be, csak háttérbe tesszük
        }
    }

    override fun onDestroy() {
        if (Bus.activity === this) Bus.activity = null
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            if (uiReady) deliverShare(t) else pendingShare = t
        }
    }

    private fun deliverShare(t: String) = js("MM.onShared(${q(t)})")

    fun mediaCommand(c: String, arg: Long) = js("window.MM&&MM.onMedia(${q(c)},$arg)")

    private fun js(code: String) {
        main.post { ui.evaluateJavascript(code, null) }
    }

    private fun q(s: String): String =
        JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun toastMsg(m: String) {
        main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    }

    // ---------- Helyi fájlok ----------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> {
                val uri = data.data ?: return
                persist(uri)
                toastMsg("Mappa beolvasása…")
                thread { scanTree(uri) }
            }
            REQ_FILES -> {
                val list = mutableListOf<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
                } else {
                    data.data?.let { list.add(it) }
                }
                list.forEach { persist(it) }
                thread {
                    val arr = JSONArray()
                    list.forEach { arr.put(meta(it, displayName(it))) }
                    js("MM.onLocalTracks(${q(arr.toString())})")
                }
            }
        }
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun displayName(uri: Uri): String {
        val n: String? = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return n ?: (uri.lastPathSegment ?: "ismeretlen")
    }

    private fun scanTree(tree: Uri) {
        val arr = JSONArray()
        fun walk(docId: String, depth: Int) {
            if (depth > 12) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val rows = mutableListOf<Triple<String, String, String>>()
            try {
                contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        rows.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: ""))
                    }
                }
            } catch (_: Exception) {
            }
            for ((id, name, mime) in rows) {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(id, depth + 1)
                } else if (mime.startsWith("audio/") || AUDIO_EXT.matches(name)) {
                    arr.put(meta(DocumentsContract.buildDocumentUriUsingTree(tree, id), name))
                    if (arr.length() % 25 == 0) js("MM.onScanProgress(${arr.length()})")
                }
            }
        }
        try {
            walk(DocumentsContract.getTreeDocumentId(tree), 0)
        } catch (_: Exception) {
        }
        js("MM.onLocalTracks(${q(arr.toString())})")
    }

    /** ID3 / fájl-metaadatok: cím, előadó, műfaj, album, hossz */
    private fun meta(uri: Uri, name: String): JSONObject {
        val o = JSONObject().put("uri", uri.toString()).put("name", name)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            o.put("title", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "")
            o.put("artist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
            o.put("genre", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "")
            o.put("album", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "")
            o.put("dur", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0")
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        return o
    }

    // ---------- JS → natív híd (felület) ----------

    inner class Bridge {
        @JavascriptInterface
        fun ytPlay(id: String) {
            if (!YT_ID.matches(id)) return
            main.post { yt.loadUrl("https://m.youtube.com/watch?v=$id") }
        }

        @JavascriptInterface
        fun ytCmd(cmd: String, arg: Double) {
            if (cmd !in setOf("play", "pause", "seek")) return
            main.post { yt.evaluateJavascript("window.__mmCmd&&__mmCmd('$cmd',$arg)", null) }
        }

        @JavascriptInterface
        fun ytStop() {
            main.post { yt.loadUrl("about:blank") }
        }

        @JavascriptInterface
        fun ytMode(mode: String) {
            main.post { setYtMode(mode) }
        }

        @JavascriptInterface
        fun ytBrowse(url: String) {
            main.post {
                if (url.startsWith("https://")) yt.loadUrl(url)
                setYtMode("browse")
            }
        }

        @JavascriptInterface
        fun ytLogin() {
            main.post {
                yt.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F")
                setYtMode("browse")
            }
        }

        @JavascriptInterface
        fun session(title: String, artist: String, playing: Boolean, pos: Double, dur: Double) {
            main.post { PlaybackService.update(applicationContext, title, artist, playing, pos.toLong(), dur.toLong()) }
        }

        @JavascriptInterface
        fun pickFolder() {
            main.post {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        ), REQ_TREE
                    )
                } catch (_: Exception) {
                    toastMsg("A mappaválasztó nem nyitható meg")
                }
            }
        }

        @JavascriptInterface
        fun pickFiles() {
            main.post {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("audio/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                        REQ_FILES
                    )
                } catch (_: Exception) {
                    toastMsg("A fájlválasztó nem nyitható meg")
                }
            }
        }

        /** CORS nélküli GET (pl. YouTube oEmbed címlekéréshez) */
        @JavascriptInterface
        fun httpGet(url: String, id: Int) {
            thread {
                var code = 0
                var body = ""
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    c.setRequestProperty("User-Agent", "MillMusic/0.1")
                    code = c.responseCode
                    val s = if (code < 400) c.inputStream else c.errorStream
                    body = s?.bufferedReader()?.use { it.readText().take(500000) } ?: ""
                    c.disconnect()
                } catch (e: Exception) {
                    body = e.message ?: ""
                }
                js("MM.onHttp($id,$code,${q(body)})")
            }
        }

        @JavascriptInterface
        fun batteryOk(): Boolean =
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

        @JavascriptInterface
        fun requestBattery() {
            main.post {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    openAppSettings()
                }
            }
        }

        @JavascriptInterface
        fun openAppSettings() {
            main.post {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun toast(msg: String) = toastMsg(msg)

        @JavascriptInterface
        fun version(): String = "0.1"
    }

    // ---------- YouTube oldal → natív → felület ----------

    inner class YtBridge {
        @JavascriptInterface
        fun post(json: String) {
            js("window.MM&&MM.onYt(${q(json)})")
        }
    }
}
