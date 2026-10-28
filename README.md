# Marquee Lens: setup guide

Marquee Lens keeps lists of films and series with their IMDb ratings, where to watch them and what you've seen.
On Android it can also show IMDb ratings **on top of streaming apps** (Netflix, Disney+, HBO Max, Prime Video, yes, HOT…),
read posters with the phone's **camera**, and run on an **Android TV / NVIDIA Shield** with the remote.

Everything here is free for personal use. Plan about an hour the first time.

```
MarqueeLens/
├── SETUP.md                  ← this guide
├── README.md                 ← how the Android app is used, in more detail
├── worker/marquee-lens-worker.js   ← your small server (Cloudflare Worker)
├── web/marquee-lens.html     ← the same app as a web page (optional)
├── app/ …                    ← the Android app's source
└── .github/workflows/build.yml     ← builds the Android app on GitHub, for free
```

---

## 1. How it fits together

```
 Phone / TV app ──invite code──▶ Your Cloudflare Worker ──your keys──▶ Gemini · OMDb · TMDB
                                       │
                                       └── D1 database: your lists (synced between devices) and 24-hour logs
```

The app never holds your API keys. It only has an **invite code**; the Worker keeps the keys as secrets and checks
the code on every request. Each invite code (one per person) has its own lists.

## 2. Accounts and keys you need

| What | Where | Notes |
|---|---|---|
| **GitHub** account | github.com | Builds the Android app for you |
| **Cloudflare** account | dash.cloudflare.com | Free plan is enough |
| **Gemini API key** | aistudio.google.com/apikey | Recognises posters and screenshots. Keep **billing off** on its Google project, so the worst case is running out of free quota, never a bill |
| **OMDb API key** | omdbapi.com/apikey.aspx | Titles and IMDb ratings. Free tier: activate it from the email they send |
| **TMDB key** | themoviedb.org/settings/api (after signing up) | Hebrew and other local titles, similar films, where to watch. Either the "API Key" or the "Read Access Token" works |

## 3. Your server (Cloudflare Worker)

### 3.1 Create it
1. Cloudflare dashboard → **Workers & Pages** → **Create** → **Create Worker** → name it `marquee-lens` → **Deploy**.
2. **Edit code** → delete what's there → paste all of `worker/marquee-lens-worker.js` → **Deploy**.
3. Note its address, like `https://marquee-lens.your-name.workers.dev`.

### 3.2 Keys and invite codes
Worker → **Settings** → **Variables and Secrets** → **Add**:

| Name | Type | Value |
|---|---|---|
| `GEMINI_KEY` | Secret | your Gemini key |
| `OMDB_KEY` | Secret | your OMDb key |
| `TMDB_KEY` | Secret | your TMDB key |
| `INVITE_CODES` | Secret | `Me:Xr7kP2mQ9vTz, Dana:b4Lw8nYq3sHe` — a name, a colon, a code; commas between people |
| `DAILY_AI_LIMIT` | Text | `100` (AI requests per person per day) |
| `ALLOWED_ORIGINS` | Text | *Only if you put the web page on a site:* that site's address, e.g. `https://my-lens.netlify.app`. The Android app is always allowed |

Click **Deploy** after adding them.

> **Make invite codes long and random: 12 or more letters and digits.** Anyone who has your Worker's address could try
> to guess codes; long ones can't be guessed. To remove someone, delete their entry and deploy.

### 3.3 The database (lists sync and logs)
1. **Storage & Databases** → **D1 SQL Database** → **Create** → name it `marquee-lists`.
2. Worker → **Settings** → **Bindings** → **Add** → **D1 database** → variable name **`DB`** → choose `marquee-lists` → **Deploy**.

The tables are created by themselves. Without this the app still works, but lists stay on each device and there are no logs.

### 3.4 Daily AI limit per person (optional)
1. **Storage & Databases** → **KV** → **Create** → name it `marquee-usage`.
2. Worker → **Settings** → **Bindings** → **Add** → **KV namespace** → variable name **`USAGE`** → choose it → **Deploy**.

