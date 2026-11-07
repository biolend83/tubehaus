// Watch page, Shorts, mini player
import { $, $$, el, esc, icon, api, lib, settings, toast, fmtTime, fmtViews, fmtCount, ago, epoch, exactTime, thumb, normVideo, metaOf, pickThumb, fmtSubs, safeHtml, linkify, parseT, copy, PUBLIC } from "./util.js";
import { videoInfo, prefetch, player, state, card, norm, remember, known, fillProgress, avatar, subBtn, navigate, videoMenu, shareDialog, saveDialog, downloadDialog, keysDialog, setList, inList, queue, setPosCache, getPos, pageCleanup, popup, mi } from "./core.js";
import { loadPlaylist } from "./pages.js";
import { $t, LANG, LOCALE, REGION } from "./i18n.js";

const W = {
  id: null, info: null, v: null,        // now playing
  pl: null, plIndex: 0,                 // playlist context
  related: [],
  sentMeta: false,
  lastSave: 0,
  watchedSession: new Set(),
};
export const watchState = W;

// ---------------------------------------------------------------- saving progress (resume + history)
function saveProgress(force) {
  const v = player.video;
  if (!W.id || !W.v || player.live || player.info?.videoId !== W.id || W.v.id !== W.id) return;
  const now = Date.now();
  if (!force && now - W.lastSave < 9000) return;
  W.lastSave = now;
  const t = Math.floor(v.currentTime), len = Math.floor(v.duration || W.v.len || 0);
  if (t < 3 && !W.sentMeta) return;
  setPosCache(W.id, t, len);
  const body = { id: W.id, t, len };
  if (!W.sentMeta) { body.meta = metaOf(W.v); W.sentMeta = true; }
  const data = JSON.stringify(body);
  if (PUBLIC) return lib("pos", { method: "POST", body }).catch(() => { });   // public instance: saved in this browser
  if (force === "beacon" && navigator.sendBeacon) navigator.sendBeacon("/api/p/yt/pos", new Blob([data], { type: "application/json" }));
  else fetch("/api/p/yt/pos", { method: "POST", headers: { "Content-Type": "application/json" }, body: data, credentials: "same-origin", keepalive: true }).catch(() => { });
}
addEventListener("pagehide", () => saveProgress("beacon"));
document.addEventListener("visibilitychange", () => { if (document.hidden) saveProgress("beacon"); });
player.on.progress = (force) => saveProgress(force);

// ---------------------------------------------------------------- next / previous
function nextItem() {
  if (W.pl && W.pl.videos.length) {
    const shuffle = sessionStorage.getItem("yt.shuffle") === W.pl.id;
    let i;
    if (shuffle) { const left = W.pl.videos.map((_, k) => k).filter((k) => k !== W.plIndex && !W.watchedSession.has(W.pl.videos[k].id)); i = left.length ? left[Math.floor(Math.random() * left.length)] : -1; }
    else i = W.plIndex + 1;
    if (i >= W.pl.videos.length && sessionStorage.getItem("yt.plloop") === W.pl.id) i = 0;
    if (i >= 0 && i < W.pl.videos.length) return { v: W.pl.videos[i], href: `/watch?v=${W.pl.videos[i].id}&list=${encodeURIComponent(W.pl.id)}&index=${i + 1}` };
    return null;
  }
  const q = queue.get();
  if (q.length) {
    const i = q.findIndex((x) => x.id === W.id);
    if (i + 1 < q.length) return { v: normVideo(q[i + 1]), href: `/watch?v=${q[i + 1].id}&list=QUEUE&index=${i + 2}` };
  }
  if (settings.calm) return null;   // calm mode: nothing queues itself up after a video
  const v = W.related.find((r) => !r.live && !W.watchedSession.has(r.id) && r.id !== W.id) || W.related[0];
  return v ? { v, href: `/watch?v=${v.id}` } : null;
}
function prevItem() {
  if (W.pl && W.plIndex > 0) { const i = W.plIndex - 1; return `/watch?v=${W.pl.videos[i].id}&list=${encodeURIComponent(W.pl.id)}&index=${i + 1}`; }
  return null;
}
player.on.next = () => { const n = nextItem(); if (n) goWatch(n.href); };
player.on.prev = () => {
  if (player.video.currentTime > 3) return player.seek(0);
  const p = prevItem();
  if (p) goWatch(p);
};
player.on.ended = () => {
  W.watchedSession.add(W.id);
  saveProgress(true);
  const n = nextItem();
  if (!n) return;
  const inList = !!W.pl || (queue.get().length && queue.get().some((x) => x.id === W.id));
  if (inList) return goWatch(n.href);               // in a playlist: just the next item
  if (!settings.autoplay) return;
  player.showEnd(n.v, 8, () => goWatch(n.href));
};
player.on.reload = async (id, t) => {
  if (!id) return;
  try {
    const info = await api(`/api/v1/videos/${id}`);
    await player.load(info, { start: t });
  } catch (e) { player.showErr(e.message, $t("다시 시도"), () => player.on.reload(id, t)); }
};
player.on.keys = () => keysDialog();
player.on.chapters = () => openPanel("chapters");
player.on.theater = () => {
  if (state.route !== "watch") return;
  settings.theater = !settings.theater;
  layoutWatch();
};
player.on.mini = () => {
  if (state.route === "watch") {
    if (!settings.miniplayer) settings.miniplayer = true;
    if (history.state?.k && history.state.k !== "first") history.back(); else navigate("/");
  }
  else if (player.mode === "mini") expandMini();
};
player.on.ambient = () => $("#ambient")?.classList.toggle("off", !settings.ambient);
function goWatch(href) {
  if (state.route === "watch") navigate(href, { replace: false });
  else if (player.mode === "mini") { navigate(href); }
  else navigate(href);
}

