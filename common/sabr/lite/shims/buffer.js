// the part of Node's Buffer that jsdom and its packages use: utf8, utf16le, latin1, base64, hex
const enc = (e) => String(e || "utf8").toLowerCase().replace("-", "");
function encode(s, e) {
  s = String(s); e = enc(e);
  if (e === "utf16le" || e === "ucs2") { const u = new Uint8Array(s.length * 2); for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); u[2 * i] = c & 255; u[2 * i + 1] = c >> 8; } return u; }
  if (e === "latin1" || e === "binary" || e === "ascii") { const u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i) & 255; return u; }
  if (e === "base64" || e === "base64url") { const b = atob(s.replace(/-/g, "+").replace(/_/g, "/")); return encode(b, "latin1"); }
  if (e === "hex") { const u = new Uint8Array(s.length >> 1); for (let i = 0; i < u.length; i++) u[i] = parseInt(s.substr(2 * i, 2), 16); return u; }
  return new TextEncoder().encode(s);
}
function decode(u, e) {
  e = enc(e);
  const chunk = (arr) => { const parts = []; for (let i = 0; i < arr.length; i += 8192) parts.push(String.fromCharCode.apply(null, arr.slice(i, i + 8192))); return parts.join(""); };
  if (e === "utf16le" || e === "ucs2") { const a = new Array(u.length >> 1); for (let i = 0; i < a.length; i++) a[i] = u[2 * i] | (u[2 * i + 1] << 8); return chunk(a); }
  if (e === "latin1" || e === "binary" || e === "ascii") return chunk(Array.from(u));
  if (e === "base64") return btoa(chunk(Array.from(u)));
  if (e === "base64url") return btoa(chunk(Array.from(u))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  if (e === "hex") return Array.from(u, (b) => b.toString(16).padStart(2, "0")).join("");
  return new TextDecoder().decode(u);
}
export class Buffer extends Uint8Array {
  static from(x, a, b) {
    if (typeof x === "string") { const u = encode(x, a); const r = new Buffer(u.length); r.set(u); return r; }
    if (x instanceof ArrayBuffer) return new Buffer(x, a || 0, b ?? x.byteLength - (a || 0));
    const r = new Buffer(x.length); r.set(x); return r;
  }
  static alloc(n, f) { const r = new Buffer(n); if (f) r.fill(typeof f === "number" ? f : 0); return r; }
  static allocUnsafe(n) { return new Buffer(n); } static allocUnsafeSlow(n) { return new Buffer(n); }
  static isBuffer(x) { return x instanceof Buffer; } static byteLength(s, e) { return typeof s === "string" ? encode(s, e).length : s.byteLength; }
  static concat(list, n) { n = n ?? list.reduce((a, b) => a + b.length, 0); const r = new Buffer(n); let o = 0; for (const b of list) { r.set(b.subarray(0, Math.min(b.length, n - o)), o); o += b.length; if (o >= n) break; } return r; }
  static isEncoding(e) { return ["utf8", "utf16le", "ucs2", "latin1", "binary", "ascii", "base64", "base64url", "hex"].includes(enc(e)); }
  toString(e, start = 0, end = this.length) { return decode(this.subarray(start, end), e); }
  write(s, off = 0, len, e) { if (typeof off === "string") { e = off; off = 0; len = undefined; } else if (typeof len === "string") { e = len; len = undefined; }
    const u = encode(s, e); const n = Math.min(u.length, len ?? this.length - off, this.length - off); this.set(u.subarray(0, n), off); return n; }
  slice(a, b) { const s = this.subarray(a, b); return new Buffer(s.buffer, s.byteOffset, s.length); }
  equals(o) { if (o.length !== this.length) return false; for (let i = 0; i < this.length; i++) if (o[i] !== this[i]) return false; return true; }
  toJSON() { return { type: "Buffer", data: Array.from(this) }; }
}
export const isAscii = (u) => { for (let i = 0; i < u.length; i++) if (u[i] > 127) return false; return true; };
export const isUtf8 = () => true;
export const Blob = globalThis.Blob || class Blob {};
export const constants = { MAX_LENGTH: 2 ** 31 - 1, MAX_STRING_LENGTH: 2 ** 29 - 24 };
export default { Buffer, isAscii, isUtf8, Blob, constants };
