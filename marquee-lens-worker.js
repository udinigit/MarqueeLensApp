// Marquee Lens · key-keeping server (Cloudflare Worker)
//
// Your API keys stay here as secrets; the app only ever sends an invite code.
// Secrets / variables (Worker → Settings → Variables and Secrets):
//   GEMINI_KEY        secret   Google Gemini API key (recognition, reception summaries)
//   OMDB_KEY          secret   OMDb key (titles, IMDb ratings)
//   TMDB_KEY          secret   TMDB API key or Read Access Token (optional: similar films, genres, where to watch)
//   INVITE_CODES      secret   who may use it, comma separated "Name:code", e.g.  Dana:k7p2x9, Avi:m4q8r1
//   ALLOWED_ORIGINS   text     your site address(es), comma separated, e.g. https://marquee-lens.netlify.app
//                              (use * only while testing)
//   DAILY_AI_LIMIT    text     AI requests per person per day (default 100)
// Optional D1 database binding named DB: keeps each person's lists, so all their devices share them (GET/PUT /sync),
// and their devices' logs for 24 hours (POST/GET/DELETE /logs), for fixing problems.
// Optional KV namespace binding named USAGE: counts each person's AI requests per day so the
// limit above is enforced. Without it, invite codes still work but there's no daily limit.

const OMDB = "https://www.omdbapi.com/";
const TMDB = "https://api.themoviedb.org/3";
const GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/";
const TMDB_PATHS = /^\/(find|discover|movie|tv|watch|genre|search)\//;
const DAY = 86400;

