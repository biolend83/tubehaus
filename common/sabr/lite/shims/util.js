export const TextEncoder = globalThis.TextEncoder; export const TextDecoder = globalThis.TextDecoder;
export const inspect = (x) => String(x); export const deprecate = (f) => f; export const promisify = (f) => (...a) => new Promise((ok, no) => f(...a, (e, v) => (e ? no(e) : ok(v))));
export const inherits = (c, p) => { Object.setPrototypeOf(c.prototype, p.prototype); Object.setPrototypeOf(c, p); }; export const format = (...a) => a.join(" "); export const debuglog = () => () => {};
export const types = { isPromise: (x) => x instanceof Promise, isUint8Array: (x) => x instanceof Uint8Array, isArrayBufferView: ArrayBuffer.isView };
export const isDeepStrictEqual = (a, b) => JSON.stringify(a) === JSON.stringify(b);
export default { TextEncoder, TextDecoder, inspect, deprecate, promisify, inherits, format, debuglog, types, isDeepStrictEqual };
