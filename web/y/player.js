// YouTube-style player: controls, captions, storyboards, chapters and SponsorBlock drawn on top of Shaka (DASH/HLS).
// Built once and reused (moving between pages continues seamlessly in the mini player).
import { $, $$, el, esc, icon, fmtTime, settings, toast, copy, localUrl, parseT, toGate, SERVER_MAX_H, PUBLIC, IPHONE } from "./util.js";
import { $t, LANG, LANGS } from "./i18n.js";

const isTouch = matchMedia("(pointer: coarse)").matches;
const SPEEDS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const SB_CATS = {
  sponsor: [$t("스폰서"), "#00d400"], selfpromo: [$t("자체 홍보"), "#ffff00"], interaction: [$t("구독·좋아요 요청"), "#cc00ff"],
  intro: [$t("인트로"), "#00ffff"], outro: [$t("엔딩"), "#0202ed"], preview: [$t("미리보기·요약"), "#008fd6"],
  music_offtopic: [$t("음악 아닌 부분"), "#ff9900"], filler: [$t("잡담"), "#7300ff"],
};
export { SB_CATS };

// ---------------------------------------------------------------- captions (WebVTT)
function vttTime(s) {
  const p = s.trim().split(":");
  return p.reduce((a, b) => a * 60 + parseFloat(b.replace(",", ".")), 0);
}
function parseVtt(txt) {
  const cues = [];
  for (const block of txt.replace(/\r/g, "").split(/\n{2,}/)) {
    const lines = block.split("\n");
    const i = lines.findIndex((l) => l.includes("-->"));
    if (i < 0) continue;
    const [a, b] = lines[i].split("-->");
    const s = vttTime(a), e = vttTime(b.trim().split(/\s+/)[0]);
    // tags (<c>, <i>, timestamps) and entities (&amp;) off; DOMParser never runs scripts
    const text = new DOMParser().parseFromString(lines.slice(i + 1).join("\n"), "text/html").body.textContent.trim();
    if (!text || e - s < 0.05) continue;
    cues.push({ s, e, text });
  }
  // Auto-generated captions repeat the previous line in the next cue (two rolling lines). Show overlapping lines once.
  for (let k = 1; k < cues.length; k++) {
    const prev = cues[k - 1].text.split("\n"), cur = cues[k].text.split("\n");
    if (cur.length > 1 && cur[0] === prev[prev.length - 1] && cues[k].s - cues[k - 1].e < 0.1) {
      cues[k].roll = true;
    }
  }
  return cues.sort((x, y) => x.s - y.s);
}

// ---------------------------------------------------------------- chapters
export function parseChapters(desc, dur) {
  const out = [];
  for (const line of (desc || "").split("\n")) {
    const m = line.match(/^\s*[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?\s*[-–—:|·.]?\s*(.+?)\s*$/) ||
      line.match(/^\s*(.+?)\s*[-–—:|·]?\s*[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?\s*$/);
    if (!m) continue;
    const ts = /\d:\d\d$/.test(m[1]) ? m[1] : m[2];
    const title = ts === m[1] ? m[2] : m[1];
    out.push({ start: parseT(ts), title: title.replace(/^[-–—:|·\s]+|[-–—:|·\s]+$/g, "") });
  }
  if (out.length < 3 || out[0].start !== 0) return [];
  for (let i = 1; i < out.length; i++) if (out[i].start <= out[i - 1].start) return [];
  out.forEach((c, i) => (c.end = i + 1 < out.length ? out[i + 1].start : dur));
  return out.filter((c) => c.start < dur);
}

// ---------------------------------------------------------------- SponsorBlock (only the first 4 hash characters are sent)
async function sponsorSegments(id) {
  if (!settings.sponsor || !crypto.subtle) return [];
  try {
    const h = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(id)))]
      .map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 4);
    // 'filler' is so frequent that it is fetched only when chosen for auto-skip
    const cats = encodeURIComponent(JSON.stringify(Object.keys(SB_CATS).filter((c) => c !== "filler" || settings.sponsorCats.includes(c))));
    const r = await fetch(`https://sponsor.ajay.app/api/skipSegments/${h}?categories=${cats}`, { referrerPolicy: "no-referrer" });
    if (!r.ok) return [];
    const hit = (await r.json()).find((x) => x.videoID === id);
    return (hit ? hit.segments : []).filter((s) => s.actionType === "skip" || !s.actionType)
      .map((s) => ({ start: s.segment[0], end: s.segment[1], cat: s.category }));
  } catch { return []; }
}

// The playback engine (785 KB) is loaded once, when a video is first opened. On the first screen a weak TV would freeze for seconds.
let shakaP = null;
function loadShaka() {
  if (window.shaka) return Promise.resolve();
  return shakaP || (shakaP = new Promise((ok, fail) => {
    const s = document.createElement("script");
    s.src = "/y/lib/shaka-player.compiled.js";
    s.async = true;
    s.onload = () => ok();
    s.onerror = () => { shakaP = null; fail(new Error($t("재생 엔진을 불러오지 못했어요"))); };
    document.head.append(s);
  }));
}
// prefetch it in idle time after the home page is up (so a click starts at once)
// (weak devices like TVs do not prefetch right away: the page freezes while it loads)
export const TV_DEVICE = /TubeHausTV|Android TV|GoogleTV|Google TV|AFT[A-Z]|BRAVIA|SMART-TV|SmartTV|HbbTV|Tizen|Web0S|webOS|TV Bro|Puffin.*TV|CrKey|AndroidTV/i.test(navigator.userAgent);
// (TVs wait 6 s after the home page, so the first click does not wait either)
setTimeout(() => (window.requestIdleCallback || ((f) => f()))(() => loadShaka().catch(() => { }), { timeout: 8000 }), TV_DEVICE ? 6000 : 0);

// ---------------------------------------------------------------- player
export class Player {
  constructor() {
    this.on = {};            // ended, next, prev, mini, theater, progress, error, title
    this.info = null;
    this.chapters = [];
    this.segments = [];      // sponsorblock
    this.cues = null;
    this.capTrack = null;    // {label, url} | null
    this.lockH = null;
    this.live = false;
    this.next = null;
    this.hasPrev = false;
    this.sleepAt = 0;        // ms epoch, or -1 = at the end of this video
    this.mode = "default";   // default | theater | mini
    this.statsOn = false;
    this.skipped = new Set();
    this.badCodec = new Set();   // codecs that dropped frames on this device
    this.smooth = new Map();     // track id -> score for decoding smoothly
    this.build();
  }

  build() {
    const r = (this.root = el(`
<div class="ytp paused ctl" tabindex="-1">
  <video playsinline preload="auto"></video>
  <div class="ytp-poster"></div>
  <div class="ytp-scrubimg"></div>
  <div class="ytp-caption"></div>
  <div class="ytp-spin"><i></i></div>
  <div class="ytp-bezel"></div>
  <div class="ytp-seekfx l"><b></b><span></span></div><div class="ytp-seekfx r"><b></b><span></span></div>
  <div class="ytp-flash"></div>
  <button class="ytp-skipbtn"></button>
  <button class="ytp-unmute">${icon("mute")}<span>${$t("소리 켜기")}</span></button>
  <div class="ytp-top"><div class="ytp-title"></div></div>
  <div class="ytp-center">
    <button class="c-prev">${icon("prev")}</button><button class="c-play">${icon("play")}</button><button class="c-next">${icon("next")}</button>
  </div>
  <div class="ytp-end"></div>
  <div class="ytp-err"></div>
  <div class="ytp-stats"></div>
  <div class="ytp-bottom">
    <div class="ytp-prog">
      <div class="ytp-segs"></div><div class="ytp-sbmarks"></div><div class="ytp-scrub"></div>
      <div class="ytp-tip"><div class="ytp-tipimg"></div><div class="ytp-tipchap"></div><div class="ytp-tiptime"></div></div>
    </div>
    <div class="ytp-bar">
      <div class="ytp-l">
        <button class="b-prev" title="${$t("이전 (Shift+P)")}">${icon("prev")}</button>
        <button class="b-play" title="${$t("재생 (k)")}">${icon("play")}</button>
        <button class="b-next" title="${$t("다음 (Shift+N)")}">${icon("next")}</button>
        <div class="ytp-vol"><button class="b-mute" title="${$t("음소거 (m)")}">${icon("vol")}</button><div class="ytp-volbar"><input type="range" min="0" max="1" step="0.01"></div></div>
        <div class="ytp-time"><span class="t-cur">0:00</span><span class="t-sep"> / </span><span class="t-dur">0:00</span></div>
        <button class="ytp-livebadge" title="${$t("실시간으로 이동")}"><i></i>${$t("실시간")}</button>
        <button class="ytp-chap"></button>
      </div>
      <div class="ytp-r">
        <button class="b-auto" title="${$t("자동재생")}"><span class="sw"><i></i></span></button>
        <button class="b-cc" title="${$t("자막 (c)")}">${icon("cc")}</button>
        <button class="b-set" title="${$t("설정")}">${icon("gear")}</button>
        <button class="b-pip" title="PIP">${icon("pip")}</button>
        <button class="b-mini" title="${$t("미니 플레이어 (i)")}">${icon("mini")}</button>
        <button class="b-theater" title="${$t("영화관 모드 (t)")}">${icon("theater")}</button>
        <button class="b-fs" title="${$t("전체 화면 (f)")}">${icon("fs")}</button>
      </div>
    </div>
  </div>
  <div class="ytp-menu"></div>
  <div class="ytp-ctx"></div>
</div>`));
    this.video = $("video", r);
    const v = this.video;
    v.volume = settings.volume >= 0.05 ? settings.volume : 1;
    v.muted = false;
    this.autoMuted = false;
    this.q = (s) => $(s, r);
    if (!document.pictureInPictureEnabled && !IPHONE) this.q(".b-pip").remove();
    this.bindVideo();
    this.bindControls();
    this.bindProgress();
    this.loop();
  }

