package com.marqueelens

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** A piece of text on screen that may be a film title, with the area of the tile it labels. */
/** [series]: the label says it's a series (season / episode), null if it doesn't say. */
data class Candidate(val variants: List<String>, val year: String?, val bounds: Rect, val raw: String, val series: Boolean? = null, val country: String? = null)

/**
 * Reads the titles that streaming apps expose for screen readers (TalkBack).
 * Netflix and most other apps label each poster tile with its title, even though their screens can't be screenshotted.
 */
object TitleFinder {
    // Words on menus and buttons, never a title
    private val STOP = Regex(
        "^(home|search|downloads?|my netflix|new & hot|new and hot|games|categories|browse|profiles?|settings|more|play|resume|" +
        "info|more info|details|watch now|trailer|watch trailer|my list|add to my list|remove from my list|share|rate|download|" +
        "episodes|season \\d+|tv shows|shows|movies|films|series|kids|for you|top 10.*|continue watching.*|because you watched.*|" +
        "trending( now)?|popular.*|recently added|coming soon|everyone's watching|leaving soon|back|close|menu|cast|chromecast|" +
        "notifications?|account|help|sign in|log in|profile|subtitles|audio|next episode|skip intro|skip recap|like|dislike|" +
        "live|guide|channels|recordings?|library|explore|subscriptions|shorts|you|for kids|sports|news|" +
        "tv-(y7?|g|pg|14|ma)|pg(-13)?|nc-17|[rg]|\\d+\\+|hd|uhd|4k|ultra hd|dolby.*|hdr.*|audio description|subtitles?.*|\\d+ seasons?|\\d+ episodes?|" +
        "new episodes?.*|watched.*|more like this|trailers? (&|and) more|episodes (&|and) info|button|row|list|selected|double tap.*|" +
        "pause|paused|rewind|fast forward|forward|skip.*|speed.*|playback speed.*|audio (&|and) subtitles|subtitles (&|and) audio|back to browse.*|" +
        "report a problem|lock( screen)?|unlock|seek bar|progress|volume|mute|unmute|full ?screen|exit full ?screen|replay|restart|start over|" +
        "\\d{1,2}:\\d{2}(:\\d{2})?.*|-?\\d+ (seconds|sec|minutes|min)( remaining| left)?|השהה|הפעל|הרץ קדימה|הרץ אחורה|דלג.*|" +
        "netflix|youtube( tv| kids)?|prime video|amazon prime video|apple tv\\+?|hbo max|max|google tv|android tv home|plex|kodi|" +
        "reading the screen.*|talkback.*|screen reader.*|in list.*|out of list|page \\d+ of \\d+|" +
        "open|move|uninstall|add to favorites|hulu|disney\\+?|דיסני\\+?|הולו|תכנים מקוריים|תוכן מקורי.*|בשבילך|להמשך צפייה|marvel|pixar|national geographic|nat geo|espn|star|originals?|" +
        "הגדרות|אווטאר.*|profile|avatar|watchlist|רשימת הצפייה|" +
        "navigate up|tv shows (&|and) movies|edit( list)?|new (&|and) popular|my netflix|search|cancel|done|" +
        "ראשי|ישראלי|ישראליים|זרים|לועזי|קולנוע|הכל|סרט|סדרה|תוכניות|בידור|ריאליטי|ילדים ונוער|בית|חיפוש|סרטים|סדרות|ילדים|הרשימה שלי|הורדות|המשך צפייה.*|צפה|צפייה|נגן|המשך|פרטים|לפרטים|טריילר|פרקים|עונה \\d+|חדש|חדשים|" +
        "פופולרי.*|מומלצים.*|טופ 10.*|10 המובילים.*|ערוצים|שידור חי|הקלטות|הגדרות|פרופיל|עוד|סגור|חזרה|תפריט|ספורט|חדשות|דוקו|קומדיה|דרמה|אקשן)$",
        RegexOption.IGNORE_CASE
    )
    // Labels that contain these are rows, durations or buttons, not titles
    private val STOP_ANY = Regex("\\b\\d+\\s*h\\s*\\d+\\s*m\\b|\\bof \\d+ (items|rows)|הפעלת cast|cast to|no device|מכשיר מחובר|דקות|דק'|השבוע|ביותר|מומלצ|חדש ב|הכי |ז'אנר|ערוץ |צפו עכשיו|לצפייה|\\d+\\s*(min|mins|minutes)\\b", RegexOption.IGNORE_CASE)
    // Actions in front of a title ("Play The Office", "Continue watching The Last of Us"); badges only with a colon ("New: …"), so New Girl stays New Girl
    private val PREFIX = Regex("^((continue watching|keep watching|watch|play|resume|now playing|המשך צפייה ב?|המשך לצפות ב?)[:,]?|(top 10|new|recently added|coming soon|חדש)[:,])\\s+", RegexOption.IGNORE_CASE)
    private val ISOLATE = Regex("\u2068([^\u2069]*)\u2069")
    private val INVISIBLE = Regex("[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
    // Live sports and events, not films
    private val LIVE = Regex("live & upcoming|live now|\\blive:|שידור חי|בשידור חי", RegexOption.IGNORE_CASE)
    // Row headings, never a single title
    private val ROW = Regex("^(continue watching|keep watching)( for .*)?$|^because you (watched|liked) .*|^more like .*|^top 10 .*", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("\\((19|20)\\d{2}\\)|,\\s*(19|20)\\d{2}\\b")
    // "שנת יציאה: 2008", "Release year: 2008", "Released 2008" (Disney+ and others put it in the label)
    private val RELEASE = Regex("(?:שנת יציאה|שנת הפקה|release(?:d| year| date)?|year)\\s*:?\\s*((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
    private val SERIES = Regex("\\b(S\\d+\\s*[:.]?\\s*E\\d+|season|episode|series|limited series)\\b|עונה|פרק|סדרה|סדרת|ע\\d+\\s*:\\s*פ\\d+", RegexOption.IGNORE_CASE)

    private class Node(val node: AccessibilityNodeInfo, val bounds: Rect, val order: Int, val scroller: Int, val visible: Boolean) { var end = order }

    /**
     * Every labelled tile that is really visible. Film tiles live in the page's scrolling list; anything outside it
     * (header, category chips, bottom bar) is the app's frame: never a title, and it covers the tiles scrolled under it.
     */
    fun collect(root: AccessibilityNodeInfo, screenW: Int, screenH: Int, density: Float): List<Candidate> {
        val all = mutableListOf<Node>()
        val screen = Rect(0, 0, screenW, screenH)
        fun walk(n: AccessibilityNodeInfo, depth: Int, scroller: Int) {
            if (all.size >= 4000) return
            val r = Rect(); n.getBoundsInScreen(r)
            // Some apps mark rows "not visible" although they're on screen (Netflix's Continue Watching): look inside anyway
            if (!n.isVisibleToUser && (r.isEmpty || !Rect.intersects(r, screen))) return
            val me = Node(n, r, all.size, scroller, n.isVisibleToUser); all.add(me)
            // a tall scrolling area: the page's list of rows
            val inner = if (n.isScrollable && r.height() >= screenH * 0.4) me.order else scroller
            if (depth < 60) for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, inner) }
            me.end = all.size - 1
        }
        walk(root, 0, -1)

        val minW = (56 * density).toInt(); val minH = (36 * density).toInt()
        val topInset = (24 * density).toInt()
        val hasList = all.any { it.node.isScrollable && it.bounds.height() >= screenH * 0.4 }
        val tops = all.filter { it.visible }
        // Bars: wide, short areas such as a header or a bottom navigation bar
        val bars = tops.filter { it.bounds.width() >= screenW * 0.5 && it.bounds.height() in (24 * density).toInt()..(screenH / 5) }
        fun inside(b: Node, a: Node) = b.order >= a.order && b.order <= a.end
        val out = LinkedHashMap<String, Candidate>()
        for (x in all) {
            val r = x.bounds
            val raw = (x.node.contentDescription ?: x.node.text)?.toString() ?: continue
            if (r.width() < minW || r.height() < minH || r.width() >= screenW * 0.98 || r.height() >= screenH * 0.7) continue
            if (r.top < topInset || r.left < 0 || r.right > screenW || r.top + minH * 2 > screenH) continue   // cut off at an edge (a tall tile may run past the bottom)
            if (notATitle(x.node, raw)) continue
            if (!x.visible && !screen.contains(r)) continue           // "not visible" and not fully on screen: really hidden
            val list = all.getOrNull(x.scroller)
            if (hasList && list == null) continue                       // header, category chips, bottom bar
            // where the rating would sit: if the app's frame covers it, the tile isn't really visible there
            val ax = r.left + minOf(r.width() / 4, (24 * density).toInt()); val ay = r.top + minOf(r.height() / 4, (24 * density).toInt())
            val covered = bars.any { b ->
                val frame = if (list != null) !inside(b, list) && !inside(x, b) else b.order > x.end
                frame && (b.bounds.contains(ax, ay) || (b.bounds.contains(r.centerX(), r.centerY()) && b.bounds.top <= r.top + r.height() / 3))
            }
            if (covered) continue
            val c = candidateOf(raw, r) ?: continue
            out.putIfAbsent(Api.norm(c.variants.first()), c)
        }
        return out.values.sortedWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left })).take(24)
    }

    /** What the screen exposes (for Settings → Last overlay scan): class, text and position of each labelled view. */
    fun dump(root: AccessibilityNodeInfo, max: Int = 80): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>(); stack.addLast(root to 0)
        var seen = 0
        while (stack.isNotEmpty() && seen < 4000 && out.size < max) {
            val (node, depth) = stack.removeLast(); seen++
            val r = Rect(); node.getBoundsInScreen(r)
            if (!node.isVisibleToUser && r.isEmpty) continue
            val raw = (node.contentDescription ?: node.text)?.toString()?.replace(Regex("\\s+"), " ")?.trim()
            if (!raw.isNullOrEmpty()) {
                out.add("${node.className?.toString()?.substringAfterLast('.') ?: "?"}${if (node.isFocused) "*" else ""}${if (node.isVisibleToUser) "" else "~"} [${r.left},${r.top} ${r.width()}x${r.height()}] ${raw.take(100)}")
            }
            if (depth < 60) for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it to depth + 1) }
        }
        return out
    }

    // Icon buttons ("Navigate up", "Play"), fields, switches and tabs are labelled with actions, never titles
    private val NOT_TITLE_CLASS = Regex("EditText|CheckBox|Switch|ToggleButton|SeekBar|ProgressBar|RadioButton|TabWidget|TabView|TabLayout|Toolbar|ActionMenu")
    private fun notATitle(node: AccessibilityNodeInfo, label: String): Boolean {
        val cls = node.className?.toString() ?: ""
        // icon buttons are named for their action ("Navigate up"), unless they name a title ("Play Community");
        // plain buttons are fine: many apps report their poster tiles as buttons (Netflix's Continue Watching)
        if (cls.contains("ImageButton")) return !PREFIX.containsMatchIn(label.trim())
        return NOT_TITLE_CLASS.containsMatchIn(cls) ||
            (node.collectionItemInfo == null && node.parent?.className?.toString()?.let { NOT_TITLE_CLASS.containsMatchIn(it) } == true)
    }

    // Where the title ends in labels like "Community, Season 1, Episode 5, 12 minutes left" or "The Crown: S6:E3"
    private val EPISODE = Regex("\\s*[,:\\-–|]?\\s*\\b(S\\d+\\s*[:.]?\\s*E\\d+|season \\d+|episode \\d+|ep\\.? ?\\d+|limited series|ע\\d+\\s*:\\s*פ\\d+|נותרו .*|\\d+\\s*(min|mins|minutes|h|hr)\\b|עונה \\d+|פרק \\d+).*", RegexOption.IGNORE_CASE)

    private val PAREN = Regex("\\s*\\(([^()]{1,30})\\)\\s*$")
    private val COUNTRIES = mapOf("US" to "United States", "USA" to "United States", "UK" to "United Kingdom", "GB" to "United Kingdom",
        "IL" to "Israel", "ISRAEL" to "Israel", "AU" to "Australia", "CA" to "Canada", "FR" to "France", "DE" to "Germany",
        "ES" to "Spain", "IT" to "Italy", "JP" to "Japan", "KR" to "South Korea", "IN" to "India", "MX" to "Mexico", "BR" to "Brazil")

    fun candidateOf(raw: String, r: Rect): Candidate? {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        // HBO wraps names in invisible direction marks: "⁨Continue Watching The Leftovers⁩ ⁨Pilot⁩ Season 1 …";
        // the first marked part is the show, the next one the episode
        val marked = ISOLATE.find(t)?.groupValues?.get(1)?.trim()
        t = t.replace(INVISIBLE, "").replace(Regex("\\s+"), " ").trim()
        if (t.length < 2 || ROW.matches(t) || LIVE.containsMatchIn(t)) return null
        val year = RELEASE.find(t)?.groupValues?.get(1) ?: YEAR.find(t)?.value?.filter(Char::isDigit)?.takeIf { it.length == 4 }
        val series = if (SERIES.containsMatchIn(t)) true else null
        if (!marked.isNullOrEmpty() && marked.count(Char::isLetter) >= 2) t = marked.replace(INVISIBLE, "")
        t = t.replace(PREFIX, "").trim()                 // "Continue watching The Last of Us" → "The Last of Us", then check it
        if (t.length < 2 || STOP.matches(t)) return null
        // the title part: before the episode / duration details and before the first comma
        val head = t.replace(EPISODE, "").substringBefore(", ").replace(YEAR, "").trim().trim(',', '.', '-', ':', '|').trim()
        if (head.length < 2 || head.length > 80 || STOP.matches(head) || STOP_ANY.containsMatchIn(head)) return null
        val variants = linkedSetOf<String>()
        fun add(s: String) {
            val v = s.replace(YEAR, "").trim().trim(',', '.', '-', ':').trim()
            if (v.length in 2..80 && v.count(Char::isLetter) >= 2 && !STOP.matches(v)) variants.add(v)
        }
        // "The Office (U.S.)": also try it without the brackets; a country in them picks that country's version
        val paren = PAREN.find(head)
        val country = paren?.groupValues?.get(1)?.let { COUNTRIES[it.uppercase().replace(".", "").replace(" ", "")] }
        add(head)
        if (paren != null) add(head.removeRange(paren.range).trim())
        add(t.substringBefore(","))             // "Mission: Impossible, …" keeps its colon
        if (t.length <= 60) add(t)
        if (variants.isEmpty()) return null
        return Candidate(variants.toList().take(3), year, r, head, series, country)
    }
}
