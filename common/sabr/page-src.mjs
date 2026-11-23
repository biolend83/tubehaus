// TubeHaus SABR in a browser page whose origin is https://www.youtube.com (a hidden WebView on Android, Edge or
// Chrome on a computer, WKWebView on an iPad): the page's own fetch talks to YouTube and its video servers as
// YouTube's web player does, and BotGuard runs in a real browser engine. Bundled into common/sabr/sabr-page.js
// with googlevideo and bgutils-js (MIT); yt-dlp's EJS solver (common/ejs) is loaded into the same page first.
//
// The app calls window.__thSabr.*; pieces go back through the host's bridge function `__thHost(json)`:
//   {t:"piece", run, at, init, ms, data(base64)}   one SABR piece and its place in YouTube's file
//   {t:"end", run, bad, error}                      a fetch is over (bad: YouTube turned its token down)
import { BotGuardClient } from "bgutils-js/botguard";
import { WebPoMinter } from "bgutils-js/webpo";
import { getHeaders, buildURL, parseLooseJSON } from "bgutils-js/utils";
import { SabrStream } from "googlevideo/sabr-stream";
import { buildSabrFormat } from "googlevideo/utils";

const send = (o) => { try { window.__thHost(JSON.stringify(o)); } catch (e) { /* host gone */ } };
const sessions = new Map();     // video id -> {at, d, sabrUrl, ustreamer, ver}
const runs = new Map();         // run id -> {stream, allow, gate}
const allows = new Map(), stopped = new Set();   // what the app said before a run had started
let minter = null, minting = null;

async function makeMinter() {
  const html = await (await fetch("/", { credentials: "omit" })).text();
  // BotGuard's program reads the page's config (EVENT_ID): YouTube's home page config, as on YouTube's own pages
  const cfg = html.match(/ytcfg\.set\(({.+?})\);/s)?.[1];
  if (cfg) { window.yt = window.yt || {}; window.yt.config_ = Object.assign(window.yt.config_ || {}, JSON.parse(cfg)); }
  const at = html.match(/window\.ytAtN\(\s*({[\s\S]*?})\s*\)/);
  if (!at) throw new Error("no BotGuard challenge on YouTube's home page");
  const ch = parseLooseJSON(at[1]).R.bgChallenge;
  // the interpreter as a <script> (as YouTube's page loads it: a fetch of it is refused, it has no CORS headers)
  if (!window[ch.globalName]) await new Promise((ok, no) => {
    const el = document.createElement("script");
    el.src = "https:" + ch.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
    el.onload = ok; el.onerror = () => no(new Error("BotGuard script not loaded"));
    document.head.appendChild(el);
  });
  const bg = await BotGuardClient.create({ program: ch.program, globalName: ch.globalName, globalObject: window });
  const webPoSignalOutput = [];
  const resp = await bg.snapshot({ webPoSignalOutput });
  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = await (await fetch(buildURL("GenerateIT", true),
    { method: "POST", headers: getHeaders(), body: JSON.stringify(["O43z0dpjhgX20SCx4KAo", resp]), credentials: "omit" })).json();
  if (!integrityToken) throw new Error("no integrity token");
  const m = await WebPoMinter.create({ integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken }, webPoSignalOutput);
  return { m, at: Date.now(), ttl: (estimatedTtlSecs || 3600) * 1000, good: false };
}
async function getMinter() {
  if (minter && Date.now() - minter.at < minter.ttl * 0.8) return minter;
  minting ??= makeMinter().then((m) => (minter = m)).finally(() => { minting = null; });
  return minting;
}
function turnedDown(m) { if (minter === m) minter = null; }