  async init() {
    if (this.sp) return;
    await loadShaka();
    shaka.polyfill.installAll();
    this.sp = new shaka.Player();
    await this.sp.attach(this.video);
    this.sp.configure({
      streaming: {
        bufferingGoal: settings.dataSaver ? 30 : 120,
        rebufferingGoal: 1,
        segmentPrefetchLimit: 6,
        maxDisabledTime: 0,
        bufferBehind: 30,
        retryParameters: { maxAttempts: 10, baseDelay: 200, backoffFactor: 1.4, fuzzFactor: 0.3, timeout: 30000, stallTimeout: 10000, connectionTimeout: 10000 },
      },
      manifest: {
        retryParameters: { maxAttempts: 5, baseDelay: 500, backoffFactor: 2, timeout: 20000 },
        hls: { liveSegmentsDelay: 20 },
      },
      // Never lower the quality on its own. Assume a huge bandwidth so the very first pick is the best quality
      abr: { enabled: false, defaultBandwidthEstimate: 1e9 },
    });
    // prefer the codec this device decodes smoothly in hardware (AV1 / VP9 / H.264)
    try { this.sp.configure("preferredDecodingAttributes", ["smooth", "powerEfficient"]); } catch { }
    this.sp.addEventListener("error", (e) => this.onError(e.detail));
    for (const n of ["adaptation", "variantchanged", "trackschanged"]) this.sp.addEventListener(n, () => { this.guard(); this.syncQualityLabel(); });
    this.sp.addEventListener("buffering", (e) => this.setBuffering(e.buffering));
  }

  // ------------------------------------------------------------ load
  async load(info, { start = 0, autoplay = true } = {}) {
    // iPhone: Safari's own player (HLS) instead of Shaka. Shaka runs on ManagedMediaSource there, which starts with a
    // black picture, has no Picture-in-Picture over other apps and no AirPlay
    let native = this.native = IPHONE && settings.nativeIphone && !(info.liveNow && !info.hlsUrl);
    if (!native) await this.init();
    this.info = info;
    this.live = !!(info.liveNow && info.hlsUrl);
    this.lockH = null;
    this.retried = 0;
    this.skipped = new Set();
    this.segments = [];
    this.cues = null;
    this.capTrack = null;
    this.ended = false;
    this.hideEnd();
    this.hideErr();
    this.closeMenu();
    const r = this.root;
    r.classList.toggle("live", this.live);
    r.classList.toggle("native", native);
    r.classList.remove("ended");
    this.q(".ytp-title").textContent = info.title;
    this.q(".ytp-poster").style.backgroundImage = `url(/vi/${info.videoId}/maxres.jpg), url(/vi/${info.videoId}/hqdefault.jpg)`;
    r.classList.add("poster");
    this.chapters = this.live ? [] : parseChapters(info.description, info.lengthSeconds);
    this.sb = this.pickStoryboard(info.storyboards);
    this.drawSegs();
    r.classList.add("buffering");
    if (native && !(await this.loadNative(info, start))) {
      if (this.info !== info) return;
      // the server could not make HLS for this video: Shaka plays it
      native = this.native = false;
      r.classList.remove("native", "buffering");
      this.hideErr();
      await this.init();
      r.classList.add("buffering");
    }
    if (!native) {
      this.releaseNative();
      this.sp.configure("streaming.bufferingGoal", TV_DEVICE ? 20 : settings.dataSaver ? 30 : this.live ? 30 : 120);
      // the cap applies from the first segment (so a TV does not start with 4K/8K and switch late)
      this.sp.configure("abr.restrictions.maxHeight", this.live ? Infinity : this.capHeight());
      if (TV_DEVICE) this.sp.configure({ streaming: { segmentPrefetchLimit: 2, bufferBehind: 10 } });
      const src = this.live ? info.hlsUrl.replace(/^https?:\/\/[^/]+/, "") : `/api/manifest/dash/id/${info.videoId}?local=true`;
      try {
        await this.sp.load(src, this.live ? null : start || 0);
      } catch (e) {
        if (e && e.code === 7000) return;   // interrupted by switching to another video
        this.onError(e);
        return;
      }
    }
    this.video.playbackRate = settings.rememberSpeed ? (+settings.speed || 1) : (+sessionStorage.getItem("yt.speed") || 1);
    if (!native) {
      await this.rateTracks();
      this.applyDefaultQuality();
    }
    this.dropWatch();
    this.setupCaptions();
    this.syncQualityLabel();
    this.mediaSession();
    sponsorSegments(info.videoId).then((s) => { if (this.info === info) { this.segments = s; this.drawSegs(); } });
    if (autoplay) this.play();
  }

  // native path (see load): the browser's own player. VOD comes as the HLS the server builds from the MP4 streams
  // (/api/p/hls), live as the server's live HLS. Resolves false if superseded or if the stream would not start
  async loadNative(info, start) {
    const v = this.video;
    if (this.sp) await this.sp.unload().catch(() => { });   // let go of Shaka's MediaSource
    if (this.info !== info) return false;
    v.disableRemotePlayback = false;
    this.nativeSrc = true;
    v.src = this.live ? info.hlsUrl.replace(/^https?:\/\/[^/]+/, "") : `/api/p/hls/${info.videoId}.m3u8`;
    try {
      await new Promise((ok, no) => {
        const ac = new AbortController();
        v.addEventListener("loadedmetadata", () => { ac.abort(); ok(); }, { signal: ac.signal });
        v.addEventListener("error", () => { ac.abort(); no(v.error); }, { signal: ac.signal });
      });
    } catch {
      return false;
    }
    if (this.info !== info) return false;
    if (start && !this.live) v.currentTime = start;
    return true;
  }
  releaseNative() {
    if (!this.nativeSrc) return;
    this.nativeSrc = false;
    this.video.removeAttribute("src");
    this.video.load();
  }

  play() {
    const p = this.video.play();
    if (p) p.catch(() => {
      // if autoplay with sound is blocked, start muted and say so
      // (this mute is not saved; one tap on the video lifts it)
      if (!this.video.muted) {
        this.autoMuted = true;
        this.video.muted = true;
        this.video.play().then(() => this.root.classList.add("automuted")).catch(() => { this.autoMuted = false; this.video.muted = false; });
      }
    });
  }
  toggle() {
    if (this.ended) return this.replay();
    if (this.video.paused) this.play(); else this.video.pause();
    this.bezel(this.video.paused ? "pause" : "play");
  }
  unmute() {
    this.autoMuted = false;
    this.root.classList.remove("automuted");
    this.video.muted = false;
    if (this.video.volume < 0.05) this.video.volume = 1;
    this.bezel("vol");
  }
  replay() { this.hideEnd(); this.video.currentTime = 0; this.play(); }
  seek(t, rel = false) {
    const v = this.video;
    const [lo, hi] = this.range();
    let x = rel ? v.currentTime + t : t;
    x = Math.max(lo, Math.min(hi - (this.live ? 0 : 0.2), x));
    v.currentTime = x;
    if (this.ended && x < hi - 1) { this.hideEnd(); }
    this.showCtl();
  }
  range() {
    if (this.live && this.sp) { const s = this.sp.seekRange(); return [s.start, s.end]; }
    if (this.live && this.native) { const s = this.video.seekable; return s.length ? [s.start(0), s.end(s.length - 1)] : [0, 0]; }
    return [0, this.video.duration || this.info?.lengthSeconds || 0];
  }
  stop() {
    this.video.pause();
    if (this.sp) this.sp.unload().catch(() => { });
    this.releaseNative();
    this.info = null;
  }

