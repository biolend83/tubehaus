// List pages: home, search, channel, playlist, subscriptions, history, you, trending, settings
import { $, $$, el, esc, icon, api, lib, settings, local, toast, fmtTime, fmtViews, ago, thumb, normVideo, pickThumb, localUrl, safeHtml, linkify, dropCache, PUBLIC, localLibrary, IPHONE } from "./util.js";
import { state, card, metaLine, chAvatar, channelCard, playlistCard, norm, remember, known, fillProgress, infinite, avatar, subBtn, isSub, navigate, popup, mi, videoMenu, dialog, loadLib, setList, pageCleanup, renderGuideSubs } from "./core.js";
import { SB_CATS } from "./player.js";
import { $t, LANG, LANGS, LOCALE, REGION, fmtShort, langLabel } from "./i18n.js";
import { bgFallback } from "./avatars.js";

const grid = (cls = "") => `<div class="vgrid ${cls}"></div>`;
const spinner = `<div class="loading"><div class="spinner"></div></div>`;
const skel = (n = 12) => `<div class="sk"><i class="skt"></i><i class="skl"></i><i class="skl s"></i></div>`.repeat(n);
const skelRows = (n = 6) => `<div class="sk row"><i class="skt"></i><div><i class="skl"></i><i class="skl s"></i><i class="skl s"></i></div></div>`.repeat(n);
// the home page's first pick, big: the cover with its colours blurred behind, a bold title, a play button
function hero(v) {
  const href = `/watch?v=${esc(v.id)}`;
  const hq = thumb(v.id);
  return `<section class="hero" data-ctx="home" style="--cover:url('${hq}')">
    <a href="${href}" data-nav class="hero-th"><img src="${thumb(v.id, "maxresdefault")}" alt="" onload="if(this.naturalWidth<=120)this.src='${hq}'" onerror="this.onerror=null;this.src='${hq}'"></a>
    <div class="hero-tx">
      <span class="hero-k">${icon("trending", "sm")}${$t("추천")}</span>
      <a href="${href}" data-nav class="hero-t" title="${esc(v.title)}">${esc(v.title)}</a>
      <div class="hero-ch">${v.authorId ? `<a href="/channel/${esc(v.authorId)}" data-nav>${avatar(chAvatar(v), "md")}<b>${esc(v.author)}</b></a>` : `<b>${esc(v.author || "")}</b>`}</div>
      <div class="hero-s">${metaLine(v)}</div>
      ${v.desc ? `<div class="hero-d">${esc(v.desc.slice(0, 220))}</div>` : ""}
      <div class="hero-btns"><a href="${href}" data-nav class="pill white">${icon("play")}${$t("재생")}</a>
        <button class="pill round" data-menu="${esc(v.id)}" aria-label="${$t("작업 메뉴")}">${icon("more")}</button></div>
    </div></section>`;
}
const empty = (ic, msg, extra = "") => `<div class="empty">${icon(ic)}<p>${msg}</p>${extra}</div>`;

function append(box, list, kind, extra) {
  box.insertAdjacentHTML("beforeend", list.map((v) => card(v, kind, extra)).join(""));
  fillProgress(box);
}

// feed item (the server's digest of the RSS feeds) -> card object
const feedNorm = (items) => remember(items.map((x) => normVideo({ ...x, authorThumb: state.lib.subs[x.authorId]?.thumb })));

// ---------------------------------------------------------------- home
const CHIPS = [
  ["all", $t("전체")], ["live", $t("실시간")], ["music", $t("음악")], ["news", $t("뉴스")], ["game", $t("게임")],
  ["sports", $t("스포츠")], ["learn", $t("학습")], ["fashion", $t("패션·뷰티")], ["movie", $t("영화")], ["cook", $t("요리")],
  ["recent", $t("최근에 업로드된 동영상")], ["watched", $t("감상한 동영상")],
];
// chips that open YouTube's own topic page (the server reads it): its shelves, in YouTube's order
const CHIP_TOPIC = { live: "live", music: "music", news: "news", game: "gaming", sports: "sports", learn: "learning", fashion: "fashion" };
const CHIP_SEARCH = {
  live: { q: $t("라이브"), features: "live", sort: "relevance" },
  music: { q: $t("음악"), date: "week", sort: "views" },
  news: { q: $t("뉴스"), date: "today", sort: "views" },
  sports: { q: $t("스포츠 하이라이트"), date: "week", sort: "views" },
  movie: { q: $t("영화 리뷰"), date: "month", sort: "views" },
  cook: { q: $t("요리 레시피"), date: "month", sort: "views" },
  learn: { q: $t("강의"), date: "month", sort: "views" },
  fashion: { q: $t("패션·뷰티"), date: "week", sort: "views" },
};

// 1.0: a card at the top of Home saying what is new, until it is closed (or Music is opened from it)
function hello10(page) {
  const it = (ic, t, s) => `<div class="hi10-it"><span class="hi10-ic">${icon(ic)}</span><div><b>${t}</b><small>${s}</small></div></div>`;
  const card = el(`<section class="hi10" role="note">
    <button class="hi10-x" aria-label="${$t("닫기")}">${icon("close")}</button>
    <div class="hi10-top"><span class="hi10-v">1.0</span><div><h2>${$t("튜브하우스")} 1.0</h2><p>${$t("가장 큰 업데이트예요")}</p></div></div>
    <div class="hi10-its">${it("music", $t("음악"), $t("노래·앨범·아티스트, 가사까지"))}${it("sparkle", $t("새 디자인"), $t("더 깔끔한 화면과 부드러운 움직임"))}${it("phone", $t("휴대폰에서도 편하게"), $t("큰 화면 목록과 아래 메뉴"))}</div>
    <a class="pill white" href="/feed/music" data-nav>${icon("music")}${$t("음악 열기")}</a>
  </section>`);
  const bye = () => {
    local.set("hi10", true);
    card.classList.add("bye");
    setTimeout(() => card.remove(), 320);
  };
  card.querySelector(".hi10-x").onclick = bye;
  card.querySelector("a").addEventListener("click", () => local.set("hi10", true));
  page.querySelector(".chips").after(card);
}
export async function home(page, m, alive) {
  // calm mode: the home screen is just your subscriptions, newest first
  const chip = settings.calm ? "recent" : new URLSearchParams(location.search).get("chip") || "all";
  page.innerHTML = `<div class="chips">${CHIPS.map(([k, l]) => `<a class="chip ${k === chip ? "on" : ""}" href="/?chip=${k}" data-nav>${l}</a>`).join("")}</div>
    <div class="shorts-shelf" hidden></div><div data-ctx="home">${grid("home")}</div>`;
  if (chip === "all" && !settings.calm && !local.get("hi10", false)) hello10(page);
  const box = $(".vgrid", page);
  box.innerHTML = skel();
  await state.libReady;
  let pool = [];
  let more = null;
  // a topic: YouTube's shelves for it; when the region has none (News in some countries) or the
  // server can't reach YouTube, the search or trending list below as before
  if (CHIP_TOPIC[chip] && await topicShelves(box, CHIP_TOPIC[chip], alive)) return;
  if (chip === "all") {
    pool = await homeMix();
  } else if (chip === "recent") {
    const f = await lib("feed", { ttl: 60000 });
    pool = feedNorm(f.items.filter((x) => !x.short));
  } else if (chip === "watched") {
    const h = await lib("hist?n=200", { ttl: 30000 });
    pool = remember(h.items.map(normVideo));
  } else if (chip === "game") {
    pool = norm(await api(`/api/v1/trending?region=${REGION}&type=gaming`, { ttl: 600000 }));
  } else {
    const p = CHIP_SEARCH[chip];
    let pg = 1;
    const q = (n) => `/api/v1/search?${new URLSearchParams({ q: p.q, type: "video", region: REGION, page: n, sort: p.sort, ...(p.date ? { date: p.date } : {}), ...(p.features ? { features: p.features } : {}) })}`;
    pool = norm(await api(q(1), { ttl: 300000 }));
    more = async () => { const r = norm(await api(q(++pg), { ttl: 300000 })); append(box, r, "grid", ""); return r.length > 0; };
  }
  if (!alive()) return;
  box.innerHTML = "";
  if (!pool.length) {
    box.outerHTML = chip === "recent" || chip === "all"
      ? empty("subs", $t("구독한 채널이 없어서 보여줄 영상이 아직 없어요. 검색해서 마음에 드는 채널을 구독해 보세요."), `<a class="pill" href="/feed/channels" data-nav>${$t("구독 가져오기")}</a>`)
      : empty("info", $t("영상이 없어요"));
    return;
  }
  if (chip === "all" && !settings.calm && !document.documentElement.classList.contains("tv")) {
    const k = pool.findIndex((v) => !v.upcoming && !v.live && !v.short);
    if (k >= 0) $(".shorts-shelf", page).insertAdjacentHTML("beforebegin", hero(pool.splice(k, 1)[0]));
  }
  // render a bit more on every scroll instead of everything at once
  let i = 0;
  const step = () => { const part = pool.slice(i, i + 24); i += 24; append(box, part, "grid", ""); return i < pool.length; };
  step();
  infinite(box, async () => (i < pool.length ? step() : more ? more() : false));
  if (chip === "all" && !settings.calm) shortsShelf($(".shorts-shelf", page));
}

