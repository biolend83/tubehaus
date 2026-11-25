// node test.mjs [videoId] [audio|video]: one format of a video through ../sabr-lite.js in QuickJS (quickjs-ng, wasm),
// with a host as the app's JsSabr is (fetch, timers, pieces put in their place); out-<id>-<itag>.mp4 should play.
import fs from "node:fs";
import { newQuickJSWASMModuleFromVariant } from "quickjs-emscripten-core"; import NG from "@jitl/quickjs-ng-wasmfile-release-sync"; const getQuickJS = () => newQuickJSWASMModuleFromVariant(NG);
const vid = process.argv[2] || "aqz-KE-bpKQ", want = process.argv[3] || "audio";
const EJS = new URL("../../ejs/", import.meta.url);
const solver = fs.readFileSync(new URL("yt.solver.lib.min.js", EJS), "utf8") + '\n;var meriyah=lib.meriyah,astring={generate:function(a,o){var w={p:[],write:function(s){this.p.push(s)}};lib.astring.generate(a,Object.assign({},o,{output:w}));return w.p.join("")}};\n' + fs.readFileSync(new URL("yt.solver.core.min.js", EJS), "utf8");

const QJS = await getQuickJS();
const rt = QJS.newRuntime(); rt.setMemoryLimit(1024 << 20);
const vm = rt.newContext();
const t0 = Date.now();
const timers = [];
const fn = (name, f) => vm.newFunction(name, f).consume((h) => vm.setProp(vm.global, name, h));
fn("__log", (...a) => { console.log("[qjs]", ...a.map((h) => vm.dump(h))); });
fn("__timer", (f, ms) => { timers.push({ fn: f.dup(), at: Date.now() + (vm.getNumber(ms) || 0) }); });
fn("__now", () => vm.newNumber(performance.now()));
fn("__rand", (n) => vm.newString(Buffer.from(crypto.getRandomValues(new Uint8Array(vm.getNumber(n)))).toString("hex")));
const events = [];   // host calls into QJS, done from the loop (not inside a host function)
const aborts = new Map();
let netBytes = 0;
fn("__fetchAbort", (id) => { aborts.get(vm.getNumber(id))?.abort(); });
fn("__fetch", (idH, jH) => {
  const id = vm.getNumber(idH), o = JSON.parse(vm.getString(jH));
  const ac = new AbortController(); aborts.set(id, ac); if (process.env.DBG) console.log("fetch", id, o.method, o.url.slice(0, 120));
  (async () => {
    try {
      const r = await fetch(o.url, { method: o.method, headers: o.headers, body: o.body == null ? undefined : Buffer.from(o.body, "base64"), signal: ac.signal, redirect: "follow" });
      events.push(["__thFetchHead", id, r.status, JSON.stringify(Object.fromEntries(r.headers))]);
      for await (const c of r.body) { netBytes += c.length; events.push(["__thFetchData", id, Buffer.from(c).toString("base64")]); }
      events.push(["__thFetchEnd", id, ""]);
    } catch (e) { events.push(["__thFetchEnd", id, String(e.message || e)]); }
    aborts.delete(id);
  })();
});
const pieces = []; let ended = null;
fn("__thHost", (sH) => { const o = JSON.parse(vm.getString(sH)); if (o.t === "piece") pieces.push(o); else if (o.t === "end") ended = o; else console.log("[page]", JSON.stringify(o).slice(0, 200)); });

const run = (code, name) => { const r = vm.evalCode(code, name); if (r.error) { const e = vm.dump(r.error); r.error.dispose(); throw new Error(name + ": " + JSON.stringify(e).slice(0, 800)); } const v = vm.dump(r.value); r.value.dispose(); return v; };
const call = (name, ...args) => { const f = vm.getProp(vm.global, name); const hs = args.map((a) => typeof a === "number" ? vm.newNumber(a) : vm.newString(a)); const r = vm.callFunction(f, vm.undefined, ...hs); hs.forEach((h) => h.dispose()); f.dispose(); if (r.error) { console.log(name, "error", vm.dump(r.error)); r.error.dispose(); } else r.value.dispose(); };
run(fs.readFileSync(new URL("../sabr-lite-polyfill.js", import.meta.url), "utf8"), "polyfill.js");
run(fs.readFileSync(new URL("../sabr-lite.js", import.meta.url), "utf8"), "sabr-lite.js");
console.log("loaded", Date.now() - t0, "ms");
run(`window.__thSabr.setSolver(${JSON.stringify(solver)})`, "solver");