  // ------------------------------------------------------------ quality
  tracks() {
    if (!this.sp) return [];
    const all = this.sp.getVariantTracks();
    const a = all.find((t) => t.active);
    // only tracks in the language of the current audio
    return all.filter((t) => !a || !a.language || t.language === a.language);
  }
  heights() {
    const m = new Map();
    for (const t of this.tracks()) if (t.height) {
      const k = t.height;
      if (!m.has(k) || (t.frameRate || 0) > (m.get(k).frameRate || 0)) m.set(k, t);
    }
    // above the operator's cap (HT_MAX_HEIGHT) isn't offered at all
    return [...m.values()].filter((t) => t.height <= SERVER_MAX_H).sort((a, b) => b.height - a.height);
  }
  pickHeight(h) {
    // same height: 60 fps first -> a codec this device plays smoothly -> codecs that dropped frames last -> higher bitrate
    const fps = (t) => Math.round(t.frameRate || 0);
    const ts = this.tracks().filter((t) => t.height === h).sort((a, b) =>
      fps(b) - fps(a) || (this.badCodec.has(a.videoCodec) - this.badCodec.has(b.videoCodec)) ||
      (this.smooth.get(b.id) || 0) - (this.smooth.get(a.id) || 0) || b.bandwidth - a.bandwidth);
    return ts[0];
  }
  async rateTracks() {
    // ask MediaCapabilities per track whether it decodes 'smooth' and 'powerEfficient' (in hardware)
    this.smooth = new Map();
    if (!navigator.mediaCapabilities) return;
    await Promise.all(this.tracks().filter((t) => t.videoCodec && t.height).map(async (t) => {
      try {
        const r = await navigator.mediaCapabilities.decodingInfo({ type: "media-source", video: {
          contentType: `${t.videoMimeType || (/^(vp|av01)/.test(t.videoCodec) && !/av01/.test(t.videoCodec) ? "video/webm" : "video/mp4")}; codecs="${t.videoCodec}"`,
          width: t.width || 1920, height: t.height, bitrate: t.videoBandwidth || t.bandwidth || 5e6, framerate: t.frameRate || 30 } });
        this.smooth.set(t.id, (r.supported ? 1 : -5) + (r.smooth ? 2 : 0) + (r.powerEfficient ? 1 : 0));
      } catch { }
    }));
  }
  setQuality(h) {
    // choosing a quality above the cap in the menu lifts the cap
    const lim = this.sp.getConfiguration().abr.restrictions.maxHeight;
    if (h > lim) this.sp.configure("abr.restrictions.maxHeight", SERVER_MAX_H);   // lift the user cap, never the server's
    const t = this.pickHeight(h);
    if (!t) return;
    this.lockH = h;
    this.sp.configure({ abr: { enabled: false } });
    this.fitBuffer(t);
    const a = this.curTrack();
    if (!a || a.id !== t.id) this.sp.selectVariantTrack(t, true, 4);
    this.syncQualityLabel();
  }
  fitBuffer(t) {
    // The browser's media buffer (MSE) is capped at about 150 MB. Buffering 120 s of 8K (~58 Mbps) overflows it
    // and it stutters, appending and evicting over and over. So size the buffer from the chosen track's bitrate.
    if (this.live || !t) return;
    const secs = (120e6 * 8) / (t.bandwidth || 5e6);
    // TVs only: 20 s ahead, 10 s behind (YouTube buffers about 15-20 s too; weak devices get busy appending)
    const goal = Math.max(8, Math.min(TV_DEVICE ? 20 : settings.dataSaver ? 30 : 120, Math.floor(secs * 0.8)));
    const behind = Math.max(3, Math.min(TV_DEVICE ? 10 : 30, Math.floor(secs * 0.2)));
    this.sp.configure({ streaming: { bufferingGoal: goal, bufferBehind: behind } });
  }
  capHeight() {
    return Math.min(SERVER_MAX_H, this.userCap());
  }
  userCap() {
    // Default: always the best quality. A cap chosen in settings limits it; data saver means 480p or less.
    // TVs: up to the screen resolution (more is invisible and only makes the start slower)
    if (settings.dataSaver) return 480;
    if (settings.quality !== "auto" && settings.quality !== "max") return +settings.quality;
    // TVs default to 720p (starts fast). Choosing higher in the menu plays that video at that quality
    if (TV_DEVICE) return 720;
    return Infinity;
  }
  applyDefaultQuality() {
    // if the video has 60 fps, 60 fps comes first
    let cap = this.capHeight();
    const hs = this.heights().filter((t) => t.height <= cap);
    if (!hs.length) { const all = this.heights(); if (all.length) this.setQuality(all[all.length - 1].height); return; }
    const top = Math.max(...hs.map((t) => Math.round(t.frameRate || 0)));
    const h = hs.find((t) => Math.round(t.frameRate || 0) >= top - 1).height;
    this.setQuality(h);
  }
  dropWatch() {
    // When many frames drop (the device struggles with this codec) switch to another codec at the same height and frame rate. The quality stays.
    clearInterval(this.dropT);
    let last = null, bad = 0;
    this.dropT = setInterval(() => {
      const v = this.video, q = v.getVideoPlaybackQuality?.();
      if (!q || v.paused || !this.info || this.lockH == null) { last = q; return; }
      if (last) {
        const tot = q.totalVideoFrames - last.totalVideoFrames, drop = q.droppedVideoFrames - last.droppedVideoFrames;
        bad = tot > 30 && drop / tot > 0.08 ? bad + 1 : 0;
        if (bad >= 2) {
          bad = 0;
          const cur = this.curTrack();
          const alt = this.tracks().find((t) => t.height === cur.height && Math.round(t.frameRate || 0) === Math.round(cur.frameRate || 0) && t.videoCodec !== cur.videoCodec && !this.badCodec.has(t.videoCodec));
          if (alt) {
            this.badCodec.add(cur.videoCodec);
            this.sp.selectVariantTrack(alt, true, 2);
            toast(`${$t("프레임이 빠져서 더 부드러운 코덱({0})으로 바꿨어요", alt.videoCodec.split(".")[0])}`);
          }
          // never lower the resolution automatically - only switch to another codec at the same resolution
        }
      }
      last = q;
    }, 4000);
  }
  guard() {
    // if the chosen quality changes (after an error etc.) return to another track of the same height
    if (this.lockH == null || !this.sp) return;
    const a = this.sp.getVariantTracks().find((t) => t.active);
    if (a && a.height !== this.lockH) {
      const t = this.pickHeight(this.lockH);
      if (t) this.sp.selectVariantTrack(t, false);
    }
  }
  curTrack() { return this.sp && this.sp.getVariantTracks().find((t) => t.active); }
  qLabel(t) {
    if (!t) return "";
    const fps = t.frameRate > 30 ? Math.round(t.frameRate) : "";
    return `${t.height}p${fps}`;
  }
  syncQualityLabel() {
    const t = this.curTrack();
    const b = this.q(".b-set");
    b.dataset.hd = !t ? "" : t.height >= 4320 ? "8K" : t.height >= 2160 ? "4K" : t.height >= 720 ? "HD" : "";
  }