export async function topicShelves(box, key, alive) {
  let d;
  try { d = await lib(`topic?key=${key}&region=${REGION}&hl=${LANG}`, { ttl: 900000, quiet: true }); } catch { return false; }
  if (!alive()) return true;
  const shelves = (d.shelves || []).map((s) => {
    const items = s.items || [];
    const vids = norm(items.filter((x) => x.type === "video"));
    const byId = new Map(vids.map((v) => [v.id, v]));
    // keep YouTube's order of videos and playlists
    return { title: s.title, list: items.map((x) => (x.type === "playlist" ? { pl: x } : byId.get(x.videoId))).filter(Boolean) };
  }).filter((s) => s.list.length);
  if (!shelves.length) return false;
  const SHOW = 8;   // two rows of a wide window; the rest behind "N more"
  const cardOf = (x) => (x.pl ? playlistCard(x.pl) : card(x, x.short ? "short" : "grid"));
  const wrap = el(`<div class="topic"></div>`);
  wrap.innerHTML = shelves.map((s, i) => {
    const shorts = s.list.every((x) => x.short);
    const body = shorts
      ? `<div class="shorts-row">${s.list.map(cardOf).join("")}</div>`
      : `<div class="vgrid">${s.list.map((x, k) => (k < SHOW ? cardOf(x) : cardOf(x).replace(/^<div class="card/, '<div hidden class="card'))).join("")}</div>`
        + (s.list.length > SHOW ? `<button class="pill ghost topic-more">${$t("{0}개 더보기", s.list.length - SHOW)}</button>` : "");
    return `<section class="topic-shelf">${s.title ? `<div class="shelf-h"><b>${esc(s.title)}</b></div>` : ""}${body}</section>`;
  }).join("");
  wrap.addEventListener("click", (e) => {
    const b = e.target.closest(".topic-more");
    if (!b) return;
    const sec = b.closest(".topic-shelf");
    const open = !sec.classList.toggle("open");
    const cards = $$(".vgrid > .card", sec);
    cards.forEach((c, k) => { if (k >= SHOW) c.hidden = open; });
    b.textContent = open ? $t("{0}개 더보기", cards.length - SHOW) : $t("간략히 보기");
  });
  box.replaceWith(wrap);
  fillProgress(wrap);
  return true;
}

async function homeMix() {
  const [feed, hist, trend] = await Promise.allSettled([
    lib("feed", { ttl: 120000 }),
    lib("hist?n=6", { ttl: 30000 }),
    api(`/api/v1/trending?region=${REGION}&type=gaming`, { ttl: 600000 }),
  ]);
  const watched = new Set();
  const histItems = hist.status === "fulfilled" ? hist.value.items : [];
  histItems.forEach((h) => watched.add(h.id));
  // related videos of recently watched ones = recommendations to your taste
  const rel = (await Promise.allSettled(histItems.slice(0, 3).map((h) => api(`/api/v1/videos/${h.id}`, { ttl: 1800000 }))))
    .filter((r) => r.status === "fulfilled").map((r) => norm(r.value.recommendedVideos || []));
  const feedItems = feed.status === "fulfilled" ? feedNorm(feed.value.items.filter((x) => !x.short)) : [];
  const pools = [
    feedItems.filter((v) => Date.now() / 1000 - v.published < 7 * 86400),
    ...rel,
    trend.status === "fulfilled" ? norm(trend.value) : [],
    feedItems.filter((v) => Date.now() / 1000 - v.published >= 7 * 86400),
  ].map((p) => p.filter((v) => !watched.has(v.id)));
  const out = [], seen = new Set();
  let added = true;
  while (added) {
    added = false;
    for (const p of pools) {
      // mix: two from the subscription feed, then one of the rest
      for (let k = 0; k < (p === pools[0] ? 2 : 1); k++) {
        const v = p.shift();
        if (!v) break;
        added = true;
        if (!seen.has(v.id)) { seen.add(v.id); out.push(v); }
      }
    }
  }
  return out;
}

async function shortsShelf(box) {
  try {
    const f = await lib("feed", { ttl: 120000 });
    let list = [];
    try { list = norm((await lib(`shorts/next?n=12&region=${REGION}`, { quiet: true })).items).map((v) => ({ ...v, short: true })); } catch { }
    if (list.length < 6) list = [...new Map(list.concat(feedNorm(f.items.filter((x) => x.short))).map((v) => [v.id, v])).values()].slice(0, 12);
    if (list.length < 6) {
      const s = norm(await api(`/api/v1/search?q=%23shorts&type=video&duration=short&region=${REGION}&sort=views&date=week`, { ttl: 600000 }));
      list = list.concat(s.map((v) => ({ ...v, short: true }))).slice(0, 12);
      remember(list);
    }
    if (!list.length) return;
    box.hidden = false;
    box.innerHTML = `<div class="shelf-h">${icon("shorts", "red")}<b>Shorts</b></div><div class="shorts-row">${list.map((v) => card(v, "short")).join("")}</div>`;
    sessionStorage.setItem("yt.shortsCtx", JSON.stringify(list.map((v) => v.id)));
  } catch { }
}

