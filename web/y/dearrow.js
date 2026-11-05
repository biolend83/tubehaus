// DeArrow (dearrow.ajay.app, by the people behind SponsorBlock): titles and thumbnails that its users
// chose instead of the clickbait ones. Like SponsorBlock, only the first 4 characters of the video ID's
// hash leave the browser; DeArrow answers with every video under that prefix. The chosen thumbnails are
// frames DeArrow's server cuts: those come through this server (/api/p/yt/dathumb), so DeArrow sees the
// server and not the viewer. Cards are changed in place as they appear, the original title kept as a tooltip.
import { settings } from "./util.js";
import { $t } from "./i18n.js";

const byPrefix = new Map();   // hash prefix -> Promise<{videoID: branding}>
const brands = new Map();     // video id -> {title, time} | null
let running = 0;
const waiting = [];

// a few requests at a time: a page of 40 cards must not open 40 connections at once
function limited(fn) {
  return new Promise((ok) => {
    const go = () => { running++; fn().then(ok, () => ok({})).finally(() => { running--; waiting.shift()?.(); }); };
    running < 4 ? go() : waiting.push(go);
  });
}

async function prefixOf(id) {
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(id));
  return [...new Uint8Array(h)].slice(0, 2).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// DeArrow marks a word to keep as written with ">" ("the >iPhone"): that mark is not for showing
const clean = (s) => String(s || "").replace(/(^|\s)>(?=\S)/g, "$1").trim();

/** the title and thumbnail time DeArrow's users settled on, or null when they kept the original */
export function pickBranding(b) {
  if (!b) return null;
  const ok = (x) => x && (x.locked || x.votes >= 0);
  const t = (b.titles || []).find(ok), th = (b.thumbnails || []).find(ok);
  const title = t && !t.original ? clean(t.title) : "";
  const time = th && !th.original && typeof th.timestamp === "number" && th.timestamp >= 0 ? th.timestamp : null;
  return title || time != null ? { title, time } : null;
}

export async function branding(id) {
  if (brands.has(id)) return brands.get(id);
  if (!crypto.subtle) return null;
  const p = await prefixOf(id);
  if (!byPrefix.has(p)) {
    byPrefix.set(p, limited(() => fetch(`https://sponsor.ajay.app/api/branding/${p}`, { referrerPolicy: "no-referrer" })
      .then((r) => (r.ok ? r.json() : {}))));
  }
  const all = await byPrefix.get(p);
  const b = pickBranding(all && all[id]);
  brands.set(id, b);
  return b;
}

function apply(node, id, b) {
  if (!b || node.dataset.da === "done") return;
  node.dataset.da = "done";
  // a video card: its title link and thumbnail; the watch page: its heading (data-da-vid)
  const t = node.matches("[data-da-vid]") ? node : node.querySelector(".t");
  if (b.title && t) {
    const orig = t.textContent;
    t.textContent = b.title;
    t.title = $t("원래 제목: {0}", orig);
  }
  const img = node.querySelector(":scope > .th img, :scope > a.th img");
  if (b.time != null && img && settings.dearrowThumbs && !node.classList.contains("short")) {
    const orig = img.getAttribute("src");
    img.onerror = () => { img.onerror = null; img.src = orig; };
    img.src = `/api/p/yt/dathumb?v=${encodeURIComponent(id)}&t=${+b.time.toFixed(3)}`;
  }
}

function want(node) {
  const id = node.dataset.vid || node.dataset.daVid;
  if (!id || node.dataset.da || !/^[\w-]{11}$/.test(id)) return;
  node.dataset.da = "asked";
  branding(id).then((b) => { if (node.isConnected) apply(node, id, b); }).catch(() => { });
}

const SEL = ".card[data-vid], [data-da-vid]";
function scan(root) {
  if (root.matches?.(SEL)) want(root);
  root.querySelectorAll?.(SEL).forEach(want);
}

export function initDeArrow() {
  new MutationObserver((ms) => {
    if (!settings.dearrow) return;
    for (const m of ms) for (const n of m.addedNodes) if (n.nodeType === 1) scan(n);
  }).observe(document.body, { childList: true, subtree: true });
  if (settings.dearrow) scan(document.body);
}
