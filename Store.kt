package com.marqueelens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings and lists. The web app inside MainActivity saves everything here (through the bridge),
 * so the overlay reads exactly the same keys, lists and filter.
 */
class Store(ctx: Context) {
    val web = ctx.applicationContext.getSharedPreferences(WEB_PREFS, Context.MODE_PRIVATE)
    private val prefs = ctx.applicationContext.getSharedPreferences("marquee", Context.MODE_PRIVATE)
    private val cachePrefs = ctx.applicationContext.getSharedPreferences("marquee_cache", Context.MODE_PRIVATE)

    private fun s(k: String) = web.getString(k, null) ?: ""

    // ---- connection (set in the app's Settings)
    val conn: String get() = s("ml.conn").ifBlank { if (DEFAULT_SERVER.isNotBlank()) "invite" else "own" }
    val server: String get() = s("ml.server").ifBlank { DEFAULT_SERVER }.trimEnd('/')
    val invite: String get() = s("ml.invite")
    val omdbKey: String get() = s("ml.okey")
    val geminiKey: String get() = s("ml.gkey")
    val tmdbKey: String get() = s("ml.tkey")

    val viaServer: Boolean get() = conn == "invite" && server.isNotBlank() && invite.isNotBlank()
    val configured: Boolean get() = viaServer || omdbKey.isNotBlank()
    val hasGemini: Boolean get() = viaServer || geminiKey.isNotBlank()
    val hasTmdb: Boolean get() = viaServer || tmdbKey.isNotBlank()

    // ---- overlay options (Settings → Ratings over streaming apps)
    val everywhere: Boolean get() = s("ov.everywhere") == "1"
    var live: Boolean
        get() = s("ov.live") == "1"
        set(v) = web.edit().putString("ov.live", if (v) "1" else "").apply()
    /** Streaming services where the bubble is switched off, e.g. ["youtube"]. */
    val offServices: Set<String> get() = runCatching { JSONArray(s("ov.off")).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } }.getOrDefault(emptySet())

    // ---- TV (Android TV / NVIDIA Shield)
    /** The remote button that shows ratings: a key code ("82" = the ≡ menu button), or "okhold" (hold OK). */
    var tvKey: String
        get() = s("ov.tvKey").ifBlank { KEY_MENU }
        set(v) = web.edit().putString("ov.tvKey", v).apply()
    /** Settings is waiting for you to press the button you want (until this time). */
    val learning: Boolean get() = (s("ov.learn").toLongOrNull() ?: 0L) > System.currentTimeMillis()
    fun stopLearning() = web.edit().putString("ov.learn", "").apply()
    /** The rating of the selected title, in the corner (on unless switched off). */
    val tvFocus: Boolean get() = s("ov.tvFocus") != "0"
    /** A "?" on titles that were read but not found, to pick the right film by hand. */
    val showUnknown: Boolean get() = s("ov.unknown") != "0"
    /** Experimental: present the overlay as a screen reader, since some apps (Netflix on TV) only share titles with those. */
    val screenReader: Boolean get() = s("ov.sr") == "1"
    fun saveScan(json: String) = web.edit().putString("ov.lastScan", json).apply()

    /** How ratings start on a phone: "bubble" (tap it), "auto" (by themselves, no bubble) or "shortcut" (Android's accessibility shortcut). */
    val startMode: String get() = s("ov.start").ifBlank { if (live) "auto" else "bubble" }

    /** Where the bubble sits: which side (0 left, 1 right) and how far down (0–1), so it stays on screen when the phone turns. */
    var bubbleSide: Int
        get() = prefs.getInt("bubbleSide", 1)
        set(v) = prefs.edit().putInt("bubbleSide", v).apply()
    var bubbleYFrac: Float
        get() = prefs.getFloat("bubbleYFrac", 0.35f)
        set(v) = prefs.edit().putFloat("bubbleYFrac", v).apply()

    // ---- "Not a film" filter and corrected titles, per app (service id, or package name for other apps)
    data class Block(val text: String, val app: String)
    data class Fix(val text: String, val app: String, val id: String)

    fun blocks(): List<Block> = runCatching {
        val a = JSONArray(s("ov.block"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Block(it.optString("t"), it.optString("app")) }
    }.getOrDefault(emptyList())

    fun fixes(): List<Fix> = runCatching {
        val a = JSONArray(s("ov.fix"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Fix(it.optString("t"), it.optString("app"), it.optString("id")) }
    }.getOrDefault(emptyList())

    fun addBlock(text: String, app: String) {
        val a = runCatching { JSONArray(s("ov.block")) }.getOrDefault(JSONArray())
        val k = Api.norm(text)
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (Api.norm(it.optString("t")) == k && it.optString("app") == app) return }
        a.put(JSONObject().put("t", text).put("app", app).put("at", System.currentTimeMillis()))
        web.edit().putString("ov.block", a.toString()).apply()
    }

    // ---- lists: is this film saved in any list? (for the ★ on badges)
    fun savedIds(): Set<String> = runCatching {
        val ls = JSONArray(s("ml.lists")); val out = HashSet<String>()
        for (i in 0 until ls.length()) {
            val items = ls.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) items.optJSONObject(j)?.optString("id")?.let(out::add)
        }
        out
    }.getOrDefault(emptySet())

    val region: String get() = s("ml.region").ifBlank { "IL" }.uppercase().take(2)

    // ---- lookup cache (title|year → film), so the same tile isn't looked up twice
    fun cachedText(key: String): String? = cachePrefs.getString(key, null)
    fun cacheText(key: String, v: String) = cachePrefs.edit().putString(key, v).apply()

    fun cached(key: String): Film? = cachePrefs.getString(key, null)?.let { s ->
        if (s == "-") MISS else runCatching { Film.fromJson(JSONObject(s)) }.getOrNull()
    }

    fun cache(key: String, film: Film?) {
        if (cachePrefs.all.size > 1500) cachePrefs.edit().clear().apply()
        cachePrefs.edit().putString(key, film?.toJson()?.toString() ?: "-").apply()
    }

    companion object {
        const val WEB_PREFS = "web"
        const val KEY_MENU = "82"
        /** Your Cloudflare Worker address. Put the same address in DEFAULT_SERVER in index.html, so friends only need an invite code. */
        const val DEFAULT_SERVER = ""   // ← PUT YOUR WORKER ADDRESS HERE too, e.g. "https://marquee-lens.your-name.workers.dev" (see SETUP.md)
        val MISS = Film(id = "tt0000000", title = "")
    }
}
