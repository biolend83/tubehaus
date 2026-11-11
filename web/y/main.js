// Entry point: header (search, notifications, account), side menu, routes, global shortcuts
import { $, $$, esc, icon, api, lib, settings, local, toast, thumb, normVideo, ago, modeReady, siteName, pickName, LINKS } from "./util.js";
import { dialog } from "./core.js";
import { player, routes, state, loadLib, navigate, render, renderGuideSubs, popup, mi, keysDialog, initCardHover, markRestore, saveScroll, known, videoMenu, pageCleanup } from "./core.js";
import * as P from "./pages.js";
import * as MU from "./music.js";
import { watch, shorts, initMini, dockMini, stopPlayer } from "./watch.js";
import { initTV } from "./tv.js";
import { initDeArrow } from "./dearrow.js";
import { initAvatars } from "./avatars.js";
import { startReceiver, deviceName } from "./cast.js";
import { $t, LANG, LANGS, LOCALE, REGION, FIRST_VISIT, translateDom, langLabel } from "./i18n.js";

// static text of index.html in the interface language
translateDom(document.body);
const regionBadge = document.getElementById("region");
if (regionBadge) regionBadge.textContent = REGION;
document.title = $t("튜브하우스");
// the instance's own name and menu links (HT_NAME, HT_LINKS), once the server has said them
modeReady.then(() => {
  if (document.title === $t("튜브하우스")) document.title = siteName();
  for (const a of document.querySelectorAll(".logo[aria-label]")) a.setAttribute("aria-label", siteName());
  if (!LINKS.length) return;
  const sec = document.createElement("div");
  sec.className = "g-sec g-links";
  sec.innerHTML = LINKS.map((l) => `<a href="${esc(l.url)}" class="gi"${/\.apk$/i.test(l.url) ? " download" : ""}>${icon(/\.apk$/i.test(l.url) ? "download" : "open", "duo")}<span>${esc(pickName(l.name))}</span></a>`).join("");
  const set = document.querySelector('#guide [data-r="settings"]')?.closest(".g-sec");
  if (set) set.before(sec);
});

// ---------------------------------------------------------------- routes (same URLs as YouTube)
routes.push(
  [/^\/$/, P.home, { name: "home" }],
  [/^\/watch$/, watch, { name: "watch" }],
  [/^\/shorts\/([\w-]{11})$/, (page, m, alive) => (settings.calm ? navigate(`/watch?v=${m[1]}`, { replace: true }) : shorts(page, m, alive)), { name: "shorts" }],
  [/^\/shorts\/?$/, async (page) => {
    if (settings.calm) return navigate("/", { replace: true });
    // Shorts tab: recommendations learned on this server -> Shorts from the subscription feed -> popular Shorts
    let id = null;
    try {
      const r = await lib(`shorts/next?n=10&region=${REGION}`, { quiet: true });
      const s = r.items.map((x) => x.id);
      if (s.length) { sessionStorage.setItem("yt.shortsCtx", JSON.stringify(s)); id = s[0]; }
    } catch { }
    if (!id) try {
      const f = await lib("feed", { ttl: 120000 });
      const s = f.items.filter((x) => x.short).map((x) => x.id);
      if (s.length) { sessionStorage.setItem("yt.shortsCtx", JSON.stringify(s)); id = s[0]; }
    } catch { }
    if (!id) {
      const r = await api(`/api/v1/search?q=%23shorts&type=video&duration=short&region=${REGION}&sort=views&date=week`, { ttl: 600000 });
      const s = r.filter((x) => x.videoId).map((x) => x.videoId);
      sessionStorage.setItem("yt.shortsCtx", JSON.stringify(s));
      id = s[0];
    }
    if (id) navigate(`/shorts/${id}`, { replace: true });
    else page.innerHTML = `<div class="empty"><p>${$t("Shorts 를 찾지 못했어요")}</p></div>`;
  }, { name: "shorts" }],
  [/^\/results$/, P.search, { name: "search" }],
  [/^\/(channel\/UC[\w-]{22}|@[^/]+|c\/[^/]+|user\/[^/]+)(\/(videos|shorts|streams|playlists|community|search|featured))?\/?$/, P.channel, { name: "channel" }],
  [/^\/playlist$/, P.playlist, { name: "playlist" }],
  [/^\/feed\/subscriptions$/, P.subscriptions, { name: "subs" }],
  [/^\/feed\/history$/, P.history, { name: "history" }],
  [/^\/feed\/(you|library)$/, P.you, { name: "you" }],
  [/^\/feed\/playlists$/, P.playlists, { name: "playlists" }],
  [/^\/feed\/channels$/, P.channelsMgr, { name: "channels" }],
  [/^\/feed\/trending$/, P.trending, { name: "trending" }],
  [/^\/feed\/music\/?$/, MU.musicHome, { name: "music" }],
  [/^\/feed\/music\/search$/, MU.musicSearch, { name: "music" }],
  [/^\/feed\/music\/album\/([\w-]{8,80})$/, MU.musicAlbum, { name: "music" }],
  [/^\/feed\/music\/artist\/(UC[\w-]{22})$/, MU.musicArtist, { name: "music" }],
  [/^\/hashtag\/([^/]+)$/, P.hashtag, { name: "search" }],
  [/^\/settings$/, P.settingsPage, { name: "settings" }],
);

