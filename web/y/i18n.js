// Interface language. Messages are written in Korean in the code (the original language)
// and looked up in y/lang/<code>.js; anything missing falls back to English, then Korean.
// English until a language is chosen (asked on the first visit, changeable in Settings).

export const LANGS = [
  ["ko", "한국어"], ["en", "English"], ["ja", "日本語"], ["zh", "简体中文"], ["es", "Español"],
  ["pt", "Português"], ["fr", "Français"], ["de", "Deutsch"], ["ru", "Русский"], ["hi", "हिन्दी"], ["id", "Bahasa Indonesia"], ["tr", "Türkçe"], ["bn", "বাংলা"], ["vi", "Tiếng Việt"],
];
const CODES = LANGS.map(([c]) => c);
// country used for trending / search results, when the browser does not say
const REGIONS = { ko: "KR", en: "US", ja: "JP", zh: "TW", es: "ES", pt: "BR", fr: "FR", de: "DE", ru: "RU", hi: "IN", id: "ID", tr: "TR", bn: "BD", vi: "VN" };

let saved;
try { saved = JSON.parse(localStorage.getItem("yt.settings") || "{}").lang; } catch { }
/** no language chosen yet on this device: the app asks once, on the first screen */
export const FIRST_VISIT = !saved;

function choose() {
  return saved && CODES.includes(saved) ? saved : "en";
}

export const LANG = choose();
// the country of the browser language that matches the interface language (ko-KR -> KR), else a default
const country = (navigator.languages || [navigator.language || ""]).map((l) => String(l).split("-"))
  .find(([l, c]) => l.toLowerCase() === LANG && c && /^[A-Za-z]{2}$/.test(c))?.[1];
export const REGION = (country || REGIONS[LANG] || "US").toUpperCase();
export const LOCALE = LANG === "zh" ? "zh-CN" : `${LANG}-${REGION}`;
document.documentElement.lang = LANG;

// English is always loaded: it is the fallback, and the server speaks English
const en = (await import("./lang/en.js")).default;
const dict = LANG === "en" ? en : LANG === "ko" ? {} : (await import(`./lang/${LANG}.js`)).default;

/** "언어 · Language" - the English word stays, so a wrong language can always be found again */
export const langLabel = () => (LANG === "en" ? "Language" : $t("언어") + " · Language");

/** $t("{0}개 더보기", 12) -> "12 more" ... the Korean text is the key. */
export function $t(ko, ...args) {
  let s = LANG === "ko" ? ko : (dict[ko] ?? en[ko] ?? ko);
  if (args.length) s = s.replace(/\{(\d)\}/g, (m, i) => (args[i] ?? ""));
  return s;
}

/** A message from the server (English, maybe "message: detail") in the interface language. */
let fromEn;
export function $tMsg(msg) {
  msg = String(msg ?? "");
  const i = msg.indexOf(": ");
  const head = i > 0 ? msg.slice(0, i) : msg, tail = i > 0 ? msg.slice(i) : "";
  fromEn ||= Object.fromEntries(Object.entries(en).map(([k, v]) => [v, k]));
  const key = fromEn[head];
  return key ? $t(key) + tail : msg;
}

// ---------------------------------------------------------------- numbers and dates in the local style
const compact = new Intl.NumberFormat(LOCALE, { notation: "compact", maximumFractionDigits: 1 });
export const fmtShort = (n) => (n < 1000 ? String(n) : compact.format(n));
const rel = new Intl.RelativeTimeFormat(LOCALE, { numeric: "always" });
export function fmtAgo(seconds) {
  const u = [[31536000, "year"], [2592000, "month"], [604800, "week"], [86400, "day"], [3600, "hour"], [60, "minute"]];
  for (const [s, w] of u) if (seconds >= s) return rel.format(-Math.floor(seconds / s), w);
  return $t("방금 전");
}

/** Static text in index.html: translate text nodes and a few attributes in place. */
export function translateDom(root) {
  if (LANG === "ko") return;
  const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const KO = /[가-힣]/;
  for (let n; (n = w.nextNode());) {
    const v = n.nodeValue, k = v.trim();
    // data-i18n gives a different key where the same Korean word means two things
    if (k && KO.test(k)) n.nodeValue = v.replace(k, $t(n.parentElement?.dataset.i18n || k));
  }
  for (const e of root.querySelectorAll("[title],[placeholder],[aria-label]")) {
    for (const a of ["title", "placeholder", "aria-label"]) {
      const v = e.getAttribute(a);
      if (v && KO.test(v)) e.setAttribute(a, $t(v));
    }
  }
}
