package com.marqueelens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** What the page can ask of whoever shows it: the app's main screen, or the details window over another app. */
interface WebHost {
    fun saveFile(name: String, type: String, text: String)
    fun startIntent(intent: Intent)
    fun closeOverlay() {}
}

/**
 * window.MarqueeNative in the page. Storage goes to SharedPreferences, so the app and the overlay share
 * one set of lists, keys and settings. Called on a background thread, so UI work is posted to the main thread.
 */
class Bridge(private val ctx: Context, private val host: WebHost) {
    private val prefs = ctx.applicationContext.getSharedPreferences(Store.WEB_PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun get(key: String): String? = prefs.getString(key, null)

    @JavascriptInterface fun set(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
        if (key.startsWith("ov.") || key in CONNECTION_KEYS) OverlayService.instance?.refresh()
    }

    @JavascriptInterface fun copy(text: String) = main.post {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Marquee Lens", text))
    }.let { }

    @JavascriptInterface fun shareText(title: String, text: String) = main.post {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
        host.startIntent(Intent.createChooser(send, null))
    }.let { }

    @JavascriptInterface fun shareFile(name: String, type: String, text: String) = main.post { shareFileNow(ctx, host, name, type, text) }.let { }

    @JavascriptInterface fun saveFile(name: String, type: String, text: String) = main.post { host.saveFile(name, type, text) }.let { }

    @JavascriptInterface fun overlayEnabled(): Boolean = OverlayService.instance != null || overlayTurnedOn(ctx)

    /** Opens Accessibility settings; TVs (Shield) don't all have a direct link, so fall back to their Settings app. */
    @JavascriptInterface fun openAccessibilitySettings() = main.post {
        val tries = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@post } catch (e: Exception) { }
        }
        android.widget.Toast.makeText(ctx, "Open Settings → Device Preferences → Accessibility", android.widget.Toast.LENGTH_LONG).show()
    }.let { }

    @JavascriptInterface fun appVersion(): String = runCatching {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${i.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong()})"
    }.getOrDefault("?")

    @JavascriptInterface fun isTv(): Boolean = OverlayService.isTv(ctx)

    /** A screenshot or text shared to the app, once ("" when there's nothing). */
    @JavascriptInterface fun takeShared(): String = SharedInbox.pending.also { SharedInbox.pending = null } ?: ""

    // ---- logs (for fixing problems): the page adds its own events; reports; sending; clearing
    @JavascriptInterface fun logEvent(type: String, json: String) {
        EventLog.add(ctx, type.take(30), runCatching { org.json.JSONObject(json) }.getOrDefault(org.json.JSONObject()))
    }
    @JavascriptInterface fun recentLogs(seconds: Int): String = EventLog.recent(seconds).toString()
    @JavascriptInterface fun reportProblem(note: String, json: String) {
        val extra = runCatching { org.json.JSONObject(json) }.getOrDefault(org.json.JSONObject())
        val svc = OverlayService.instance
        if (svc != null && extra.length() == 0) main.post { svc.reportProblem(note) } else EventLog.report(ctx, note, extra)
    }
    /** Sends unsent events now (runs on the bridge's own thread). */
    @JavascriptInterface fun flushLogs(): Boolean = EventLog.uploadNow(ctx)
    @JavascriptInterface fun clearLogs() = EventLog.clear(ctx).let { }

    /** Netflix on TV: is Marquee Lens the TV's speech engine? */
    @JavascriptInterface fun speechEngineIsOurs(): Boolean = speechEngineIsOurs(ctx)

    @JavascriptInterface fun openSpeechSettings() = main.post {
        val tries = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@post } catch (e: Exception) { } }
    }.let { }

    /** A remote button's name, e.g. 82 → "Menu". */
    @JavascriptInterface fun keyName(code: Int): String = android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        .split('_').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    @JavascriptInterface fun closeOverlay() = main.post { host.closeOverlay() }.let { }

    companion object {
        private val CONNECTION_KEYS = setOf("ml.conn", "ml.server", "ml.invite", "ml.okey", "ml.gkey", "ml.tkey")

        fun shareFileNow(ctx: Context, host: WebHost, name: String, type: String, text: String) {
            val uri = FileShare.write(ctx, name, text)
            val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(name, uri)
            host.startIntent(Intent.createChooser(send, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }

        fun speechEngineIsOurs(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, "tts_default_synth") == ctx.packageName

        fun overlayTurnedOn(ctx: Context): Boolean {
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, OverlayService::class.java)
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Serves the bundled web app from https://appassets.androidplatform.net/ (a secure address, so the camera works). */
object Web {
    const val HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$HOST"

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(wv: WebView, ctx: Context, bridge: Bridge, openExternal: (Uri) -> Unit) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            setSupportMultipleWindows(false)
        }
        wv.addJavascriptInterface(bridge, "MarqueeNative")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != HOST) return null
                val path = u.path.orEmpty().trimStart('/').ifBlank { "index.html" }
                return try {
                    WebResourceResponse(mimeOf(path), "utf-8", ctx.assets.open(path))
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == HOST) return false
                openExternal(req.url)          // IMDb, JustWatch and other links open in the browser
                return true
            }
        }
    }

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"; "js" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
        "svg" -> "image/svg+xml"; "png" -> "image/png"; else -> "application/octet-stream"
    }
}
