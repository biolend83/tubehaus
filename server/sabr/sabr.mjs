// SABR for the TubeHaus server (run by deno; started and restarted by tubehaus_server.py).
//
// YouTube's web player gets its video as SABR: no plain stream address, but a POST conversation in
// which the player says where it is and the server sends the next pieces. A web proof-of-origin token
// rides along; without one the answers stop after about a minute.
//
// This helper speaks SABR for the server, so the players (web, apps, TVs) need not:
//  - /prepare?v=<id>&hl=&gl=   the watch page's player answer, each format's address pointing at
//                              /companion/sabr on this server (the server's video answer is built from it)
//  - /track?v=<id>&itag=<n>&xtags=<..>   one format as an ordinary file with byte ranges. Every SABR piece
//                              says where in YouTube's file it belongs (its byte offset), so the pieces are
//                              written to their place in a file on disk and ranges are answered from there.
//                              A range not there yet is fetched: SABR is asked to start at the time that byte
//                              plays (from the pieces seen, else the bitrate), so a jump far ahead does not
//                              wait for everything before it.
//
// The token: YouTube's own BotGuard challenge from its home page, run in JSDOM, its integrity token from
// YouTube's /api/jnn, minted for the video id. No account, no cookies (as googlevideo's downloader example
// does it). About half the minters JSDOM gives are turned down; YouTube says so in its first answer
// (protection status 2 instead of 1), so a turned-down one is replaced at once and a good one kept.
//
// The npm libraries (jsdom, bgutils-js, googlevideo: MIT) are bundled in vendor.mjs, so nothing is
// downloaded when this runs (see build-vendor.mjs). yt-dlp's EJS solver (server/ejs, Unlicense) solves n.
import { JSDOM, BotGuardClient, WebPoMinter, getHeaders, buildURL, USER_AGENT, parseLooseJSON, SabrStream, buildSabrFormat } from "./vendor.mjs";
import vm from "node:vm";

const PORT = +(Deno.env.get("HT_SABR_PORT") || 3033);
const DIR = (Deno.env.get("HT_SABR_DIR") || "/tmp/tubehaus-sabr").replace(/\/+$/, "");
const EJS = new URL("../ejs/", import.meta.url);
const DISK_MAX = +(Deno.env.get("HT_SABR_DISK_MB") || 3000) * 1e6;
const AHEAD = 24e6;                 // read at most this far past what a player has asked for (it may never want the rest)
const NEAR = 6e6;                   // a byte this close ahead of where a fetch is will come from it: no new fetch
const IDLE_MS = 20 * 60e3;          // a format not asked for in 20 minutes is deleted
const SESSION_MS = 4 * 3600e3;      // the watch page's answer is used for 4 hours (YouTube gives 6)
const UA = USER_AGENT;
const log = (...a) => console.log("[sabr]", ...a);
await Deno.mkdir(DIR, { recursive: true });
for await (const e of Deno.readDir(DIR)) await Deno.remove(DIR + "/" + e.name).catch(() => {});