// ---------------------------------------------------------------- mini player
const mini = () => $("#mini");
export function dockMini() {
  if (!player.info || !W.id || !settings.miniplayer) { stopPlayer(); return; }
  const m = mini();
  player.mode = "mini";
  player.root.classList.add("mini");
  player.root.classList.remove("theater");
  m.querySelector(".mp-video").append(player.root);
  m.querySelector(".mp-t").textContent = W.info?.title || "";
  m.querySelector(".mp-a").textContent = W.pl ? `${W.pl.title} • ${W.plIndex + 1}/${W.pl.videos.length}` : W.info?.author || "";
  m.classList.add("show");
  player.setIcons();
  syncMiniBtns();
}
function syncMiniBtns() {
  const m = mini();
  m.querySelector(".mp-play").innerHTML = icon(player.video.paused ? "play" : "pause");
  m.querySelector(".mp-next").style.display = nextItem() ? "" : "none";
}
player.video.addEventListener("play", syncMiniBtns);
player.video.addEventListener("pause", syncMiniBtns);
export function expandMini() {
  if (!W.id) return;
  const t = Math.floor(player.video.currentTime);
  const plq = W.pl ? `&list=${encodeURIComponent(W.pl.id)}&index=${W.plIndex + 1}` : "";
  navigate(`/watch?v=${W.id}${plq}`);
}
export function stopPlayer() {
  saveProgress(true);
  player.stop();
  player.mode = "default";
  player.root.classList.remove("mini", "shorts");
  player.root.remove();
  mini().classList.remove("show");
  W.id = W.info = W.v = null;
  if ("mediaSession" in navigator) navigator.mediaSession.metadata = null;
}
export function initMini() {
  const m = mini();
  m.querySelector(".mp-play").onclick = () => player.toggle();
  m.querySelector(".mp-next").onclick = () => player.on.next();
  m.querySelector(".mp-close").onclick = () => stopPlayer();
  m.querySelector(".mp-exp").onclick = () => expandMini();
  m.querySelector(".mp-info").onclick = () => expandMini();
  m.querySelector(".mp-video").addEventListener("click", (e) => { if (!e.target.closest(".ytp-bottom,.ytp-unmute")) expandMini(); }, true);
}

// ---------------------------------------------------------------- watch page
function layoutWatch() {
  const page = $(".watch");
  if (!page) return;
  // TVs: always large, even though they report a small width in CSS pixels
  const th = settings.theater && (innerWidth >= 1000 || document.documentElement.classList.contains("tv"));
  page.classList.toggle("theater", th);
  player.mode = th ? "theater" : "default";
  player.root.classList.toggle("theater", th);
  const slot = th ? $(".w-full", page) : $(".w-slot", page);
  if (player.root.parentElement !== slot) slot.append(player.root);
  $(".w-full", page).hidden = !th;
  player.setIcons();
}
addEventListener("resize", () => { if (state.route === "watch") layoutWatch(); });

