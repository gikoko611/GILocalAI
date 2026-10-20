package com.gikoko.gilocalai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cloud AI helpers: streamed chat (OpenAI-compatible / Anthropic), model list, image generation. */
object Online {
    private fun base(o: JSONObject) = o.getString("url").trim().trimEnd('/')

    private fun auth(c: HttpURLConnection, anth: Boolean, key: String) {
        if (anth) {
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            c.setRequestProperty("Authorization", "Bearer $key")
        }
    }

    private fun text(o: JSONObject?, k: String): String = if (o == null || o.isNull(k)) "" else o.optString(k)

    private fun errMsg(t: String): String = try {
        val e = JSONObject(t).opt("error")
        if (e is JSONObject) e.optString("message", t) else e?.toString() ?: t
    } catch (_: Exception) {
        t
    }

    /** Returns "" on success, otherwise an error message. */
    fun chat(o: JSONObject, cancelled: () -> Boolean, onUsage: (Int, Int) -> Unit, onText: (String) -> Unit): String {
        val anth = o.optString("fmt") == "anthropic"
        val r = call(o, cancelled, onUsage, onText, !anth)
        // some providers reject stream_options: retry once without it
        return if (!anth && r.startsWith("HTTP 400")) call(o, cancelled, onUsage, onText, false) else r
    }

    private fun call(o: JSONObject, cancelled: () -> Boolean, onUsage: (Int, Int) -> Unit, onText: (String) -> Unit, usageOpt: Boolean): String {
        val b = base(o)
        if (!b.startsWith("https://")) return "The Base URL must start with https://"
        val anth = o.optString("fmt") == "anthropic"
        val msgs = o.getJSONArray("msgs")
        val temp = o.optDouble("temp", 0.7)
        val body = JSONObject()
            .put("model", o.getString("model"))
            .put("stream", true)
            .put("temperature", if (anth) Math.min(temp, 1.0) else temp)
            .put("max_tokens", o.optInt("max", 256))
        if (usageOpt) body.put("stream_options", JSONObject().put("include_usage", true))
        val list = JSONArray()
        var system = ""
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            if (anth && m.getString("role") == "system") system += m.getString("content") + "\n"
            else list.put(JSONObject().put("role", m.getString("role")).put("content", m.getString("content")))
        }
        body.put("messages", list)
        if (anth && system.isNotBlank()) body.put("system", system.trim())

        val c = URL(b + (if (anth) "/messages" else "/chat/completions")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        auth(c, anth, o.optString("key"))
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = c.responseCode
        if (code !in 200..299) {
            val txt = (c.errorStream?.bufferedReader()?.use { it.readText() } ?: "").take(600)
            c.disconnect()
            return "HTTP $code: " + errMsg(txt)
        }
        var inTok = 0
        var outTok = 0
        var gotUsage = false
        c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
            while (!cancelled()) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.substring(5).trim()
                if (d == "[DONE]") break
                try {
                    val j = JSONObject(d)
                    if (anth) {
                        when (j.optString("type")) {
                            "message_start" -> {
                                inTok = j.optJSONObject("message")?.optJSONObject("usage")?.optInt("input_tokens") ?: 0
                                gotUsage = true
                            }
                            "message_delta" -> {
                                outTok = j.optJSONObject("usage")?.optInt("output_tokens") ?: outTok
                                gotUsage = true
                            }
                            "content_block_delta" -> {
                                val t = text(j.optJSONObject("delta"), "text")
                                if (t.isNotEmpty()) onText(t)
                            }
                        }
                    } else {
                        val u = j.optJSONObject("usage")
                        if (u != null) {
                            inTok = u.optInt("prompt_tokens")
                            outTok = u.optInt("completion_tokens")
                            gotUsage = true
                        }
                        val t = text(j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta"), "content")
                        if (t.isNotEmpty()) onText(t)
                    }
                } catch (_: Exception) {
                }
            }
        }
        c.disconnect()
        if (gotUsage) onUsage(inTok, outTok)
        return ""
    }

    /** GET {base}/models. Returns (status, body). */
    fun get(o: JSONObject): Pair<Int, String> {
        val b = base(o)
        if (!b.startsWith("https://")) return Pair(0, "The Base URL must start with https://")
        val c = URL("$b/models").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        auth(c, o.optString("fmt") == "anthropic", o.optString("key"))
        val st = c.responseCode
        val txt = ((if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "").take(3000000)
        c.disconnect()
        return Pair(st, txt)
    }

    /** POST {base}/images/generations. Returns (base64 image, error). */
    fun image(o: JSONObject): Pair<String, String> {
        val b = base(o)
        if (!b.startsWith("https://")) return Pair("", "The Base URL must start with https://")
        val body = JSONObject()
            .put("model", o.getString("model"))
            .put("prompt", o.getString("prompt"))
            .put("n", 1)
            .put("size", o.optString("size", "1024x1024"))
        val c = URL("$b/images/generations").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 180000
        c.setRequestProperty("Content-Type", "application/json")
        auth(c, false, o.optString("key"))
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) {
            val txt = (c.errorStream?.bufferedReader()?.use { it.readText() } ?: "").take(600)
            c.disconnect()
            return Pair("", "HTTP $code: " + errMsg(txt))
        }
        val txt = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        c.disconnect()
        val d = JSONObject(txt).getJSONArray("data").getJSONObject(0)
        val b64 = if (d.isNull("b64_json")) "" else d.optString("b64_json")
        if (b64.isNotEmpty()) return Pair(b64, "")
        val u = if (d.isNull("url")) "" else d.optString("url")
        if (u.startsWith("https://")) {
            val bytes = URL(u).openStream().use { it.readBytes() }
            return Pair(Base64.encodeToString(bytes, Base64.NO_WRAP), "")
        }
        return Pair("", "The provider returned no image")
    }
}
