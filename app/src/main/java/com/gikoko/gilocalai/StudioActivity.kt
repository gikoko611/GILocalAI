package com.gikoko.gilocalai

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
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
import org.json.JSONObject

class StudioActivity : Activity() {
    private lateinit var web: WebView
    private var encoder: H264Mp4Encoder? = null
    private var output: File? = null
    private var outputName = "GI-Animation.mp4"
    private var selectedAudioUri: Uri? = null
    private val audioPickerRequest = 4107

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF040B07.toInt()
        window.navigationBarColor = 0xFF0A1610.toInt()
        web = WebView(this).apply {
            setBackgroundColor(0xFF040B07.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "Studio")
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/studio.html")
    }

    private fun launchAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, audioPickerRequest)
    }

    @Deprecated("Legacy Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == audioPickerRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            selectedAudioUri = uri
            web.evaluateJavascript("window.onAudioPicked && window.onAudioPicked(" + JSONObject.quote(uri.toString()) + ")", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun pickAudio() { runOnUiThread { launchAudioPicker() } }
        @JavascriptInterface fun clearAudio() { selectedAudioUri = null }

        @JavascriptInterface fun generateVideo(json: String) {
            Thread {
                try {
                    val request = JSONObject(json)
                    val prompt = request.getString("prompt")
                    val system = "You are a professional motion designer and JavaScript Canvas animator. Return ONLY executable JavaScript code, no markdown. Define drawFrame(t, ctx, width, height) using CanvasRenderingContext2D. t is milliseconds. Draw the entire frame on every call; animation must be deterministic based only on t. Canvas 2D APIs only. No DOM, network, timers, imports, eval, or requestAnimationFrame. Optimize for Android phones. Create a polished cinematic animation matching the user prompt, with background, motion, lighting and visual details. No external images or fonts."
                    val msgs = org.json.JSONArray()
                    msgs.put(JSONObject().put("role", "system").put("content", system))
                    msgs.put(JSONObject().put("role", "user").put("content", prompt))
                    request.put("msgs", msgs); request.put("temp", 0.7); request.put("max", 2200)
                    val err = Online.chat(request, { false }, { _, _ -> }) { token ->
                        runOnUiThread { web.evaluateJavascript("window.onAiVideoChunk(" + JSONObject.quote(token) + ")", null) }
                    }
                    runOnUiThread { web.evaluateJavascript("window.onAiVideoDone(" + JSONObject.quote(err) + ")", null) }
                } catch (e: Throwable) {
                    val err = e.message ?: "AI video code generation failed"
                    runOnUiThread { web.evaluateJavascript("window.onAiVideoDone(" + JSONObject.quote(err) + ")", null) }
                }
            }.start()
        }
        @JavascriptInterface fun beginExport(width: Int, height: Int, fps: Int, name: String): String { return try {
            if (encoder != null) return "An export is already running"
            val w = width.coerceIn(160, 1280).let { it - it % 2 }
            val h = height.coerceIn(160, 1280).let { it - it % 2 }
            val rate = fps.coerceIn(12, 30)
            outputName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".mp4")) it else "$it.mp4" }
            output = File(cacheDir, "render_" + System.currentTimeMillis() + ".mp4")
            encoder = H264Mp4Encoder(w, h, rate, output!!)
            ""
        } catch (e: Throwable) { encoder = null; e.message ?: "Encoder initialization failed" } }

        @JavascriptInterface fun addFrame(dataUrl: String): String { return try {
            val raw = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return "Invalid frame image"
            try { encoder?.writeFrame(bmp) ?: return "Encoder is not running" } finally { bmp.recycle() }
            ""
        } catch (e: Throwable) { e.message ?: "Frame encoding failed" } }

        @JavascriptInterface fun finishExport(): String { return try {
            val e = encoder ?: return "No export is running"
            e.finish()
            encoder = null
            val file = output ?: return "MP4 output missing"
            if (!file.exists() || file.length() < 1024) return "MP4 validation failed"
            val soundtrack = selectedAudioUri
            if (soundtrack != null) {
                val muxed = File(cacheDir, "muxed_" + System.currentTimeMillis() + ".mp4")
                AudioMuxer.attach(this@StudioActivity, file, soundtrack, muxed)
                if (!muxed.exists() || muxed.length() < 1024) return "Audio mux validation failed"
                file.delete()
                if (!muxed.renameTo(file)) {
                    muxed.copyTo(file, overwrite = true)
                    muxed.delete()
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GI Local AI")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "Could not create Movies output"
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
            file.delete(); output = null
            "Saved|" + uri.toString()
        } catch (e: Throwable) { encoder = null; e.message ?: "MP4 finalization failed" } }

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