// the loop: host events, pending jobs, due timers
async function pump(until) {
  for (;;) {
    while (events.length) { const [n, ...a] = events.shift(); call(n, ...a); }
    const j = rt.executePendingJobs(); if (j.error) { console.log("job error", vm.dump(j.error)); j.error.dispose(); }
    if (until()) return;
    timers.sort((a, b) => a.at - b.at);
    if (timers.length && timers[0].at <= Date.now()) { const t = timers.shift(); const r = vm.callFunction(t.fn, vm.undefined); t.fn.dispose(); if (r.error) { console.log("timer error", vm.dump(r.error)); r.error.dispose(); } else r.value.dispose(); continue; }
    if (!events.length) await new Promise((r) => setTimeout(r, timers.length ? Math.max(0, Math.min(5, timers[0].at - Date.now())) : 2));
  }
}
const pending = (expr) => { run(`globalThis.__r = undefined; Promise.resolve(${expr}).then((v) => { globalThis.__r = { v }; }, (e) => { globalThis.__r = { e: String(e) + " | " + (e && e.stack) }; }); 1`, "call"); };
const got = () => run("globalThis.__r", "r");

pending(`window.__thSabr.prepare(${JSON.stringify(vid)}, "en", "US")`);
await pump(() => got() !== undefined);
const pr = got(); if (pr.e) { console.log("prepare failed", pr.e); process.exit(1); }
const d = JSON.parse(pr.v);
console.log("prepare", d.playabilityStatus?.status, d.videoDetails?.title, Date.now() - t0, "ms");
const fmts = d.streamingData.adaptiveFormats;
let index = want === "audio" ? fmts.findIndex((f) => f.mimeType.startsWith("audio/mp4")) : fmts.map((f, i) => [f, i]).filter(([f]) => f.mimeType.startsWith("video/mp4")).sort((a, b) => a[0].bitrate - b[0].bitrate)[0][1];
const f = fmts[index], len = +f.contentLength;
console.log("format", index, f.itag, f.mimeType.split(";")[0], f.qualityLabel || "", len, "bytes");
const ts = Date.now();
// as Sabr.kt: a fetch that ends bad (token turned down) starts again from where the pieces got to
for (let runN = 1; runN < 12; runN++) {
  let from = 0; for (const p of pieces) if (!p.init && p.ms >= 0) from = Math.max(from, p.ms);
  ended = null; run(`window.__thSabr.start(${runN}, ${JSON.stringify(vid)}, ${index}, ${from})`, "start");
  await pump(() => ended !== null);
  console.log("run", runN, "from", from, "ms:", JSON.stringify(ended), "pieces so far", pieces.length);
  if (!ended.bad) break;
}
// put the pieces where they belong, as Sabr.kt does, and see how much of YouTube's file is there
const file = Buffer.alloc(len); const have = [];
for (const p of pieces) { const b = Buffer.from(p.data, "base64"); if (p.init) { b.copy(file, 0); have.push([0, b.length]); } else { b.copy(file, p.at); have.push([p.at, p.at + b.length]); } }
have.sort((a, b) => a[0] - b[0]); let cov = 0, end = 0; for (const [a, b] of have) { if (b > end) { cov += b - Math.max(a, end); end = b; } }
fs.writeFileSync(`out-${vid}-${f.itag}.mp4`, file);
const s = (Date.now() - ts) / 1000;
console.log("end", JSON.stringify(ended), "pieces", pieces.length, "have", cov, "of", len, (100 * cov / len).toFixed(1) + "%", s.toFixed(1), "s", (cov / s / 1e6).toFixed(2), "MB/s", "net", netBytes);
process.exit(0);