// ---------------------------------------------------------------- search
const FILTERS = {
  date: [$t("업로드 날짜"), [["", $t("전체")], ["hour", $t("지난 1시간")], ["today", $t("오늘")], ["week", $t("이번 주")], ["month", $t("이번 달")], ["year", $t("올해")]]],
  type: [$t("구분"), [["all", $t("전체")], ["video", $t("동영상")], ["channel", $t("채널")], ["playlist", $t("재생목록")], ["movie", $t("영화")]]],
  duration: [$t("길이"), [["", $t("전체")], ["short", $t("4분 미만")], ["medium", $t("4~20분")], ["long", $t("20분 초과")]]],
  features: [$t("기능별"), [["live", $t("실시간")], ["4k", "4K"], ["hd", "HD"], ["subtitles", $t("자막")], ["creative_commons", $t("크리에이티브 커먼즈")], ["360", "360°"], ["vr180", "VR180"], ["3d", "3D"], ["hdr", "HDR"], ["location", $t("위치")], ["purchased", $t("구매함")]]],
  sort: [$t("정렬기준"), [["relevance", $t("관련성")], ["date", $t("업로드 날짜")], ["views", $t("조회수")], ["rating", $t("평점")]]],
};
export async function search(page, m, alive) {
  const sp = new URLSearchParams(location.search);
  const q = sp.get("search_query") || sp.get("q") || "";
  $("#q").value = q;
  if (!q) { page.innerHTML = empty("search", $t("검색어를 입력하세요")); return; }
  const f = { date: sp.get("date") || "", type: sp.get("type") || "all", duration: sp.get("duration") || "", features: (sp.get("features") || "").split(",").filter(Boolean), sort: sp.get("sort") || "relevance" };
  const active = (f.date ? 1 : 0) + (f.type !== "all" ? 1 : 0) + (f.duration ? 1 : 0) + f.features.length + (f.sort !== "relevance" ? 1 : 0);
  page.innerHTML = `<div class="search-page"><div class="sp-top"><button class="pill ghost filt">${$t("{0} 필터{1}", icon("tune"), active ? ` (${active})` : "")}</button></div>
    <div class="filters" hidden></div><div class="results"></div></div>`;
  const filt = $(".filters", page);
  filt.innerHTML = Object.entries(FILTERS).map(([k, [title, opts]]) => `<div class="fcol"><h4>${title}</h4>${opts.map(([v, l]) => {
    const on = k === "features" ? f.features.includes(v) : f[k] === v;
    const nf = { ...f, features: [...f.features] };
    if (k === "features") nf.features = on ? nf.features.filter((x) => x !== v) : [...nf.features, v];
    else nf[k] = on && v ? "" : v;
    const u = new URLSearchParams({ search_query: q });
    for (const [kk, vv] of Object.entries(nf)) { const s = Array.isArray(vv) ? vv.join(",") : vv; if (s && !(kk === "type" && s === "all") && !(kk === "sort" && s === "relevance")) u.set(kk, s); }
    return `<a href="/results?${u}" data-nav class="${on ? "on" : ""}">${l}${on && k !== "type" && k !== "sort" ? " " + icon("close") : ""}</a>`;
  }).join("")}</div>`).join("");
  $(".filt", page).onclick = () => (filt.hidden = !filt.hidden);
  const box = $(".results", page);
  box.innerHTML = skelRows();
  let pg = 1;
  const url = (n) => {
    const u = new URLSearchParams({ q, page: n, region: REGION, type: f.type, sort: f.sort });
    if (f.date) u.set("date", f.date);
    if (f.duration) u.set("duration", f.duration);
    if (f.features.length) u.set("features", f.features.join(","));
    return "/api/v1/search?" + u;
  };
  const draw = (list) => {
    const html = list.map((x) => {
      if (x.type === "channel") return channelCard(x);
      if (x.type === "playlist") return `<div class="row-pl">${playlistCard(x)}</div>`;
      if (x.type === "hashtag") return "";
      const v = remember([normVideo(x)])[0];
      return card(v, "row");
    }).join("");
    box.insertAdjacentHTML("beforeend", html);
    fillProgress(box);
  };
  const first = await api(url(1), { ttl: 120000 });
  if (!alive()) return;
  box.innerHTML = "";
  if (!first.length) { box.innerHTML = empty("search", $t("검색결과가 없습니다"), `<p class='sub'>${$t("다른 검색어를 시도하거나 필터를 지워 보세요")}</p>`); return; }
  draw(first);
  infinite(box, async () => { const r = await api(url(++pg), { ttl: 120000 }); draw(r); return r.length > 0; });
  // search history (this device)
  const h = local.get("sh", []).filter((x) => x !== q);
  h.unshift(q);
  local.set("sh", h.slice(0, 30));
}

