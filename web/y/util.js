// Shared helpers: DOM, number/time formats (local style), server API, settings, icons
import { $t, $tMsg, fmtShort, fmtAgo, LANG, LOCALE } from "./i18n.js";
import * as L from "./locallib.js";
export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
export const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ESC[c]);

export function el(html) {
  const t = document.createElement("template");
  t.innerHTML = html.trim();
  return t.content.firstElementChild;
}

// ---------------------------------------------------------------- formats
export function fmtTime(s) {
  if (!isFinite(s) || s < 0) s = 0;
  s = Math.floor(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0");
}

// 1,234 -> 1.2K / 1.2천, 12,345 -> 12K / 1.2만 ... in the interface language
export const fmtViews = (n) => (n == null ? "" : $t("조회수 {0}회", fmtShort(+n)));
export const fmtSubs = (n) => (n == null || n === "" ? "" : $t("구독자 {0}명", typeof n === "number" ? fmtShort(n) : String(n).replace(/subscribers?/i, "").trim()));
export const fmtCount = (n) => (n == null ? "" : fmtShort(+n));

/** a usable epoch-seconds time, or 0: some sources give strings or nothing */
export const epoch = (ts) => (Number.isFinite(+ts) && +ts > 0 && +ts < 1e11 ? +ts : 0);

export function ago(ts) {
  if (!epoch(ts)) return "";
  const d = Date.now() / 1000 - epoch(ts);
  return d < 60 ? $t("방금 전") : fmtAgo(d);
}

/** "Oct 21, 2026, 9:00 AM" for a tooltip; "" when the time isn't usable, so a bad value never breaks a page */
export function exactTime(ts) {
  if (!epoch(ts)) return "";
  try {
    return new Intl.DateTimeFormat(LOCALE, { dateStyle: "medium", timeStyle: "short" }).format(epoch(ts) * 1000);
  } catch { return ""; }
}

export function parseT(t) {
  // "1h2m3s", "123", "1:02:03"
  if (!t) return 0;
  if (/^\d+$/.test(t)) return +t;
  if (t.includes(":")) return t.split(":").reduce((a, b) => a * 60 + +b, 0);
  let s = 0;
  t.replace(/(\d+)([hms])/g, (_, n, u) => (s += +n * { h: 3600, m: 60, s: 1 }[u]));
  return s;
}

// ---------------------------------------------------------------- settings (per device)
// iPhone only (an iPad reports itself as a Mac): these play with Safari's own player, see player.js load()
export const IPHONE = /iPhone|iPod/.test(navigator.userAgent);
// iPad (newer ones report themselves as a Mac with a touch screen): the device's own player too, see player.js load()
export const IPAD = /iPad/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);

