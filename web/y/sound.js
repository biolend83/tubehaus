// The sound: loudness matching (YouTube's own loudness number for each video), and for those who want them an
// equalizer, stereo width, balance and a night mode (quiet parts up, loud parts down). The effects go through
// Web Audio, attached to a player's <audio>/<video> only once one is needed and the browser lets sound start.
// iPhone and iPad are left alone: Safari there stops Web Audio when the screen locks, and ignores volume.
import { settings, IPHONE, IPAD } from "./util.js";

export const BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
export const PRESETS = {
  flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  bass: [6, 5, 4, 2, 0, 0, 0, 0, 0, 0],
  treble: [0, 0, 0, 0, 0, 1, 2, 4, 5, 6],
  vocal: [-2, -2, -1, 1, 3, 4, 3, 1, 0, -1],
  pop: [-1, 1, 2, 3, 2, 0, -1, -1, 0, 1],
  rock: [4, 3, 2, 0, -1, -1, 1, 2, 3, 4],
  jazz: [3, 2, 1, 2, -1, -1, 0, 1, 2, 3],
  classical: [3, 2, 1, 0, 0, 0, -1, -1, 1, 2],
  electronic: [5, 4, 1, 0, -2, 1, 0, 1, 4, 5],
  acoustic: [3, 3, 2, 1, 1, 1, 2, 2, 2, 1],
  hiphop: [5, 4, 2, 3, -1, -1, 1, 0, 1, 2],
  speech: [-4, -3, -1, 1, 3, 4, 4, 2, 0, -2],
};
const FX0 = { eq: false, preset: "flat", gains: PRESETS.flat, width: 100, balance: 0, night: false };

export const CAN_FX = !(IPHONE || IPAD) && !!(window.AudioContext || window.webkitAudioContext);
export const CAN_LEVEL = !(IPHONE || IPAD);

export function fx() {
  const f = { ...FX0, ...(settings.fx && typeof settings.fx === "object" ? settings.fx : {}) };
  if (!Array.isArray(f.gains) || f.gains.length !== BANDS.length) f.gains = PRESETS[f.preset] || PRESETS.flat;
  f.gains = f.gains.map((g) => Math.max(-12, Math.min(12, +g || 0)));
  f.width = Math.max(0, Math.min(200, +f.width || 0));
  f.balance = Math.max(-100, Math.min(100, +f.balance || 0));
  return f;
}
export function setFx(patch) {
  settings.fx = { ...fx(), ...patch };
  for (const h of handles) h.update();
}
const eqOn = (f) => f.eq && f.gains.some((g) => g);
export const effectsOn = (f = fx()) => eqOn(f) || f.width !== 100 || f.balance !== 0 || f.night;

/** how much to turn a video down: YouTube says how many dB louder than its target it is. Quieter ones stay as they are */
const levelOf = (db) => (settings.soundNorm !== false && Number.isFinite(db) && db > 0 ? 10 ** (-Math.min(db, 20) / 20) : 1);

// ---------------------------------------------------------------- the audio context, once sound may start
let ctx = null, waiting = false;
const pending = new Set();
function context() {
  if (!ctx) {
    const C = window.AudioContext || window.webkitAudioContext;
    try { ctx = new C({ latencyHint: "playback" }); } catch { return null; }
  }
  return ctx;
}
function whenRunning(h) {
  const c = context();
  if (!c) return;
  if (c.state === "running") return h.attach();
  pending.add(h);
  c.resume().then(() => { if (c.state === "running") flush(); }, () => { });
  if (waiting) return;
  waiting = true;
  // autoplay rules: a context may only start on a tap or a key
  const go = () => c.resume().then(() => { if (c.state === "running") { flush(); off(); } }, () => { });
  const off = () => { waiting = false; for (const ev of ["pointerdown", "keydown", "touchend"]) removeEventListener(ev, go, true); };
  for (const ev of ["pointerdown", "keydown", "touchend"]) addEventListener(ev, go, true);
}
function flush() { for (const h of pending) h.attach(); pending.clear(); }

const handles = new Set();
/**
 * The sound of one player. kind "music": the music player (its volume is ours, so the level and the fades
 * go through it where there are no effects); "video": the video player (its volume is the viewer's slider).
 */