// ---------------------------------------------------------------- channel
const ucidCache = new Map();
async function resolveChannel(path) {
  // /channel/UC.., /@handle, /c/name, /user/name → UC id
  const m = path.match(/^\/channel\/(UC[\w-]{22})/);
  if (m) return m[1];
  const key = (path.match(/^\/(@[^/]+|c\/[^/]+|user\/[^/]+)/) || [path])[0];
  if (ucidCache.has(key)) return ucidCache.get(key);
  const r = await api(`/api/v1/resolveurl?url=${encodeURIComponent("https://www.youtube.com" + decodeURIComponent(key))}`, { ttl: 86400000 });
  if (!r.ucid) throw new Error($t("채널을 찾을 수 없어요"));
  ucidCache.set(key, r.ucid);
  return r.ucid;
}
const CTABS = [["", $t("홈")], ["videos", $t("동영상")], ["shorts", "Shorts"], ["streams", $t("라이브")], ["playlists", $t("재생목록")], ["community", $t("게시물")]];
export async function channel(page, m, alive) {
  page.innerHTML = spinner;
  const path = location.pathname;
  const ucid = await resolveChannel(path);
  const tabM = path.match(/\/(videos|shorts|streams|playlists|community|search)$/);
  const tab = tabM ? tabM[1] : "";
  const c = await api(`/api/v1/channels/${ucid}`, { ttl: 600000 });
  if (!alive()) return;
  const av = pickThumb(c.authorThumbnails, 176);
  const banner = c.authorBanners?.length ? localUrl([...c.authorBanners].sort((a, b) => b.width - a.width)[0].url) : "";
  const base = `/channel/${ucid}`;
  const tabsAvail = new Set((c.tabs || ["videos", "shorts", "streams", "playlists"]).map((t) => t.toLowerCase()).filter((t) => !(settings.calm && t === "shorts")));
  const sp = new URLSearchParams(location.search);
  page.innerHTML = `
    <div class="channel">
      ${banner ? `<div class="banner" style="background-image:url('${esc(banner)}')"></div>` : ""}
      <div class="ch-head">${avatar(av, "xxl")}
        <div class="ch-info"><h1>${esc(c.author)}${c.authorVerified ? icon("verified", "vf") : ""}</h1>
          <div class="s">${c.channelHandle ? `<b>${esc(c.channelHandle)}</b> • ` : ""}${esc($t("구독자 {0}명", fmtSubCount(c.subCount)))}${c.totalViews ? " • " + esc(fmtViews(c.totalViews)) : ""}</div>
          <div class="ch-desc">${esc((c.description || "").split("\n")[0].slice(0, 120))}${c.description ? ` <button class="more-about">${$t("더보기")}</button>` : ""}</div>
          <div class="ch-btns">${subBtn(ucid, c.author, av, true)}<a class="pill ghost" href="https://www.youtube.com/channel/${esc(ucid)}" target="_blank" rel="noopener">${icon("open")}</a></div>
        </div>
      </div>
      <div class="ctabs">${CTABS.filter(([k]) => !k || tabsAvail.has(k) || (k === "videos")).map(([k, l]) => `<a href="${base}${k ? "/" + k : ""}" data-nav class="${k === tab ? "on" : ""}">${l}</a>`).join("")}
        <form class="ch-search"><button type="button">${icon("search")}</button><input placeholder="${$t("검색")}" value="${esc(tab === "search" ? sp.get("query") || "" : "")}"></form></div>
      <div class="ch-body"></div>
    </div>`;
  $(".more-about", page)?.addEventListener("click", () => dialog(`<div class="dh"><b>${esc(c.author)}</b><button data-close>${icon("close")}</button></div>
    <div class="about">${linkify(c.description || "")}<hr><p>${c.joined ? $t("가입일: ") + new Date(c.joined * 1000).toLocaleDateString(LOCALE) : ""}</p><p>${c.totalViews ? fmtViews(c.totalViews) : ""}</p><p>youtube.com/channel/${esc(ucid)}</p></div>`));
  if (banner) bgFallback($(".banner", page), banner);
  const sf = $(".ch-search", page);
  sf.onsubmit = (e) => { e.preventDefault(); const v = sf.querySelector("input").value.trim(); if (v) navigate(`${base}/search?query=${encodeURIComponent(v)}`); };
  sf.querySelector("button").onclick = () => sf.classList.add("open") || sf.querySelector("input").focus();
  const body = $(".ch-body", page);

  if (tab === "") {
    const lv = norm(c.latestVideos || []);
    body.innerHTML = (lv.length ? `<h3 class="sec">${$t("최신 동영상")}</h3><div class="vgrid">${lv.slice(0, 12).map((v) => card(v, "grid")).join("")}</div>` : "") +
      (c.relatedChannels?.length ? `<h3 class="sec">${$t("추천 채널")}</h3><div class="chan-row">${c.relatedChannels.map((r) => `<a href="/channel/${esc(r.authorId)}" data-nav class="chan-mini">${avatar(pickThumb(r.authorThumbnails, 176), "xl")}<span>${esc(r.author)}</span></a>`).join("")}</div>` : "");
    fillProgress(body);
    if (!lv.length) body.innerHTML = empty("info", $t("아직 동영상이 없는 채널이에요"));
    return;
  }
  if (tab === "playlists") {
    body.innerHTML = `<div class="vgrid"></div>`;
    const g = $(".vgrid", body);
    let cont = "";
    const load = async () => {
      const r = await api(`/api/v1/channels/${ucid}/playlists${cont ? "?continuation=" + encodeURIComponent(cont) : ""}`, { ttl: 600000 });
      g.insertAdjacentHTML("beforeend", (r.playlists || []).map(playlistCard).join(""));
      cont = r.continuation;
      return !!cont;
    };
    if (await load()) infinite(g, load);
    if (!g.children.length) body.innerHTML = empty("playlists", $t("재생목록이 없어요"));
    return;
  }
  if (tab === "community") {
    body.innerHTML = spinner;
    try {
      const r = await api(`/api/v1/channels/${ucid}/community`, { ttl: 600000 });
      body.innerHTML = `<div class="posts">${(r.comments || []).map((p) => `<div class="post">${avatar(av, "sm")}<div><b>${esc(p.author)}</b> <span class="s">${esc(p.publishedText || "")}</span>
        <div class="pc">${safeHtml(p.contentHtml || esc(p.content))}</div>
        ${p.attachment?.imageThumbnails ? `<img class="pimg" loading="lazy" src="${esc(pickThumb(p.attachment.imageThumbnails, 600))}">` : ""}
        ${p.attachment?.type === "video" && p.attachment.videoId ? card(remember([normVideo(p.attachment)])[0], "row") : ""}
        <div class="s">${icon("like")} ${esc(p.likeCount ?? "")}</div></div></div>`).join("") || empty("chat", $t("게시물이 없어요"))}</div>`;
    } catch { body.innerHTML = empty("chat", $t("게시물을 불러오지 못했어요")); }
    return;
  }
  if (tab === "search") {
    const qq = sp.get("query") || "";
    body.innerHTML = `<div class="results"></div>`;
    const g = $(".results", body);
    let pg = 1;
    const load = async () => { const r = norm(await api(`/api/v1/channels/${ucid}/search?q=${encodeURIComponent(qq)}&page=${pg++}`, { ttl: 300000 })); append(g, r, "row"); return r.length > 0; };
    if (await load()) infinite(g, load);
    if (!g.children.length) body.innerHTML = empty("search", $t("검색결과가 없어요"));
    return;
  }
  // videos | shorts | streams
  const sort = sp.get("sort") || "newest";
  body.innerHTML = (tab !== "shorts" ? `<div class="chips small">${[["newest", $t("최신순")], ["popular", $t("인기순")], ["oldest", $t("날짜순")]].map(([k, l]) =>
    `<a class="chip ${k === sort ? "on" : ""}" href="${base}/${tab}?sort=${k}" data-nav>${l}</a>`).join("")}</div>` : "") + `<div class="vgrid ${tab === "shorts" ? "shorts-grid" : ""}"></div>`;
  const g = $(".vgrid", body);
  let cont = "";
  const load = async () => {
    const r = await api(`/api/v1/channels/${ucid}/${tab}?sort_by=${sort}${cont ? "&continuation=" + encodeURIComponent(cont) : ""}`, { ttl: 300000 });
    let list = norm(r.videos || []);
    if (tab === "shorts") list = list.map((v) => ({ ...v, short: true }));
    remember(list);
    append(g, list, tab === "shorts" ? "short" : "grid");
    if (tab === "shorts") sessionStorage.setItem("yt.shortsCtx", JSON.stringify($$("[data-vid]", g).map((x) => x.dataset.vid)));
    cont = r.continuation;
    return !!cont && list.length > 0;
  };
  if (await load()) infinite(g, load);
  if (!g.children.length) body.innerHTML = empty("info", $t("동영상이 없어요"));
}
const fmtSubCount = (n) => (n == null ? "?" : fmtShort(+n));