  // ------------------------------------------------------------ captions
  captionList() {
    // YouTube gives no captions for live streams -> our server transcribes the audio (whisper)
    if (this.live && this.info) return this.native ? [] : [{ label: $t("자동 받아쓰기 (실시간)"), lang: "auto", url: `live:${this.info.videoId}`, liveCc: true }];
    const list = (this.info?.captions || []).map((c) => ({ label: c.label, lang: c.language_code, url: c.url.replace(/^https?:\/\/[^/]+/, "") }));
    if (this.info && !this.live && !list.some((c) => c.lang.startsWith(LANG) && !/auto/i.test(c.label)) && list.length) {
      const name = (LANGS.find(([c]) => c === LANG) || [, LANG])[1];
      list.push({ label: $t("{0} (자동 번역)", name), lang: LANG, url: `/api/p/sub/${this.info.videoId}.vtt?tl=${LANG}`, tr: true });
    }
    return list;
  }
  setupCaptions() {
    const list = this.captionList();
    this.q(".b-cc").style.display = list.length ? "" : "none";
    if (!settings.captions || !list.length) return this.setCaption(null);
    const want = settings.capLang;
    const pick = list.find((c) => c.lang.startsWith(want) && !/auto/i.test(c.label)) ||
      list.find((c) => c.lang.startsWith(want) && !c.tr) ||
      (settings.autoTranslate && want === LANG && list.find((c) => c.tr)) ||
      null;
    this.setCaption(pick);
  }
  async setCaption(c) {
    this.capTrack = c;
    this.cues = null;
    this.q(".b-cc").classList.toggle("on", !!c);
    this.renderCaption(true);
    clearInterval(this.liveCcT);
    this.liveCcT = null;
    this.liveCcSq = new Map();
    if (!c) return;
    if (c.liveCc) {
      this.cues = [];
      this.liveCcT = setInterval(() => this.liveCcPoll(c), 3000);
      this.liveCcPoll(c);
      return;
    }
    const info = this.info;
    try {
      const r = await fetch(c.url, { credentials: "same-origin" });
      if (!r.ok) throw new Error(r.status);
      const cues = parseVtt(await r.text());
      if (this.info === info && this.capTrack === c) { this.cues = cues; this.renderCaption(true); }
    } catch {
      if (this.capTrack === c) toast($t("자막을 불러오지 못했어요"));
    }
  }
  // segments of the current manifest: playback time <-> YouTube segment number (sq). The segment URL contains /sq/<number>/.
  liveRefs() {
    try {
      const m = this.sp.getManifest();
      const tr = this.curTrack();
      const v = m.variants.find((x) => tr && x.id === tr.id) || m.variants[0];
      const idx = (v.video || v.audio)?.segmentIndex;
      const out = [];
      if (!idx) return out;
      for (const ref of idx) {
        if (!ref) continue;
        const b = ref.getUris()[0].split("/").pop().replace(/\.ts.*$/, "");
        const sq = /\/sq\/(\d+)\//.exec(atob(b.replace(/-/g, "+").replace(/_/g, "/")))?.[1];
        if (sq) out.push({ sq: +sq, s: ref.getStartTime(), e: ref.getEndTime() });
      }
      return out;
    } catch { return []; }
  }
  async liveCcPoll(c) {
    if (this.capTrack !== c || !this.live) { clearInterval(this.liveCcT); this.liveCcT = null; return; }
    if (this.liveCcBusy) return;
    const refs = this.liveRefs();
    const t = this.video.currentTime;
    const cur = refs.find((r) => r.s <= t && t < r.e) || refs.find((r) => r.s > t);
    if (!cur) return;
    this.liveCcBusy = true;
    try {
      const r = await fetch(`/api/p/live/${this.info.videoId}/cc.json?from=${cur.sq}&to=${cur.sq + 12}`, { credentials: "same-origin" });
      const j = await r.json();
      if (this.capTrack !== c) return;
      const bySq = new Map(refs.map((x) => [x.sq, x]));
      for (const [k, list] of Object.entries(j.cues || {})) {
        const ref = bySq.get(+k);
        if (ref) this.liveCcSq.set(+k, list.map(([a, b, text]) => ({ s: ref.s + a, e: ref.s + Math.min(b, a + 8), text })));
      }
      for (const k of [...this.liveCcSq.keys()]) if (k < cur.sq - 30) this.liveCcSq.delete(k);
      // one line at a time: a line ends when the next one starts, and repeated sentences are dropped
      const all = [...this.liveCcSq.values()].flat().sort((x, y) => x.s - y.s);
      const out = [];
      for (const c of all) {
        const prev = out[out.length - 1];
        if (out.slice(-3).some((p) => p.text === c.text)) continue;
        if (prev && prev.e > c.s) prev.e = c.s;
        out.push({ ...c });
      }
      this.cues = out;
    } catch { } finally { this.liveCcBusy = false; }
  }
  toggleCaptions() {
    if (this.capTrack) { this.setCaption(null); settings.captions = false; this.flash($t("자막 꺼짐")); }
    else {
      settings.captions = true;
      this.setupCaptions();
      if (!this.capTrack) { const l = this.captionList(); if (l.length) this.setCaption(l[0]); }
      if (this.capTrack) this.flash(this.capTrack.label);
    }
  }
  renderCaption(force) {
    const box = this.q(".ytp-caption");
    if (!this.cues) { if (force || box.innerHTML) box.innerHTML = ""; return; }
    const t = this.video.currentTime;
    const act = [];
    for (const c of this.cues) { if (c.s > t) break; if (c.e > t) act.push(c); }
    const key = act.map((c) => c.s).join(",");
    if (!force && key === this.capKey) return;
    this.capKey = key;
    let lines = [];
    for (const c of act) for (const l of c.text.split("\n")) if (lines[lines.length - 1] !== l) lines.push(l);
    if (lines.length > 3) lines = lines.slice(-3);
    box.style.setProperty("--cs", settings.capSize / 100);
    box.style.setProperty("--cb", settings.capBg / 100);
    box.innerHTML = lines.map((l) => `<span>${esc(l)}</span>`).join("<br>");
  }

  // ------------------------------------------------------------ storyboards (seek previews)
  pickStoryboard(list) {
    if (!list || !list.length) return null;
    const s = [...list].filter((x) => x.templateUrl).sort((a, b) => b.width - a.width)[0];
    if (!s) return null;
    return { ...s, tpl: s.templateUrl.replace(/^https?:\/\/i\.ytimg\.com\/sb\//, "/sb/i/") };
  }
  sbFrame(t) {
    const s = this.sb;
    if (!s) return null;
    const per = s.storyboardWidth * s.storyboardHeight;
    let i = Math.min(s.count - 1, Math.max(0, Math.floor((t * 1000) / (s.interval || 1))));
    if (!s.interval) i = Math.min(s.count - 1, Math.floor((t / (this.range()[1] || 1)) * s.count));
    const sheet = Math.floor(i / per), p = i % per;
    return {
      url: s.tpl.replace("$M", sheet), w: s.width, h: s.height,
      x: (p % s.storyboardWidth) * s.width, y: Math.floor(p / s.storyboardWidth) * s.height,
      cols: s.storyboardWidth, rows: s.storyboardHeight,
    };
  }

  // ------------------------------------------------------------ progress bar
  drawSegs() {
    const [, dur0] = this.range();
    const dur = dur0 || this.info?.lengthSeconds || 1;
    const segs = this.chapters.length ? this.chapters : [{ start: 0, end: dur }];
    this.q(".ytp-segs").innerHTML = segs.map((c) =>
      `<div class="seg" style="flex:${Math.max(0.001, (c.end - c.start) / dur)}"><i class="ld"></i><i class="hv"></i><i class="pl"></i></div>`).join("");
    this.segEls = $$(".seg", this.q(".ytp-segs"));
    this.q(".ytp-sbmarks").innerHTML = this.segments.map((s) =>
      `<i style="left:${(s.start / dur) * 100}%;width:${((s.end - s.start) / dur) * 100}%;background:${SB_CATS[s.cat]?.[1] || "#0f0"}"></i>`).join("");
    this.q(".ytp-prog").classList.toggle("chaptered", segs.length > 1);
  }
  paintProgress(hoverT) {
    const v = this.video;
    const [lo, hi] = this.range();
    const span = hi - lo || 1;
    const cur = this.scrubT ?? v.currentTime;
    let buf = cur;
    for (let i = 0; i < v.buffered.length; i++) if (v.buffered.start(i) <= cur + 0.5 && v.buffered.end(i) > buf) buf = v.buffered.end(i);
    const segs = this.chapters.length ? this.chapters : [{ start: lo, end: hi }];
    const f = (t, s) => Math.max(0, Math.min(1, (t - s.start) / ((s.end - s.start) || 1)));
    segs.forEach((s, i) => {
      const e = this.segEls?.[i];
      if (!e) return;
      e.children[0].style.transform = `scaleX(${f(buf, s)})`;
      e.children[1].style.transform = `scaleX(${hoverT != null ? f(hoverT, s) : 0})`;
      e.children[2].style.transform = `scaleX(${f(cur, s)})`;
    });
    this.q(".ytp-scrub").style.left = ((cur - lo) / span) * 100 + "%";
  }
  bindProgress() {
    const pg = this.q(".ytp-prog");
    const tip = this.q(".ytp-tip");
    const tAt = (x) => {
      const b = pg.getBoundingClientRect();
      const [lo, hi] = this.range();
      return lo + Math.max(0, Math.min(1, (x - b.left) / b.width)) * (hi - lo);
    };
    const showTip = (x) => {
      const t = tAt(x);
      this.hoverT = t;
      const b = pg.getBoundingClientRect();
      const fr = this.live ? null : this.sbFrame(t);
      const img = this.q(".ytp-tipimg");
      if (fr) {
        const scale = 160 / fr.w;
        img.style.cssText = `display:block;width:${fr.w * scale}px;height:${fr.h * scale}px;background-image:url("${fr.url}");` +
          `background-size:${fr.cols * fr.w * scale}px ${fr.rows * fr.h * scale}px;background-position:-${fr.x * scale}px -${fr.y * scale}px`;
        // while dragging, show that frame large over the whole video (the approach of pncsoares/youtube-video-player-clone, MIT)
        if (this.scrubbing) {
          const big = this.q(".ytp-scrubimg"), W = this.root.clientWidth, H = this.root.clientHeight;
          const s2 = Math.min(W / fr.w, H / fr.h), bw = fr.w * s2, bh = fr.h * s2;
          big.style.cssText = `display:block;left:${(W - bw) / 2}px;top:${(H - bh) / 2}px;width:${bw}px;height:${bh}px;background-image:url("${fr.url}");` +
            `background-size:${fr.cols * bw}px ${fr.rows * bh}px;background-position:-${fr.x * s2}px -${fr.y * s2}px`;
        }
      } else img.style.display = "none";
      const ch = this.chapters.find((c) => t >= c.start && t < c.end);
      this.q(".ytp-tipchap").textContent = ch ? ch.title : "";
      this.q(".ytp-tiptime").textContent = this.live ? "-" + fmtTime(this.range()[1] - t) : fmtTime(t);
      tip.classList.add("show");
      const w = tip.offsetWidth, x0 = Math.max(4, Math.min(b.width - w - 4, x - b.left - w / 2));
      tip.style.left = x0 + "px";
    };
    pg.addEventListener("pointermove", (e) => { if (e.pointerType === "mouse" || this.scrubbing) showTip(e.clientX); });
    pg.addEventListener("pointerleave", () => { if (!this.scrubbing) { tip.classList.remove("show"); this.hoverT = null; } });
    pg.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.scrubbing = true;
      this.root.classList.add("scrubbing");
      pg.setPointerCapture(e.pointerId);
      this.scrubT = tAt(e.clientX);
      showTip(e.clientX);
      const move = (ev) => { this.scrubT = tAt(ev.clientX); showTip(ev.clientX); };
      const up = (ev) => {
        pg.removeEventListener("pointermove", move);
        pg.removeEventListener("pointerup", up);
        pg.removeEventListener("pointercancel", up);
        this.scrubbing = false;
        this.root.classList.remove("scrubbing");
        this.q(".ytp-scrubimg").style.display = "none";
        const t = this.scrubT;
        this.scrubT = null;
        tip.classList.remove("show");
        this.hoverT = null;
        if (ev.type === "pointerup") this.seek(t);
      };
      pg.addEventListener("pointermove", move);
      pg.addEventListener("pointerup", up);
      pg.addEventListener("pointercancel", up);
    });
  }

