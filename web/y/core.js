// App skeleton: router, shared state (my library), cards, popup menus, dialogs
import { $, $$, el, esc, icon, api, lib, settings, local, toast, copy, fmtTime, fmtViews, ago, exactTime, thumb, normVideo, metaOf, pickThumb, dropCache, PUBLIC } from "./util.js";
import { Player, audioName } from "./player.js";
import { knownAvatar } from "./avatars.js";
import { $t, $tMsg, fmtShort, LANG, LOCALE } from "./i18n.js";

export const player = new Player();
export const routes = [];        // [regex, handler]
export const state = {
  lib: { subs: {}, wl: [], likes: [], dislikes: [], playlists: {} },
  libReady: null,
  route: "",
  hidden: new Set(local.get("hidden", [])),
};

// ---------------------------------------------------------------- my library (stored on the server -> same on every device)
export function loadLib() {
  state.libReady = lib("lib").then((d) => { if (d) state.lib = d; renderGuideSubs(); return state.lib; }).catch(() => state.lib);
  return state.libReady;
}
export const isSub = (cid) => !!state.lib.subs[cid];
export const inList = (list, id) => (list === "wl" || list === "likes" ? state.lib[list].some((v) => v.id === id) : list === "dislikes" ? state.lib.dislikes.includes(id) : (state.lib.playlists[list]?.ids || []).includes(id));

export async function setSub(cid, name, thumbUrl, on) {
  try {
    await lib("sub", { method: "POST", body: { id: cid, name, thumb: thumbUrl, on } });
    if (on) state.lib.subs[cid] = { name, thumb: thumbUrl, at: Date.now() / 1000 };
    else delete state.lib.subs[cid];
    dropCache("/yt/feed");
    renderGuideSubs();
    toast(on ? $t("구독했어요") : $t("구독을 취소했어요"));
    $$(`[data-subbtn="${cid}"]`).forEach((b) => subBtnPaint(b, on));
  } catch (e) { toast(e.message); }
}
export function subBtn(cid, name, thumbUrl, big = false) {
  const on = isSub(cid);
  return `<button class="subbtn ${on ? "on" : ""} ${big ? "big" : ""}" data-subbtn="${esc(cid)}" data-name="${esc(name)}" data-thumb="${esc(thumbUrl || "")}">${on ? icon("bell") + $t("구독중") : $t("구독")}</button>`;
}
function subBtnPaint(b, on) {
  b.classList.toggle("on", on);
  b.innerHTML = on ? icon("bell") + $t("구독중") : $t("구독");
}

export async function setList(list, v, on, quiet) {
  try {
    const r = await lib("list", { method: "POST", body: { list, video: metaOf(v), on } });
    if (list === "wl" || list === "likes") {
      state.lib[list] = state.lib[list].filter((x) => x.id !== v.id);
      if (on) state.lib[list].unshift(metaOf(v));
    } else if (list === "dislikes") {
      state.lib.dislikes = state.lib.dislikes.filter((x) => x !== v.id);
      if (on) state.lib.dislikes.push(v.id);
    } else if (r && r.playlist) state.lib.playlists[list] = r.playlist;
    if (!quiet) {
      const name = list === "wl" ? $t("나중에 볼 동영상") : list === "likes" ? $t("좋아요 표시한 동영상") : state.lib.playlists[list]?.title;
      if (list !== "dislikes") toast(on ? `${$t("{0}에 저장됨", name)}` : `${$t("{0}에서 삭제됨", name)}`);
    }
  } catch (e) { toast(e.message); }
}

// ---------------------------------------------------------------- current queue (this tab only)
export const queue = {
  get() { try { return JSON.parse(sessionStorage.getItem("yt.queue") || "[]"); } catch { return []; } },
  set(a) { sessionStorage.setItem("yt.queue", JSON.stringify(a)); },
  add(v) { const q = this.get().filter((x) => x.id !== v.id); q.push(metaOf(v)); this.set(q); toast($t("현재 재생목록에 추가됨")); },
};

// ---------------------------------------------------------------- resume positions
const posCache = new Map();
export async function fillProgress(root) {
  const cards = $$("[data-vid]", root).filter((c) => !c.dataset.pos);
  const ids = [...new Set(cards.map((c) => c.dataset.vid))].filter((id) => !posCache.has(id));
  for (let i = 0; i < ids.length; i += 100) {
    try {
      const d = await lib("pos?ids=" + ids.slice(i, i + 100).join(","), { quiet: true });
      for (const id of ids.slice(i, i + 100)) posCache.set(id, d[id] || null);
    } catch { break; }
  }
  for (const c of cards) {
    c.dataset.pos = "1";
    const p = posCache.get(c.dataset.vid);
    if (!p || !p[1]) continue;
    const f = p[0] / p[1] > 0.93 ? 1 : p[0] / p[1];
    const th = c.querySelector(".th");
    if (th && f > 0.01) th.insertAdjacentHTML("beforeend", `<div class="resume"><i style="width:${f * 100}%"></i></div>`);
  }
}
export function setPosCache(id, t, len) { posCache.set(id, [t, len]); }
export function getPos(id) { return posCache.get(id); }