export async function watch(page, m, alive) {
  const sp = new URLSearchParams(location.search);
  const id = sp.get("v");
  if (!id || !/^[\w-]{11}$/.test(id)) return navigate("/", { replace: true });
  const listId = sp.get("list");
  const index = Math.max(1, +sp.get("index") || 1) - 1;
  const tParam = sp.get("t") ? parseT(sp.get("t").replace(/s$/, "")) : null;
  mini().classList.remove("show");
  player.root.classList.remove("mini", "shorts");
  player.video.loop = false;

  page.innerHTML = `<div class="watch">
    <div class="w-full" hidden></div>
    <div class="w-cols">
      <div class="w-primary">
        <div class="w-playerbox"><canvas id="ambient" width="64" height="36" class="${settings.ambient ? "" : "off"}"></canvas><div class="w-slot"></div></div>
        <div class="w-info"><div class="sk-title"></div></div>
        <div class="w-comments"></div>
      </div>
      <div class="w-secondary"><div class="w-panel"></div><div class="w-plpanel"></div><div class="w-related"></div></div>
    </div></div>`;
  layoutWatch();
  player.ambientTo($("#ambient", page));

  // the same video is already playing (back from the mini player): do not reload it
  const same = W.id === id && player.info?.videoId === id;
  let info;
  if (same) info = W.info;
  else {
    saveProgress(true);
    W.id = id; W.v = null; W.info = null; W.sentMeta = false; W.lastSave = 0;
    player.next = null; player.hasPrev = false;
    try { info = await videoInfo(id); }
    catch (e) {
      if (!alive()) return;
      W.id = null;
      player.stop();
      // not started yet (upcoming live / premiere): Invidious says "This live event will begin in 7 hours."
      const up = /(?:begin|premieres?) in (\d+) (minute|hour|day)/i.exec(e.message || "");
      const soon = /begin in a few moments/i.test(e.message || "");
      const msg = up ? $t("예정된 라이브 방송이에요. {0} 시작해요", new Intl.RelativeTimeFormat(LOCALE).format(+up[1], up[2].toLowerCase()))
        : soon ? $t("곧 시작하는 라이브 방송이에요")
        : e.status === 500 || e.status === 502 ? $t("영상을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.") : e.message;
      player.showErr(msg, $t("다시 시도"), () => navigate(location.pathname + location.search, { replace: true }));
      $(".w-info", page).innerHTML = "";
      return;
    }
    if (!alive()) return;
    if (info.error) { player.showErr(info.error); return; }
  }
  W.info = info;
  W.v = remember([normVideo(info)])[0];
  W.v.authorThumb = pickThumb(info.authorThumbnails, 88);
  W.related = norm(info.recommendedVideos || []);
  document.title = info.title + $t(" - 튜브하우스");

  // playlist context
  W.pl = null;
  if (listId) {
    try { W.pl = await loadPlaylist(listId); } catch { }
    if (W.pl) {
      let i = W.pl.videos.findIndex((x) => x.id === id);
      if (i < 0) i = Math.min(index, W.pl.videos.length - 1);
      W.plIndex = Math.max(0, i);
    }
  }
  if (!alive()) return;

  // start position: ?t= > resume point > beginning
  if (!same) {
    let start = tParam;
    if (start == null && settings.resume && !info.liveNow) {
      try {
        const p = (await lib("pos?ids=" + id, { quiet: true }))[id];
        if (p && p[0] > 10 && p[0] < (p[1] || info.lengthSeconds) - 20) start = p[0];
      } catch { }
    }
    player.next = nextItem()?.v || null;
    player.hasPrev = !!prevItem();
    player.load(info, { start: start || 0 });
  } else {
    player.next = nextItem()?.v || null;
    player.hasPrev = !!prevItem();
    if (tParam != null) player.seek(tParam);
  }
  player.setIcons();

  // TVs show only the full-page player, so skip description, related videos and comments (less work for a weak TV)
  if (document.documentElement.classList.contains("tv")) {
    const nx = nextItem();
    if (nx) setTimeout(() => prefetch(nx.v.id), 5000);
    return;
  }
  renderInfo(page, info);
  renderRelated(page);
  renderPlaylistPanel(page);
  renderComments(page, info);
  const nx = nextItem();
  if (nx) setTimeout(() => prefetch(nx.v.id), 5000);
  const onKey = (e) => {
    if (e.target.closest("input,textarea,select,[contenteditable]")) return;
    if (e.key === "?" ) { keysDialog(); return; }
  };
  addEventListener("keydown", onKey);
  pageCleanup.push(() => removeEventListener("keydown", onKey));
}

