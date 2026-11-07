// Channel pictures for cards whose list came without one: YouTube leaves them out of many lists, and
// only subscriptions have one saved. The empty circle asks the channel (the request its page makes,
// cut down to the pictures) once, a few channels at a time, and the answer is kept in this browser.
import { api, pickThumb, icon } from "./util.js";

const KEY = "th-chav";
let saved = {};
try { saved = JSON.parse(localStorage.getItem(KEY) || "{}") || {}; } catch { }
const asking = new Map();     // channel id -> Promise<url or "">
let running = 0;
const waiting = [];

// a home page of 40 cards asks 3 channels at a time
function limited(fn) {
  return new Promise((ok) => {
    const go = () => { running++; fn().then(ok, () => ok("")).finally(() => { running--; waiting.shift()?.(); }); };
    running < 3 ? go() : waiting.push(go);
  });
}

function keep(id, url) {
  saved[id] = url;
  const ks = Object.keys(saved);
  if (ks.length > 2000) for (const k of ks.slice(0, 500)) delete saved[k];
  try { localStorage.setItem(KEY, JSON.stringify(saved)); } catch { }
}

/** the picture already known for a channel (for cards drawn after the first time), or "" */
export const knownAvatar = (id) => (id && saved[id]) || "";

function channelAvatar(id) {
  if (saved[id]) return Promise.resolve(saved[id]);
  if (!asking.has(id)) {
    asking.set(id, limited(() => api(`/api/v1/channels/${id}?fields=authorThumbnails`, { ttl: 86400000, quiet: true })
      .then((c) => pickThumb(c && c.authorThumbnails, 88), () => "")
      // Invidious can't open YouTube's "- Topic" (auto-generated artist) channels; YouTube Music has them as artists
      .then((u) => u || api(`/api/p/yt/music/artist?id=${id}`, { ttl: 86400000, quiet: true })
        .then((a) => (a && a.art ? "/api/p/yt/music/img?u=" + encodeURIComponent(a.art) : ""), () => ""))
      .then((u) => { if (u) keep(id, u); return u; })));
  }
  return asking.get(id);
}

function fill(ph) {
  if (ph.dataset.chav) return;
  const a = ph.closest('a[href^="/channel/UC"]');
  const id = a && a.getAttribute("href").slice(9).split(/[/?#]/)[0];
  if (!id || !/^UC[\w-]{22}$/.test(id)) return;
  ph.dataset.chav = "1";
  channelAvatar(id).then((u) => {
    if (!u || !ph.isConnected) return;
    const img = document.createElement("img");
    img.className = ph.className.replace(/\bph\b/, "").trim();
    img.loading = "lazy";
    img.alt = "";
    img.src = u;
    ph.replaceWith(img);
  });
}

// A saved picture that no longer loads (the channel changed its picture, YouTube dropped the old address):
// ask the channel for the one it has now, keep it in this browser, and use it for the rest of the visit.
const abs = (u) => { try { return new URL(u, location.href).href; } catch { return u; } };
function toPh(img) {
  if (!img.isConnected) return;
  const ph = document.createElement("span");
  ph.className = img.className + " ph";
  ph.dataset.chav = "1";
  ph.innerHTML = icon("account");
  img.replaceWith(ph);
}
// the same picture through this server's own image relay (/api/p/yt/music/img), straight from
// yt3.googleusercontent.com: Invidious' /ggpht/ turns some newer picture addresses away
function relayed(src) {
  const m = /^(?:https?:\/\/[^/]+)?\/ggpht\/(.+)$/.exec(src) || /^https:\/\/yt3\.(?:ggpht|googleusercontent)\.com\/(.+)$/.exec(src);
  return m ? "/api/p/yt/music/img?u=" + encodeURIComponent("https://yt3.googleusercontent.com/" + m[1]) : "";
}
function fixBroken(img) {
  const step = img.dataset.chfix || "";
  if (step === "2") return toPh(img);                 // nothing worked
  const src = img.getAttribute("src") || "";
  const a = img.closest('a[href^="/channel/UC"], [data-ch^="UC"]');
  const id = a && (a.dataset.ch || a.getAttribute("href").slice(9).split(/[/?#]/)[0]);
  const ok = id && /^UC[\w-]{22}$/.test(id);
  const use = (u) => {
    if (!u || abs(u) === abs(src)) return toPh(img);
    if (img.isConnected) img.src = u;
    if (ok) import("./core.js").then(({ state }) => { const sub = state.lib?.subs?.[id]; if (sub) sub.thumb = u; }).catch(() => { });
  };
  if (!step) {
    img.dataset.chfix = "1";
    const r = relayed(src);
    if (r) { if (ok) keep(id, r); return use(r); }
  }
  img.dataset.chfix = "2";
  if (!ok) return toPh(img);
  const bad = abs(src);
  if (saved[id] && abs(saved[id]) !== bad) return use(saved[id]);
  delete saved[id];
  asking.delete(id);
  channelAvatar(id).then(use, () => toPh(img));
}

const SEL = '.card a[href^="/channel/UC"] .av.ph';
function scan(root) {
  if (root.matches?.(SEL)) fill(root);
  root.querySelectorAll?.(SEL).forEach(fill);
}

export function initAvatars() {
  document.addEventListener("error", (e) => {
    const t = e.target;
    if (!(t instanceof HTMLImageElement)) return;
    if (t.classList.contains("av")) return fixBroken(t);
    // other pictures from the same host (community posts, banners): the same detour, once
    if (t.dataset.chfix) return;
    const r = relayed(t.getAttribute("src") || "");
    if (r) { t.dataset.chfix = "1"; t.src = r; }
  }, true);
  // pictures that already failed before this started
  document.querySelectorAll("img.av").forEach((i) => { if (i.complete && !i.naturalWidth && i.getAttribute("src")) fixBroken(i); });
  new MutationObserver((ms) => {
    for (const m of ms) for (const n of m.addedNodes) if (n.nodeType === 1) scan(n);
  }).observe(document.body, { childList: true, subtree: true });
  scan(document.body);
}
