package com.facu.geminibrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.Base64
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private enum class Mode { HOME, BROWSE, SHEET, AGENT }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var page: BounceWebView   // páginas web (sin puente a la IA)
    private lateinit var ui: WebView     // inicio + chat (HTML local, con puente)
    private lateinit var bar: LinearLayout
    private lateinit var pill: EditText
    private lateinit var agentBar: LinearLayout
    private lateinit var agentText: TextView
    private lateinit var agentStop: TextView
    @Volatile private var loading = false
    private val barButtons = ArrayList<TextView>()
    private var mode = Mode.HOME
    private var hasPage = false

    private val streams = ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun q(s: String): String = JSONObject.quote(s)
    private fun js(code: String) { ui.post { ui.evaluateJavascript(code, null) } }
    private fun isDark(): Boolean = when (prefs.getString("theme", "")) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gb", Context.MODE_PRIVATE)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        page = makePage()
        ui = makeUi()
        page.visibility = View.GONE
        content.addView(page, FrameLayout.LayoutParams(matchParent, matchParent))
        content.addView(ui, FrameLayout.LayoutParams(matchParent, matchParent))
        root.addView(content, LinearLayout.LayoutParams(matchParent, 0, 1f))
        buildBar()
        root.addView(bar, LinearLayout.LayoutParams(matchParent, dp(60)))
        bar.visibility = View.GONE
        buildAgentBar()
        root.addView(agentBar, LinearLayout.LayoutParams(matchParent, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        applyTheme()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val u = i?.data ?: return
        if (u.scheme == "http" || u.scheme == "https") openUrl(u.toString())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    override fun onPause() { super.onPause(); page.onPause(); ui.onPause() }
    override fun onResume() { super.onResume(); page.onResume(); ui.onResume() }
    override fun onDestroy() { page.destroy(); ui.destroy(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (mode) {
            Mode.SHEET -> showBrowse()
            Mode.AGENT -> js("GB.agentStop()")
            Mode.BROWSE -> if (page.canGoBack()) page.goBack() else showHome()
            Mode.HOME -> ui.evaluateJavascript("GB.back()") { r ->
                if (r != "true") { if (hasPage) showBrowse() else finish() }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (t != null) js("GB.onVoice(${q(t)})")
        }
        if (requestCode == 8 && resultCode == RESULT_OK) data?.data?.let { importFontFrom(it) }
        if (requestCode == 9 && resultCode == RESULT_OK && data != null) {
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) readAttachment(cd.getItemAt(i).uri)
            } else {
                data.data?.let { readAttachment(it) }
            }
        }
    }

    // ---------------------------------------------------------------- vistas

    @SuppressLint("SetJavaScriptEnabled")
    private fun makePage(): BounceWebView = BounceWebView(this).apply {
        bounce = prefs.getString("bounce", "1") != "0"
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        offForceDark(settings)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { loading = true; updateBar(url) }
            override fun onPageFinished(v: WebView, url: String) { loading = false; updateBar(url) }
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "http" || u.scheme == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                return true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeUi(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        offForceDark(settings)
        setBackgroundColor(Color.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_NEVER
        addJavascriptInterface(Bridge(), "Android")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                js("GB.setTheme('${if (isDark()) "dark" else "light"}')")
            }
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith("file:///android_asset/")) return false
                openUrl(u)
                return true
            }
        }
        loadUrl("file:///android_asset/ui/index.html")
    }

    @Suppress("DEPRECATION")
    private fun offForceDark(s: WebSettings) {
        if (Build.VERSION.SDK_INT in 29..32) s.forceDark = WebSettings.FORCE_DARK_OFF
    }

    private fun barButton(label: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener(onClick)
            barButtons.add(this)
        }

    private fun buildBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val back = barButton("←") { if (page.canGoBack()) page.goBack() }
        val fwd = barButton("→") { if (page.canGoForward()) page.goForward() }
        pill = EditText(this).apply {
            hint = "Pregunta o navega…"
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    val t = text.toString()
                    setText("")
                    clearFocus()
                    omnibox(t)
                    true
                } else false
            }
        }
        val ask = barButton("✨") { showSheet() }
        val more = barButton("⋮") { v -> showMenu(v) }
        bar.addView(back, LinearLayout.LayoutParams(dp(40), dp(44)))
        bar.addView(fwd, LinearLayout.LayoutParams(dp(40), dp(44)))
        bar.addView(pill, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        bar.addView(ask, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(more, LinearLayout.LayoutParams(dp(36), dp(44)))
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Inicio")
            menu.add(0, 2, 1, "Recargar")
            menu.add(0, 3, 2, "Compartir enlace")
            menu.add(0, 4, 3, "Ajustes de IA")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showHome()
                    2 -> page.reload()
                    3 -> page.url?.let { u ->
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "Compartir"))
                    }
                    4 -> { showSheet(); js("GB.openSettings()") }
                }
                true
            }
            show()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val dark = isDark()
        val bg = if (dark) 0xFF101012.toInt() else 0xFFF8F8F8.toInt()
        val pillBg = if (dark) 0xFF1E1F22.toInt() else 0xFFE9EDF3.toInt()
        val fg = if (dark) 0xFFE3E3E3.toInt() else 0xFF1F1F1F.toInt()
        val hint = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
        root.setBackgroundColor(bg)
        bar.setBackgroundColor(bg)
        agentBar.setBackgroundColor(bg)
        pill.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(pillBg) }
        pill.setTextColor(fg)
        pill.setHintTextColor(hint)
        agentText.setTextColor(fg)
        agentStop.background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(pillBg) }
        agentStop.setTextColor(fg)
        barButtons.forEach { it.setTextColor(fg) }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 30) {
            val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else f, f)
        } else {
            window.decorView.systemUiVisibility =
                if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
        js("GB.setTheme('${if (dark) "dark" else "light"}')")
    }

    // ---------------------------------------------------------------- modos

    private fun showHome() {
        mode = Mode.HOME
        page.visibility = View.GONE
        ui.visibility = View.VISIBLE
        content.bringChildToFront(ui)
        bar.visibility = View.GONE
        agentBar.visibility = View.GONE
        hideKeyboard()
        js("GB.setMode('home')")
    }

    private fun showBrowse() {
        mode = Mode.BROWSE
        page.visibility = View.VISIBLE
        ui.visibility = View.GONE
        bar.visibility = View.VISIBLE
        agentBar.visibility = View.GONE
        hideKeyboard()
    }

    private fun showSheet() {
        if (!hasPage) { showHome(); return }
        mode = Mode.SHEET
        page.visibility = View.VISIBLE
        ui.visibility = View.VISIBLE
        content.bringChildToFront(ui)
        bar.visibility = View.GONE
        agentBar.visibility = View.GONE
        js("GB.setMode('sheet')")
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun updateBar(url: String?) {
        val host = try { Uri.parse(url).host } catch (_: Exception) { null }
        pill.hint = host ?: "Pregunta o navega…"
    }

    // ---------------------------------------------------------------- barra única

    private fun toUrl(t: String): String? {
        if (t.startsWith("g ", ignoreCase = true)) {
            return "https://www.google.com/search?q=" + Uri.encode(t.substring(2).trim())
        }
        if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(t)) return t
        if (t.contains(' ')) return null
        if (Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/\\S*)?$").matches(t)) return "http://$t"
        if (Regex("^[\\w-]+(\\.[\\w-]+)*\\.[A-Za-z]{2,}(:\\d+)?(/\\S*)?$").matches(t)) return "https://$t"
        return null
    }

    private fun openUrl(url: String) {
        hasPage = true
        showBrowse()
        page.loadUrl(url)
    }

    private fun omnibox(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = toUrl(t)
        if (url != null) { openUrl(url); return }
        if (mode == Mode.BROWSE) showSheet()
        js("GB.ask(${q(t)})")
    }

    private fun sendPageText() {
        if (!hasPage || page.url == null) { js("GB.onPageText(null)"); return }
        val code = "(function(){var s=(window.getSelection()||'').toString().trim();" +
            "var t=document.body?document.body.innerText:'';" +
            "return JSON.stringify({title:document.title,url:location.href,selection:s.slice(0,6000),text:t.slice(0,15000)});})()"
        page.evaluateJavascript(code) { res -> js("GB.onPageText($res)") }
    }

    private fun emit(id: String, kind: String, a: String = "", b: String = "") =
        js("GB.onStream(${q(id)},${q(kind)},${q(a)},${q(b)})")

    // ---------------------------------------------------------------- fuentes

    private var fontCache: String? = null
    private val fontRoots = listOf("/system/fonts", "/system_ext/fonts", "/product/fonts", "/vendor/fonts")
    private fun fontsDir(): File = File(filesDir, "fonts").apply { mkdirs() }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun allowedFont(f: File): Boolean {
        val p = try { f.canonicalPath } catch (_: Exception) { return false }
        return fontRoots.any { p.startsWith("$it/") } || p.startsWith(fontsDir().canonicalPath + "/")
    }

    // Nombre de familia de un TTF/OTF (tabla "name", IDs 16 o 1). null si no es válido o es .ttc
    private fun fontFamily(f: File): String? = try {
        RandomAccessFile(f, "r").use { parseFamily(it) }
    } catch (_: Exception) { null }

    private fun parseFamily(r: RandomAccessFile): String? {
        if (r.length() < 12) return null
        val tag = r.readInt()
        if (tag != 0x00010000 && tag != 0x4F54544F && tag != 0x74727565) return null
        val n = r.readUnsignedShort()
        r.skipBytes(6)
        var off = -1L
        for (i in 0 until n) {
            val t = r.readInt()
            r.readInt()
            val o = r.readInt()
            r.readInt()
            if (t == 0x6E616D65) { off = o.toLong() and 0xFFFFFFFFL; break }
        }
        if (off < 0) return null
        r.seek(off)
        r.readUnsignedShort()
        val count = r.readUnsignedShort()
        val strOff = r.readUnsignedShort()
        var best: String? = null
        var bestRank = 99
        for (i in 0 until count) {
            val pid = r.readUnsignedShort()
            r.readUnsignedShort()
            r.readUnsignedShort()
            val nid = r.readUnsignedShort()
            val len = r.readUnsignedShort()
            val o = r.readUnsignedShort()
            if (nid != 1 && nid != 16) continue
            val uni = pid == 3 || pid == 0
            val rank = (if (nid == 16) 0 else 1) + (if (uni) 0 else 2)
            if (rank >= bestRank) continue
            val pos = r.filePointer
            val bytes = ByteArray(len)
            r.seek(off + strOff + o)
            r.readFully(bytes)
            r.seek(pos)
            best = String(bytes, if (uni) Charsets.UTF_16BE else Charsets.ISO_8859_1)
            bestRank = rank
        }
        val s = best?.trim()
        return if (s.isNullOrEmpty()) null else s
    }

    private fun importFontFrom(uri: Uri) {
        thread {
            try {
                var display = "fuente.ttf"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) display = c.getString(i) ?: display
                }
                val ext = display.substringAfterLast('.', "").lowercase()
                if (ext != "ttf" && ext != "otf") { runOnUiThread { toast("Elegí un archivo .ttf u .otf") }; return@thread }
                val dest = File(fontsDir(), display.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                contentResolver.openInputStream(uri)?.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                val name = fontFamily(dest)
                if (name == null || dest.length() > 6_000_000L) {
                    dest.delete()
                    runOnUiThread { toast("Fuente no válida o demasiado pesada (máx. 6 MB)") }
                    return@thread
                }
                fontCache = null
                js("GB.onFontImported(${q(name)},${q(dest.absolutePath)})")
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo importar la fuente") }
            }
        }
    }

    // ---------------------------------------------------------------- adjuntos, chats y agente

    private fun onUi(block: () -> Unit) {
        val l = java.util.concurrent.CountDownLatch(1)
        runOnUiThread { try { block() } finally { l.countDown() } }
        l.await()
    }

    private fun chatsDir(): File = File(filesDir, "chats").apply { mkdirs() }
    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "")
    private fun chatFile(id: String) = File(chatsDir(), safeId(id) + ".json")
    private fun metaFile(id: String) = File(chatsDir(), safeId(id) + ".meta")

    private fun docxText(bytes: ByteArray): String {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val xml = z.readBytes().toString(Charsets.UTF_8)
                    return xml.replace("</w:p>", "\n").replace("<w:tab/>", "\t").replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&apos;", "'").take(300_000)
                }
                e = z.nextEntry
            }
        }
        return ""
    }

    private fun readAttachment(uri: Uri) {
        thread {
            try {
                var name = "archivo"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = name.substringAfterLast('.', "").lowercase()
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@thread
                if (bytes.size > 10_000_000) {
                    runOnUiThread { toast("\"$name\" pesa más de 10 MB") }
                    return@thread
                }
                val textExt = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "js", "ts", "kt", "java", "py", "c", "cpp", "h", "css", "yml", "yaml", "log", "sql", "sh")
                when {
                    mime.startsWith("image/") || mime == "application/pdf" || mime.startsWith("audio/") ->
                        js("GB.onFile(${q(name)},${q(mime)},'inline',${q(Base64.encodeToString(bytes, Base64.NO_WRAP))})")
                    ext == "docx" -> js("GB.onFile(${q(name)},'text/plain','text',${q(docxText(bytes))})")
                    mime.startsWith("text/") || mime == "application/json" || ext in textExt ->
                        js("GB.onFile(${q(name)},'text/plain','text',${q(String(bytes, Charsets.UTF_8).take(300_000))})")
                    else -> runOnUiThread { toast("Formato no soportado: $name") }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo leer el archivo") }
            }
        }
    }

    // ---- agente: ejecución de acciones sobre la página (coordenadas normalizadas 0-1000)
    private fun tap(x: Float, y: Float) {
        val fx = x / page.width
        val fy = y / page.height
        onUi {
            page.evaluateJavascript(
                "(function(fx,fy){var d=document.createElement('div');d.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;width:26px;height:26px;margin:-13px 0 0 -13px;border-radius:50%;border:2px solid #8ab4f8;background:rgba(138,180,248,.4);box-shadow:0 0 14px 4px rgba(138,180,248,.55);left:'+fx*innerWidth+'px;top:'+fy*innerHeight+'px;transition:transform .5s,opacity .5s';document.documentElement.appendChild(d);requestAnimationFrame(function(){d.style.transform='scale(1.8)';d.style.opacity='0'});setTimeout(function(){d.remove()},700)})($fx,$fy)",
                null
            )
        }
        val t = SystemClock.uptimeMillis()
        onUi { val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); page.dispatchTouchEvent(e); e.recycle() }
        Thread.sleep(70)
        onUi { val e = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0); page.dispatchTouchEvent(e); e.recycle() }
    }

    private fun typeText(text: String, enter: Boolean, clear: Boolean) {
        val code = "(function(t,c){var e=document.activeElement;if(c){if(e&&e.select){try{e.select()}catch(x){}}else{document.execCommand('selectAll')}document.execCommand('delete');}document.execCommand('insertText',false,t);})(${q(text)},$clear)"
        onUi { page.evaluateJavascript(code, null) }
        if (enter) {
            Thread.sleep(250)
            pressKeys(listOf("Enter"))
        }
    }

    private fun keyCode(k: String): Int = when (k.lowercase()) {
        "enter", "return" -> KeyEvent.KEYCODE_ENTER
        "tab" -> KeyEvent.KEYCODE_TAB
        "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
        "backspace" -> KeyEvent.KEYCODE_DEL
        "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
        "space" -> KeyEvent.KEYCODE_SPACE
        "up", "arrowup" -> KeyEvent.KEYCODE_DPAD_UP
        "down", "arrowdown" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left", "arrowleft" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right", "arrowright" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "home" -> KeyEvent.KEYCODE_MOVE_HOME
        "end" -> KeyEvent.KEYCODE_MOVE_END
        "pageup" -> KeyEvent.KEYCODE_PAGE_UP
        "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
        else -> KeyEvent.keyCodeFromString("KEYCODE_" + k.uppercase())
    }

    private fun pressKeys(keys: List<String>) {
        val main = keys.lastOrNull()?.trim() ?: return
        var meta = 0
        for (m in keys.dropLast(1)) {
            meta = meta or when (m.trim().lowercase()) {
                "control", "ctrl", "controlormeta" -> KeyEvent.META_CTRL_ON
                "alt", "option" -> KeyEvent.META_ALT_ON
                "shift" -> KeyEvent.META_SHIFT_ON
                "meta", "cmd", "command", "win" -> KeyEvent.META_META_ON
                else -> 0
            }
        }
        val code = keyCode(main)
        if (code == KeyEvent.KEYCODE_UNKNOWN) throw Exception("Tecla no soportada: $main")
        val t = SystemClock.uptimeMillis()
        onUi {
            page.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
            page.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        }
    }

    private fun scrollJs(fx: Double, fy: Double, dir: String, fm: Double): String =
        "(function(fx,fy,dir,fm){var x=fx*innerWidth,y=fy*innerHeight;var dx=dir=='left'?-1:dir=='right'?1:0,dy=dir=='up'?-1:dir=='down'?1:0;var sx=dx*fm*innerWidth,sy=dy*fm*innerHeight;var el=document.elementFromPoint(x,y);while(el&&el!==document.body&&el!==document.documentElement){var s=getComputedStyle(el);if(/(auto|scroll)/.test(dy?s.overflowY:s.overflowX)&&(dy?el.scrollHeight>el.clientHeight:el.scrollWidth>el.clientWidth)){el.scrollBy({top:sy,left:sx,behavior:'instant'});return}el=el.parentElement}window.scrollBy({top:sy,left:sx,behavior:'instant'})})($fx,$fy,'$dir',$fm)"

    private fun doAct(name: String, a: JSONObject) {
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        val px = (a.optDouble("x", 500.0) / 1000.0 * w).toFloat()
        val py = (a.optDouble("y", 500.0) / 1000.0 * h).toFloat()
        when (name) {
            "open_web_browser", "take_screenshot", "move", "hover_at" -> {}
            "wait" -> Thread.sleep((minOf(10.0, a.optDouble("seconds", 1.0)) * 1000).toLong())
            "wait_5_seconds" -> Thread.sleep(5000)
            "navigate" -> {
                var u = a.optString("url")
                if (!Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(u)) u = "https://$u"
                onUi { page.loadUrl(u) }
            }
            "search" -> onUi { page.loadUrl("https://www.google.com") }
            "go_back" -> onUi { if (page.canGoBack()) page.goBack() }
            "go_forward" -> onUi { if (page.canGoForward()) page.goForward() }
            "click", "click_at", "right_click", "middle_click" -> tap(px, py)
            "double_click" -> {
                tap(px, py)
                Thread.sleep(120)
                tap(px, py)
            }
            "type" -> typeText(a.optString("text"), a.optBoolean("press_enter", false), false)
            "type_text_at" -> {
                tap(px, py)
                Thread.sleep(450)
                typeText(a.optString("text"), a.optBoolean("press_enter", false), a.optBoolean("clear_before_typing", true))
            }
            "press_key" -> pressKeys(listOf(a.optString("key")))
            "hotkey", "key_combination" -> {
                val k = a.opt("keys")
                val keys = if (k is org.json.JSONArray) (0 until k.length()).map { k.optString(it) } else k.toString().split("+")
                pressKeys(keys)
            }
            "scroll", "scroll_at", "scroll_document" -> {
                val doc = name == "scroll_document"
                val fx = if (doc) 0.5 else a.optDouble("x", 500.0) / 1000.0
                val fy = if (doc) 0.5 else a.optDouble("y", 500.0) / 1000.0
                val fm = (if (doc) 800.0 else a.optDouble("magnitude", a.optDouble("magnitude_in_pixels", 800.0))) / 1000.0
                val dir = a.optString("direction", "down").lowercase().filter { it.isLetter() }
                onUi { page.evaluateJavascript(scrollJs(fx, fy, dir, fm), null) }
                Thread.sleep(250)
            }
            else -> throw Exception("Acción no soportada: $name")
        }
    }

    private fun buildAgentBar() {
        agentBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        agentText = TextView(this).apply {
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            text = "Agente en marcha…"
        }
        agentStop = TextView(this).apply {
            text = "Detener"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { js("GB.agentStop()") }
        }
        agentBar.addView(agentText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        agentBar.addView(agentStop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showAgent() {
        if (!hasPage) {
            hasPage = true
            page.loadUrl("https://www.google.com")
        }
        mode = Mode.AGENT
        page.visibility = View.VISIBLE
        ui.visibility = View.VISIBLE
        content.bringChildToFront(page) // la página queda arriba; la interfaz sigue viva por debajo
        bar.visibility = View.GONE
        agentBar.visibility = View.VISIBLE
        agentText.text = "Agente en marcha…"
        hideKeyboard()
    }

    // ---------------------------------------------------------------- puente JS <-> Kotlin

    inner class Bridge {
        @JavascriptInterface
        fun getPrefs(): String = JSONObject(prefs.all).toString()

        @JavascriptInterface
        fun setPref(k: String, v: String) { prefs.edit().putString(k, v).apply() }

        @JavascriptInterface
        fun omnibox(text: String) { runOnUiThread { this@MainActivity.omnibox(text) } }

        @JavascriptInterface
        fun requestPageText() { runOnUiThread { sendPageText() } }

        @JavascriptInterface
        fun closeSheet() { runOnUiThread { showBrowse() } }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Gemini", text))
            }
        }

        @JavascriptInterface
        fun startVoice() {
            runOnUiThread {
                try {
                    startActivityForResult(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                        7
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No hay reconocimiento de voz disponible", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun listFonts(): String {
            fontCache?.let { return it }
            val byName = java.util.TreeMap<String, File>(String.CASE_INSENSITIVE_ORDER)
            val dirs = fontRoots.map { File(it) } + fontsDir()
            for (d in dirs) {
                for (f in d.listFiles() ?: emptyArray<File>()) {
                    val ext = f.extension.lowercase()
                    if ((ext != "ttf" && ext != "otf") || f.length() > 6_000_000L) continue
                    val name = fontFamily(f) ?: continue
                    if (name.startsWith("Noto") && name != "Noto Sans" && name != "Noto Serif") continue
                    if (Regex("(?i)emoji|symbol|icon|fallback").containsMatchIn(name)) continue
                    val prev = byName[name]
                    if (prev == null || (f.name.lowercase().contains("regular") && !prev.name.lowercase().contains("regular"))) byName[name] = f
                }
            }
            val arr = org.json.JSONArray()
            for ((n, f) in byName) arr.put(JSONObject().put("name", n).put("path", f.absolutePath))
            return arr.toString().also { fontCache = it }
        }

        @JavascriptInterface
        fun getFont(path: String): String = try {
            val f = File(path)
            if (!allowedFont(f) || !f.isFile || f.length() > 6_000_000L) ""
            else android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) { "" }

        @JavascriptInterface
        fun nativeFont(path: String) {
            runOnUiThread {
                pill.typeface = try {
                    if (path.isEmpty() || !allowedFont(File(path))) Typeface.DEFAULT else Typeface.createFromFile(path)
                } catch (_: Exception) { Typeface.DEFAULT }
            }
        }

        @JavascriptInterface
        fun importFont() {
            runOnUiThread {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 8)
                } catch (e: ActivityNotFoundException) {
                    toast("No hay selector de archivos disponible")
                }
            }
        }

        @JavascriptInterface
        fun setTheme(t: String) {
            prefs.edit().putString("theme", t).apply()
            runOnUiThread { applyTheme() }
        }

        @JavascriptInterface
        fun setBounce(on: Boolean) {
            prefs.edit().putString("bounce", if (on) "1" else "0").apply()
            runOnUiThread { page.bounce = on }
        }

        @JavascriptInterface
        fun pickFiles() {
            runOnUiThread {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                        9
                    )
                } catch (e: ActivityNotFoundException) {
                    toast("No hay selector de archivos disponible")
                }
            }
        }

        @JavascriptInterface
        fun pasteClip() {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val u = item?.uri
                val t = item?.text?.toString()
                when {
                    u != null -> readAttachment(u)
                    !t.isNullOrEmpty() -> js("GB.onVoice(${q(t)})")
                    else -> toast("El portapapeles está vacío")
                }
            }
        }

        @JavascriptInterface
        fun saveChat(id: String, title: String, updated: String, json: String) {
            try {
                chatFile(id).writeText(json)
                metaFile(id).writeText(
                    JSONObject().put("id", safeId(id)).put("title", title)
                        .put("updated", updated.toLongOrNull() ?: System.currentTimeMillis()).toString()
                )
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun listChats(): String {
            val arr = ArrayList<JSONObject>()
            for (f in chatsDir().listFiles() ?: emptyArray<File>()) {
                if (f.extension != "meta") continue
                try { arr.add(JSONObject(f.readText())) } catch (_: Exception) { }
            }
            arr.sortByDescending { it.optLong("updated") }
            return org.json.JSONArray(arr).toString()
        }

        @JavascriptInterface
        fun loadChat(id: String): String = try { chatFile(id).readText() } catch (_: Exception) { "[]" }

        @JavascriptInterface
        fun deleteChat(id: String) {
            chatFile(id).delete()
            metaFile(id).delete()
        }

        @JavascriptInterface
        fun isLoading(): Boolean = loading

        @JavascriptInterface
        fun agentBegin() { runOnUiThread { showAgent() } }

        @JavascriptInterface
        fun agentStatus(t: String) { runOnUiThread { agentText.text = t } }

        @JavascriptInterface
        fun agentEnd() { runOnUiThread { showSheet() } }

        @JavascriptInterface
        fun shot(id: String) {
            runOnUiThread {
                page.evaluateJavascript("location.href") { res ->
                    val url = try { (org.json.JSONTokener(res ?: "null").nextValue() as? String) ?: "" } catch (_: Exception) { "" }
                    try {
                        val w = page.width
                        val h = page.height
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        page.draw(Canvas(bmp))
                        val sc = if (w > 1000) 1000f / w else 1f
                        val out = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (w * sc).toInt(), (h * sc).toInt(), true) else bmp
                        val bos = ByteArrayOutputStream()
                        out.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                        js("GB.onShot(${q(id)},${q(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))},${q(url)})")
                    } catch (e: Exception) {
                        js("GB.onShot(${q(id)},'',${q(url)})")
                    }
                }
            }
        }

        @JavascriptInterface
        fun act(id: String, name: String, argsJson: String) {
            thread {
                var err = ""
                try { doAct(name, JSONObject(argsJson)) } catch (e: Exception) { err = e.message ?: e.toString() }
                js("GB.onAct(${q(id)},${q(err)})")
            }
        }

        @JavascriptInterface
        fun confirm(id: String, text: String) {
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("El agente quiere continuar")
                    .setMessage(text)
                    .setPositiveButton("Permitir") { _, _ -> js("GB.onConfirm(${q(id)},true)") }
                    .setNegativeButton("Cancelar") { _, _ -> js("GB.onConfirm(${q(id)},false)") }
                    .setOnCancelListener { js("GB.onConfirm(${q(id)},false)") }
                    .show()
            }
        }

        @JavascriptInterface
        fun generate(id: String, model: String, body: String) {
            val key = prefs.getString("apiKey", "") ?: ""
            thread {
                var code = 0
                var text = ""
                var conn: HttpURLConnection? = null
                try {
                    conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 120000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    code = conn.responseCode
                    val s = if (code in 200..299) conn.inputStream else conn.errorStream
                    text = s?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                } catch (e: Exception) {
                    text = e.message ?: e.toString()
                } finally {
                    conn?.disconnect()
                }
                js("GB.onGen(${q(id)},$code,${q(text)})")
            }
        }

        @JavascriptInterface
        fun cancel(id: String) {
            cancelled.add(id)
            streams[id]?.disconnect()
        }

        // Llamada a la API de Gemini hecha desde Kotlin: la API key nunca sale del teléfono
        // hacia el HTML y se evita cualquier problema de CORS.
        @JavascriptInterface
        fun stream(id: String, model: String, body: String) {
            val key = prefs.getString("apiKey", "") ?: ""
            thread {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
                    conn = url.openConnection() as HttpURLConnection
                    streams[id] = conn
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 120000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                        emit(id, "http", code.toString(), err)
                        return@thread
                    }
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.startsWith("data:")) emit(id, "data", line.substring(5).trim())
                        }
                    }
                    emit(id, "done")
                } catch (e: Exception) {
                    if (cancelled.remove(id)) emit(id, "cancelled") else emit(id, "error", e.message ?: e.toString())
                } finally {
                    streams.remove(id)
                    conn?.disconnect()
                }
            }
        }
    }
}
