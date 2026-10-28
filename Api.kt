package com.marqueelens

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** Talks to OMDb and Gemini, either through your server (invite code) or directly with the user's own keys. */
class Api(private val store: Store) {

    // ---------- HTTP ----------
    private fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, timeoutMs: Int = 15000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally { c.disconnect() }
    }

    private fun q(params: Map<String, String>) = params.filterValues { it.isNotBlank() }
        .entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

    private fun serverError(code: Int, text: String): ApiException {
        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
        return ApiException(msg ?: "Server error $code", code)
    }

    // ---------- server ----------
    /** Checks the invite code. Returns e.g. {"name":"Dana","limit":100,"used":7,...}. */
    fun me(): JSONObject {
        val (code, text) = http("GET", "${store.server}/me", mapOf("X-Invite" to store.invite))
        if (code != 200) throw serverError(code, text)
        return JSONObject(text)
    }

    // ---------- OMDb ----------
    fun omdb(params: Map<String, String>): JSONObject {
        val (code, text) = if (store.viaServer)
            http("GET", "${store.server}/omdb?${q(params)}", mapOf("X-Invite" to store.invite))
        else
            http("GET", "https://www.omdbapi.com/?${q(params + ("apikey" to store.omdbKey))}")
        if (code != 200) throw if (store.viaServer) serverError(code, text) else ApiException("OMDb error $code", code)
        return JSONObject(text)
    }

    // ---------- TMDB ----------
    fun tmdb(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val k = store.tmdbKey
        val (code, text) = when {
            store.viaServer -> http("GET", "${store.server}/tmdb$path?${q(params)}", mapOf("X-Invite" to store.invite))
            k.startsWith("eyJ") -> http("GET", "https://api.themoviedb.org/3$path?${q(params)}", mapOf("Authorization" to "Bearer $k"))
            else -> http("GET", "https://api.themoviedb.org/3$path?${q(params + ("api_key" to k))}")
        }
        if (code != 200) throw if (store.viaServer) serverError(code, text) else ApiException("TMDB error $code", code)
        return JSONObject(text)
    }

    /**
     * A title shown in Hebrew (or another non-Latin script): OMDb only knows English titles,
     * so search TMDB's translated titles, then get the IMDb rating for the film it finds.
     */
    private fun findLocal(title: String, year: String?, series: Boolean?): Film? {
        if (!store.hasTmdb) return null
        val lang = languageOf(title) ?: return null
        for (q in localVariants(title)) {
            val r = tmdb("/search/multi", mapOf("query" to q, "language" to lang, "include_adult" to "false"))
            val res = r.optJSONArray("results") ?: JSONArray()
            val items = (0 until res.length()).mapNotNull { res.optJSONObject(it) }.filter { it.optString("media_type") == "movie" || it.optString("media_type") == "tv" }
            var best: JSONObject? = null; var bestScore = 0
            items.forEachIndexed { i, x ->
                val kind = x.optString("media_type")
                val local = x.optString("title").ifBlank { x.optString("name") }
                val original = x.optString("original_title").ifBlank { x.optString("original_name") }
                val yr = (x.optString("release_date").ifBlank { x.optString("first_air_date") }).take(4)
                val yearOk = year != null && yr == year
                val yearNear = year == null || (yr.toIntOrNull()?.let { kotlin.math.abs(it - (year.toIntOrNull() ?: it)) <= 1 } ?: false)
                // A real match, not just a shared word: the same title, nearly the same spelling, or nearly all the same words
                var score = when {
                    sameTitle(local, q) || sameTitle(original, q) -> 100
                    spelling(local, q) >= 0.75 -> 65                                     // האמריקאים / האמריקנים
                    similarity(local, q) >= 0.75 && norm(q).split(" ").size >= 2 -> 55
                    // no Hebrew title on TMDB (Andor shows as "Andor"): trust the search only with strong evidence
                    yearOk && i < 3 -> 55
                    items.size == 1 && year == null -> 50
                    else -> 0
                }
                if (score == 0) return@forEachIndexed
                if (!yearNear) { if (score < 100) return@forEachIndexed else score -= 30 }
                if (yearOk) score += 25
                if (series != null) score += if ((kind == "tv") == series) 15 else -25
                score += (10 - i).coerceAtLeast(0)
                if (score > bestScore) { bestScore = score; best = x }
            }
            val b = best ?: continue
            val imdbId = tmdb("/${b.optString("media_type")}/${b.optInt("id")}/external_ids").optString("imdb_id")
            if (!imdbId.startsWith("tt")) continue
            Film.fromOmdb(omdb(mapOf("i" to imdbId)))?.let { return it }
        }
        return null
    }

    /** A film by its IMDb id (used for titles you corrected with "Wrong title?"). Cached. */
    fun filmById(id: String): Film? {
        val key = "id|$id"
        store.cached(key)?.let { return if (it.id == Store.MISS.id) null else it }
        val f = Film.fromOmdb(omdb(mapOf("i" to id))); store.cache(key, f); return f
    }

    /** Is this TMDB film or series on that streaming service in your country? (JustWatch data via TMDB.) Cached. */
    private fun providersHave(type: String, tmdbId: Int, service: String): Boolean {
        val key = "prov|$type|$tmdbId|$service|${store.region}"
        store.cachedText(key)?.let { return it == "1" }
        val r = runCatching { tmdb("/$type/$tmdbId/watch/providers").optJSONObject("results")?.optJSONObject(store.region) }.getOrNull()
        val yes = r != null && listOf("flatrate", "free", "ads", "rent", "buy").any { k ->
            val a = r.optJSONArray(k) ?: return@any false
            (0 until a.length()).any { Services.providerMatches(service, a.optJSONObject(it)?.optString("provider_name") ?: "") }
        }
        store.cacheText(key, if (yes) "1" else "0")
        return yes
    }

    /**
     * "Ricky Gervais Alley Cats" on Netflix, listed under a slightly different name: TMDB's search, keeping a result only
     * when its words and the screen's overlap completely one way or the other, and it is on [service] here.
     */
    private fun looseOnService(title: String, service: String): Film? = runCatching {
        val res = tmdb("/search/multi", mapOf("query" to title, "include_adult" to "false")).optJSONArray("results") ?: JSONArray()
        val said = norm(title).split(" ").filter { it.isNotEmpty() }.toSet()
        for (i in 0 until minOf(res.length(), 3)) {
            val x = res.optJSONObject(i) ?: continue
            val kind = x.optString("media_type"); if (kind != "movie" && kind != "tv") continue
            val name = norm(x.optString("title").ifBlank { x.optString("name") }).split(" ").filter { it.isNotEmpty() }
            if (name.isEmpty() || !(name.all { it in said } || said.all { it in name })) continue
            if (!providersHave(kind, x.optInt("id"), service)) continue
            val imdb = tmdb("/$kind/${x.optInt("id")}/external_ids").optString("imdb_id")
            if (imdb.startsWith("tt")) return@runCatching Film.fromOmdb(omdb(mapOf("i" to imdb)))
        }
        null
    }.getOrNull()

    /** Several films or series share this title: the IMDb id of the one this service has, or null. */
    private fun pickOnService(title: String, service: String): String? = runCatching {
        val res = tmdb("/search/multi", mapOf("query" to title, "include_adult" to "false")).optJSONArray("results") ?: JSONArray()
        val same = (0 until res.length()).mapNotNull { res.optJSONObject(it) }.filter {
            val kind = it.optString("media_type")
            (kind == "movie" || kind == "tv") && (sameTitle(it.optString("title").ifBlank { it.optString("name") }, title) ||
                sameTitle(it.optString("original_title").ifBlank { it.optString("original_name") }, title))
        }
        if (same.size < 2) return@runCatching null
        val hit = same.take(5).firstOrNull { providersHave(it.optString("media_type"), it.optInt("id"), service) } ?: return@runCatching null
        tmdb("/${hit.optString("media_type")}/${hit.optInt("id")}/external_ids").optString("imdb_id").takeIf { it.startsWith("tt") }
    }.getOrNull()

    /**
     * Finds the film with exactly this title (punctuation and a leading "The" don't matter). Cached.
     * When several share the title (Inside Man: the 2006 film and the 2022 series), the one on [service] wins.
     */
    fun findFilm(title: String, year: String? = null, service: String? = null, series: Boolean? = null, country: String? = null): Film? {
        val key = "${norm(title)}|${year ?: ""}|${service ?: ""}|${series ?: ""}|${country ?: ""}"
        if (languageOf(title) != null) {
            store.cached(key)?.let { return if (it.id == Store.MISS.id) null else it }
            val f = findLocal(title, year, series); store.cache(key, f); return f
        }
        store.cached(key)?.let { return if (it.id == Store.MISS.id) null else it }
        var found: Film? = null
        val first = Film.fromOmdb(omdb(mapOf("t" to title, "y" to (year ?: ""), "type" to if (series == true) "series" else "")))
        if (first != null && sameTitle(first.title, title)) found = first
        // "The Office (U.S.)": the same title from that country (the US series, not the UK one)
        if (country != null) {
            val hits = omdb(mapOf("s" to title)).optJSONArray("Search") ?: JSONArray()
            val ids = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }
                .filter { it.optString("Type") != "game" && sameTitle(it.optString("Title"), title) }.map { it.optString("imdbID") }.distinct().take(4)
            if (ids.size >= 2) for (id in ids) {
                val o = omdb(mapOf("i" to id))
                if (o.optString("Country").contains(country, ignoreCase = true)) { Film.fromOmdb(o)?.let { found = it }; break }
            }
        }
        // same title, several films (Inside Man: the 2006 film and the 2022 series): prefer the one this app has
        if (service != null && year == null && country == null && store.hasTmdb) {
            pickOnService(title, service)?.let { id -> if (id != found?.id) Film.fromOmdb(omdb(mapOf("i" to id)))?.let { found = it } }
        }
        // not found by its exact name: a looser search, trusted only if the result is really on this app
        if (found == null && service != null && store.hasTmdb) found = looseOnService(title, service)
        if (found == null) {
            val s = omdb(mapOf("s" to title))
            val hits = s.optJSONArray("Search") ?: JSONArray()
            var bestId: String? = null; var bestGap = Int.MAX_VALUE
            for (i in 0 until hits.length()) {
                val h = hits.optJSONObject(i) ?: continue
                if (h.optString("Type") == "game" || !sameTitle(h.optString("Title"), title)) continue
                val gap = if (year == null) 0 else kotlin.math.abs((h.optString("Year").take(4).toIntOrNull() ?: 0) - (year.toIntOrNull() ?: 0))
                if (gap < bestGap) { bestGap = gap; bestId = h.optString("imdbID") }
            }
            if (bestId != null) found = Film.fromOmdb(omdb(mapOf("i" to bestId)))
        }
        store.cache(key, found)
        return found
    }

    // ---------- Gemini (screenshot fallback) ----------
    /** Sends a request to Gemini, trying the next free model if one is busy. Returns the answer text. */
    fun gemini(parts: JSONArray, json: Boolean = true): String {
        var last: ApiException? = null
        for (model in MODELS) {
            val gc = JSONObject().put("temperature", 0.2)
            if (json) gc.put("responseMimeType", "application/json")
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", gc).toString()
            val (code, text) = if (store.viaServer)
                http("POST", "${store.server}/gemini/$model:generateContent", mapOf("X-Invite" to store.invite), body, 20000)
            else
                http("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to store.geminiKey), body, 20000)
            if (code == 200) {
                val ps = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                return (0 until ps.length()).mapNotNull { ps.optJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }.trim()
            }
            val status = runCatching { JSONObject(text).getJSONObject("error").optString("status") }.getOrDefault("")
            last = if (store.viaServer) serverError(code, text) else ApiException("Gemini error $code", code)
            if (status == "DAILY_LIMIT" || status == "INVITE" || code == 401 || code == 403) throw last
            if (code != 429 && code != 500 && code != 503 && code != 404) throw last
        }
        throw last ?: ApiException("Gemini is busy. Try again in a minute.")
    }

    fun poster(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }.getOrNull()

    companion object {
        val MODELS = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

        /** TMDB language for a title in a non-Latin script, or null for Latin titles. */
        fun languageOf(s: String): String? = when {
            Regex("[\\u0590-\\u05FF]").containsMatchIn(s) -> "he-IL"
            Regex("[\\u0600-\\u06FF]").containsMatchIn(s) -> "ar"
            Regex("[\\u0400-\\u04FF]").containsMatchIn(s) -> "ru"
            else -> null
        }

        /** How alike two spellings are, letter by letter (1 = identical). */
        fun spelling(a: String, b: String): Double {
            val x = norm(a).replace(" ", ""); val y = norm(b).replace(" ", "")
            if (x.isEmpty() || y.isEmpty()) return 0.0
            var prev = IntArray(y.length + 1) { it }
            for (i in 1..x.length) {
                val cur = IntArray(y.length + 1); cur[0] = i
                for (j in 1..y.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = cur
            }
            return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
        }

        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** Share of words two titles have in common (1 = the same words). */
        fun similarity(a: String, b: String): Double {
            val x = norm(a).split(" ").filter { it.isNotEmpty() }; val y = norm(b).split(" ").filter { it.isNotEmpty() }.toSet()
            if (x.isEmpty() || y.isEmpty()) return 0.0
            return x.count { it in y }.toDouble() / maxOf(x.size, y.size)
        }

        /** Cleaned-up versions of a title read on screen: no Latin junk next to Hebrew, and a final ו that's really a misread "!". */
        fun localVariants(text: String): List<String> {
            val out = mutableListOf<String>()
            fun add(v: String) { val t = v.replace(Regex("\\s+"), " ").trim(); if (t.count(Char::isLetter) >= 2 && out.none { norm(it) == norm(t) }) out.add(t) }
            val heb = Regex("[\\u0590-\\u05FF]")
            val base = text.split(Regex("\\s+")).filter { heb.containsMatchIn(it) || it.any(Char::isDigit) || it.length > 3 }
                .joinToString(" ").trim().trim { !it.isLetterOrDigit() && it != '!' && it != '?' }
            add(base)
            val words = base.split(" "); val last = words.lastOrNull() ?: ""
            if (Regex("ו[!?.]?$").containsMatchIn(last) && last.filter { it.isLetter() }.length >= 3)
                add((words.dropLast(1) + last.replace(Regex("ו([!?.]?)$"), "!")).joinToString(" "))
            add(base.replace(Regex("[^\\p{L}\\p{N}\\s]"), " "))
            return out.take(3)
        }
        private fun compact(s: String) = norm(s).replace(Regex("^(the|a|an) "), "").replace(" ", "")
        fun sameTitle(a: String, b: String) = compact(a).isNotEmpty() && compact(a) == compact(b)
    }
}
