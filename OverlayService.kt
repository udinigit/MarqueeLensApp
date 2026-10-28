package com.marqueelens

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ratings over streaming apps.
 * Phone: a floating button. Tap it and the IMDb rating appears on every title on screen; long-press it for Live mode.
 * TV (Shield): a remote button (≡ by default) shows the ratings; arrows pick one, OK opens it, Back hides them.
 *   The selected title's rating can also show in the corner while you browse.
 * Tap or OK on a rating: the app's own film sheet opens on top. Long-press a rating: "Not a film", hidden from then on.
 */
class OverlayService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private lateinit var api: Api
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lookups = Executors.newFixedThreadPool(4)
    private var tv = false

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val badges = mutableListOf<Badge>()
    private var selected = -1                 // TV: the badge the arrows are on
    private var details: View? = null
    private var detailsWeb: WebView? = null
    private var detailsFor: Pair<String, String>? = null
    private var detailsFilm: String? = null
    private var currentPkg: String? = null
    private var scanning = false
    private var scanSeq = 0
    private var okHeld = false
    private var lastHint = 0L
    // Ratings stay on while you browse: hidden during a scroll, back on the right posters when it stops
    private var sticky = false
    private var choosing = false                    // TV: the arrows move between ratings
    private val sessionFilms = HashMap<String, Film?>()   // screen text → film (null: not a film), for this app
    private var snapSeq = 0
    private var lastContentSnap = 0L
    private val resnapRun = Runnable { resnap() }
    private var pendingRaws: List<String> = emptyList()
    private var pendingCands: List<Candidate> = emptyList()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // ================= lifecycle =================
    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = Store(this)
        api = Api(store)
        tv = isTv(this)
        applyServiceInfo()
        instance = this
        // Screen off / locked: nothing of ours may show over the lock screen
        val lockFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(lockWatch, lockFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(lockWatch, lockFilter)
        main.postDelayed(audioWatch, 1000)
        // Android's accessibility shortcut (button, gesture or both volume keys): show / hide ratings
        runCatching {
            accessibilityButtonController.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) { toggleRatings() }
            })
        }
        currentPkg = rootInActiveWindow?.packageName?.toString()
        updateBubble()
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(lockWatch) }
        main.removeCallbacks(audioWatch)
        clearBadges(); hideDetails(); hideChip(); removeBubble()
        io.shutdownNow(); lookups.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // Android's own panels (notifications, quick settings, lock screen) opening or closing over the app
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.packageName == "com.android.systemui")) {
            main.removeCallbacks(coverCheck); main.postDelayed(coverCheck, 120)
        }
        val pkg = e.packageName?.toString() ?: return
        if (pkg != packageName && !ignoredPackage(pkg)) record(e)
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == packageName || ignoredPackage(pkg)) return
                if (e.className?.toString()?.contains("SoftInputWindow") == true || e.className?.toString()?.contains("InputMethod") == true) return
                // while the film sheet is open, only a real app switch (not a dialog or menu from the same app) closes it
                if (details != null && pkg == currentPkg) return
                if (appEnabled(pkg)) e.className?.toString()?.substringAfterLast('.')?.let { cls ->
                    if (cls != lastScreen) { lastScreen = cls; EventLog.add(this, "screen", EventLog.of("app" to appKey(pkg), "screen" to cls, "title" to e.text.joinToString(" "))) }
                }
                if (pkg != currentPkg) {
                    currentPkg = pkg
                    stopRatings(); sessionFilms.clear(); lastFocusText = null; hideDetails(); hideChip(); updateBubble()
                    bubbleHiddenHere = false
                    if (!tv && store.startMode == "auto" && appEnabled(pkg)) main.postDelayed({ if (!sticky && currentPkg == pkg && !playbackHide) scan(quiet = true) }, 900)
                    if (tv && appEnabled(pkg) && System.currentTimeMillis() - lastHint > 20 * 60_000) {
                        lastHint = System.currentTimeMillis()
                        toast("Marquee Lens: press ${triggerName()} for ratings")
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg != currentPkg || details != null) return
                if (!sticky) {
                    if (badges.isNotEmpty()) clearBadges()
                    // Automatic: ratings come back by themselves once the list stops moving
                    if (!tv && store.startMode == "auto" && appEnabled(pkg) && !playbackHide) { main.removeCallbacks(autoScan); main.postDelayed(autoScan, 500) }
                    return
                }
                // moving: hide the ratings, put them back once it stops
                snapSeq++; choosing = false; selected = -1
                badges.forEach { it.view.animate().cancel(); it.view.animate().alpha(0f).setDuration(90).start() }
                main.removeCallbacks(resnapRun); main.postDelayed(resnapRun, 260)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // the page changed without scrolling (opened a title, a row expanded): re-place, at most every 1.5 s
                if (!sticky || pkg != currentPkg || details != null || choosing || scanning) return
                val now = System.currentTimeMillis()
                if (now - lastContentSnap < 1500) return
                lastContentSnap = now
                main.removeCallbacks(resnapRun); main.postDelayed(resnapRun, 400)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                if (!tv || pkg != currentPkg) return
                val said = eventText(e)
                if (said.isNotEmpty()) lastFocusText = said
                if (playingSince > 0) playingSince = System.currentTimeMillis()   // moved to another title: its rating gets its 10 seconds
                if (!appEnabled(pkg) || details != null || sticky || badges.isNotEmpty()) return
                if (said.isNotEmpty()) {
                    // the selected tile's words (what TalkBack reads): find the title in them
                    main.removeCallbacks(spokenRun); spokenBuf.clear(); spokenBuf.append(said); spokenAt = System.currentTimeMillis(); spokenVia = "focus"
                    hideChip(); main.postDelayed(spokenRun, DWELL_MS)   // only once you stay on a title for a second
                } else if (store.tvFocus) {
                    focusNode = e.source; focusSaid = null
                    hideChip(); main.removeCallbacks(focusLookup); main.postDelayed(focusLookup, DWELL_MS)
                }
            }
        }
    }

    // Keyboards (any brand: Gboard, Samsung, SwiftKey…) open over the app; they aren't a switch to another app
    private var imePackages: Set<String> = emptySet()
    private var imeCheckedAt = 0L
    private fun keyboards(): Set<String> {
        if (System.currentTimeMillis() - imeCheckedAt > 60_000) {
            imeCheckedAt = System.currentTimeMillis()
            imePackages = runCatching { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).enabledInputMethodList.map { it.packageName }.toSet() }.getOrDefault(imePackages)
        }
        return imePackages
    }
    private fun ignoredPackage(p: String) = p == "com.android.systemui" || p.contains("inputmethod") || p.contains("keyboard") || p in keyboards()

    /** Settings changed in the app. */
    fun refresh() { main.post { applyServiceInfo(); updateBubble(); styleBubble(); if (!store.tvFocus) hideChip() } }

    private var appliedReader: Boolean? = null
    private var appliedShortcut: Boolean? = null

    /** The accessibility shortcut (or anything else that toggles): ratings on this screen, or off again. */
    private fun toggleRatings() {
        if (tv) return
        if (!appEnabled(currentPkg)) { toast("Ratings are off for this app. Switch it on in Marquee Lens → Configuration → Overlay"); return }
        if (sticky) { stopRatings(); toast("Ratings off") } else scan(quiet = false)
    }
    /** TV: watch the remote and every kind of event. Screen-reader mode: tell apps a screen reader is running. */
    private fun applyServiceInfo() {
        val reader = store.screenReader
        val shortcut = !tv && store.startMode == "shortcut"
        if (appliedReader == reader && appliedShortcut == shortcut && appliedReader != null) return
        appliedReader = reader; appliedShortcut = shortcut
        serviceInfo = serviceInfo.apply {
            if (tv) { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS; eventTypes = AccessibilityEvent.TYPES_ALL_MASK }
            flags = if (shortcut) flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON else flags and AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON.inv()
            feedbackType = if (reader) AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_GENERIC else AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
    }

    // What the app has been telling accessibility services lately (for the scan report)
    private val recentEvents = ArrayDeque<Pair<String, String>>()   // package → event
    /** The words on a view, or on its first few children when it has none itself (Netflix's TV tiles work this way). */
    private fun nodeText(n: AccessibilityNodeInfo?, depth: Int = 2): String {
        if (n == null) return ""
        val own = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()
        if (own.isNotEmpty() || depth == 0) return own
        return (0 until minOf(n.childCount, 8)).mapNotNull { n.getChild(it) }.map { nodeText(it, depth - 1) }.filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun eventText(e: AccessibilityEvent): String =
        e.text.joinToString(" ").ifBlank { e.contentDescription?.toString() ?: "" }.ifBlank { runCatching { nodeText(e.source) }.getOrDefault("") }
            .replace(Regex("\\s+"), " ").trim()

    private fun record(e: AccessibilityEvent) {
        val text = eventText(e)
        if (text.isEmpty() && e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val line = "${AccessibilityEvent.eventTypeToString(e.eventType).removePrefix("TYPE_")} ${e.className?.toString()?.substringAfterLast('.') ?: ""}: ${text.take(100)}"
        val pkg = e.packageName?.toString() ?: ""
        if (recentEvents.lastOrNull()?.second == line) return
        recentEvents.addLast(pkg to line); while (recentEvents.size > 120) recentEvents.removeFirst()
    }
    /** The last title-like text the app announced for the selected item (when it has no readable screen). */
    private var lastFocusText: String? = null

    /** Ratings are on for this app: its streaming service is switched on, or "Any other app" is. */
    private fun appEnabled(p: String?): Boolean {
        if (p == null || p == packageName || !store.configured) return false
        val service = Services.fromPackage(p)
        return if (service != null) service !in store.offServices else store.everywhere
    }

    // ================= floating button (phone) =================
    private fun updateBubble() {
        val want = !tv && !locked() && appEnabled(currentPkg) && store.startMode == "bubble" && !playbackHide && !bubbleHiddenHere
        if (want) addBubble() else { removeBubble(); if (!appEnabled(currentPkg)) clearBadges() }
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addBubble() {
        if (bubble != null) return
        val size = dp(54)
        val v = FrameLayout(this)
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_logo); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        v.addView(icon, FrameLayout.LayoutParams(size, size))
        v.contentDescription = "Marquee Lens: tap to show or hide ratings, long-press to hide this button here"
        val p = overlayParams(size, size)
        placeBubble(p, size)
        bubble = v; bubbleParams = p
        if (covered) v.visibility = View.INVISIBLE
        styleBubble()
        v.setOnTouchListener(BubbleTouch())
        runCatching { wm.addView(v, p) }.onFailure { bubble = null }
    }

    /** Side + height → screen position, always inside the screen (whichever way the phone is turned). */
    private fun placeBubble(p: WindowManager.LayoutParams, size: Int = dp(54)) {
        val dm = resources.displayMetrics
        p.x = if (store.bubbleSide == 0) dp(8) else dm.widthPixels - size - dp(8)
        p.y = (store.bubbleYFrac * dm.heightPixels).toInt().coerceIn(dp(48), dm.heightPixels - size - dp(48))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post {
            val v = bubble; val p = bubbleParams
            if (v != null && p != null) { placeBubble(p); runCatching { wm.updateViewLayout(v, p) } }
            if (badges.isNotEmpty()) { clearBadges(); if (sticky) { main.removeCallbacks(resnapRun); main.postDelayed(resnapRun, 400) } }
            updateBubble()
        }
    }

    // While a video plays the bubble stays out of the way: phone turned sideways, or sound for 10 seconds
    private val playbackHide: Boolean get() =
        !tv && (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || longPlaying())
    private var bubbleHiddenHere = false

    // ---- Hidden while one of Android's panels covers the app (pulled-down notifications, quick settings, lock screen)
    private var covered = false
    private val coverCheck = Runnable { setCovered(systemPanelOpen() || locked()) }
    private fun systemPanelOpen(): Boolean = runCatching {
        val dm = resources.displayMetrics
        windows.any { w ->
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            val r = Rect(); w.getBoundsInScreen(r)
            (w.isFocused || w.isActive) && w.root?.packageName?.toString() == "com.android.systemui" &&
                r.height() > dm.heightPixels * 0.4 && r.width() > dm.widthPixels * 0.6
        }
    }.getOrDefault(false)
    private fun setCovered(c: Boolean) {
        if (c == covered) return
        covered = c
        val v = if (c) View.INVISIBLE else View.VISIBLE
        badges.forEach { it.view.visibility = v }
        bubble?.visibility = v; chip?.visibility = v
        if (c) EventLog.add(this, "covered", EventLog.of("by" to "Android panel"))
    }

    private val keyguard by lazy { getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager }
    private fun locked() = runCatching { keyguard.isKeyguardLocked }.getOrDefault(false)
    private fun unlocked() {
        updateBubble()
        if (!tv && store.startMode == "auto" && appEnabled(currentPkg)) { main.removeCallbacks(autoScan); main.postDelayed(autoScan, 800) }
    }
    private val lockWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {             // hide everything until you unlock
                    EventLog.add(this@OverlayService, "screen locked")
                    stopRatings(); hideChip(); hideDetails(); removeBubble()
                    EventLog.upload(this@OverlayService)
                }
                Intent.ACTION_USER_PRESENT -> unlocked()                       // unlocked: back as it was
                Intent.ACTION_SCREEN_ON -> if (!locked()) unlocked()          // no lock screen set
            }
        }
    }
    private var lastScreen = ""
    private var trigReported = false

    /** Report a problem from the remote (hold ≡ 3 s): the last minutes of events, the last scan, and what's on screen now. */
    fun reportProblem(note: String) {
        val root = rootInActiveWindow
        val dm = resources.displayMetrics
        val now = JSONObject().put("app", appKey(currentPkg)).put("package", currentPkg ?: "")
            .put("screenNow", JSONArray(runCatching { root?.let { TitleFinder.dump(it, 60) } ?: emptyList() }.getOrDefault(emptyList<String>())))
            .put("selected", lastFocusText ?: "").put("cornerRating", spokenHit?.let { "${it.raw} → ${it.film.title} (${it.film.year})" } ?: "")
            .put("playing", playingSince > 0).put("spoken", JSONArray(spokenLog.toList().takeLast(10)))
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
        EventLog.report(this, note, now)
        toast("Problem report saved, with the last scan and what's on screen. Tell Claude what happened.")
    }
    private val autoScan = Runnable { if (!sticky && !scanning && details == null && store.startMode == "auto" && appEnabled(currentPkg) && !playbackHide) scan(quiet = true) }

    private fun styleBubble() {
        val v = bubble ?: return
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E617141B"))
            setStroke(dp(if (sticky) 4 else 2), Color.parseColor(if (sticky) "#FFFFFF" else "#FFC94A"))
        }
        v.elevation = dp(6).toFloat()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun setBusy(busy: Boolean) {
        val v = bubble ?: return
        v.animate().cancel()
        v.animate().alpha(if (busy) 0.45f else 1f).setDuration(200).start()
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false; private var longFired = false
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private val longPress = Runnable {
            longFired = true
            bubbleHiddenHere = true; updateBubble()
            toast("Button hidden until you leave this app. Want no button at all? Marquee Lens → Configuration → Overlay → Automatic")
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    dragging = false; longFired = false
                    main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, p) } }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        // snap to the nearer side; remember the height as a share of the screen
                        val dm = resources.displayMetrics
                        store.bubbleSide = if (p.x + v.width / 2 < dm.widthPixels / 2) 0 else 1
                        store.bubbleYFrac = (p.y.toFloat() / dm.heightPixels).coerceIn(0.05f, 0.9f)
                        placeBubble(p); runCatching { wm.updateViewLayout(v, p) }
                    }
                    else if (!longFired) { v.performClick(); if (sticky) { stopRatings(); toast("Ratings off") } else scan(quiet = false) }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    // ================= TV remote =================
    private fun triggerName() = if (store.tvKey == "okhold") "and hold OK" else when (store.tvKey) {
        Store.KEY_MENU -> "≡"
        else -> store.tvKey.toIntOrNull()?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_").lowercase().replace('_', ' ') } ?: "≡"
    }

    private fun isDpad(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** Every remote press passes here first (TV only). Returning true keeps it from the app. */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!tv) return false
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN

        // Settings → "Remote button": the next button you press becomes the ratings button
        if (store.learning) {
            if (isDpad(code) || isOk(code) || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) return false
            if (!down) { store.tvKey = code.toString(); store.stopLearning() }
            return true
        }
        if (details != null) return false                       // the film sheet uses the remote itself

        // Hold the ratings button for 3 seconds: report a problem
        if (store.tvKey != "okhold" && code.toString() == store.tvKey) {
            if (down && e.repeatCount == 0) trigReported = false
            if (down && e.repeatCount > 0) {
                if (!trigReported && e.eventTime - e.downTime >= 3000) { trigReported = true; reportProblem("") }
                return true
            }
            if (!down && trigReported) { trigReported = false; return true }
        }

        // The rest of a long OK press that already did something
        if (okHeld && isOk(code)) { if (!down) okHeld = false; return true }
        // Choosing: the arrows move between the ratings
        if (choosing && badges.isNotEmpty()) {
            when {
                isDpad(code) -> { if (down) moveSelection(code); return true }
                isOk(code) -> { if (!down && e.repeatCount == 0) openSelected(); return true }
                code == KeyEvent.KEYCODE_BACK || code.toString() == store.tvKey -> { if (!down) stopChoosing(); return true }
                code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO -> { if (!down) hideSelected(); return true }
                else -> { stopChoosing(); return false }
            }
        }
        // Browsing with ratings on: the remote works the app as usual; ≡ to choose a rating, Back to hide them
        if (sticky) {
            if (code == KeyEvent.KEYCODE_BACK) { if (!down) { stopRatings(); toast("Ratings off") }; return true }
            if (store.tvKey == "okhold" && isOk(code)) {
                if (down && e.repeatCount > 0 && e.eventTime - e.downTime >= 650) { okHeld = true; startChoosing(); return true }
                return false
            }
            if (code.toString() == store.tvKey) { if (!down) startChoosing(); return true }
            return false
        }
        if (!appEnabled(currentPkg)) return false

        if (store.tvKey == "okhold") {
            if (!isOk(code)) return false
            if (down && e.repeatCount == 0) { okHeld = false; return false }
            if (down && !okHeld && e.eventTime - e.downTime >= 650) { okHeld = true; scan(quiet = false); return true }
            return okHeld                                         // swallow the rest of a long press
        }
        if (code.toString() == store.tvKey) { if (!down) scan(quiet = false); return true }
        return false
    }

    private fun startChoosing() {
        if (badges.isEmpty()) { toast("No ratings on this screen"); return }
        choosing = true
        select(badges.indices.minByOrNull { badges[it].p.y * 4 + badges[it].p.x } ?: 0)
        toast("Arrows: choose · OK: details · ≡: not a film · Back: done")
    }

    private fun stopChoosing() {
        choosing = false; selected = -1
        badges.forEach { styleBadge(it, false) }
    }

    /** Ratings off: until the next tap of the bubble (or the ratings button). */
    private fun stopRatings() {
        sticky = false; choosing = false; snapSeq++
        main.removeCallbacks(resnapRun)
        clearBadges(); styleBubble()
    }

    /** Put the ratings back on what's on screen now: films seen before at once, new ones after their lookup. */
    private fun resnap() {
        if (!sticky || details != null || locked()) return
        if (scanning) { main.postDelayed(resnapRun, 250); return }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg != currentPkg || !appEnabled(pkg)) { stopRatings(); return }
        val service = Services.fromPackage(pkg); val app = appKey(pkg)
        val dm = resources.displayMetrics
        val blocked = blockedFor(app); val fixes = fixesFor(app)
        val candidates = TitleFinder.collect(root, dm.widthPixels, dm.heightPixels, dm.density)
            .filter { c -> (c.variants + c.raw).none { Api.norm(it) in blocked } }
        val seq = ++snapSeq
        if (!choosing) selected = -1
        val saved = store.savedIds()
        val todo = mutableListOf<Candidate>()
        val keep = HashSet<Badge>()
        for (c in candidates) {
            val k = Api.norm(c.raw)
            if (!sessionFilms.containsKey(k)) { todo.add(c); continue }
            val f = sessionFilms[k]
            if (f == null && !store.showUnknown) continue
            val hit = if (f != null) Hit(f, c.bounds, c.raw) else unknownHit(c)
            val key = if (f != null) f.id else "?$k"
            if (keep.any { it.key == key }) continue
            val old = badges.firstOrNull { it.key == key && it !in keep }
            if (old != null) { moveBadge(old, c.bounds); keep.add(old) }
            else addBadge(hit, service, app, f != null && f.id in saved)?.let { keep.add(it) }
        }
        badges.filter { it !in keep }.forEach { removeBadge(it) }
        if (todo.isEmpty()) return
        io.execute {
            val found = runCatching { lookups.invokeAll(todo.map { c -> Callable { c to lookup(c, fixes, service) } }).mapNotNull { runCatching { it.get() }.getOrNull() } }.getOrDefault(emptyList())
            val err = lastError; lastError = null
            main.post {
                // a lookup that failed (no connection) is tried again next time; a real "not found" is remembered
                found.forEach { (c, hit) -> if (hit != null || err == null) sessionFilms[Api.norm(c.raw)] = hit?.film }
                if (seq != snapSeq || !sticky || details != null) return@post
                val now = store.savedIds()
                found.forEach { (c, hit) ->
                    if (hit != null) addBadge(hit, service, app, hit.film.id in now)
                    else if (store.showUnknown && err == null) addBadge(unknownHit(c), service, app, false)
                }
            }
        }
    }

    private fun moveSelection(code: Int) {
        if (badges.isEmpty()) return
        val cur = badges[selected.coerceIn(0, badges.size - 1)]
        val (dx, dy) = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0; KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1; else -> 0 to 1
        }
        var best = -1; var bestScore = Double.MAX_VALUE
        badges.forEachIndexed { i, b ->
            if (b === cur) return@forEachIndexed
            val ox = (b.p.x - cur.p.x).toDouble(); val oy = (b.p.y - cur.p.y).toDouble()
            val along = if (dx != 0) ox * dx else oy * dy
            if (along <= dp(8)) return@forEachIndexed
            val across = abs(if (dx != 0) oy else ox)
            val score = along + across * 2.5
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) select(best)
    }

    private fun select(i: Int) {
        selected = i
        badges.forEachIndexed { j, b -> styleBadge(b, j == i) }
    }

    private fun openSelected() {
        val b = badges.getOrNull(selected) ?: return
        showDetails(b.hit.film, b.service, b.app, b.hit.raw)       // a "?" opens the film search
    }

    /** ≡ or Info while choosing: "Not a film" for the selected rating. */
    private fun hideSelected() {
        val b = badges.getOrNull(selected) ?: return
        block(b)
    }

    // ================= TV: rating of the selected title, in the corner =================
    private var focusNode: AccessibilityNodeInfo? = null
    private var focusSaid: String? = null
    private var chip: TextView? = null
    private var chipSeq = 0
    private val focusLookup = Runnable {
        val node = focusNode; val said = focusSaid
        val pkg = currentPkg; val app = appKey(pkg)
        val dm = resources.displayMetrics
        val blocked = blockedFor(app); val fixes = fixesFor(app)
        val fromNode = node?.let { TitleFinder.collect(it, dm.widthPixels, dm.heightPixels * 2, dm.density) }.orEmpty()
        val fromSaid = said?.let { TitleFinder.candidateOf(it, Rect()) }?.let { listOf(it) }.orEmpty()
        val c = (fromNode + fromSaid).firstOrNull { c -> (c.variants + c.raw).none { Api.norm(it) in blocked } }
        if (c == null) { hideChip(); return@Runnable }
        val seq = ++chipSeq
        io.execute {
            val hit = lookup(c, fixes); lastError = null
            main.post { if (seq == chipSeq && details == null && badges.isEmpty()) { if (hit != null) showChip(hit) else hideChip() } }
        }
    }

    // ================= TV: what the app says aloud (Marquee Lens as the TV's silent speech engine) =================
    private val spokenLog = ArrayDeque<String>()
    private val spokenBuf = StringBuilder()
    private var spokenAt = 0L
    private var spokenHit: Hit? = null
    private var spokenHitAt = 0L
    private var spokenSeq = 0
    private val spokenRun = Runnable { val said = spokenBuf.toString(); spokenBuf.clear(); handleSpoken(said) }

    /** Called by [SpeechCatcher] with each thing an app (or TalkBack) says. */
    fun onSpoken(text: String, caller: String) {
        main.post {
            spokenLog.addLast("${caller.substringAfterLast('.').ifBlank { "?" }}: ${text.replace(Regex("\\s+"), " ").take(160)}")
            while (spokenLog.size > 30) spokenLog.removeFirst()
            val pkg = currentPkg ?: return@post
            if (!appEnabled(pkg)) return@post
            // the app itself, or the screen reader reading it (TalkBack shows up as "talkback", "marvin" or "feedback")
            if (caller == packageName) return@post
            val now = System.currentTimeMillis()
            if (now - spokenAt > 1500) spokenBuf.clear()
            spokenBuf.append(text).append(". "); spokenAt = now; spokenVia = "speech"
            main.removeCallbacks(spokenRun); main.postDelayed(spokenRun, if (tv) DWELL_MS else 450)
        }
    }

    private val SAID_SPLIT = Regex("(?<!\\b(?:Mr|Mrs|Ms|Dr|St|Jr|Vol|No|Pt))[.!?]\\s+|[,;\\n]+|\\s[-–|•]\\s")
    private val SAID_YEAR = Regex("\\b(19|20)\\d{2}\\b")
    private val SAID_SERIES = Regex("\\b(season|episode|series|limited series|seasons)\\b|עונה|פרק|סדרה", RegexOption.IGNORE_CASE)

    /** "Top 10 in Israel Today, Stranger Things, TV-14, 2016, 4 Seasons, …": find the title and its rating. */
    private var spokenVia = "focus"
    private fun handleSpoken(said: String, open: Boolean = false) {
        val app = appKey(currentPkg)
        val blocked = blockedFor(app); val fixes = fixesFor(app)
        val year = SAID_YEAR.find(said)?.value
        val series = if (SAID_SERIES.containsMatchIn(said)) true else null
        val candidates = said.split(SAID_SPLIT).map { it.trim() }
            .filter { it.length in 2..60 && it.split(' ').size <= 8 }
            .mapNotNull { TitleFinder.candidateOf(it, Rect()) }
            .filter { c -> (c.variants + c.raw).none { Api.norm(it) in blocked } }
            .distinctBy { Api.norm(it.raw) }.take(4)
            .map { it.copy(year = it.year ?: year, series = it.series ?: series) }
        if (candidates.isEmpty()) return
        val seq = ++spokenSeq
        io.execute {
            // try them all; prefer a film from the year that was said, else the first one that's a real title
            val hits = candidates.map { c -> runCatching { lookup(c, fixes) ?: if (c.year != null) lookup(c.copy(year = null), fixes) else null }.getOrNull() }
            lastError = null
            val hit = hits.filterNotNull().firstOrNull { year != null && it.film.year.startsWith(year) } ?: hits.filterNotNull().firstOrNull()
            main.post {
                if (seq != spokenSeq) return@post
                if (hit == null) { if (open) toast("Couldn't find a film in “${said.take(40)}”"); return@post }
                spokenHit = hit; spokenHitAt = System.currentTimeMillis()
                EventLog.add(this@OverlayService, "chip", EventLog.of("app" to appKey(currentPkg), "said" to said.take(140), "film" to "${hit.film.title} (${hit.film.year})",
                    "id" to hit.film.id, "via" to if (open) "≡" else spokenVia, "playing" to (playingSince > 0)))
                if (open && details == null) { showDetails(hit.film, Services.fromPackage(currentPkg), appKey(currentPkg), hit.raw); return@post }
                if (store.tvFocus && details == null && badges.isEmpty()) showChip(hit)
            }
        }
    }

    // Once a video has been playing for 10 seconds (the TV is playing sound), the corner rating fades away.
    // Shorter sound, like the film you're pointing at starting to autoplay, leaves it up.
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }
    private var playingSince = 0L
    private fun longPlaying() = playingSince > 0 && System.currentTimeMillis() - playingSince >= PLAYING_HIDE_MS
    private val audioWatch = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val playing = runCatching { audio.isMusicActive }.getOrDefault(false)
            val hiddenBefore = playbackHide
            if (playing != (playingSince > 0)) EventLog.add(this@OverlayService, "playback", EventLog.of("app" to appKey(currentPkg), "sound" to if (playing) "started" else "stopped"))
            playingSince = if (playing) (if (playingSince == 0L) now else playingSince) else 0L
            EventLog.uploadIfDue(this@OverlayService)
            if (chip != null && longPlaying()) fadeChip()
            if (!tv && hiddenBefore != playbackHide) updateBubble()
            main.postDelayed(this, 1000)
        }
    }

    private fun fadeChip() {
        val c = chip ?: return
        c.animate().cancel()
        c.animate().alpha(0f).setDuration(700).withEndAction { if (chip === c) hideChip() }.start()
    }

    private fun showChip(hit: Hit) {
        if (longPlaying() || locked()) return
        val f = hit.film
        val saved = f.id in store.savedIds()
        val text = (if (saved) "★ " else "") + (if (f.imdb.isBlank()) "No IMDb rating" else "IMDb ${f.imdb}") + "\n" + f.title + (if (f.year.isNotBlank()) " (${f.year.take(4)})" else "")
        val v = chip ?: TextView(this).apply {
            setTextColor(Color.parseColor("#F6F1E9")); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(9)); maxWidth = dp(320)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#E617141B")); setStroke(dp(2), Color.parseColor("#FFC94A")) }
            val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = false).apply {
                gravity = Gravity.TOP or Gravity.END; x = dp(28); y = dp(24)
            }
            if (runCatching { wm.addView(this, p) }.isFailure) return
            chip = this
        }
        v.text = text
        v.animate().cancel(); v.alpha = 1f
        v.visibility = if (covered) View.INVISIBLE else View.VISIBLE
    }

    private fun hideChip() {
        chipSeq++
        chip?.let { runCatching { wm.removeView(it) } }
        chip = null
    }

    // ================= scanning =================
    private data class Hit(val film: Film, val bounds: Rect, val raw: String)

    /** The filter key for this app: its service (netflix, yes…) or, for other apps, the package name. */
    private fun appKey(pkg: String?) = Services.fromPackage(pkg) ?: pkg.orEmpty()

    fun scan(quiet: Boolean) {
        if (scanning || locked()) return
        val root = rootInActiveWindow ?: run { if (!quiet) toast("Can't read this screen"); return }
        val pkg = root.packageName?.toString()
        val service = Services.fromPackage(pkg)
        val app = appKey(pkg)
        val dm = resources.displayMetrics
        val blocked = blockedFor(app)
        val fixes = fixesFor(app)
        var all = TitleFinder.collect(root, dm.widthPixels, dm.heightPixels, dm.density)
        // nothing readable on screen, but the app announced the selected title: rate that one
        if (all.isEmpty()) lastFocusText?.let { TitleFinder.candidateOf(it, Rect(dm.widthPixels / 3, dm.heightPixels / 3, dm.widthPixels / 2, dm.heightPixels / 2)) }?.let { all = listOf(it) }
        val candidates = all.filter { c -> (c.variants + c.raw).none { Api.norm(it) in blocked } }
        scanInfo = JSONObject().put("at", System.currentTimeMillis()).put("app", app).put("package", pkg ?: "")
            .put("device", if (tv) "tv" else "phone").put("screen", JSONArray(TitleFinder.dump(root)))
            .put("candidates", JSONArray(candidates.map { it.raw })).put("hiddenByFilter", all.size - candidates.size)
            .put("events", JSONArray(recentEvents.filter { it.first == pkg }.map { it.second }.takeLast(40)))
            .put("otherEvents", recentEvents.count { it.first != pkg }).put("screenReader", store.screenReader)
            .put("spoken", JSONArray(spokenLog.toList().takeLast(30))).put("speechEngine", Bridge.speechEngineIsOurs(this))
            .put("windows", JSONArray(windows.mapNotNull { w -> w.root?.let { "${w.title ?: ""} ${it.packageName} children=${it.childCount}" } }))
        hideDetails(); hideChip(); clearBadges()
        sticky = true; choosing = false; snapSeq++; styleBubble()
        pendingRaws = candidates.map { it.raw }; pendingCands = candidates
        val seq = ++scanSeq
        scanning = true; setBusy(true)
        if (!quiet && tv) toast("Reading the screen…")
        val focusedText = runCatching {
            listOf(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY, AccessibilityNodeInfo.FOCUS_INPUT).map { nodeText(root.findFocus(it)) }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())
        scanInfo?.put("focused", JSONArray(focusedText))
        if (candidates.isEmpty() && tv && !quiet && focusedText.isNotEmpty()) {
            store.saveScan(scanInfo.toString())
            scanning = false; setBusy(false); sticky = false
            toast("Looking up the selected title…")
            handleSpoken(focusedText.joinToString(". "), open = true); return
        }
        if (candidates.isEmpty() && tv && !quiet) {
            val h = spokenHit
            if (h != null && System.currentTimeMillis() - spokenHitAt < 120_000) {
                scanInfo?.put("found", JSONArray(listOf("(spoken) ${h.raw} → ${h.film.title} (${h.film.year}) ${h.film.id}")))?.let { store.saveScan(it.toString()) }
                scanning = false; setBusy(false); sticky = false
                showDetails(h.film, service, app, h.raw); return
            }
        }
        if (candidates.isEmpty()) { screenshotScan(seq, service, app, quiet); return }
        io.execute {
            val hits = try {
                lookups.invokeAll(candidates.map { c -> Callable { lookup(c, fixes, service) } }).mapNotNull { runCatching { it.get() }.getOrNull() }
            } catch (e: Exception) { emptyList() }
            val err = lastError; lastError = null
            main.post { finishScan(seq, hits, service, app, quiet, err) }
        }
    }

    private var scanInfo: JSONObject? = null

    private fun blockedFor(app: String) = store.blocks().filter { it.app.isBlank() || it.app == app }.map { Api.norm(it.text) }.toSet()
    private fun fixesFor(app: String) = store.fixes().filter { it.app.isBlank() || it.app == app }.associate { Api.norm(it.text) to it.id }

    @Volatile private var lastError: String? = null

    private fun lookup(c: Candidate, fixes: Map<String, String>, service: String? = Services.fromPackage(currentPkg)): Hit? {
        val t0 = System.currentTimeMillis()
        val app = appKey(currentPkg)
        fun logged(h: Hit, how: String): Hit {
            val ms = System.currentTimeMillis() - t0
            EventLog.add(this@OverlayService, "lookup", EventLog.of("app" to app, "raw" to c.raw, "film" to "${h.film.title} (${h.film.year})", "id" to h.film.id,
                "imdb" to h.film.imdb, "how" to if (how == "search" && ms < 40) "remembered" else how, "year" to c.year, "series" to c.series, "ms" to ms))
            return h
        }
        try {
            // a title you corrected before with "Wrong title?"
            for (v in listOf(c.raw) + c.variants) fixes[Api.norm(v)]?.let { id -> api.filmById(id)?.let { return logged(Hit(it, c.bounds, c.raw), "your correction") } }
            for (v in c.variants) api.findFilm(v, c.year, service, c.series, c.country.takeIf { v != c.raw })?.let { return logged(Hit(it, c.bounds, c.raw), if (v == c.variants.first()) "search" else "search: $v") }
        } catch (e: ApiException) { lastError = e.message } catch (e: Exception) { lastError = "No connection" }
        if (lastError != null) EventLog.add(this@OverlayService, "error", EventLog.of("where" to "lookup", "raw" to c.raw, "msg" to lastError))
        else EventLog.add(this@OverlayService, "miss", EventLog.of("app" to app, "raw" to c.raw, "tried" to c.variants.joinToString(" | "), "year" to c.year, "ms" to System.currentTimeMillis() - t0))
        return null
    }

    private fun finishScan(seq: Int, hits: List<Hit>, service: String?, app: String, quiet: Boolean, error: String?) {
        scanning = false; setBusy(false)
        if (seq != scanSeq) return
        val unique = hits.distinctBy { it.film.id }
        val saved = store.savedIds()
        for (r in pendingRaws) hits.firstOrNull { it.raw == r }?.film.let { if (it != null || error == null) sessionFilms[Api.norm(r)] = it }
        unique.forEach { addBadge(it, service, app, it.film.id in saved) }
        if (store.showUnknown && error == null) pendingCands.filter { c -> hits.none { it.raw == c.raw } }.forEach { addBadge(unknownHit(it), service, app, false) }
        EventLog.add(this, "scan", EventLog.of("app" to app, "trigger" to if (quiet) "auto" else "you", "read" to pendingCands.size, "found" to unique.size, "error" to error))
        scanInfo?.let { info ->
            info.put("found", JSONArray(unique.map { "${it.raw} → ${it.film.title} (${it.film.year}) ${it.film.id}" }))
            if (error != null) info.put("error", error)
            store.saveScan(info.toString())
        }
        if (unique.isEmpty() && (tv || store.startMode != "auto")) { sticky = false; styleBubble() }   // nothing to keep showing
        if (tv && badges.isNotEmpty() && !quiet) toast("Ratings stay while you browse · ${triggerName()}: choose one · Back: hide")
        if (!quiet) when {
            unique.isNotEmpty() -> {}
            error != null -> toast(error)
            else -> toast("No films recognized on this screen")
        }
    }

    /** For apps that don't label their tiles: a screenshot goes to Gemini (Android 11+, and only if the app allows screenshots). */
    private fun screenshotScan(seq: Int, service: String?, app: String, quiet: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !store.hasGemini) {
            finishScan(seq, emptyList(), service, app, quiet, if (quiet) null else "This app doesn't show its titles to Marquee Lens")
            return
        }
        bubble?.visibility = View.INVISIBLE
        main.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    bubble?.visibility = View.VISIBLE
                    val hb = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    if (bmp == null) { finishScan(seq, emptyList(), service, app, quiet, "Couldn't read the screen"); return }
                    io.execute {
                        val hits = runCatching { geminiHits(bmp, app) }.getOrElse { lastError = it.message; emptyList() }
                        val err = lastError; lastError = null
                        main.post { finishScan(seq, hits, service, app, quiet, err) }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    bubble?.visibility = View.VISIBLE
                    finishScan(seq, emptyList(), service, app, quiet, if (quiet) null else "This app hides its screen and doesn't show its titles, so Marquee Lens can't read it")
                }
            })
        }, 60)
    }

    private fun geminiHits(full: Bitmap, app: String): List<Hit> {
        val blocked = blockedFor(app); val fixes = fixesFor(app)
        val sw = full.width; val sh = full.height
        val k = min(1f, 1024f / max(sw, sh))
        val small = Bitmap.createScaledBitmap(full, (sw * k).toInt(), (sh * k).toInt(), true)
        val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpg, Base64.NO_WRAP))))
            .put(JSONObject().put("text", PROMPT))
        val text = api.gemini(parts)
        val arr = runCatching { JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1)) }.getOrElse { JSONArray() }
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optDouble("confidence", 1.0) >= 0.5 && Api.norm(it.optString("title")) !in blocked }
        return lookups.invokeAll(items.map { o ->
            Callable {
                val b = o.optJSONArray("box_2d")
                val r = if (b != null && b.length() == 4) Rect((b.optDouble(1) / 1000 * sw).toInt(), (b.optDouble(0) / 1000 * sh).toInt(), (b.optDouble(3) / 1000 * sw).toInt(), (b.optDouble(2) / 1000 * sh).toInt()) else Rect(0, 0, 0, 0)
                val year = o.optString("year").take(4).takeIf { it.length == 4 }
                val title = o.optString("title")
                runCatching { fixes[Api.norm(title)]?.let { api.filmById(it) } ?: api.findFilm(title, year, Services.fromPackage(currentPkg)) }.getOrNull()?.let { Hit(it, r, title) }
            }
        }).mapNotNull { runCatching { it.get() }.getOrNull() }
    }

    // ================= rating badges =================
    private class Badge(val view: TextView, val hit: Hit, val service: String?, val app: String, val p: WindowManager.LayoutParams, var saved: Boolean)

    private val Badge.unknown get() = hit.film.id.isEmpty()
    private val Badge.key get() = if (unknown) "?" + Api.norm(hit.raw) else hit.film.id
    private fun unknownHit(c: Candidate) = Hit(Film(id = "", title = c.raw), c.bounds, c.raw)

    private fun badgeText(b: Badge) = if (b.unknown) "?" else (if (b.saved) "★ " else "") + b.hit.film.imdb.ifBlank { "n/a" }

    private fun styleBadge(b: Badge, chosen: Boolean) {
        val rated = b.hit.film.imdb.isNotBlank() && !b.unknown
        b.view.text = badgeText(b)
        b.view.background = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(Color.parseColor(if (rated) "#FFC94A" else "#3A3340"))
            if (chosen) setStroke(dp(3), Color.WHITE) else setStroke(dp(1), Color.parseColor("#66000000"))
        }
        b.view.scaleX = if (chosen) 1.25f else 1f; b.view.scaleY = b.view.scaleX
        b.view.elevation = dp(if (chosen) 10 else 4).toFloat()
    }

    private fun badgeX(r: Rect) = (r.left + dp(4)).coerceIn(0, resources.displayMetrics.widthPixels - dp(56))
    private fun badgeY(r: Rect) = (r.top + dp(4)).coerceIn(0, resources.displayMetrics.heightPixels - dp(30))

    /** A rating that's still right: move it if its poster moved, and show it again. */
    private fun moveBadge(b: Badge, r: Rect) {
        val x = badgeX(r); val y = badgeY(r)
        if (abs(b.p.x - x) > 2 || abs(b.p.y - y) > 2) { b.p.x = x; b.p.y = y; runCatching { wm.updateViewLayout(b.view, b.p) } }
        b.view.animate().cancel(); b.view.animate().alpha(1f).setDuration(120).start()
    }

    private fun addBadge(hit: Hit, service: String?, app: String, saved: Boolean): Badge? {
        val film = hit.film
        val r = hit.bounds
        // one rating per film, and never two on the same spot
        val key = if (film.id.isEmpty()) "?" + Api.norm(hit.raw) else film.id
        badges.firstOrNull { it.key == key }?.let { moveBadge(it, r); return it }
        if (badges.any { abs(it.p.x - badgeX(r)) < dp(14) && abs(it.p.y - badgeY(r)) < dp(14) }) return null
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = !tv).apply {
            x = badgeX(r); y = badgeY(r)
        }
        val tvw = TextView(this).apply {
            setTextColor(Color.parseColor(if (film.imdb.isBlank()) "#B0A697" else "#1D1606"))
            textSize = if (tv) 19f else 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(3), dp(7), dp(3))
            contentDescription = if (film.id.isEmpty()) "“${hit.raw}” wasn't found. Tap to pick the film, long-press if it's not a film."
                else "${film.title}, IMDb ${film.imdb.ifBlank { "no rating" }}. Tap for details, long-press if it's not a film."
        }
        val b = Badge(tvw, hit, service, app, p, saved)
        styleBadge(b, false)
        tvw.setOnClickListener { showDetails(film, service, app, hit.raw) }   // a "?" opens the film search
        tvw.setOnLongClickListener { block(b); true }
        tvw.alpha = 0f
        if (covered) tvw.visibility = View.INVISIBLE
        return runCatching { wm.addView(tvw, p); badges.add(b); tvw.animate().alpha(1f).setDuration(140).start(); b }.getOrNull()
    }

    /** "Not a film": hide this text in this app from now on. */
    private fun block(b: Badge) {
        EventLog.add(this, "not a film", EventLog.of("app" to b.app, "raw" to b.hit.raw, "was" to b.hit.film.title))
        store.addBlock(b.hit.raw, b.app)
        removeBadge(b)
        toast("Hidden: “${b.hit.raw.take(40)}”. Undo in Marquee Lens → Configuration → Overlay → Advanced")
    }

    private fun removeBadge(b: Badge) {
        runCatching { wm.removeView(b.view) }
        val i = badges.indexOf(b); badges.remove(b)
        if (badges.isEmpty()) { selected = -1; choosing = false }
        else if (choosing && selected >= 0) select(if (i <= selected) max(0, selected - 1) else selected)
    }

    private fun clearBadges() {
        badges.forEach { runCatching { wm.removeView(it.view) } }
        badges.clear(); selected = -1
    }

    // ================= film details: the app's own sheet, over the other app =================
    private fun showDetails(film: Film, service: String?, app: String, raw: String) {
        hideDetails()
        EventLog.add(this, if (film.id.isEmpty()) "open ?" else "open", EventLog.of("app" to app, "raw" to raw, "film" to film.title.takeIf { film.id.isNotEmpty() }, "id" to film.id))
        val wv = WebView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val host = object : WebHost {
            override fun saveFile(name: String, type: String, text: String) = Bridge.shareFileNow(this@OverlayService, this, name, type, text)
            override fun startIntent(intent: Intent) {
                runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("No app can open this") }
                hideDetails()
            }
            override fun closeOverlay() = hideDetails()
        }
        Web.setup(wv, this, Bridge(this, host)) { host.startIntent(Intent(Intent.ACTION_VIEW, it)) }
        wv.webChromeClient = WebChromeClient()
        val frame = object : FrameLayout(this) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_UP) wv.evaluateJavascript("window.mlBack ? mlBack() : false") { if (it != "true") hideDetails() }
                return true
            }
        }
        frame.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,     // focusable: typing and the remote work in the sheet
            PixelFormat.TRANSLUCENT
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        if (runCatching { wm.addView(frame, p) }.isFailure) { wv.destroy(); toast("Couldn't open the details"); return }
        details = frame; detailsWeb = wv
        detailsFor = raw to app; detailsFilm = film.id
        wv.requestFocus()
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val what = if (film.id.isEmpty()) "find=1" else "film=${enc(film.id)}"
        wv.loadUrl("${Web.ORIGIN}/index.html?overlay=1&$what&svc=${enc(service ?: "")}&app=${enc(app)}&raw=${enc(raw)}")
    }

    private fun hideDetails() {
        val d = details ?: return
        details = null
        runCatching { wm.removeView(d) }
        detailsWeb?.destroy(); detailsWeb = null
        // the film may have been added, removed or hidden: refresh the stars, drop a hidden one
        val saved = store.savedIds()
        badges.forEachIndexed { i, b -> b.saved = b.hit.film.id in saved; styleBadge(b, i == selected) }
        if (sticky) { main.removeCallbacks(resnapRun); main.postDelayed(resnapRun, 200) }
        val (raw, app) = detailsFor ?: return
        detailsFor = null
        sessionFilms.remove(Api.norm(raw))          // a film may have been picked for this text: look it up again
        if (Api.norm(raw) in blockedFor(app)) badges.filter { it.hit.film.id == detailsFilm }.forEach { removeBadge(it) }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG.takeIf { tv } ?: Toast.LENGTH_SHORT).show() }

    companion object {
        @Volatile var instance: OverlayService? = null
        private const val PLAYING_HIDE_MS = 10_000L
        /** TV: how long you stay on a title before its rating is looked up (moving on cancels it). */
        private const val DWELL_MS = 1_000L

        fun isTv(ctx: Context): Boolean =
            (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        private const val PROMPT = """Identify every film or TV series visible in this screenshot of a streaming app: poster tiles, title cards or banners.
For each item return:
- title: the official English title (as listed on IMDb)
- year: its original release year
- box_2d: bounding box [ymin, xmin, ymax, xmax] normalized to 0-1000
- confidence: 0 to 1
Only include items you are reasonably sure about. Answer with ONLY a JSON array, e.g. [{"title":"Inception","year":2010,"box_2d":[100,200,600,500],"confidence":0.9}]. Return [] if there are none."""
    }
}