// ---------------------------------------------------------------- cards
function badge(v) {
  if (v.live) return `<span class="dur live">${$t("{0}실시간", icon("live"))}</span>`;
  if (v.upcoming) return `<span class="dur up">${$t("예정")}</span>`;
  if (v.short) return `<span class="dur">${icon("shorts")}</span>`;
  return v.len ? `<span class="dur">${fmtTime(v.len)}</span>` : "";
}
export function metaLine(v) {
  const parts = [];
  let tip = "";
  if (v.live) parts.push(v.views != null ? `${fmtViewsLive(v.views)}` : $t("실시간"));
  else if (v.upcoming) parts.push(v.premiere ? `${$t("{0} 공개 예정", new Date(v.premiere * 1000).toLocaleString(LOCALE, { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit" }))}` : $t("공개 예정"));
  else {
    if (v.views != null && v.views !== "") parts.push(fmtViews(v.views));
    else if (v.viewsText) parts.push(v.viewsText);
    const a = v.published ? ago(v.published) : v.publishedText;
    if (a) parts.push(a);
    if (v.published && a) tip = exactTime(v.published);
  }
  return parts.map((p, i) => tip && i === parts.length - 1 ? `<span title="${esc(tip)}">${esc(p)}</span>` : esc(p)).join(" • ");
}
const fmtViewsLive = (n) => $t("{0}명 시청 중", fmtShort(+n));

export function avatar(src, cls = "") {
  return src ? `<img class="av ${cls}" loading="lazy" src="${esc(src)}" alt="">` : `<span class="av ph ${cls}">${icon("account")}</span>`;
}
export function chAvatar(v) {
  const s = v.authorThumb || state.lib.subs[v.authorId]?.thumb || knownAvatar(v.authorId);
  return s;
}

// kind: grid | row (search results / history) | compact (related videos) | short
export function card(v, kind = "grid", extra = "") {
  if (!v || !v.id || state.hidden.has(v.id) || (v.short && settings.calm)) return "";
  const href = v.short ? `/shorts/${v.id}` : `/watch?v=${v.id}${extra}`;
  const by = v.authorId ? `<a href="/channel/${esc(v.authorId)}" data-nav class="by">${esc(v.author)}${v.verified ? icon("verified", "vf") : ""}</a>` : `<span class="by">${esc(v.author)}</span>`;
  const more = `<button class="dots" data-menu="${esc(v.id)}" aria-label="${$t("작업 메뉴")}">${icon("more")}</button>`;
  const th = `<a href="${href}" data-nav class="th"><img loading="lazy" src="${thumb(v.id)}" alt="">${badge(v)}</a>`;
  if (kind === "short") {
    return `<div class="card short" data-vid="${esc(v.id)}"><a href="/shorts/${esc(v.id)}" data-nav class="th"><img loading="lazy" src="${thumb(v.id, "oar2")}" onerror="this.onerror=null;this.classList.add('lb');this.src='${thumb(v.id)}'" onload="if(this.naturalWidth<=120&&!this.classList.contains('lb')){this.classList.add('lb');this.src='${thumb(v.id)}'}" alt=""></a>
      <div class="meta"><a href="/shorts/${esc(v.id)}" data-nav class="t">${esc(v.title)}</a><div class="s">${v.views != null ? fmtViews(v.views) : ""}</div></div>${more}</div>`;
  }
  if (kind === "grid") {
    const av = chAvatar(v);
    return `<div class="card grid" data-vid="${esc(v.id)}">${th}
      <div class="meta">${v.authorId ? `<a href="/channel/${esc(v.authorId)}" data-nav class="avl">${avatar(av)}</a>` : ""}
      <div class="tx"><a href="${href}" data-nav class="t" title="${esc(v.title)}">${esc(v.title)}</a>${by}<div class="s">${metaLine(v)}</div></div>${more}</div></div>`;
  }
  if (kind === "row") {
    const av = chAvatar(v);
    return `<div class="card row" data-vid="${esc(v.id)}">${th}
      <div class="tx"><a href="${href}" data-nav class="t" title="${esc(v.title)}">${esc(v.title)}</a><div class="s">${metaLine(v)}</div>
      <div class="chl">${v.authorId ? `<a href="/channel/${esc(v.authorId)}" data-nav>${avatar(av, "sm")}</a>` : ""}${by}</div>
      ${v.desc ? `<div class="d">${esc(v.desc.slice(0, 160))}</div>` : ""}
      <div class="bd">${v.isNew ? `<i>${$t("새 동영상")}</i>` : ""}${v.is4k ? "<i>4K</i>" : ""}${v.cc ? `<i>${$t("자막")}</i>` : ""}</div></div>${more}</div>`;
  }
  // compact
  return `<div class="card compact" data-vid="${esc(v.id)}">${th}<div class="tx"><a href="${href}" data-nav class="t" title="${esc(v.title)}">${esc(v.title)}</a>${by}<div class="s">${metaLine(v)}</div></div>${more}</div>`;
}
export function channelCard(c) {
  const th = pickThumb(c.authorThumbnails, 176);
  return `<div class="card chan"><a href="/channel/${esc(c.authorId)}" data-nav class="cth">${avatar(th, "xl")}</a>
    <div class="tx"><a href="/channel/${esc(c.authorId)}" data-nav class="t">${esc(c.author)}${c.authorVerified ? icon("verified", "vf") : ""}</a>
    <div class="s">${esc(c.channelHandle || "")}${c.subCount != null ? " • " + esc($t("구독자 {0}명", fmtShort(+c.subCount))) : ""}</div>
    <div class="d">${esc((c.description || "").slice(0, 140))}</div></div>
    ${subBtn(c.authorId, c.author, th)}</div>`;
}
export function playlistCard(p) {
  const first = p.videos?.[0]?.videoId || p.first;
  const img = p.playlistThumbnail ? p.playlistThumbnail.replace(/^https?:\/\/[^/]+(?=\/vi\/)/, "") : first ? thumb(first) : "";
  const href = `/playlist?list=${esc(p.playlistId || p.id)}`;
  return `<div class="card grid pl"><a href="${href}" data-nav class="th stack">${img ? `<img loading="lazy" src="${esc(img)}" alt="">` : ""}<span class="dur">${$t("{0}동영상 {1}개", icon("playlists"), p.videoCount ?? p.count ?? 0)}</span></a>
    <div class="meta"><div class="tx"><a href="${href}" data-nav class="t">${esc(p.title)}</a><span class="by">${esc(p.author || "")}</span><a href="${href}" data-nav class="s">${$t("전체 재생목록 보기")}</a></div></div></div>`;
}

