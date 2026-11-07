// Music: the apps' Music tab on the web. Albums and artists from YouTube Music, songs from YouTube,
// lyrics from LRCLIB, all through this server (/api/p/yt/music/*). Sound only: an <audio> element with
// Shaka leaving the video streams out, so a song plays like in a music app and keeps going on a locked phone.
import { $, $$, esc, icon, api, local, toast, thumb, fmtTime, el, IPHONE, siteName } from "./util.js";
import { player, navigate, popup, mi } from "./core.js";
import { loadShaka } from "./player.js";
import { $t, LANG, REGION } from "./i18n.js";
import { topicShelves } from "./pages.js";

// ---------------------------------------------------------------- server
const M = "/api/p/yt/music/";
const get = (what, p = {}, ttl = 600000) => api(`${M}${what}?${new URLSearchParams({ ...p, hl: LANG, region: REGION })}`, { ttl });
const img = (u) => `${M}img?u=${encodeURIComponent(u)}`;
// a song's picture: the album cover when YouTube Music gave one, else the video's thumbnail (cropped square by CSS)
const cover = (s) => (s.art ? img(s.art) : thumb(s.id, "mqdefault"));
const bigCover = (s) => (s.art ? img(s.art) : thumb(s.id, "maxresdefault"));
const imgTag = (s, big = false) => `<img loading="lazy" alt="" src="${esc(big ? bigCover(s) : cover(s))}"${!s.art && big
  ? ` onerror="this.onerror=null;this.src='${thumb(s.id, "hqdefault")}'" onload="if(this.naturalWidth<=120)this.src='${thumb(s.id, "hqdefault")}'"` : ""}>`;
const slim = (s) => ({ id: s.id, title: s.title || "", artist: s.artist || "", artistId: s.artistId || "", len: s.len || 0, art: s.art || "", album: s.album || "", albumId: s.albumId || "" });

// ---------------------------------------------------------------- what this browser remembers (only here)
const store = {
  get: (k) => { const v = local.get("music." + k, []); return Array.isArray(v) ? v : []; },
  set: (k, v) => local.set("music." + k, v),
  push(k, item, max) { const l = [item, ...store.get(k).filter((x) => x.id !== item.id)].slice(0, max); store.set(k, l); return l; },
  drop(k, id) { store.set(k, store.get(k).filter((x) => x.id !== id)); },
};
const liked = (id) => store.get("likes").some((x) => x.id === id);
function toggleLike(s) {
  if (!s) return;
  if (liked(s.id)) { store.drop("likes", s.id); toast($t("좋아요를 취소했어요")); }
  else { store.push("likes", slim(s), 500); toast($t("좋아요 표시한 곡에 추가했어요")); }
  paint();
}

// ---------------------------------------------------------------- the player
const audio = new Audio();
audio.preload = "auto";
const MP = { queue: [], i: -1, orig: null, shuffle: false, repeat: local.get("music.repeat", "off"), sp: null, from: "",
  radioFor: "", lyrics: null, lyricsFor: "", tab: local.get("music.tab", "queue"), open: false };
const cur = () => MP.queue[MP.i];
let seq = 0;

async function engine() {
  if (MP.sp) return MP.sp;
  await loadShaka();
  shaka.polyfill.installAll();
  const sp = new shaka.Player();
  await sp.attach(audio);
  sp.configure({
    manifest: { disableVideo: true, retryParameters: { maxAttempts: 4, baseDelay: 500, backoffFactor: 2, timeout: 20000 } },
    streaming: { bufferingGoal: 90, rebufferingGoal: 1, bufferBehind: 30,
      retryParameters: { maxAttempts: 8, baseDelay: 300, backoffFactor: 1.5, timeout: 30000, stallTimeout: 10000, connectionTimeout: 10000 } },
    abr: { enabled: false, defaultBandwidthEstimate: 1e9 },
  });
  // a stream that breaks while playing: the plain file from where it stopped
  sp.addEventListener("error", (e) => { const s = cur(); if (e.detail?.severity === 2 && s && MP.loaded === s.id && MP.direct !== s.id) direct(s, audio.currentTime); });
  return (MP.sp = sp);
}
// the plain way: the m4a sound track as a file (iPhone without MSE, or when the manifest fails)
async function direct(s, at = 0) {
  if (!s || MP.direct === s.id) { if (s) failed(s); return; }
  MP.direct = s.id;
  await dropEngine();
  if (cur() !== s) return;
  audio.src = `/latest_version?id=${encodeURIComponent(s.id)}&itag=140&local=true`;
  if (at) audio.addEventListener("loadedmetadata", () => { audio.currentTime = at; }, { once: true });
  audio.play().catch(() => { });
}
function failed(s) {
  toast($t("이 곡은 재생할 수 없어요"));
  if (MP.i + 1 < MP.queue.length) setTimeout(() => { if (cur() === s) start(MP.i + 1); }, 1200);
}

