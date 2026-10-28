package com.marqueelens

import org.json.JSONArray
import org.json.JSONObject

/** A film or series. Field names match the web app, so exported lists move freely between them. */
data class Film(
    val id: String,
    val title: String,
    val year: String = "",
    val imdb: String = "",
    val genre: String = "",
    val type: String = "",
    val poster: String = "",
    val plot: String = "",
    val rt: String = "",
    var viewed: Boolean = false,
    var services: MutableList<String> = mutableListOf(),
    var added: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("year", year); put("imdb", imdb)
        put("genre", genre); put("type", type); put("poster", poster); put("plot", plot); put("rt", rt)
        put("viewed", viewed); put("services", JSONArray(services)); put("added", added)
    }

    companion object {
        private val ID = Regex("^tt\\d{6,10}$")

        fun fromJson(o: JSONObject): Film? {
            val id = o.optString("id").ifBlank { o.optString("imdbID") }
            if (!ID.matches(id)) return null
            val sv = mutableListOf<String>()
            o.optJSONArray("services")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(sv::add) }
            return Film(
                id = id,
                title = o.optString("title").ifBlank { o.optString("Title") },
                year = o.optString("year"), imdb = o.optString("imdb"), genre = o.optString("genre"),
                type = o.optString("type"), poster = o.optString("poster"), plot = o.optString("plot"), rt = o.optString("rt"),
                viewed = o.optBoolean("viewed", false), services = sv,
                added = o.optLong("added", System.currentTimeMillis())
            )
        }

        /** From an OMDb answer, or null if OMDb didn't find anything. */
        fun fromOmdb(o: JSONObject): Film? {
            if (o.optString("Response") != "True") return null
            fun v(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "N/A" } ?: ""
            var rt = ""
            o.optJSONArray("Ratings")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { r -> if (r.optString("Source") == "Rotten Tomatoes") rt = r.optString("Value") }
            }
            val id = v("imdbID")
            if (!ID.matches(id)) return null
            return Film(id = id, title = v("Title"), year = v("Year"), imdb = v("imdbRating"), genre = v("Genre"),
                type = v("Type"), poster = v("Poster"), plot = v("Plot"), rt = rt)
        }
    }
}