// ---------------------------------------------------------------- playlist
export async function loadPlaylist(id) {
  // WL / LL / my playlists (HP…) / YouTube playlists (PL…, RD… mixes)
  if (id === "WL" || id === "LL") {
    await state.libReady;
    const items = id === "WL" ? state.lib.wl : state.lib.likes;
    return { id, mine: true, title: id === "WL" ? $t("나중에 볼 동영상") : $t("좋아요 표시한 동영상"), author: $t("나"), videos: remember(items.map(normVideo)) };
  }
  if (id === "QUEUE") {
    const { queue } = await import("./core.js");
    return { id, mine: true, title: $t("현재 재생목록"), author: $t("나"), videos: remember(queue.get().map(normVideo)) };
  }
  if (/^HP/.test(id)) {
    const p = await lib("pl?id=" + encodeURIComponent(id), { ttl: 5000 });
    return { id, mine: true, title: p.title, author: $t("나"), updated: p.updated, videos: remember(p.items.map(normVideo)) };
  }
  if (/^RD/.test(id)) {
    const p = await api(`/api/v1/mixes/${id}`, { ttl: 600000 });
    return { id, title: p.title || $t("믹스"), author: "YouTube", videos: norm(p.videos || []) };
  }
  const p = await api(`/api/v1/playlists/${id}`, { ttl: 600000 });
  let videos = p.videos || [];
  // long playlists: load the next pages too (up to 5)
  for (let pg = 2; videos.length < (p.videoCount || 0) && pg <= 5; pg++) {
    const more = await api(`/api/v1/playlists/${id}?page=${pg}`, { ttl: 600000 }).catch(() => null);
    if (!more || !more.videos?.length) break;
    const seen = new Set(videos.map((v) => v.videoId));
    const add = more.videos.filter((v) => !seen.has(v.videoId));
    if (!add.length) break;
    videos = videos.concat(add);
  }
  return { id, title: p.title, author: p.author, authorId: p.authorId, desc: p.description, count: p.videoCount, views: p.viewCount, updated: p.updated, videos: norm(videos) };
}
export async function playlist(page, m, alive) {
  const id = new URLSearchParams(location.search).get("list");
  if (!id) return navigate("/feed/playlists", { replace: true });
  page.innerHTML = spinner;
  const p = await loadPlaylist(id);
  if (!alive()) return;
  const first = p.videos[0];
  const ctxParam = (v, i) => `&list=${encodeURIComponent(id)}&index=${i + 1}`;
  page.innerHTML = `<div class="plpage" ${p.mine ? `data-pl="${esc(id === "WL" ? "wl" : id === "LL" ? "likes" : id)}" data-ctx="pl"` : ""}>
    <aside class="pl-hero" style="--cover:url('${first ? thumb(first.id) : ""}')"><div class="pl-hero-in">
      ${first ? `<a href="/watch?v=${first.id}${ctxParam(first, 0)}" data-nav class="pl-cover"><img src="${thumb(first.id)}" alt=""><span>${$t("{0} 모두 재생", icon("play"))}</span></a>` : ""}
      <h1>${esc(p.title)}</h1>
      <div class="pl-by">${p.authorId ? `<a href="/channel/${esc(p.authorId)}" data-nav>${esc(p.author)}</a>` : esc(p.author || "")}</div>
      <div class="s">${$t("동영상 {0}개{1}{2}", p.count ?? p.videos.length, p.views ? " • " + fmtViews(p.views) : "", p.updated ? " • " + $t("{0} 업데이트", ago(p.updated)) : "")}</div>
      ${p.desc ? `<div class="pl-desc">${esc(p.desc)}</div>` : ""}
      <div class="pl-btns">
        ${first ? `<a class="pill white" href="/watch?v=${first.id}${ctxParam(first, 0)}" data-nav>${$t("{0} 모두 재생", icon("play"))}</a><button class="pill ghost shuf">${$t("{0} 셔플", icon("shuffle"))}</button>` : ""}
        ${p.mine && /^HP/.test(id) ? `<button class="pill ghost ren">${icon("notes")}</button><button class="pill ghost del">${icon("trash")}</button>` : ""}
      </div></div></aside>
    <div class="pl-list">${p.videos.map((v, i) => `<div class="plrow" data-vid="${esc(v.id)}"><span class="idx">${i + 1}</span>${card(v, "row", ctxParam(v, i)).replace('class="card row"', 'class="card row slim"')}</div>`).join("") || empty("playlists", $t("재생목록에 동영상이 없어요"))}</div></div>`;
  fillProgress(page);
  $(".shuf", page)?.addEventListener("click", () => {
    const i = Math.floor(Math.random() * p.videos.length);
    sessionStorage.setItem("yt.shuffle", id);
    navigate(`/watch?v=${p.videos[i].id}${ctxParam(p.videos[i], i)}`);
  });
  $(".del", page)?.addEventListener("click", async () => {
    if (!confirm(`${$t("\"{0}\" 재생목록을 삭제할까요?", p.title)}`)) return;
    await lib("pl/del", { method: "POST", body: { id } });
    delete state.lib.playlists[id];
    toast($t("재생목록을 삭제했어요"));
    navigate("/feed/playlists", { replace: true });
  });
  $(".ren", page)?.addEventListener("click", async () => {
    const t = prompt($t("새 이름"), p.title);
    if (!t || !t.trim()) return;
    const r = await lib("pl/rename", { method: "POST", body: { id, title: t.trim() } });
    state.lib.playlists[id] = r.playlist;
    $(".pl-hero h1", page).textContent = t.trim();
  });
}

// ---------------------------------------------------------------- subscriptions
export async function subscriptions(page, m, alive) {
  await state.libReady;
  const subs = Object.entries(state.lib.subs);
  page.innerHTML = `<div class="feedhead"><h2>${$t("최신순")}</h2><a class="pill ghost" href="/feed/channels" data-nav>${$t("관리")}</a></div>
    <div class="sub-strip">${subs.slice(0, 30).map(([id, s]) => `<a href="/channel/${esc(id)}" data-nav>${avatar(s.thumb, "md")}<span>${esc(s.name)}</span></a>`).join("")}</div>
    <div class="shorts-shelf" hidden></div>${grid()}`;
  const box = $(".vgrid", page);
  if (!subs.length) { box.outerHTML = empty("subs", $t("구독한 채널이 없어요"), `<a class="pill" href="/feed/channels" data-nav>${$t("구독 가져오기")}</a>`); return; }
  box.innerHTML = skel();
  const f = await lib("feed", { ttl: 60000 });
  if (!alive()) return;
  box.innerHTML = "";
  const all = feedNorm(f.items);
  const vids = all.filter((v) => !v.short);
  const shorts = all.filter((v) => v.short).slice(0, 12);
  if (shorts.length) {
    const sh = $(".shorts-shelf", page);
    sh.hidden = false;
    sh.innerHTML = `<div class="shelf-h">${icon("shorts", "red")}<b>Shorts</b></div><div class="shorts-row">${shorts.map((v) => card(v, "short")).join("")}</div>`;
    sessionStorage.setItem("yt.shortsCtx", JSON.stringify(shorts.map((v) => v.id)));
  }
  let i = 0;
  const step = () => { append(box, vids.slice(i, i + 30), "grid"); i += 30; return i < vids.length; };
  step();
  infinite(box, async () => step());
  if (!vids.length) box.outerHTML = empty("subs", f.updating ? $t("구독 채널의 새 영상을 모으는 중이에요. 잠시 뒤 새로고침해 주세요.") : $t("새 영상이 없어요"));
}