// Shaka lets go of the <audio> element (it would keep its own MediaSource on it)
async function dropEngine() {
  const sp = MP.sp;
  MP.sp = null;
  MP.loaded = "";
  if (sp) try { await sp.destroy(); } catch { }
}
// ---------------------------------------------------------------- a music video -> the song's own recording
// Lyrics are timed for the song; a music video adds an intro, a scene, an ending, so they would run early.
// Like YouTube Music, a music video plays as the song (its album track), found once and kept here.
const VIDEOISH = /\b(M\/?V|music video|official (video|audio)|lyrics?( video)?|visuali[sz]er|audio)\b|뮤직비디오|뮤비|[‘’'"“”\[\]|]/i;
const songOfs = () => { const o = local.get("music.songof", {}); return o && typeof o === "object" ? o : {}; };
const asked = new Map();
function songOf(s) {
  if (!s || s.albumId || s.mv || / - Topic$/.test(s.artist) || !VIDEOISH.test(s.title)) return Promise.resolve(null);
  const known = songOfs()[s.id];
  if (known !== undefined) return Promise.resolve(known || null);
  if (!asked.has(s.id)) {
    asked.set(s.id, get("song", { v: s.id, title: s.title, artist: s.artist, len: s.len || 0 }, 21600000)
      .then((d) => {
        const r = d?.song?.id ? slim(d.song) : null;
        const o = songOfs();
        o[s.id] = r || 0;
        const ks = Object.keys(o);
        if (ks.length > 600) for (const k of ks.slice(0, ks.length - 600)) delete o[k];
        local.set("music.songof", o);
        return r;
      }, () => null));        // not now (YouTube didn't answer): the video, and ask again next time
  }
  return asked.get(s.id);
}
const within = (p, ms) => Promise.race([p, new Promise((ok) => setTimeout(() => ok(null), ms))]);
// the same sound already (a few seconds of rounding): keep what was chosen
const swapTo = (s, r) => r && r.id !== s.id && !(s.len && r.len && Math.abs(s.len - r.len) <= 2);

async function start(i, { at = 0 } = {}) {
  let s = MP.queue[i];
  if (!s) return;
  MP.i = i;
  MP.direct = "";
  MP.loaded = "";
  const my = ++seq;
  if (player.video && !player.video.paused) player.video.pause();
  show();
  paint();
  session();
  const r = await within(songOf(s), 4000);
  if (my !== seq) return;
  if (swapTo(s, r)) {
    s = MP.queue[i] = { ...r, mv: s.id, auto: s.auto };
    if (MP.orig) MP.orig = MP.orig.map((x) => (x.id === s.mv ? s : x));
    at = 0;
    paint();
    paintQueue();
  }
  // the next one, while this one plays
  setTimeout(() => songOf(MP.queue[MP.i + 1]), 3000);
  store.push("recent", slim(s), 80);
  if (MP.tab === "lyrics" && MP.open) loadLyrics();
  const plain = IPHONE || !(window.MediaSource || window.ManagedMediaSource);
  if (plain) { MP.direct = ""; return direct(s, at); }
  try {
    const sp = await engine();
    if (my !== seq) return;
    await sp.load(`/api/manifest/dash/id/${encodeURIComponent(s.id)}?local=true`, at || 0);
    if (my !== seq) return;
    MP.loaded = s.id;
    audio.play().catch(() => { });
  } catch (e) {
    if (my !== seq || e?.code === 7000) return;
    direct(s, at);
  }
  // near the end of the list: carry on with songs like the last one (as music apps do)
  if (MP.repeat === "off" && MP.i >= MP.queue.length - 2) extend();
}
async function extend() {
  const s = MP.queue[MP.queue.length - 1];
  if (!s || MP.radioFor === s.id) return;
  MP.radioFor = s.id;
  try {
    const d = await get("mix", { list: "RDAMVM" + s.id, v: s.id });
    const have = new Set(MP.queue.map((x) => x.id));
    const add = (d.songs || []).filter((x) => !have.has(x.id)).map((x) => ({ ...slim(x), auto: true }));
    if (add.length) { MP.queue.push(...add); if (MP.orig) MP.orig.push(...add); paintQueue(); }
  } catch { MP.radioFor = ""; }
}
function next(auto = false) {
  if (auto && MP.repeat === "one") { audio.currentTime = 0; audio.play().catch(() => { }); return; }
  if (MP.i + 1 < MP.queue.length) return start(MP.i + 1);
  if (MP.repeat === "all" && MP.queue.length) return start(0);
  if (auto) paint();
}
function prev() {
  if (audio.currentTime > 3 || MP.i <= 0) { audio.currentTime = 0; return; }
  start(MP.i - 1);
}
const toggle = () => { if (!cur()) return; if (audio.paused) { if (player.video && !player.video.paused) player.video.pause(); audio.play().catch(() => { }); } else audio.pause(); };
function shuffleArr(a) { for (let k = a.length - 1; k > 0; k--) { const j = Math.floor(Math.random() * (k + 1)); [a[k], a[j]] = [a[j], a[k]]; } return a; }
function setShuffle(on) {
  const c = cur();
  if (on && !MP.shuffle) {
    MP.orig = MP.queue.slice();
    const rest = shuffleArr(MP.queue.filter((_, k) => k !== MP.i));
    MP.queue = c ? [c, ...rest] : rest;
    MP.i = c ? 0 : -1;
  } else if (!on && MP.shuffle && MP.orig) {
    MP.queue = MP.orig;
    MP.i = Math.max(0, MP.queue.indexOf(c));
    MP.orig = null;
  }
  MP.shuffle = on;
  paint();
  paintQueue();
}
function setRepeat() {
  MP.repeat = { off: "all", all: "one", one: "off" }[MP.repeat] || "off";
  local.set("music.repeat", MP.repeat);
  toast({ off: $t("반복 안 함"), all: $t("전체 반복"), one: $t("한 곡 반복") }[MP.repeat]);
  paint();
}
/** play these songs from song i (shuffled, or followed by a radio when the list runs out) */
export function playList(songs, i = 0, { shuffle = false, from = "" } = {}) {
  songs = (songs || []).filter((s) => s && s.id);
  if (!songs.length) return;
  MP.queue = songs.map(slim);
  MP.orig = null;
  MP.shuffle = false;
  MP.radioFor = "";
  MP.from = from;
  MP.i = shuffle ? Math.floor(Math.random() * songs.length) : Math.max(0, Math.min(i, songs.length - 1));
  if (shuffle) setShuffle(true);
  start(MP.i);
}
/** a song and then the songs like it */
export async function playRadio(s, from = "") {
  playList([s], 0, { from: from || $t("{0} 라디오", s.title) });
  MP.radioFor = "";
  extend();
}
async function playMix(list, opts = {}) {
  try {
    const d = await get("mix", { list });
    if (!d.songs?.length) throw 0;
    playList(d.songs, 0, opts);
  } catch { toast($t("불러오지 못했어요")); }
}
function playNext(s) {
  if (!cur()) return playList([s]);
  MP.queue.splice(MP.i + 1, 0, slim(s));
  if (MP.orig) MP.orig.push(slim(s));
  paintQueue();
  toast($t("다음에 재생해요"));
}
function addQueue(s) {
  if (!cur()) return playList([s]);
  MP.queue.push(slim(s));
  if (MP.orig) MP.orig.push(slim(s));
  paintQueue();
  toast($t("대기열에 추가했어요"));
}
function stop() {
  seq++;
  audio.pause();
  dropEngine().then(() => { if (!cur()) { audio.removeAttribute("src"); audio.load(); } });
  MP.queue = []; MP.i = -1; MP.orig = null; MP.shuffle = false;
  closeFull();
  $("#mbar")?.setAttribute("hidden", "");
  document.body.classList.remove("has-mbar");
  if (navigator.mediaSession) navigator.mediaSession.metadata = null;
}

// a video starting stops the music, and the other way round (start() pauses the video)
if (player.video) player.video.addEventListener("play", () => { if (!audio.paused) audio.pause(); });

// lock screen / headphone buttons
function session() {
  const ms = navigator.mediaSession, s = cur();
  if (!ms || !s || typeof MediaMetadata === "undefined") return;
  const src = new URL(s.art ? img(s.art) : thumb(s.id, "hqdefault"), location.href).href;
  ms.metadata = new MediaMetadata({ title: s.title, artist: s.artist, album: s.album || "", artwork: [{ src, sizes: "544x544", type: "image/jpeg" }] });
}
if (navigator.mediaSession) {
  const ms = navigator.mediaSession;
  const on = (a, f) => { try { ms.setActionHandler(a, f); } catch { } };
  on("play", () => toggle());
  on("pause", () => audio.pause());
  on("previoustrack", () => prev());
  on("nexttrack", () => next());
  on("seekto", (d) => { if (d.seekTime != null) audio.currentTime = d.seekTime; });
  on("seekbackward", (d) => { audio.currentTime = Math.max(0, audio.currentTime - (d.seekOffset || 10)); });
  on("seekforward", (d) => { audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + (d.seekOffset || 10)); });
}

