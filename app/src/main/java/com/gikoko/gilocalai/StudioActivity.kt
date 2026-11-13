package com.gikoko.gilocalai

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream

class StudioActivity : Activity() {
    private lateinit var web: WebView
    private var encoder: H264Mp4Encoder? = null
    private var output: File? = null
    private var outputName = "GI-Animation.mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF040B07.toInt()
        window.navigationBarColor = 0xFF0A1610.toInt()
        web = WebView(this).apply {
            setBackgroundColor(0xFF040B07.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "Studio")
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/studio.html")
    }

    inner class Bridge {
        @JavascriptInterface fun beginExport(width: Int, height: Int, fps: Int, name: String): String = try {
            if (encoder != null) return "An export is already running"
            val w = width.coerceIn(160, 1280).let { it - it % 2 }
            val h = height.coerceIn(160, 1280).let { it - it % 2 }
            val rate = fps.coerceIn(12, 30)
            outputName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".mp4")) it else "$it.mp4" }
            output = File(cacheDir, "render_" + System.currentTimeMillis() + ".mp4")
            encoder = H264Mp4Encoder(w, h, rate, output!!)
            ""
        } catch (e: Throwable) { encoder = null; e.message ?: "Encoder initialization failed" }

        @JavascriptInterface fun addFrame(dataUrl: String): String = try {
            val raw = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return "Invalid frame image"
            try { encoder?.writeFrame(bmp) ?: return "Encoder is not running" } finally { bmp.recycle() }
            ""
        } catch (e: Throwable) { e.message ?: "Frame encoding failed" }

        @JavascriptInterface fun finishExport(): String = try {
            val e = encoder ?: return "No export is running"
            e.finish()
            encoder = null
            val file = output ?: return "MP4 output missing"
            if (!file.exists() || file.length() < 1024) return "MP4 validation failed"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GI Local AI")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "Could not create Movies output"
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
            file.delete(); output = null
            "Saved to Movies/GI Local AI/$outputName"
        } catch (e: Throwable) { encoder = null; e.message ?: "MP4 finalization failed" }

        @JavascriptInterface fun cancelExport() {
            try { encoder?.abort() } catch (_: Throwable) {}
            encoder = null
            output?.delete()
            output = null
        }
        @JavascriptInterface fun closeStudio() = runOnUiThread { finish() }
    }

    override fun onDestroy() {
        try { encoder?.abort() } catch (_: Throwable) {}
        encoder = null
        web.destroy()
        super.onDestroy()
    }
}