  // ------------------------------------------------------------ video events
  bindVideo() {
    const v = this.video, r = this.root;
    v.addEventListener("play", () => { r.classList.remove("paused"); this.setIcons(); this.autoHide(); });
    v.addEventListener("pause", () => { r.classList.add("paused"); this.setIcons(); this.showCtl(true); this.on.progress?.(true); });
    v.addEventListener("playing", () => {
      this.setBuffering(false); r.classList.remove("poster"); this.hideErr();
      // public instances count how many videos are played (just the number, see stat() on the server)
      if (PUBLIC && this.info && this.counted !== this.info) { this.counted = this.info; navigator.sendBeacon?.("/api/p/stat/play"); }
    });
    v.addEventListener("waiting", () => this.setBuffering(true));
    v.addEventListener("loadeddata", () => r.classList.remove("poster"));
    v.addEventListener("durationchange", () => this.drawSegs());
    v.addEventListener("volumechange", () => {
      if (!v.muted && v.volume > 0) settings.volume = v.volume;
      if (!v.muted && this.autoMuted) { this.autoMuted = false; this.root.classList.remove("automuted"); }
      this.setIcons();
    });
    v.addEventListener("ratechange", () => {
      sessionStorage.setItem("yt.speed", v.playbackRate);
      if (settings.rememberSpeed) settings.speed = v.playbackRate;
    });
    v.addEventListener("ended", () => {
      if (v.loop) return;
      this.ended = true;
      r.classList.add("ended");
      this.setIcons();
      this.on.progress?.(true);
      if (this.sleepAt === -1) { this.sleepAt = 0; toast($t("수면 타이머: 재생을 멈췄어요")); return; }
      this.on.ended?.();
    });
    v.addEventListener("enterpictureinpicture", () => r.classList.add("pip"));
    v.addEventListener("leavepictureinpicture", () => r.classList.remove("pip"));
    v.addEventListener("webkitpresentationmodechanged", () => r.classList.toggle("pip", v.webkitPresentationMode === "picture-in-picture"));
  }
  setBuffering(on) {
    // a spinner shown for every tiny wait (segment boundaries etc.) looks like flicker -> only after 0.4 s
    clearTimeout(this.bufT);
    if (on) this.bufT = setTimeout(() => this.root.classList.add("buffering"), 400);
    else this.root.classList.remove("buffering");
  }
  setIcons() {
    const v = this.video;
    const playIc = this.ended ? "replay" : v.paused ? "play" : "pause";
    this.q(".b-play").innerHTML = icon(playIc);
    this.q(".c-play").innerHTML = icon(playIc);
    this.q(".b-play").title = this.ended ? $t("다시 보기") : v.paused ? $t("재생 (k)") : $t("일시중지 (k)");
    this.q(".b-mute").innerHTML = icon(v.muted || v.volume === 0 ? "mute" : v.volume < 0.5 ? "vollow" : "vol");
    const sl = this.q(".ytp-volbar input");
    sl.value = v.muted ? 0 : v.volume;
    sl.style.setProperty("--p", (v.muted ? 0 : v.volume) * 100 + "%");
    this.q(".b-auto").classList.toggle("on", settings.autoplay);
    this.q(".b-auto").title = settings.autoplay ? $t("자동재생 사용") : $t("자동재생 사용 안함");
    const fs = !!(document.fullscreenElement || document.webkitFullscreenElement) || document.body.classList.contains("tvfull");
    this.q(".b-fs").innerHTML = icon(fs ? "fsx" : "fs");
    this.q(".b-theater").innerHTML = icon(this.mode === "theater" ? "theaterx" : "theater");
    this.root.classList.toggle("fs", fs);
    this.q(".b-next").style.display = this.next ? "" : "none";
    this.q(".c-next").style.visibility = this.next ? "" : "hidden";
    this.q(".b-prev").style.display = this.hasPrev ? "" : "none";
    this.q(".c-prev").style.visibility = this.hasPrev ? "" : "hidden";
  }

  // ------------------------------------------------------------ controls
  bindControls() {
    const r = this.root, v = this.video;
    // skip buttons this device does not have (e.g. PiP removed on TVs). A missing button must not stop the whole app.
    const on = (s, f) => this.q(s)?.addEventListener("click", (e) => { e.stopPropagation(); f(e); });
    on(".b-play", () => this.toggle());
    on(".c-play", () => this.toggle());
    on(".b-next", () => this.on.next?.());
    on(".c-next", () => this.on.next?.());
    on(".b-prev", () => this.on.prev?.());
    on(".c-prev", () => this.on.prev?.());
    on(".b-mute", () => { v.muted = !v.muted; if (!v.muted && v.volume === 0) v.volume = 0.5; });
    on(".b-auto", () => { settings.autoplay = !settings.autoplay; this.setIcons(); this.flash(settings.autoplay ? $t("자동재생 사용") : $t("자동재생 사용 안함")); });
    on(".b-cc", () => this.toggleCaptions());
    on(".b-set", () => (this.menuOpen ? this.closeMenu() : this.openMenu("main")));
    on(".b-mini", () => this.on.mini?.());
    on(".b-theater", () => this.on.theater?.());
    on(".b-fs", () => this.fullscreen());
    on(".b-pip", () => this.pip());
    on(".ytp-unmute", () => this.unmute());
    on(".ytp-livebadge", () => this.seek(this.range()[1]));
    on(".ytp-chap", () => this.on.chapters?.());
    on(".ytp-skipbtn", () => { const s = this.pendingSkip; if (s) { this.seek(s.end); this.skipped.add(s); } });
    const sl = this.q(".ytp-volbar input");
    sl.addEventListener("input", () => { v.volume = +sl.value; v.muted = +sl.value === 0; });
    sl.addEventListener("click", (e) => e.stopPropagation());

    // click / tap
    let lastTap = 0, tapT, taps = 0, tapSide = 0;
    r.addEventListener("click", (e) => {
      if (e.target.closest(".ytp-bottom,.ytp-menu,.ytp-ctx,.ytp-end,.ytp-err,.ytp-center button,.ytp-skipbtn,.ytp-top")) return;
      if (this.menuOpen || this.ctxOpen) { this.closeMenu(); this.closeCtx(); return; }
      if (this.autoMuted) { this.unmute(); return; }
      if (this.mode === "mini") { return; }
      if (this.mode === "shorts") { this.toggle(); return; }
      if (!isTouch) {
        // desktop: single = play/pause, double = fullscreen
        clearTimeout(tapT);
        if (Date.now() - lastTap < 300) { lastTap = 0; this.toggle(); this.fullscreen(); return; }
        lastTap = Date.now();
        this.toggle();
        return;
      }
      // touch: single = show/hide controls, double left/right = 10 s seek (keeps adding while tapping)
      const b = r.getBoundingClientRect();
      const side = e.clientX < b.left + b.width * 0.35 ? -1 : e.clientX > b.left + b.width * 0.65 ? 1 : 0;
      const now = Date.now();
      if (side && now - lastTap < 350 && (taps === 0 || side === tapSide)) {
        clearTimeout(tapT);
        taps++;
        tapSide = side;
        lastTap = now;
        this.seek(side * 10, true);
        this.seekFx(side, taps * 10);
        tapT = setTimeout(() => { taps = 0; }, 700);
        return;
      }
      if (taps && side === tapSide && now - lastTap < 700) { taps++; lastTap = now; this.seek(side * 10, true); this.seekFx(side, taps * 10); clearTimeout(tapT); tapT = setTimeout(() => { taps = 0; }, 700); return; }
      lastTap = now;
      taps = 0;
      clearTimeout(tapT);
      tapT = setTimeout(() => {
        if (r.classList.contains("ctl") && !v.paused) this.hideCtl(); else this.showCtl();
      }, side ? 260 : 0);
    });
    r.addEventListener("mousemove", () => { if (!isTouch) this.showCtl(); });
    r.addEventListener("mouseleave", () => { if (!v.paused && !this.menuOpen) this.hideCtl(); });
    r.addEventListener("contextmenu", (e) => { if (this.mode === "mini") return; e.preventDefault(); this.openCtx(e); });
    for (const n of ["fullscreenchange", "webkitfullscreenchange"]) document.addEventListener(n, () => { this.setIcons(); this.on.fullscreen?.(); });
  }
  showCtl(stay) {
    this.root.classList.add("ctl");
    clearTimeout(this.hideT);
    if (!stay) this.autoHide();
  }
  autoHide() {
    clearTimeout(this.hideT);
    this.hideT = setTimeout(() => {
      if (!this.video.paused && !this.menuOpen && !this.scrubbing && !this.root.matches(".ytp-bottom:hover, .ytp-bottom *:hover")) this.hideCtl();
      else if (!this.video.paused) this.autoHide();
    }, isTouch ? 3000 : 2500);
  }
  hideCtl() {
    if (this.menuOpen || this.scrubbing) return;
    if (this.q(".ytp-bar").contains(document.activeElement)) return;
    if (this.q(".ytp-bottom").matches(":hover") && !isTouch) return;
    this.root.classList.remove("ctl");
  }
  bezel(name) {
    const b = this.q(".ytp-bezel");
    b.innerHTML = icon(name);
    b.classList.remove("go");
    void b.offsetWidth;
    b.classList.add("go");
  }
  seekFx(side, n) {
    const f = this.q(side < 0 ? ".ytp-seekfx.l" : ".ytp-seekfx.r");
    f.querySelector("span").textContent = $t("{0}초", n);
    f.classList.remove("go");
    void f.offsetWidth;
    f.classList.add("go");
  }
  flash(text) {
    const f = this.q(".ytp-flash");
    f.textContent = text;
    f.classList.remove("go");
    void f.offsetWidth;
    f.classList.add("go");
  }