// Invidious sometimes loses a video's channel picture and subscriber count (YouTube's newer page layout
// for some channels); the channel itself still has both
async function fillOwner(box, info) {
  if (!/^UC[\w-]{22}$/.test(info.authorId || "")) return;
  let c;
  try { c = await api(`/api/v1/channels/${info.authorId}?fields=subCount,authorThumbnails`, { ttl: 3600000, quiet: true }); } catch { return; }
  const own = box.isConnected && $(".w-owner", box);
  if (!own || !c) return;
  const u = pickThumb(c.authorThumbnails, 88);
  const ph = $("a > .av.ph", own);
  if (u && ph) {
    ph.outerHTML = avatar(u, "md");
    const sb = $("[data-subbtn]", own);
    if (sb && !sb.dataset.thumb) sb.dataset.thumb = u;
    if (W.info === info && !W.v.authorThumb) W.v.authorThumb = u;
  }
  const s = $(".w-ch .s", own);
  if (s && !s.textContent.trim() && c.subCount != null) s.textContent = fmtSubs(+c.subCount);
}
function renderInfo(page, info) {
  const box = $(".w-info", page);
  const v = W.v;
  const av = pickThumb(info.authorThumbnails, 88);
  const liked = inList("likes", v.id), disliked = inList("dislikes", v.id);
  const pub = epoch(info.published) ? new Date(epoch(info.published) * 1000) : null;
  const hashtags = (info.keywords || []).slice(0, 3);
  box.innerHTML = `
    <div class="w-tags">${hashtags.map((k) => `<a href="/results?search_query=${encodeURIComponent(k)}" data-nav>#${esc(k.replace(/\s+/g, ""))}</a>`).join(" ")}</div>
    <h1 class="w-title" data-da-vid="${esc(v.id)}">${esc(info.title)}</h1>
    <div class="w-row">
      <div class="w-owner">
        <a href="/channel/${esc(info.authorId)}" data-nav>${avatar(av, "md")}</a>
        <div class="w-ch"><a href="/channel/${esc(info.authorId)}" data-nav class="nm">${esc(info.author)}${info.authorVerified ? icon("verified", "vf") : ""}</a><div class="s">${esc(info.subCountText && info.subCountText !== "-" ? $t("구독자 {0}명", info.subCountText.replace(/subscribers?/i, "").trim()) : "")}</div></div>
        ${subBtn(info.authorId, info.author, av)}
      </div>
      <div class="w-actions">
        <div class="seg-btn"><button class="like ${liked ? "on" : ""}" title="${$t("좋아요")}">${icon("like")}<span>${info.likeCount ? fmtCount(info.likeCount) : ""}</span></button><button class="dislike ${disliked ? "on" : ""}" title="${$t("싫어요")}">${icon("dislike")}<span class="dc"></span></button></div>
        <button class="pill cast" title="${$t("TV로 보내기")}">${icon("tv")}<span>${$t("TV로")}</span></button>
        <button class="pill share">${icon("share")}<span>${$t("공유")}</span></button>
        ${info.liveNow ? "" : `<button class="pill dl">${icon("download")}<span>${$t("오프라인 저장")}</span></button>`}
        <button class="pill save">${icon("save")}<span>${$t("저장")}</span></button>
        <button class="pill round more">${icon("moreh")}</button>
      </div>
    </div>
    <div class="w-desc collapsed">
      <div class="w-dmeta"><b>${info.liveNow ? `${$t("{0}명 시청 중", fmtCount(info.viewCount))}` : fmtViews(info.viewCount)}</b> <b${pub ? ` title="${esc(exactTime(info.published))}"` : ""}>${pub ? (info.liveNow ? $t("실시간 스트리밍 시작: ") + ago(info.published) : ago(info.published)) : esc(info.publishedText || "")}</b>
        ${pub ? `<span class="full-date">${pub.toLocaleDateString(LOCALE)}</span>` : ""}</div>
      <div class="w-dtext">${info.descriptionHtml ? safeHtml(info.descriptionHtml.replace(/\n/g, "<br>")) : linkify(info.description || "")}</div>
      ${player.chapters.length ? `<div class="w-dchap"><h4>${$t("챕터")}</h4><div class="chap-row">${player.chapters.map((c) => `<button data-seek="${c.start}"><span class="ct">${fmtTime(c.start)}</span><span>${esc(c.title)}</span></button>`).join("")}</div></div>` : ""}
      <div class="w-dbtns"><button class="pill ghost transcript">${$t("{0} 스크립트 표시", icon("notes"))}</button></div>
      <button class="w-dmore">${$t("더보기")}</button><button class="w-dless">${$t("간략히")}</button>
    </div>`;
  if (!av || !info.subCountText || info.subCountText === "-") fillOwner(box, info);
  const desc = $(".w-desc", box);
  desc.addEventListener("click", (e) => {
    const s = e.target.closest("[data-seek]");
    if (s) { e.preventDefault(); player.seek(+s.dataset.seek); scrollTo({ top: 0, behavior: "smooth" }); return; }
    const a = e.target.closest("a[href]");
    if (a) {
      // time links inside this video's text (/watch?v=sameID&t=..)
      const u = new URL(a.href, location.origin);
      if (u.origin === location.origin && u.searchParams.get("v") === W.id && u.searchParams.get("t")) {
        e.preventDefault(); e.stopPropagation(); player.seek(parseT(u.searchParams.get("t").replace(/s$/, ""))); scrollTo({ top: 0, behavior: "smooth" }); return;
      }
      return;
    }
    if (e.target.closest(".w-dless")) { desc.classList.add("collapsed"); return; }
    if (e.target.closest(".transcript")) { openPanel("transcript"); return; }
    if (desc.classList.contains("collapsed")) desc.classList.remove("collapsed");
  });
  const likeB = $(".like", box), disB = $(".dislike", box);
  likeB.onclick = () => {
    const on = !likeB.classList.contains("on");
    likeB.classList.toggle("on", on);
    if (on && disB.classList.contains("on")) { disB.classList.remove("on"); setList("dislikes", v, false, true); }
    setList("likes", v, on);
  };
  disB.onclick = () => {
    const on = !disB.classList.contains("on");
    disB.classList.toggle("on", on);
    if (on && likeB.classList.contains("on")) { likeB.classList.remove("on"); setList("likes", v, false, true); }
    setList("dislikes", v, on, true);
  };
  $(".share", box).onclick = () => shareDialog(v);
  $(".cast", box).onclick = (e) => { const btn = e.currentTarget; import("./cast.js").then((m) => m.castTo(v, btn)); };
  $(".save", box).onclick = () => saveDialog(v);
  $(".dl", box)?.addEventListener("click", () => downloadDialog(v));
  $(".more", box).onclick = (e) => popup(e.currentTarget, [
    mi("queue", "playlists", $t("현재 재생목록에 추가")),
    mi("transcript", "notes", $t("스크립트 표시")),
    player.chapters.length ? mi("chapters", "sort", $t("챕터")) : "",
    mi("yt", "open", $t("YouTube 에서 열기")),
    mi("loop", "loop", player.video.loop ? $t("반복 재생 끄기") : $t("반복 재생")),
    mi("keys", "keyboard", $t("단축키")),
  ].join(""), (a) => {
    if (a === "queue") queue.add(v);
    if (a === "transcript") openPanel("transcript");
    if (a === "chapters") openPanel("chapters");
    if (a === "yt") open(`https://www.youtube.com/watch?v=${v.id}`, "_blank", "noopener");
    if (a === "loop") { player.video.loop = !player.video.loop; toast(player.video.loop ? $t("반복 재생 켜짐") : $t("반복 재생 꺼짐")); }
    if (a === "keys") keysDialog();
  });
  // dislike count (Return YouTube Dislike)
  if (settings.dislikes) {
    fetch(`https://returnyoutubedislikeapi.com/votes?videoId=${v.id}`, { referrerPolicy: "no-referrer" }).then((r) => r.json()).then((d) => {
      if (W.id !== v.id) return;
      $(".dc", box).textContent = d.dislikes != null ? fmtCount(d.dislikes) : "";
      if (!info.likeCount && d.likes) $(".like span", box).textContent = fmtCount(d.likes);
      const tot = (d.likes || 0) + (d.dislikes || 0);
      if (tot) $(".seg-btn", box).style.setProperty("--ratio", ((d.likes || 0) / tot) * 100 + "%");
    }).catch(() => { });
  }
}