### 3.5 Check it
Open `https://<your-worker-address>/me` in a browser. **"Invite code not recognized"** means it's running
(a browser doesn't send a code).

## 4. Put your Worker address into the app

Search for `PUT YOUR WORKER ADDRESS HERE` and fill in your address (no slash at the end) in:

1. `app/src/main/assets/index.html` — the app's screens
2. `app/src/main/java/com/marqueelens/Store.kt` — the ratings over other apps
3. `web/marquee-lens.html` — only if you'll use the web page

```js
const DEFAULT_SERVER = "https://marquee-lens.your-name.workers.dev";
```

If you skip this, the app asks for the server address on first start (under **More**).

## 5. A signing key (recommended)

Android only installs a new version over the old one (keeping your lists and settings) when both are signed with the
same key. Without your own key, every build gets a random one and you'd have to uninstall before each update.

Make one, once, on any computer with Java installed (`keytool` comes with it):

```bash
keytool -genkeypair -keystore marquee.keystore -storetype PKCS12 -alias marquee \
  -keyalg RSA -keysize 2048 -validity 12000 -dname "CN=Marquee Lens"
```

Choose a password when asked (use it for both prompts). Then turn the file into one line of text:

| System | Command |
|---|---|
| macOS | `base64 -i marquee.keystore` |
| Linux | `base64 -w0 marquee.keystore` |
| Windows (PowerShell) | `[Convert]::ToBase64String([IO.File]::ReadAllBytes("marquee.keystore"))` |

In your GitHub repository (step 6): **Settings → Secrets and variables → Actions → New repository secret**:
- `SIGNING_KEY` — that long line
- `SIGNING_PASSWORD` — your password

> **Keep `marquee.keystore` and its password private and backed up** (e.g. in a password manager).
> **Never upload the keystore to the repository**; the secrets are enough. If it's lost, the next build can't update the
> installed app (uninstall once and carry on with a new key).

## 6. Build the Android app (on GitHub, free)

1. github.com → **New repository** → name it `MarqueeLens` → **Private** → **Create**.
2. **uploading an existing file** → drag in **everything inside** the `MarqueeLens` folder → **Commit changes**.
   Folders starting with a dot (`.github`) are often hidden: if it didn't upload, use **Add file → Create new file**,
   name it `.github/workflows/build.yml`, paste that file's contents and commit.
3. **Actions** tab → **Build APK** runs by itself (about 5 minutes; or **Run workflow**).
4. When it's green: open the run → **Artifacts** → download **MarqueeLens-apk** → unzip → `app-debug.apk`.

The build log's "Use your signing key" step says whether your key was used.

## 7. Install on the phone

1. Send `app-debug.apk` to the phone (Drive, email…) and open it. Allow installing from that app when asked.
2. Open **Marquee Lens**, enter your **invite code** → **Continue**.
3. Step 2 is the overlay (ratings over streaming apps): **Turn on** → Accessibility → *Installed apps* / *Downloaded apps* →
   **Marquee Lens ratings** → On.
   - **Android 13+, the switch is greyed out ("restricted setting"):** try it once, then phone **Settings → Apps → Marquee Lens →
     ⋮ → Allow restricted settings**, and switch it on again.
4. Choose **How ratings appear** (Configuration → Overlay): **Bubble** (tap a button), **Automatic** (no button) or
   **Shortcut** (Android's accessibility shortcut).

Installing from a computer avoids the restricted-settings step: enable USB debugging and run `adb install app-debug.apk`.

## 8. Install on a TV (NVIDIA Shield, Android TV, Google TV)

The same APK works on the TV.

1. Install **Send files to TV** from the Play Store on the phone and the TV; send the APK and install it on the TV.
   (Or from a computer: TV Settings → Device Preferences → About → press *Build* 7 times → Developer options →
   Network debugging, then `adb connect <TV-IP>:5555` and `adb install app-debug.apk`.)
2. TV **Settings → Device Preferences → Accessibility → Marquee Lens ratings → Enable**.
3. Open Marquee Lens (*See all apps*), enter the invite code. The **Google TV** app on your phone has a keyboard for this.
4. In a streaming app press **≡** on the remote: ratings appear; **≡** again to choose one, **OK** for details, **Back** to hide.
   Staying on a title for a second shows its rating in the corner. Hold **≡** for 3 seconds to report a problem.

**Netflix on a TV** draws its own screen and shares no titles, so it needs one extra step: Marquee Lens →
Configuration → Overlay → *Netflix on this TV* → **Set up** (choose Marquee Lens as the speech engine and turn on TalkBack;
the TV stays quiet). On a TV with LG's or Samsung's own system (not Android) none of this is possible; use the phone's camera.

## 9. The web page (optional)

`web/marquee-lens.html` is the same app (lists, camera, sharing) for any browser. Put it on a free host
(e.g. Netlify: **Add new site → Deploy manually** → drop the file, renamed `index.html`). Then:
- add that address to `ALLOWED_ORIGINS` on the Worker;
- to make shared list links from the Android app point to it, set `SHARE_BASE` in both HTML files to that address.

## 10. Inviting people

Add a `Name:code` pair to `INVITE_CODES`, deploy, and send them the APK (or the web address) and **their own** code.
Each person has separate lists, and the daily AI limit counts per person.

## 11. What's stored, and costs

- **On the server (D1):** each person's lists (until deleted) and the app's logs for **24 hours**
  (titles read and found, corrections, errors, problem reports — not what you type). Configuration → API →
  *Problems and logs* → **Clear logs** deletes them.
- **API keys** are only in the Worker's secrets.
- **Costs:** nothing on the free plans at personal scale (Cloudflare's free Worker plan allows 100,000 requests a day).

## 12. Troubleshooting

| Problem | Fix |
|---|---|
| "Invite code not recognized" | Check `INVITE_CODES` (`Name:code`, commas between), the exact code (case matters), and **Deploy** after editing |
| "List sync isn't set up on the server" | Step 3.3: the D1 binding must be named exactly `DB` |
| A new build won't install over the old one | Signing key missing or different (step 5). Uninstall once, then updates work |
| Overlay switch greyed out | Step 7.3, *Allow restricted settings* |
| No ratings in an app | Configuration → Overlay → make sure that app is ticked; then Advanced → **Last overlay scan** shows what the app exposed |
| Wrong film for a title | Open it → **Wrong title?** → pick the right one (remembered for that app) |
| Something odd happened | Configuration → API → *Problems and logs* → **Report a problem** right away, then **Share logs** |

## 13. Keeping it safe

- Never commit `marquee.keystore`, its password, or your API keys. (`.gitignore` already excludes the keystore, but the
  GitHub upload page ignores `.gitignore` — check what you drag in.)
- In a private repository on a personal GitHub account, every collaborator can change the build and could read your
  signing key through it. Only add people you fully trust; otherwise share a copy of the code instead.
- Use long invite codes, and remove codes you no longer need.
