package com.gikoko.gilocalai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pfd: ParcelFileDescriptor? = null
    private var ctx = 1024
    private var thr = 4
    private val busy = AtomicBoolean(false)
    private var loadedName = ""
    private var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF040B07.toInt()
        window.navigationBarColor = 0xFF0A1610.toInt()
        web = WebView(this)
        web.setBackgroundColor(0xFF040B07.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return try { startActivityForResult(p.createIntent(), 2); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun q(s: String) = JSONObject.quote(s)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) data?.data?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            loadModel(it)
        }
        if (req == 2) {
            fileCb?.onReceiveValue(if (res == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(res, data) else null)
            fileCb = null
        }
    }

    private fun loadModel(uri: Uri) {
        var name = "model.gguf"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        if (!name.endsWith(".gguf", true)) { js("window.onModelError(" + q("That file is not a .gguf model.") + ")"); return }
        js("window.onModelLoading(" + q(name) + ")")
        Thread {
            try {
                val head = ByteArray(4)
                contentResolver.openInputStream(uri)?.use { it.read(head) }
                if (String(head, Charsets.ISO_8859_1) != "GGUF") { js("window.onModelError(" + q("This file is not a valid GGUF model.") + ")"); return@Thread }
                loadedName = ""; Llama.unload(); pfd?.close(); pfd = null
                val p = contentResolver.openFileDescriptor(uri, "r")!!
                var err = Llama.load("/proc/self/fd/" + p.fd, ctx, thr)
                val copy = File(filesDir, "model.gguf")
                if (err.isNotEmpty()) {
                    p.close()
                    contentResolver.openInputStream(uri)!!.use { i -> copy.outputStream().use { o -> i.copyTo(o) } }
                    err = Llama.load(copy.absolutePath, ctx, thr)
                } else { pfd = p; copy.delete() }
                if (err.isEmpty()) { loadedName = name; js("window.onModelLoaded(" + q(name) + "," + size + "," + q(uri.toString()) + ")") }
                else js("window.onModelError(" + q(err) + ")")
            } catch (e: Throwable) {
                js("window.onModelError(" + q(e.message ?: "Could not open the file.") + ")")
            }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface fun pickModel(c: Int, t: Int) {
            ctx = c; thr = t
            runOnUiThread {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, 1)
            }
        }
        @JavascriptInterface fun stop() { Llama.stop() }
        @JavascriptInterface fun loadedName(): String = this@MainActivity.loadedName
        @JavascriptInterface fun loadModel(uri: String, c: Int, t: Int) { ctx = c; thr = t; loadModel(Uri.parse(uri)) }
        @JavascriptInterface fun copy(text: String) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GI", text))
        }
        @JavascriptInterface fun share(text: String) {
            runOnUiThread {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Export chat"))
            }
        }
        @JavascriptInterface fun generate(json: String) {
            if (!busy.compareAndSet(false, true)) return
            Thread {
                var err = ""
                try {
                    val o = JSONObject(json)
                    val a = o.getJSONArray("msgs")
                    val roles = Array(a.length()) { a.getJSONObject(it).getString("role") }
                    val cont = Array(a.length()) { a.getJSONObject(it).getString("content").toByteArray(Charsets.UTF_8) }
                    err = Llama.generate(roles, cont, o.getDouble("temp").toFloat(), o.getInt("max"), object : TokenCallback {
                        override fun onToken(b: ByteArray) { js("window.onToken(" + q(String(b, Charsets.UTF_8)) + ")") }
                    })
                } catch (e: Throwable) { err = e.message ?: "Error" }
                busy.set(false)
                js("window.onGenDone(" + q(err) + ")")
            }.start()
        }
    }
}