function renderRelated(page) {
  const box = $(".w-related", page);
  if (settings.calm) { box.innerHTML = ""; return; }
  const chips = [...new Set([$t("전체"), W.info.author, $t("관련 콘텐츠")])];
  box.innerHTML = `<div class="rel-auto"><b>${$t("다음 동영상")}</b><label class="autosw">${$t("자동재생")} <input type="checkbox" class="sw" ${settings.autoplay ? "checked" : ""}></label></div>` +
    W.related.map((v) => card(v, "compact")).join("");
  box.querySelector(".autosw input").onchange = (e) => { settings.autoplay = e.target.checked; player.setIcons(); };
  fillProgress(box);
}

function renderPlaylistPanel(page) {
  const box = $(".w-plpanel", page);
  if (!W.pl) { box.innerHTML = ""; return; }
  const p = W.pl;
  const loopOn = sessionStorage.getItem("yt.plloop") === p.id, shufOn = sessionStorage.getItem("yt.shuffle") === p.id;
  box.innerHTML = `<div class="plp">
    <div class="plp-h"><div><b>${esc(p.title)}</b><div class="s">${esc(p.author || "")} - ${W.plIndex + 1}/${p.videos.length}</div></div>
      <div class="plp-btns"><button class="lp ${loopOn ? "on" : ""}" title="${$t("재생목록 반복")}">${icon("loop")}</button><button class="sf ${shufOn ? "on" : ""}" title="${$t("셔플")}">${icon("shuffle")}</button>
      <button class="cl" title="${$t("접기")}">${icon("expand")}</button></div></div>
    <div class="plp-list">${p.videos.map((v, i) => `<a class="plp-it ${i === W.plIndex ? "cur" : ""}" href="/watch?v=${esc(v.id)}&list=${encodeURIComponent(p.id)}&index=${i + 1}" data-nav>
      <span class="ix">${i === W.plIndex ? icon("play") : i + 1}</span><span class="th"><img loading="lazy" src="${thumb(v.id, "mqdefault")}" alt="">${v.len ? `<span class="dur">${fmtTime(v.len)}</span>` : ""}</span>
      <span class="tx"><span class="t">${esc(v.title)}</span><span class="s">${esc(v.author)}</span></span></a>`).join("")}</div></div>`;
  const cur = box.querySelector(".plp-it.cur");
  if (cur) cur.parentElement.scrollTop = cur.offsetTop - cur.parentElement.offsetTop - 40;
  box.querySelector(".lp").onclick = (e) => { const on = sessionStorage.getItem("yt.plloop") !== p.id; on ? sessionStorage.setItem("yt.plloop", p.id) : sessionStorage.removeItem("yt.plloop"); e.currentTarget.classList.toggle("on", on); player.next = nextItem()?.v || null; player.setIcons(); };
  box.querySelector(".sf").onclick = (e) => { const on = sessionStorage.getItem("yt.shuffle") !== p.id; on ? sessionStorage.setItem("yt.shuffle", p.id) : sessionStorage.removeItem("yt.shuffle"); e.currentTarget.classList.toggle("on", on); };
  box.querySelector(".cl").onclick = () => box.querySelector(".plp").classList.toggle("folded");
}