// n of the streaming address, by yt-dlp's solver in a Web Worker: it runs YouTube's player script, which in the
// page itself would act on the page (it navigates away). The app hands its code in with setSolver().
let solverSrc = null, worker = null, wid = 0;
const pending = new Map();
function jsc(input) {
  if (!worker) {
    if (!solverSrc) throw new Error("no solver");
    const src = solverSrc + "\n;onmessage=(e)=>{let r;try{r={id:e.data.id,r:jsc(e.data.input)}}catch(x){r={id:e.data.id,err:String(x)}}postMessage(r)};";
    worker = new Worker(URL.createObjectURL(new Blob([src], { type: "text/javascript" })));
    worker.onmessage = (e) => { const p = pending.get(e.data.id); pending.delete(e.data.id); if (p) e.data.err ? p[1](new Error(e.data.err)) : p[0](e.data.r); };
  }
  return new Promise((ok, no) => { const id = ++wid; pending.set(id, [ok, no]); worker.postMessage({ id, input }); });
}
const players = new Map(), scripts = new Map();
function playerJs(jsUrl) {
  let p = scripts.get(jsUrl);
  if (!p) {
    p = fetch(jsUrl, { credentials: "omit" }).then((r) => { if (!r.ok) throw new Error("player script " + r.status); return r.text(); });
    p.catch(() => scripts.delete(jsUrl));
    scripts.set(jsUrl, p);
    if (scripts.size > 3) scripts.delete(scripts.keys().next().value);
  }
  return p;
}
async function solveWith(url, jsUrl) {
  const u = new URL(url), n = u.searchParams.get("n");
  if (!n) return url;
  let pre = players.get(jsUrl);
  if (!pre) {
    const js = await playerJs(jsUrl);
    pre = (await jsc({ type: "player", player: js, requests: [], output_preprocessed: true })).preprocessed_player;
    players.set(jsUrl, pre);
    if (players.size > 3) players.delete(players.keys().next().value);
  }
  const ans = (await jsc({ type: "preprocessed", preprocessed_player: pre, requests: [{ type: "n", challenges: [n] }] })).responses?.[0]?.data?.[n];
  if (!ans) throw new Error("n not solved");
  u.searchParams.set("n", ans);
  return u.toString();
}
async function solveN(url, jsUrl) {
  try { return await solveWith(url, jsUrl); } catch (e) {
    const id = (jsUrl || "").match(/\/s\/player\/(\w+)\//)?.[1];
    if (!id) throw e;
    return await solveWith(url, `/s/player/${id}/player_ias.vflset/en_US/base.js`);
  }
}

async function session(vid, hl, gl) {
  const s = sessions.get(vid);
  if (s && Date.now() - s.at < 4 * 3600e3) return s;
  const html = await (await fetch(`/watch?v=${vid}&hl=${encodeURIComponent(hl || "en")}&gl=${encodeURIComponent(gl || "US")}&has_verified=1&bpctr=9999999999`, { credentials: "omit" })).text();
  const cfg = (k) => (html.match(new RegExp(`"${k}":"([^"]+)"`)) || [])[1];
  const m = html.match(/ytInitialPlayerResponse\s*=\s*(\{.+?\});(?:var|<\/script)/s);
  if (!m) throw new Error("no player answer on the watch page");
  const d = JSON.parse(m[1]);
  const sd = d.streamingData || {};
  const ustreamer = d.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig;
  const out = { at: Date.now(), d, ver: cfg("INNERTUBE_CLIENT_VERSION") };
  if (d.playabilityStatus?.status === "OK" && sd.serverAbrStreamingUrl && ustreamer) {
    out.sabrUrl = await solveN(sd.serverAbrStreamingUrl, cfg("PLAYER_JS_URL") || (html.match(/"jsUrl":"([^"]+)"/) || [])[1]);
    out.ustreamer = ustreamer;
  }
  sessions.set(vid, out);
  if (sessions.size > 30) sessions.delete(sessions.keys().next().value);
  return out;
}

// the system the web player says it runs on, as YouTube's own does: from the browser's user agent
function osOf(ua) {
  let m;
  if ((m = ua.match(/Windows NT ([\d.]+)/))) return { osName: "Windows", osVersion: m[1] };
  if ((m = ua.match(/Mac OS X ([\d_]+)/))) return { osName: "Macintosh", osVersion: m[1].replace(/_/g, ".") };
  if ((m = ua.match(/Android ([\d.]+)/))) return { osName: "Android", osVersion: m[1] };
  return { osName: "X11", osVersion: "" };
}
function sendPiece(o, u8) {
  if (window.__thBin) {
    try {
      const head = new TextEncoder().encode(JSON.stringify(o));
      const buf = new Uint8Array(4 + head.length + u8.length);
      new DataView(buf.buffer).setUint32(0, head.length);
      buf.set(head, 4); buf.set(u8, 4 + head.length);
      window.__thBin.postMessage(buf.buffer);
      return;
    } catch (e) { window.__thBin = null; }
  }
  send({ ...o, data: b64(u8) });
}
const b64 = (u8) => { let s = ""; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); };

window.__thSabr = {
  /** the watch page's player answer (JSON text), with its caption addresses already carrying a token */
  async prepare(vid, hl, gl) {
    const s = await session(vid, hl, gl);
    const d = structuredClone(s.d);
    if (!s.sabrUrl && d.playabilityStatus?.status === "OK") d.playabilityStatus = { status: "UNPLAYABLE", reason: "No SABR address" };
    getMinter().catch(() => {});
    return JSON.stringify(d);
  },
  /** a caption file whose address wants a token: its text, or "" */
  async caption(vid, url) {
    const ver = sessions.get(vid)?.ver || "2.20261119.01.00";
    for (let i = 0; i < 4; i++) {
      const m = await getMinter();
      const pot = await m.m.mintAsWebsafeString(vid);
      const r = await fetch(url + "&potc=1&pot=" + encodeURIComponent(pot) + "&c=WEB&cver=" + encodeURIComponent(ver), { credentials: "omit" });
      const t = await r.text();
      if (r.ok && t.length > 60) { m.good = true; return t; }
      if (!r.ok) return "";
      turnedDown(m);
    }
    return "";
  },
  /** starts fetch [run]: format [index] of the video's adaptiveFormats from [startMs]; pieces go to the host */
  start(run, vid, index, startMs) {
    let r = null;
    (async () => {
      const s = await session(vid);
      if (!s.sabrUrl) throw new Error("no SABR address");
      const raw = s.d.streamingData.adaptiveFormats;
      const f = raw[index];
      if (!f) throw new Error("no such format");
      const video = (f.mimeType || "").startsWith("video/");
      const formats = raw.map(buildSabrFormat), me = formats[index];
      const audioOf = video ? formats.filter((x) => x.hasAudio).sort((a, b) => a.bitrate - b.bitrate)[0] : me;
      const m = await getMinter();
      const stream = new SabrStream({
        videoId: vid, formats, serverAbrStreamingUrl: s.sabrUrl, videoPlaybackUstreamerConfig: s.ustreamer,
        clientInfo: { clientName: 1, clientVersion: s.ver, ...osOf(navigator.userAgent) },
        poToken: await m.m.mintAsWebsafeString(vid),
        callbacks: { onMintPoToken: async () => (await getMinter()).m.mint(vid) },
        // as YouTube's own player sends it: a plain-text body (no CORS preflight), no headers a page may not set
        fetchFunction: (u, o = {}) => fetch(u, { ...o, headers: { "content-type": "text/plain;charset=UTF-8", accept: "application/vnd.yt-ump" }, credentials: "omit" }),
      });
      r = { stream, allow: allows.get(run) ?? Infinity, gate: null, bad: false };
      allows.delete(run);
      if (stopped.delete(run)) { stream.abort().catch(() => {}); return; }
      runs.set(run, r);
      let first = true, refused = 0;
      stream.on("streamProtectionStatusUpdate", (p) => {
        send({ t: "log", run, msg: "protection " + p.status });
        if (first) { first = false; if (p.status === 1) m.good = true; }
        if (p.status === 1) m.good = true;
        refused = p.status === 3 ? refused + 1 : 0;
        if (refused >= 3) { r.bad = true; m.good = false; turnedDown(m); stream.abort().catch(() => {}); }
      });
      stream.on("error", (e) => send({ t: "log", run, msg: "error " + String(e?.message || e).slice(0, 200) }));
      const kind = video ? "video" : "audio";
      const heads = [];
      const sb = stream.segmentBuffer, fin = sb.finalizeSegment.bind(sb);
      let cur = null;
      sb.finalizeSegment = (id) => { cur = sb.pendingSegments.get(id)?.mediaHeader || null; try { return fin(id); } finally { cur = null; } };
      const out = stream.trackCollection[kind].output, enq = out.controller.enqueue.bind(out.controller);
      out.controller.enqueue = (chunk) => { heads.push(cur); enq(chunk); };
      const st = stream.start({ videoFormat: video ? me : undefined, audioFormat: audioOf, startTimeMs: startMs || 0,
        enabledTrackTypes: video ? 2 : 1, isPostLiveDvr: false, stallDetectionMs: 1e12 });
      const mine = video ? st.videoStream : st.audioStream, other = video ? st.audioStream : st.videoStream;
      (async () => { const rd = other.getReader(); while (!(await rd.read()).done); })().catch(() => {});
      const rd = mine.getReader();
      let head = 0;
      for (;;) {
        // the app says how far it wants this fetch to go (it waits for its players)
        while (head > r.allow && runs.get(run) === r) await new Promise((ok) => { r.gate = ok; setTimeout(ok, 10e3); });
        if (runs.get(run) !== r) break;
        const { done, value } = await rd.read();
        if (done) break;
        const h = heads.shift();
        if (!h) continue;
        const u8 = value instanceof Uint8Array ? value : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
        const at = h.isInitializationSegment ? 0 : +(h.segmentByteRangeStart ?? -1);
        if (at < 0) continue;
        if (!h.isInitializationSegment) head = at + u8.length;
        sendPiece({ t: "piece", run, at, init: !!h.isInitializationSegment, ms: h.startMs != null ? +h.startMs : -1 }, u8);
      }
      stream.abort().catch(() => {});
      runs.delete(run);
      send({ t: "end", run, bad: r.bad });
    })().catch((e) => { runs.delete(run); send({ t: "end", run, bad: !!r?.bad, error: r?.bad ? undefined : String(e?.message || e).slice(0, 200) }); });
    return "ok";
  },
  /** a live stream: one conversation for picture (format [index]) and sound together near the live edge (a
   *  picture-only live conversation gets the same sound segment for ever). Picture pieces go as run [runV], sound
   *  as [runA], each {at: segment number, ms: its start time, init}. stop(runV) ends both. */
  liveStart(runV, runA, vid, index) {
    let r = null;
    (async () => {
      const s = await session(vid);
      if (!s.sabrUrl) throw new Error("no SABR address");
      const raw = s.d.streamingData.adaptiveFormats;
      const f = raw[index];
      if (!f || !(f.mimeType || "").startsWith("video/")) throw new Error("no such picture format");
      const formats = raw.map(buildSabrFormat), me = formats[index];
      const sound = raw.map((x, i) => [x, i]).filter(([x]) => (x.mimeType || "").startsWith("audio/mp4"))
        .sort((a, b) => (b[0].bitrate || 0) - (a[0].bitrate || 0))[0];
      if (!sound) throw new Error("no mp4 sound");
      const m = await getMinter();
      let last = 0;
      const stream = new SabrStream({
        videoId: vid, formats, serverAbrStreamingUrl: s.sabrUrl, videoPlaybackUstreamerConfig: s.ustreamer,
        clientInfo: { clientName: 1, clientVersion: s.ver, ...osOf(navigator.userAgent) },
        poToken: await m.m.mintAsWebsafeString(vid),
        callbacks: { onMintPoToken: async () => (await getMinter()).m.mint(vid) },
        // at the live edge the library asks again at once: at most one request a second
        fetchFunction: async (u, o = {}) => {
          const gap = 1000 - (Date.now() - last);
          if (gap > 0) await new Promise((ok) => setTimeout(ok, gap));
          last = Date.now();
          return fetch(u, { ...o, headers: { "content-type": "text/plain;charset=UTF-8", accept: "application/vnd.yt-ump" }, credentials: "omit" });
        },
      });
      r = { stream, allow: Infinity, gate: null, bad: false };
      if (stopped.delete(runV)) { stream.abort().catch(() => {}); return; }
      runs.set(runV, r);
      let refused = 0;
      stream.on("streamProtectionStatusUpdate", (p) => {
        if (p.status === 1) m.good = true;
        refused = p.status === 3 ? refused + 1 : 0;
        if (refused >= 3) { r.bad = true; m.good = false; turnedDown(m); stream.abort().catch(() => {}); }
      });
      stream.on("error", (e) => send({ t: "log", run: runV, msg: "live error " + String(e?.message || e).slice(0, 200) }));
      const heads = { video: [], audio: [] };
      const sb = stream.segmentBuffer, fin = sb.finalizeSegment.bind(sb);
      let cur = null;
      sb.finalizeSegment = (id) => {
        const p = sb.pendingSegments.get(id);
        cur = p ? { ...p.mediaHeader, num: p.segmentNumber, start: p.startTimeMs } : null;
        try { return fin(id); } finally { cur = null; }
      };
      for (const kind of ["video", "audio"]) {
        const out = stream.trackCollection[kind].output, enq = out.controller.enqueue.bind(out.controller);
        out.controller.enqueue = (chunk) => { heads[kind].push(cur); enq(chunk); };
      }
      const st = stream.start({ videoFormat: me, audioFormat: formats[sound[1]], enabledTrackTypes: 0, isPostLiveDvr: false, stallDetectionMs: 1e12 });
      const pump = async (rs, kind, run) => {
        const rd = rs.getReader();
        let n = 0;
        for (;;) {
          if (runs.get(runV) !== r) break;
          const { done, value } = await rd.read();
          if (done) break;
          const h = heads[kind].shift();
          const u8 = value instanceof Uint8Array ? value : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
          const init = !!h?.isInitializationSegment;
          const seq = init ? 0 : (h?.num != null && +h.num > 0 ? +h.num : n + 1);
          if (!init) n = seq;
          sendPiece({ t: "piece", run, at: seq, init, ms: h?.start != null ? +h.start : -1 }, u8);
        }
        stream.abort().catch(() => {});
      };
      await Promise.all([pump(st.videoStream, "video", runV), pump(st.audioStream, "audio", runA)]);
      runs.delete(runV);
      send({ t: "end", run: runA, bad: r.bad });
      send({ t: "end", run: runV, bad: r.bad });
    })().catch((e) => {
      runs.delete(runV);
      const error = r?.bad ? undefined : String(e?.message || e).slice(0, 200);
      send({ t: "end", run: runA, bad: !!r?.bad, error });
      send({ t: "end", run: runV, bad: !!r?.bad, error });
    });
    return "ok";
  },
  /** the app wants fetch [run] to go up to byte [n] for now */
  allow(run, n) { const r = runs.get(run); if (r) { r.allow = n; r.gate?.(); } else allows.set(run, n); return "ok"; },
  stop(run) { const r = runs.get(run); allows.delete(run); if (r) { runs.delete(run); r.gate?.(); r.stream.abort().catch(() => {}); } else { stopped.add(run); if (stopped.size > 200) stopped.delete(stopped.values().next().value); } return "ok"; },
  /** yt-dlp's solver (lib, then core) as one text, from the app's copy of common/ejs */
  setSolver(src) { solverSrc = src; worker?.terminate(); worker = null; return "ok"; },
  ready() { return solverSrc ? "ok" : "no solver"; },
};
