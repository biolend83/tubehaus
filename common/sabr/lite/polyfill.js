// what QuickJS lacks of a browser's globals, as far as jsdom, googlevideo and BotGuard need them; evaluated before sabr-lite.js.
// The host gives __now, __rand, __log and a clock: __sleep(ms) (a promise) or __timer(fn, ms).
(function (g) {
  g.globalThis = g; g.self = g; g.global = g;
  g.WeakRef = g.WeakRef || class { constructor(o) { this._o = o; } deref() { return this._o; } };
  g.FinalizationRegistry = g.FinalizationRegistry || class { register() {} unregister() {} };
  g.process = g.process || { env: {}, argv: [], platform: "browser", version: "v20.0.0", versions: { node: "20.0.0" }, cwd: () => "/", nextTick: (f, ...a) => Promise.resolve().then(() => f(...a)), on() {}, emit() {}, hrtime: Object.assign(() => [0, 0], { bigint: () => 0n }), memoryUsage: () => ({}) };
  g.console = g.console || { log: (...a) => __log(a.join(" ")), warn: (...a) => __log(a.join(" ")), error: (...a) => __log(a.join(" ")), info() {}, debug() {} };
  let tid = 0; const live = new Set();
  // the host's clock: __sleep(ms) (a promise, quickjs-kt) or __timer(fn, ms) (a loop of its own)
  const __timerOf = (f, ms) => (typeof __sleep === "function" ? __sleep(Math.max(0, +ms || 0)).then(f) : __timer(f, ms));
  g.setTimeout = (f, ms, ...a) => { const id = ++tid; live.add(id); __timerOf(() => { if (live.delete(id)) typeof f === "function" ? f(...a) : (0, eval)(f); }, ms || 0); return id; };
  g.clearTimeout = (id) => { live.delete(id); };
  g.setInterval = (f, ms, ...a) => { const id = ++tid; live.add(id); const tick = () => { if (!live.has(id)) return; f(...a); __timerOf(tick, ms || 0); }; __timerOf(tick, ms || 0); return id; };
  g.clearInterval = g.clearTimeout;
  g.setImmediate = (f, ...a) => g.setTimeout(f, 0, ...a);
  g.clearImmediate = g.clearTimeout;
  g.queueMicrotask = g.queueMicrotask || ((f) => Promise.resolve().then(f));
  const t0 = __now();
  g.performance = g.performance || { now: () => __now() - t0, timeOrigin: Date.now(), mark() {}, measure() {}, getEntriesByName: () => [], getEntriesByType: () => [] };
  g.structuredClone = g.structuredClone || ((x) => (x === undefined ? x : JSON.parse(JSON.stringify(x))));

  // what webidl-conversions (jsdom) reads off the engine: QuickJS has no resizable or growable buffers
  for (const [C, k] of [[g.ArrayBuffer, "resizable"], [g.SharedArrayBuffer, "growable"]]) if (C && !Object.getOwnPropertyDescriptor(C.prototype, k)) Object.defineProperty(C.prototype, k, { get() { return false; }, configurable: true });
  if (!g.SharedArrayBuffer) { g.SharedArrayBuffer = class SharedArrayBuffer { constructor() { throw new TypeError("no SharedArrayBuffer"); } }; Object.defineProperty(g.SharedArrayBuffer.prototype, "growable", { get() { return false; }, configurable: true }); Object.defineProperty(g.SharedArrayBuffer.prototype, "byteLength", { get() { return 0; }, configurable: true }); }
  // Iterator and its helpers (ES2025): jsdom's tree walking extends Iterator and uses map/filter/find/toArray
  if (typeof g.Iterator === "undefined") {
    const AIP = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
    function Iterator() { if (new.target === undefined || new.target === Iterator) throw new TypeError("Iterator is abstract"); }
    Iterator.prototype = AIP;
    Object.defineProperty(AIP, "constructor", { value: Iterator, writable: true, configurable: true });
    const gen = (it, f) => { const o = Object.create(AIP); let done = false; o.next = () => { if (done) return { done: true, value: undefined }; const r = f(); if (r.done) done = true; return r; }; o.return = (v) => { done = true; if (it.return) it.return(); return { done: true, value: v }; }; return o; };
    const H = {
      map(fn) { let i = 0; return gen(this, () => { const r = this.next(); return r.done ? r : { done: false, value: fn(r.value, i++) }; }); },
      filter(fn) { let i = 0; return gen(this, () => { for (;;) { const r = this.next(); if (r.done || fn(r.value, i++)) return r; } }); },
      take(n) { let i = 0; return gen(this, () => (i++ < n ? this.next() : (this.return && this.return(), { done: true, value: undefined }))); },
      drop(n) { let i = 0; return gen(this, () => { while (i < n) { i++; if (this.next().done) return { done: true, value: undefined }; } return this.next(); }); },
      flatMap(fn) { let inner = null, i = 0; return gen(this, () => { for (;;) { if (inner) { const r = inner.next(); if (!r.done) return r; inner = null; } const o = this.next(); if (o.done) return o; inner = fn(o.value, i++)[Symbol.iterator](); } }); },
      toArray() { const a = []; for (let r = this.next(); !r.done; r = this.next()) a.push(r.value); return a; },
      forEach(fn) { let i = 0; for (let r = this.next(); !r.done; r = this.next()) fn(r.value, i++); },
      some(fn) { let i = 0; for (let r = this.next(); !r.done; r = this.next()) if (fn(r.value, i++)) { this.return && this.return(); return true; } return false; },
      every(fn) { let i = 0; for (let r = this.next(); !r.done; r = this.next()) if (!fn(r.value, i++)) { this.return && this.return(); return false; } return true; },
      find(fn) { let i = 0; for (let r = this.next(); !r.done; r = this.next()) if (fn(r.value, i++)) { this.return && this.return(); return r.value; } return undefined; },
      reduce(fn, ...init) { let i = 0, acc; if (init.length) acc = init[0]; else { const r = this.next(); if (r.done) throw new TypeError("reduce of empty iterator"); acc = r.value; i = 1; } for (let r = this.next(); !r.done; r = this.next()) acc = fn(acc, r.value, i++); return acc; },
    };
    for (const [k, f] of Object.entries(H)) if (!AIP[k]) Object.defineProperty(AIP, k, { value: f, writable: true, configurable: true });
    Iterator.from = (o) => { const it = o[Symbol.iterator] ? o[Symbol.iterator]() : o; return it instanceof Iterator ? it : gen(it, () => it.next()); };
    g.Iterator = Iterator;
  }
  // ---- text
  if (!g.TextEncoder) {
    g.TextEncoder = class { get encoding() { return "utf-8"; } encode(s = "") {
      s = String(s); const out = [];
      for (let i = 0; i < s.length; i++) {
        let c = s.charCodeAt(i);
        if (c >= 0xd800 && c < 0xdc00 && i + 1 < s.length) { const d = s.charCodeAt(i + 1); if (d >= 0xdc00 && d < 0xe000) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; } }
        if (c < 0x80) out.push(c); else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
      return new Uint8Array(out);
    } };
    g.TextDecoder = class { constructor(l = "utf-8") { this.encoding = l; } decode(b) {
      if (!b) return ""; const u = b instanceof Uint8Array ? b : ArrayBuffer.isView(b) ? new Uint8Array(b.buffer, b.byteOffset, b.byteLength) : new Uint8Array(b);
      const parts = []; let cs = [];
      for (let i = 0; i < u.length;) {
        const c = u[i++];
        if (c < 0x80) cs.push(c);
        else if (c < 0xe0) cs.push(((c & 31) << 6) | (u[i++] & 63));
        else if (c < 0xf0) cs.push(((c & 15) << 12) | ((u[i++] & 63) << 6) | (u[i++] & 63));
        else { const p = (((c & 7) << 18) | ((u[i++] & 63) << 12) | ((u[i++] & 63) << 6) | (u[i++] & 63)) - 0x10000; cs.push(0xd800 + (p >> 10), 0xdc00 + (p & 1023)); }
        if (cs.length >= 8192) { parts.push(String.fromCharCode.apply(null, cs)); cs = []; }
      }
      parts.push(String.fromCharCode.apply(null, cs));
      return parts.join("");
    } };
  }
  const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const B64I = new Int16Array(128).fill(-1); for (let i = 0; i < 64; i++) B64I[B64.charCodeAt(i)] = i; B64I[45] = 62; B64I[95] = 63;
  g.btoa = g.btoa || ((s) => { s = String(s); const out = [], parts = [];
    for (let i = 0; i < s.length; i += 3) { const a = s.charCodeAt(i), b = i + 1 < s.length ? s.charCodeAt(i + 1) : 0, c = i + 2 < s.length ? s.charCodeAt(i + 2) : 0;
      out.push(B64.charCodeAt(a >> 2), B64.charCodeAt(((a & 3) << 4) | (b >> 4)), i + 1 < s.length ? B64.charCodeAt(((b & 15) << 2) | (c >> 6)) : 61, i + 2 < s.length ? B64.charCodeAt(c & 63) : 61);
      if (out.length >= 8192) { parts.push(String.fromCharCode.apply(null, out)); out.length = 0; } }
    parts.push(String.fromCharCode.apply(null, out)); return parts.join(""); });
  g.atob = g.atob || ((s) => { s = String(s); const out = [], parts = []; let acc = 0, n = 0;
    for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i), v = c < 128 ? B64I[c] : -1; if (v < 0) continue; acc = (acc << 6) | v; n += 6;
      if (n >= 8) { n -= 8; out.push((acc >> n) & 255); if (out.length >= 8192) { parts.push(String.fromCharCode.apply(null, out)); out.length = 0; } } }
    parts.push(String.fromCharCode.apply(null, out)); return parts.join(""); });
  // fast paths the host bridge uses: base64 <-> bytes without a string in between
  g.__b64u8 = (s) => { const u = new Uint8Array((s.length * 3) >> 2); let acc = 0, n = 0, o = 0; for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i), v = c < 128 ? B64I[c] : -1; if (v < 0) continue; acc = ((acc << 6) | v) & 0xffffff; n += 6; if (n >= 8) { n -= 8; u[o++] = (acc >> n) & 255; } } return o === u.length ? u : u.subarray(0, o); };

  // ---- crypto
  g.crypto = g.crypto || {
    getRandomValues(a) { const h = __rand(a.byteLength); const u = new Uint8Array(a.buffer, a.byteOffset, a.byteLength); for (let i = 0; i < u.length; i++) u[i] = parseInt(h.substr(i * 2, 2), 16); return a; },
    randomUUID() { const h = __rand(16); return h.slice(0, 8) + "-" + h.slice(8, 12) + "-4" + h.slice(13, 16) + "-a" + h.slice(17, 20) + "-" + h.slice(20, 32); },
    subtle: {},
  };

  // ---- URL (enough for absolute http(s) URLs and relative ones against them)
  if (!g.URL) {
    class URLSearchParams {
      constructor(q = "") { this._l = []; if (typeof q === "string") { q = q.replace(/^\?/, ""); if (q) for (const p of q.split("&")) { const i = p.indexOf("="); const d = (x) => decodeURIComponent(x.replace(/\+/g, " ")); this._l.push(i < 0 ? [d(p), ""] : [d(p.slice(0, i)), d(p.slice(i + 1))]); } }
        else if (q && typeof q === "object") for (const [k, v] of (Symbol.iterator in q ? q : Object.entries(q))) this._l.push([String(k), String(v)]); }
      get(k) { const e = this._l.find((x) => x[0] === k); return e ? e[1] : null; } getAll(k) { return this._l.filter((x) => x[0] === k).map((x) => x[1]); }
      has(k) { return this._l.some((x) => x[0] === k); } set(k, v) { const i = this._l.findIndex((x) => x[0] === k); this._l = this._l.filter((x, j) => x[0] !== k || j === i); if (i < 0) this._l.push([k, String(v)]); else this._l[this._l.findIndex((x) => x[0] === k)][1] = String(v); }
      append(k, v) { this._l.push([k, String(v)]); } delete(k) { this._l = this._l.filter((x) => x[0] !== k); } forEach(f) { this._l.forEach(([k, v]) => f(v, k, this)); }
      keys() { return this._l.map((x) => x[0])[Symbol.iterator](); } values() { return this._l.map((x) => x[1])[Symbol.iterator](); } entries() { return this._l.map((x) => [x[0], x[1]])[Symbol.iterator](); }
      [Symbol.iterator]() { return this.entries(); } get size() { return this._l.length; }
      toString() { return this._l.map(([k, v]) => encodeURIComponent(k) + "=" + encodeURIComponent(v)).join("&"); }
    }
    class URL {
      constructor(u, base) {
        u = String(u);
        const m = /^([a-zA-Z][a-zA-Z0-9+.-]*:)(.*)$/.exec(u);
        if (!m) { if (base === undefined) throw new TypeError("Invalid URL: " + u); const b = new URL(base);
          if (u.startsWith("//")) u = b.protocol + u; else if (u.startsWith("/")) u = b.origin + u;
          else if (u.startsWith("?")) u = b.origin + b.pathname + u; else if (u.startsWith("#")) u = b.origin + b.pathname + b.search + u;
          else u = b.origin + b.pathname.replace(/[^/]*$/, "") + u; }
        const p = /^([a-zA-Z][a-zA-Z0-9+.-]*:)(?:\/\/(?:([^:@/]*)(?::([^@/]*))?@)?([^:/?#]*)(?::(\d+))?)?([^?#]*)(\?[^#]*)?(#.*)?$/.exec(u);
        if (!p) throw new TypeError("Invalid URL: " + u);
        this.protocol = p[1].toLowerCase(); this.username = p[2] || ""; this.password = p[3] || ""; this.hostname = (p[4] || "").toLowerCase(); this.port = p[5] || "";
        let path = p[6] || (this.hostname ? "/" : ""); const segs = []; for (const s of path.split("/")) { if (s === "..") segs.pop(); else if (s !== ".") segs.push(s); }
        this.pathname = this.hostname ? (segs.join("/").startsWith("/") ? segs.join("/") : "/" + segs.join("/")) : path;
        this.searchParams = new URLSearchParams(p[7] || ""); this._search = p[7] && p[7] !== "?" ? p[7] : ""; this.hash = p[8] && p[8] !== "#" ? p[8] : "";
      }
      get search() { const s = this.searchParams.toString(); return s ? "?" + s : ""; } set search(v) { this.searchParams = new URLSearchParams(v); }
      get host() { return this.hostname + (this.port ? ":" + this.port : ""); } get origin() { return this.hostname ? this.protocol + "//" + this.host : "null"; }
      get href() { return this.protocol + (this.hostname ? "//" + (this.username ? this.username + (this.password ? ":" + this.password : "") + "@" : "") + this.host : "") + this.pathname + this.search + this.hash; }
      set href(v) { Object.assign(this, new URL(v)); }
      toString() { return this.href; } toJSON() { return this.href; }
      static canParse(u, b) { try { new URL(u, b); return true; } catch { return false; } }
      static createObjectURL() { return "blob:null"; } static revokeObjectURL() {}
    }
    g.URL = URL; g.URLSearchParams = URLSearchParams;
  }
  g.AbortController = g.AbortController || class { constructor() { this.signal = { aborted: false, addEventListener() {}, removeEventListener() {} }; } abort() { this.signal.aborted = true; } };
  g.Blob = g.Blob || class Blob { constructor(p = [], o = {}) { this._p = p; this.type = o.type || ""; } get size() { return this._p.reduce((n, x) => n + (x.length || x.byteLength || 0), 0); } };
})(globalThis);