// ---------------------------------------------------------------- side panel: transcript / chapters
async function openPanel(kind) {
  const box = $(".w-panel");
  if (!box) return;
  if (box.dataset.kind === kind) { box.dataset.kind = ""; box.innerHTML = ""; return; }
  box.dataset.kind = kind;
  const head = (t) => `<div class="pn-h"><b>${t}</b><button class="x">${icon("close")}</button></div>`;
  if (kind === "chapters") {
    box.innerHTML = `<div class="pn">${head($t("챕터"))}<div class="pn-list">${player.chapters.map((c) => {
      const f = player.sbFrame(c.start + 1);
      const img = f ? `<span class="cthumb" style="background-image:url('${f.url}');background-size:${(f.cols * f.w * 96) / f.w}px auto;background-position:-${(f.x * 96) / f.w}px -${(f.y * 96) / f.w}px;width:96px;height:${(f.h * 96) / f.w}px"></span>` : "";
      return `<button class="pn-it" data-seek="${c.start}">${img}<span><b>${esc(c.title)}</b><em>${fmtTime(c.start)}</em></span></button>`;
    }).join("")}</div></div>`;
  } else {
    box.innerHTML = `<div class="pn">${head($t("스크립트"))}<div class="pn-list tr"><div class="spinner"></div></div></div>`;
    const list = player.captionList();
    const pick = player.capTrack || list.find((c) => c.lang.startsWith(LANG) && !/auto/i.test(c.label)) || list.find((c) => c.lang.startsWith(LANG)) || list[0];
    const L = box.querySelector(".pn-list");
    if (!pick) { L.innerHTML = `<p class='sub'>${$t("이 영상에는 자막이 없어요")}</p>`; }
    else {
      try {
        const txt = await (await fetch(pick.url, { credentials: "same-origin" })).text();
        const cues = [];
        for (const b of txt.replace(/\r/g, "").split(/\n{2,}/)) {
          const ls = b.split("\n"), i = ls.findIndex((l) => l.includes("-->"));
          if (i < 0) continue;
          const t = ls[i].split("-->")[0].trim().split(":").reduce((a, x) => a * 60 + parseFloat(x), 0);
          const text = ls.slice(i + 1).join(" ").replace(/<[^>]+>/g, "").trim();
          if (text && (!cues.length || cues[cues.length - 1].text !== text)) cues.push({ t, text });
        }
        L.innerHTML = `<div class="tr-lang">${esc(pick.label)}</div>` + cues.map((c) => `<button class="pn-it tr-it" data-seek="${c.t}"><em>${fmtTime(c.t)}</em><span>${esc(c.text)}</span></button>`).join("");
        const items = $$(".tr-it", L);
        const iv = setInterval(() => {
          if (!L.isConnected) return clearInterval(iv);
          const t = player.video.currentTime;
          let cur = null;
          for (const it of items) { if (+it.dataset.seek <= t) cur = it; else break; }
          items.forEach((x) => x.classList.toggle("cur", x === cur));
          if (cur && !L.matches(":hover")) L.scrollTop = cur.offsetTop - L.offsetTop - L.clientHeight / 3;
        }, 500);
      } catch { L.innerHTML = `<p class='sub'>${$t("스크립트를 불러오지 못했어요")}</p>`; }
    }
  }
  box.querySelector(".x").onclick = () => { box.dataset.kind = ""; box.innerHTML = ""; };
  box.addEventListener("click", (e) => { const s = e.target.closest("[data-seek]"); if (s) player.seek(+s.dataset.seek); });
  if (innerWidth < 1000) box.scrollIntoView({ behavior: "smooth", block: "start" });
}