// ---------------------------------------------------------------- the bar at the bottom and the full screen
const eq = `<span class="m-eq" aria-hidden="true"><i></i><i></i><i></i></span>`;
function show() {
  if (!$("#mbar")) build();
  $("#mbar").removeAttribute("hidden");
  document.body.classList.add("has-mbar");
}
function build() {
  const bar = el(`<div id="mbar" hidden role="region" aria-label="${$t("음악 플레이어")}">
    <div class="mb-prog"><i></i></div>
    <button class="mb-info" data-mb="full" aria-label="${$t("재생 화면 열기")}"><span class="mb-art"></span><span class="mb-tx"><b></b><span></span></span></button>
    <div class="mb-ctl">
      <button class="ib mb-prev" data-mb="prev" aria-label="${$t("이전 곡")}">${icon("prev")}</button>
      <button class="ib mb-play" data-mb="play" aria-label="${$t("재생")}">${icon("play")}</button>
      <button class="ib mb-next" data-mb="next" aria-label="${$t("다음 곡")}">${icon("next")}</button>
    </div>
    <div class="mb-end">
      <span class="mb-time"></span>
      <button class="ib mb-like" data-mb="like" aria-label="${$t("좋아요")}">${icon("heart")}</button>
      <button class="ib" data-mb="lyrics" aria-label="${$t("가사")}">${icon("lyrics")}</button>
      <button class="ib" data-mb="queue" aria-label="${$t("다음 곡 목록")}">${icon("qmusic")}</button>
      <button class="ib" data-mb="close" aria-label="${$t("음악 닫기")}">${icon("close")}</button>
    </div></div>`);
  document.body.append(bar);
  bar.addEventListener("click", (e) => {
    const b = e.target.closest("[data-mb]");
    if (!b) return;
    const a = b.dataset.mb;
    if (a === "full") openFull();
    else if (a === "play") toggle();
    else if (a === "prev") prev();
    else if (a === "next") next();
    else if (a === "like") toggleLike(cur());
    else if (a === "lyrics" || a === "queue") openFull(a);
    else if (a === "close") stop();
  });
  // tap / drag on the thin line seeks
  const pr = $(".mb-prog", bar);
  pr.addEventListener("click", (e) => { const r = pr.getBoundingClientRect(); if (audio.duration) audio.currentTime = ((e.clientX - r.left) / r.width) * audio.duration; });

  const full = el(`<div id="mfull" hidden role="dialog" aria-label="${$t("재생 중")}">
    <div class="mf-bg"></div>
    <div class="mf-top">
      <button class="ib mf-down" data-mf="close" aria-label="${$t("닫기")}">${icon("down")}</button>
      <div class="mf-from"><small>${$t("재생 중")}</small><b></b></div>
      <button class="ib" data-mf="more" aria-label="${$t("작업 메뉴")}">${icon("more")}</button>
    </div>
    <div class="mf-body">
      <div class="mf-main">
        <div class="mf-cover"></div>
        <div class="mf-meta">
          <div class="mf-tx"><b class="mf-t"></b><a class="mf-a" data-nav></a></div>
          <button class="ib mf-like" data-mf="like" aria-label="${$t("좋아요")}">${icon("heart")}</button>
        </div>
        <div class="mf-seek"><input type="range" min="0" max="1000" value="0" step="1" aria-label="${$t("재생 위치")}"><div class="mf-times"><span>0:00</span><span>0:00</span></div></div>
        <div class="mf-ctl">
          <button class="ib mf-sh" data-mf="shuffle" aria-label="${$t("셔플")}">${icon("shuffle")}</button>
          <button class="ib mf-pv" data-mf="prev" aria-label="${$t("이전 곡")}">${icon("prev")}</button>
          <button class="mf-play" data-mf="play" aria-label="${$t("재생")}">${icon("play")}</button>
          <button class="ib mf-nx" data-mf="next" aria-label="${$t("다음 곡")}">${icon("next")}</button>
          <button class="ib mf-rp" data-mf="repeat" aria-label="${$t("반복")}">${icon("loop")}</button>
        </div>
      </div>
      <div class="mf-side">
        <div class="mf-tabs" role="tablist">
          <button role="tab" data-tab="queue">${$t("다음 곡")}</button>
          <button role="tab" data-tab="lyrics">${$t("가사")}</button>
        </div>
        <div class="mf-panel mf-queue"></div>
        <div class="mf-panel mf-lyrics" hidden></div>
      </div>
    </div></div>`);
  document.body.append(full);
  full.addEventListener("click", (e) => {
    const t = e.target.closest("[data-tab]");
    if (t) return setTab(t.dataset.tab, true);
    const q = e.target.closest(".mf-queue .m-row");
    if (q && !e.target.closest("[data-mmore]")) { const k = +q.dataset.k; if (k === MP.i) toggle(); else start(k); return; }
    const ly = e.target.closest(".ly-line[data-ms]");
    if (ly && MP.lyrics?.synced && MP.lySync) { setLyOff(Math.round(audio.currentTime * 1000) - +ly.dataset.ms, true); return; }
    if (ly && MP.lyrics?.synced) { audio.currentTime = Math.max(0, (+ly.dataset.ms + lyOff()) / 1000 + 0.01); if (audio.paused) toggle(); return; }
    const lo = e.target.closest("[data-lyoff]");
    if (lo) { const v = lo.dataset.lyoff; if (v === "sync") { MP.lySync = !MP.lySync; paintLySync(); } else setLyOff(v === "0" ? 0 : lyOff() + +v); return; }
    const al = e.target.closest(".mf-a[href]");
    if (al) { e.preventDefault(); e.stopPropagation(); const h = al.getAttribute("href"); closeFull(() => navigate(h)); return; }
    const b = e.target.closest("[data-mf]");
    if (!b) return;
    const a = b.dataset.mf;
    if (a === "close") closeFull();
    else if (a === "play") toggle();
    else if (a === "prev") prev();
    else if (a === "next") next();
    else if (a === "shuffle") setShuffle(!MP.shuffle);
    else if (a === "repeat") setRepeat();
    else if (a === "like") toggleLike(cur());
    else if (a === "more" && cur()) songMenu(b, cur(), { playing: true });
  });
  const range = $(".mf-seek input", full);
  range.addEventListener("input", () => { MP.seeking = true; $(".mf-times span", full).textContent = fmtTime((range.value / 1000) * (audio.duration || 0)); paintRange(); });
  range.addEventListener("change", () => { if (audio.duration) audio.currentTime = (range.value / 1000) * audio.duration; MP.seeking = false; });
  // reading the lyrics by hand: stop following the song for a moment
  const lyr = $(".mf-lyrics", full);
  for (const ev of ["wheel", "touchmove"]) lyr.addEventListener(ev, () => { MP.handScroll = Date.now(); }, { passive: true });
  // swipe the cover down to close (phones)
  let y0 = null;
  $(".mf-top", full).addEventListener("touchstart", (e) => { y0 = e.touches[0].clientY; }, { passive: true });
  full.addEventListener("touchend", (e) => { if (y0 != null && e.changedTouches[0].clientY - y0 > 90) closeFull(); y0 = null; }, { passive: true });
}
function openFull(tab) {
  if (!cur()) return;
  show();
  const f = $("#mfull");
  MP.open = true;
  f.removeAttribute("hidden");
  requestAnimationFrame(() => f.classList.add("in"));
  document.documentElement.classList.add("mfull-open");
  // phones: the lyrics or the list take the cover's place only when asked for
  f.classList.toggle("panel", !!tab);
  setTab(tab || MP.tab);
  paint();
  if (!history.state?.mfull) history.pushState({ ...(history.state || {}), mfull: 1 }, "", location.href);
}
function hideFull() {
  const f = $("#mfull");
  if (!f || !MP.open) return false;
  MP.open = false;
  f.classList.remove("in");
  document.documentElement.classList.remove("mfull-open");
  setTimeout(() => { if (!MP.open) f.setAttribute("hidden", ""); }, 260);
  return true;
}
/** close the full screen, then (after its history entry is gone) do `then`, e.g. open a page */
function closeFull(then) {
  const was = hideFull();
  if (was && history.state?.mfull) { MP.after = typeof then === "function" ? then : null; MP.backing = true; history.back(); }
  else if (typeof then === "function") then();
}
// the back button (and a phone's back gesture) closes the full screen first; the capture phase runs before the router's
addEventListener("popstate", (e) => {
  if (MP.backing) {
    MP.backing = false;
    e.stopImmediatePropagation();
    const f = MP.after;
    MP.after = null;
    if (f) f();
  } else if (MP.open) { e.stopImmediatePropagation(); hideFull(); }
}, true);
addEventListener("keydown", (e) => {
  if (!MP.open) return;
  if (e.key === "Escape") { e.preventDefault(); closeFull(); }
  else if (e.key === " " && !e.target.closest("input, textarea, button, a")) { e.preventDefault(); e.stopPropagation(); toggle(); }
  else if (e.key === "ArrowRight" && !e.target.closest("input")) { e.stopPropagation(); audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + 5); }
  else if (e.key === "ArrowLeft" && !e.target.closest("input")) { e.stopPropagation(); audio.currentTime = Math.max(0, audio.currentTime - 5); }
}, true);
function setTab(t, tapped = false) {
  const was = MP.tab;
  MP.tab = t === "lyrics" ? "lyrics" : "queue";
  local.set("music.tab", MP.tab);
  const f = $("#mfull");
  if (!f) return;
  // phones: tapping a tab opens its panel over the cover; tapping the open one again closes it
  if (tapped) f.classList.toggle("panel", !(was === MP.tab && f.classList.contains("panel")));
  $$(".mf-tabs [data-tab]", f).forEach((b) => { b.classList.toggle("on", b.dataset.tab === MP.tab); b.setAttribute("aria-selected", b.dataset.tab === MP.tab); });
  $(".mf-queue", f).hidden = MP.tab !== "queue";
  $(".mf-lyrics", f).hidden = MP.tab !== "lyrics";
  f.classList.toggle("lyr", MP.tab === "lyrics");
  if (MP.tab === "lyrics") loadLyrics(); else paintQueue(true);
}