// keep video objects where the card menu can find them
export const known = new Map();
export function remember(list) {
  for (const v of list) if (v && v.id) known.set(v.id, v);
  return list;
}
export const norm = (list) => remember((list || []).filter((x) => x && (x.type === undefined || x.type === "video" || x.type === "shortVideo" || x.videoId)).map(normVideo));

// ---------------------------------------------------------------- infinite scroll
export function infinite(container, loadMore) {
  const sent = el(`<div class="sentinel"><div class="spinner"></div></div>`);
  container.after(sent);
  let busy = false, done = false;
  const io = new IntersectionObserver(async (es) => {
    if (!es[0].isIntersecting || busy || done) return;
    busy = true;
    try { done = !(await loadMore()); } catch (e) { done = true; console.warn(e); }
    busy = false;
    if (done) { sent.remove(); io.disconnect(); }
    else if (sent.getBoundingClientRect().top < innerHeight + 400) { io.unobserve(sent); io.observe(sent); }
  }, { rootMargin: "800px" });
  io.observe(sent);
  pageCleanup.push(() => io.disconnect());
}
export const pageCleanup = [];

// ---------------------------------------------------------------- popup menu (⋮)
export function popup(anchor, html, onClick, { align = "right" } = {}) {
  closePopup();
  const p = $("#popup");
  p.innerHTML = html;
  p.className = "show";
  const b = anchor.getBoundingClientRect();
  const w = p.offsetWidth, hgt = p.offsetHeight;
  let x = align === "right" ? b.right - w : b.left;
  let y = b.bottom + 4;
  if (y + hgt > innerHeight - 8) y = Math.max(8, b.top - hgt - 4);
  x = Math.max(8, Math.min(innerWidth - w - 8, x));
  p.style.left = x + "px";
  p.style.top = y + "px";
  p.onclick = (e) => {
    const it = e.target.closest("[data-a]");
    if (!it) return;
    e.stopPropagation();
    closePopup();
    onClick(it.dataset.a, it);
  };
  setTimeout(() => {
    closer = (e) => { if (!p.contains(e.target)) closePopup(); };
    document.addEventListener("click", closer);
  }, 0);
}
let closer = null;
export function closePopup() {
  const p = $("#popup");
  if (p) p.className = "";
  if (closer) { document.removeEventListener("click", closer); closer = null; }
}
export const mi = (a, ic, label) => `<div class="mi" data-a="${a}">${icon(ic)}<span>${label}</span></div>`;

