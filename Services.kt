package com.marqueelens

/** Streaming services (same ids as the web app) and which installed app belongs to which service. */
object Services {
    val names: Map<String, String> = linkedMapOf(
        "netflix" to "Netflix", "disney" to "Disney+", "hbo" to "HBO Max", "apple" to "Apple TV+",
        "prime" to "Prime Video", "yes" to "yes", "hot" to "HOT", "sting" to "STINGTV",
        "partner" to "Partner TV", "cellcom" to "Cellcom TV", "freetv" to "FreeTV", "kan" to "Kan BOX",
        "mako" to "mako (Keshet 12)", "reshet" to "Reshet 13", "youtube" to "YouTube"
    )

    // Matched against the app's package name, e.g. com.netflix.mediaclient
    private val rules = listOf(
        Regex("netflix") to "netflix",
        Regex("disney") to "disney",
        Regex("hbo|wbd\\.stream|\\.max(\\.|$)") to "hbo",
        Regex("atve|apple.*tv") to "apple",
        Regex("amazon\\.avod|primevideo") to "prime",
        Regex("youtube") to "youtube",
        Regex("(^|\\.)yes(go|plus|tv)?(\\.|$)|il\\.co\\.yes") to "yes",
        Regex("(^|\\.)hot(play|vod|tv)?(\\.|$)") to "hot",
        Regex("sting") to "sting",
        Regex("partner") to "partner",
        Regex("cellcom") to "cellcom",
        Regex("freetv") to "freetv",
        Regex("(^|\\.)kan(11|box)?(\\.|$)") to "kan",
        Regex("mako|keshet") to "mako",
        Regex("reshet") to "reshet"
    )

    fun fromPackage(pkg: String?): String? {
        val p = pkg?.lowercase() ?: return null
        return rules.firstOrNull { it.first.containsMatchIn(p) }?.second
    }

    fun name(id: String): String = names[id] ?: id

    /** How each service is named in TMDB's where-to-watch data (JustWatch). */
    private val providers = mapOf(
        "netflix" to Regex("netflix", RegexOption.IGNORE_CASE), "disney" to Regex("disney", RegexOption.IGNORE_CASE),
        "hbo" to Regex("^max|hbo", RegexOption.IGNORE_CASE), "apple" to Regex("apple tv", RegexOption.IGNORE_CASE),
        "prime" to Regex("prime video|amazon", RegexOption.IGNORE_CASE), "yes" to Regex("^yes", RegexOption.IGNORE_CASE),
        "hot" to Regex("^hot", RegexOption.IGNORE_CASE), "sting" to Regex("sting", RegexOption.IGNORE_CASE),
        "partner" to Regex("partner", RegexOption.IGNORE_CASE), "cellcom" to Regex("cellcom", RegexOption.IGNORE_CASE),
        "freetv" to Regex("free ?tv", RegexOption.IGNORE_CASE), "kan" to Regex("^kan", RegexOption.IGNORE_CASE),
        "mako" to Regex("mako|keshet", RegexOption.IGNORE_CASE), "reshet" to Regex("reshet", RegexOption.IGNORE_CASE),
        "youtube" to Regex("youtube", RegexOption.IGNORE_CASE)
    )
    fun providerMatches(service: String, providerName: String) = providers[service]?.containsMatchIn(providerName) == true
}