function paint() {
  const s = cur();
  const bar = $("#mbar"), f = $("#mfull");
  if (!s || !bar) return;
  const playing = !audio.paused;
  const pic = imgTag(s);
  if (bar.dataset.id !== s.id) {
    bar.dataset.id = s.id;
    $(".mb-art", bar).innerHTML = pic;
    $(".mb-tx b", bar).textContent = s.title;
    $(".mb-tx span", bar).textContent = s.artist;
  }
  $(".mb-play", bar).innerHTML = icon(playing ? "pause" : "play");
  $(".mb-play", bar).setAttribute("aria-label", playing ? $t("일시정지") : $t("재생"));
  $(".mb-like", bar).innerHTML = icon("heart", liked(s.id) ? "fill" : "");
  $(".mb-like", bar).classList.toggle("on", liked(s.id));
  bar.classList.toggle("playing", playing);
  if (f) {
    if (f.dataset.id !== s.id) {
      f.dataset.id = s.id;
      $(".mf-cover", f).innerHTML = imgTag(s, true);
      $(".mf-bg", f).style.backgroundImage = `url("${s.art ? img(s.art) : thumb(s.id, "hqdefault")}")`;
      $(".mf-t", f).textContent = s.title;
      const a = $(".mf-a", f);
      a.textContent = s.artist;
      if (s.artistId && /^UC/.test(s.artistId)) a.setAttribute("href", `/feed/music/artist/${s.artistId}`); else a.removeAttribute("href");
    }
    $(".mf-from b", f).textContent = MP.from || s.album || s.artist || "";
    $(".mf-play", f).innerHTML = icon(playing ? "pause" : "play");
    $(".mf-like", f).innerHTML = icon("heart", liked(s.id) ? "fill" : "");
    $(".mf-like", f).classList.toggle("on", liked(s.id));
    $(".mf-sh", f).classList.toggle("on", MP.shuffle);
    $(".mf-rp", f).classList.toggle("on", MP.repeat !== "off");
    $(".mf-rp", f).innerHTML = icon(MP.repeat === "one" ? "loop1" : "loop");
    f.classList.toggle("playing", playing);
    if (MP.open) paintQueue();
  }
  // the lists on the page: which song is playing
  $$(".m-row[data-id]").forEach((r) => {
    const on = r.dataset.id === s.id && (!r.closest(".mf-queue") || +r.dataset.k === MP.i);
    r.classList.toggle("on", on);
    r.classList.toggle("playing", on && playing);
  });
}
let queueSig = "";
function paintQueue(force = false) {
  const f = $("#mfull");
  if (!f || !MP.open || MP.tab !== "queue") return;
  const box = $(".mf-queue", f);
  const sig = MP.i + "|" + MP.queue.map((x) => x.id).join(",");
  if (!force && sig === queueSig) return;
  queueSig = sig;
  const up = MP.queue.slice(MP.i + 1);
  const firstAuto = up.findIndex((x) => x.auto);
  box.innerHTML = (cur() ? `<div class="mq-h">${$t("재생 중")}</div>${row(cur(), MP.i, { queue: true })}` : "")
    + (up.length ? `<div class="mq-h">${$t("다음 곡")}${MP.shuffle ? ` · ${$t("셔플")}` : ""}</div>` : `<div class="mq-empty">${$t("다음 곡이 없어요")}</div>`)
    + up.map((s, k) => (k === firstAuto ? `<div class="mq-h mq-auto">${icon("radio")}${$t("비슷한 곡 자동 재생")}</div>` : "") + row(s, MP.i + 1 + k, { queue: true })).join("");
}