// ---------------------------------------------------------------- theme
function applyTheme() {
  const glass = settings.theme === "spatial" || settings.theme === "spatial-light";   // spatial.css on the dark or light colours
  const t = settings.theme === "system" ? (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark")
    : glass ? (settings.theme === "spatial" ? "dark" : "light") : settings.theme;
  document.documentElement.classList.toggle("spatial", glass);
  document.documentElement.classList.toggle("spatial-light", settings.theme === "spatial-light");
  document.documentElement.dataset.theme = t;
  $('meta[name="theme-color"]').content = t === "dark" ? "#0f0f0f" : "#ffffff";
}
applyTheme();
matchMedia("(prefers-color-scheme: light)").addEventListener("change", applyTheme);
addEventListener("settings", (e) => { if (e.detail === "theme") applyTheme(); });

// ---------------------------------------------------------------- link interception (no page reloads)
document.addEventListener("click", (e) => {
  const menu = e.target.closest("[data-menu]");
  if (menu) {
    e.preventDefault();
    e.stopPropagation();
    const ctx = menu.closest("[data-ctx]")?.dataset || {};
    const v = known.get(menu.dataset.menu);
    if (v) videoMenu(menu, v, { home: ctx.ctx === "home", hist: ctx.ctx === "hist", pl: ctx.pl || null });
    return;
  }
  const sb = e.target.closest("[data-subbtn]");
  if (sb) {
    e.preventDefault();
    import("./core.js").then(({ setSub, isSub }) => {
      const cid = sb.dataset.subbtn;
      if (isSub(cid)) {
        popup(sb, mi("unsub", "close", $t("구독 취소")), (a) => { if (a === "unsub") setSub(cid, sb.dataset.name, sb.dataset.thumb, false); }, { align: "left" });
      } else setSub(cid, sb.dataset.name, sb.dataset.thumb, true);
    });
    return;
  }
  const a = e.target.closest("a[href]");
  if (!a || a.target === "_blank" || e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0 || a.hasAttribute("download")) return;
  const u = new URL(a.href, location.href);
  if (u.origin !== location.origin) return;
  if (!routes.some(([re]) => re.test(u.pathname))) return;   // leave Invidious' own pages (/login etc.) alone
  e.preventDefault();
  if (u.pathname + u.search === location.pathname + location.search && state.route !== "watch") { scrollTo({ top: 0, behavior: "smooth" }); return; }
  navigate(u.pathname + u.search + u.hash);
});
addEventListener("popstate", () => { markRestore(); render(); });
history.scrollRestoration = "manual";
addEventListener("beforeunload", saveScroll);

// leaving the watch page moves the player into the mini player (before the page is cleared)
routes.forEach((r) => {
  if (r[2]?.name !== "watch") return;
  const fn = r[1];
  r[1] = async (page, m, alive) => {
    pageCleanup.push(() => dockMini());
    return fn(page, m, alive);
  };
});

// ---------------------------------------------------------------- header: search
const q = $("#q"), sugg = $("#sugg"), form = $("#sform");
let sTimer, sIdx = -1, sItems = [];
function showSugg(items, hist = false) {
  sItems = items;
  sIdx = -1;
  if (!items.length) { sugg.hidden = true; return; }
  sugg.innerHTML = items.map((s, i) => `<div class="sg" data-i="${i}">${icon(hist ? "history" : "search")}<span>${esc(s)}</span>${hist ? `<button class="sg-x" data-x="${esc(s)}">${$t("삭제")}</button>` : ""}</div>`).join("");
  sugg.hidden = false;
}
q.addEventListener("input", () => {
  clearTimeout(sTimer);
  const v = q.value.trim();
  $(".sclear").hidden = !q.value;
  if (!v) return showSugg(local.get("sh", []).slice(0, 12), true);
  sTimer = setTimeout(async () => {
    try {
      const r = await api(`/api/v1/search/suggestions?q=${encodeURIComponent(v)}&hl=ko&gl=KR`, { ttl: 300000 });
      // a late answer must not reopen the list after Enter or after leaving the box
      if (q.value.trim() === v && document.activeElement === q) showSugg(r.suggestions || []);
    } catch { }
  }, 120);
});
q.addEventListener("focus", () => { document.body.classList.add("searching"); if (!q.value.trim()) showSugg(local.get("sh", []).slice(0, 12), true); else q.dispatchEvent(new Event("input")); });
q.addEventListener("blur", () => setTimeout(() => { sugg.hidden = true; document.body.classList.remove("searching"); }, 150));
q.addEventListener("keydown", (e) => {
  if (sugg.hidden || !sItems.length) return;
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    sIdx = (sIdx + (e.key === "ArrowDown" ? 1 : -1) + sItems.length + 1) % (sItems.length + 1) - (0);
    if (sIdx >= sItems.length) sIdx = -1;
    $$(".sg", sugg).forEach((x, i) => x.classList.toggle("on", i === sIdx));
    if (sIdx >= 0) q.value = sItems[sIdx];
  }
  if (e.key === "Escape") { sugg.hidden = true; q.blur(); }
});
sugg.addEventListener("mousedown", (e) => {
  e.preventDefault();
  const x = e.target.closest("[data-x]");
  if (x) { local.set("sh", local.get("sh", []).filter((s) => s !== x.dataset.x)); showSugg(local.get("sh", []).slice(0, 12), true); return; }
  const it = e.target.closest(".sg");
  if (!it) return;
  q.value = sItems[+it.dataset.i];
  doSearch();
});
form.addEventListener("submit", (e) => { e.preventDefault(); doSearch(); });
$(".sclear").onclick = () => { q.value = ""; q.focus(); $(".sclear").hidden = true; };
function doSearch() {
  const v = q.value.trim();
  if (!v) return;
  clearTimeout(sTimer);
  sugg.hidden = true;
  q.blur();
  document.body.classList.remove("msearch");
  // pasting a YouTube link opens that video directly
  const m = v.match(/(?:youtu\.be\/|v=|shorts\/|live\/)([\w-]{11})/);
  if (m) return navigate(`/watch?v=${m[1]}`);
  const pl = v.match(/[?&]list=([\w-]+)/);
  if (pl) return navigate(`/playlist?list=${pl[1]}`);
  navigate(`/results?search_query=${encodeURIComponent(v)}`);
}
// voice search (Chrome / Edge / Safari)
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
if (!SR) $("#voice").hidden = true;
$("#voice").onclick = () => {
  const r = new SR();
  r.lang = LOCALE;
  r.interimResults = true;
  const { box, close } = (() => { const m = $("#modal"); m.innerHTML = `<div class="dlg voice"><div class="v-text">${$t("듣고 있어요...")}</div><div class="v-mic">${icon("mic")}</div></div>`; m.className = "show"; m.onclick = () => { r.abort(); m.className = ""; }; return { box: m.firstElementChild, close: () => (m.className = "") }; })();
  r.onresult = (e) => {
    const t = [...e.results].map((x) => x[0].transcript).join("");
    box.querySelector(".v-text").textContent = t;
    if (e.results[e.results.length - 1].isFinal) { close(); q.value = t; doSearch(); }
  };
  r.onerror = () => { box.querySelector(".v-text").textContent = $t("다시 말씀해 주세요"); setTimeout(close, 1200); };
  r.onend = () => setTimeout(close, 800);
  r.start();
};
$("#msearch").onclick = () => { document.body.classList.add("msearch"); q.focus(); };
$("#mback").onclick = () => { document.body.classList.remove("msearch"); q.blur(); };