// ---------------------------------------------------------------- the token
// One minter (JSDOM + BotGuard) for many tokens, made again before its integrity token runs out or when
// YouTube turned its token down. `good` once YouTube took one of its tokens.
let minter = null, minting = null;
async function makeMinter() {
  const dom = new JSDOM('<!DOCTYPE html><html lang="en"><head><title></title></head><body></body></html>',
    { url: "https://www.youtube.com", referrer: "https://www.youtube.com/", userAgent: UA });
  const html = await (await fetch("https://www.youtube.com", { headers: { accept: "*/*", "accept-language": "en-US,en;q=0.7", "user-agent": UA } })).text();
  const cfg = html.match(/ytcfg\.set\(({.+?})\);/s)?.[1];
  if (!cfg) throw new Error("no ytcfg on YouTube's home page");
  dom.window.yt = { config_: JSON.parse(cfg) };
  // BotGuard draws on a canvas; JSDOM has none, so a 2D context that draws nothing
  const ctx2d = { fillRect() {}, clearRect() {}, getImageData: (_x, _y, w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }), putImageData() {},
    createImageData: () => [], setTransform() {}, drawImage() {}, save() {}, fillText() {}, restore() {}, beginPath() {}, moveTo() {}, lineTo() {},
    closePath() {}, stroke() {}, translate() {}, scale() {}, rotate() {}, arc() {}, fill() {}, measureText: () => ({ width: 0 }), transform() {}, rect() {}, clip() {} };
  dom.window.HTMLCanvasElement.prototype.getContext = (id) => (id === "2d" ? ctx2d : null);
  Object.assign(globalThis, { yt: dom.window.yt, window: dom.window, document: dom.window.document, location: dom.window.location, origin: dom.window.origin });
  if (!("navigator" in globalThis)) Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
  const at = html.match(/window\.ytAtN\(\s*({[\s\S]*?})\s*\)/);
  if (!at) throw new Error("no BotGuard challenge on YouTube's home page");
  const ch = parseLooseJSON(at[1]).R.bgChallenge;
  const js = await (await fetch("https:" + ch.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue)).text();
  new Function(js)();
  const bg = await BotGuardClient.create({ program: ch.program, globalName: ch.globalName, globalObject: globalThis });
  const webPoSignalOutput = [];
  const resp = await bg.snapshot({ webPoSignalOutput });
  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = await (await fetch(buildURL("GenerateIT", true),
    { method: "POST", headers: getHeaders(), body: JSON.stringify(["O43z0dpjhgX20SCx4KAo", resp]) })).json();
  if (!integrityToken) throw new Error("no integrity token");
  const m = await WebPoMinter.create({ integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken }, webPoSignalOutput);
  return { m, at: Date.now(), ttl: (estimatedTtlSecs || 3600) * 1000, good: false };
}
async function getMinter() {
  if (minter && Date.now() - minter.at < minter.ttl * 0.8) return minter;
  if (!minting) {
    const t = Date.now();
    minting = makeMinter().then((m) => { minter = m; log("token minter ready", Date.now() - t, "ms"); return m; })
      .finally(() => { minting = null; });
  }
  return minting;
}
function turnedDown(m) { if (minter === m) minter = null; }

