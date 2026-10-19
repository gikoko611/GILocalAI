package com.gikoko.gilocalai

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Resumable downloads (HTTP Range). Data goes to <dir>/<name>.part and is renamed when complete. */
class Downloader(private val dir: File, private val report: (String) -> Unit) {
    private class Job(val url: String, val name: String) {
        @Volatile var pause = false
        @Volatile var cancel = false
        var thread: Thread? = null
    }

    private val jobs = HashMap<String, Job>()

    fun start(url: String, name: String) {
        if (!url.startsWith("https://")) { send(name, 0, 0, "error", "Only https links are allowed"); return }
        synchronized(jobs) {
            if (jobs[name]?.thread?.isAlive == true) return
            val job = Job(url, name)
            jobs[name] = job
            job.thread = Thread { work(job) }.also { it.start() }
        }
    }

    fun pause(name: String) { synchronized(jobs) { jobs[name]?.pause = true } }

    fun cancel(name: String) {
        synchronized(jobs) {
            val j = jobs[name]
            if (j?.thread?.isAlive == true) { j.cancel = true; j.pause = true }
            else File(dir, "$name.part").delete()
        }
    }

    private fun send(name: String, done: Long, total: Long, state: String, msg: String = "", path: String = "") {
        report(JSONObject().put("name", name).put("done", done).put("total", total)
            .put("state", state).put("msg", msg).put("path", path).toString())
    }

    private fun open(u: URL, have: Long): HttpURLConnection {
        val c = u.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "GI-local-AI")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        return c
    }

    private fun work(job: Job) {
        val part = File(dir, job.name + ".part")
        val out = File(dir, job.name)
        var have = if (part.exists()) part.length() else 0L
        var total = 0L
        try {
            var url = URL(job.url)
            var conn = open(url, have)
            var hops = 0
            while (conn.responseCode in 300..399 && hops < 6) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc)
                conn.disconnect()
                conn = open(url, have)
                hops++
            }
            val code = conn.responseCode
            if (code == 416 && have > 0) {
                out.delete(); part.renameTo(out)
                send(job.name, have, have, "done", path = out.absolutePath)
                return
            }
            if (code != 200 && code != 206) throw RuntimeException("Server answered $code")
            if (code == 200) have = 0L
            val len = conn.contentLengthLong
            total = if (len > 0) have + len else 0L
            if (total > 0 && dir.usableSpace < total - have + 50_000_000L) throw RuntimeException("Not enough storage space")
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(have)
                raf.seek(have)
                val buf = ByteArray(65536)
                var last = 0L
                conn.inputStream.use { inp ->
                    while (!job.pause) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        have += n
                        val now = System.currentTimeMillis()
                        if (now - last > 500) { last = now; send(job.name, have, total, "run") }
                    }
                }
            }
            when {
                job.cancel -> { part.delete(); send(job.name, 0, total, "pause") }
                job.pause -> send(job.name, have, total, "pause")
                total > 0 && have < total -> send(job.name, have, total, "error", "Connection lost. Tap Resume.")
                else -> { out.delete(); part.renameTo(out); send(job.name, have, total, "done", path = out.absolutePath) }
            }
        } catch (e: Throwable) {
            send(job.name, have, total, "error", e.message ?: "Download failed")
        }
    }
}