const DEFAULTS = {
  lang: "auto",           // interface language: auto = the browser's
  theme: "light",         // light | dark | system (default: light, like classic YouTube)
  autoplay: true,         // autoplay the next video
  calm: false,            // calm mode: subscriptions only, no Shorts, trending or recommendations, no autoplay
  quality: "auto",        // auto | 4320 | 2160 | 1440 | 1080 | 720 | 480 | 360
  dataSaver: false,
  buffer: 0,              // seconds to buffer ahead; 0 = automatic
  nativeIphone: true,     // iPhone and iPad: play with the device's own player (Picture-in-Picture over other apps, AirPlay)
  captions: false,        // captions always on
  capLang: LANG,
  capSize: 100,           // %
  capBg: 75,              // background opacity %
  autoTranslate: true,    // translated captions when a video has none in your language
  speed: 1,
  rememberSpeed: false,
  volume: 1,
  muted: false,
  ambient: true,
  theater: false,
  resume: true,
  sponsor: true,
  sponsorCats: ["sponsor", "selfpromo", "interaction"],
  dislikes: true,
  heatmap: true,          // "Most replayed" over the progress bar
  dearrow: true,          // DeArrow titles
  dearrowThumbs: true,    // and thumbnails
  miniplayer: true,
  annotations: true,
};
let SET = {};
try { SET = JSON.parse(localStorage.getItem("yt.settings") || "{}"); } catch { }
export const settings = new Proxy({ ...DEFAULTS, ...SET }, {
  set(o, k, v) {
    o[k] = v;
    try { localStorage.setItem("yt.settings", JSON.stringify(o)); } catch { }
    dispatchEvent(new CustomEvent("settings", { detail: k }));
    return true;
  },
});
export const local = {
  get(k, d) { try { const v = localStorage.getItem("yt." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("yt." + k, JSON.stringify(v)); } catch { } },
};

// ---------------------------------------------------------------- server
export class HttpError extends Error {
  constructor(status, msg) { super(msg); this.status = status; }
}
// TubeHaus has no accounts of its own; put it behind your own login (auth proxy).
// Set window.TUBEHAUS_LOGIN_URL (e.g. "/login?r=") to send expired sessions there;
// otherwise the page is simply reloaded so the auth proxy can step in.
export function toGate(back = location.href) {
  const login = window.TUBEHAUS_LOGIN_URL;
  // a same-site login page only takes a path back
  if (login && login.startsWith("/") && back.startsWith(location.origin)) back = back.slice(location.origin.length) || "/";
  const to = new URL(login ? login + encodeURIComponent(back) : back, location.origin);
  if (to.protocol === "https:" || to.protocol === "http:") location.href = to.href;   // never javascript: and the like
}
const inflight = new Map();
const cache = new Map();
export async function api(path, { method = "GET", body, ttl = 0, signal, quiet = false } = {}) {
  const key = method + path;
  if (method === "GET" && ttl) {
    const c = cache.get(key);
    if (c && Date.now() - c.t < ttl) return c.v;
    if (inflight.has(key)) return inflight.get(key);
  }
  const p = (async () => {
    const r = await fetch(path, {
      method, signal, credentials: "same-origin",
      headers: body ? { "Content-Type": "application/json" } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (r.status === 401 && !quiet) { toGate(); throw new HttpError(401, $t("로그인이 필요해요")); }
    let d = null;
    try { d = await r.json(); } catch { }
    if (!r.ok) throw new HttpError(r.status, d && d.error ? $tMsg(d.error) : $t("서버 오류 ") + r.status);
    if (method === "GET" && ttl) cache.set(key, { t: Date.now(), v: d });
    return d;
  })();
  if (method === "GET" && ttl) {
    inflight.set(key, p);
    const done = () => inflight.delete(key);
    p.then(done, done);   // (p.finally() would make a second promise nobody catches: an "Uncaught" error per failed request)
  }
  return p;
}
// Public instances (server started with HT_PUBLIC_MODE=1) keep each visitor's library in their own
// browser (locallib.js); the server only fetches feeds and parses imports for them.
export let PUBLIC = false;
export let SERVER_MAX_H = Infinity;     // the operator's quality cap (HT_MAX_HEIGHT), to save bandwidth
// the instance's own name (HT_NAME) and extra menu links (HT_LINKS)
let NAME = "";
export let LINKS = [];
// a name may come in several languages, {"ko": "홈튜브", "en": "HomeTube"}: the interface language, else English, else the first
export const pickName = (v) => !v || typeof v === "string" ? v || "" : String(v[LANG] ?? v.en ?? Object.values(v)[0] ?? "");
export const siteName = () => pickName(NAME) || $t("튜브하우스");
export const modeReady = fetch("/api/p/health", { credentials: "same-origin" })
  .then((r) => (r.ok ? r.json() : {})).then((h) => {
    PUBLIC = !!h.public;
    SERVER_MAX_H = +h.max_height || Infinity;
    NAME = typeof h.name === "string" || (h.name && typeof h.name === "object") ? h.name : "";
    LINKS = Array.isArray(h.links) ? h.links : [];
    if (h.accounts && !window.TUBEHAUS_LOGIN_URL) window.TUBEHAUS_LOGIN_URL = "/login?r=";   // sign-up required
    document.documentElement.classList.toggle("public", PUBLIC);
    return PUBLIC;
  })
  .catch(() => false);
const LOCAL = /^(lib|pos|sub|subs\/import|list|pl|pl\/new|pl\/del|pl\/rename|hist|hist\/del|shorts\/sig|shorts\/reset)$/;
export async function lib(path, opts = {}) {
  if (await modeReady) {
    const [p, q] = path.split("?");
    if (p === "feed") return api("/api/p/yt/feed?ch=" + L.subIds().join(","), opts);
    if (p === "import") { const n = L.libMerge(await api("/api/p/yt/import", opts)); fillNames(); return n; }
    if (LOCAL.test(p)) {
      try { return (opts.method || "GET") === "GET" ? (p === "lib" ? L.libGet() : L.libQuery(p, new URLSearchParams(q || ""))) : L.libPost(p, opts.body || {}); }
      catch (e) { throw new HttpError(e.status || 400, $tMsg(e.message)); }
    }
  }
  return api("/api/p/yt/" + path, opts);
}
// imports that only had channel IDs: look the names and pictures up (the server does this itself when it keeps the library)
async function fillNames() {
  const subs = L.libGet().subs;
  for (const [cid, s] of Object.entries(subs).filter(([c, s]) => !s.thumb || s.name === c).slice(0, 100)) {
    try {
      const c = await api(`/api/v1/channels/${cid}?fields=author,authorThumbnails`, { quiet: true });
      L.libPost("sub", { id: cid, name: c.author || s.name, thumb: pickThumb(c.authorThumbnails, 88), on: true });
    } catch { }
  }
}
export const localLibrary = () => L.libAll();
export const dropCache = (prefix) => { for (const k of cache.keys()) if (k.includes(prefix)) cache.delete(k); };

// ---------------------------------------------------------------- video objects
export function thumb(id, q = "hqdefault") {
  return `/vi/${id}/${q}.jpg`;
}
export function localUrl(u) {
  if (!u) return "";
  if (u.startsWith("//")) u = "https:" + u;
  return u.replace(/^https?:\/\/[^/]+(?=\/(vi|ggpht|sb)\/)/, "").replace(/^https:\/\/(yt\d\.ggpht\.com|yt\d\.googleusercontent\.com)\//, "/ggpht/");
}
export function pickThumb(list, want = 88) {
  if (!list || !list.length) return "";
  const s = list.filter((x) => x && x.url).sort((a, b) => (a.width || 0) - (b.width || 0));   // some come with no address
  if (!s.length) return "";
  return localUrl((s.find((x) => (x.width || 0) >= want) || s[s.length - 1]).url);
}
export function normVideo(v) {
  if (!v) return null;
  const id = v.videoId || v.id;
  return {
    id,
    title: v.title || "",
    author: v.author || "",
    authorId: v.authorId || "",
    len: +v.lengthSeconds || +v.len || 0,
    views: v.viewCount ?? v.views ?? null,
    viewsText: v.viewCountText || "",
    published: v.published || 0,
    publishedText: v.publishedText || "",
    live: !!(v.liveNow || v.live),
    upcoming: !!(v.isUpcoming || v.upcoming),
    premiere: v.premiereTimestamp || 0,
    authorThumb: v.authorThumb || pickThumb(v.authorThumbnails, 68),
    verified: !!v.authorVerified,
    short: !!v.short,
    desc: v.description || "",
    is4k: !!v.is4k,
    cc: !!v.hasCaptions,
    isNew: !!v.isNew,
    index: v.index,
  };
}
export function metaOf(v) {
  // only what the server needs to keep (history / playlists)
  return { id: v.id, title: v.title, author: v.author, authorId: v.authorId, len: v.len, published: v.published, views: v.views, live: v.live || undefined };
}

// ---------------------------------------------------------------- toasts
let toastT;
export function toast(msg, action, fn, ms = 4000) {
  let t = $("#toast");
  t.innerHTML = `<span>${esc(msg)}</span>` + (action ? `<button>${esc(action)}</button>` : "");
  t.classList.add("show");
  if (action) t.querySelector("button").onclick = () => { t.classList.remove("show"); fn(); };
  clearTimeout(toastT);
  toastT = setTimeout(() => t.classList.remove("show"), ms);
}

export async function copy(text) {
  try { await navigator.clipboard.writeText(text); }
  catch {
    const ta = el("<textarea style='position:fixed;opacity:0'></textarea>");
    ta.value = text; document.body.append(ta); ta.select(); document.execCommand("copy"); ta.remove();
  }
  toast($t("클립보드에 복사됨"));
}

// keep only allowed tags of the HTML Invidious returns (descriptions, comments)
export function safeHtml(html) {
  const doc = new DOMParser().parseFromString(`<div>${html || ""}</div>`, "text/html");
  const walk = (n) => {
    for (const c of [...n.childNodes]) {
      if (c.nodeType === 1) {
        const tag = c.tagName.toLowerCase();
        if (!["a", "b", "i", "s", "br", "span", "strong", "em", "p", "u"].includes(tag)) {
          c.replaceWith(...c.childNodes);
          walk(n);
          return;
        }
        for (const a of [...c.attributes]) if (!(tag === "a" && a.name === "href")) c.removeAttribute(a.name);
        if (tag === "a") {
          let h = c.getAttribute("href") || "";
          h = h.replace(/^https?:\/\/(www\.|m\.)?youtube\.com(?=\/)/, "").replace(/^https?:\/\/youtu\.be\/([\w-]{11})\??/, "/watch?v=$1&");
          if (/^javascript:/i.test(h)) h = "#";
          c.setAttribute("href", h);
          if (/^https?:/.test(h)) { c.setAttribute("target", "_blank"); c.setAttribute("rel", "noopener noreferrer"); }
          else if (h.startsWith("/")) c.setAttribute("data-nav", "");
        }
        walk(c);
      }
    }
  };
  walk(doc.body.firstChild);
  return doc.body.firstChild.innerHTML;
}
export function linkify(text) {
  return esc(text)
    .replace(/https?:\/\/[^\s<]+/g, (u) => `<a href="${u}" target="_blank" rel="noopener noreferrer">${u}</a>`)
    .replace(/(^|\s)((?:\d{1,2}:)?\d{1,2}:\d{2})(?=\s|$)/g, (m, s, t) => `${s}<a href="#" data-seek="${parseT(t)}">${t}</a>`)
    .replace(/(^|\s)#([\p{L}\p{N}_]+)/gu, (m, s, tag) => `${s}<a href="/hashtag/${encodeURIComponent(tag)}" data-nav>#${tag}</a>`)
    .replace(/\n/g, "<br>");
}

// ---------------------------------------------------------------- icons (Material, Apache-2.0)
// Material Symbols Rounded, weight 400 (https://github.com/google/material-design-icons), Copyright Google LLC, Apache License 2.0.
// P = outlined, F = filled; both on a 960 grid (viewBox 0 -960 960 960).
const P = {
  menu: "M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h640q17 0 28.5 11.5T840-280q0 17-11.5 28.5T800-240H160Zm0-200q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm0-200q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h640q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H160Z",
  search: "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z",
  mic: "M395-435q-35-35-35-85v-240q0-50 35-85t85-35q50 0 85 35t35 85v240q0 50-35 85t-85 35q-50 0-85-35Zm85-205Zm-40 480v-83q-92-13-157.5-78T203-479q-2-17 9-29t28-12q17 0 28.5 11.5T284-480q14 70 69.5 115T480-320q72 0 127-45.5T676-480q4-17 15.5-28.5T720-520q17 0 28 12t9 29q-14 91-79 157t-158 79v83q0 17-11.5 28.5T480-120q-17 0-28.5-11.5T440-160Zm68.5-331.5Q520-503 520-520v-240q0-17-11.5-28.5T480-800q-17 0-28.5 11.5T440-760v240q0 17 11.5 28.5T480-480q17 0 28.5-11.5Z",
  home: "M240-200h120v-200q0-17 11.5-28.5T400-440h160q17 0 28.5 11.5T600-400v200h120v-360L480-740 240-560v360Zm-80 0v-360q0-19 8.5-36t23.5-28l240-180q21-16 48-16t48 16l240 180q15 11 23.5 28t8.5 36v360q0 33-23.5 56.5T720-120H560q-17 0-28.5-11.5T520-160v-200h-80v200q0 17-11.5 28.5T400-120H240q-33 0-56.5-23.5T160-200Zm320-270Z",
  shorts: "m422-232 207-248H469l29-227-185 267h139l-30 208Zm-62-128H236q-24 0-35.5-21.5T203-423l299-430q10-14 26-19.5t33 .5q17 6 25 21t6 32l-32 259h155q26 0 36.5 23t-6.5 43L416-100q-11 13-27 17t-31-3q-15-7-23.5-21.5T328-139l32-221Zm111-110Z",
  subs: "M160-80q-33 0-56.5-23.5T80-160v-400q0-33 23.5-56.5T160-640h640q33 0 56.5 23.5T880-560v400q0 33-23.5 56.5T800-80H160Zm0-80h640v-400H160v400Zm271-61 184-122q9-6 9-17t-9-17L431-499q-10-7-20.5-1.5T400-483v246q0 12 10.5 17.5T431-221ZM200-680q-17 0-28.5-11.5T160-720q0-17 11.5-28.5T200-760h560q17 0 28.5 11.5T800-720q0 17-11.5 28.5T760-680H200Zm120-120q-17 0-28.5-11.5T280-840q0-17 11.5-28.5T320-880h320q17 0 28.5 11.5T680-840q0 17-11.5 28.5T640-800H320ZM160-160v-400 400Z",
  history: "M480-120q-126 0-223-76.5T131-392q-4-15 6-27.5t27-14.5q16-2 29 6t18 24q24 90 99 147t170 57q117 0 198.5-81.5T760-480q0-117-81.5-198.5T480-760q-69 0-129 32t-101 88h70q17 0 28.5 11.5T360-600q0 17-11.5 28.5T320-560H160q-17 0-28.5-11.5T120-600v-160q0-17 11.5-28.5T160-800q17 0 28.5 11.5T200-760v54q51-64 124.5-99T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-120Zm40-376 100 100q11 11 11 28t-11 28q-11 11-28 11t-28-11L452-452q-6-6-9-13.5t-3-15.5v-159q0-17 11.5-28.5T480-680q17 0 28.5 11.5T520-640v144Z",
  playlists: "M160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Zm511 499q-5 3-10 3t-10-2q-5-2-8-6.5t-3-10.5v-246q0-6 3-10.5t8-6.5q5-2 10-2t10 3l184 122q5 3 7 7.5t2 9.5q0 5-2 9.5t-7 7.5L671-141Z",
  later: "M520-496v-144q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v159q0 8 3 15.5t9 13.5l132 132q11 11 28 11t28-11q11-11 11-28t-11-28L520-496ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 320q133 0 226.5-93.5T800-480q0-133-93.5-226.5T480-800q-133 0-226.5 93.5T160-480q0 133 93.5 226.5T480-160Z",
  like: "M840-640q32 0 56 24t24 56v80q0 7-2 15t-4 15L794-168q-9 20-30 34t-44 14H280v-520l240-238q15-15 35.5-17.5T595-888q19 10 28 28t4 37l-45 183h258Zm-480 34v406h360l120-280v-80H480l54-220-174 174ZM160-120q-33 0-56.5-23.5T80-200v-360q0-33 23.5-56.5T160-640h120v80H160v360h120v80H160Zm200-80v-406 406Z",
  dislike: "M120-320q-32 0-56-24t-24-56v-80q0-7 2-15t4-15l120-282q9-20 30-34t44-14h440v520L440-82q-15 15-35.5 17.5T365-72q-19-10-28-28t-4-37l45-183H120Zm480-34v-406H240L120-480v80h360l-54 220 174-174Zm200-486q33 0 56.5 23.5T880-760v360q0 33-23.5 56.5T800-320H680v-80h120v-360H680v-80h120Zm-200 80v406-406Z",
  share: "M680-80q-50 0-85-35t-35-85q0-6 3-28L282-392q-16 15-37 23.5t-45 8.5q-50 0-85-35t-35-85q0-50 35-85t85-35q24 0 45 8.5t37 23.5l281-164q-2-7-2.5-13.5T560-760q0-50 35-85t85-35q50 0 85 35t35 85q0 50-35 85t-85 35q-24 0-45-8.5T598-672L317-508q2 7 2.5 13.5t.5 14.5q0 8-.5 14.5T317-452l281 164q16-15 37-23.5t45-8.5q50 0 85 35t35 85q0 50-35 85t-85 35Zm0-80q17 0 28.5-11.5T720-200q0-17-11.5-28.5T680-240q-17 0-28.5 11.5T640-200q0 17 11.5 28.5T680-160ZM200-440q17 0 28.5-11.5T240-480q0-17-11.5-28.5T200-520q-17 0-28.5 11.5T160-480q0 17 11.5 28.5T200-440Zm508.5-291.5Q720-743 720-760t-11.5-28.5Q697-800 680-800t-28.5 11.5Q640-777 640-760t11.5 28.5Q663-720 680-720t28.5-11.5ZM680-200ZM200-480Zm480-280Z",
  download: "M465-339.5q-7-2.5-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T480-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5q-8 0-15-2.5ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z",
  save: "M160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h200q17 0 28.5 11.5T400-360q0 17-11.5 28.5T360-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h360q17 0 28.5 11.5T560-520q0 17-11.5 28.5T520-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h360q17 0 28.5 11.5T560-680q0 17-11.5 28.5T520-640H160Zm491.5 468.5Q640-183 640-200v-120H520q-17 0-28.5-11.5T480-360q0-17 11.5-28.5T520-400h120v-120q0-17 11.5-28.5T680-560q17 0 28.5 11.5T720-520v120h120q17 0 28.5 11.5T880-360q0 17-11.5 28.5T840-320H720v120q0 17-11.5 28.5T680-160q-17 0-28.5-11.5Z",
  more: "M480-160q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T480-160Zm0-240q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm0-240q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0 33-23.5 56.5T480-640Z",
  moreh: "M240-400q-33 0-56.5-23.5T160-480q0-33 23.5-56.5T240-560q33 0 56.5 23.5T320-480q0 33-23.5 56.5T240-400Zm240 0q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm240 0q-33 0-56.5-23.5T640-480q0-33 23.5-56.5T720-560q33 0 56.5 23.5T800-480q0 33-23.5 56.5T720-400Z",
  play: "M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Zm80-207Zm0 134 210-134-210-134v268Z",
  pause: "M600-200q-33 0-56.5-23.5T520-280v-400q0-33 23.5-56.5T600-760h80q33 0 56.5 23.5T760-680v400q0 33-23.5 56.5T680-200h-80Zm-320 0q-33 0-56.5-23.5T200-280v-400q0-33 23.5-56.5T280-760h80q33 0 56.5 23.5T440-680v400q0 33-23.5 56.5T360-200h-80Zm320-80h80v-400h-80v400Zm-320 0h80v-400h-80v400Zm0-400v400-400Zm320 0v400-400Z",
  next: "M660-280v-400q0-17 11.5-28.5T700-720q17 0 28.5 11.5T740-680v400q0 17-11.5 28.5T700-240q-17 0-28.5-11.5T660-280Zm-440-35v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T548-480q0 10-4.5 18.5T530-447L282-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Zm80-165Zm0 90 136-90-136-90v180Z",
  prev: "M220-280v-400q0-17 11.5-28.5T260-720q17 0 28.5 11.5T300-680v400q0 17-11.5 28.5T260-240q-17 0-28.5-11.5T220-280Zm458-1L430-447q-9-6-13.5-14.5T412-480q0-10 4.5-18.5T430-513l248-166q5-4 11-5t11-1q16 0 28 11t12 29v330q0 18-12 29t-28 11q-5 0-11-1t-11-5Zm-18-199Zm0 90v-180l-136 90 136 90Z",
  vol: "M760-481q0-83-44-151.5T598-735q-15-7-22-21.5t-2-29.5q6-16 21.5-23t31.5 0q97 43 155 131.5T840-481q0 108-58 196.5T627-153q-16 7-31.5 0T574-176q-5-15 2-29.5t22-21.5q74-34 118-102.5T760-481ZM280-360H160q-17 0-28.5-11.5T120-400v-160q0-17 11.5-28.5T160-600h120l132-132q19-19 43.5-8.5T480-703v446q0 27-24.5 37.5T412-228L280-360Zm380-120q0 42-19 79.5T591-339q-10 6-20.5.5T560-356v-250q0-12 10.5-17.5t20.5.5q31 25 50 63t19 80ZM400-606l-86 86H200v80h114l86 86v-252ZM300-480Z",
  vollow: "M360-360H240q-17 0-28.5-11.5T200-400v-160q0-17 11.5-28.5T240-600h120l132-132q19-19 43.5-8.5T560-703v446q0 27-24.5 37.5T492-228L360-360Zm380-120q0 42-19 79.5T671-339q-10 6-20.5.5T640-356v-250q0-12 10.5-17.5t20.5.5q31 25 50 63t19 80ZM480-606l-86 86H280v80h114l86 86v-252ZM380-480Z",
  mute: "M671-177q-11 7-22 13t-23 11q-15 7-30.5 0T574-176q-6-15 1.5-29.5T598-227q4-2 7.5-4t7.5-4L480-368v111q0 27-24.5 37.5T412-228L280-360H160q-17 0-28.5-11.5T120-400v-160q0-17 11.5-28.5T160-600h88L84-764q-11-11-11-28t11-28q11-11 28-11t28 11l680 680q11 11 11 28t-11 28q-11 11-28 11t-28-11l-93-93Zm89-304q0-83-44-151.5T598-735q-15-7-22-21.5t-2-29.5q6-16 21.5-23t31.5 0q97 43 155 131t58 197q0 33-6 65.5T817-353q-8 22-24.5 27.5t-30.5.5q-14-5-22.5-18t-.5-30q11-26 16-52.5t5-55.5ZM591-623q33 21 51 63t18 80v10q0 5-1 10-2 13-14 17t-22-6l-51-51q-6-6-9-13.5t-3-15.5v-77q0-12 10.5-17.5t20.5.5Zm-201-59q-6-6-6-14t6-14l22-22q19-19 43.5-8.5T480-703v63q0 14-12 19t-22-5l-56-56Zm10 328v-94l-72-72H200v80h114l86 86Zm-36-130Z",
  cc: "M200-160q-33 0-56.5-23.5T120-240v-480q0-33 23.5-56.5T200-800h560q33 0 56.5 23.5T840-720v480q0 33-23.5 56.5T760-160H200Zm0-80h560v-480H200v480Zm80-120h120q17 0 28.5-11.5T440-400v-20q0-9-6-15t-15-6h-18q-9 0-15 6t-6 15h-80v-120h80q0 9 6 15t15 6h18q9 0 15-6t6-15v-20q0-17-11.5-28.5T400-600H280q-17 0-28.5 11.5T240-560v160q0 17 11.5 28.5T280-360Zm400-240H560q-17 0-28.5 11.5T520-560v160q0 17 11.5 28.5T560-360h120q17 0 28.5-11.5T720-400v-20q0-9-6-15t-15-6h-18q-9 0-15 6t-6 15h-80v-120h80q0 9 6 15t15 6h18q9 0 15-6t6-15v-20q0-17-11.5-28.5T680-600ZM200-240v-480 480Z",
  gear: "M433-80q-27 0-46.5-18T363-142l-9-66q-13-5-24.5-12T307-235l-62 26q-25 11-50 2t-39-32l-47-82q-14-23-8-49t27-43l53-40q-1-7-1-13.5v-27q0-6.5 1-13.5l-53-40q-21-17-27-43t8-49l47-82q14-23 39-32t50 2l62 26q11-8 23-15t24-12l9-66q4-26 23.5-44t46.5-18h94q27 0 46.5 18t23.5 44l9 66q13 5 24.5 12t22.5 15l62-26q25-11 50-2t39 32l47 82q14 23 8 49t-27 43l-53 40q1 7 1 13.5v27q0 6.5-2 13.5l53 40q21 17 27 43t-8 49l-48 82q-14 23-39 32t-50-2l-60-26q-11 8-23 15t-24 12l-9 66q-4 26-23.5 44T527-80h-94Zm7-80h79l14-106q31-8 57.5-23.5T639-327l99 41 39-68-86-65q5-14 7-29.5t2-31.5q0-16-2-31.5t-7-29.5l86-65-39-68-99 42q-22-23-48.5-38.5T533-694l-13-106h-79l-14 106q-31 8-57.5 23.5T321-633l-99-41-39 68 86 64q-5 15-7 30t-2 32q0 16 2 31t7 30l-86 65 39 68 99-42q22 23 48.5 38.5T427-266l13 106Zm42-180q58 0 99-41t41-99q0-58-41-99t-99-41q-59 0-99.5 41T342-480q0 58 40.5 99t99.5 41Zm-2-140Z",
  fs: "M200-200h80q17 0 28.5 11.5T320-160q0 17-11.5 28.5T280-120H160q-17 0-28.5-11.5T120-160v-120q0-17 11.5-28.5T160-320q17 0 28.5 11.5T200-280v80Zm560 0v-80q0-17 11.5-28.5T800-320q17 0 28.5 11.5T840-280v120q0 17-11.5 28.5T800-120H680q-17 0-28.5-11.5T640-160q0-17 11.5-28.5T680-200h80ZM200-760v80q0 17-11.5 28.5T160-640q-17 0-28.5-11.5T120-680v-120q0-17 11.5-28.5T160-840h120q17 0 28.5 11.5T320-800q0 17-11.5 28.5T280-760h-80Zm560 0h-80q-17 0-28.5-11.5T640-800q0-17 11.5-28.5T680-840h120q17 0 28.5 11.5T840-800v120q0 17-11.5 28.5T800-640q-17 0-28.5-11.5T760-680v-80Z",
  fsx: "M240-240h-80q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h120q17 0 28.5 11.5T320-280v120q0 17-11.5 28.5T280-120q-17 0-28.5-11.5T240-160v-80Zm480 0v80q0 17-11.5 28.5T680-120q-17 0-28.5-11.5T640-160v-120q0-17 11.5-28.5T680-320h120q17 0 28.5 11.5T840-280q0 17-11.5 28.5T800-240h-80ZM240-720v-80q0-17 11.5-28.5T280-840q17 0 28.5 11.5T320-800v120q0 17-11.5 28.5T280-640H160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h80Zm480 0h80q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H680q-17 0-28.5-11.5T640-680v-120q0-17 11.5-28.5T680-840q17 0 28.5 11.5T720-800v80Z",
  theater: "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm0-80h80v-480h-80v480Zm160 0h320v-480H320v480Zm400 0h80v-480h-80v480ZM320-720v480-480Z",
  theaterx: "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm0-80h160v-480H160v480Zm240 0h160v-480H400v480Zm240 0h160v-480H640v480Zm-240 0v-480 480Z",
  mini: "M440-280h280q17 0 28.5-11.5T760-320v-160q0-17-11.5-28.5T720-520H440q-17 0-28.5 11.5T400-480v160q0 17 11.5 28.5T440-280ZM160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm0-80h640v-480H160v480Zm0 0v-480 480Z",
  close: "M480-424 284-228q-11 11-28 11t-28-11q-11-11-11-28t11-28l196-196-196-196q-11-11-11-28t11-28q11-11 28-11t28 11l196 196 196-196q11-11 28-11t28 11q11 11 11 28t-11 28L536-480l196 196q11 11 11 28t-11 28q-11 11-28 11t-28-11L480-424Z",
  check: "m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z",
  right: "M504-480 348-636q-11-11-11-28t11-28q11-11 28-11t28 11l184 184q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L404-268q-11 11-28 11t-28-11q-11-11-11-28t11-28l156-156Z",
  left: "m432-480 156 156q11 11 11 28t-11 28q-11 11-28 11t-28-11L348-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l184-184q11-11 28-11t28 11q11 11 11 28t-11 28L432-480Z",
  back: "m313-440 196 196q12 12 11.5 28T508-188q-12 11-28 11.5T452-188L188-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l264-264q11-11 27.5-11t28.5 11q12 12 12 28.5T508-715L313-520h447q17 0 28.5 11.5T800-480q0 17-11.5 28.5T760-440H313Z",
  replay: "M339.5-108.5q-65.5-28.5-114-77t-77-114Q120-365 120-440q0-17 11.5-28.5T160-480q17 0 28.5 11.5T200-440q0 117 81.5 198.5T480-160q117 0 198.5-81.5T760-440q0-117-81.5-198.5T480-720h-6l34 34q12 12 11.5 28T508-630q-12 12-28.5 12.5T451-629L348-732q-12-12-12-28t12-28l103-103q12-12 28.5-11.5T508-890q11 12 11.5 28T508-834l-34 34h6q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-440q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-80q-75 0-140.5-28.5Z",
  loop: "m274-200 34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z",
  shuffle: "M600-160q-17 0-28.5-11.5T560-200q0-17 11.5-28.5T600-240h64l-99-99q-12-12-11.5-28.5T566-396q12-12 28.5-12t28.5 12l97 98v-62q0-17 11.5-28.5T760-400q17 0 28.5 11.5T800-360v160q0 17-11.5 28.5T760-160H600Zm-428-12q-11-11-11-28t11-28l492-492h-64q-17 0-28.5-11.5T560-760q0-17 11.5-28.5T600-800h160q17 0 28.5 11.5T800-760v160q0 17-11.5 28.5T760-560q-17 0-28.5-11.5T720-600v-64L228-172q-11 11-28 11t-28-11Zm-1-560q-11-11-11-28t11-28q11-11 27.5-11t28.5 11l168 167q11 11 11.5 27.5T395-565q-11 11-28 11t-28-11L171-732Z",
  speed: "M480-316.5q38-.5 56-27.5l169-253q9-14-2.5-25.5T677-625L424-456q-27 18-28.5 55t22.5 61q24 24 62 23.5Zm0-483.5q36 0 71 6t68 19q16 6 34 22.5t10 31.5q-8 15-36 20t-45-1q-25-9-50.5-13.5T480-720q-133 0-226.5 93.5T160-400q0 42 11.5 83t32.5 77h552q23-38 33.5-79t10.5-85q0-26-4.5-51T782-504q-6-17-2-33t18-27q13-10 28.5-6t21.5 18q15 35 23 71.5t9 74.5q1 57-13 109t-41 99q-11 18-30 28t-40 10H204q-21 0-40-10t-30-28q-26-45-40-95.5T80-400q0-83 31.5-155.5t86-127Q252-737 325-768.5T480-800Zm7 313Z",
  tune: "M451.5-131.5Q440-143 440-160v-160q0-17 11.5-28.5T480-360q17 0 28.5 11.5T520-320v40h280q17 0 28.5 11.5T840-240q0 17-11.5 28.5T800-200H520v40q0 17-11.5 28.5T480-120q-17 0-28.5-11.5ZM160-200q-17 0-28.5-11.5T120-240q0-17 11.5-28.5T160-280h160q17 0 28.5 11.5T360-240q0 17-11.5 28.5T320-200H160Zm131.5-171.5Q280-383 280-400v-40H160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h120v-40q0-17 11.5-28.5T320-600q17 0 28.5 11.5T360-560v160q0 17-11.5 28.5T320-360q-17 0-28.5-11.5ZM480-440q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520h320q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H480Zm131.5-171.5Q600-623 600-640v-160q0-17 11.5-28.5T640-840q17 0 28.5 11.5T680-800v40h120q17 0 28.5 11.5T840-720q0 17-11.5 28.5T800-680H680v40q0 17-11.5 28.5T640-600q-17 0-28.5-11.5ZM160-680q-17 0-28.5-11.5T120-720q0-17 11.5-28.5T160-760h320q17 0 28.5 11.5T520-720q0 17-11.5 28.5T480-680H160Z",
  timer: "M400-840q-17 0-28.5-11.5T360-880q0-17 11.5-28.5T400-920h160q17 0 28.5 11.5T600-880q0 17-11.5 28.5T560-840H400Zm108.5 428.5Q520-423 520-440v-160q0-17-11.5-28.5T480-640q-17 0-28.5 11.5T440-600v160q0 17 11.5 28.5T480-400q17 0 28.5-11.5Zm-168 303Q275-137 226-186t-77.5-114.5Q120-366 120-440t28.5-139.5Q177-645 226-694t114.5-77.5Q406-800 480-800q62 0 119 20t107 58l28-28q11-11 28-11t28 11q11 11 11 28t-11 28l-28 28q38 50 58 107t20 119q0 74-28.5 139.5T734-186q-49 49-114.5 77.5T480-80q-74 0-139.5-28.5ZM678-242q82-82 82-198t-82-198q-82-82-198-82t-198 82q-82 82-82 198t82 198q82 82 198 82t198-82ZM480-440Z",
  ambient: "M106-386q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-160q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm105.5 334.5Q200-223 200-240t11.5-28.5Q223-280 240-280t28.5 11.5Q280-257 280-240t-11.5 28.5Q257-200 240-200t-28.5-11.5Zm0-160Q200-383 200-400t11.5-28.5Q223-440 240-440t28.5 11.5Q280-417 280-400t-11.5 28.5Q257-360 240-360t-28.5-11.5Zm0-160Q200-543 200-560t11.5-28.5Q223-600 240-600t28.5 11.5Q280-577 280-560t-11.5 28.5Q257-520 240-520t-28.5-11.5Zm0-160Q200-703 200-720t11.5-28.5Q223-760 240-760t28.5 11.5Q280-737 280-720t-11.5 28.5Q257-680 240-680t-28.5-11.5Zm146 334Q340-375 340-400t17.5-42.5Q375-460 400-460t42.5 17.5Q460-425 460-400t-17.5 42.5Q425-340 400-340t-42.5-17.5Zm0-160Q340-535 340-560t17.5-42.5Q375-620 400-620t42.5 17.5Q460-585 460-560t-17.5 42.5Q425-500 400-500t-42.5-17.5Zm14 306Q360-223 360-240t11.5-28.5Q383-280 400-280t28.5 11.5Q440-257 440-240t-11.5 28.5Q417-200 400-200t-28.5-11.5Zm0-480Q360-703 360-720t11.5-28.5Q383-760 400-760t28.5 11.5Q440-737 440-720t-11.5 28.5Q417-680 400-680t-28.5-11.5ZM386-106q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-720q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm131.5 468.5Q500-375 500-400t17.5-42.5Q535-460 560-460t42.5 17.5Q620-425 620-400t-17.5 42.5Q585-340 560-340t-42.5-17.5Zm0-160Q500-535 500-560t17.5-42.5Q535-620 560-620t42.5 17.5Q620-585 620-560t-17.5 42.5Q585-500 560-500t-42.5-17.5Zm14 306Q520-223 520-240t11.5-28.5Q543-280 560-280t28.5 11.5Q600-257 600-240t-11.5 28.5Q577-200 560-200t-28.5-11.5Zm0-480Q520-703 520-720t11.5-28.5Q543-760 560-760t28.5 11.5Q600-737 600-720t-11.5 28.5Q577-680 560-680t-28.5-11.5ZM546-106q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-720q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm145.5 614.5Q680-223 680-240t11.5-28.5Q703-280 720-280t28.5 11.5Q760-257 760-240t-11.5 28.5Q737-200 720-200t-28.5-11.5Zm0-160Q680-383 680-400t11.5-28.5Q703-440 720-440t28.5 11.5Q760-417 760-400t-11.5 28.5Q737-360 720-360t-28.5-11.5Zm0-160Q680-543 680-560t11.5-28.5Q703-600 720-600t28.5 11.5Q760-577 760-560t-11.5 28.5Q737-520 720-520t-28.5-11.5Zm0-160Q680-703 680-720t11.5-28.5Q703-760 720-760t28.5 11.5Q760-737 760-720t-11.5 28.5Q737-680 720-680t-28.5-11.5ZM826-386q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-160q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Z",
  info: "M508.5-291.5Q520-303 520-320v-160q0-17-11.5-28.5T480-520q-17 0-28.5 11.5T440-480v160q0 17 11.5 28.5T480-280q17 0 28.5-11.5Zm0-320Q520-623 520-640t-11.5-28.5Q497-680 480-680t-28.5 11.5Q440-657 440-640t11.5 28.5Q463-600 480-600t28.5-11.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
  audio: "M280-120h-80q-33 0-56.5-23.5T120-200v-280q0-75 28.5-140.5t77-114q48.5-48.5 114-77T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480v280q0 33-23.5 56.5T760-120h-80q-33 0-56.5-23.5T600-200v-160q0-33 23.5-56.5T680-440h80v-40q0-117-81.5-198.5T480-760q-117 0-198.5 81.5T200-480v40h80q33 0 56.5 23.5T360-360v160q0 33-23.5 56.5T280-120Zm0-240h-80v160h80v-160Zm400 0v160h80v-160h-80Zm-400 0h-80 80Zm400 0h80-80Z",
  bell: "M200-200q-17 0-28.5-11.5T160-240q0-17 11.5-28.5T200-280h40v-280q0-83 50-147.5T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h40q17 0 28.5 11.5T800-240q0 17-11.5 28.5T760-200H200Zm280-300Zm0 420q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z",
  account: "M234-276q51-39 114-61.5T480-360q69 0 132 22.5T726-276q35-41 54.5-93T800-480q0-133-93.5-226.5T480-800q-133 0-226.5 93.5T160-480q0 59 19.5 111t54.5 93Zm146.5-204.5Q340-521 340-580t40.5-99.5Q421-720 480-720t99.5 40.5Q620-639 620-580t-40.5 99.5Q539-440 480-440t-99.5-40.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm100-95.5q47-15.5 86-44.5-39-29-86-44.5T480-280q-53 0-100 15.5T294-220q39 29 86 44.5T480-160q53 0 100-15.5ZM523-537q17-17 17-43t-17-43q-17-17-43-17t-43 17q-17 17-17 43t17 43q17 17 43 17t43-17Zm-43-43Zm0 360Z",
  trending: "M253-173q-93-93-93-227 0-113 67-217t184-182q22-15 45.5-1.5T480-760v52q0 34 23.5 57t57.5 23q17 0 32.5-7.5T621-657q8-10 20.5-12.5T665-664q63 45 99 115t36 149q0 134-93 227T480-80q-134 0-227-93Zm-13-227q0 52 21 98.5t60 81.5q-1-5-1-9v-9q0-32 12-60t35-51l113-111 113 111q23 23 35 51t12 60v9q0 4-1 9 39-35 60-81.5t21-98.5q0-50-18.5-94.5T648-574q-20 13-42 19.5t-45 6.5q-62 0-107.5-41T401-690q-78 66-119.5 140.5T240-400Zm240 52-57 56q-11 11-17 25t-6 29q0 32 23.5 55t56.5 23q33 0 56.5-23t23.5-55q0-16-6-29.5T537-292l-57-56Z",
  music: "M287-167q-47-47-47-113t47-113q47-47 113-47 23 0 42.5 5.5T480-418v-382q0-17 11.5-28.5T520-840h160q17 0 28.5 11.5T720-800v80q0 17-11.5 28.5T680-680H560v400q0 66-47 113t-113 47q-66 0-113-47Z",
  game: "M182-200q-51 0-79-35.5T82-322l42-300q9-60 53.5-99T282-760h396q60 0 104.5 39t53.5 99l42 300q7 51-21 86.5T778-200q-21 0-39-7.5T706-230l-90-90H344l-90 90q-15 15-33 22.5t-39 7.5Zm16-86 114-114h336l114 114q2 2 16 6 11 0 17.5-6.5T800-304l-44-308q-4-29-26-48.5T678-680H282q-30 0-52 19.5T204-612l-44 308q-2 11 4.5 17.5T182-280q2 0 16-6Zm510.5-165.5Q720-463 720-480t-11.5-28.5Q697-520 680-520t-28.5 11.5Q640-497 640-480t11.5 28.5Q663-440 680-440t28.5-11.5Zm-80-120Q640-583 640-600t-11.5-28.5Q617-640 600-640t-28.5 11.5Q560-617 560-600t11.5 28.5Q583-560 600-560t28.5-11.5ZM480-480Zm-170-30v40q0 13 8.5 21.5T340-440q13 0 21.5-8.5T370-470v-40h40q13 0 21.5-8.5T440-540q0-13-8.5-21.5T410-570h-40v-40q0-13-8.5-21.5T340-640q-13 0-21.5 8.5T310-610v40h-40q-13 0-21.5 8.5T240-540q0 13 8.5 21.5T270-510h40Z",
  news: "M160-120q-33 0-56.5-23.5T80-200v-616q0-7 6-9.5t11 2.5l50 50 52-53q6-6 14-6t14 6l53 53 53-53q6-6 14-6t14 6l52 53 53-53q6-6 14-6t14 6l53 53 52-53q6-6 14-6t14 6l53 53 53-53q6-6 14-6t14 6l52 53 50-50q5-5 11-2.5t6 9.5v616q0 33-23.5 56.5T800-120H160Zm0-80h280v-240H160v240Zm360 0h280v-80H520v80Zm0-160h280v-80H520v80ZM160-520h640v-120H160v120Z",
  movie: "m160-800 65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h80l65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h80l65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h120q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800Zm0 240v320h640v-320H160Zm0 0v320-320Z",
  live: "M160-480q0 56 17 105.5t49 89.5q11 14 11 30.5T225-226q-12 12-29 11.5T168-229q-42-52-65-115.5T80-480q0-72 23-136t65-115q11-14 28-14.5t29 11.5q12 12 12 28.5T226-675q-32 41-49 90.5T160-480Zm160 0q0 23 6 43.5t17 38.5q9 14 8.5 31T339-338q-12 12-29 11.5T284-341q-21-29-32.5-65T240-480q0-39 11.5-74.5T284-619q10-14 26.5-14t28.5 12q12 12 12.5 28.5T343-562q-11 18-17 38.5t-6 43.5Zm103.5 56.5Q400-447 400-480t23.5-56.5Q447-560 480-560t56.5 23.5Q560-513 560-480t-23.5 56.5Q513-400 480-400t-56.5-23.5ZM640-480q0-23-6-43.5T617-562q-9-14-8-31t13-29q12-12 28-11.5t26 14.5q21 29 32.5 64.5T720-480q0 38-11.5 74T676-341q-9 14-26 14t-29-12q-12-12-12.5-28.5T617-398q11-18 17-38.5t6-43.5Zm160 0q0-56-17-105.5T734-675q-11-14-11.5-30.5T734-734q12-12 29.5-11.5T792-731q42 51 65 115t23 136q0 72-23 135.5T792-229q-11 14-28 14.5T735-226q-12-12-12-28.5t11-30.5q32-41 49-90.5T800-480Z",
  libr: "M701-535q14-9 14-25t-14-25L506-710q-15-10-30.5-1.5T460-685v250q0 18 15.5 26.5T506-410l195-125ZM320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H320Zm0-80h480v-480H320v480ZM160-80q-33 0-56.5-23.5T80-160v-520q0-17 11.5-28.5T120-720q17 0 28.5 11.5T160-680v520h520q17 0 28.5 11.5T720-120q0 17-11.5 28.5T680-80H160Zm160-720v480-480Z",
  dark: "M480-120q-151 0-255.5-104.5T120-480q0-138 90-239.5T440-838q13-2 23 3.5t16 14.5q6 9 6.5 21t-7.5 23q-17 26-25.5 55t-8.5 61q0 90 63 153t153 63q31 0 61.5-9t54.5-25q11-7 22.5-6.5T819-479q10 5 15.5 15t3.5 24q-14 138-117.5 229T480-120Zm0-80q88 0 158-48.5T740-375q-20 5-40 8t-40 3q-123 0-209.5-86.5T364-660q0-20 3-40t8-40q-78 32-126.5 102T200-480q0 116 82 198t198 82Zm-10-270Z",
  sort: "M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h160q17 0 28.5 11.5T360-280q0 17-11.5 28.5T320-240H160Zm0-200q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h400q17 0 28.5 11.5T600-480q0 17-11.5 28.5T560-440H160Zm0-200q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h640q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H160Z",
  trash: "M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h160q17 0 28.5 11.5T600-800h160q17 0 28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 56.5T680-120H280Zm400-600H280v520h400v-520ZM428.5-291.5Q440-303 440-320v-280q0-17-11.5-28.5T400-640q-17 0-28.5 11.5T360-600v280q0 17 11.5 28.5T400-280q17 0 28.5-11.5Zm160 0Q600-303 600-320v-280q0-17-11.5-28.5T560-640q-17 0-28.5 11.5T520-600v280q0 17 11.5 28.5T560-280q17 0 28.5-11.5ZM280-720v520-520Z",
  link: "M280-280q-83 0-141.5-58.5T80-480q0-83 58.5-141.5T280-680h120q17 0 28.5 11.5T440-640q0 17-11.5 28.5T400-600H280q-50 0-85 35t-35 85q0 50 35 85t85 35h120q17 0 28.5 11.5T440-320q0 17-11.5 28.5T400-280H280Zm80-160q-17 0-28.5-11.5T320-480q0-17 11.5-28.5T360-520h240q17 0 28.5 11.5T640-480q0 17-11.5 28.5T600-440H360Zm200 160q-17 0-28.5-11.5T520-320q0-17 11.5-28.5T560-360h120q50 0 85-35t35-85q0-50-35-85t-85-35H560q-17 0-28.5-11.5T520-640q0-17 11.5-28.5T560-680h120q83 0 141.5 58.5T880-480q0 83-58.5 141.5T680-280H560Z",
  verified: "m438-452-58-57q-11-11-27.5-11T324-508q-11 11-11 28t11 28l86 86q12 12 28 12t28-12l170-170q12-12 11.5-28T636-592q-12-12-28.5-12.5T579-593L438-452ZM326-90l-58-98-110-24q-15-3-24-15.5t-7-27.5l11-113-75-86q-10-11-10-26t10-26l75-86-11-113q-2-15 7-27.5t24-15.5l110-24 58-98q8-13 22-17.5t28 1.5l104 44 104-44q14-6 28-1.5t22 17.5l58 98 110 24q15 3 24 15.5t7 27.5l-11 113 75 86q10 11 10 26t-10 26l-75 86 11 113q2 15-7 27.5T802-212l-110 24-58 98q-8 13-22 17.5T584-74l-104-44-104 44q-14 6-28 1.5T326-90Zm52-72 102-44 104 44 56-96 110-26-10-112 74-84-74-86 10-112-110-24-58-96-102 44-104-44-56 96-110 24 10 112-74 86 74 84-10 114 110 24 58 96Zm102-318Z",
  add: "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760q17 0 28.5 11.5T520-720v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v-200Z",
  notes: "M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h400q17 0 28.5 11.5T600-280q0 17-11.5 28.5T560-240H160Zm0-200q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm0-200q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h640q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H160Z",
  open: "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h240q17 0 28.5 11.5T480-800q0 17-11.5 28.5T440-760H200v560h560v-240q0-17 11.5-28.5T800-480q17 0 28.5 11.5T840-440v240q0 33-23.5 56.5T760-120H200Zm560-584L416-360q-11 11-28 11t-28-11q-11-11-11-28t11-28l344-344H600q-17 0-28.5-11.5T560-800q0-17 11.5-28.5T600-840h200q17 0 28.5 11.5T840-800v200q0 17-11.5 28.5T800-560q-17 0-28.5-11.5T760-600v-104Z",
  keyboard: "M160-200q-33 0-56.5-23.5T80-280v-400q0-33 23.5-56.5T160-760h640q33 0 56.5 23.5T880-680v400q0 33-23.5 56.5T800-200H160Zm0-80h640v-400H160v400Zm200-40h240q17 0 28.5-11.5T640-360q0-17-11.5-28.5T600-400H360q-17 0-28.5 11.5T320-360q0 17 11.5 28.5T360-320Zm-200 40v-400 400Zm108.5-291.5Q280-583 280-600t-11.5-28.5Q257-640 240-640t-28.5 11.5Q200-617 200-600t11.5 28.5Q223-560 240-560t28.5-11.5Zm120 0Q400-583 400-600t-11.5-28.5Q377-640 360-640t-28.5 11.5Q320-617 320-600t11.5 28.5Q343-560 360-560t28.5-11.5Zm120 0Q520-583 520-600t-11.5-28.5Q497-640 480-640t-28.5 11.5Q440-617 440-600t11.5 28.5Q463-560 480-560t28.5-11.5Zm120 0Q640-583 640-600t-11.5-28.5Q617-640 600-640t-28.5 11.5Q560-617 560-600t11.5 28.5Q583-560 600-560t28.5-11.5Zm120 0Q760-583 760-600t-11.5-28.5Q737-640 720-640t-28.5 11.5Q680-617 680-600t11.5 28.5Q703-560 720-560t28.5-11.5Zm-480 120Q280-463 280-480t-11.5-28.5Q257-520 240-520t-28.5 11.5Q200-497 200-480t11.5 28.5Q223-440 240-440t28.5-11.5Zm120 0Q400-463 400-480t-11.5-28.5Q377-520 360-520t-28.5 11.5Q320-497 320-480t11.5 28.5Q343-440 360-440t28.5-11.5Zm120 0Q520-463 520-480t-11.5-28.5Q497-520 480-520t-28.5 11.5Q440-497 440-480t11.5 28.5Q463-440 480-440t28.5-11.5Zm120 0Q640-463 640-480t-11.5-28.5Q617-520 600-520t-28.5 11.5Q560-497 560-480t11.5 28.5Q583-440 600-440t28.5-11.5Zm120 0Q760-463 760-480t-11.5-28.5Q737-520 720-520t-28.5 11.5Q680-497 680-480t11.5 28.5Q703-440 720-440t28.5-11.5Z",
  pip: "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm0-80h640v-480H160v480Zm0 0v-480 480Zm320-40h240q17 0 28.5-11.5T760-320v-160q0-17-11.5-28.5T720-520H480q-17 0-28.5 11.5T440-480v160q0 17 11.5 28.5T480-280Zm40-80v-80h160v80H520Z",
  expand: "m480-236 93-93q12-12 29-12t29 12q12 12 12 29t-12 29L508-148q-6 6-13 8.5t-15 2.5q-8 0-15-2.5t-13-8.5L329-271q-12-12-12-29t12-29q12-12 29-12t29 12l93 93Zm0-484-93 93q-12 12-29 12t-29-12q-12-12-12-29t12-29l123-123q6-6 13-8.5t15-2.5q8 0 15 2.5t13 8.5l123 123q12 12 12 29t-12 29q-12 12-29 12t-29-12l-93-93Z",
  chat: "m240-240-92 92q-19 19-43.5 8.5T80-177v-623q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240Zm-34-80h594v-480H160v525l46-45Zm-46 0v-480 480Z",
  heart: "M451.5-152q-14.5-5-25.5-16l-69-63q-106-97-191.5-192.5T80-634q0-94 63-157t157-63q53 0 100 22.5t80 61.5q33-39 80-61.5T660-854q94 0 157 63t63 157q0 115-85 211T602-230l-68 62q-11 11-25.5 16t-28.5 5q-14 0-28.5-5ZM442-690q-29-41-62-62.5T300-774q-60 0-100 40t-40 100q0 52 37 110.5T285.5-410q51.5 55 106 103t88.5 79q34-31 88.5-79t106-103Q726-465 763-523.5T800-634q0-60-40-100t-100-40q-47 0-80 21.5T518-690q-7 10-17 15t-21 5q-11 0-21-5t-17-15Zm38 189Z",
  pin: "M640-760v280l68 68q6 6 9 13.5t3 15.5v23q0 17-11.5 28.5T680-320H520v234q0 17-11.5 28.5T480-46q-17 0-28.5-11.5T440-86v-234H280q-17 0-28.5-11.5T240-360v-23q0-8 3-15.5t9-13.5l68-68v-280q-17 0-28.5-11.5T280-800q0-17 11.5-28.5T320-840h320q17 0 28.5 11.5T680-800q0 17-11.5 28.5T640-760ZM354-400h252l-46-46v-314H400v314l-46 46Zm126 0Z",
  sports: "M440-200v-124q-49-11-87.5-41.5T296-442q-75-9-125.5-65.5T120-640v-40q0-33 23.5-56.5T200-760h80q0-33 23.5-56.5T360-840h240q33 0 56.5 23.5T680-760h80q33 0 56.5 23.5T840-680v40q0 76-50.5 132.5T664-442q-18 46-56.5 76.5T520-324v124h120q17 0 28.5 11.5T680-160q0 17-11.5 28.5T640-120H320q-17 0-28.5-11.5T280-160q0-17 11.5-28.5T320-200h120ZM280-528v-152h-80v40q0 38 22 68.5t58 43.5Zm285 93q35-35 35-85v-240H360v240q0 50 35 85t85 35q50 0 85-35Zm115-93q36-13 58-43.5t22-68.5v-40h-80v152Zm-200-52Z",
  flag: "M280-400v240q0 17-11.5 28.5T240-120q-17 0-28.5-11.5T200-160v-600q0-17 11.5-28.5T240-800h287q14 0 25 9t14 23l10 48h184q17 0 28.5 11.5T800-680v320q0 17-11.5 28.5T760-320H553q-14 0-25-9t-14-23l-10-48H280Zm306 0h134v-240H543q-14 0-25-9t-14-23l-10-48H280v240h257q14 0 25 9t14 23l10 48Zm-86-160Z",
  skip: "M100-315v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T428-480q0 10-4.5 18.5T410-447L162-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Zm400 0v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T828-480q0 10-4.5 18.5T810-447L562-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29ZM180-480Zm400 0Zm-400 90 136-90-136-90v180Zm400 0 136-90-136-90v180Z",
  sleep: "M484-80q-84 0-157.5-32t-128-86.5Q144-253 112-326.5T80-484q0-128 72-232t193-146q22-8 41 5.5t18 36.5q-3 85 27 162t90 137q60 60 137 90t162 27q26-1 38.5 17.5T863-345q-44 120-147.5 192.5T484-80Zm0-80q88 0 163-44t118-121q-86-8-163-43.5T464-465q-61-61-97-138t-43-163q-77 43-120.5 118.5T160-484q0 135 94.5 229.5T484-160Zm-20-305Z",
  upload: "M240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Zm200-486-75 75q-12 12-28.5 11.5T308-572q-11-12-11.5-28t11.5-28l144-144q6-6 13-8.5t15-2.5q8 0 15 2.5t13 8.5l144 144q12 12 11.5 28T652-572q-12 12-28.5 12.5T595-571l-75-75v286q0 17-11.5 28.5T480-320q-17 0-28.5-11.5T440-360v-286Z",
  globe: "M325-111.5q-73-31.5-127.5-86t-86-127.5Q80-398 80-480.5t31.5-155q31.5-72.5 86-127t127.5-86Q398-880 480.5-880t155 31.5q72.5 31.5 127 86t86 127Q880-563 880-480.5T848.5-325q-31.5 73-86 127.5t-127 86Q563-80 480.5-80T325-111.5ZM480-162q26-36 45-75t31-83H404q12 44 31 83t45 75Zm-104-16q-18-33-31.5-68.5T322-320H204q29 50 72.5 87t99.5 55Zm208 0q56-18 99.5-55t72.5-87H638q-9 38-22.5 73.5T584-178ZM170-400h136q-3-20-4.5-39.5T300-480q0-21 1.5-40.5T306-560H170q-5 20-7.5 39.5T160-480q0 21 2.5 40.5T170-400Zm216 0h188q3-20 4.5-39.5T580-480q0-21-1.5-40.5T574-560H386q-3 20-4.5 39.5T380-480q0 21 1.5 40.5T386-400Zm268 0h136q5-20 7.5-39.5T800-480q0-21-2.5-40.5T790-560H654q3 20 4.5 39.5T660-480q0 21-1.5 40.5T654-400Zm-16-240h118q-29-50-72.5-87T584-782q18 33 31.5 68.5T638-640Zm-234 0h152q-12-44-31-83t-45-75q-26 36-45 75t-31 83Zm-200 0h118q9-38 22.5-73.5T376-782q-56 18-99.5 55T204-640Z",
  tv: "M160-200q-33 0-56.5-23.5T80-280v-480q0-33 23.5-56.5T160-840h640q33 0 56.5 23.5T880-760v480q0 33-23.5 56.5T800-200H640v40q0 17-11.5 28.5T600-120H360q-17 0-28.5-11.5T320-160v-40H160Zm0-80h640v-480H160v480Zm0 0v-480 480Z",
  radio: "M160-80q-33 0-56.5-23.5T80-160v-480q0-25 13.5-45t36.5-29l473-193q14-5 27.5.5T649-887q5 14-.5 27.5T629-841L332-720h468q33 0 56.5 23.5T880-640v480q0 33-23.5 56.5T800-80H160Zm0-80h640v-280H160v280Zm231-69q29-29 29-71t-29-71q-29-29-71-29t-71 29q-29 29-29 71t29 71q29 29 71 29t71-29ZM160-520h480v-40q0-17 11.5-28.5T680-600q17 0 28.5 11.5T720-560v40h80v-120H160v120Zm0 360v-280 280Z",
  album: "M480-300q75 0 127.5-52.5T660-480q0-75-52.5-127.5T480-660q-75 0-127.5 52.5T300-480q0 75 52.5 127.5T480-300Zm-28.5-151.5Q440-463 440-480t11.5-28.5Q463-520 480-520t28.5 11.5Q520-497 520-480t-11.5 28.5Q497-440 480-440t-28.5-11.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
  person: "M367-527q-47-47-47-113t47-113q47-47 113-47t113 47q47 47 47 113t-47 113q-47 47-113 47t-113-47ZM160-240v-32q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v32q0 33-23.5 56.5T720-160H240q-33 0-56.5-23.5T160-240Zm80 0h480v-32q0-11-5.5-20T700-306q-54-27-109-40.5T480-360q-56 0-111 13.5T260-306q-9 5-14.5 14t-5.5 20v32Zm296.5-343.5Q560-607 560-640t-23.5-56.5Q513-720 480-720t-56.5 23.5Q400-673 400-640t23.5 56.5Q447-560 480-560t56.5-23.5ZM480-640Zm0 400Z",
  qmusic: "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-288q0-17 11.5-28.5T720-720h120q17 0 28.5 11.5T880-680q0 17-11.5 28.5T840-640h-80v360q0 50-35 85t-85 35ZM160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Z",
  down: "M465-363.5q-7-2.5-13-8.5L268-556q-11-11-11-28t11-28q11-11 28-11t28 11l156 156 156-156q11-11 28-11t28 11q11 11 11 28t-11 28L508-372q-6 6-13 8.5t-15 2.5q-8 0-15-2.5Z",
  lyrics: "M280-400h80q17 0 28.5-11.5T400-440q0-17-11.5-28.5T360-480h-80q-17 0-28.5 11.5T240-440q0 17 11.5 28.5T280-400Zm480-80q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 2t19 5v-167q0-17 11.5-28.5T840-920h80q17 0 28.5 11.5T960-880q0 17-11.5 28.5T920-840h-40v240q0 50-35 85t-85 35Zm-480-40h200q17 0 28.5-11.5T520-560q0-17-11.5-28.5T480-600H280q-17 0-28.5 11.5T240-560q0 17 11.5 28.5T280-520Zm0-120h200q17 0 28.5-11.5T520-680q0-17-11.5-28.5T480-720H280q-17 0-28.5 11.5T240-680q0 17 11.5 28.5T280-640Zm320 400H240l-92 92q-6 6-13 9t-15 3q-16 0-28-11.5T80-177v-623q0-33 23.5-56.5T160-880h440q33 0 56.5 23.5T680-800v17q-24 11-44 27t-36 36v-80H160v527l47-47h393v-160q16 20 36 36t44 27v97q0 33-23.5 56.5T600-240Zm-440-80v-480 480Z",
  loop1: "M460-540h-30q-13 0-21.5-8.5T400-570q0-13 8.5-21.5T430-600h50q17 0 28.5 11.5T520-560v170q0 13-8.5 21.5T490-360q-13 0-21.5-8.5T460-390v-150ZM274-200l34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z",
  eq: "M280-280v-400q0-17 11.5-28.5T320-720q17 0 28.5 11.5T360-680v400q0 17-11.5 28.5T320-240q-17 0-28.5-11.5T280-280Zm160 160v-720q0-17 11.5-28.5T480-880q17 0 28.5 11.5T520-840v720q0 17-11.5 28.5T480-80q-17 0-28.5-11.5T440-120ZM120-440v-80q0-17 11.5-28.5T160-560q17 0 28.5 11.5T200-520v80q0 17-11.5 28.5T160-400q-17 0-28.5-11.5T120-440Zm480 160v-400q0-17 11.5-28.5T640-720q17 0 28.5 11.5T680-680v400q0 17-11.5 28.5T640-240q-17 0-28.5-11.5T600-280Zm160-160v-80q0-17 11.5-28.5T800-560q17 0 28.5 11.5T840-520v80q0 17-11.5 28.5T800-400q-17 0-28.5-11.5T760-440Z",
  sun: "M480-360q50 0 85-35t35-85q0-50-35-85t-85-35q-50 0-85 35t-35 85q0 50 35 85t85 35Zm0 80q-83 0-141.5-58.5T280-480q0-83 58.5-141.5T480-680q83 0 141.5 58.5T680-480q0 83-58.5 141.5T480-280ZM200-440H40v-80h160v80Zm720 0H760v-80h160v80ZM440-760v-160h80v160h-80Zm0 720v-160h80v160h-80ZM256-650l-101-97 57-59 96 100-52 56Zm492 496-97-101 53-55 101 97-57 59Zm-98-550 97-101 59 57-100 96-56-52ZM154-212l101-97 55 53-97 101-59-57Zm326-268Z",
  phone: "M280-40q-33 0-56.5-23.5T200-120v-720q0-33 23.5-56.5T280-920h400q33 0 56.5 23.5T760-840v720q0 33-23.5 56.5T680-40H280Zm0-120v40h400v-40H280Zm0-80h400v-480H280v480Zm0-560h400v-40H280v40Zm0 0v-40 40Zm0 640v40-40Z",
  sparkle: "m744-576-30-66-66-30 66-30 30-66 30 66 66 30-66 30-30 66Zm0 504-30-66-66-30 66-30 30-66 30 66 66 30-66 30-30 66ZM336-192l-60-132-132-60 132-60 60-132 60 132 132 60-132 60-60 132Zm0-182 24-50 50-24-50-24-24-50-24 50-50 24 50 24 24 50Zm0-74Z",
};
const F = {
  menu: "M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h640q17 0 28.5 11.5T840-280q0 17-11.5 28.5T800-240H160Zm0-200q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm0-200q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h640q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H160Z",
  search: "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z",
  mic: "M395-435q-35-35-35-85v-240q0-50 35-85t85-35q50 0 85 35t35 85v240q0 50-35 85t-85 35q-50 0-85-35Zm45 275v-83q-92-13-157.5-78T203-479q-2-17 9-29t28-12q17 0 28.5 11.5T284-480q14 70 69.5 115T480-320q72 0 127-45.5T676-480q4-17 15.5-28.5T720-520q17 0 28 12t9 29q-14 91-79 157t-158 79v83q0 17-11.5 28.5T480-120q-17 0-28.5-11.5T440-160Z",
  home: "M160-200v-360q0-19 8.5-36t23.5-28l240-180q21-16 48-16t48 16l240 180q15 11 23.5 28t8.5 36v360q0 33-23.5 56.5T720-120H600q-17 0-28.5-11.5T560-160v-200q0-17-11.5-28.5T520-400h-80q-17 0-28.5 11.5T400-360v200q0 17-11.5 28.5T360-120H240q-33 0-56.5-23.5T160-200Z",
  shorts: "M360-360H236q-24 0-35.5-21.5T203-423l299-430q10-14 26-19.5t33 .5q17 6 25 21t6 32l-32 259h155q26 0 36.5 23t-6.5 43L416-100q-11 13-27 17t-31-3q-15-7-23.5-21.5T328-139l32-221Z",
  subs: "M160-80q-33 0-56.5-23.5T80-160v-400q0-33 23.5-56.5T160-640h640q33 0 56.5 23.5T880-560v400q0 33-23.5 56.5T800-80H160Zm271-141 184-122q9-6 9-17t-9-17L431-499q-10-7-20.5-1.5T400-483v246q0 12 10.5 17.5T431-221ZM200-680q-17 0-28.5-11.5T160-720q0-17 11.5-28.5T200-760h560q17 0 28.5 11.5T800-720q0 17-11.5 28.5T760-680H200Zm120-120q-17 0-28.5-11.5T280-840q0-17 11.5-28.5T320-880h320q17 0 28.5 11.5T680-840q0 17-11.5 28.5T640-800H320Z",
  history: "M480-120q-126 0-223-76.5T131-392q-4-15 6-27.5t27-14.5q16-2 29 6t18 24q24 90 99 147t170 57q117 0 198.5-81.5T760-480q0-117-81.5-198.5T480-760q-69 0-129 32t-101 88h70q17 0 28.5 11.5T360-600q0 17-11.5 28.5T320-560H160q-17 0-28.5-11.5T120-600v-160q0-17 11.5-28.5T160-800q17 0 28.5 11.5T200-760v54q51-64 124.5-99T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-120Zm40-376 100 100q11 11 11 28t-11 28q-11 11-28 11t-28-11L452-452q-6-6-9-13.5t-3-15.5v-159q0-17 11.5-28.5T480-680q17 0 28.5 11.5T520-640v144Z",
  playlists: "M160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Zm511 499q-5 3-10 3t-10-2q-5-2-8-6.5t-3-10.5v-246q0-6 3-10.5t8-6.5q5-2 10-2t10 3l184 122q5 3 7 7.5t2 9.5q0 5-2 9.5t-7 7.5L671-141Z",
  later: "M520-496v-144q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v159q0 8 3 15.5t9 13.5l132 132q11 11 28 11t28-11q11-11 11-28t-11-28L520-496ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z",
  like: "M840-640q32 0 56 24t24 56v80q0 7-1.5 15t-4.5 15L794-168q-9 20-30 34t-44 14H400q-33 0-56.5-23.5T320-200v-407q0-16 6.5-30.5T344-663l217-216q15-14 35.5-17t39.5 7q19 10 27.5 28t3.5 37l-45 184h218ZM160-120q-33 0-56.5-23.5T80-200v-360q0-33 23.5-56.5T160-640q33 0 56.5 23.5T240-560v360q0 33-23.5 56.5T160-120Z",
  dislike: "M120-320q-32 0-56-24t-24-56v-80q0-7 1.5-15t4.5-15l120-282q9-20 30-34t44-14h320q33 0 56.5 23.5T640-760v407q0 16-6.5 30.5T616-297L399-81q-15 14-35.5 17T324-71q-19-10-27.5-28t-3.5-37l45-184H120Zm680-520q33 0 56.5 23.5T880-760v360q0 33-23.5 56.5T800-320q-33 0-56.5-23.5T720-400v-360q0-33 23.5-56.5T800-840Z",
  share: "M680-80q-50 0-85-35t-35-85q0-6 3-28L282-392q-16 15-37 23.5t-45 8.5q-50 0-85-35t-35-85q0-50 35-85t85-35q24 0 45 8.5t37 23.5l281-164q-2-7-2.5-13.5T560-760q0-50 35-85t85-35q50 0 85 35t35 85q0 50-35 85t-85 35q-24 0-45-8.5T598-672L317-508q2 7 2.5 13.5t.5 14.5q0 8-.5 14.5T317-452l281 164q16-15 37-23.5t45-8.5q50 0 85 35t35 85q0 50-35 85t-85 35Z",
  download: "M465-339.5q-7-2.5-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T480-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5q-8 0-15-2.5ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z",
  save: "M160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h200q17 0 28.5 11.5T400-360q0 17-11.5 28.5T360-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h360q17 0 28.5 11.5T560-520q0 17-11.5 28.5T520-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h360q17 0 28.5 11.5T560-680q0 17-11.5 28.5T520-640H160Zm491.5 468.5Q640-183 640-200v-120H520q-17 0-28.5-11.5T480-360q0-17 11.5-28.5T520-400h120v-120q0-17 11.5-28.5T680-560q17 0 28.5 11.5T720-520v120h120q17 0 28.5 11.5T880-360q0 17-11.5 28.5T840-320H720v120q0 17-11.5 28.5T680-160q-17 0-28.5-11.5Z",
  more: "M480-160q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T480-160Zm0-240q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm0-240q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0 33-23.5 56.5T480-640Z",
  moreh: "M240-400q-33 0-56.5-23.5T160-480q0-33 23.5-56.5T240-560q33 0 56.5 23.5T320-480q0 33-23.5 56.5T240-400Zm240 0q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm240 0q-33 0-56.5-23.5T640-480q0-33 23.5-56.5T720-560q33 0 56.5 23.5T800-480q0 33-23.5 56.5T720-400Z",
  play: "M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Z",
  pause: "M640-200q-33 0-56.5-23.5T560-280v-400q0-33 23.5-56.5T640-760q33 0 56.5 23.5T720-680v400q0 33-23.5 56.5T640-200Zm-320 0q-33 0-56.5-23.5T240-280v-400q0-33 23.5-56.5T320-760q33 0 56.5 23.5T400-680v400q0 33-23.5 56.5T320-200Z",
  next: "M660-280v-400q0-17 11.5-28.5T700-720q17 0 28.5 11.5T740-680v400q0 17-11.5 28.5T700-240q-17 0-28.5-11.5T660-280Zm-440-35v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T548-480q0 10-4.5 18.5T530-447L282-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Z",
  prev: "M220-280v-400q0-17 11.5-28.5T260-720q17 0 28.5 11.5T300-680v400q0 17-11.5 28.5T260-240q-17 0-28.5-11.5T220-280Zm458-1L430-447q-9-6-13.5-14.5T412-480q0-10 4.5-18.5T430-513l248-166q5-4 11-5t11-1q16 0 28 11t12 29v330q0 18-12 29t-28 11q-5 0-11-1t-11-5Z",
  vol: "M760-481q0-83-44-151.5T598-735q-15-7-22-21.5t-2-29.5q6-16 21.5-23t31.5 0q97 43 155 131.5T840-481q0 108-58 196.5T627-153q-16 7-31.5 0T574-176q-5-15 2-29.5t22-21.5q74-34 118-102.5T760-481ZM280-360H160q-17 0-28.5-11.5T120-400v-160q0-17 11.5-28.5T160-600h120l132-132q19-19 43.5-8.5T480-703v446q0 27-24.5 37.5T412-228L280-360Zm380-120q0 42-19 79.5T591-339q-10 6-20.5.5T560-356v-250q0-12 10.5-17.5t20.5.5q31 25 50 63t19 80Z",
  vollow: "M360-360H240q-17 0-28.5-11.5T200-400v-160q0-17 11.5-28.5T240-600h120l132-132q19-19 43.5-8.5T560-703v446q0 27-24.5 37.5T492-228L360-360Zm380-120q0 42-19 79.5T671-339q-10 6-20.5.5T640-356v-250q0-12 10.5-17.5t20.5.5q31 25 50 63t19 80Z",
  mute: "M671-177q-11 7-22 13t-23 11q-15 7-30.5 0T574-176q-6-15 1.5-29.5T598-227q4-2 7.5-4t7.5-4L480-368v111q0 27-24.5 37.5T412-228L280-360H160q-17 0-28.5-11.5T120-400v-160q0-17 11.5-28.5T160-600h88L84-764q-11-11-11-28t11-28q11-11 28-11t28 11l680 680q11 11 11 28t-11 28q-11 11-28 11t-28-11l-93-93Zm89-304q0-83-44-151.5T598-735q-15-7-22-21.5t-2-29.5q6-16 21.5-23t31.5 0q97 43 155 131t58 197q0 33-6 65.5T817-353q-8 22-24.5 27.5t-30.5.5q-14-5-22.5-18t-.5-30q11-26 16-52.5t5-55.5ZM591-623q33 21 51 63t18 80v10q0 5-1 10-2 13-14 17t-22-6l-51-51q-6-6-9-13.5t-3-15.5v-77q0-12 10.5-17.5t20.5.5Zm-201-59q-6-6-6-14t6-14l22-22q19-19 43.5-8.5T480-703v63q0 14-12 19t-22-5l-56-56Z",
  cc: "M200-160q-33 0-56.5-23.5T120-240v-480q0-33 23.5-56.5T200-800h560q33 0 56.5 23.5T840-720v480q0 33-23.5 56.5T760-160H200Zm80-200h120q17 0 28.5-11.5T440-400v-20q0-9-6-15t-15-6h-18q-9 0-15 6t-6 15h-80v-120h80q0 9 6 15t15 6h18q9 0 15-6t6-15v-20q0-17-11.5-28.5T400-600H280q-17 0-28.5 11.5T240-560v160q0 17 11.5 28.5T280-360Zm400-240H560q-17 0-28.5 11.5T520-560v160q0 17 11.5 28.5T560-360h120q17 0 28.5-11.5T720-400v-20q0-9-6-15t-15-6h-18q-9 0-15 6t-6 15h-80v-120h80q0 9 6 15t15 6h18q9 0 15-6t6-15v-20q0-17-11.5-28.5T680-600Z",
  gear: "M433-80q-27 0-46.5-18T363-142l-9-66q-13-5-24.5-12T307-235l-62 26q-25 11-50 2t-39-32l-47-82q-14-23-8-49t27-43l53-40q-1-7-1-13.5v-27q0-6.5 1-13.5l-53-40q-21-17-27-43t8-49l47-82q14-23 39-32t50 2l62 26q11-8 23-15t24-12l9-66q4-26 23.5-44t46.5-18h94q27 0 46.5 18t23.5 44l9 66q13 5 24.5 12t22.5 15l62-26q25-11 50-2t39 32l47 82q14 23 8 49t-27 43l-53 40q1 7 1 13.5v27q0 6.5-2 13.5l53 40q21 17 27 43t-8 49l-48 82q-14 23-39 32t-50-2l-60-26q-11 8-23 15t-24 12l-9 66q-4 26-23.5 44T527-80h-94Zm49-260q58 0 99-41t41-99q0-58-41-99t-99-41q-59 0-99.5 41T342-480q0 58 40.5 99t99.5 41Z",
  fs: "M200-200h80q17 0 28.5 11.5T320-160q0 17-11.5 28.5T280-120H160q-17 0-28.5-11.5T120-160v-120q0-17 11.5-28.5T160-320q17 0 28.5 11.5T200-280v80Zm560 0v-80q0-17 11.5-28.5T800-320q17 0 28.5 11.5T840-280v120q0 17-11.5 28.5T800-120H680q-17 0-28.5-11.5T640-160q0-17 11.5-28.5T680-200h80ZM200-760v80q0 17-11.5 28.5T160-640q-17 0-28.5-11.5T120-680v-120q0-17 11.5-28.5T160-840h120q17 0 28.5 11.5T320-800q0 17-11.5 28.5T280-760h-80Zm560 0h-80q-17 0-28.5-11.5T640-800q0-17 11.5-28.5T680-840h120q17 0 28.5 11.5T840-800v120q0 17-11.5 28.5T800-640q-17 0-28.5-11.5T760-680v-80Z",
  fsx: "M240-240h-80q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h120q17 0 28.5 11.5T320-280v120q0 17-11.5 28.5T280-120q-17 0-28.5-11.5T240-160v-80Zm480 0v80q0 17-11.5 28.5T680-120q-17 0-28.5-11.5T640-160v-120q0-17 11.5-28.5T680-320h120q17 0 28.5 11.5T840-280q0 17-11.5 28.5T800-240h-80ZM240-720v-80q0-17 11.5-28.5T280-840q17 0 28.5 11.5T320-800v120q0 17-11.5 28.5T280-640H160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h80Zm480 0h80q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H680q-17 0-28.5-11.5T640-680v-120q0-17 11.5-28.5T680-840q17 0 28.5 11.5T720-800v80Z",
  theater: "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm0-80h80v-480h-80v480Zm560 0h80v-480h-80v480Z",
  theaterx: "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm0-80h160v-480H160v480Zm480 0h160v-480H640v480Z",
  mini: "M440-280h280q17 0 28.5-11.5T760-320v-160q0-17-11.5-28.5T720-520H440q-17 0-28.5 11.5T400-480v160q0 17 11.5 28.5T440-280ZM160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Z",
  close: "M480-424 284-228q-11 11-28 11t-28-11q-11-11-11-28t11-28l196-196-196-196q-11-11-11-28t11-28q11-11 28-11t28 11l196 196 196-196q11-11 28-11t28 11q11 11 11 28t-11 28L536-480l196 196q11 11 11 28t-11 28q-11 11-28 11t-28-11L480-424Z",
  check: "m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z",
  right: "M504-480 348-636q-11-11-11-28t11-28q11-11 28-11t28 11l184 184q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L404-268q-11 11-28 11t-28-11q-11-11-11-28t11-28l156-156Z",
  left: "m432-480 156 156q11 11 11 28t-11 28q-11 11-28 11t-28-11L348-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l184-184q11-11 28-11t28 11q11 11 11 28t-11 28L432-480Z",
  back: "m313-440 196 196q12 12 11.5 28T508-188q-12 11-28 11.5T452-188L188-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l264-264q11-11 27.5-11t28.5 11q12 12 12 28.5T508-715L313-520h447q17 0 28.5 11.5T800-480q0 17-11.5 28.5T760-440H313Z",
  replay: "M339.5-108.5q-65.5-28.5-114-77t-77-114Q120-365 120-440q0-17 11.5-28.5T160-480q17 0 28.5 11.5T200-440q0 117 81.5 198.5T480-160q117 0 198.5-81.5T760-440q0-117-81.5-198.5T480-720h-6l34 34q12 12 11.5 28T508-630q-12 12-28.5 12.5T451-629L348-732q-12-12-12-28t12-28l103-103q12-12 28.5-11.5T508-890q11 12 11.5 28T508-834l-34 34h6q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-440q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-80q-75 0-140.5-28.5Z",
  loop: "m274-200 34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z",
  shuffle: "M600-160q-17 0-28.5-11.5T560-200q0-17 11.5-28.5T600-240h64l-99-99q-12-12-11.5-28.5T566-396q12-12 28.5-12t28.5 12l97 98v-62q0-17 11.5-28.5T760-400q17 0 28.5 11.5T800-360v160q0 17-11.5 28.5T760-160H600Zm-428-12q-11-11-11-28t11-28l492-492h-64q-17 0-28.5-11.5T560-760q0-17 11.5-28.5T600-800h160q17 0 28.5 11.5T800-760v160q0 17-11.5 28.5T760-560q-17 0-28.5-11.5T720-600v-64L228-172q-11 11-28 11t-28-11Zm-1-560q-11-11-11-28t11-28q11-11 27.5-11t28.5 11l168 167q11 11 11.5 27.5T395-565q-11 11-28 11t-28-11L171-732Z",
  speed: "M418-340q25 25 63 23.5t55-27.5l169-253q9-14-2.5-25.5T677-625L424-456q-26 18-28.5 54.5T418-340ZM204-160q-22 0-40.5-9.5T134-198q-26-47-40-97.5T80-400q0-83 31.5-156T197-683q54-54 127-85.5T480-800q82 0 154 31t126 84.5q54 53.5 86 125T879-406q1 55-12.5 107.5T825-198q-11 19-29.5 28.5T755-160H204Z",
  tune: "M451.5-131.5Q440-143 440-160v-160q0-17 11.5-28.5T480-360q17 0 28.5 11.5T520-320v40h280q17 0 28.5 11.5T840-240q0 17-11.5 28.5T800-200H520v40q0 17-11.5 28.5T480-120q-17 0-28.5-11.5ZM160-200q-17 0-28.5-11.5T120-240q0-17 11.5-28.5T160-280h160q17 0 28.5 11.5T360-240q0 17-11.5 28.5T320-200H160Zm131.5-171.5Q280-383 280-400v-40H160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h120v-40q0-17 11.5-28.5T320-600q17 0 28.5 11.5T360-560v160q0 17-11.5 28.5T320-360q-17 0-28.5-11.5ZM480-440q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520h320q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H480Zm131.5-171.5Q600-623 600-640v-160q0-17 11.5-28.5T640-840q17 0 28.5 11.5T680-800v40h120q17 0 28.5 11.5T840-720q0 17-11.5 28.5T800-680H680v40q0 17-11.5 28.5T640-600q-17 0-28.5-11.5ZM160-680q-17 0-28.5-11.5T120-720q0-17 11.5-28.5T160-760h320q17 0 28.5 11.5T520-720q0 17-11.5 28.5T480-680H160Z",
  timer: "M400-840q-17 0-28.5-11.5T360-880q0-17 11.5-28.5T400-920h160q17 0 28.5 11.5T600-880q0 17-11.5 28.5T560-840H400Zm108.5 428.5Q520-423 520-440v-160q0-17-11.5-28.5T480-640q-17 0-28.5 11.5T440-600v160q0 17 11.5 28.5T480-400q17 0 28.5-11.5Zm-168 303Q275-137 226-186t-77.5-114.5Q120-366 120-440t28.5-139.5Q177-645 226-694t114.5-77.5Q406-800 480-800q62 0 119 20t107 58l28-28q11-11 28-11t28 11q11 11 11 28t-11 28l-28 28q38 50 58 107t20 119q0 74-28.5 139.5T734-186q-49 49-114.5 77.5T480-80q-74 0-139.5-28.5Z",
  ambient: "M106-386q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-160q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm105.5 334.5Q200-223 200-240t11.5-28.5Q223-280 240-280t28.5 11.5Q280-257 280-240t-11.5 28.5Q257-200 240-200t-28.5-11.5Zm0-160Q200-383 200-400t11.5-28.5Q223-440 240-440t28.5 11.5Q280-417 280-400t-11.5 28.5Q257-360 240-360t-28.5-11.5Zm0-160Q200-543 200-560t11.5-28.5Q223-600 240-600t28.5 11.5Q280-577 280-560t-11.5 28.5Q257-520 240-520t-28.5-11.5Zm0-160Q200-703 200-720t11.5-28.5Q223-760 240-760t28.5 11.5Q280-737 280-720t-11.5 28.5Q257-680 240-680t-28.5-11.5Zm146 334Q340-375 340-400t17.5-42.5Q375-460 400-460t42.5 17.5Q460-425 460-400t-17.5 42.5Q425-340 400-340t-42.5-17.5Zm0-160Q340-535 340-560t17.5-42.5Q375-620 400-620t42.5 17.5Q460-585 460-560t-17.5 42.5Q425-500 400-500t-42.5-17.5Zm14 306Q360-223 360-240t11.5-28.5Q383-280 400-280t28.5 11.5Q440-257 440-240t-11.5 28.5Q417-200 400-200t-28.5-11.5Zm0-480Q360-703 360-720t11.5-28.5Q383-760 400-760t28.5 11.5Q440-737 440-720t-11.5 28.5Q417-680 400-680t-28.5-11.5ZM386-106q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-720q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm131.5 468.5Q500-375 500-400t17.5-42.5Q535-460 560-460t42.5 17.5Q620-425 620-400t-17.5 42.5Q585-340 560-340t-42.5-17.5Zm0-160Q500-535 500-560t17.5-42.5Q535-620 560-620t42.5 17.5Q620-585 620-560t-17.5 42.5Q585-500 560-500t-42.5-17.5Zm14 306Q520-223 520-240t11.5-28.5Q543-280 560-280t28.5 11.5Q600-257 600-240t-11.5 28.5Q577-200 560-200t-28.5-11.5Zm0-480Q520-703 520-720t11.5-28.5Q543-760 560-760t28.5 11.5Q600-737 600-720t-11.5 28.5Q577-680 560-680t-28.5-11.5ZM546-106q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-720q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm145.5 614.5Q680-223 680-240t11.5-28.5Q703-280 720-280t28.5 11.5Q760-257 760-240t-11.5 28.5Q737-200 720-200t-28.5-11.5Zm0-160Q680-383 680-400t11.5-28.5Q703-440 720-440t28.5 11.5Q760-417 760-400t-11.5 28.5Q737-360 720-360t-28.5-11.5Zm0-160Q680-543 680-560t11.5-28.5Q703-600 720-600t28.5 11.5Q760-577 760-560t-11.5 28.5Q737-520 720-520t-28.5-11.5Zm0-160Q680-703 680-720t11.5-28.5Q703-760 720-760t28.5 11.5Q760-737 760-720t-11.5 28.5Q737-680 720-680t-28.5-11.5ZM826-386q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Zm0-160q-6-6-6-14t6-14q6-6 14-6t14 6q6 6 6 14t-6 14q-6 6-14 6t-14-6Z",
  info: "M508.5-291.5Q520-303 520-320v-160q0-17-11.5-28.5T480-520q-17 0-28.5 11.5T440-480v160q0 17 11.5 28.5T480-280q17 0 28.5-11.5Zm0-320Q520-623 520-640t-11.5-28.5Q497-680 480-680t-28.5 11.5Q440-657 440-640t11.5 28.5Q463-600 480-600t28.5-11.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z",
  audio: "M280-120h-80q-33 0-56.5-23.5T120-200v-280q0-75 28.5-140.5t77-114q48.5-48.5 114-77T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480v280q0 33-23.5 56.5T760-120h-80q-33 0-56.5-23.5T600-200v-160q0-33 23.5-56.5T680-440h80v-40q0-117-81.5-198.5T480-760q-117 0-198.5 81.5T200-480v40h80q33 0 56.5 23.5T360-360v160q0 33-23.5 56.5T280-120Z",
  bell: "M200-200q-17 0-28.5-11.5T160-240q0-17 11.5-28.5T200-280h40v-280q0-83 50-147.5T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h40q17 0 28.5 11.5T800-240q0 17-11.5 28.5T760-200H200ZM480-80q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80Z",
  account: "M234-276q51-39 114-61.5T480-360q69 0 132 22.5T726-276q35-41 54.5-93T800-480q0-133-93.5-226.5T480-800q-133 0-226.5 93.5T160-480q0 59 19.5 111t54.5 93Zm146.5-204.5Q340-521 340-580t40.5-99.5Q421-720 480-720t99.5 40.5Q620-639 620-580t-40.5 99.5Q539-440 480-440t-99.5-40.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z",
  trending: "M160-400q0-113 67-217t184-182q22-15 45.5-1.5T480-760v52q0 34 23.5 57t57.5 23q17 0 32.5-7.5T621-657q8-10 20.5-12.5T665-664q63 45 99 115t36 149q0 88-43 160.5T644-125q17-24 26.5-52.5T680-238q0-40-15-75.5T622-377L480-516 339-377q-29 29-44 64t-15 75q0 32 9.5 60.5T316-125q-70-42-113-114.5T160-400Zm320-4 85 83q17 17 26 38t9 45q0 49-35 83.5T480-120q-50 0-85-34.5T360-238q0-23 9-44.5t26-38.5l85-83Z",
  music: "M287-167q-47-47-47-113t47-113q47-47 113-47 23 0 42.5 5.5T480-418v-382q0-17 11.5-28.5T520-840h160q17 0 28.5 11.5T720-800v80q0 17-11.5 28.5T680-680H560v400q0 66-47 113t-113 47q-66 0-113-47Z",
  game: "M182-200q-51 0-79-35.5T82-322l42-300q9-60 53.5-99T282-760h396q60 0 104.5 39t53.5 99l42 300q7 51-21 86.5T778-200q-21 0-39-7.5T706-230l-90-90H344l-90 90q-15 15-33 22.5t-39 7.5Zm526.5-251.5Q720-463 720-480t-11.5-28.5Q697-520 680-520t-28.5 11.5Q640-497 640-480t11.5 28.5Q663-440 680-440t28.5-11.5Zm-80-120Q640-583 640-600t-11.5-28.5Q617-640 600-640t-28.5 11.5Q560-617 560-600t11.5 28.5Q583-560 600-560t28.5-11.5ZM310-510v40q0 13 8.5 21.5T340-440q13 0 21.5-8.5T370-470v-40h40q13 0 21.5-8.5T440-540q0-13-8.5-21.5T410-570h-40v-40q0-13-8.5-21.5T340-640q-13 0-21.5 8.5T310-610v40h-40q-13 0-21.5 8.5T240-540q0 13 8.5 21.5T270-510h40Z",
  news: "M160-120q-33 0-56.5-23.5T80-200v-616q0-7 6-9.5t11 2.5l50 50 52-53q6-6 14-6t14 6l53 53 53-53q6-6 14-6t14 6l52 53 53-53q6-6 14-6t14 6l53 53 52-53q6-6 14-6t14 6l53 53 53-53q6-6 14-6t14 6l52 53 50-50q5-5 11-2.5t6 9.5v616q0 33-23.5 56.5T800-120H160Zm0-80h280v-240H160v240Zm360 0h280v-80H520v80Zm0-160h280v-80H520v80ZM160-520h640v-120H160v120Z",
  movie: "m160-800 65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h80l65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h80l65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h120q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800Z",
  live: "M160-480q0 56 17 105.5t49 89.5q11 14 11 30.5T225-226q-12 12-29 11.5T168-229q-42-52-65-115.5T80-480q0-72 23-136t65-115q11-14 28-14.5t29 11.5q12 12 12 28.5T226-675q-32 41-49 90.5T160-480Zm160 0q0 23 6 43.5t17 38.5q9 14 8.5 31T339-338q-12 12-29 11.5T284-341q-21-29-32.5-65T240-480q0-39 11.5-74.5T284-619q10-14 26.5-14t28.5 12q12 12 12.5 28.5T343-562q-11 18-17 38.5t-6 43.5Zm103.5 56.5Q400-447 400-480t23.5-56.5Q447-560 480-560t56.5 23.5Q560-513 560-480t-23.5 56.5Q513-400 480-400t-56.5-23.5ZM640-480q0-23-6-43.5T617-562q-9-14-8-31t13-29q12-12 28-11.5t26 14.5q21 29 32.5 64.5T720-480q0 38-11.5 74T676-341q-9 14-26 14t-29-12q-12-12-12.5-28.5T617-398q11-18 17-38.5t6-43.5Zm160 0q0-56-17-105.5T734-675q-11-14-11.5-30.5T734-734q12-12 29.5-11.5T792-731q42 51 65 115t23 136q0 72-23 135.5T792-229q-11 14-28 14.5T735-226q-12-12-12-28.5t11-30.5q32-41 49-90.5T800-480Z",
  libr: "M701-535q14-9 14-25t-14-25L506-710q-15-10-30.5-1.5T460-685v250q0 18 15.5 26.5T506-410l195-125ZM320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H320ZM160-80q-33 0-56.5-23.5T80-160v-520q0-17 11.5-28.5T120-720q17 0 28.5 11.5T160-680v520h520q17 0 28.5 11.5T720-120q0 17-11.5 28.5T680-80H160Z",
  dark: "M480-120q-151 0-255.5-104.5T120-480q0-138 90-239.5T440-838q13-2 23 3.5t16 14.5q6 9 6.5 21t-7.5 23q-17 26-25.5 55t-8.5 61q0 90 63 153t153 63q31 0 61.5-9t54.5-25q11-7 22.5-6.5T819-479q10 5 15.5 15t3.5 24q-14 138-117.5 229T480-120Z",
  sort: "M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h160q17 0 28.5 11.5T360-280q0 17-11.5 28.5T320-240H160Zm0-200q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h400q17 0 28.5 11.5T600-480q0 17-11.5 28.5T560-440H160Zm0-200q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h640q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H160Z",
  trash: "M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h160q17 0 28.5 11.5T600-800h160q17 0 28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 56.5T680-120H280Zm148.5-171.5Q440-303 440-320v-280q0-17-11.5-28.5T400-640q-17 0-28.5 11.5T360-600v280q0 17 11.5 28.5T400-280q17 0 28.5-11.5Zm160 0Q600-303 600-320v-280q0-17-11.5-28.5T560-640q-17 0-28.5 11.5T520-600v280q0 17 11.5 28.5T560-280q17 0 28.5-11.5Z",
  link: "M280-280q-83 0-141.5-58.5T80-480q0-83 58.5-141.5T280-680h120q17 0 28.5 11.5T440-640q0 17-11.5 28.5T400-600H280q-50 0-85 35t-35 85q0 50 35 85t85 35h120q17 0 28.5 11.5T440-320q0 17-11.5 28.5T400-280H280Zm80-160q-17 0-28.5-11.5T320-480q0-17 11.5-28.5T360-520h240q17 0 28.5 11.5T640-480q0 17-11.5 28.5T600-440H360Zm200 160q-17 0-28.5-11.5T520-320q0-17 11.5-28.5T560-360h120q50 0 85-35t35-85q0-50-35-85t-85-35H560q-17 0-28.5-11.5T520-640q0-17 11.5-28.5T560-680h120q83 0 141.5 58.5T880-480q0 83-58.5 141.5T680-280H560Z",
  verified: "m438-452-58-57q-11-11-27.5-11T324-508q-11 11-11 28t11 28l86 86q12 12 28 12t28-12l170-170q12-12 11.5-28T636-592q-12-12-28.5-12.5T579-593L438-452ZM326-90l-58-98-110-24q-15-3-24-15.5t-7-27.5l11-113-75-86q-10-11-10-26t10-26l75-86-11-113q-2-15 7-27.5t24-15.5l110-24 58-98q8-13 22-17.5t28 1.5l104 44 104-44q14-6 28-1.5t22 17.5l58 98 110 24q15 3 24 15.5t7 27.5l-11 113 75 86q10 11 10 26t-10 26l-75 86 11 113q2 15-7 27.5T802-212l-110 24-58 98q-8 13-22 17.5T584-74l-104-44-104 44q-14 6-28 1.5T326-90Z",
  add: "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760q17 0 28.5 11.5T520-720v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v-200Z",
  notes: "M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h400q17 0 28.5 11.5T600-280q0 17-11.5 28.5T560-240H160Zm0-200q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm0-200q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h640q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H160Z",
  open: "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h240q17 0 28.5 11.5T480-800q0 17-11.5 28.5T440-760H200v560h560v-240q0-17 11.5-28.5T800-480q17 0 28.5 11.5T840-440v240q0 33-23.5 56.5T760-120H200Zm560-584L416-360q-11 11-28 11t-28-11q-11-11-11-28t11-28l344-344H600q-17 0-28.5-11.5T560-800q0-17 11.5-28.5T600-840h200q17 0 28.5 11.5T840-800v200q0 17-11.5 28.5T800-560q-17 0-28.5-11.5T760-600v-104Z",
  keyboard: "M160-200q-33 0-56.5-23.5T80-280v-400q0-33 23.5-56.5T160-760h640q33 0 56.5 23.5T880-680v400q0 33-23.5 56.5T800-200H160Zm200-120h240q17 0 28.5-11.5T640-360q0-17-11.5-28.5T600-400H360q-17 0-28.5 11.5T320-360q0 17 11.5 28.5T360-320Zm-91.5-251.5Q280-583 280-600t-11.5-28.5Q257-640 240-640t-28.5 11.5Q200-617 200-600t11.5 28.5Q223-560 240-560t28.5-11.5Zm120 0Q400-583 400-600t-11.5-28.5Q377-640 360-640t-28.5 11.5Q320-617 320-600t11.5 28.5Q343-560 360-560t28.5-11.5Zm120 0Q520-583 520-600t-11.5-28.5Q497-640 480-640t-28.5 11.5Q440-617 440-600t11.5 28.5Q463-560 480-560t28.5-11.5Zm120 0Q640-583 640-600t-11.5-28.5Q617-640 600-640t-28.5 11.5Q560-617 560-600t11.5 28.5Q583-560 600-560t28.5-11.5Zm120 0Q760-583 760-600t-11.5-28.5Q737-640 720-640t-28.5 11.5Q680-617 680-600t11.5 28.5Q703-560 720-560t28.5-11.5Zm-480 120Q280-463 280-480t-11.5-28.5Q257-520 240-520t-28.5 11.5Q200-497 200-480t11.5 28.5Q223-440 240-440t28.5-11.5Zm120 0Q400-463 400-480t-11.5-28.5Q377-520 360-520t-28.5 11.5Q320-497 320-480t11.5 28.5Q343-440 360-440t28.5-11.5Zm120 0Q520-463 520-480t-11.5-28.5Q497-520 480-520t-28.5 11.5Q440-497 440-480t11.5 28.5Q463-440 480-440t28.5-11.5Zm120 0Q640-463 640-480t-11.5-28.5Q617-520 600-520t-28.5 11.5Q560-497 560-480t11.5 28.5Q583-440 600-440t28.5-11.5Zm120 0Q760-463 760-480t-11.5-28.5Q737-520 720-520t-28.5 11.5Q680-497 680-480t11.5 28.5Q703-440 720-440t28.5-11.5Z",
  pip: "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm320-120h240q17 0 28.5-11.5T760-320v-160q0-17-11.5-28.5T720-520H480q-17 0-28.5 11.5T440-480v160q0 17 11.5 28.5T480-280Zm120-120Z",
  expand: "m480-236 93-93q12-12 29-12t29 12q12 12 12 29t-12 29L508-148q-6 6-13 8.5t-15 2.5q-8 0-15-2.5t-13-8.5L329-271q-12-12-12-29t12-29q12-12 29-12t29 12l93 93Zm0-484-93 93q-12 12-29 12t-29-12q-12-12-12-29t12-29l123-123q6-6 13-8.5t15-2.5q8 0 15 2.5t13 8.5l123 123q12 12 12 29t-12 29q-12 12-29 12t-29-12l-93-93Z",
  chat: "m240-240-92 92q-19 19-43.5 8.5T80-177v-623q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240Z",
  heart: "M451.5-152q-14.5-5-25.5-16l-69-63q-106-97-191.5-192.5T80-634q0-94 63-157t157-63q53 0 100 22.5t80 61.5q33-39 80-61.5T660-854q94 0 157 63t63 157q0 115-85 211T602-230l-68 62q-11 11-25.5 16t-28.5 5q-14 0-28.5-5Z",
  pin: "M640-760v280l68 68q6 6 9 13.5t3 15.5v23q0 17-11.5 28.5T680-320H520v234q0 17-11.5 28.5T480-46q-17 0-28.5-11.5T440-86v-234H280q-17 0-28.5-11.5T240-360v-23q0-8 3-15.5t9-13.5l68-68v-280q-17 0-28.5-11.5T280-800q0-17 11.5-28.5T320-840h320q17 0 28.5 11.5T680-800q0 17-11.5 28.5T640-760Z",
  sports: "M280-528v-152h-80v40q0 38 22 68.5t58 43.5Zm400 0q36-13 58-43.5t22-68.5v-40h-80v152ZM440-200v-124q-49-11-87.5-41.5T296-442q-75-9-125.5-65.5T120-640v-40q0-33 23.5-56.5T200-760h80q0-33 23.5-56.5T360-840h240q33 0 56.5 23.5T680-760h80q33 0 56.5 23.5T840-680v40q0 76-50.5 132.5T664-442q-18 46-56.5 76.5T520-324v124h120q17 0 28.5 11.5T680-160q0 17-11.5 28.5T640-120H320q-17 0-28.5-11.5T280-160q0-17 11.5-28.5T320-200h120Z",
  flag: "M280-400v240q0 17-11.5 28.5T240-120q-17 0-28.5-11.5T200-160v-600q0-17 11.5-28.5T240-800h287q14 0 25 9t14 23l10 48h184q17 0 28.5 11.5T800-680v320q0 17-11.5 28.5T760-320H553q-14 0-25-9t-14-23l-10-48H280Z",
  skip: "M100-315v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T428-480q0 10-4.5 18.5T410-447L162-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Zm400 0v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T828-480q0 10-4.5 18.5T810-447L562-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Z",
  sleep: "M484-80q-84 0-157.5-32t-128-86.5Q144-253 112-326.5T80-484q0-128 72-232t193-146q22-8 41 5.5t18 36.5q-3 85 27 162t90 137q60 60 137 90t162 27q26-1 38.5 17.5T863-345q-44 120-147.5 192.5T484-80Z",
  upload: "M240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Zm200-486-75 75q-12 12-28.5 11.5T308-572q-11-12-11.5-28t11.5-28l144-144q6-6 13-8.5t15-2.5q8 0 15 2.5t13 8.5l144 144q12 12 11.5 28T652-572q-12 12-28.5 12.5T595-571l-75-75v286q0 17-11.5 28.5T480-320q-17 0-28.5-11.5T440-360v-286Z",
  globe: "M325-111.5q-73-31.5-127.5-86t-86-127.5Q80-398 80-480.5t31.5-155q31.5-72.5 86-127t127.5-86Q398-880 480.5-880t155 31.5q72.5 31.5 127 86t86 127Q880-563 880-480.5T848.5-325q-31.5 73-86 127.5t-127 86Q563-80 480.5-80T325-111.5ZM480-162q26-36 45-75t31-83H404q12 44 31 83t45 75Zm-104-16q-18-33-31.5-68.5T322-320H204q29 50 72.5 87t99.5 55Zm208 0q56-18 99.5-55t72.5-87H638q-9 38-22.5 73.5T584-178ZM170-400h136q-3-20-4.5-39.5T300-480q0-21 1.5-40.5T306-560H170q-5 20-7.5 39.5T160-480q0 21 2.5 40.5T170-400Zm216 0h188q3-20 4.5-39.5T580-480q0-21-1.5-40.5T574-560H386q-3 20-4.5 39.5T380-480q0 21 1.5 40.5T386-400Zm268 0h136q5-20 7.5-39.5T800-480q0-21-2.5-40.5T790-560H654q3 20 4.5 39.5T660-480q0 21-1.5 40.5T654-400Zm-16-240h118q-29-50-72.5-87T584-782q18 33 31.5 68.5T638-640Zm-234 0h152q-12-44-31-83t-45-75q-26 36-45 75t-31 83Zm-200 0h118q9-38 22.5-73.5T376-782q-56 18-99.5 55T204-640Z",
  tv: "M160-200q-33 0-56.5-23.5T80-280v-480q0-33 23.5-56.5T160-840h640q33 0 56.5 23.5T880-760v480q0 33-23.5 56.5T800-200H640v40q0 17-11.5 28.5T600-120H360q-17 0-28.5-11.5T320-160v-40H160Z",
  radio: "M160-80q-33 0-56.5-23.5T80-160v-534l523-213q14-5 27.5.5T649-887q5 14-.5 27.5T629-841L332-720h468q33 0 56.5 23.5T880-640v480q0 33-23.5 56.5T800-80H160Zm231-149q29-29 29-71t-29-71q-29-29-71-29t-71 29q-29 29-29 71t29 71q29 29 71 29t71-29ZM160-520h480v-40q0-17 11.5-28.5T680-600q17 0 28.5 11.5T720-560v40h80v-120H160v120Z",
  album: "M480-300q75 0 127.5-52.5T660-480q0-75-52.5-127.5T480-660q-75 0-127.5 52.5T300-480q0 75 52.5 127.5T480-300Zm-28.5-151.5Q440-463 440-480t11.5-28.5Q463-520 480-520t28.5 11.5Q520-497 520-480t-11.5 28.5Q497-440 480-440t-28.5-11.5ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z",
  person: "M367-527q-47-47-47-113t47-113q47-47 113-47t113 47q47 47 47 113t-47 113q-47 47-113 47t-113-47ZM160-240v-32q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v32q0 33-23.5 56.5T720-160H240q-33 0-56.5-23.5T160-240Z",
  qmusic: "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-288q0-17 11.5-28.5T720-720h120q17 0 28.5 11.5T880-680q0 17-11.5 28.5T840-640h-80v360q0 50-35 85t-85 35ZM160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Z",
  down: "M465-363.5q-7-2.5-13-8.5L268-556q-11-11-11-28t11-28q11-11 28-11t28 11l156 156 156-156q11-11 28-11t28 11q11 11 11 28t-11 28L508-372q-6 6-13 8.5t-15 2.5q-8 0-15-2.5Z",
  lyrics: "M280-400h80q17 0 28.5-11.5T400-440q0-17-11.5-28.5T360-480h-80q-17 0-28.5 11.5T240-440q0 17 11.5 28.5T280-400Zm480-80q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 2t19 5v-167q0-17 11.5-28.5T840-920h80q17 0 28.5 11.5T960-880q0 17-11.5 28.5T920-840h-40v240q0 50-35 85t-85 35Zm-480-40h200q17 0 28.5-11.5T520-560q0-17-11.5-28.5T480-600H280q-17 0-28.5 11.5T240-560q0 17 11.5 28.5T280-520Zm0-120h200q17 0 28.5-11.5T520-680q0-17-11.5-28.5T480-720H280q-17 0-28.5 11.5T240-680q0 17 11.5 28.5T280-640Zm-40 400-92 92q-6 6-13 9t-15 3q-16 0-28-11.5T80-177v-623q0-33 23.5-56.5T160-880h440q31 0 54.5 19t23.5 49q0 14-6.5 25T654-769q-44 27-69 72t-25 97q0 54 26.5 100t73.5 73q22 13 35 34.5t13 47.5q0 45-31.5 75T600-240H240Z",
  loop1: "M460-540h-30q-13 0-21.5-8.5T400-570q0-13 8.5-21.5T430-600h50q17 0 28.5 11.5T520-560v170q0 13-8.5 21.5T490-360q-13 0-21.5-8.5T460-390v-150ZM274-200l34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z",
  eq: "M280-280v-400q0-17 11.5-28.5T320-720q17 0 28.5 11.5T360-680v400q0 17-11.5 28.5T320-240q-17 0-28.5-11.5T280-280Zm160 160v-720q0-17 11.5-28.5T480-880q17 0 28.5 11.5T520-840v720q0 17-11.5 28.5T480-80q-17 0-28.5-11.5T440-120ZM120-440v-80q0-17 11.5-28.5T160-560q17 0 28.5 11.5T200-520v80q0 17-11.5 28.5T160-400q-17 0-28.5-11.5T120-440Zm480 160v-400q0-17 11.5-28.5T640-720q17 0 28.5 11.5T680-680v400q0 17-11.5 28.5T640-240q-17 0-28.5-11.5T600-280Zm160-160v-80q0-17 11.5-28.5T800-560q17 0 28.5 11.5T840-520v80q0 17-11.5 28.5T800-400q-17 0-28.5-11.5T760-440Z",
  sun: "M480-360q50 0 85-35t35-85q0-50-35-85t-85-35q-50 0-85 35t-35 85q0 50 35 85t85 35Zm0 80q-83 0-141.5-58.5T280-480q0-83 58.5-141.5T480-680q83 0 141.5 58.5T680-480q0 83-58.5 141.5T480-280ZM200-440H40v-80h160v80Zm720 0H760v-80h160v80ZM440-760v-160h80v160h-80Zm0 720v-160h80v160h-80ZM256-650l-101-97 57-59 96 100-52 56Zm492 496-97-101 53-55 101 97-57 59Zm-98-550 97-101 59 57-100 96-56-52ZM154-212l101-97 55 53-97 101-59-57Zm326-268Z",
  phone: "M280-40q-33 0-56.5-23.5T200-120v-720q0-33 23.5-56.5T280-920h400q33 0 56.5 23.5T760-840v720q0 33-23.5 56.5T680-40H280Zm0-120v40h400v-40H280Zm0-80h400v-480H280v480Zm0-560h400v-40H280v40Zm0 0v-40 40Zm0 640v40-40Z",
  sparkle: "m744-576-30-66-66-30 66-30 30-66 30 66 66 30-66 30-30 66Zm0 504-30-66-66-30 66-30 30-66 30 66 66 30-66 30-30 66ZM336-192l-60-132-132-60 132-60 60-132 60 132 132 60-132 60-60 132Zm0-182 24-50 50-24-50-24-24-50-24 50-50 24 50 24 24 50Zm0-74Z",
};
const SOLID = new Set(["play","pause","next","prev","skip","verified","vol","vollow","mute"]);
const VB = 'viewBox="0 -960 960 960"';
// icon(name): outlined (player buttons solid). "fill" in cls draws it filled; "duo" carries both shapes,
// so CSS can fill it when its link is the current page (.act / .on).
export const icon = (n, cls = "") => {
  if (/\bduo\b/.test(cls)) return `<svg class="ic ${cls}" ${VB} aria-hidden="true"><path class="o" d="${P[n] || ""}"/><path class="f" d="${F[n] || ""}"/></svg>`;
  const d = (/\bfill\b/.test(cls) || SOLID.has(n) ? F[n] : P[n]) || "";
  return `<svg class="ic ${cls}" ${VB} aria-hidden="true"><path d="${d}"/></svg>`;
};
