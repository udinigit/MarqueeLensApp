**New here? Start with [SETUP.md](SETUP.md).**

# Marquee Lens for Android

The full Marquee Lens app (lists, camera, sharing, settings) **plus** an optional floating button over streaming apps
(Netflix, Disney+, HBO Max, Prime Video, Apple TV, YouTube, yes, HOT, STINGTV, Partner TV…).
Tap the button: the IMDb rating appears on every title on screen. Tap a rating: the same film sheet as in camera mode opens on top.

The app is the web app (`app/src/main/assets/index.html`, a copy of `web/marquee-lens.html`) running inside the APK, so both
always look and work the same. When you change `web/marquee-lens.html`, copy it over `app/src/main/assets/index.html` before building.

It reads titles the way screen readers do (Android's accessibility service), so it works even in apps that block screenshots, like Netflix.
Hebrew titles are looked up by their Hebrew name on TMDB, then rated from IMDb.
For apps that don't label their titles, it can fall back to a screenshot read by Gemini (Android 11+, only where the app allows screenshots).

## Build it online, free (no Android Studio needed)
1. Sign in at github.com → **New repository** → name it `MarqueeLens` → **Private** is fine → **Create repository**.
2. On the new repository page click **uploading an existing file**, and drag in **everything inside** the `MarqueeLensAndroid` folder
   (including the `.github` folder — if your computer hides it, see the note below) → **Commit changes**.
3. Open the **Actions** tab. A run called **Build APK** starts by itself (about 5 minutes).
4. When it shows a green tick, open the run → under **Artifacts** download **MarqueeLens-apk** → unzip → `app-debug.apk`.
5. Send the APK to your phone (Drive, email, WhatsApp to yourself…) and open it to install.

Note: folders starting with a dot can be hidden. If `.github` didn't upload, in the repository click **Add file → Create new file**,
type the name `.github/workflows/build.yml`, paste the contents of that file from this folder, and **Commit**.
Free: unlimited for public repositories, 2,000 build minutes a month for private ones.

## Your signing key (so new builds install as updates)
Android only installs a new build over the old one (keeping your lists) when both are signed with the same key.
Set this up once, before your next build:
1. In the GitHub repository: **Settings → Secrets and variables → Actions → New repository secret**.
2. Name `SIGNING_KEY`, value: the long line from SETUP.md step 5 → **Add secret**.
3. Again: name `SIGNING_PASSWORD`, value: your keystore password → **Add secret**.
4. **Actions → Build APK → Run workflow.** The log's "Use your signing key" step says *Signing with your fixed key*.
The first build with the key still needs the old app uninstalled once. After that, every new APK installs as an update.
Keep your keystore and its password somewhere safe and private. If it's lost, the next build can't update the installed app.
Never upload the key file itself to the repository; the secrets are enough.

## Build it in Android Studio instead (about 20 minutes the first time)
1. Install **Android Studio** (free): developer.android.com/studio
2. **File → Open** → choose this `MarqueeLensAndroid` folder. Let it finish "Gradle sync" (it downloads what it needs).
3. Optional: to give friends a ready-made app, put your Worker address in `DEFAULT_SERVER` in **both**
   `app/src/main/assets/index.html` and `app/src/main/java/com/marqueelens/Store.kt`.
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. When it's done, click **locate**: the file is `app-debug.apk`.
5. Copy the APK to your phone (or connect the phone with USB debugging and press ▶ Run) and install it.
   Android will ask you to allow installing apps from that source.

## Set it up (on the phone)
1. Open **Marquee Lens**. Choose **Invite code** or **My own keys** (OMDb; optional Gemini and TMDB, which Hebrew titles need) → Save.
2. To use the overlay: **Settings** (top right) → *Ratings over streaming apps* → **Turn on** → *Installed apps* (or *Downloaded apps*)
   → **Marquee Lens ratings** → On.
3. **Android 13 and later:** if the switch is greyed out, open the phone's **Settings → Apps → Marquee Lens** → ⋮ (top right)
   → **Allow restricted settings**, then try step 2 again. (Android adds this step for apps installed outside the Play Store.)

## Use the overlay
- Open a streaming app. The round button appears on the side. **Tap** it: ratings appear on the tiles and stay on while you browse:
  they hide while you scroll and come back on the right posters when you stop. **Tap** the button again to turn them off
  (white ring = ratings on).
- **Tap a rating**: the film sheet opens over the app (add to a list, services, Wrong title?, More like this, IMDb). Back or swipe down closes it.
- **Long-press a rating** if it isn't a film (a menu, a row name…): it's hidden in that app from then on.
  The film sheet also has **Not a film · hide it**.
- **Wrong title?** in the sheet: pick the right film, and the overlay remembers it for that text in that app.
- **Drag** the button to move it (it snaps to the nearest side). **Long-press** it to hide it until you leave that app.
  While a video plays (phone sideways, or sound for 10 s) the button keeps out of the way.
- Configuration → Overlay → **How ratings appear**: **Bubble** (tap the button), **Automatic** (no button: ratings appear
  by themselves) or **Shortcut** (no button: Android's accessibility shortcut, set in Accessibility → Marquee Lens ratings).
- Settings → *Ratings over streaming apps*:
  - **Show the bubble in:** switch any app on or off (e.g. YouTube off), or turn on *Any other app*.
  - **Not a film:** see what's hidden, tap one to show it again, **Export filter / Import filter** (a .json file; backups include it too).

## Share to Marquee Lens
- Share a **screenshot** (from the gallery, or right after taking it) → **Marquee Lens · Ratings & lists**: the films in it are
  recognised; one opens its details, several go through the review below.
- Share **text** (Netflix's "Check out …", an IMDb link, a list of names) the same way.
- **Add several at once** (under the add box on the Lists page, or ⋯): paste titles, one per line or with commas, English or
  Hebrew → pick a list or a new one → each match is shown one by one (Add / Not this one / Skip) → "x/y added", with a
  Search button for each title that wasn't found.

## Lists, sharing and files
Everything is under **Share & files** on the Lists page: send a list as a message or a file, save a file or spreadsheet,
back up everything, open a file (list, spreadsheet, backup or overlay filter), or paste a shared link.
Shared links need a web address: set `SHARE_BASE` in `index.html` to where the web app is hosted (e.g. your Netlify site);
without it, messages carry the titles only and files still work.

## On a TV (NVIDIA Shield, Android TV, Google TV)
The same APK works on the TV. There is no floating button there; the remote does it:
- In Netflix (or any app switched on in Settings), press **≡** on the remote: ratings appear on the titles and stay while you
  browse with the remote as usual. **Back** turns them off.
- Press **≡** again to pick one: **arrows** move between ratings, **OK** opens the film sheet, **≡** marks it *Not a film*,
  **Back** returns to browsing.
- While you browse, the rating of the selected title shows in the top-right corner (Settings can switch this off).
- Settings → *Ratings button on the remote → Change* picks another button (press it), or *hold OK*.
- In the app itself, the arrows move between buttons and OK presses them.
- Nothing found? Settings → *Last overlay scan → View* shows what the TV app exposed; take a photo of it for support.

### Netflix on the TV
Netflix's TV app draws its own screen and shares no titles with other apps, so ≡ finds nothing there. It does say the selected
title aloud when a screen reader is on, and Marquee Lens can listen instead, silently:
1. Marquee Lens → ⚙ → Overlay → *Netflix on this TV* → **Set up** → *Open speech settings* → Preferred engine →
   **Marquee Lens (silent, for ratings)**.
2. Turn on **TalkBack** (Settings → Device Preferences → Accessibility). Its voice now goes to Marquee Lens, so the TV stays quiet.
3. In Netflix, move between titles: the rating of the selected one shows in the corner. Press **≡** for the full film sheet.
To undo: choose your previous engine again (usually Google) and turn TalkBack off.

### Install on the Shield
**Easiest (phone, no computer):** install **Send files to TV** from the Play Store on the phone and on the Shield.
On the phone: Send → pick `app-debug.apk` → pick the Shield. On the Shield, open Send files to TV → the APK → Install.
If asked, allow *Send files to TV* to install apps (it opens the setting; switch it on, press Back) and Install again.

**From a computer (skips Android's install restrictions):**
1. Shield: Settings → Device Preferences → About → select **Build** and press OK 7 times.
2. Back → Device Preferences → **Developer options** → **Network debugging** on. Note the IP address shown.
3. Computer: download *SDK Platform-Tools* (developer.android.com/tools/releases/platform-tools), unzip, open a terminal there:
   `adb connect 192.168.1.50:5555` (your Shield's IP) → on the TV choose *Always allow* → then `adb install -r app-debug.apk`

### Turn it on
1. Shield: Settings → Device Preferences → **Accessibility** → **Marquee Lens ratings** → Enable → OK.
2. Open **Marquee Lens** (Apps row on the home screen, or *See all apps*) → Settings → **Invite code** → type it → Save.
   Tip: the **Google TV** app on your phone has a remote with a keyboard, much easier than typing with the remote.

## Privacy
The screen is read only when you tap the button (or in Live mode), and only the titles it finds are sent to look up ratings.
With an invite code, requests go through your Cloudflare Worker; your API keys never reach the phone.

## Good to know
- Publishing on Google Play would need an accessibility-use declaration and Google's review; this build is meant for installing yourself.
- If an app updates its screens and stops exposing titles, the Gemini fallback (needs a Gemini key or the invite server) still works where screenshots are allowed.
- Built for Android 8.0+ (Gemini fallback: Android 11+). No extra libraries: only Android and Kotlin.
- The Camera tab works inside the app too (Android asks for camera permission the first time). Saying a title by voice isn't
  available inside the app; type it instead.
