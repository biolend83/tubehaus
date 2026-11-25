// jsdom copies CSS strings through Buffer (so cached pieces do not hold big sources); a plain copy does the same here
import fs from "node:fs";
export const plain = { name: "plain-copy", setup(b) {
  b.onLoad({ filter: /css[\\/]utils[\\/]string\.js$/ }, async (a) => {
    let src = fs.readFileSync(a.path, "utf8").replace('const { Buffer } = require("node:buffer");', "");
    src = src.replace(/function copyString\(value\) \{[\s\S]*?\n\}/, 'function copyString(value) { return value.length ? (value + " ").slice(0, -1) : value; }');
    return { loader: "js", contents: src };
  });
} };
// QuickJS's regex parser does not take "\-" in a /u character class (valid since ES2015): the same character as \x2D
export const qjsRegex = { name: "qjs-regex", setup(b) {
  b.onLoad({ filter: /xml-name-validator[\\/]lib[\\/].*\.js$/ }, async (a) => ({ loader: "js", contents: fs.readFileSync(a.path, "utf8").replace(/\\-/g, "\\x2D") }));
} };
