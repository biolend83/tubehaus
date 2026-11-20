import { build } from "esbuild";
await build({ entryPoints: ["page-src.mjs"], bundle: true, format: "iife", target: "es2020", platform: "browser", minify: true, legalComments: "none",
  banner: { js: "// built from page-src.mjs (see build.txt); holds googlevideo and bgutils-js (MIT, LuanRT)" }, outfile: "sabr-page.js" });
