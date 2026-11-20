// npm install && node build-vendor.mjs: bundles entry.mjs (jsdom, bgutils-js, googlevideo) into ../vendor.mjs,
// with the license of every package in it in ../vendor.LICENSES.txt. Not minified: BotGuard looks at the code it runs in.
import { build } from "esbuild";
import fs from "node:fs"; import path from "node:path"; import { fileURLToPath } from "node:url";
const out = fileURLToPath(new URL("../vendor.mjs", import.meta.url));
const r = await build({
  entryPoints: ["entry.mjs"], bundle: true, platform: "node", format: "esm", target: "es2022", legalComments: "none",
  external: ["canvas"], metafile: true, write: false,
  banner: { js: "// bundled by server/sabr/build/build-vendor.mjs; licenses in vendor.LICENSES.txt\nimport { createRequire as __cr } from 'node:module'; const require = __cr(import.meta.url);" },
});
let js = r.outputFiles[0].text;
// jsdom finds its synchronous XMLHttpRequest worker file at run time; nothing here makes synchronous requests
const re = /(\w+)\.resolve\("\.\/xhr-sync-worker\.js"\)/g;
if (!re.test(js)) throw new Error("jsdom's worker path not found: check the bundle by hand");
js = js.replace(re, "null");
fs.writeFileSync(out, js);
// the packages the bundle holds, and their licenses
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
fs.writeFileSync(out.replace(/vendor\.mjs$/, "vendor.LICENSES.txt"),
  "The packages bundled in vendor.mjs, and their licenses.\n\n" + list.map((p) => `==== ${p.name} ${p.version} (${p.license})\n\n${p.text || "(no license file in the package; license: " + p.license + ")"}\n`).join("\n"));
console.log("vendor.mjs", (js.length / 1e6).toFixed(1), "MB,", list.length, "packages:", [...new Set(list.map((p) => p.license))].join(", "));