export function videoMenu(anchor, v, ctx = {}) {
  const items = [
    mi("queue", "playlists", $t("현재 재생목록에 추가")),
    mi("wl", "later", inList("wl", v.id) ? $t("나중에 볼 동영상에서 삭제") : $t("나중에 볼 동영상에 저장")),
    mi("save", "save", $t("재생목록에 저장")),
    v.live || PUBLIC ? "" : mi("dl", "download", $t("오프라인 저장 (다운로드)")),
    mi("share", "share", $t("공유")),
    PUBLIC ? "" : mi("cast", "tv", $t("TV로 보내기")),
    "<hr>",
    ctx.hist ? mi("unhist", "trash", $t("시청 기록에서 삭제")) : "",
    ctx.pl ? mi("unpl", "trash", $t("재생목록에서 삭제")) : "",
    ctx.home ? mi("hide", "close", $t("관심 없음")) : "",
    v.authorId ? mi("chan", "account", $t("채널로 이동")) : "",
  ].join("");
  popup(anchor, items, async (a) => {
    if (a === "queue") queue.add(v);
    if (a === "wl") setList("wl", v, !inList("wl", v.id));
    if (a === "save") saveDialog(v);
    if (a === "dl") downloadDialog(v);
    if (a === "share") shareDialog(v);
    if (a === "cast") import("./cast.js").then((m) => m.castTo(v, anchor));
    if (a === "chan") navigate(`/channel/${v.authorId}`);
    if (a === "hide") {
      state.hidden.add(v.id);
      local.set("hidden", [...state.hidden].slice(-2000));
      anchor.closest(".card")?.remove();
      toast($t("동영상이 삭제됨"), $t("실행취소"), () => { state.hidden.delete(v.id); local.set("hidden", [...state.hidden]); render(); });
    }
    if (a === "unhist") {
      await lib("hist/del", { method: "POST", body: { id: v.id } }).catch(() => { });
      anchor.closest(".card")?.remove();
      toast($t("시청 기록에서 삭제됨"));
    }
    if (a === "unpl") {
      await setList(ctx.pl, v, false);
      anchor.closest(".card")?.remove();
    }
  });
}

// ---------------------------------------------------------------- dialogs
export function dialog(html, { cls = "", onClose } = {}) {
  const m = $("#modal");
  m.innerHTML = `<div class="dlg ${cls}" role="dialog">${html}</div>`;
  m.className = "show";
  const close = () => { m.className = ""; m.innerHTML = ""; onClose?.(); removeEventListener("keydown", esc_); };
  const esc_ = (e) => { if (e.key === "Escape") close(); };
  addEventListener("keydown", esc_);
  m.onclick = (e) => { if (e.target === m || e.target.closest("[data-close]")) close(); };
  return { box: m.firstElementChild, close };
}

export function shareDialog(v) {
  const t = player.info?.videoId === v.id ? Math.floor(player.video.currentTime) : 0;
  const base = `${location.origin}/watch?v=${v.id}`;
  const { box, close } = dialog(`
    <div class="dh"><b>${$t("공유")}</b><button data-close>${icon("close")}</button></div>
    <div class="share-row">
      ${navigator.share ? `<button class="sh-btn" data-s="sys">${icon("share")}<span>${$t("기기 공유")}</span></button>` : ""}
      <button class="sh-btn" data-s="yt">${icon("open")}<span>${$t("YouTube 링크")}</span></button>
    </div>
    <div class="share-link"><input readonly value="${esc(base)}"><button class="pill blue" data-s="copy">${$t("복사")}</button></div>
    ${t ? `<label class="chk"><input type="checkbox" class="st"> ${$t("시작 시간 {0}", fmtTime(t))}</label>` : ""}`);
  const inp = box.querySelector(".share-link input");
  const st = box.querySelector(".st");
  const url = () => (st && st.checked ? `${base}&t=${t}` : base);
  if (st) st.onchange = () => (inp.value = url());
  box.onclick = async (e) => {
    const s = e.target.closest("[data-s]")?.dataset.s;
    if (s === "copy") { copy(url()); close(); }
    if (s === "yt") { copy(`https://youtu.be/${v.id}${st && st.checked ? "?t=" + t : ""}`); close(); }
    if (s === "sys") { try { await navigator.share({ title: v.title, url: url() }); } catch { } close(); }
  };
}