  async fullscreen() {
    const r = this.root, v = this.video;
    const fsEl = document.fullscreenElement || document.webkitFullscreenElement;
    if (fsEl) {
      (document.exitFullscreen || document.webkitExitFullscreen).call(document);
      return;
    }
    if (r.requestFullscreen) {
      try {
        await r.requestFullscreen({ navigationUI: "hide" });
        const vw = v.videoWidth, vh = v.videoHeight;
        if (screen.orientation?.lock && vw > vh) screen.orientation.lock("landscape").catch(() => { });
      } catch { }
    } else if (r.webkitRequestFullscreen) r.webkitRequestFullscreen();
    else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();   // iPhone
  }
  async pip() {
    const v = this.video;
    try {
      // iPhone's native player: Safari's own Picture-in-Picture, the one that floats over other apps
      if (this.native && v.webkitSupportsPresentationMode?.("picture-in-picture")) {
        v.webkitSetPresentationMode(v.webkitPresentationMode === "picture-in-picture" ? "inline" : "picture-in-picture");
        return;
      }
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else await this.video.requestPictureInPicture();
    } catch { toast($t("PIP 를 열 수 없어요")); }
  }

  // ------------------------------------------------------------ settings menu
  openMenu(page) {
    this.menuOpen = true;
    this.showCtl(true);
    const m = this.q(".ytp-menu");
    m.innerHTML = this.menuHtml(page);
    m.classList.add("show");
    m.onclick = (e) => {
      e.stopPropagation();
      const it = e.target.closest("[data-a]");
      if (!it) return;
      this.menuAct(it.dataset.a, it.dataset.v);
    };
    const inp = m.querySelector("input[type=range]");
    if (inp) inp.oninput = () => { this.video.playbackRate = +inp.value; m.querySelector(".spdv").textContent = (+inp.value).toFixed(2) + "x"; };
  }
  closeMenu() {
    this.menuOpen = false;
    this.q(".ytp-menu").classList.remove("show");
    if (!this.video.paused) this.autoHide();
  }
  menuHtml(page) {
    const v = this.video;
    const row = (a, ic, label, val, vv = "") => `<div class="mi" data-a="${a}" data-v="${esc(vv)}">${icon(ic)}<span class="lb">${label}</span><span class="vl">${val}</span>${a.startsWith("go:") ? icon("right", "ar") : ""}</div>`;
    const tog = (a, ic, label, on) => `<div class="mi" data-a="${a}">${icon(ic)}<span class="lb">${label}</span><span class="tg ${on ? "on" : ""}"><i></i></span></div>`;
    const head = (t) => `<div class="mh" data-a="go:main">${icon("left")}<span>${t}</span></div>`;
    const opt = (a, v2, label, sel, sub = "") => `<div class="mo ${sel ? "sel" : ""}" data-a="${a}" data-v="${v2}">${sel ? icon("check") : "<i class=sp></i>"}<span>${label}</span>${sub ? `<em>${sub}</em>` : ""}</div>`;
    if (page === "main") {
      const t = this.curTrack();
      const caps = this.captionList();
      const auds = this.audioLangs();
      const sleepTxt = this.sleepAt === -1 ? $t("동영상 끝날 때") : this.sleepAt ? $t("{0}분", Math.ceil((this.sleepAt - Date.now()) / 60000)) : $t("사용 안함");
      return [
        tog("ambient", "ambient", $t("앰비언트 모드"), settings.ambient),
        caps.length ? row("go:cc", "cc", `${$t("자막 ({0})", caps.length)}`, this.capTrack ? esc(this.capTrack.label) : $t("사용 안함")) : "",
        row("go:speed", "speed", $t("재생 속도"), v.playbackRate === 1 ? $t("표준") : v.playbackRate + ""),
        this.native || (this.live && !this.tracks().length) ? "" : row("go:quality", "tune", $t("화질"), this.qLabel(t)),
        auds.length > 1 ? row("go:audio", "audio", $t("오디오 트랙"), esc(this.curAudio() || "")) : "",
        row("go:sleep", "timer", $t("수면 타이머"), sleepTxt),
        tog("loop", "loop", $t("반복 재생"), v.loop),
        tog("stats", "info", $t("전문가용 통계"), this.statsOn),
      ].join("");
    }
    if (page === "speed") {
      return head($t("재생 속도")) +
        `<div class="spd"><span class="spdv">${v.playbackRate.toFixed(2)}x</span><input type="range" min="0.25" max="4" step="0.05" value="${v.playbackRate}"></div>` +
        SPEEDS.map((s) => opt("speed", s, s === 1 ? $t("표준") : s, Math.abs(v.playbackRate - s) < 0.001)).join("");
    }
    if (page === "quality") {
      const t = this.curTrack();
      return head($t("화질")) + this.heights().map((x) =>
        opt("quality", x.height, this.qLabel(x), this.lockH === x.height, x.height >= 4320 ? "8K" : x.height >= 2160 ? "4K" : x.height >= 720 ? "HD" : "")).join("");
    }
    if (page === "cc") {
      return head($t("자막")) + opt("cc", "", $t("사용 안함"), !this.capTrack) +
        this.captionList().map((c, i) => opt("cc", i, esc(c.label), this.capTrack && this.capTrack.url === c.url)).join("") +
        `<div class="mo" data-a="go:capstyle"><i class=sp></i><span>${$t("옵션 (크기·배경)")}</span></div>`;
    }
    if (page === "capstyle") {
      return `<div class="mh" data-a="go:cc">${icon("left")}<span>${$t("자막 옵션")}</span></div>` +
        [50, 75, 100, 150, 200].map((s) => opt("capsize", s, `${$t("글자 크기 {0}%", s)}`, settings.capSize === s)).join("") +
        [0, 50, 75, 100].map((s) => opt("capbg", s, `${$t("배경 불투명도 {0}%", s)}`, settings.capBg === s)).join("");
    }
    if (page === "audio") {
      const cur = this.curAudio();
      return head($t("오디오 트랙")) + this.audioLangs().map((l) => opt("audio", l, esc(l), l === cur)).join("");
    }
    if (page === "sleep") {
      return head($t("수면 타이머")) + opt("sleep", 0, $t("사용 안함"), !this.sleepAt) +
        [10, 15, 20, 30, 45, 60].map((m) => opt("sleep", m, $t("{0}분", m), false)).join("") +
        (this.live ? "" : opt("sleep", -1, $t("동영상 끝날 때"), this.sleepAt === -1));
    }
    return "";
  }
  menuAct(a, val) {
    const v = this.video;
    if (a.startsWith("go:")) return this.openMenu(a.slice(3));
    if (a === "ambient") { settings.ambient = !settings.ambient; this.on.ambient?.(); return this.openMenu("main"); }
    if (a === "loop") { v.loop = !v.loop; this.flash(v.loop ? $t("반복 재생 켜짐") : $t("반복 재생 꺼짐")); return this.openMenu("main"); }
    if (a === "stats") { this.statsOn = !this.statsOn; this.q(".ytp-stats").classList.toggle("show", this.statsOn); return this.closeMenu(); }
    if (a === "speed") { v.playbackRate = +val; this.flash(val + "x"); return this.closeMenu(); }
    if (a === "quality") { this.setQuality(+val); return this.closeMenu(); }
    if (a === "cc") {
      const c = val === "" ? null : this.captionList()[+val];
      settings.captions = !!c;
      if (c) settings.capLang = c.lang.slice(0, 2);
      this.setCaption(c);
      return this.closeMenu();
    }
    if (a === "capsize") { settings.capSize = +val; this.renderCaption(true); return this.openMenu("capstyle"); }
    if (a === "capbg") { settings.capBg = +val; this.renderCaption(true); return this.openMenu("capstyle"); }
    if (a === "audio") { this.sp.selectAudioLanguage(val); return this.closeMenu(); }
    if (a === "sleep") {
      const m = +val;
      this.sleepAt = m === -1 ? -1 : m ? Date.now() + m * 60000 : 0;
      toast(m === -1 ? $t("이 동영상이 끝나면 멈춰요") : m ? `${$t("{0}분 뒤에 멈춰요", m)}` : $t("수면 타이머 꺼짐"));
      return this.closeMenu();
    }
  }
  audioLangs() {
    if (!this.sp) return [];
    return [...new Set(this.sp.getVariantTracks().map((t) => t.language).filter(Boolean))];
  }
  curAudio() { return this.curTrack()?.language; }

