const c = () => globalThis.crypto;
export const webcrypto = globalThis.crypto;
export const randomUUID = () => c().randomUUID();
export const getRandomValues = (a) => c().getRandomValues(a);
export const randomFillSync = (a, off = 0, size) => { const u = new Uint8Array(a.buffer || a, (a.byteOffset || 0) + off, size ?? (a.byteLength - off)); c().getRandomValues(u); return a; };
export const randomBytes = (n) => c().getRandomValues(new Uint8Array(n));
export const randomInt = (a, b) => { if (b === undefined) { b = a; a = 0; } return a + Math.floor(Math.random() * (b - a)); };
export default { webcrypto, randomUUID, getRandomValues, randomFillSync, randomBytes, randomInt };