export async function channelsMgr(page) {
  await state.libReady;
  const draw = () => {
    const subs = Object.entries(state.lib.subs).sort((a, b) => a[1].name.localeCompare(b[1].name, LANG));
    page.innerHTML = `<div class="narrow-page"><h2>${$t("구독 채널 {0}개", subs.length)}</h2>
      <div class="imp"><button class="pill ghost file">${$t("{0} 파일에서 가져오기", icon("upload"))}</button>
      <button class="pill ghost exp">${$t("{0} 내보내기", icon("download"))}</button>
      <button class="pill ghost inv">${$t("{0} Invidious 계정의 구독 가져오기", icon("download"))}</button>
      <input type="file" class="impfile" hidden accept=".json,.csv,.opml,.xml,.db,.txt">
      <form class="addch"><input placeholder="${$t("채널 주소나 @핸들 (예: @MBCNEWS11)")}"><button class="pill">${$t("추가")}</button></form></div>
      ${PUBLIC ? `<p class="sub pub-note">${$t("공개 서버예요. 구독과 기록은 이 브라우저에만 저장되고 서버에는 남지 않아요. 브라우저 데이터를 지우면 사라지니 가끔 내보내기로 백업해 두세요.")}</p>` : ""}
      <p class="sub imp-hint">${$t("구글 Takeout (구독 CSV, 시청 기록), Invidious, NewPipe, FreeTube, OPML, TubeHaus 백업 파일을 가져올 수 있어요")}</p>
      <div class="chlist">${subs.map(([id, s]) => `<div class="chrow"><a href="/channel/${esc(id)}" data-nav>${avatar(s.thumb, "md")}<span>${esc(s.name)}</span></a>${subBtn(id, s.name, s.thumb)}</div>`).join("") || `<p class='sub'>${$t("아직 없어요")}</p>`}</div></div>`;
    $(".inv", page).onclick = () => importInvidious(true).then(draw);
    $(".file", page).onclick = () => $(".impfile", page).click();
    $(".impfile", page).onchange = async (e) => { if (await importFile(e.target.files[0])) draw(); };
    $(".exp", page).onclick = exportDialog;
    $(".addch", page).onsubmit = async (e) => {
      e.preventDefault();
      const v = $(".addch input", page).value.trim();
      if (!v) return;
      try {
        let path = v.replace(/^https?:\/\/(www\.|m\.)?youtube\.com/, "");
        if (!path.startsWith("/")) path = v.startsWith("@") ? "/" + v : v.startsWith("UC") ? "/channel/" + v : "/@" + v;
        const ucid = await resolveChannel(path);
        const c = await api(`/api/v1/channels/${ucid}`, { ttl: 600000 });
        const { setSub } = await import("./core.js");
        await setSub(ucid, c.author, pickThumb(c.authorThumbnails, 176), true);
        draw();
      } catch (ex) { toast(ex.message); }
    };
  };
  draw();
}
// Your data is never stuck here: export everything, import from other apps
export function exportDialog() {
  // public instance: the library lives in this browser, so send it along and let the server only convert it
  const f = (fmt, label, desc) => PUBLIC
    ? `<button class="exp-row" data-fmt="${fmt}"><b>${label}</b><span>${desc}</span></button>`
    : `<a class="exp-row" href="/api/p/yt/export?format=${fmt}" download><b>${label}</b><span>${desc}</span></a>`;
  const { box } = dialog(`<div class="dh"><b>${$t("데이터 내보내기")}</b><button data-close>${icon("close")}</button></div>
    <div class="exp-list">
      ${f("tubehaus", $t("TubeHaus 백업"), $t("구독, 시청 기록, 이어보기, 재생목록, 좋아요까지 전부. 그대로 다시 가져올 수 있어요"))}
      ${f("invidious", $t("Invidious 형식"), $t("구독, 시청 기록, 재생목록. Invidious 와 그 앱들에서 가져올 수 있어요"))}
      ${f("csv", "CSV", $t("구독 목록 (구글 Takeout 과 같은 형식)"))}
      ${f("opml", "OPML", $t("구독 목록 (RSS 리더용)"))}
    </div>`);
  box.querySelectorAll("button.exp-row").forEach((b) => (b.onclick = async () => {
    const r = await fetch("/api/p/yt/export", { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ format: b.dataset.fmt, lib: localLibrary() }) });
    const name = (r.headers.get("Content-Disposition") || "").match(/filename="([^"]+)"/)?.[1] || "tubehaus-export";
    const a = el(`<a href="${URL.createObjectURL(await r.blob())}" download="${esc(name)}" hidden></a>`);
    document.body.append(a); a.click(); a.remove();
  }));
}
export async function importFile(file) {
  if (!file) return false;
  try {
    toast($t("가져오는 중..."));
    const res = await lib("import", { method: "POST", body: { name: file.name, text: await file.text() } });
    await loadLib();
    dropCache("/yt/");
    toast($t("가져왔어요: 구독 {0}개, 재생목록 {1}개, 시청 기록 {2}개", res.subs, res.playlists, res.hist));
    return true;
  } catch (e) { toast(e.message); return false; }
}
export async function importInvidious(loud) {
  try {
    const r = await fetch("/api/v1/auth/subscriptions", { credentials: "same-origin" });
    if (r.status === 403) { if (loud) dialog(`<div class="dh"><b>${$t("Invidious 로그인 필요")}</b><button data-close>${icon("close")}</button></div><p>${$t("Invidious 계정에 로그인되어 있지 않아요. 로그인한 뒤 다시 누르세요.")}</p><a class="pill blue" href="/login?referer=%2Ffeed%2Fchannels">${$t("Invidious 로그인")}</a>`); return; }
    const list = await r.json();
    if (!Array.isArray(list) || !list.length) { if (loud) toast($t("가져올 구독이 없어요")); return; }
    const items = list.map((s) => ({ id: s.authorId, name: s.author, thumb: "" }));
    const res = await lib("subs/import", { method: "POST", body: { list: items } });
    await loadLib();
    dropCache("/yt/feed");
    if (loud || res.added) toast(`${$t("구독 {0}개를 가져왔어요", res.added)}`);
  } catch (e) { if (loud) toast(e.message); }
}

// ---------------------------------------------------------------- history
export async function history(page, m, alive) {
  page.innerHTML = `<div class="histpage"><div class="hist-main" data-ctx="hist"><h2>${$t("시청 기록")}</h2><div class="results"></div></div>
    <aside class="hist-side"><form class="hs">${icon("search")}<input placeholder="${$t("시청 기록 검색")}"></form>
      <button class="side-btn clear">${$t("{0} 모든 시청 기록 지우기", icon("trash"))}</button>
      <button class="side-btn shreset">${$t("{0} Shorts 추천 초기화", icon("shorts"))}</button></aside></div>`;
  const box = $(".results", page);
  let off = 0, q = "";
  const groupName = (at) => {
    const d = new Date(at * 1000), now = new Date();
    const days = Math.floor((new Date(now.toDateString()) - new Date(d.toDateString())) / 86400000);
    return days === 0 ? $t("오늘") : days === 1 ? $t("어제") : days < 7 ? d.toLocaleDateString(LOCALE, { weekday: "long" }) : d.toLocaleDateString(LOCALE, { year: now.getFullYear() === d.getFullYear() ? undefined : "numeric", month: "long", day: "numeric" });
  };
  let lastGroup = "";
  const load = async () => {
    const r = await lib(`hist?n=40&o=${off}&q=${encodeURIComponent(q)}`);
    off += r.items.length;
    const html = r.items.map((h) => {
      const g = groupName(h.at);
      const v = remember([normVideo(h)])[0];
      const head = g !== lastGroup ? `<h3 class="sec">${esc(g)}</h3>` : "";
      lastGroup = g;
      return head + card(v, "row");
    }).join("");
    box.insertAdjacentHTML("beforeend", html);
    fillProgress(box);
    return r.items.length === 40;
  };
  const restart = async () => { off = 0; lastGroup = ""; box.innerHTML = ""; while (pageCleanup.length) pageCleanup.pop()(); $$(".sentinel", page).forEach((s) => s.remove()); if (await load()) infinite(box, load); if (!box.children.length) box.innerHTML = empty("history", q ? $t("검색결과가 없어요") : $t("시청 기록이 없어요")); };
  await restart();
  $(".hs", page).onsubmit = (e) => { e.preventDefault(); q = $(".hs input", page).value.trim(); restart(); };
  $(".clear", page).onclick = async () => {
    if (!confirm($t("모든 기기의 시청 기록을 지울까요? (이어보기 위치도 지워져요)"))) return;
    await lib("hist/del", { method: "POST", body: { all: true } });
    toast($t("시청 기록을 지웠어요"));
    restart();
  };
  $(".shreset", page).onclick = async () => {
    if (!confirm($t("Shorts 추천을 처음부터 다시 배울까요?"))) return;
    await lib("shorts/reset", { method: "POST", body: {} });
    toast($t("Shorts 추천을 초기화했어요"));
  };
}