let tableReady = false;
const LOG_KEEP_MS = 24 * 3600_000;   // logs are deleted after 24 hours
async function ensureTable(db){
  if (tableReady) return;
  await db.prepare("CREATE TABLE IF NOT EXISTS userdata (user TEXT PRIMARY KEY, rev INTEGER NOT NULL, data TEXT NOT NULL, updated INTEGER NOT NULL)").run();
  await db.prepare("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, user TEXT NOT NULL, device TEXT, version TEXT, ts INTEGER NOT NULL, data TEXT NOT NULL)").run();
  await db.prepare("CREATE INDEX IF NOT EXISTS logs_user_ts ON logs (user, ts)").run();
  tableReady = true;
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const origin = req.headers.get("Origin") || "";
    const allowed = (env.ALLOWED_ORIGINS || "*").split(",").map(s => s.trim()).filter(Boolean);
    // No Origin = not a web page (the Android overlay); the invite code still decides. The Android app's built-in page is always allowed.
    const originOk = allowed.includes("*") || allowed.includes(origin) || origin === "" || origin === "https://appassets.androidplatform.net";
    const cors = {
      "Access-Control-Allow-Origin": originOk ? (origin || "*") : "null",
      "Access-Control-Allow-Headers": "Content-Type, X-Invite",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Max-Age": "86400",
      "Vary": "Origin"
    };
    const json = (obj, status = 200) => new Response(JSON.stringify(obj), { status, headers: { ...cors, "Content-Type": "application/json" } });
    const fail = (status, message, code = "SERVER") => json({ error: { code: status, status: code, message } }, status);

    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (!originOk) return fail(403, "This website isn't allowed to use this server.", "ORIGIN");

    // Who is this? (invite code → name)
    const users = new Map();
    for (const part of String(env.INVITE_CODES || "").split(",")) {
      const [name, code] = part.split(":").map(s => (s || "").trim());
      if (name && code) users.set(code, name);
    }
    const user = users.get(req.headers.get("X-Invite") || "");
    if (!user) return fail(401, "Invite code not recognized. Check it in Settings.", "INVITE");

    const limit = parseInt(env.DAILY_AI_LIMIT || "100", 10);
    const usageKey = `ai:${new Date().toISOString().slice(0, 10)}:${user}`;
    const usedToday = async () => env.USAGE ? parseInt(await env.USAGE.get(usageKey) || "0", 10) : null;

    try {
      // ---- who am I / what's available
      if (url.pathname === "/me") {
        return json({ name: user, limit: env.USAGE ? limit : null, used: await usedToday(),
          gemini: !!env.GEMINI_KEY, omdb: !!env.OMDB_KEY, tmdb: !!env.TMDB_KEY, sync: !!env.DB });
      }

      // ---- Logs from your devices, kept 24 hours: POST /logs {device, version, events}, GET /logs?hours=24, DELETE /logs
      if (url.pathname === "/logs") {
        if (!env.DB) return fail(501, "Logs aren't set up on the server (no DB database).", "NO_DB");
        await ensureTable(env.DB);
        const since = Date.now() - LOG_KEEP_MS;
        if (req.method === "POST") {
          const body = await req.text();
          if (body.length > 600_000) return fail(413, "Log batch too big");
          let d; try { d = JSON.parse(body); } catch { return fail(400, "Bad log data"); }
          const events = Array.isArray(d.events) ? d.events.slice(-1000) : [];
          if (!events.length) return json({ saved: 0 });
          await env.DB.prepare("INSERT INTO logs (user, device, version, ts, data) VALUES (?, ?, ?, ?, ?)")
            .bind(user, String(d.device || "?").slice(0, 20), String(d.version || "").slice(0, 20), Date.now(), JSON.stringify(events)).run();
          ctx.waitUntil(env.DB.prepare("DELETE FROM logs WHERE ts < ?").bind(since).run());   // older than 24 hours
          return json({ saved: events.length });
        }
        if (req.method === "GET") {
          const hours = Math.min(24, Math.max(1, parseInt(url.searchParams.get("hours") || "24", 10)));
          const r = await env.DB.prepare("SELECT device, version, ts, data FROM logs WHERE user = ? AND ts > ? ORDER BY ts")
            .bind(user, Math.max(since, Date.now() - hours * 3600_000)).all();
          const events = [];
          for (const row of r.results || []) for (const e of JSON.parse(row.data)) events.push({ ...e, dev: e.dev || row.device, ver: e.ver || row.version });
          events.sort((a, b) => (a.t || 0) - (b.t || 0));
          return json({ user, events });
        }
        if (req.method === "DELETE") {
          await env.DB.prepare("DELETE FROM logs WHERE user = ?").bind(user).run();
          return json({ deleted: true });
        }
        return fail(405, "Use GET, POST or DELETE");
      }

      // ---- Lists shared between your devices: GET /sync → {rev, data}; PUT /sync {rev, data} → {rev}
      // The app merges film by film; rev guards against two devices saving over each other (409 = merge and try again).
      if (url.pathname === "/sync") {
        if (!env.DB) return fail(501, "List sync isn't set up on the server (no DB database).", "NO_SYNC");
        await ensureTable(env.DB);
        const row = async () => await env.DB.prepare("SELECT rev, data FROM userdata WHERE user = ?").bind(user).first();
        if (req.method === "GET") {
          const r = await row();
          return json(r ? { rev: r.rev, data: JSON.parse(r.data) } : { rev: 0, data: null });
        }
        if (req.method === "PUT") {
          const body = await req.text();
          if (body.length > 1_900_000) return fail(413, "Your lists are too big to sync (about 2 MB at most).", "TOO_BIG");
          let d; try { d = JSON.parse(body); } catch { return fail(400, "Bad sync data"); }
          const base = parseInt(d.rev, 10) || 0, data = JSON.stringify(d.data ?? {}), now = Date.now();
          const res = base === 0
            ? await env.DB.prepare("INSERT OR IGNORE INTO userdata (user, rev, data, updated) VALUES (?, 1, ?, ?)").bind(user, data, now).run()
            : await env.DB.prepare("UPDATE userdata SET rev = rev + 1, data = ?, updated = ? WHERE user = ? AND rev = ?").bind(data, now, user, base).run();
          if (!res.meta?.changes) {   // someone else saved first: send back theirs to merge with
            const r = await row();
            return json({ conflict: true, rev: r?.rev || 0, data: r ? JSON.parse(r.data) : null }, 409);
          }
          return json({ rev: base + 1 });
        }
        return fail(405, "Use GET or PUT");
      }

      // ---- Gemini: POST /gemini/<model>:generateContent
      if (url.pathname.startsWith("/gemini/")) {
        if (req.method !== "POST") return fail(405, "Use POST.");
        if (!env.GEMINI_KEY) return fail(503, "AI recognition isn't set up on this server.");
        const model = decodeURIComponent(url.pathname.slice("/gemini/".length)).replace(/:generateContent$/, "");
        if (!/^gemini-[a-z0-9.\-]+$/i.test(model)) return fail(400, "Unknown model.");
        if (env.USAGE) {
          const used = await usedToday();
          if (used >= limit) return fail(429, `You've used today's ${limit} AI requests. They reset at 03:00 Israel time (midnight UTC).`, "DAILY_LIMIT");
          ctx.waitUntil(env.USAGE.put(usageKey, String(used + 1), { expirationTtl: 2 * DAY }));
        }
        const body = await req.text();
        if (body.length > 6_000_000) return fail(413, "Picture too large.");
        const r = await fetch(GEMINI + encodeURIComponent(model) + ":generateContent", {
          method: "POST", body,
          headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_KEY }
        });
        return new Response(r.body, { status: r.status, headers: { ...cors, "Content-Type": "application/json" } });
      }

      // ---- OMDb: GET /omdb?t=…&y=…  (cached for a day)
      if (url.pathname === "/omdb") {
        if (!env.OMDB_KEY) return fail(503, "Film data isn't set up on this server.");
        const p = new URLSearchParams(url.search); p.delete("apikey"); p.sort();
        return cached(ctx, `https://cache.marquee-lens/omdb?${p}`, () => {
          const up = new URLSearchParams(p); up.set("apikey", env.OMDB_KEY);
          return fetch(`${OMDB}?${up}`);
        }, cors, d => d && d.Response === "True");
      }

      // ---- TMDB: GET /tmdb/<path>?…  (cached for a day)
      if (url.pathname.startsWith("/tmdb/")) {
        if (!env.TMDB_KEY) return fail(503, "Similar films and where-to-watch aren't set up on this server.");
        const path = url.pathname.slice("/tmdb".length);
        if (!TMDB_PATHS.test(path)) return fail(400, "Not allowed.");
        const p = new URLSearchParams(url.search); p.delete("api_key"); p.sort();
        const bearer = env.TMDB_KEY.startsWith("eyJ");
        return cached(ctx, `https://cache.marquee-lens/tmdb${path}?${p}`, () => {
          const up = new URLSearchParams(p); if (!bearer) up.set("api_key", env.TMDB_KEY);
          return fetch(`${TMDB}${path}?${up}`, bearer ? { headers: { Authorization: "Bearer " + env.TMDB_KEY } } : {});
        }, cors, (d, r) => r.ok);
      }

      return fail(404, "Not found.");
    } catch (e) {
      return fail(502, "The server couldn't reach the service. Try again.");
    }
  }
};

// Serve from Cloudflare's cache when we can; store good answers for a day (keys never appear in cache keys)
async function cached(ctx, key, load, cors, good) {
  const cache = caches.default, req = new Request(key);
  const hit = await cache.match(req);
  if (hit) return new Response(hit.body, { status: hit.status, headers: { ...cors, "Content-Type": "application/json", "X-Cache": "HIT" } });
  const r = await load();
  const text = await r.text();
  let data = null; try { data = JSON.parse(text); } catch {}
  if (r.ok && good(data, r)) {
    ctx.waitUntil(cache.put(req, new Response(text, { headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${DAY}` } })));
  }
  return new Response(text, { status: r.status, headers: { ...cors, "Content-Type": "application/json", "X-Cache": "MISS" } });
}