// ---------------------------------------------------------------- things switched off in Settings, titles in your language
const applyHidden = () => {
  document.documentElement.classList.toggle("no-music", !!settings.hideMusic);
  document.documentElement.classList.toggle("no-logo", !!settings.hideLogo);
};
applyHidden();
addEventListener("settings", (e) => { if (e.detail === "hideMusic" || e.detail === "hideLogo") applyHidden(); });
// its own file: it needs regular expressions older TV browsers don't have, and those just go without it
modeReady.then((pub) => { if (!pub) import("./titletr.js").then((m) => m.startTitleTr()).catch(() => { }); });

// ---------------------------------------------------------------- side menu
// installed as an app there is no browser bar: back and forward sit at the top of the side menu
if (navigator.standalone || ["standalone", "minimal-ui", "window-controls-overlay"].some((m) => matchMedia(`(display-mode: ${m})`).matches)) document.documentElement.classList.add("installed");
$("#gback").onclick = () => history.back();
$("#gfwd").onclick = () => history.forward();
if (window.navigation && "canGoForward" in navigation) {
  // dimmed when there is nowhere to go (browsers without the Navigation API leave both lit)
  const lit = () => { $("#gback").disabled = !navigation.canGoBack; $("#gfwd").disabled = !navigation.canGoForward; };
  navigation.addEventListener("currententrychange", lit);
  lit();
}
$("#guideBtn").onclick = () => document.body.classList.toggle("guide-open");
$("#scrim").onclick = () => document.body.classList.remove("guide-open");
addEventListener("click", (e) => { if (e.target.closest("#guide a[data-nav]")) document.body.classList.remove("guide-open"); });