// ---------------------------------------------------------------- you (library)
export async function you(page, m, alive) {
  await state.libReady;
  const [h] = await Promise.all([lib("hist?n=20", { ttl: 15000 }).catch(() => ({ items: [] }))]);
  if (!alive()) return;
  const L = state.lib;
  const pls = Object.entries(L.playlists).sort((a, b) => (b[1].updated || 0) - (a[1].updated || 0));
  // a row of the grey box: the icon in a circle, the name, a line under it, an arrow
  const row = (ic, title, sub, href) => `<a class="grow" href="${href}" data-nav><span class="gic">${icon(ic)}</span><span class="gtx"><b>${title}</b>${sub ? `<small>${esc(sub)}</small>` : ""}</span>${icon("right")}</a>`;
  const shelf = (title, href, list, kind = "grid") => `<section class="you-sec"><div class="you-h"><h3>${title}</h3>${href ? `<a class="pill ghost" href="${href}" data-nav>${$t("모두 보기")}</a>` : ""}</div>
    <div class="hrow">${list.length ? list.map((v) => card(v, kind)).join("") : `<p class='sub'>${$t("아직 없어요")}</p>`}</div></section>`;
  page.innerHTML = `<div class="youpage">
    <div class="you-top">${avatar("", "xxl")}<div><h1>${$t("나")}</h1><div class="s">${$t("이 집 서버에 저장되어 모든 기기에서 같아요 · 구독 {0} · 재생목록 {1}", Object.keys(L.subs).length, pls.length)}</div></div></div>
    ${shelf($t("기록"), "/feed/history", remember(h.items.map(normVideo)).slice(0, 12))}
    <div class="rgroup">
      ${row("history", $t("기록"), "", "/feed/history")}
      ${row("later", $t("나중에 볼 동영상"), $t("동영상 {0}개{1}{2}", L.wl.length, "", ""), "/playlist?list=WL")}
      ${row("like", $t("좋아요 표시한 동영상"), $t("동영상 {0}개{1}{2}", L.likes.length, "", ""), "/playlist?list=LL")}
      ${row("playlists", $t("재생목록"), $t("재생목록 {0}개", pls.length), "/feed/playlists")}
      ${row("subs", $t("구독 관리"), $t("구독 채널 {0}개", Object.keys(L.subs).length), "/feed/channels")}
      ${row("gear", $t("설정"), "", "/settings")}
    </div>
    <section class="you-sec"><div class="you-h"><h3>${$t("재생목록")}</h3><a class="pill ghost" href="/feed/playlists" data-nav>${$t("모두 보기")}</a></div>
      <div class="hrow">${[["WL", $t("나중에 볼 동영상"), L.wl], ["LL", $t("좋아요 표시한 동영상"), L.likes]].map(([id, t, items]) => playlistCard({ id, title: t, count: items.length, first: items[0]?.id, author: $t("비공개") })).join("")}
      ${pls.map(([id, p]) => playlistCard({ id, title: p.title, count: p.count, first: p.first, author: $t("비공개") })).join("")}</div></section>
    ${shelf($t("나중에 볼 동영상"), "/playlist?list=WL", remember(L.wl.map(normVideo)).slice(0, 12))}
    ${shelf($t("좋아요 표시한 동영상"), "/playlist?list=LL", remember(L.likes.map(normVideo)).slice(0, 12))}
  </div>`;
  fillProgress(page);
}
export async function playlists(page) {
  await state.libReady;
  const L = state.lib;
  const pls = Object.entries(L.playlists).sort((a, b) => (b[1].updated || 0) - (a[1].updated || 0));
  page.innerHTML = `<div class="feedhead"><h2>${$t("재생목록")}</h2><button class="pill ghost newpl">${$t("{0} 새 재생목록", icon("add"))}</button></div><div class="vgrid">${
    [["WL", $t("나중에 볼 동영상"), L.wl], ["LL", $t("좋아요 표시한 동영상"), L.likes]].map(([id, t, items]) => playlistCard({ id, title: t, count: items.length, first: items[0]?.id, author: $t("비공개") })).join("") +
    pls.map(([id, p]) => playlistCard({ id, title: p.title, count: p.count, first: p.first, author: $t("비공개") })).join("")}</div>`;
  $(".newpl", page).onclick = async () => {
    const t = prompt($t("새 재생목록 이름"));
    if (!t || !t.trim()) return;
    const r = await lib("pl/new", { method: "POST", body: { title: t.trim() } });
    state.lib.playlists[r.id] = r.playlist;
    navigate(`/playlist?list=${r.id}`);
  };
}

// ---------------------------------------------------------------- trending (explore)
export async function trending(page, m, alive) {
  const type = new URLSearchParams(location.search).get("type") || "";
  const tabs = [["", $t("지금 뜨는 실시간")], ["gaming", $t("게임")], ["music", $t("음악")], ["news", $t("뉴스")]];
  page.innerHTML = `<div class="trend-head">${icon("trending", "big red")}<h1>${$t("인기 급상승")}</h1></div>
    <div class="ctabs">${tabs.map(([k, l]) => `<a href="/feed/trending${k ? "?type=" + k : ""}" data-nav class="${k === type ? "on" : ""}">${l}</a>`).join("")}</div><div class="results">${spinner}</div>`;
  let list;
  if (type === "music" || type === "news") {
    list = norm(await api(`/api/v1/search?q=${type === "music" ? $t("음악") : $t("뉴스")}&type=video&region=${REGION}&date=week&sort=views`, { ttl: 600000 }));
  } else list = norm(await api(`/api/v1/trending?region=${REGION}${type ? "&type=" + type : ""}`, { ttl: 600000 }));
  if (!alive()) return;
  const box = $(".results", page);
  box.innerHTML = list.map((v, i) => `<div class="rank"><b>${i + 1}</b>${card(v, "row")}</div>`).join("") || empty("info", $t("목록을 불러오지 못했어요"));
  fillProgress(box);
}

export function hashtag(page, m) {
  navigate(`/results?search_query=${encodeURIComponent("#" + decodeURIComponent(m[1]))}`, { replace: true });
}

