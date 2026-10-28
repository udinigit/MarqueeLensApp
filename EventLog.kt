package com.marqueelens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A light log of what the app did (titles read, films found, corrections, errors), for fixing problems.
 * Kept on the device until sent to your server in batches (every 10 minutes, at 100 events, or right after an error
 * or a problem report); the server keeps them 24 hours. The last few minutes also stay in memory for problem reports.
 */
object EventLog {
    private const val MAX_UNSENT = 500
    private const val BATCH = 100
    private val lock = Any()
    private val recent = ArrayDeque<JSONObject>()        // the last few minutes, for problem reports
    private val sender = Executors.newSingleThreadExecutor()
    private var unsent = 0
    @Volatile private var lastUpload = 0L

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "events.jsonl")

    /** Adds an event: a type ("lookup", "miss", "chip", "error"…) and a few short fields. */
    fun add(ctx: Context, type: String, data: JSONObject = JSONObject()) {
        val e = JSONObject().put("t", System.currentTimeMillis()).put("type", type)
        data.keys().forEach { k -> e.put(k, data.get(k)) }
        synchronized(lock) {
            recent.addLast(e); while (recent.size > 400) recent.removeFirst()
            runCatching { file(ctx).appendText(e.toString() + "\n") }
            unsent++
        }
        if (type == "error" || type == "report" || unsent >= BATCH) upload(ctx)
    }

    /** Events from the last [seconds] seconds (newest last). */
    fun recent(seconds: Int): JSONArray {
        val since = System.currentTimeMillis() - seconds * 1000L
        return synchronized(lock) { JSONArray(recent.filter { it.optLong("t") >= since }) }
    }

    /** A problem report: your note, the last two minutes of events and the last scan of the screen. */
    fun report(ctx: Context, note: String, extra: JSONObject = JSONObject()) {
        val scan = runCatching { JSONObject(Store(ctx).web.getString("ov.lastScan", null) ?: "{}") }.getOrDefault(JSONObject())
        add(ctx, "report", JSONObject().put("note", note.take(500)).put("recent", recent(120)).put("scan", scan).apply {
            extra.keys().forEach { k -> put(k, extra.get(k)) }
        })
    }

    fun clear(ctx: Context) = synchronized(lock) { recent.clear(); unsent = 0; runCatching { file(ctx).delete() } }

    /** Sends what hasn't been sent yet, in the background. */
    fun upload(ctx: Context) { sender.execute { uploadNow(ctx) } }

    /** Every 10 minutes is enough when nothing urgent happened. */
    fun uploadIfDue(ctx: Context) { if (System.currentTimeMillis() - lastUpload > 10 * 60_000) upload(ctx) }

    /** Sends now (call off the main thread). Returns false if it couldn't reach the server. */
    fun uploadNow(ctx: Context): Boolean {
        val store = Store(ctx)
        if (!store.viaServer) return false
        val lines = synchronized(lock) {
            val f = file(ctx)
            val l = runCatching { f.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
            runCatching { f.writeText("") }; unsent = 0; l
        }
        lastUpload = System.currentTimeMillis()
        if (lines.isEmpty()) return true
        val events = JSONArray(); lines.takeLast(MAX_UNSENT).forEach { runCatching { events.put(JSONObject(it)) } }
        val body = JSONObject().put("device", if (OverlayService.isTv(ctx)) "tv" else "phone").put("version", version(ctx)).put("events", events).toString()
        val ok = runCatching {
            val c = URL("${store.server}/logs").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 15000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("X-Invite", store.invite)
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode in 200..299
            } finally { c.disconnect() }
        }.getOrDefault(false)
        if (!ok) synchronized(lock) {   // keep them for the next try (the newest 500)
            val f = file(ctx)
            val now = runCatching { f.readLines() }.getOrDefault(emptyList())
            runCatching { f.writeText((lines + now).filter { it.isNotBlank() }.takeLast(MAX_UNSENT).joinToString("\n", postfix = "\n")) }
            unsent = lines.size + now.size
        }
        return ok
    }

    fun version(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")

    /** Shorthand: JSONObject from pairs, skipping empty values. */
    fun of(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        for ((k, v) in pairs) if (v != null && v != "") put(k, if (v is String) v.take(160) else v)
    }
}