  // ------------------------------------------------------------ context menu
  openCtx(e) {
    const c = this.q(".ytp-ctx");
    const v = this.video;
    const items = [
      ["loop", "loop", $t("반복 재생"), v.loop],
      ["url", "link", $t("동영상 URL 복사")],
      ["urlt", "link", $t("현재 시간에 동영상 URL 복사")],
      ["yt", "open", $t("YouTube 에서 열기")],
      ["pip", "pip", $t("PIP 모드")],
      ["stats", "info", $t("전문가용 통계")],
      ["keys", "keyboard", $t("단축키")],
    ];
    c.innerHTML = items.map(([a, ic, l, on]) => `<div class="mi" data-a="${a}">${icon(ic)}<span class="lb">${l}</span>${on ? icon("check") : ""}</div>`).join("");
    const b = this.root.getBoundingClientRect();
    c.classList.add("show");
    c.style.left = Math.min(e.clientX - b.left, b.width - c.offsetWidth - 8) + "px";
    c.style.top = Math.min(e.clientY - b.top, b.height - c.offsetHeight - 8) + "px";
    this.ctxOpen = true;
    c.onclick = (ev) => {
      ev.stopPropagation();
      const a = ev.target.closest("[data-a]")?.dataset.a;
      this.closeCtx();
      const id = this.info?.videoId;
      if (a === "loop") { v.loop = !v.loop; this.flash(v.loop ? $t("반복 재생 켜짐") : $t("반복 재생 꺼짐")); }
      if (a === "url") copy(`${location.origin}/watch?v=${id}`);
      if (a === "urlt") copy(`${location.origin}/watch?v=${id}&t=${Math.floor(v.currentTime)}`);
      if (a === "yt") window.open(`https://www.youtube.com/watch?v=${id}&t=${Math.floor(v.currentTime)}`, "_blank", "noopener");
      if (a === "pip") this.pip();
      if (a === "stats") { this.statsOn = !this.statsOn; this.q(".ytp-stats").classList.toggle("show", this.statsOn); }
      if (a === "keys") this.on.keys?.();
    };
  }
  closeCtx() { this.ctxOpen = false; this.q(".ytp-ctx").classList.remove("show"); }

  // ------------------------------------------------------------ end screen / errors
  showEnd(next, seconds, onGo, onCancel) {
    const e = this.q(".ytp-end");
    if (!next) { e.innerHTML = ""; return; }
    e.innerHTML = `
      <div class="nx">
        <div class="nx-h">${$t("다음 동영상")} <b class="cnt">${seconds}</b></div>
        <div class="nx-card"><img src="/vi/${esc(next.id)}/mqdefault.jpg" alt=""><div><div class="nx-t">${esc(next.title)}</div><div class="nx-a">${esc(next.author)}</div></div></div>
        <div class="nx-b"><button class="cancel">${$t("취소")}</button><button class="go">${$t("지금 재생")}</button></div>
      </div>`;
    e.classList.add("show");
    this.showCtl(true);
    let n = seconds;
    clearInterval(this.endT);
    this.endT = setInterval(() => {
      n--;
      const c = e.querySelector(".cnt");
      if (c) c.textContent = n;
      if (n <= 0) { clearInterval(this.endT); this.hideEnd(); onGo(); }
    }, 1000);
    e.querySelector(".go").onclick = (ev) => { ev.stopPropagation(); clearInterval(this.endT); this.hideEnd(); onGo(); };
    e.querySelector(".cancel").onclick = (ev) => { ev.stopPropagation(); clearInterval(this.endT); this.hideEnd(); onCancel?.(); };
    e.querySelector(".nx-card").onclick = (ev) => { ev.stopPropagation(); clearInterval(this.endT); this.hideEnd(); onGo(); };
  }
  hideEnd() { clearInterval(this.endT); this.q(".ytp-end").classList.remove("show"); }
  onError(err) {
    const d = err || {};
    console.warn("player error", d);
    const status = d.data && d.data[1];
    if (d.code === 1001 && status === 401) {
      return this.showErr($t("로그인이 끝났어요"), $t("다시 로그인"), () => {
        toGate(`${location.origin}/watch?v=${this.info?.videoId}&t=${Math.floor(this.video.currentTime)}`);
      });
    }
    // the video URL expired (403) or the connection dropped: reopen once at the same time
    if (this.info && this.retried < 2 && (d.code === 1001 || d.code === 1002 || d.code === 3016 || d.category === 3)) {
      this.retried++;
      const t = this.video.currentTime, info = this.info;
      this.on.reload?.(info.videoId, t);
      return;
    }
    if (d.severity === 1) return;   // recoverable errors are handled by Shaka itself
    this.showErr(`${$t("재생 중 문제가 생겼어요 (오류 {0})", d.code || "?")}`, $t("다시 시도"), () => this.on.reload?.(this.info?.videoId, this.video.currentTime));
  }
  showErr(msg, btn, fn) {
    const e = this.q(".ytp-err");
    e.innerHTML = `<div>${icon("info")}<p>${esc(msg)}</p>${btn ? `<button>${esc(btn)}</button>` : ""}</div>`;
    e.classList.add("show");
    this.root.classList.remove("buffering");
    if (btn) e.querySelector("button").onclick = (ev) => { ev.stopPropagation(); this.hideErr(); fn(); };
  }
  hideErr() { this.q(".ytp-err").classList.remove("show"); }