// progress
let lastPos = 0;
audio.addEventListener("timeupdate", () => {
  const d = audio.duration || cur()?.len || 0, t = audio.currentTime;
  const bar = $("#mbar");
  if (bar) {
    $(".mb-prog i", bar).style.width = d ? (t / d) * 100 + "%" : "0";
    $(".mb-time", bar).textContent = `${fmtTime(t)} / ${fmtTime(d)}`;
  }
  const f = $("#mfull");
  if (f && MP.open && !MP.seeking) {
    $(".mf-seek input", f).value = d ? Math.round((t / d) * 1000) : 0;
    const sp = $$(".mf-times span", f);
    sp[0].textContent = fmtTime(t);
    sp[1].textContent = fmtTime(d);
    paintRange();
  }
  if (MP.open && MP.tab === "lyrics") followLyrics(t);
  const ms = navigator.mediaSession;
  if (ms?.setPositionState && d && Math.abs(t - lastPos) > 4) {
    lastPos = t;
    try { ms.setPositionState({ duration: d, position: Math.min(t, d), playbackRate: audio.playbackRate || 1 }); } catch { }
  }
});
function paintRange() {
  const r = $("#mfull .mf-seek input");
  if (r) r.style.setProperty("--p", r.value / 10 + "%");
}
for (const ev of ["play", "pause", "playing"]) audio.addEventListener(ev, () => paint());
audio.addEventListener("waiting", () => $("#mbar")?.classList.add("wait"));
audio.addEventListener("playing", () => $("#mbar")?.classList.remove("wait"));
audio.addEventListener("ended", () => next(true));
audio.addEventListener("error", () => { const s = cur(); if (s && MP.direct === s.id) failed(s); });

// ---------------------------------------------------------------- lyrics (LRCLIB, asked only when opened)
async function loadLyrics() {
  const s = cur();
  const box = $("#mfull .mf-lyrics");
  if (!s || !box) return;
  if (MP.lyricsFor === s.id) return;
  MP.lyricsFor = s.id;
  MP.lyrics = null;
  MP.lySync = false;
  box.innerHTML = `<div class="ly-wait"><span class="spin"></span>${$t("가사를 찾는 중")}</div>`;
  try {
    const d = await get("lyrics", { v: s.id, title: s.title, artist: s.artist, len: s.len || Math.round(audio.duration) || 0 }, 3600000);
    if (cur() !== s) return;
    MP.lyrics = d;
    if (!d.lines?.length) {
      box.innerHTML = `<div class="ly-none">${icon("lyrics")}<p>${$t("이 곡의 가사를 찾지 못했어요")}</p></div>`;
      return;
    }
    // a music video longer than the song the times were made for: they may run early or late
    const vlen = s.len || Math.round(audio.duration) || 0;
    const off = d.synced && d.len && vlen && Math.abs(vlen - d.len) > 2;
    box.innerHTML = `${d.synced ? `<div class="ly-sync${off ? " warn" : ""}">
        <span class="ly-sync-t">${off ? $t("영상이 노래보다 {0}초 길어서 가사가 어긋날 수 있어요", Math.abs(vlen - d.len)) : $t("가사 타이밍")}</span>
        <div class="ly-sync-b"><button data-lyoff="-500" aria-label="${$t("가사 빨리")}">−0.5${$t("초")}</button><output></output><button data-lyoff="500" aria-label="${$t("가사 늦게")}">+0.5${$t("초")}</button>
        <button class="ly-sync-tap" data-lyoff="sync">${$t("지금 부르는 줄 누르기")}</button></div></div>` : ""}<div class="ly ${d.synced ? "synced" : ""}">${d.synced ? '<div class="ly-pad"></div>' : ""}${d.lines.map(([ms, w]) =>
      w ? `<p class="ly-line"${d.synced ? ` data-ms="${ms}"` : ""}>${esc(w)}</p>` : `<p class="ly-gap">${d.synced ? "♪" : ""}</p>`).join("")}${d.synced ? '<div class="ly-pad"></div>' : ""}
      <div class="ly-src">${$t("가사 제공: {0}", "LRCLIB")}</div></div>`;
    MP.lyLine = -1;
    paintLySync();
    followLyrics(audio.currentTime, true);
  } catch {
    if (cur() !== s) return;
    MP.lyricsFor = "";
    box.innerHTML = `<div class="ly-none">${icon("info")}<p>${$t("가사를 불러오지 못했어요")}</p><button class="pill" onclick="this.closest('.mf-lyrics').dispatchEvent(new Event('retry'))">${$t("다시 시도")}</button></div>`;
    box.addEventListener("retry", loadLyrics, { once: true });
  }
}
// the listener's nudge for a song's lyrics (ms; more = later), kept in this browser for the next time
const lyOffs = () => { const o = local.get("music.lyoff", {}); return o && typeof o === "object" ? o : {}; };
function lyOff() { const s = cur(); return (s && lyOffs()[s.id]) || 0; }
function setLyOff(v, done) {
  const s = cur();
  if (!s) return;
  const o = lyOffs();
  v = Math.max(-120000, Math.min(120000, Math.round(v / 50) * 50));
  delete o[s.id];
  if (v) o[s.id] = v;
  const ks = Object.keys(o);
  if (ks.length > 400) for (const k of ks.slice(0, ks.length - 400)) delete o[k];
  local.set("music.lyoff", o);
  if (done) MP.lySync = false;
  paintLySync();
  followLyrics(audio.currentTime, true);
}
function paintLySync() {
  const bar = $("#mfull .ly-sync");
  if (!bar) return;
  const v = lyOff();
  $("output", bar).textContent = v ? (v > 0 ? "+" : "−") + (Math.abs(v) / 1000).toFixed(1) + $t("초") : "0";
  bar.classList.toggle("tap", !!MP.lySync);
  $(".mf-lyrics", $("#mfull"))?.classList.toggle("ly-tapping", !!MP.lySync);
  $(".ly-sync-tap", bar).textContent = MP.lySync ? $t("지금 부르는 줄을 눌러 주세요") : $t("지금 부르는 줄 누르기");
}
function followLyrics(t, jump = false) {
  const d = MP.lyrics;
  if (!d?.synced) return;
  const ms = t * 1000 + 100 - lyOff();
  let k = -1;
  for (let j = 0; j < d.lines.length; j++) { if (d.lines[j][0] <= ms) k = j; else break; }
  if (k === MP.lyLine && !jump) return;
  MP.lyLine = k;
  const box = $("#mfull .mf-lyrics");
  const ps = $$(".ly > p", box);
  ps.forEach((p, j) => { p.classList.toggle("on", j === k); p.classList.toggle("past", j < k); });
  const p = ps[k];
  if (p && Date.now() - (MP.handScroll || 0) > 3500) {
    box.scrollTo({ top: p.offsetTop - box.clientHeight * 0.38, behavior: jump ? "auto" : "smooth" });
  }
}