export function sound(el, kind) {
  const h = {
    el, kind, db: NaN, fadeV: 1, g: null,
    /** this video's loudness (dB over YouTube's target), NaN when unknown */
    level(db) { h.db = +db; h.update(); },
    /** 0..1 on top of everything (fading between songs, the sleep timer) */
    fade(v) { h.fadeV = Math.max(0, Math.min(1, v)); h.gain(); },
    wanted() {
      if (!CAN_FX) return false;
      const f = fx();
      return kind === "music" ? effectsOn(f) : (settings.soundVideo && effectsOn(f)) || levelOf(h.db) < 1;
    },
    update() {
      if (!h.g && h.wanted()) whenRunning(h);
      if (h.g) h.wire();
      h.gain();
    },
    gain() {
      const v = (CAN_LEVEL ? levelOf(h.db) : 1) * h.fadeV;
      if (h.g) {
        // the element's own volume (the video's slider) still applies before the graph
        if (kind === "music") el.volume = 1;
        h.g.level.gain.setTargetAtTime(v, h.g.c.currentTime, 0.015);
      } else if (kind === "music" && CAN_LEVEL) el.volume = v;
    },
    attach() {
      if (h.g) return;
      const c = context();
      let src;
      try { src = c.createMediaElementSource(el); } catch { return; }
      const level = c.createGain();
      // a mono song upmixed to both sides first, so width and balance have two channels to work with
      level.channelCount = 2; level.channelCountMode = "explicit"; level.channelInterpretation = "speakers";
      const bands = BANDS.map((hz, i) => {
        const b = c.createBiquadFilter();
        b.type = i === 0 ? "lowshelf" : i === BANDS.length - 1 ? "highshelf" : "peaking";
        b.frequency.value = hz;
        b.Q.value = 1.1;
        return b;
      });
      const pre = c.createGain();
      const split = c.createChannelSplitter(2), merge = c.createChannelMerger(2);
      const ll = c.createGain(), lr = c.createGain(), rr = c.createGain(), rl = c.createGain();
      split.connect(ll, 0); split.connect(lr, 0); split.connect(rr, 1); split.connect(rl, 1);
      ll.connect(merge, 0, 0); rl.connect(merge, 0, 0); rr.connect(merge, 0, 1); lr.connect(merge, 0, 1);
      const pan = c.createStereoPanner ? c.createStereoPanner() : null;
      const comp = c.createDynamicsCompressor();
      comp.threshold.value = -28; comp.knee.value = 12; comp.ratio.value = 4; comp.attack.value = 0.005; comp.release.value = 0.25;
      const makeup = c.createGain();
      makeup.gain.value = 10 ** (7 / 20);
      h.g = { c, src, level, bands, pre, split, merge, m: [ll, lr, rr, rl], pan, comp, makeup, sig: "" };
      src.connect(level);
      h.wire();
      h.gain();
      // a context the browser paused (another tab took the sound device): back on when this plays
      el.addEventListener("play", () => { if (c.state !== "running") c.resume().catch(() => { }); });
    },
    // the chain: level -> equalizer -> width -> balance -> night mode -> speakers, leaving out what is off
    wire() {
      const g = h.g, f = fx(), video = kind === "video" && !settings.soundVideo;
      const on = (x) => !video && x;
      const sig = JSON.stringify([video, f.eq, f.width !== 100, f.balance !== 0, f.night]);
      g.bands.forEach((b, i) => { b.gain.value = on(f.eq) ? f.gains[i] : 0; });
      // headroom for what the equalizer raises, so a raised band doesn't clip
      g.pre.gain.value = on(eqOn(f)) ? 10 ** (-Math.max(0, ...f.gains) * 0.75 / 20) : 1;
      const w = f.width / 100, a = (1 + w) / 2, b = (1 - w) / 2;
      g.m[0].gain.value = a; g.m[2].gain.value = a; g.m[1].gain.value = b; g.m[3].gain.value = b;
      if (g.pan) g.pan.pan.value = f.balance / 100;
      if (sig === g.sig) return;
      g.sig = sig;
      for (const n of [g.level, ...g.bands, g.pre, g.merge, g.pan, g.comp, g.makeup]) try { n?.disconnect(); } catch { }
      let at = g.level;
      const to = (n) => { at.connect(n); at = n; };
      if (on(eqOn(f))) { for (const b of g.bands) to(b); to(g.pre); }
      if (on(f.width !== 100)) { at.connect(g.split); at = g.merge; }
      if (on(f.balance !== 0) && g.pan) to(g.pan);
      if (on(f.night)) { to(g.comp); to(g.makeup); }
      at.connect(g.c.destination);
    },
  };
  handles.add(h);
  return h;
}
addEventListener("settings", (e) => { if (e.detail === "soundNorm" || e.detail === "soundVideo") for (const h of handles) h.update(); });
