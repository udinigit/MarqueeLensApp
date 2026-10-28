package com.marqueelens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast

/** What was shared to the app, until the page takes it. */
object SharedInbox { @Volatile var pending: String? = null }

/** The whole Marquee Lens app (lists, camera, settings), running from the copy bundled inside the APK. */
class MainActivity : Activity(), WebHost {

    private lateinit var web: WebView
    private var pendingSave: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraRequest: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#0E0C11")
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this).apply { setBackgroundColor(bg) }
        Web.setup(web, this, Bridge(this, this)) { startIntent(Intent(Intent.ACTION_VIEW, it)) }
        web.webChromeClient = object : WebChromeClient() {
            // "Open a file" in Share & files
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return try { startActivityForResult(Intent.createChooser(pick, "Open a file"), REQ_OPEN); true }
                catch (e: Exception) { fileCallback = null; false }
            }
            // The Camera tab
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources) { request.deny(); return@runOnUiThread }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    else { cameraRequest = request; requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) }
                }
            }
        }
        setContentView(web)
        handleShare(intent)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl("${Web.ORIGIN}/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Something shared to Marquee Lens: a screenshot (recognised by Gemini) or text with titles. The page picks it up. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val type = i.type ?: return
        @Suppress("DEPRECATION") val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        i.action = Intent.ACTION_MAIN                     // don't handle it again on rotation
        Thread {
            val shared = org.json.JSONObject()
            if (type.startsWith("image/") && uri != null) {
                val b64 = runCatching { scaledJpeg(uri) }.getOrNull()
                if (b64 == null) { runOnUiThread { Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show() }; return@Thread }
                shared.put("type", "image").put("data", b64)
            } else shared.put("type", "text").put("text", text.take(5000))
            SharedInbox.pending = shared.toString()
            runOnUiThread { web.evaluateJavascript("window.mlSharedReady && mlSharedReady()", null) }
        }.start()
    }

    /** The picture, at most 1600 px on its long side, as base64 JPEG (small enough to send, sharp enough to read). */
    private fun scaledJpeg(uri: Uri): String {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("unreadable")
        val k = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val out = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        val bytes = java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onResume() {
        super.onResume(); web.onResume()
        web.evaluateJavascript("window.mlResume && mlResume()", null)   // pick up films added over other apps
    }

    override fun onPause() { web.onPause(); EventLog.upload(this); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }

    @Deprecated("Back is handled by the page first")
    override fun onBackPressed() {
        web.evaluateJavascript("window.mlBack ? mlBack() : false") { handled -> if (handled != "true") moveTaskToBack(true) }
    }

    // ---------- WebHost ----------
    override fun saveFile(name: String, type: String, text: String) {
        pendingSave = text
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(create, REQ_SAVE) } catch (e: Exception) { pendingSave = null; Bridge.shareFileNow(this, this, name, type, text) }
    }

    override fun startIntent(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Simple activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OPEN -> {
                val uri = data?.data
                fileCallback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
                fileCallback = null
            }
            REQ_SAVE -> {
                val text = pendingSave; pendingSave = null
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null && text != null) {
                    val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
                    Toast.makeText(this, if (ok) "Saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_CAMERA) return
        val req = cameraRequest ?: return; cameraRequest = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
    }

    companion object {
        private const val REQ_OPEN = 11
        private const val REQ_SAVE = 12
        private const val REQ_CAMERA = 13
    }
}