// ---------------------------------------------------------------- song rows, cards, menus
const LISTS = new Map();
let listKey = 0;
function reg(songs, mode) { LISTS.set(++listKey, { songs, mode }); if (LISTS.size > 80) LISTS.delete(LISTS.keys().next().value); return listKey; }
function row(s, k, { num = false, album = true, queue = false } = {}) {
  const sub = [s.artist, album && s.album ? s.album : ""].filter(Boolean).join(" · ");
  return `<div class="m-row${num ? " num" : ""}" data-k="${k}" data-id="${esc(s.id)}" tabindex="0">
    ${num ? `<span class="m-num"><i>${k + 1}</i>${eq}</span>` : `<span class="m-art">${imgTag(s)}${eq}<span class="m-hov">${icon("play")}</span></span>`}
    <span class="m-tx"><b>${esc(s.title)}</b>${sub ? `<span>${esc(sub)}</span>` : ""}</span>
    ${s.len ? `<span class="m-len">${fmtTime(s.len)}</span>` : ""}
    <button class="ib m-more" data-mmore aria-label="${$t("작업 메뉴")}">${icon("more")}</button></div>`;
}
/** a list of songs: "list" plays them from the one clicked, "radio" plays the one clicked and songs like it */
function songList(songs, { mode = "list", cls = "", num = false, album = true, limit = 0 } = {}) {
  const k = reg(songs, mode);
  const rows = songs.map((s, i) => row(s, i, { num, album }).replace(/^<div class="m-row/, limit && i >= limit ? '<div hidden class="m-row' : '<div class="m-row')).join("");
  return `<div class="m-list ${cls}" data-ml="${k}">${rows}</div>${limit && songs.length > limit ? `<button class="pill ghost m-morebtn" data-mexpand>${$t("{0}곡 더보기", songs.length - limit)}</button>` : ""}`;
}
const hrefOf = (c) => (c.kind === "artist" ? `/feed/music/artist/${c.id}` : c.kind === "album" ? `/feed/music/album/${c.id}`
  : c.kind === "playlist" ? `/playlist?list=${c.id.replace(/^VL/, "")}` : "");
const CARDS = new Map();
function mcard(c) {
  const href = hrefOf(c);
  if (!href) return "";
  CARDS.set(c.kind + ":" + c.id, c);
  return `<a class="m-card ${esc(c.kind)}" href="${esc(href)}" data-nav data-mc="${esc(c.kind)}:${esc(c.id)}">
    <span class="m-cv">${c.art ? `<img loading="lazy" alt="" src="${esc(img(c.art))}">` : icon(c.kind === "artist" ? "person" : "album")}
      <button class="m-cplay" data-mcplay aria-label="${$t("재생")}">${icon("play")}</button></span>
    <b>${esc(c.title)}</b>${c.sub ? `<span>${esc(c.sub)}</span>` : ""}</a>`;
}
const cardRow = (cards) => `<div class="m-hrow">${cards.map(mcard).join("")}</div>`;
async function playCard(c) {
  try {
    if (c.kind === "album") { const a = await get("album", { id: c.id }); playList(a.songs, 0, { from: a.title }); }
    else if (c.kind === "artist") {
      const a = await get("artist", { id: c.id });
      if (a.shuffleId) playMix(a.shuffleId, { from: a.name });
      else playList(a.songs, 0, { shuffle: true, from: a.name });
    } else if (c.kind === "playlist") playMix(c.id.replace(/^VL/, ""), { from: c.title });
  } catch { toast($t("불러오지 못했어요")); }
}
function songMenu(anchor, s, { playing = false } = {}) {
  const items = [
    playing ? "" : mi("next", "next", $t("다음에 재생")),
    playing ? "" : mi("queue", "save", $t("대기열에 추가")),
    mi("radio", "radio", $t("비슷한 곡 라디오")),
    mi("like", "heart", liked(s.id) ? $t("좋아요 취소") : $t("좋아요")),
    s.albumId ? mi("album", "album", $t("앨범으로 이동")) : "",
    s.artistId && /^UC/.test(s.artistId) ? mi("artist", "person", $t("아티스트로 이동")) : "",
    mi("video", "movie", $t("동영상으로 보기")),
    mi("share", "share", $t("공유")),
    playing ? mi("stop", "close", $t("음악 닫기")) : "",
  ].join("");
  popup(anchor, items, (a) => {
    if (a === "next") playNext(s);
    else if (a === "queue") addQueue(s);
    else if (a === "radio") playRadio(s);
    else if (a === "like") toggleLike(s);
    else if (a === "album") closeFull(() => navigate(`/feed/music/album/${s.albumId}`));
    else if (a === "artist") closeFull(() => navigate(`/feed/music/artist/${s.artistId}`));
    else if (a === "video") { audio.pause(); closeFull(() => navigate(`/watch?v=${s.id}`)); }
    else if (a === "stop") stop();
    else if (a === "share") {
      const u = `https://www.youtube.com/watch?v=${s.id}`;
      if (navigator.share) navigator.share({ title: s.title, url: u }).catch(() => { });
      else navigator.clipboard?.writeText(u).then(() => toast($t("링크를 복사했어요")), () => { });
    }
  });
}

// clicks anywhere in the music pages (capture: before the router turns a card into a page change)
document.addEventListener("click", (e) => {
  const t = e.target;
  const more = t.closest("[data-mmore]");
  if (more) {
    e.preventDefault(); e.stopPropagation();
    const r = more.closest(".m-row");
    const inQueue = r.closest(".mf-queue");
    const s = inQueue ? MP.queue[+r.dataset.k] : LISTS.get(+r.closest("[data-ml]")?.dataset.ml)?.songs[+r.dataset.k];
    if (s) songMenu(more, s, { playing: inQueue && +r.dataset.k === MP.i });
    return;
  }
  const cp = t.closest("[data-mcplay]");
  if (cp) {
    e.preventDefault(); e.stopPropagation();
    const c = CARDS.get(cp.closest("[data-mc]").dataset.mc);
    if (c) playCard(c);
    return;
  }
  const ex = t.closest("[data-mexpand]");
  if (ex) {
    const list = ex.previousElementSibling;
    const open = ex.classList.toggle("open");
    $$(".m-row", list).forEach((r, i) => { if (i >= 5) r.hidden = !open; });
    ex.textContent = open ? $t("간략히 보기") : $t("{0}곡 더보기", $$(".m-row", list).length - 5);
    return;
  }
  const r = t.closest("[data-ml] .m-row");
  if (r && !t.closest("a")) {
    const L = LISTS.get(+r.closest("[data-ml]").dataset.ml);
    if (!L) return;
    const k = +r.dataset.k, s = L.songs[k];
    if (cur()?.id === s.id) { toggle(); return; }
    if (L.mode === "radio") playRadio(s); else playList(L.songs, k, { from: L.from || "" });
  }
}, true);
document.addEventListener("keydown", (e) => {
  if ((e.key === "Enter" || e.key === " ") && e.target.matches?.(".m-row")) { e.preventDefault(); e.target.click(); }
});

// ---------------------------------------------------------------- pages
const searchBox = (q = "") => `<form class="m-search" role="search">${icon("search")}
  <input name="q" type="search" enterkeyhint="search" autocomplete="off" placeholder="${$t("노래, 앨범, 아티스트 검색")}" value="${esc(q)}" aria-label="${$t("음악 검색")}">
  ${q ? `<a class="ib m-sclear" href="/feed/music" data-nav aria-label="${$t("지우기")}">${icon("close")}</a>` : ""}</form>`;
function bindSearch(page) {
  const f = $(".m-search", page);
  f.addEventListener("submit", (e) => {
    e.preventDefault();
    const q = f.q.value.trim();
    if (q) navigate(`/feed/music/search?q=${encodeURIComponent(q)}`);
  });
}
const sec = (title, body, extra = "") => `<section class="m-sec"><div class="m-h"><h2>${title}</h2>${extra}</div>${body}</section>`;
const loading = (n = 6) => `<div class="m-skel">${'<div class="m-skrow"><i></i><span><b></b><b></b></span></div>'.repeat(n)}</div>`;
const playAll = (key, label = $t("모두 재생")) => `<button class="pill m-pall" data-mplayall="${key}">${icon("play")}${label}</button>`;
function bindPlayAll(page, lists) {
  page.addEventListener("click", (e) => {
    const b = e.target.closest("[data-mplayall]");
    if (!b) return;
    const [k, how] = b.dataset.mplayall.split(":");
    const L = lists[k];
    if (L) playList(L.songs, 0, { shuffle: how === "shuffle", from: L.from });
  });
}
// the page re-marks the playing song when it is drawn
const marked = (page) => { paint(); return page; };

export async function musicHome(page, m, alive) {
  document.title = $t("음악") + " - " + siteName();
  const recent = store.get("recent"), likes = store.get("likes"), opened = store.get("opened"), searches = store.get("searches");
  const hour = new Date().getHours();
  const hello = hour < 5 ? $t("늦은 밤의 음악") : hour < 12 ? $t("좋은 아침이에요") : hour < 18 ? $t("오후의 음악") : $t("오늘 저녁의 음악");
  const lists = {};
  page.innerHTML = `<div class="mpage">
    <div class="m-hello"><div><small>${$t("음악")}</small><h1>${hello}</h1></div>${searchBox()}</div>
    ${searches.length ? `<div class="m-chips">${searches.slice(0, 10).map((q) => `<a class="chip" href="/feed/music/search?q=${encodeURIComponent(q.id)}" data-nav>${icon("history", "sm")}${esc(q.id)}</a>`).join("")}</div>` : ""}
    <div class="m-quickbox"></div>
    <div class="m-rest"></div>
    <div class="m-yt"><div class="m-ytbox"></div></div></div>`;
  bindSearch(page);
  bindPlayAll(page, lists);
  const rest = $(".m-rest", page);
  let html = "";
  if (recent.length) {
    lists.recent = { songs: recent, from: $t("다시 듣기") };
    html += sec($t("다시 듣기"), songList(recent.slice(0, 20), { mode: "radio", cls: "m-quick" }), playAll("recent"));
  }
  if (likes.length) {
    lists.likes = { songs: likes, from: $t("좋아요 표시한 곡") };
    html += sec(`${$t("좋아요 표시한 곡")} <span class="m-count">${likes.length}</span>`, songList(likes.slice(0, 20), { cls: "m-quick" }),
      `<div class="m-hb">${playAll("likes")}${playAll("likes:shuffle", $t("셔플"))}</div>`);
  }
  if (opened.length) html += sec($t("최근에 본 앨범·아티스트"), cardRow(opened));
  if (!recent.length && !likes.length) {
    html += `<div class="m-welcome">${icon("music", "fill")}<h2>${$t("듣고 싶은 노래를 찾아보세요")}</h2>
      <p>${$t("앨범과 아티스트는 YouTube Music에서, 노래는 소리만 재생해요. 가사도 함께 보여 드려요.")}</p></div>`;
  }
  rest.innerHTML = html;
  marked(page);
  // quick picks: the songs like the one heard last
  if (recent.length) {
    const base = recent[0];
    const qb = $(".m-quickbox", page);
    qb.innerHTML = sec($t("빠른 선곡"), loading(4), "");
    try {
      const d = await get("mix", { list: "RDAMVM" + base.id, v: base.id });
      if (!alive()) return;
      const songs = (d.songs || []).filter((s) => s.id !== base.id).slice(0, 20);
      if (songs.length) {
        lists.quick = { songs, from: $t("빠른 선곡") };
        qb.innerHTML = sec(`${$t("빠른 선곡")}<small class="m-based">${esc($t("‘{0}’ 기반", base.title))}</small>`, songList(songs, { cls: "m-quick", mode: "radio" }), playAll("quick"));
        marked(page);
      } else qb.innerHTML = "";
    } catch { qb.innerHTML = ""; }
  }
  // YouTube's own music shelves (videos), below
  if (!alive()) return;
  const yb = $(".m-ytbox", page);
  const ok = await topicShelves(yb, "music", alive);
  if (ok && alive()) $(".m-yt", page).insertAdjacentHTML("afterbegin", `<div class="m-h m-yth"><h2>${$t("YouTube의 음악 동영상")}</h2></div>`);
}

export async function musicSearch(page, m, alive) {
  const q = (new URLSearchParams(location.search).get("q") || "").trim();
  if (!q) return navigate("/feed/music", { replace: true });
  document.title = q + " - " + $t("음악");
  store.push("searches", { id: q }, 15);
  page.innerHTML = `<div class="mpage"><div class="m-hello m-hello-s">${searchBox(q)}</div><div class="m-res">${loading(8)}</div></div>`;
  bindSearch(page);
  let d;
  try { d = await get("search", { q }); } catch (e) {
    if (!alive()) return;
    $(".m-res", page).innerHTML = `<div class="empty">${icon("info")}<p>${esc(e.message || $t("불러오지 못했어요"))}</p><button class="pill" onclick="location.reload()">${$t("다시 시도")}</button></div>`;
    return;
  }
  if (!alive()) return;
  const lists = { songs: { songs: d.songs, from: q } };
  bindPlayAll(page, lists);
  if (!d.songs.length && !d.albums.length && !d.artists.length) {
    $(".m-res", page).innerHTML = `<div class="empty">${icon("search")}<p>${$t("검색 결과가 없어요")}</p></div>`;
    return;
  }
  // the best match: the first artist when the name fits the search, else the first album, else the first song
  const norm = (x) => String(x || "").toLowerCase().replace(/[\s()（）\[\]·.,'"!?-]/g, "");
  const nq = norm(q);
  const art = d.artists.find((a) => norm(a.title) && (nq.includes(norm(a.title)) || norm(a.title).includes(nq)));
  const alb = !art && d.albums.find((a) => norm(a.title) && nq.includes(norm(a.title)));
  const top = art || alb;
  let topHtml = "";
  if (top) {
    CARDS.set(top.kind + ":" + top.id, top);
    topHtml = `<a class="m-top ${top.kind}" href="${esc(hrefOf(top))}" data-nav data-mc="${top.kind}:${esc(top.id)}">
      <span class="m-tcv">${top.art ? `<img alt="" src="${esc(img(top.art))}">` : icon("person")}</span>
      <span class="m-ttx"><b>${esc(top.title)}</b><span>${esc(top.sub || "")}</span></span>
      <span class="m-tbtn"><button class="pill white" data-mcplay>${icon(top.kind === "artist" ? "shuffle" : "play")}${top.kind === "artist" ? $t("셔플") : $t("재생")}</button></span></a>`;
  } else if (d.songs[0]) {
    const s = d.songs[0];
    topHtml = `<div class="m-top song" data-ml="${reg([s], "radio")}"><div class="m-row" data-k="0" data-id="${esc(s.id)}">
      <span class="m-tcv sq">${imgTag(s, true)}</span><span class="m-ttx"><b>${esc(s.title)}</b><span>${esc(s.artist)}</span></span>
      <span class="m-tbtn"><span class="pill white">${icon("play")}${$t("재생")}</span></span></div></div>`;
  }
  $(".m-res", page).innerHTML = `
    <div class="m-topgrid">
      ${topHtml ? `<section class="m-sec"><div class="m-h"><h2>${$t("최상위 결과")}</h2></div>${topHtml}</section>` : ""}
      ${d.songs.length ? sec($t("노래"), songList(d.songs, { mode: "radio", limit: 5 }), playAll("songs")) : ""}
    </div>
    ${d.albums.length ? sec($t("앨범"), cardRow(d.albums)) : ""}
    ${d.artists.length ? sec($t("아티스트"), cardRow(d.artists)) : ""}`;
  marked(page);
}

function head(kind, d, art, round = false) {
  return `<section class="m-head ${kind}" style="--cover:url('${esc(art)}')">
    <div class="m-hcv${round ? " round" : ""}">${art ? `<img alt="" src="${esc(art)}">` : icon(round ? "person" : "album")}</div>
    <div class="m-htx">${d.kind ? `<span class="m-kind">${esc(d.kind)}</span>` : ""}<h1>${esc(d.title)}</h1>
      ${d.by || ""}${d.info ? `<span class="m-info">${esc(d.info)}</span>` : ""}
      <div class="m-btns">${d.btns}</div></div></section>`;
}

export async function musicAlbum(page, m, alive) {
  page.innerHTML = `<div class="mpage"><div class="m-head skel"><div class="m-hcv"></div><div class="m-htx"><b></b><b></b></div></div>${loading(8)}</div>`;
  let a;
  try { a = await get("album", { id: m[1] }); } catch (e) {
    if (alive()) page.innerHTML = `<div class="empty">${icon("info")}<p>${esc(e.message || $t("불러오지 못했어요"))}</p></div>`;
    return;
  }
  if (!alive()) return;
  if (!a || (!a.title && !(a.songs || []).length)) { page.innerHTML = `<div class="empty">${icon("info")}<p>${$t("불러오지 못했어요")}</p></div>`; return; }
  document.title = a.title + " - " + $t("음악");
  store.push("opened", { id: m[1], title: a.title, sub: [a.sub.split(" • ")[0], a.artist].filter(Boolean).join(" • "), art: a.art, kind: "album" }, 20);
  const lists = { album: { songs: a.songs, from: a.title } };
  page.innerHTML = `<div class="mpage">${head("album", {
    kind: a.sub, title: a.title, info: a.info,
    by: a.artist ? (a.artistId ? `<a class="m-by" href="/feed/music/artist/${esc(a.artistId)}" data-nav>${esc(a.artist)}</a>` : `<span class="m-by">${esc(a.artist)}</span>`) : "",
    btns: `<button class="pill white" data-mplayall="album">${icon("play")}${$t("재생")}</button>
      <button class="pill" data-mplayall="album:shuffle">${icon("shuffle")}${$t("셔플")}</button>
      ${a.songs[0] ? `<button class="pill" data-mradio>${icon("radio")}${$t("라디오")}</button>` : ""}`,
  }, a.art ? img(a.art) : "")}
    ${songList(a.songs, { num: true, album: false, cls: "m-tracks" })}</div>`;
  bindPlayAll(page, lists);
  page.querySelector("[data-mradio]")?.addEventListener("click", () => playRadio(a.songs[0], $t("{0} 라디오", a.title)));
  marked(page);
}

export async function musicArtist(page, m, alive) {
  page.innerHTML = `<div class="mpage"><div class="m-head artist skel"><div class="m-hcv round"></div><div class="m-htx"><b></b><b></b></div></div>${loading(5)}</div>`;
  let a;
  try { a = await get("artist", { id: m[1] }); } catch (e) {
    if (alive()) page.innerHTML = `<div class="empty">${icon("info")}<p>${esc(e.message || $t("불러오지 못했어요"))}</p></div>`;
    return;
  }
  if (!alive()) return;
  if (!a || (!a.name && !(a.songs || []).length)) { page.innerHTML = `<div class="empty">${icon("info")}<p>${$t("불러오지 못했어요")}</p></div>`; return; }
  document.title = a.name + " - " + $t("음악");
  store.push("opened", { id: m[1], title: a.name, sub: $t("아티스트"), art: a.art, kind: "artist" }, 20);
  const lists = { top: { songs: a.songs, from: a.name } };
  const art = a.art ? img(a.art) : "";
  page.innerHTML = `<div class="mpage">${head("artist", {
    kind: $t("아티스트"), title: a.name, info: a.audience,
    btns: `${a.shuffleId || a.songs.length ? `<button class="pill white" data-ma="shuffle">${icon("shuffle")}${$t("셔플")}</button>` : ""}
      ${a.radioId ? `<button class="pill" data-ma="radio">${icon("radio")}${$t("라디오")}</button>` : ""}
      <a class="pill" href="/channel/${esc(m[1])}" data-nav>${icon("movie")}${$t("채널")}</a>`,
  }, art, true)}
    ${a.songs.length ? sec($t("인기 곡"), songList(a.songs, { limit: 5 }), playAll("top")) : ""}
    ${a.shelves.map((s) => sec(esc(s.title), cardRow(s.items))).join("")}
    ${a.about ? sec($t("소개"), `<div class="m-about"><p>${esc(a.about)}</p><button class="pill ghost" onclick="this.parentNode.classList.toggle('open')">${$t("더보기")}</button></div>`) : ""}</div>`;
  bindPlayAll(page, lists);
  page.addEventListener("click", (e) => {
    const b = e.target.closest("[data-ma]");
    if (!b) return;
    if (b.dataset.ma === "shuffle") { if (a.shuffleId) playMix(a.shuffleId, { from: a.name }); else playList(a.songs, 0, { shuffle: true, from: a.name }); }
    else if (b.dataset.ma === "radio") playMix(a.radioId, { from: $t("{0} 라디오", a.name) });
  });
  marked(page);
}