// ---------------------------------------------------------------- comments
async function renderComments(page, info, sortBy = "top", target) {
  const box = target || $(".w-comments", page);
  if (info.liveNow) { box.innerHTML = `<div class="cm-h"><b>${$t("실시간 채팅은 지원하지 않아요")}</b></div>`; return; }
  if (settings.calm && !target && !box.dataset.open) {
    // calm mode: comments stay folded until you ask for them
    box.innerHTML = `<button class="pill ghost cm-open">${$t("{0} 댓글 보기", icon("chat"))}</button>`;
    $(".cm-open", box).onclick = () => { box.dataset.open = "1"; renderComments(page, info, sortBy); };
    return;
  }
  if (!target && innerWidth < 1000) {
    box.classList.add("teaser");
    box.addEventListener("click", () => box.classList.remove("teaser"), { once: true });
  }
  box.innerHTML = `<div class="cm-h"><h3 class="cm-count">${$t("댓글")}</h3><button class="pill ghost sortb">${$t("{0} 정렬 기준", icon("sort"))}</button></div><div class="cm-list"><div class="spinner"></div></div>`;
  const list = $(".cm-list", box);
  let cont = "";
  const load = async () => {
    const r = await api(`/api/v1/comments/${info.videoId}?sort_by=${sortBy}${cont ? "&continuation=" + encodeURIComponent(cont) : ""}`);
    if (!cont) { list.innerHTML = ""; if (r.commentCount != null) $(".cm-count", box).textContent = `${$t("댓글 {0}개", (+r.commentCount).toLocaleString(LOCALE))}`; }
    list.insertAdjacentHTML("beforeend", (r.comments || []).map(commentHtml).join(""));
    cont = r.continuation;
    return !!cont;
  };
  try {
    if (await load()) {
      const sent = el(`<div class="sentinel"><div class="spinner"></div></div>`);
      list.after(sent);
      const io = new IntersectionObserver(async (es) => {
        if (!es[0].isIntersecting || io.busy) return;
        io.busy = true;
        const more = await load().catch(() => false);
        io.busy = false;
        if (!more) { io.disconnect(); sent.remove(); }
      }, { rootMargin: "600px" });
      io.observe(sent);
      pageCleanup.push(() => io.disconnect());
    }
  } catch (e) { list.innerHTML = `<p class="sub">${e.status === 404 || e.status === 403 ? $t("댓글이 사용 중지되었습니다.") : $t("댓글을 불러오지 못했어요")}</p>`; }
  $(".sortb", box).onclick = (e) => popup(e.currentTarget, mi("top", "trending", $t("인기 댓글순")) + mi("new", "history", $t("최신순")), (a) => renderComments(page, info, a, box), { align: "left" });
  box.onclick = async (e) => {
    const s = e.target.closest("a[href*='&t='], a[href*='?t=']");
    if (s) {
      const u = new URL(s.href, location.origin);
      if (u.searchParams.get("v") === W.id || !u.searchParams.get("v")) { e.preventDefault(); e.stopPropagation(); player.seek(parseT((u.searchParams.get("t") || "0").replace(/s$/, ""))); scrollTo({ top: 0, behavior: "smooth" }); return; }
    }
    const rb = e.target.closest(".rep-btn");
    if (rb) {
      const wrap = rb.nextElementSibling;
      if (wrap.dataset.open) { delete wrap.dataset.open; wrap.hidden = true; rb.classList.remove("open"); return; }
      wrap.dataset.open = "1"; wrap.hidden = false; rb.classList.add("open");
      if (!wrap.dataset.loaded) {
        wrap.innerHTML = `<div class="spinner"></div>`;
        let c = rb.dataset.cont;
        wrap.innerHTML = "";
        while (c) {
          const r = await api(`/api/v1/comments/${info.videoId}?continuation=${encodeURIComponent(c)}`).catch(() => null);
          if (!r) break;
          wrap.insertAdjacentHTML("beforeend", (r.comments || []).map((x) => commentHtml(x, true)).join(""));
          c = r.continuation;
          if (wrap.children.length > 200) break;
        }
        wrap.dataset.loaded = "1";
      }
    }
    const rd = e.target.closest(".cm-more");
    if (rd) { rd.previousElementSibling.classList.toggle("clamp"); rd.textContent = rd.previousElementSibling.classList.contains("clamp") ? $t("자세히 보기") : $t("간략히"); }
  };
  // fold long comments
  requestAnimationFrame(() => $$(".cm-text", box).forEach((t) => { if (t.scrollHeight > t.clientHeight + 4) t.insertAdjacentHTML("afterend", `<button class="cm-more">${$t("자세히 보기")}</button>`); }));
}
function commentHtml(c, reply = false) {
  // newer Invidious sends one "authorThumbnail" address instead of the "authorThumbnails" list
  const av = pickThumb(c.authorThumbnails, 48) || pickThumb(c.authorThumbnail ? [{ url: c.authorThumbnail, width: 48 }] : [], 48);
  const owner = c.authorIsChannelOwner;
  return `<div class="cm ${reply ? "reply" : ""}">
    <a href="/channel/${esc(c.authorId)}" data-nav>${avatar(av, reply ? "xs" : "sm")}</a>
    <div class="cm-b">
      ${c.isPinned ? `<div class="pinned">${$t("{0} {1}님이 고정함", icon("pin"), esc(W.info?.author || ""))}</div>` : ""}
      <div class="cm-a"><a href="/channel/${esc(c.authorId)}" data-nav class="${owner ? "owner" : ""}">${esc(c.author)}</a> <span>${esc(c.published ? ago(c.published) : c.publishedText || "")}${c.isEdited ? $t(" (수정됨)") : ""}</span></div>
      <div class="cm-text clamp">${safeHtml(c.contentHtml || esc(c.content || ""))}</div>
      <div class="cm-act"><span>${icon("like")} ${c.likeCount ? fmtCount(c.likeCount) : ""}</span><span>${icon("dislike")}</span>${c.creatorHeart ? `<span class="heart" title="${$t("{0}님이 좋아함", esc(W.info?.author || ""))}">${avatar(pickThumb(c.creatorHeart.creatorThumbnail ? [{ url: c.creatorHeart.creatorThumbnail, width: 48 }] : [], 48), "xxs")}${icon("heart")}</span>` : ""}</div>
      ${c.replies?.replyCount ? `<button class="rep-btn" data-cont="${esc(c.replies.continuation)}">${$t("{0} 답글 {1}개", icon("expand"), c.replies.replyCount)}</button><div class="replies" hidden></div>` : ""}
    </div></div>`;
}

