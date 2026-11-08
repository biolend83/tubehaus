// node tests/test_search_filters.mjs
import assert from "node:assert/strict";
import { viewLimit, refineSearch, searchSourceSort } from "../web/y/search-filters.js";

const items = [
  { videoId: "a", type: "video", viewCount: 1000, published: 300 },
  { videoId: "b", type: "video", viewCount: null, published: 0 },
  { videoId: "c", type: "video", viewCount: 0, published: 200 },
  { videoId: "d", type: "video", viewCount: 10, published: 100 },
  { videoId: "e", type: "video", published: null },
  { type: "channel", authorId: "ch", viewCount: 1, published: 1 },
];
const ids = (x) => x.map((v) => v.videoId);
assert.deepEqual(ids(refineSearch(items, { sort: "views_asc" })), ["c", "d", "a", "b", "e"]);
assert.deepEqual(ids(refineSearch(items, { sort: "date_asc" })), ["d", "c", "a", "b", "e"]);
assert.deepEqual(ids(refineSearch(items, { sort: "views_asc", min_views: "0", max_views: "10" })), ["c", "d"]);
assert.deepEqual(ids(refineSearch(items, { sort: "relevance", min_views: "10", max_views: "1000" })), ["a", "d"]);
assert.equal(refineSearch(items, { sort: "relevance", min_views: "10", max_views: "2" }).length, 0);
assert.equal(refineSearch(items, { sort: "relevance" }).length, 6);
for (const x of [null, "", "-1", "1.5", "NaN", "Infinity", "1e3", "9007199254740992", "<script>"]) assert.equal(viewLimit(x), "");
assert.equal(viewLimit("0"), "0");
assert.equal(viewLimit("0010"), "10");
assert.equal(searchSourceSort("views_asc"), "relevance");
assert.equal(searchSourceSort("views"), "views");
assert.deepEqual(ids(items).slice(0, 5), ["a", "b", "c", "d", "e"]);   // the list itself is left alone
console.log("search filters: ok");
