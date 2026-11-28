// node tests/test_sha256.mjs
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { sha256Hex, sha256Prefix } from "../web/y/sha256.js";

for (const value of ["", "abc", "kJQP7kiw5Fk", "a".repeat(55), "a".repeat(56), "한글"]) {
  const expected = createHash("sha256").update(value).digest("hex");
  assert.equal(sha256Hex(value), expected);
  assert.equal(sha256Prefix(value), expected.slice(0, 4));
}

console.log("sha256: ok");