// ---------------------------------------------------------------- settings
export function settingsPage(page) {
  const S = settings;
  const sel = (k, opts) => `<select data-k="${k}">${opts.map(([v, l]) => `<option value="${v}" ${String(S[k]) === String(v) ? "selected" : ""}>${l}</option>`).join("")}</select>`;
  const tog = (k, label, sub = "") => `<label class="set-row"><span><b>${label}</b>${sub ? `<small>${sub}</small>` : ""}</span><input type="checkbox" class="sw" data-k="${k}" ${S[k] ? "checked" : ""}></label>`;
  const row = (label, ctl, sub = "") => `<div class="set-row"><span><b>${label}</b>${sub ? `<small>${sub}</small>` : ""}</span>${ctl}</div>`;
  page.innerHTML = `<div class="narrow-page settings">
    <h2>${$t("설정")}</h2>
    <h3>${$t("모양")}</h3>
    ${row(langLabel(), sel("lang", [["auto", $t("자동 (브라우저 언어)")], ...LANGS]))}
    ${row($t("테마"), sel("theme", [["system", $t("기기 테마 사용")], ["dark", $t("어두운 테마")], ["light", $t("밝은 테마")]]))}
    ${tog("ambient", $t("앰비언트 모드"), $t("영상 색이 플레이어 뒤로 은은하게 번져요 (어두운 테마)"))}
    ${row($t("TV 모드"), sel("tv", [["auto", $t("자동 (안드로이드 TV 에서 켜짐)")], ["on", $t("켜기")], ["off", $t("끄기")]]), $t("리모컨 방향키로 이동 · 큰 글씨 · 바꾸면 새로고침"))}
    <h3>${$t("재생")}</h3>
    ${tog("calm", $t("차분 모드"), $t("홈은 구독 채널의 새 영상만, 쇼츠·인기·추천 영상은 숨기고, 자동재생은 끄고, 댓글은 눌러야 보여요"))}
    ${tog("autoplay", $t("자동재생"), $t("영상이 끝나면 다음 영상을 재생해요"))}
    ${tog("rememberSpeed", $t("재생 속도 기억"), $t("다음에 와도 고른 재생 속도를 그대로 써요"))}
    ${row($t("선호 화질"), sel("quality", [["auto", $t("최고 화질 (기본)")], ["2160", "4K (2160p)"], ["1440", "1440p"], ["1080", "1080p"], ["720", "720p"], ["480", "480p"], ["360", "360p"]]), $t("화질을 알아서 낮추지 않고 이 화질로 고정해요 (영상에 없으면 그 아래 최고)"))}
    ${tog("dataSaver", $t("데이터 절약"), $t("480p 이하, 미리 받는 양을 줄여요"))}
    ${IPHONE ? tog("nativeIphone", $t("iPhone 기본 플레이어"), $t("Safari 자체 플레이어로 재생해요. 다른 앱 위 PIP 와 AirPlay 가 되고, 화질은 자동으로 정해져요 (다음 영상부터 적용)")) : ""}
    ${tog("resume", $t("이어서 보기"), $t("보던 곳부터 재생 (모든 기기 공유)"))}
    ${tog("miniplayer", $t("미니 플레이어"), $t("다른 페이지로 가도 작은 화면으로 계속 재생"))}
    ${tog("heatmap", $t("가장 많이 다시 본 장면"), $t("재생바 위에 사람들이 많이 다시 본 구간을 물결 모양으로 보여줘요"))}
    ${PUBLIC ? "" : `<h3>${$t("TV로 보내기")}</h3>
    ${tog("castReceive", $t("이 기기에서 받기"), $t("다른 기기가 'TV로 보내기'로 이 화면에 영상을 보낼 수 있어요 (TV 모드면 늘 켜짐)"))}
    ${row($t("이 기기 이름"), `<input data-k="deviceName" maxlength="40" placeholder="${$t("예: 거실 TV")}" value="${esc(S.deviceName || "")}" style="height:36px;padding:0 10px;border:1px solid var(--input-line);background:var(--input);min-width:160px">`, $t("보낼 때 목록에 이렇게 보여요"))}`}
    <h3>${$t("자막")}</h3>
    ${tog("captions", $t("자막 항상 표시"))}
    ${row($t("자막 언어"), sel("capLang", LANGS))}
    ${tog("autoTranslate", $t("자동 번역 자막"), $t("내 언어 자막이 없으면 서버가 번역한 자막을 써요"))}
    ${row($t("자막 크기"), sel("capSize", [[50, "50%"], [75, "75%"], [100, "100%"], [150, "150%"], [200, "200%"]]))}
    <h3>${$t("SponsorBlock · 싫어요 수")}</h3>
    ${tog("sponsor", "SponsorBlock", $t("영상 속 협찬·광고 구간을 건너뛰어요 (sponsor.ajay.app, 영상 ID 앞 4글자만 보내요)"))}
    <div class="sbcats">${Object.entries(SB_CATS).map(([k, [l, c]]) => `<label class="chk"><input type="checkbox" data-cat="${k}" ${S.sponsorCats.includes(k) ? "checked" : ""}><i style="background:${c}"></i>${$t("{0} 자동 건너뛰기", l)}</label>`).join("")}</div>
    ${tog("dislikes", $t("싫어요 수 보기"), $t("Return YouTube Dislike 의 추정치"))}
    ${tog("dearrow", "DeArrow", $t("낚시성 제목과 썸네일을 사람들이 고친 것으로 바꿔요 (dearrow.ajay.app, 영상 ID 앞 4글자만 보내요)"))}
    ${tog("dearrowThumbs", $t("DeArrow 썸네일"), $t("사람들이 고른 장면을 썸네일로 써요 (이 서버를 거쳐 받아요)"))}
    <h3>${$t("내 데이터")}</h3>
    ${PUBLIC ? `<p class="sub pub-note">${$t("공개 서버예요. 구독과 기록은 이 브라우저에만 저장되고 서버에는 남지 않아요. 브라우저 데이터를 지우면 사라지니 가끔 내보내기로 백업해 두세요.")}</p>` : ""}
    <div class="set-btns"><a class="pill ghost" href="/feed/channels" data-nav>${$t("구독 관리 · 가져오기")}</a>
    <button class="pill ghost simp">${$t("{0} 파일에서 가져오기", icon("upload"))}</button><input type="file" class="simpfile" hidden accept=".json,.csv,.opml,.xml,.db,.txt">
    <button class="pill ghost sexp">${$t("{0} 내보내기", icon("download"))}</button><button class="pill ghost unhide">${$t("'관심 없음' {0}개 되돌리기", state.hidden.size)}</button>
    <button class="pill ghost keys">${$t("단축키")}</button></div>
    <h3>${$t("면책 조항")}</h3>
    <p class="sub">${esc($t("TubeHaus는 개인이 만든 무료 오픈소스 소프트웨어이고, 유튜브나 구글과 관계가 없어요. 영상을 저장하거나 배포하지 않고, 어떤 보증도 하지 않아요. 내려받은 영상은 본인이 보는 용도로만 쓰세요. 공유하거나 다시 올리는 건 그렇게 한 사람의 책임이에요. 사는 곳의 법과 연결하는 서비스의 약관에 맞게 써 주세요."))} <a href="https://github.com/biolend83/tubehaus#disclaimer" target="_blank" rel="noopener">${$t("전체 면책 조항 보기")}</a></p>
  </div>`;
  page.addEventListener("change", (e) => {
    const t = e.target;
    if (t.dataset.cat) {
      const s = new Set(S.sponsorCats);
      t.checked ? s.add(t.dataset.cat) : s.delete(t.dataset.cat);
      S.sponsorCats = [...s];
      return;
    }
    const k = t.dataset.k;
    if (!k) return;
    S[k] = t.type === "checkbox" ? t.checked : /^\d+$/.test(t.value) && k !== "quality" ? +t.value : t.value;
    if (k === "rememberSpeed" && S.rememberSpeed) S.speed = +sessionStorage.getItem("yt.speed") || 1;
    if (k === "lang" || k === "calm") return location.reload();     // the whole interface changes
    toast($t("저장됨"));
  });
  $(".unhide", page).onclick = () => { state.hidden.clear(); local.set("hidden", []); toast($t("되돌렸어요")); };
  // signed-in account on an instance that requires one
  api("/api/p/auth/me", { quiet: true }).then((d) => {
    if (!d?.accounts || !d.user) return;
    const row = el(`<div class="set-btns"><span class="sub">${esc($t("{0} 으로 로그인했어요", d.user))}</span><button class="pill ghost logout">${$t("로그아웃")}</button></div>`);
    $(".set-btns", page).after(row);
    $(".logout", row).onclick = async () => {
      await api("/api/p/auth/logout", { method: "POST", body: {} }).catch(() => { });
      location.href = "/login";
    };
  }).catch(() => { });
  $(".simp", page).onclick = () => $(".simpfile", page).click();
  $(".simpfile", page).onchange = (e) => importFile(e.target.files[0]);
  $(".sexp", page).onclick = exportDialog;
  $(".keys", page).onclick = async () => (await import("./core.js")).keysDialog();
}
