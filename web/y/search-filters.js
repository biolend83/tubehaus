// Search refinements the search itself doesn't offer: fewest views first, oldest first, and a view count range.
// They work on the results loaded so far (the search still finds the videos), so they are videos only.
// (from Vildor Pierre's fork, micronlogivdev/tubehaus)
export const LOCAL_SORTS = new Set(["views_asc", "date_asc"]);

/** A view count limit from the address: whole numbers only, anything else is "" (no limit). */
export function viewLimit(value) {
  if (value == null || !/^\d+$/.test(String(value))) return "";
  const n = Number(value);
  return Number.isSafeInteger(n) ? String(n) : "";
}

const known = (x) => (x == null || x === "" || !Number.isFinite(Number(x)) || Number(x) < 0 ? null : Number(x));

export function refineSearch(items, { sort, min_views = "", max_views = "" }) {
  const ranged = min_views !== "" || max_views !== "";
  const out = items.filter((x) => {
    if ((ranged || LOCAL_SORTS.has(sort)) && !(x.type === "video" || x.type === "shortVideo" || (!x.type && x.videoId))) return false;
    if (!ranged) return true;
    const v = known(x.viewCount);
    return v !== null && (min_views === "" || v >= +min_views) && (max_views === "" || v <= +max_views);
  });
  if (LOCAL_SORTS.has(sort)) {
    const key = sort === "views_asc" ? "viewCount" : "published";
    // an unknown count or date goes last; an upload date of 0 is unknown, not the oldest
    const val = (x) => { const v = known(x[key]); return key === "published" && v === 0 ? null : v; };
    out.sort((a, b) => { const x = val(a), y = val(b); return x === null ? (y === null ? 0 : 1) : y === null ? -1 : x - y; });
  }
  return out;
}

/** What to ask the search for: a "most viewed" list turned around would never find the little-watched videos. */
export const searchSourceSort = (sort) => (LOCAL_SORTS.has(sort) ? "relevance" : sort);