export async function saveDialog(v) {
  await state.libReady;
  const pls = Object.entries(state.lib.playlists).sort((a, b) => (b[1].updated || 0) - (a[1].updated || 0));
  const row = (list, label, on) => `<label class="chk"><input type="checkbox" data-l="${esc(list)}" ${on ? "checked" : ""}> ${esc(label)}</label>`;
  const { box, close } = dialog(`
    <div class="dh"><b>${$t("동영상 저장...")}</b><button data-close>${icon("close")}</button></div>
    <div class="savelist">${row("wl", $t("나중에 볼 동영상"), inList("wl", v.id))}${pls.map(([id, p]) => row(id, p.title, (p.ids || []).includes(v.id))).join("")}</div>
    <div class="newpl"><button class="ghost add">${$t("{0} 새 재생목록 만들기", icon("add"))}</button>
      <div class="newform" hidden><input maxlength="150" placeholder="${$t("이름 입력...")}"><button class="pill blue mk">${$t("만들기")}</button></div></div>`, { cls: "narrow" });
  box.querySelectorAll("input[data-l]").forEach((c) => (c.onchange = () => setList(c.dataset.l, v, c.checked)));
  box.querySelector(".add").onclick = () => { box.querySelector(".newform").hidden = false; box.querySelector(".add").hidden = true; box.querySelector(".newform input").focus(); };
  const mk = async () => {
    const title = box.querySelector(".newform input").value.trim();
    if (!title) return;
    try {
      const r = await lib("pl/new", { method: "POST", body: { title, video: metaOf(v) } });
      state.lib.playlists[r.id] = r.playlist;
      toast(`${$t("{0}에 저장됨", title)}`);
      close();
    } catch (e) { toast(e.message); }
  };
  box.querySelector(".mk").onclick = mk;
  box.querySelector(".newform input").onkeydown = (e) => { if (e.key === "Enter") mk(); };
}

