// Channel pictures for cards whose list came without one: YouTube leaves them out of many lists, and
// only subscriptions have one saved. The empty circle asks the channel (the request its page makes,
// cut down to the pictures) once, a few channels at a time, and the answer is kept in this browser.
import { api, pickThumb } from "./util.js";

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
      .then((c) => { const u = pickThumb(c && c.authorThumbnails, 88); if (u) keep(id, u); return u; })));
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

const SEL = '.card a[href^="/channel/UC"] .av.ph';
function scan(root) {
  if (root.matches?.(SEL)) fill(root);
  root.querySelectorAll?.(SEL).forEach(fill);
}

export function initAvatars() {
  new MutationObserver((ms) => {
    for (const m of ms) for (const n of m.addedNodes) if (n.nodeType === 1) scan(n);
  }).observe(document.body, { childList: true, subtree: true });
  scan(document.body);
}
