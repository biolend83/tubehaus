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

const DEFAULTS = {
  lang: "auto",           // interface language: auto = the browser's
  theme: "light",         // light | dark | system (default: light, like classic YouTube)
  autoplay: true,         // autoplay the next video
  calm: false,            // calm mode: subscriptions only, no Shorts, trending or recommendations, no autoplay
  quality: "auto",        // auto | 2160 | 1440 | 1080 | 720 | 480 | 360
  dataSaver: false,
  nativeIphone: true,     // iPhone: play with Safari's own player (Picture-in-Picture over other apps, AirPlay)
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
    p.finally(() => inflight.delete(key));
  }
  return p;
}
// Public instances (server started with HT_PUBLIC_MODE=1) keep each visitor's library in their own
// browser (locallib.js); the server only fetches feeds and parses imports for them.
export let PUBLIC = false;
export let SERVER_MAX_H = Infinity;     // the operator's quality cap (HT_MAX_HEIGHT), to save bandwidth
export const modeReady = fetch("/api/p/health", { credentials: "same-origin" })
  .then((r) => (r.ok ? r.json() : {})).then((h) => {
    PUBLIC = !!h.public;
    SERVER_MAX_H = +h.max_height || Infinity;
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
  const s = [...list].sort((a, b) => (a.width || 0) - (b.width || 0));
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
const P = {
  menu: "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z",
  search: "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
  mic: "M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z",
  home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
  shorts: "M17.77 10.32l-1.2-.5L18 9.06a3.74 3.74 0 0 0-3.5-6.62L6 6.94a3.74 3.74 0 0 0 .23 6.74l1.2.49L6 14.93a3.75 3.75 0 0 0 3.5 6.63l8.5-4.5a3.74 3.74 0 0 0-.23-6.74zM10 14.65V9.35L15 12l-5 2.65z",
  subs: "M20 8H4V6h16v2zm-2-6H6v2h12V2zm4 10v8c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2v-8c0-1.1.9-2 2-2h16c1.1 0 2 .9 2 2zm-6 4l-6-3.27v6.53L16 16z",
  history: "M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
  playlists: "M3 10h11v2H3zm0-4h11v2H3zm0 8h7v2H3zm13-1v8l6-4z",
  later: "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2zm4.2 14.2L11 13V7h1.5v5.2l4.5 2.7-.8 1.3z",
  like: "M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z",
  dislike: "M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z",
  share: "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z",
  download: "M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z",
  save: "M14 10H2v2h12v-2zm0-4H2v2h12V6zm4 8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2 16h8v-2H2v2z",
  more: "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
  moreh: "M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
  play: "M8 5v14l11-7z",
  pause: "M6 19h4V5H6v14zm8-14v14h4V5h-4z",
  next: "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z",
  prev: "M6 6h2v12H6zm3.5 6l8.5 6V6z",
  vol: "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
  vollow: "M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z",
  mute: "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51A8.796 8.796 0 0 0 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a8.99 8.99 0 0 0 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z",
  cc: "M19 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1c0 .55-.45 1-1 1h-3c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1z",
  gear: "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
  fs: "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z",
  fsx: "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z",
  theater: "M19 6H5c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 10H5V8h14v8z",
  theaterx: "M19 7H5c-1.1 0-2 .9-2 2v6c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V9c0-1.1-.9-2-2-2zm0 8H5V9h14v6z",
  mini: "M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z",
  close: "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  check: "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  right: "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
  left: "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z",
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
  replay: "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z",
  loop: "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z",
  shuffle: "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
  speed: "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z",
  tune: "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
  timer: "M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42A8.962 8.962 0 0 0 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
  ambient: "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0a.996.996 0 0 0 0-1.41l-1.06-1.06zm1.06-10.96a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z",
  info: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
  audio: "M12 3v9.28a4.39 4.39 0 0 0-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z",
  bell: "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z",
  account: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 3c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.2a7.2 7.2 0 0 1-6-3.22c.03-1.99 4-3.08 6-3.08 1.99 0 5.97 1.09 6 3.08a7.2 7.2 0 0 1-6 3.22z",
  trending: "M13.5.67s.74 2.65.74 4.8c0 2.06-1.35 3.73-3.41 3.73-2.07 0-3.63-1.67-3.63-3.73l.03-.36C5.21 7.51 4 10.62 4 14c0 4.42 3.58 8 8 8s8-3.58 8-8C20 8.61 17.41 3.8 13.5.67zM11.71 19c-1.78 0-3.22-1.4-3.22-3.14 0-1.62 1.05-2.76 2.81-3.12 1.77-.36 3.6-1.21 4.62-2.58.39 1.29.59 2.65.59 4.04 0 2.65-2.15 4.8-4.8 4.8z",
  music: "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z",
  game: "M21.58 16.09l-1.09-7.66A3.996 3.996 0 0 0 16.53 5H7.47C5.48 5 3.79 6.46 3.51 8.43l-1.09 7.66a2.545 2.545 0 0 0 4.32 2.17L9 16h6l2.25 2.25a2.545 2.545 0 0 0 4.33-2.16zM11 11H9v2H8v-2H6v-1h2V8h1v2h2v1zm4-1c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm2 3c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z",
  news: "M22 3l-1.67 1.67L18.67 3 17 4.67 15.33 3l-1.66 1.67L12 3l-1.67 1.67L8.67 3 7 4.67 5.33 3 3.67 4.67 2 3v16c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V3zM11 19H4v-6h7v6zm9 0h-7v-2h7v2zm0-4h-7v-2h7v2zm0-4H4V8h16v3z",
  movie: "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z",
  live: "M7.76 16.24C6.67 15.16 6 13.66 6 12s.67-3.16 1.76-4.24l1.42 1.42C8.45 9.9 8 10.9 8 12c0 1.1.45 2.1 1.17 2.83l-1.41 1.41zm8.48 0C17.33 15.16 18 13.66 18 12s-.67-3.16-1.76-4.24l-1.42 1.42C15.55 9.9 16 10.9 16 12c0 1.1-.45 2.1-1.17 2.83l1.41 1.41zM12 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm8 2c0 2.21-.9 4.21-2.35 5.65l1.42 1.42C20.88 17.26 22 14.76 22 12s-1.12-5.26-2.93-7.07l-1.42 1.42A7.94 7.94 0 0 1 20 12zM6.35 6.35L4.93 4.93C3.12 6.74 2 9.24 2 12s1.12 5.26 2.93 7.07l1.42-1.42C4.9 16.21 4 14.21 4 12s.9-4.21 2.35-5.65z",
  libr: "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z",
  dark: "M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z",
  sort: "M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z",
  trash: "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
  link: "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
  verified: "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2zM9.8 17.3l-4.2-4.1L7 11.8l2.8 2.7L17 7.4l1.4 1.4-8.6 8.5z",
  add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  notes: "M14 17H4v2h10v-2zm6-8H4v2h16V9zM4 15h16v-2H4v2zM4 5v2h16V5H4z",
  open: "M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
  keyboard: "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z",
  pip: "M19 7h-8v6h8V7zm2-4H3c-1.1 0-2 .9-2 2v14c0 1.1.9 1.98 2 1.98h18c1.1 0 2-.88 2-1.98V5c0-1.1-.9-2-2-2zm0 16.01H3V4.98h18v14.03z",
  expand: "M12 5.83L15.17 9l1.41-1.41L12 3 7.41 7.59 8.83 9 12 5.83zm0 12.34L8.83 15l-1.41 1.41L12 21l4.59-4.59L15.17 15 12 18.17z",
  chat: "M21.99 4c0-1.1-.89-2-1.99-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4-.01-18zM18 14H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z",
  heart: "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
  pin: "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z",
  flag: "M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z",
  skip: "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z",
  sleep: "M9.27 4.49c-1.63 7.54 3.75 12.41 7.66 13.8C15.54 19.38 13.81 20 12 20c-4.41 0-8-3.59-8-8 0-3.45 2.2-6.4 5.27-7.51m2.72-2.48C6.4 2.01 2 6.54 2 12c0 5.52 4.48 10 10 10 3.08 0 5.82-1.4 7.65-3.59-6.38 0-11.74-5.55-7.66-16.4z",
  upload: "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z",
  globe: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95a15.65 15.65 0 0 0-1.38-3.56A8.03 8.03 0 0 1 18.92 8zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2s.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56A7.987 7.987 0 0 1 5.08 16zm2.95-8H5.08a7.987 7.987 0 0 1 4.33-3.56A15.65 15.65 0 0 0 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2s.07-1.35.16-2h4.68c.09.65.16 1.32.16 2s-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95a8.03 8.03 0 0 1-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2s-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z",
  tv: "M21 3H3c-1.11 0-2 .89-2 2v12c0 1.1.89 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.11-.9-2-2-2zm0 14H3V5h18v12z",
};
export const icon = (n, cls = "") => `<svg class="ic ${cls}" viewBox="0 0 24 24" aria-hidden="true"><path d="${P[n] || ""}"/></svg>`;