// ---------------------------------------------------------------- n (yt-dlp's EJS solver)
const players = new Map();   // player script path -> preprocessed script
let solverCtx = null;
async function solverReady() {
  if (solverCtx) return solverCtx;
  const lib = await Deno.readTextFile(new URL("yt.solver.lib.min.js", EJS)), core = await Deno.readTextFile(new URL("yt.solver.core.min.js", EJS));
  solverCtx = vm.createContext({});
  vm.runInContext(lib + ";var meriyah=lib.meriyah,astring=lib.astring;" + core, solverCtx);
  return solverCtx;
}
async function solveN(url, jsUrl) {
  try { return await solveWith(url, jsUrl); } catch (e) {
    // the page may name a variant of the player script the solver does not read (e.g. _tcl): the main one of the same player
    const id = (jsUrl || "").match(/\/s\/player\/(\w+)\//)?.[1];
    if (!id) throw e;
    return await solveWith(url, `/s/player/${id}/player_ias.vflset/en_US/base.js`);
  }
}
async function solveWith(url, jsUrl) {
  const u = new URL(url), n = u.searchParams.get("n");
  if (!n) return url;
  const ctx = await solverReady();
  let pre = players.get(jsUrl);
  if (!pre) {
    const js = await (await fetch("https://www.youtube.com" + jsUrl, { headers: { "User-Agent": UA } })).text();
    ctx.input = { type: "player", player: js, requests: [], output_preprocessed: true };
    pre = vm.runInContext("jsc(input)", ctx).preprocessed_player;
    players.set(jsUrl, pre);
    if (players.size > 4) players.delete(players.keys().next().value);
  }
  ctx.input = { type: "preprocessed", preprocessed_player: pre, requests: [{ type: "n", challenges: [n] }] };
  const ans = vm.runInContext("jsc(input)", ctx).responses?.[0]?.data?.[n];
  if (!ans) throw new Error("n not solved");
  u.searchParams.set("n", ans);
  return u.toString();
}

// ---------------------------------------------------------------- the watch page's answer
const sessions = new Map();   // video id -> {at, d, sabrUrl, ustreamer, ver, formats}
const preparing = new Map();
const fkey = (f) => String(f.itag) + ":" + (f.xtags || "");
// a format by itag and xtags; with no xtags, the original audio track (YouTube lists dubbed ones under the same itag)
function pick(s, itag, xtags) {
  const exact = s.formats.get(itag + ":" + (xtags || ""));
  if (exact) return exact;
  const same = [...s.formats.values()].filter((x) => String(x.itag) === String(itag));
  const original = (x) => { try { return /original/.test(atob(decodeURIComponent(x.xtags).replace(/-/g, "+").replace(/_/g, "/"))); } catch { return false; } };
  return same.find((x) => x.audioTrack?.audioIsDefault) || same.find((x) => !x.xtags || original(x)) || same[0];
}
async function prepare(vid, hl = "en", gl = "US") {
  const s = sessions.get(vid);
  if (s && Date.now() - s.at < SESSION_MS) return s;
  if (preparing.has(vid)) return preparing.get(vid);
  const p = (async () => {
    const t = Date.now();
    const r = await fetch(`https://www.youtube.com/watch?v=${vid}&hl=${encodeURIComponent(hl)}&gl=${encodeURIComponent(gl)}&has_verified=1&bpctr=9999999999`,
      { headers: { "User-Agent": UA, "Accept-Language": hl + ",en;q=0.8" } });
    const html = await r.text();
    const cfg = (k) => (html.match(new RegExp(`"${k}":"([^"]+)"`)) || [])[1];
    const m = html.match(/ytInitialPlayerResponse\s*=\s*(\{.+?\});(?:var|<\/script)/s);
    if (!m) throw Object.assign(new Error("no player answer on the watch page"), { code: 502 });
    const d = JSON.parse(m[1]);
    const ps = d.playabilityStatus || {};
    const sd = d.streamingData || {};
    const ustreamer = d.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig;
    if (ps.status !== "OK" || !sd.serverAbrStreamingUrl || !ustreamer) return { at: Date.now(), d, failed: true };
    const sabrUrl = await solveN(sd.serverAbrStreamingUrl, cfg("PLAYER_JS_URL") || (html.match(/"jsUrl":"([^"]+)"/) || [])[1]);
    const out = { at: Date.now(), d, sabrUrl, ustreamer, ver: cfg("INNERTUBE_CLIENT_VERSION"), formats: new Map() };
    for (const f of sd.adaptiveFormats || []) out.formats.set(fkey(f), f);
    log("video", vid, "SABR ready,", out.formats.size, "formats", Date.now() - t, "ms");
    return out;
  })();
  preparing.set(vid, p);
  try {
    const s2 = await p;
    sessions.set(vid, s2);
    return s2;
  } finally { preparing.delete(vid); }
}
async function answer(vid, s) {
  // the player answer with each format's address pointing at the server's SABR path
  const d = structuredClone(s.d);
  for (const f of d.streamingData?.adaptiveFormats || []) {
    const q = new URLSearchParams({ v: vid, itag: String(f.itag) });
    if (f.xtags) q.set("xtags", f.xtags);
    f.url = "/companion/sabr?" + q;
  }
  if (d.streamingData) delete d.streamingData.formats;   // the muxed ones have no SABR path
  return d;
}

// ---------------------------------------------------------------- a format: pieces written to their place on disk
// t.ranges: the byte ranges on disk, [start, end) sorted and merged. t.run: the SABR fetch going on (at most one).
// t.points: [byte, ms] of the pieces seen, to turn a byte into the time SABR is asked to start at.
const tracks = new Map();   // "<vid>|<itag>:<xtags>" -> track
function wake(t) { const w = t.waiters; t.waiters = []; for (const f of w) f(); }
function onDisk(t, n) { for (const [a, b] of t.ranges) { if (n < a) return 0; if (n < b) return b; } return 0; }   // end of the range holding n
function addRange(t, a, b) {
  const r = [...t.ranges, [a, b]].sort((x, y) => x[0] - y[0]), out = [];
  for (const x of r) { const l = out[out.length - 1]; if (l && x[0] <= l[1]) l[1] = Math.max(l[1], x[1]); else out.push([x[0], x[1]]); }
  t.ranges = out;
  t.done = t.length > 0 && out.length === 1 && out[0][0] === 0 && out[0][1] >= t.length;
}
function timeOf(t, n) {
  // the start time of the piece holding byte n: from the pieces seen around it, else the average bitrate
  if (n <= t.headEnd) return 0;
  let lo = null, hi = null;
  for (const p of t.points) { if (p[0] <= n && (!lo || p[0] > lo[0])) lo = p; if (p[0] > n && (!hi || p[0] < hi[0])) hi = p; }
  const dur = +t.f.approxDurationMs || 0, len = t.length || 1;
  lo ??= [t.headEnd, 0];
  hi ??= [len, dur];
  const ms = lo[1] + (hi[1] - lo[1]) * ((n - lo[0]) / Math.max(1, hi[0] - lo[0]));
  return Math.max(0, Math.floor(ms - 2000));
}
async function fetchFrom(t, n, back = 0) {
  // a SABR fetch starting at the piece holding byte n (back: seconds earlier, after a start that came too late)
  const s = await prepare(t.vid);
  if (s.failed) throw new Error("video not playable over SABR");
  const video = (t.f.mimeType || "").startsWith("video/");
  const formats = [...s.formats.values()].map(buildSabrFormat);
  const me = buildSabrFormat(t.f);
  const audioOf = video ? formats.filter((x) => x.hasAudio).sort((a, b) => a.bitrate - b.bitrate)[0] : me;
  const m = await getMinter();
  const startMs = Math.max(0, timeOf(t, n) - back * 1000);
  const stream = new SabrStream({
    videoId: t.vid, formats, serverAbrStreamingUrl: s.sabrUrl, videoPlaybackUstreamerConfig: s.ustreamer,
    clientInfo: { clientName: 1, clientVersion: s.ver, osName: "Windows", osVersion: "10.0" },
    poToken: await m.m.mintAsWebsafeString(t.vid),
    callbacks: { onMintPoToken: async () => (await getMinter()).m.mint(t.vid) },
    fetchFunction: (u, o = {}) => fetch(u, { ...o, headers: { ...(o.headers || {}), "User-Agent": UA, Origin: "https://www.youtube.com", Referer: "https://www.youtube.com/" } }),
  });
  const run = { stream, from: n, head: n, startMs, bad: false, over: false };
  t.run = run;
  // YouTube's first word on the token: 1 = taken; anything else = turned down (a new minter for the next fetch)
  let first = true;
  stream.on("streamProtectionStatusUpdate", (p) => {
    if (first) { first = false; if (p.status === 1) m.good = true; }
    if (p.status !== 1 && !m.good) { run.bad = true; turnedDown(m); stream.abort().catch(() => {}); }
    else if (p.status === 3) { run.bad = true; m.good = false; turnedDown(m); stream.abort().catch(() => {}); }
  });
  stream.on("error", () => {});
  // each piece's header (its place in the file) in the order the pieces reach the stream
  const kind = video ? "video" : "audio";
  const heads = [];
  const sb = stream.segmentBuffer, fin = sb.finalizeSegment.bind(sb);
  let cur = null;
  sb.finalizeSegment = (id) => { cur = sb.pendingSegments.get(id)?.mediaHeader || null; try { return fin(id); } finally { cur = null; } };
  const out = stream.trackCollection[kind].output, enq = out.controller.enqueue.bind(out.controller);
  out.controller.enqueue = (chunk) => { heads.push(cur); enq(chunk); };
  const { videoStream, audioStream } = stream.start({
    videoFormat: video ? me : undefined, audioFormat: audioOf, startTimeMs: startMs,
    enabledTrackTypes: video ? 2 : 1, isPostLiveDvr: false,
    stallDetectionMs: 1e12,   // waiting for a player that is not reading on is not a stall
  });
  const mine = video ? videoStream : audioStream, other = video ? audioStream : videoStream;
  // the other kind is not asked for, but its stream is read so nothing waits on it
  (async () => { const r = other.getReader(); while (!(await r.read()).done); })().catch(() => {});
  const fh = await Deno.open(t.path, { write: true, create: true });
  const rd = mine.getReader();
  let media = 0;
  try {
    for (;;) {
      // far enough ahead of what the players asked for: wait until one reads on (SABR waits with us)
      while (run.head > t.want + AHEAD && t.run === run && !t.dead) await new Promise((r) => { t.wantWaiters.push(r); setTimeout(r, 10e3); });
      if (t.run !== run || t.dead) break;
      const { done, value } = await rd.read();
      if (done) break;
      const h = heads.shift();
      const b = value instanceof Uint8Array ? value : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
      const at = h?.isInitializationSegment ? 0 : +(h?.segmentByteRangeStart ?? -1);
      if (at < 0) continue;
      if (h.isInitializationSegment) t.headEnd = Math.max(t.headEnd, b.length);
      else {
        if (h.startMs != null) t.points.push([at, +h.startMs]);
        if (media++ === 0 && at > n) {
          // started after the byte wanted (the time was guessed late): again, earlier
          run.over = true;
          break;
        }
      }
      await fh.seek(at, Deno.SeekMode.Start);
      let w = 0;
      while (w < b.length) w += await fh.write(b.subarray(w));
      addRange(t, at, at + b.length);
      if (!h.isInitializationSegment) run.head = at + b.length;
      wake(t);
      if (t.done) break;
      // caught up with bytes already on disk for a while: this fetch has nothing new to give
      if (!h.isInitializationSegment && onDisk(t, run.head) - run.head > NEAR) break;
    }
  } finally {
    fh.close();
    stream.abort().catch(() => {});
    if (t.run === run) t.run = null;
    wake(t);
  }
  return run;
}
function ensure(t, n) {
  // byte n is wanted: a fetch that will bring it, unless one is on its way
  if (onDisk(t, n) || t.failed || t.dead) return;
  const r = t.run;
  // (a fetch started a little earlier than its byte is still on its way there while it catches up)
  if (r && r.from <= n && n - Math.max(r.head, r.from) < NEAR) return;
  if (r) t.run = null;   // that fetch ends at its next piece; this one takes over
  const tries = (back = 0, left = 4) => fetchFrom(t, n, back).then((run) => {
    t.fails = run.bad ? t.fails + 1 : 0;
    if (run.over && left > 0) return tries(back ? back * 3 : 20, left - 1);
    if (run.bad && t.fails < 6 && !onDisk(t, n)) return tries(back, left);
    if (t.fails >= 6) { t.failed = true; log("format", t.vid, t.key, "FAIL: tokens turned down"); }
  }).catch((e) => {
    t.fails++;
    log("format", t.vid, t.key, "fetch FAIL", String(e?.message || e).slice(0, 160));
    if (t.fails >= 6) t.failed = true;
    if (t.fails === 2) sessions.delete(t.vid);   // a fresh watch page answer for the next try
    wake(t);
  });
  tries();
}
function track(vid, f) {
  const key = fkey(f), id = vid + "|" + key;
  let t = tracks.get(id);
  if (t) { t.used = Date.now(); return t; }
  t = { id, vid, key, f, length: +f.contentLength || 0, ranges: [], points: [], headEnd: 0, run: null, want: 0, wantWaiters: [], waiters: [],
    fails: 0, done: false, failed: false, dead: false, used: Date.now(), path: `${DIR}/${vid}-${key.replace(/[^\w]/g, "_").slice(0, 60)}` };
  tracks.set(id, t);
  return t;
}
function want(t, n) {
  t.want = n;
  const w = t.wantWaiters; t.wantWaiters = []; for (const f of w) f();
}
async function until(t, n) {
  // wait for byte n to be on disk; a minute and a half with no piece arriving is a failure
  let quiet = Date.now();
  while (!onDisk(t, n)) {
    if (t.failed || t.dead) throw new Error("format failed");
    ensure(t, n);
    const before = JSON.stringify(t.ranges);
    await new Promise((r) => { t.waiters.push(r); setTimeout(r, 15e3); });
    if (JSON.stringify(t.ranges) !== before) quiet = Date.now();
    else if (Date.now() - quiet > 90e3) throw new Error("stalled");
  }
}
async function sweep() {
  const now = Date.now();
  const size = (t) => t.ranges.reduce((a, [x, y]) => a + y - x, 0);
  let total = 0;
  const all = [...tracks.values()].sort((a, b) => a.used - b.used);
  for (const t of all) total += size(t);
  for (const t of all) {
    if (t.waiters.length === 0 && (now - t.used > IDLE_MS || total > DISK_MAX)) {
      total -= size(t);
      tracks.delete(t.id);
      t.dead = true;
      t.run = null;
      wake(t);
      want(t, Infinity);
      await Deno.remove(t.path).catch(() => {});
    }
  }
  for (const [k, s] of sessions) if (now - s.at > SESSION_MS) sessions.delete(k);
}
setInterval(() => sweep().catch(() => {}), 60e3);

// a range of a format, fetched where it is missing
async function serveTrack(req, url) {
  const vid = url.searchParams.get("v") || "", itag = url.searchParams.get("itag") || "", xtags = url.searchParams.get("xtags") || "";
  if (!/^[\w-]{11}$/.test(vid) || !/^\d+$/.test(itag)) return new Response("bad request", { status: 400 });
  const s = await prepare(vid);
  if (s.failed) return new Response("not playable", { status: 404 });
  const f = pick(s, itag, xtags);
  if (!f) return new Response("no such format", { status: 404 });
  const t = track(vid, f), length = t.length;
  const m = (req.headers.get("range") || "").match(/bytes=(\d*)-(\d*)/);
  const start = m && m[1] ? +m[1] : 0;
  let end = m && m[2] ? +m[2] : (length ? length - 1 : Infinity);
  if (length && end >= length) end = length - 1;
  if (length && start >= length) return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${length}` } });
  const mime = (f.mimeType || "application/octet-stream").split(";")[0];
  let pos = start, fh = null;
  want(t, start);
  try { await until(t, start); } catch (e) { return new Response(String(e.message), { status: 502 }); }
  const body = new ReadableStream({
    async pull(c) {
      try {
        if (pos > end) { c.close(); fh?.close(); return; }
        t.used = Date.now();
        want(t, pos);
        await until(t, pos);
        fh ??= await Deno.open(t.path, { read: true });
        const n = Math.min(end + 1, onDisk(t, pos)) - pos;
        const buf = new Uint8Array(Math.min(n, 256 * 1024));
        await fh.seek(pos, Deno.SeekMode.Start);
        const got = await fh.read(buf);
        if (!got) return;
        pos += got;
        c.enqueue(buf.subarray(0, got));
      } catch (e) { fh?.close(); c.error(e); }
    },
    cancel() { fh?.close(); },
  });
  const h = { "Content-Type": mime, "Accept-Ranges": "bytes", "Cache-Control": "private, max-age=3600" };
  if (length) {
    h["Content-Length"] = String(end - start + 1);
    if (m) h["Content-Range"] = `bytes ${start}-${end}/${length}`;
  }
  return new Response(body, { status: m && length ? 206 : 200, headers: h });
}

Deno.serve({ hostname: "127.0.0.1", port: PORT, onListen: () => log("listening on 127.0.0.1:" + PORT) }, async (req) => {
  const url = new URL(req.url);
  try {
    if (url.pathname === "/health") return Response.json({ ok: true, tracks: tracks.size, sessions: sessions.size, minter: !!minter, good: !!minter?.good });
    if (url.pathname === "/prepare") {
      const vid = url.searchParams.get("v") || "";
      if (!/^[\w-]{11}$/.test(vid)) return new Response("bad id", { status: 400 });
      const s = await prepare(vid, url.searchParams.get("hl") || "en", url.searchParams.get("gl") || "US");
      if (s.failed) return Response.json({ playabilityStatus: s.d.playabilityStatus || { status: "ERROR" } });
      return Response.json(await answer(vid, s));
    }
    if (url.pathname === "/track") return await serveTrack(req, url);
    return new Response("not found", { status: 404 });
  } catch (e) {
    log("error", url.pathname, String(e?.message || e).slice(0, 200));
    return new Response(String(e?.message || e), { status: e?.code || 500 });
  }
});
