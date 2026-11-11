// Titles in your language (Settings -> Translate titles, off by default): video titles written in
// another script are asked from this server, which asks a translator and remembers the answers.
// The original stays in the tooltip. Loaded on its own, so a browser too old for it just goes without.
import { settings, api } from "./util.js";
import { LANG } from "./i18n.js";

const SEL = ".card[data-vid] a.t, .card.pl a.t, h1.w-title, .ytp-title, #mini .mp-t";
const OWN = { ko: /\p{Script=Hangul}/gu, ja: /[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}]/gu, zh: /\p{Script=Han}/gu,
  ru: /\p{Script=Cyrillic}/gu, hi: /\p{Script=Devanagari}/gu, bn: /\p{Script=Bengali}/gu }[LANG];
const LETTER = /\p{L}/gu, LATIN = /\p{Script=Latin}/gu;
const count = (re, t) => (t.match(re) || []).length;

/** does this title need translating for someone who reads LANG? */
export function wants(text) {
  const all = count(LETTER, text);
  if (all < 2) return false;
  // a language with its own script: mostly written in something else
  if (OWN) return count(OWN, text) / all < 0.3;
  // a language in Latin letters: at least two letters of another script (a title in another
  // Latin-script language can't be told apart here, and stays as it is)
  return all - count(LATIN, text) >= 2;
}

const memo = new Map();          // title -> its translation, for this visit
const state = new WeakMap();     // element -> { src, out }
let timer = 0, busy = false, again = false;

function put(el, src, out) {
  if (!out || out === src) { state.set(el, { src, out: src }); return; }
  state.set(el, { src, out });
  el.textContent = out;
  el.dataset.tr = "1";
  if (!el.title || el.title === out) el.title = src;
}

async function scan() {
  if (!settings.trTitles) return;
  if (busy) { again = true; return; }
  busy = true;
  try {
    const need = new Map();      // title -> the elements that show it
    for (const el of document.querySelectorAll(SEL)) {
      const text = el.textContent.trim(), st = state.get(el);
      if (st && st.out === text) continue;                     // ours, or already looked at
      if (!wants(text)) { state.set(el, { src: text, out: text }); continue; }
      if (memo.has(text)) put(el, text, memo.get(text));
      else (need.get(text) || need.set(text, []).get(text)).push(el);
    }
    const all = [...need.keys()];
    for (let i = 0; i < all.length; i += 40) {
      const q = all.slice(i, i + 40);
      let t;
      try { t = (await api("/api/p/yt/tr", { method: "POST", body: { tl: LANG, q }, quiet: true })).t; } catch { break; }
      q.forEach((src, k) => {
        const out = typeof t[k] === "string" ? t[k] : src;
        memo.set(src, out);
        for (const el of need.get(src)) if (el.isConnected && el.textContent.trim() === src) put(el, src, out);
      });
    }
  } finally {
    busy = false;
    if (again) { again = false; later(); }
  }
}
function later() { clearTimeout(timer); timer = setTimeout(scan, 250); }

function undo() {
  for (const el of document.querySelectorAll("[data-tr]")) {
    const st = state.get(el);
    if (st && el.textContent === st.out) el.textContent = st.src;
    delete el.dataset.tr;
    state.delete(el);
  }
}

export function startTitleTr() {
  new MutationObserver(later).observe(document.body, { childList: true, subtree: true, characterData: true });
  addEventListener("settings", (e) => { if (e.detail === "trTitles") (settings.trTitles ? later : undo)(); });
  later();
}