  // ------------------------------------------------------------ periodic work (progress bar, captions, SponsorBlock, stats, sleep timer)
  loop() {
    // drawing (progress bar, captions) runs in rAF only while the page is visible,
    // skipping, the sleep timer and the clock run on a timer so they work with the screen off (background playback)
    let lastStats = 0;
    const draw = () => {
      requestAnimationFrame(draw);
      if (!this.info) return;
      const now = performance.now();
      if (this.root.classList.contains("ctl") || this.scrubbing || this.mode === "mini" || this.mode === "shorts") this.paintProgress(this.hoverT);
      this.renderCaption();
      if (this.statsOn && now - lastStats > 500) { lastStats = now; this.renderStats(); }
    };
    requestAnimationFrame(draw);
    setInterval(() => {
      if (!this.info) return;
      const v = this.video;
      const [, hi] = this.range();
      if (this.live) {
        this.q(".ytp-livebadge").classList.toggle("edge", hi - v.currentTime < 30);
      } else {
        this.q(".t-cur").textContent = fmtTime(this.scrubT ?? v.currentTime);
        this.q(".t-dur").textContent = fmtTime(hi);
      }
      const ch = this.chapters.find((c) => v.currentTime >= c.start && v.currentTime < c.end);
      const cb = this.q(".ytp-chap");
      const txt = ch ? `• ${ch.title}` : "";
      if (cb.dataset.t !== txt) { cb.dataset.t = txt; cb.innerHTML = txt ? `${esc(txt)} ${icon("right")}` : ""; }
      this.sponsorCheck();
      if (this.sleepAt > 0 && Date.now() > this.sleepAt) { this.sleepAt = 0; v.pause(); toast($t("수면 타이머: 재생을 멈췄어요")); }
      this.on.tick?.(v.currentTime);
      if ("mediaSession" in navigator && navigator.mediaSession.setPositionState && !this.live && isFinite(v.duration)) {
        try { navigator.mediaSession.setPositionState({ duration: v.duration, playbackRate: v.playbackRate, position: Math.min(v.currentTime, v.duration) }); } catch { }
      }
    }, 250);
    setInterval(() => { if (this.info && !this.video.paused) this.on.progress?.(false); }, 10000);
  }
  sponsorCheck() {
    const v = this.video, t = v.currentTime;
    const btn = this.q(".ytp-skipbtn");
    // where segments overlap, the auto-skip categories win
    const hits = this.segments.filter((x) => t >= x.start && t < x.end - 0.3);
    const s = hits.find((x) => settings.sponsorCats.includes(x.cat) && !this.skipped.has(x)) || hits[0];
    this.pendingSkip = s;
    if (!s) { btn.classList.remove("show"); return; }
    const auto = settings.sponsorCats.includes(s.cat);
    if (auto && !this.skipped.has(s) && !v.paused) {
      this.skipped.add(s);
      v.currentTime = s.end;
      toast(`${$t("{0} 건너뜀", SB_CATS[s.cat]?.[0] || $t("구간"))}`, $t("실행취소"), () => { v.currentTime = s.start; });
      btn.classList.remove("show");
      return;
    }
    if (!auto) {
      btn.innerHTML = `${$t("{0} 건너뛰기 {1}", esc(SB_CATS[s.cat]?.[0] || $t("구간")), icon("skip"))}`;
      btn.classList.add("show");
    }
  }
  renderStats() {
    const s = this.sp?.getStats?.() || {};
    const t = this.curTrack() || {};
    const v = this.video;
    let buf = 0;
    for (let i = 0; i < v.buffered.length; i++) if (v.buffered.start(i) <= v.currentTime && v.buffered.end(i) >= v.currentTime) buf = v.buffered.end(i) - v.currentTime;
    const q = v.getVideoPlaybackQuality?.() || {};
    const rows = [
      [$t("동영상 ID"), this.info?.videoId],
      [$t("화면 / 프레임"), `${$t("{0}x{1}*{2} / 손실 {3} / {4}", this.root.clientWidth, this.root.clientHeight, devicePixelRatio, q.droppedVideoFrames ?? s.droppedFrames ?? 0, q.totalVideoFrames ?? s.decodedFrames ?? 0)}`],
      [$t("현재 / 최적 해상도"), `${v.videoWidth}x${v.videoHeight}@${Math.round(t.frameRate || 0)}fps / ${t.width || "?"}x${t.height || "?"}`],
      [$t("60프레임 버전"), this.tracks().some((x) => (x.frameRate || 0) > 48) ? $t("있음 (사용 중: ") + (t.frameRate > 48 ? $t("예") : $t("아니오")) + ")" : $t("없음 (올린 영상이 30프레임)")],
      [$t("코덱"), `${t.videoCodec || "?"} (${t.originalVideoId || t.id || ""}) / ${t.audioCodec || "?"}`],
      [$t("볼륨"), `${Math.round(v.volume * 100)}%${v.muted ? $t(" (음소거)") : ""}`],
      [$t("연결 속도"), `${Math.round((s.estimatedBandwidth || 0) / 1000).toLocaleString()} Kbps`],
      [$t("트랙 비트레이트"), `${Math.round((t.bandwidth || 0) / 1000).toLocaleString()} Kbps`],
      [$t("버퍼 상태"), `${buf.toFixed(2)} s`],
      this.live ? [$t("실시간 지연"), `${(this.range()[1] - v.currentTime).toFixed(1)} s`] : null,
      [$t("재생 속도"), v.playbackRate + "x"],
      [$t("모드"), this.live ? $t("HLS 실시간") : "DASH"],
    ].filter(Boolean);
    this.q(".ytp-stats").innerHTML = `<button class="x">${icon("close")}</button>` + rows.map(([k, x]) => `<div><b>${k}</b><span>${esc(x)}</span></div>`).join("");
    this.q(".ytp-stats .x").onclick = (e) => { e.stopPropagation(); this.statsOn = false; this.q(".ytp-stats").classList.remove("show"); };
  }

  // ------------------------------------------------------------ ambient mode (video colours glowing behind it)
  ambientTo(canvas) {
    this.ambCanvas = canvas;
    if (this.ambT) return;
    this.ambT = setInterval(() => {
      const c = this.ambCanvas, v = this.video;
      // Copying a large hardware-decoded frame breaks the accelerated path: frames collapse and flicker (down to 1 fps at 8K).
      // So draw only in the dark theme (where it shows), at 1440p or less, not HDR, once a second.
      const t = this.curTrack();
      const heavy = !t || t.height > 1440 || t.hdr === "PQ" || t.hdr === "HLG" || /\.10\./.test(t.videoCodec || "");
      if (!c || !c.isConnected || !settings.ambient || heavy || document.documentElement.dataset.theme !== "dark" ||
          v.readyState < 2 || v.paused || document.hidden || this.mode !== "default" && this.mode !== "theater") { if (c && heavy) c.classList.remove("on"); return; }
      try {
        const g = c.getContext("2d");
        g.globalAlpha = 0.5;    // draw over the previous picture so it changes smoothly
        g.drawImage(v, 0, 0, c.width, c.height);
        c.classList.add("on");
      } catch { }
    }, 1000);
  }

  // ------------------------------------------------------------ lock screen / Bluetooth buttons
  mediaSession() {
    if (!("mediaSession" in navigator) || !this.info) return;
    const i = this.info;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: i.title, artist: i.author,
      artwork: [
        { src: `${location.origin}/vi/${i.videoId}/mqdefault.jpg`, sizes: "320x180", type: "image/jpeg" },
        { src: `${location.origin}/vi/${i.videoId}/maxres.jpg`, sizes: "1280x720", type: "image/jpeg" },
      ],
      album: i.liveNow ? $t("실시간") : $t("튜브하우스"),
    });
    const ms = navigator.mediaSession;
    const set = (a, f) => { try { ms.setActionHandler(a, f); } catch { } };
    set("play", () => this.play());
    set("pause", () => this.video.pause());
    set("seekbackward", (d) => this.seek(-(d.seekOffset || 10), true));
    set("seekforward", (d) => this.seek(d.seekOffset || 10, true));
    set("seekto", (d) => this.seek(d.seekTime));
    set("nexttrack", this.next ? () => this.on.next?.() : null);
    set("previoustrack", this.hasPrev ? () => this.on.prev?.() : null);
  }

  // ------------------------------------------------------------ keyboard shortcuts (same as YouTube)
  key(e) {
    const v = this.video, k = e.key;
    if (e.ctrlKey || e.metaKey || e.altKey) return false;
    const frame = 1 / (this.curTrack()?.frameRate || 30);
    switch (k) {
      case " ": case "k": case "K": this.toggle(); break;
      case "j": case "J": this.seek(-10, true); this.seekFx(-1, 10); break;
      case "l": case "L": this.seek(10, true); this.seekFx(1, 10); break;
      case "ArrowLeft": this.seek(-5, true); this.seekFx(-1, 5); break;
      case "ArrowRight": this.seek(5, true); this.seekFx(1, 5); break;
      case "ArrowUp": v.muted = false; v.volume = Math.min(1, v.volume + 0.05); this.flash(Math.round(v.volume * 100) + "%"); break;
      case "ArrowDown": v.volume = Math.max(0, v.volume - 0.05); this.flash(Math.round(v.volume * 100) + "%"); break;
      case "m": case "M": v.muted = !v.muted; this.bezel(v.muted ? "mute" : "vol"); break;
      case "f": case "F": this.fullscreen(); break;
      case "t": case "T": this.on.theater?.(); break;
      case "i": case "I": this.on.mini?.(); break;
      case "c": case "C": this.toggleCaptions(); break;
      case "Home": this.seek(this.range()[0]); break;
      case "End": this.seek(this.range()[1]); break;
      case ",": if (v.paused) v.currentTime = Math.max(0, v.currentTime - frame); break;
      case ".": if (v.paused) v.currentTime += frame; break;
      case "<": v.playbackRate = Math.max(0.25, +(v.playbackRate - 0.25).toFixed(2)); this.flash(v.playbackRate + "x"); break;
      case ">": v.playbackRate = Math.min(2, +(v.playbackRate + 0.25).toFixed(2)); this.flash(v.playbackRate + "x"); break;
      case "N": if (e.shiftKey) this.on.next?.(); else return false; break;
      case "P": if (e.shiftKey) this.on.prev?.(); else return false; break;
      case "Escape": if (this.menuOpen) this.closeMenu(); else return false; break;
      default:
        if (/^[0-9]$/.test(k) && !this.live) { this.seek((this.range()[1] * +k) / 10); break; }
        return false;
    }
    this.showCtl();
    return true;
  }
}