// ---------------------------------------------------------------- Shorts
export async function shorts(page, m, alive) {
  const first = m[1];
  if (player.mode === "mini" || (player.info && state.route !== "watch")) stopPlayer();
  page.innerHTML = `<div class="shorts-view"><div class="sv-scroll"></div>
    <div class="sv-nav"><button class="up">${icon("left")}</button><button class="down">${icon("right")}</button></div></div>`;
  const sc = $(".sv-scroll", page);
  let ids = [];
  try { ids = JSON.parse(sessionStorage.getItem("yt.shortsCtx") || "[]"); } catch { }
  let i0 = ids.indexOf(first);
  if (i0 < 0) { ids = [first, ...ids.filter((x) => x !== first)]; i0 = 0; }
  ids = ids.slice(i0);
  const slides = new Map();
  // what this viewing tells the recommender: seconds watched (loops count), like / dislike
  let watched = 0, lastT = 0, cur = null;
  const onTime = () => {
    const t = player.video.currentTime, dt = t - lastT;
    if (dt > 0 && dt < 1.5) watched += dt;
    lastT = t;
  };
  player.video.addEventListener("timeupdate", onTime);
  const report = () => {
    if (!cur) return;
    const { id, info } = cur;
    cur = null;
    lib("shorts/sig", { method: "POST", quiet: true, body: {
      id, authorId: info.authorId, author: info.author, title: info.title, keywords: info.keywords || [], genre: info.genre || "",
      watched: Math.round(watched * 10) / 10, len: info.lengthSeconds || 0, liked: inList("likes", id), disliked: inList("dislikes", id),
    } }).catch(() => { });
  };
  const addSlide = (id) => {
    if (slides.has(id)) return;
    const s = el(`<section class="short-slide" data-id="${esc(id)}"><div class="ss-media" style="background-image:url('${thumb(id)}')"></div>
      <div class="ss-meta"><div class="ss-ch"></div><div class="ss-t"></div></div>
      <div class="ss-act">
        <button class="sa like">${icon("like")}<span>${$t("좋아요")}</span></button>
        <button class="sa dis">${icon("dislike")}<span>${$t("싫어요")}</span></button>
        <button class="sa cmt">${icon("chat")}<span>${$t("댓글")}</span></button>
        <button class="sa shr">${icon("share")}<span>${$t("공유")}</span></button>
        <button class="sa more">${icon("moreh")}</button>
      </div></section>`);
    slides.set(id, s);
    sc.append(s);
    io.observe(s);
  };
  let loadingMore = false, active = null;
  const moreShorts = async () => {
    if (loadingMore) return;
    loadingMore = true;
    try {
      // learned on this server from what you watch; popular Shorts if it has nothing yet
      const r = await lib(`shorts/next?n=10&region=${REGION}&skip=${[...slides.keys()].slice(-200).join(",")}`, { quiet: true });
      r.items.forEach((v) => addSlide(v.id));
      if (!r.items.length) throw 0;
    } catch {
      try {
        const pg = 1 + Math.floor(slides.size / 15);
        const r = norm(await api(`/api/v1/search?q=%23shorts&type=video&duration=short&region=${REGION}&sort=views&date=week&page=${pg}`, { ttl: 600000 }));
        r.forEach((v) => addSlide(v.id));
      } catch { }
    }
    loadingMore = false;
  };
  const io = new IntersectionObserver((es) => {
    for (const e of es) if (e.isIntersecting && e.intersectionRatio > 0.6) activate(e.target);
  }, { root: sc, threshold: [0.6] });
  async function activate(s) {
    if (active === s) return;
    report();
    active = s;
    const id = s.dataset.id;
    history.replaceState(history.state, "", `/shorts/${id}`);
    const media = s.querySelector(".ss-media");
    player.mode = "shorts";
    player.root.classList.add("shorts");
    player.root.classList.remove("mini", "theater");
    media.append(player.root);
    player.video.loop = true;
    const all = [...slides.keys()];
    if (all.indexOf(id) > all.length - 4) moreShorts();
    let info;
    try { info = await videoInfo(id); } catch (e) { player.showErr(e.message); return; }
    // prefetch the next 2 Shorts
    const nx = [...slides.keys()];
    nx.slice(nx.indexOf(id) + 1, nx.indexOf(id) + 3).forEach(prefetch);
    if (active !== s) return;
    W.id = id; W.info = info; W.v = remember([normVideo(info)])[0]; W.sentMeta = false; W.pl = null;
    player.next = null; player.hasPrev = false;
    await player.load(info, { start: 0 });
    player.video.loop = true;
    watched = 0; lastT = 0; cur = { id, info };
    const av = pickThumb(info.authorThumbnails, 88);
    s.querySelector(".ss-ch").innerHTML = `<a href="/channel/${esc(info.authorId)}" data-nav>${avatar(av, "sm")}<b>${esc(info.author)}</b></a>${subBtn(info.authorId, info.author, av)}`;
    s.querySelector(".ss-t").textContent = info.title;
    s.querySelector(".like span").textContent = info.likeCount ? fmtCount(info.likeCount) : $t("좋아요");
    s.querySelector(".like").classList.toggle("on", inList("likes", id));
    s.querySelector(".cmt span").textContent = $t("댓글");
    s.querySelector(".like").onclick = (e) => { const on = !e.currentTarget.classList.contains("on"); e.currentTarget.classList.toggle("on", on); setList("likes", W.v, on); };
    s.querySelector(".dis").onclick = (e) => { const on = !e.currentTarget.classList.contains("on"); e.currentTarget.classList.toggle("on", on); setList("dislikes", W.v, on, true); };
    s.querySelector(".shr").onclick = () => shareDialog(W.v);
    s.querySelector(".more").onclick = (e) => videoMenu(e.currentTarget, W.v);
    s.querySelector(".cmt").onclick = () => {
      const sheet = el(`<div class="sheet"><div class="sheet-in"><div class="sheet-h"><b>${$t("댓글")}</b><button>${icon("close")}</button></div><div class="sheet-body"></div></div></div>`);
      document.body.append(sheet);
      sheet.querySelector("button").onclick = () => sheet.remove();
      sheet.onclick = (ev) => { if (ev.target === sheet) sheet.remove(); };
      renderComments(null, info, "top", sheet.querySelector(".sheet-body"));
    };
  }
  ids.forEach(addSlide);
  if (ids.length < 5) moreShorts();
  const go = (d) => { const all = [...sc.children]; const i = all.indexOf(active); const t = all[Math.max(0, Math.min(all.length - 1, i + d))]; t?.scrollIntoView({ behavior: "smooth" }); };
  $(".up", page).onclick = () => go(-1);
  $(".down", page).onclick = () => go(1);
  const onKey = (e) => {
    if (e.target.closest("input,textarea")) return;
    if (e.key === "ArrowDown") { e.preventDefault(); go(1); }
    if (e.key === "ArrowUp") { e.preventDefault(); go(-1); }
  };
  addEventListener("keydown", onKey, true);
  pageCleanup.push(() => {
    report();
    player.video.removeEventListener("timeupdate", onTime);
    removeEventListener("keydown", onKey, true);
    io.disconnect();
    saveProgress(true);
    player.video.loop = false;
    stopPlayer();
  });
}
