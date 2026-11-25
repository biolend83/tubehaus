// TubeHaus SABR without a browser: a page-like world for ../page-src.mjs inside QuickJS (the app's JsSabr).
// jsdom stands in for the document BotGuard looks at; fetch goes to the host: __fetch(json, body) for the head and
// __read(id) for each piece of the body (the app, quickjs-kt), or __fetch(id, json) with __thFetchHead/Data/End
// called back (a host that pushes, as the test in test.mjs does).
import { JSDOM } from "jsdom";
import { ReadableStream, WritableStream, TransformStream, ByteLengthQueuingStrategy, CountQueuingStrategy } from "web-streams-polyfill";

const g = globalThis;
g.__thQjs = true;
Object.assign(g, { ReadableStream, WritableStream, TransformStream, ByteLengthQueuingStrategy, CountQueuingStrategy });
const ORIGIN = "https://www.youtube.com";
const UA = g.__ua || "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)";

// ---- fetch through the host
const b64u8 = (s) => g.__b64u8(s);
const u8b64 = (u8) => { let s = ""; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); };
class Headers {
  constructor(h) { this._m = new Map(); if (h) for (const [k, v] of (h instanceof Headers ? h._m : Symbol.iterator in Object(h) ? h : Object.entries(h))) this._m.set(String(k).toLowerCase(), String(v)); }
  get(k) { return this._m.has(k.toLowerCase()) ? this._m.get(k.toLowerCase()) : null; } has(k) { return this._m.has(k.toLowerCase()); }
  set(k, v) { this._m.set(k.toLowerCase(), String(v)); } append(k, v) { this.set(k, v); } delete(k) { this._m.delete(k.toLowerCase()); }
  forEach(f) { this._m.forEach((v, k) => f(v, k, this)); } entries() { return this._m.entries(); } [Symbol.iterator]() { return this._m.entries(); }
}
class Response {
  constructor(status, headers, body) { this.status = status; this.ok = status >= 200 && status < 300; this.statusText = ""; this.headers = headers; this.body = body; }
  async arrayBuffer() { const rd = this.body.getReader(), parts = []; let n = 0; for (;;) { const { done, value } = await rd.read(); if (done) break; parts.push(value); n += value.length; }
    const u = new Uint8Array(n); let o = 0; for (const p of parts) { u.set(p, o); o += p.length; } return u.buffer; }
  async text() { return new TextDecoder().decode(new Uint8Array(await this.arrayBuffer())); }
  async json() { return JSON.parse(await this.text()); }
}
const open = new Map();
let fid = 0;
// the app's way (quickjs-kt): __fetch(json, body) answers the head, __read(id) each piece of the body (null at its end)
const pullFetch = async (input, o = {}) => {
  const url = new URL(String(input && input.url || input), ORIGIN + "/").href;
  const h = { "user-agent": UA, "accept-language": "en-US,en;q=0.9", origin: ORIGIN, referer: ORIGIN + "/", ...Object.fromEntries(new Headers(o.headers)._m) };
  const b = o.body;
  const body = b == null ? null : typeof b === "string" ? new TextEncoder().encode(b) : b instanceof Uint8Array ? b : ArrayBuffer.isView(b) ? new Uint8Array(b.buffer, b.byteOffset, b.byteLength) : new Uint8Array(b);
  if (o.signal && o.signal.aborted) throw new Error("AbortError");
  const head = JSON.parse(await __fetch(JSON.stringify({ url, method: o.method || "GET", headers: h }), body));
  const id = head.id;
  if (o.signal && o.signal.addEventListener) o.signal.addEventListener("abort", () => __fetchAbort(id));
  const stream = new ReadableStream({
    async pull(c) { const d = await __read(id); if (d == null) c.close(); else c.enqueue(d instanceof Uint8Array ? d : new Uint8Array(d)); },
    cancel() { __fetchAbort(id); },
  }, { highWaterMark: 0 });
  return new Response(head.status, new Headers(head.headers), stream);
};
// commands from the app, one at a time off __next(); answers go back through __reply(id, value, error)
g.__thLoop = async () => {
  for (;;) {
    const c = await __next();
    if (c == null) return "closed";
    const m = JSON.parse(c);
    (async () => {
      try { const v = await window.__thSabr[m.f](...m.a); if (m.id) __reply(m.id, v == null ? null : String(v), null); }
      catch (e) { if (m.id) __reply(m.id, null, String((e && e.message) || e)); }
    })();
  }
};
g.fetch = typeof __read === "function" ? pullFetch : (input, o = {}) => new Promise((ok, no) => {
  const url = new URL(String(input && input.url || input), ORIGIN + "/").href;
  const id = ++fid;
  const h = { "user-agent": UA, "accept-language": "en-US,en;q=0.9", origin: ORIGIN, referer: ORIGIN + "/", ...Object.fromEntries(new Headers(o.headers)._m) };
  let body = o.body;
  if (body != null && typeof body !== "string") body = u8b64(body instanceof Uint8Array ? body : new Uint8Array(body.buffer || body));
  else if (typeof body === "string") body = u8b64(new TextEncoder().encode(body));
  let ctl;
  const stream = new ReadableStream({ start(c) { ctl = c; }, cancel() { open.delete(id); __fetchAbort(id); } });
  open.set(id, { ok, no, ctl, head: false });
  if (o.signal) { if (o.signal.aborted) { open.delete(id); return no(new Error("AbortError")); } o.signal.addEventListener?.("abort", () => { const f = open.get(id); if (!f) return; open.delete(id); __fetchAbort(id); f.head ? f.ctl.error(new Error("AbortError")) : no(new Error("AbortError")); }); }
  __fetch(id, JSON.stringify({ url, method: o.method || "GET", headers: h, body: body ?? null }));
  open.get(id).stream = stream;
});
g.__thFetchHead = (id, status, hjson) => { const f = open.get(id); if (!f) return; f.head = true; f.ok(new Response(status, new Headers(JSON.parse(hjson)), f.stream)); };
g.__thFetchData = (id, b64) => { const f = open.get(id); if (f) f.ctl.enqueue(b64u8(b64)); };
g.__thFetchEnd = (id, err) => { const f = open.get(id); if (!f) return; open.delete(id); if (!f.head) return f.no(new Error(err || "network")); err ? f.ctl.error(new Error(err)) : f.ctl.close(); };
Object.assign(g, { Headers, Response });
if (!g.AbortController || !new g.AbortController().signal.addEventListener.length) {
  g.AbortController = class { constructor() { const ls = []; this.signal = { aborted: false, reason: undefined, onabort: null, addEventListener: (t, f) => ls.push(f), removeEventListener: (t, f) => { const i = ls.indexOf(f); if (i >= 0) ls.splice(i, 1); }, throwIfAborted() { if (this.aborted) throw this.reason; } }; this._ls = ls; }
    abort(r) { if (this.signal.aborted) return; this.signal.aborted = true; this.signal.reason = r || new Error("AbortError"); for (const f of this._ls.slice()) f({ type: "abort" }); this.signal.onabort?.({ type: "abort" }); } };
}

// ---- a window for BotGuard (as in a browser page on www.youtube.com)
// jsdom, as the server uses (YouTube takes its tokens; happy-dom's pass BotGuard but the video server turns them down)
const win = new JSDOM('<!DOCTYPE html><html lang="en"><head><title></title></head><body></body></html>', { url: ORIGIN, referrer: ORIGIN + "/", userAgent: UA }).window;
const ctx2d = { fillRect() {}, clearRect() {}, getImageData: (_x, _y, w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }), putImageData() {}, createImageData: () => [], setTransform() {}, drawImage() {}, save() {}, fillText() {}, restore() {}, beginPath() {}, moveTo() {}, lineTo() {}, closePath() {}, stroke() {}, translate() {}, scale() {}, rotate() {}, arc() {}, fill() {}, measureText: () => ({ width: 0 }), transform() {}, rect() {}, clip() {} };
if (win.HTMLCanvasElement) win.HTMLCanvasElement.prototype.getContext = (id) => (id === "2d" ? ctx2d : null);
win.yt = {};
Object.assign(g, { window: win, yt: win.yt, document: win.document, location: win.location, origin: ORIGIN, navigator: win.navigator });
win.__thHost = (s) => g.__thHost(s);