// ---------------------------------------------------------------- notifications (new videos from subscriptions)
async function bell() {
  try {
    const f = await lib("feed", { ttl: 120000, quiet: true });
    const seen = local.get("bellSeen", 0);
    const fresh = f.items.filter((x) => x.published > seen && !x.short);
    const b = $("#bell .badge");
    b.textContent = fresh.length > 9 ? "9+" : fresh.length;
    b.hidden = !fresh.length;
    return f.items.filter((x) => !x.short).slice(0, 25);
  } catch { return []; }
}
$("#bell").onclick = async (e) => {
  const anchor = e.currentTarget;
  const items = await bell();
  const seen = local.get("bellSeen", 0);
  local.set("bellSeen", Math.floor(Date.now() / 1000));
  $("#bell .badge").hidden = true;
  popup(anchor, `<div class="notif"><div class="nt-h"><b>${$t("알림")}</b><a href="/settings" data-nav>${icon("gear")}</a></div>` +
    (items.length ? items.map((x) => `<a class="nt ${x.published > seen ? "new" : ""}" href="/watch?v=${esc(x.id)}" data-nav>
      <span class="nt-av"><img src="${esc(state.lib.subs[x.authorId]?.thumb || "")}" alt=""></span>
      <span class="nt-tx"><span>${$t("{0}에서 업로드한 동영상: {1}", esc(x.author), esc(x.title))}</span><em>${ago(x.published)}</em></span>
      <img class="nt-th" src="${thumb(x.id, "mqdefault")}" alt=""></a>`).join("") : `<div class="sub" style="padding:24px">${$t("새 소식이 없어요")}</div>`) + "</div>",
    () => { });
};
setInterval(bell, 10 * 60000);