export async function downloadDialog(v) {
  const { box, close } = dialog(`<div class="dh"><b>${$t("오프라인 저장")}</b><button data-close>${icon("close")}</button></div><div class="dlbody"><div class="spinner"></div></div>`, { cls: "narrow dl" });
  let info;
  try { info = await api(`/api/v1/videos/${v.id}`, { ttl: 600000 }); } catch (e) { box.querySelector(".dlbody").textContent = e.message; return; }
  const name = (info.title || v.id).replace(/[\/:*?"<>|]+/g, " ").slice(0, 120);
  const af = info.adaptiveFormats || [];
  const audAll = af.filter((f) => /^audio/.test(f.type));
  // "stable volume" (drc) copies are left out, as the player does
  const aud = audAll.filter((f) => !atrack(f).drc).length ? audAll.filter((f) => !atrack(f).drc) : audAll;
  const best = (list) => list.sort((a, b) => b.bitrate - a.bitrate)[0];
  // the audio tracks (original, dubs, audio description): one per language and role, the original first
  const tracks = [];
  for (const f of aud) { const t = atrack(f); if (!tracks.some((x) => x.id === t.id)) tracks.push(t); }
  for (const t of tracks) t.label = (t.lang ? audioName({ lang: t.lang, orig: t.orig, desc: t.kind === "descriptive" }) : t.name || $t("오디오")) + (t.kind === "dubbed-auto" ? ` (${$t("자동 더빙")})` : "");
  tracks.sort((a, b) => b.orig - a.orig || (a.kind === "descriptive") - (b.kind === "descriptive") || a.label.localeCompare(b.label, LOCALE));
  const caps = info.liveNow ? [] : (info.captions || []);
  const vids = af.filter((f) => /^video/.test(f.type) && f.clen);
  const heights = [...new Set(vids.map((f) => parseInt(f.qualityLabel || f.resolution) || 0))].filter(Boolean).sort((a, b) => b - a);
  const mb = (n) => (n >= 1073741824 ? (n / 1073741824).toFixed(2) + " GB" : (n / 1048576).toFixed(0) + " MB");
  const body = box.querySelector(".dlbody");
  let tr = tracks[0], hd = [], direct = [];
  const picks = (tracks.length > 1 ? `<label>${$t("오디오 트랙")}<select class="dl-at">${tracks.map((t, i) => `<option value="${i}">${esc(t.label)}</option>`).join("")}</select></label>` : "") +
    (caps.length ? `<label>${$t("자막")}<select class="dl-cc"><option value="">${$t("넣지 않음")}</option>${caps.map((c, i) => `<option value="${i}">${esc(c.label)}</option>`).join("")}</select></label>` : "");
  body.innerHTML = (picks ? `<div class="dlpick">${picks}</div>` : "") + `<div class="dlopts"></div>` +
    `<div class="dlprog" hidden><div class="dlbar"><i></i></div><div class="dltxt"></div></div>` +
    `<p class="hint">${$t("영상과 소리를 서버가 받아서 한 파일로 합친 다음 내려줘요. 창을 닫아도 서버에서 계속 준비하고, 다 되면 알려줘요.")}</p>`;
  const opts = body.querySelector(".dlopts");
  const draw = () => {
    const mine = aud.filter((f) => !tr || atrack(f).id === tr.id);
    const m4a = best(mine.filter((f) => /mp4/.test(f.type)));
    const opus = best(mine.filter((f) => /webm/.test(f.type)));
    // one per quality: H.264 (MP4) plays everywhere -> else AV1 (MP4) -> else VP9 (WebM)
    hd = [];
    for (const h of heights) {
      const at = vids.filter((f) => (parseInt(f.qualityLabel || f.resolution) || 0) === h);
      const pick = (re) => at.filter((f) => re.test(f.type)).sort((a, b) => (+b.fps || 0) - (+a.fps || 0) || b.bitrate - a.bitrate)[0];
      const f = pick(/avc1/) || pick(/av01/) || pick(/vp9|vp09/);
      if (!f) continue;
      const webm = /webm/.test(f.type);
      const a = webm ? opus : m4a;
      if (!a) continue;
      const codec = /avc1/.test(f.type) ? $t("H.264 · 어디서나 재생") : /av01/.test(f.type) ? $t("AV1 · 최신 기기") : $t("VP9 · 컴퓨터/안드로이드");
      hd.push({ v: f.itag, a: a.itag, ext: webm ? "webm" : "mp4", label: `${f.qualityLabel || h + "p"}`, sub: codec, size: (+f.clen || 0) + (+a.clen || 0), h });
    }
    direct = [];
    if (m4a) direct.push({ itag: m4a.itag, label: `${$t("음악만 · M4A {0}kbps", Math.round(m4a.bitrate / 1000))}`, ext: "m4a", size: m4a.clen });
    if (opus) direct.push({ itag: opus.itag, label: `${$t("음악만 · Opus {0}kbps", Math.round(opus.bitrate / 1000))}`, ext: "webm", size: opus.clen });
    // the original sound comes straight from YouTube; another track has to be picked out by the server
    opts.innerHTML =
      (hd.length ? `<div class="dlsec">${$t("동영상 (소리 포함)")}</div>` + hd.map((o, i) =>
        `<button class="dlopt" data-i="${i}">${icon("download")}<span><b>${esc(o.label)}</b> <small>${esc(o.sub)}</small></span><em>${mb(o.size)}</em></button>`).join("") : "") +
      (direct.length ? `<div class="dlsec">${$t("음악")}</div>` + direct.map((o, i) => !tr || tr.orig
        ? `<a class="dlopt" href="/latest_version?id=${esc(v.id)}&itag=${o.itag}&local=true&title=${encodeURIComponent(name + "." + o.ext)}" download="${esc(name + "." + o.ext)}">${icon("audio")}<span>${esc(o.label)}</span>${o.size ? `<em>${mb(+o.size)}</em>` : ""}</a>`
        : `<button class="dlopt" data-m="${i}">${icon("audio")}<span>${esc(o.label)}</span>${o.size ? `<em>${mb(+o.size)}</em>` : ""}</button>`).join("") : "");
    opts.querySelectorAll("a.dlopt").forEach((a) => (a.onclick = () => { toast($t("다운로드를 시작했어요")); setTimeout(close, 300); }));
  };
  const btns = () => box.querySelectorAll("button.dlopt, .dlpick select");
  opts.addEventListener("click", async (e) => {
    const b = e.target.closest("button.dlopt");
    if (!b) return;
    const cc = caps[box.querySelector(".dl-cc")?.value ?? ""];
    const o = b.dataset.i != null ? hd[+b.dataset.i] : { ...direct[+b.dataset.m], v: "", a: direct[+b.dataset.m].itag, label: "" };
    const what = [o.label, tr && !tr.orig ? tr.label : "", o.v && cc ? cc.label : ""].filter(Boolean).join(", ");
    btns().forEach((x) => (x.disabled = true));
    try {
      const r = await api("/api/p/yt/dl", { method: "POST", body: {
        id: v.id, v: o.v, a: o.a, ext: o.ext, name: what ? `${name} (${what})` : name,
        at: tr && !tr.orig ? tr.id : "", al: tr?.lang || "", sub: o.v && cc ? cc.label : "", sl: o.v && cc ? cc.language_code : "" } });
      watchDl(r.job, box);
    } catch (err) { toast(err.message); btns().forEach((x) => (x.disabled = false)); }
  });
  box.querySelector(".dl-at")?.addEventListener("change", (e) => { tr = tracks[+e.target.value]; draw(); });
  draw();
}
// an audio stream's track, as the server reads it (_atrack): from audioTrack, else from the address's xtags
// (acont=dubbed:lang=ko, drc=1 = stable volume)
function atrack(f) {
  const x = {};
  try { for (const p of (new URL(f.url, location.href).searchParams.get("xtags") || "").split(":")) { const i = p.indexOf("="); if (i > 0) x[p.slice(0, i)] = p.slice(i + 1); } } catch { }
  const t = f.audioTrack || {};
  const lang = x.lang || (t.id || "").split(".")[0];
  const kind = x.acont || (t.isDefault ? "original" : Object.keys(t).length ? "dubbed" : "");
  return { id: t.id || (lang ? `${lang}.${kind}` : ""), lang, kind, orig: kind === "original" || !!t.isDefault || !lang, drc: x.drc === "1", name: t.displayName || "" };
}
// follow the server job and download the file when it is done (keeps going if the dialog is closed)
function watchDl(job, box) {
  const prog = box.querySelector(".dlprog");
  prog.hidden = false;
  const tick = async () => {
    let s;
    try { s = await api(`/api/p/yt/dl?job=${job}`, { quiet: true }); } catch { return setTimeout(tick, 3000); }
    if (box.isConnected) {
      box.querySelector(".dlbar i").style.width = s.pct + "%";
      box.querySelector(".dltxt").textContent = s.status === "merge" ? $t("영상과 소리를 합치는 중...") : s.status === "done" ? $t("완료! 저장을 시작해요") : s.status === "error" ? $tMsg(s.err) : `${$t("서버가 받는 중 {0}%", Math.floor(s.pct))}`;
    }
    if (s.status === "error") { toast($t("저장 실패: ") + $tMsg(s.err || "")); return; }
    if (s.status === "done" && s.warn) toast($tMsg(s.warn));
    if (s.status !== "done") return setTimeout(tick, 1500);
    const a = el(`<a href="/api/p/yt/dl/file?job=${job}" download="${esc(s.name)}" hidden></a>`);
    document.body.append(a);
    a.click();
    a.remove();
    toast(`${$t("{0} 저장 시작", s.name)}`, $t("다시 받기"), () => { location.href = `/api/p/yt/dl/file?job=${job}`; }, 10000);
    if (box.isConnected) setTimeout(() => $("#modal").className === "show" && box.isConnected && ($("#modal").className = ""), 800);
  };
  tick();
}

export function keysDialog() {
  const k = [
    [$t("k / 스페이스"), $t("재생 / 일시중지")], ["j / l", $t("10초 뒤로 / 앞으로")], ["← / →", $t("5초 뒤로 / 앞으로")],
    ["↑ / ↓", $t("볼륨 5% 올리기 / 내리기")], ["m", $t("음소거")], ["f", $t("전체 화면")], ["t", $t("영화관 모드")], ["i", $t("미니 플레이어")],
    ["c", $t("자막 켜기 / 끄기")], ["0 … 9", $t("영상의 0% … 90% 지점으로")], [", / .", $t("(일시중지 중) 한 프레임 뒤 / 앞")],
    ["< / >", $t("재생 속도 느리게 / 빠르게")], ["Shift + N / P", $t("다음 / 이전 동영상")], ["Home / End", $t("처음 / 끝으로")], ["/", $t("검색창으로")], ["?", $t("이 단축키 목록")],
  ];
  dialog(`<div class="dh"><b>${$t("단축키")}</b><button data-close>${icon("close")}</button></div><table class="keys">${k.map(([a, b]) => `<tr><td><kbd>${esc(a)}</kbd></td><td>${esc(b)}</td></tr>`).join("")}</table>`);
}

// ---------------------------------------------------------------- side menu
export function renderGuideSubs() {
  const box = $("#guide .g-subs");
  if (!box) return;
  const subs = Object.entries(state.lib.subs).sort((a, b) => a[1].name.localeCompare(b[1].name, LANG));
  const show = box.dataset.all ? subs : subs.slice(0, 7);
  box.innerHTML = subs.length
    ? show.map(([id, s]) => `<a href="/channel/${esc(id)}" data-nav class="gi">${avatar(s.thumb, "xs")}<span>${esc(s.name)}</span></a>`).join("") +
    (subs.length > 7 ? `<button class="gi more">${icon(box.dataset.all ? "expand" : "expand")}<span>${box.dataset.all ? $t("간략히 보기") : `${$t("{0}개 더보기", subs.length - 7)}`}</span></button>` : "")
    : `<div class="g-empty">${$t("구독한 채널이 여기에 나와요")}</div>`;
  const m = box.querySelector(".more");
  if (m) m.onclick = () => { if (box.dataset.all) delete box.dataset.all; else box.dataset.all = "1"; renderGuideSubs(); };
}

// ---------------------------------------------------------------- router
let renderSeq = 0;
export function navigate(url, { replace = false } = {}) {
  const u = new URL(url, location.origin);
  if (u.origin !== location.origin) { open(url, "_blank", "noopener"); return; }
  saveScroll();
  const st = { k: Math.random().toString(36).slice(2) };
  if (replace) history.replaceState(st, "", u.pathname + u.search + u.hash);
  else history.pushState(st, "", u.pathname + u.search + u.hash);
  render();
}
function saveScroll() {
  const k = history.state?.k;
  if (k) { const m = JSON.parse(sessionStorage.getItem("yt.scroll") || "{}"); m[k] = scrollY; sessionStorage.setItem("yt.scroll", JSON.stringify(m)); }
}
export { saveScroll };
function restoreScroll() {
  const k = history.state?.k;
  const m = JSON.parse(sessionStorage.getItem("yt.scroll") || "{}");
  if (k && m[k] != null) { const y = m[k]; requestAnimationFrame(() => scrollTo(0, y)); setTimeout(() => scrollTo(0, y), 300); }
}
export async function render() {
  const seq = ++renderSeq;
  while (pageCleanup.length) try { pageCleanup.pop()(); } catch { }
  closePopup();
  const path = location.pathname;
  const page = $("#page");
  for (const [re, fn, opt] of routes) {
    const m = path.match(re);
    if (!m) continue;
    state.route = opt?.name || "";
    document.body.dataset.route = state.route;
    $$("#guide .gi, #mguide a, #bnav a").forEach((a) => a.classList.toggle("act", a.dataset.r === state.route));
    page.innerHTML = "";
    page.classList.remove("enter");         // restart the "rise in" of the new page (app.css: Motion)
    void page.offsetWidth;
    page.classList.add("enter");
    const restoring = _restore;
    _restore = false;
    if (!restoring) scrollTo(0, 0);
    try { await fn(page, m, () => seq === renderSeq); }
    catch (e) {
      console.error(e);
      if (seq === renderSeq) page.innerHTML = `<div class="empty">${icon("info")}<p>${esc(e.message || $t("문제가 생겼어요"))}</p><button class="pill" onclick="location.reload()">${$t("다시 시도")}</button></div>`;
    }
    if (restoring && seq === renderSeq) restoreScroll();
    if (seq === renderSeq) dispatchEvent(new CustomEvent("routed"));
    return;
  }
  page.innerHTML = `<div class="empty">${icon("info")}<p>${$t("페이지를 찾을 수 없어요")}</p><a class="pill" href="/" data-nav>${$t("홈으로")}</a></div>`;
}
// restore the old scroll position only when going back
let _restore = false;
export const markRestore = () => (_restore = true);

// ---------------------------------------------------------------- prefetch / moving thumbnails
// When a finger touches a card or the mouse rests on it, fetch the video info early -> it opens instantly.
// Hovering flips through storyboard frames (like YouTube's moving previews).
export const videoInfo = (id) => api(`/api/v1/videos/${id}`, { ttl: 300000 });
const prefetched = new Set();
export function prefetch(id) {
  if (!id || prefetched.has(id)) return;
  prefetched.add(id);
  videoInfo(id).catch(() => prefetched.delete(id));
}
function sbOf(info) {
  const s = (info.storyboards || []).filter((x) => x.templateUrl).sort((a, b) => b.width - a.width)[0];
  return s ? { ...s, tpl: s.templateUrl.replace(/^https?:\/\/i\.ytimg\.com\/sb\//, "/sb/i/") } : null;
}
let hovT = null;
async function hoverPreview(th, id) {
  let info;
  try { info = await videoInfo(id); } catch { return; }
  if (!th.dataset.hov || info.liveNow) return;
  const sb = sbOf(info);
  if (!sb || !sb.count) return;
  const box = el(`<div class="sbprev"></div>`);
  th.append(box);
  const per = sb.storyboardWidth * sb.storyboardHeight, W = th.clientWidth, H = (W * sb.height) / sb.width;
  let k = 0;
  const show = () => {
    const i = Math.min(sb.count - 1, Math.floor(((k % 16) + 1) * sb.count / 17));
    const sheet = Math.floor(i / per), p = i % per;
    box.style.cssText = `background-image:url("${sb.tpl.replace("$M", sheet)}");background-size:${sb.storyboardWidth * W}px ${sb.storyboardHeight * H}px;` +
      `background-position:-${(p % sb.storyboardWidth) * W}px -${Math.floor(p / sb.storyboardWidth) * H}px;height:${H}px;top:${(th.clientHeight - H) / 2}px`;
    k++;
  };
  show();
  th._sbT = setInterval(show, 700);
}
export function initCardHover() {
  document.addEventListener("pointerover", (e) => {
    const th = e.target.closest(".card .th");
    if (!th || th.dataset.hov || e.pointerType !== "mouse") return;
    const id = th.closest("[data-vid]")?.dataset.vid;
    if (!id) return;
    th.dataset.hov = "1";
    clearTimeout(hovT);
    hovT = setTimeout(() => { prefetch(id); if (!th.closest(".short,.pl")) hoverPreview(th, id); }, 450);
  });
  document.addEventListener("pointerout", (e) => {
    const th = e.target.closest(".card .th");
    if (!th || th.contains(e.relatedTarget)) return;
    delete th.dataset.hov;
    clearTimeout(hovT);
    clearInterval(th._sbT);
    th.querySelector(".sbprev")?.remove();
  });
  document.addEventListener("touchstart", (e) => {
    const c = e.target.closest("[data-vid]");
    if (c) prefetch(c.dataset.vid);
  }, { passive: true });
}
