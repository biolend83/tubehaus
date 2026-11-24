// npm install && node build.mjs: ../page-src.mjs with jsdom, googlevideo, bgutils-js and a web streams polyfill,
// for QuickJS (no Node, no browser): ../sabr-lite.js, and ../sabr-lite-polyfill.js (evaluated first).
// The licenses of the packages inside go to ../sabr-lite.LICENSES.txt.
//
// Not minified: BotGuard looks at the code it runs with (minified class names, for one, get its tokens turned
// down). Node's modules are replaced by ./shims: some for real (Buffer, events, crypto, os), the rest as stubs
// for what jsdom never reaches here (network, files, workers). What matters for YouTube taking the token:
//  - os: jsdom's navigator.hardwareConcurrency is os.cpus().length (a stub there and every token is turned down)
//  - jsdom's CSS copies strings through Buffer: a plain copy instead (./plain.mjs)
//  - canvas must be missing, not a stub (jsdom then has no canvas of its own; the app gives BotGuard a 2D one)
import { build } from "esbuild";
import fs from "node:fs"; import path from "node:path";
import { plain, qjsRegex } from "./plain.mjs";

const stubs = "http-proxy-agent https-proxy-agent agent-base debug child_process fs http https path sqlite zlib tls assert string_decoder dns worker_threads bufferutil utf-8-validate querystring punycode tty http2 module readline async_hooks diagnostics_channel".split(" ");
const alias = {};
for (const s of stubs) alias[s] = alias["node:" + s] = "./shims/stubc.cjs";
const own = { os: "os", canvas: "nocanvas", undici: "undici", ws: "ws", stream: "stream", events: "events", url: "url", buffer: "buffer", perf_hooks: "perf_hooks",
  "stream/web": "streamweb", vm: "vm", util: "util", "util/types": "util", net: "net", crypto: "crypto" };
const cjs = new Set(["os", "nocanvas", "undici", "ws", "stream", "events"]);   // CommonJS: what jsdom requires
for (const [m, f] of Object.entries(own)) alias[m] = alias["node:" + m] = `./shims/${f}.${cjs.has(f) ? "cjs" : "js"}`;

const r = await build({
  entryPoints: ["entry.mjs"], bundle: true, format: "iife", platform: "neutral", mainFields: ["module", "main"], conditions: ["import", "default", "require"],
  // QuickJS: no `#x in obj` and other ES2022 syntax yet in every build the apps use
  nodePaths: [path.resolve("node_modules")], // ../page-src.mjs finds its packages here too
  target: "es2020", legalComments: "none", alias, define: { "require.resolve": "String" }, plugins: [plain, qjsRegex], metafile: true, write: false, logLevel: "error",
  banner: { js: "// built by common/sabr/lite/build.mjs from ../page-src.mjs and ./env.mjs; licenses in sabr-lite.LICENSES.txt" },
});
// jsdom looks for its synchronous XMLHttpRequest worker file: nothing here makes synchronous requests
const js = r.outputFiles[0].text.replace(/(\w+)\.resolve\("\.\/xhr-sync-worker\.js"\)/g, "null").replace(/String\("\.\/xhr-sync-worker\.js"\)/g, "null");
fs.writeFileSync("../sabr-lite.js", js);
fs.copyFileSync("polyfill.js", "../sabr-lite-polyfill.js");
const pkgs = new Map();
for (const f of Object.keys(r.metafile.inputs)) {
  const m = f.match(/node_modules\/((?:@[^/]+\/)?[^/]+)/g);
  if (!m) continue;
  const dir = f.slice(0, f.lastIndexOf(m[m.length - 1]) + m[m.length - 1].length);
  if (pkgs.has(dir)) continue;
  const pj = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
  const lic = fs.readdirSync(dir).find((x) => /^(licen[cs]e|copying)/i.test(x));
  pkgs.set(dir, { name: pj.name, version: pj.version, license: pj.license || "", text: lic ? fs.readFileSync(path.join(dir, lic), "utf8").trim() : "" });
}
const list = [...pkgs.values()].sort((a, b) => a.name.localeCompare(b.name));
fs.writeFileSync("../sabr-lite.LICENSES.txt", "The packages bundled in sabr-lite.js, and their licenses.\n\n" +
  list.map((p) => `==== ${p.name} ${p.version} (${p.license})\n\n${p.text || "(no license file in the package; license: " + p.license + ")"}\n`).join("\n"));
console.log("sabr-lite.js", (js.length / 1e6).toFixed(1), "MB,", list.length, "packages:", [...new Set(list.map((p) => p.license))].join(", "));