// ---------------------------------------------------------------- account menu
$("#acct").onclick = (e) => {
  const th = { system: $t("기기 테마 사용"), dark: $t("어두운 테마"), light: $t("밝은 테마"), spatial: $t("유리 테마"), "spatial-light": $t("밝은 유리 테마") };
  popup(e.currentTarget, `<div class="acct-h">${icon("account")}<div><b>${esc(siteName())}</b><span>${$t("내 라이브러리")}</span></div></div><hr>` +
    mi("you", "libr", $t("내 페이지")) + mi("history", "history", $t("시청 기록")) + mi("subs", "subs", $t("구독 관리")) + "<hr>" +
    mi("theme", "dark", $t("디자인: ") + th[settings.theme]) + mi("lang", "globe", langLabel() + ": " + LANGS.find(([c]) => c === LANG)[1]) +
    mi("settings", "gear", $t("설정")) + mi("keys", "keyboard", $t("단축키")),
    (a) => {
      if (a === "you") navigate("/feed/you");
      if (a === "history") navigate("/feed/history");
      if (a === "subs") navigate("/feed/channels");
      if (a === "settings") navigate("/settings");
      if (a === "keys") keysDialog();
      if (a === "lang") langPicker(false);
      if (a === "theme") {
        const order = ["system", "dark", "light", "spatial", "spatial-light"];
        settings.theme = order[(order.indexOf(settings.theme) + 1) % order.length];
        toast($t("디자인: ") + th[settings.theme]);
      }
    });
};

// ---------------------------------------------------------------- language
// asked once on the first visit (the browser's language is preselected), and from the account menu
function langPicker(first) {
  const { box, close } = dialog(`<div class="dh"><b>${icon("globe")} ${langLabel()}</b>${first ? "" : `<button data-close>${icon("close")}</button>`}</div>
    <div class="langs">${LANGS.map(([c, n]) => `<button class="lang ${c === LANG ? "on" : ""}" data-l="${c}">${n}</button>`).join("")}</div>`, { cls: "narrow" });
  box.querySelectorAll("[data-l]").forEach((b) => (b.onclick = () => {
    settings.lang = b.dataset.l;
    if (b.dataset.l === LANG) close(); else location.reload();
  }));
  setTimeout(() => box.querySelector(".lang.on")?.focus(), 50);
}
if (FIRST_VISIT) setTimeout(() => langPicker(true), 300);

// ---------------------------------------------------------------- global shortcuts
addEventListener("keydown", (e) => {
  if (e.target.closest("input,textarea,select,[contenteditable]")) return;
  if (e.key === "/" ) { e.preventDefault(); q.focus(); return; }
  if (e.key === "?") { keysDialog(); return; }
  if (!player.info || $("#modal").className === "show") return;
  if (state.route === "watch" || player.mode === "mini" || state.route === "shorts") {
    if (state.route === "shorts" && (e.key === "ArrowUp" || e.key === "ArrowDown")) return;
    if (player.key(e)) e.preventDefault();
  }
});

// ---------------------------------------------------------------- start
// start-up steps: if one fails the page still comes up, and the failure is logged on the server
function step(name, fn) {
  window.__step = name;
  try { fn(); } catch (e) { console.error(name, e); window.__beacon?.({ ev: "initfail", step: name, msg: e && (e.stack || e.message || e) }); }
}
step("mini", initMini);
step("hover", initCardHover);
step("tv", initTV);
step("dearrow", initDeArrow);
step("avatars", initAvatars);
// pictures fade in once loaded (app.css: Motion): mark each picture when it is there, or when it failed
step("imgfx", () => {
  const done = (e) => { if (e.target instanceof HTMLImageElement) e.target.classList.add("ld"); };
  document.addEventListener("load", done, true);
  document.addEventListener("error", done, true);
  document.querySelectorAll("img").forEach((i) => { if (i.complete) i.classList.add("ld"); });
  document.documentElement.classList.add("imgfx");
});
step("cast", () => modeReady.then((pub) => { if (!pub) startReceiver(); }));   // no send-to-TV on public instances
addEventListener("settings", (e) => { if (e.detail === "castReceive") startReceiver(); });
loadLib().then(async (L) => {
  // first run: import the subscriptions of the Invidious account, if there are any
  if (!Object.keys(L.subs).length && !local.get("invImported", false)) {
    local.set("invImported", true);
    await P.importInvidious(false);
  }
  bell();
});
if (!history.state) history.replaceState({ k: "first" }, "");
window.__step = "render";
render().then(() => (window.__step = "rendered")).catch((e) => window.__beacon?.({ ev: "renderfail", msg: e && (e.stack || e.message) }));
if ("serviceWorker" in navigator) navigator.serviceWorker.getRegistrations().then((rs) => rs.forEach((r) => { if (r.scope === location.origin + "/") r.unregister(); })).catch(() => { });
