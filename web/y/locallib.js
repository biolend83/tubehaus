// The library (subscriptions, history, resume points, playlists) kept in this browser.
// Used on public instances (HT_PUBLIC_MODE=1), where the server keeps nothing about its
// visitors. Same paths and answers as the server's /api/p/yt/* so the rest of the app
// doesn't need to know which one it talks to.
const KEY = "yt.library";
const VID = /^[\w-]{11}$/, UCID = /^UC[\w-]{22}$/, PLID = /^HP[0-9a-f]{16}$/;
// only our own playlist IDs ever index the playlists object (no "__proto__" and friends)
const plOf = (d, id) => (PLID.test(String(id)) && Object.hasOwn(d.playlists, id) ? d.playlists[id] : null);
const now = () => Math.floor(Date.now() / 1000);

function load() {
  let d = {};
  try { d = JSON.parse(localStorage.getItem(KEY) || "{}") || {}; } catch { }
  for (const [k, v] of [["subs", {}], ["hist", []], ["pos", {}], ["wl", []], ["likes", []], ["dislikes", []], ["playlists", {}]]) d[k] ??= v;
  return d;
}
function save(d) {
  try { localStorage.setItem(KEY, JSON.stringify(d)); }
  catch {
    // storage full: drop the oldest half of the history and try once more
    d.hist = d.hist.slice(0, Math.floor(d.hist.length / 2));
    try { localStorage.setItem(KEY, JSON.stringify(d)); } catch { }
  }
}

// same rules as the server's _yt_meta
export function meta(v) {
  v = v || {};
  const out = { id: String(v.id || "").slice(0, 11) };
  for (const k of ["title", "author", "authorId"]) out[k] = String(v[k] || "").slice(0, 300);
  for (const k of ["len", "published", "views"]) out[k] = Math.floor(+v[k] || 0);
  if (v.live) out.live = true;
  return VID.test(out.id) ? out : null;
}
const summary = (pl) => ({ title: pl.title || "", count: pl.items.length, first: pl.items[0]?.id || null, updated: pl.updated || 0, ids: pl.items.map((x) => x.id) });
const fail = (status, error) => { const e = new Error(error); e.status = status; throw e; };

export function libGet() {
  const d = load();
  return { subs: d.subs, wl: d.wl, likes: d.likes, dislikes: d.dislikes, playlists: Object.fromEntries(Object.entries(d.playlists).map(([k, v]) => [k, summary(v)])) };
}

export function libQuery(path, qs) {
  const d = load();
  if (path === "pos") {
    const ids = (qs.get("ids") || "").split(",").filter((i) => VID.test(i)).slice(0, 200);
    return Object.fromEntries(ids.filter((i) => d.pos[i]).map((i) => [i, d.pos[i].slice(0, 2)]));
  }
  if (path === "hist") {
    const n = Math.min(+qs.get("n") || 40, 200), o = +qs.get("o") || 0, q = (qs.get("q") || "").trim().toLowerCase();
    const h = d.hist.filter((x) => !q || `${x.title || ""} ${x.author || ""}`.toLowerCase().includes(q));
    return { items: h.slice(o, o + n), total: h.length };
  }
  if (path === "pl") return plOf(d, qs.get("id")) || fail(404, "Playlist not found");
  return fail(404, "not found");
}

export function libPost(path, b) {
  const d = load(), t = now();
  const res = { ok: true };
  if (path === "pos") {
    const vid = String(b.id || "");
    if (!VID.test(vid)) fail(400, "bad id");
    d.pos[vid] = [Math.floor(+b.t || 0), Math.floor(+b.len || 0), t];
    const keys = Object.keys(d.pos);
    if (keys.length > 5000) keys.sort((a, c) => d.pos[a][2] - d.pos[c][2]).slice(0, keys.length - 5000).forEach((k) => delete d.pos[k]);
    const m = b.meta ? meta(b.meta) : null, old = d.hist.find((h) => h.id === vid);
    if (m || old) d.hist = [{ ...(old || {}), ...(m || {}), at: t, id: vid }, ...d.hist.filter((x) => x.id !== vid)].slice(0, 3000);
  } else if (path === "sub") {
    const cid = String(b.id || "");
    if (!UCID.test(cid)) fail(400, "bad channel");
    if (b.on) d.subs[cid] = { name: String(b.name || cid).slice(0, 200), thumb: String(b.thumb || "").slice(0, 500), at: t };
    else delete d.subs[cid];
  } else if (path === "subs/import") {
    let added = 0;
    for (const x of (b.list || []).slice(0, 2000)) {
      const cid = String(x.id || "");
      if (UCID.test(cid) && !d.subs[cid]) { d.subs[cid] = { name: String(x.name || cid).slice(0, 200), thumb: String(x.thumb || "").slice(0, 500), at: t }; added++; }
    }
    res.added = added;
  } else if (path === "list") {
    const lst = String(b.list || ""), on = !!b.on, m = meta(b.video);
    if (!m) fail(400, "bad video");
    if (lst === "wl" || lst === "likes") {
      d[lst] = d[lst].filter((x) => x.id !== m.id);
      if (on) d[lst].unshift(m);
      d[lst] = d[lst].slice(0, 5000);
    } else if (lst === "dislikes") {
      d.dislikes = [...d.dislikes.filter((x) => x !== m.id), ...(on ? [m.id] : [])];
    } else if (plOf(d, lst)) {
      const pl = plOf(d, lst);
      pl.items = pl.items.filter((x) => x.id !== m.id);
      if (on) pl.items.push(m);
      pl.updated = t;
      res.playlist = summary(pl);
    } else fail(404, "Playlist not found");
  } else if (path === "pl/new") {
    const title = String(b.title || "").trim().slice(0, 150);
    if (!title) fail(400, "Please enter a name");
    const pid = "HP" + [...crypto.getRandomValues(new Uint8Array(8))].map((x) => x.toString(16).padStart(2, "0")).join("");
    const m = b.video ? meta(b.video) : null;
    d.playlists[pid] = { title, items: m ? [m] : [], updated: t };
    Object.assign(res, { id: pid, playlist: summary(d.playlists[pid]) });
  } else if (path === "pl/del") {
    if (plOf(d, b.id)) delete d.playlists[b.id];
  } else if (path === "pl/rename") {
    const pl = plOf(d, b.id) || fail(404, "Playlist not found");
    pl.title = String(b.title || pl.title).trim().slice(0, 150);
    pl.updated = t;
    res.playlist = summary(pl);
  } else if (path === "hist/del") {
    if (b.all) { d.hist = []; d.pos = {}; }
    else { d.hist = d.hist.filter((x) => x.id !== String(b.id || "")); delete d.pos[String(b.id || "")]; }
  } else if (path === "pos/del") {    // resume points only: the history stays
    if (b.all) d.pos = {};
    else if (Array.isArray(b.ids)) b.ids.forEach((id) => delete d.pos[String(id)]);
    else fail(400, "bad ids");
  } else if (path === "shorts/sig" || path === "shorts/reset") {
    return res;                       // no learning on public instances
  } else fail(404, "not found");
  save(d);
  return res;
}

// an import parsed by the server (dry run), merged here like the server would
export function libMerge(p) {
  const d = load(), t = now();
  const n = { format: p.format, subs: 0, hist: 0, playlists: 0 };
  for (const [cid, s] of Object.entries(p.subs || {})) if (!d.subs[cid]) { d.subs[cid] = { ...s, at: t }; n.subs++; }
  const have = new Set(d.hist.map((h) => h.id));
  const fresh = (p.hist || []).filter((h) => !have.has(h.id));
  if (fresh.length) {
    d.hist = [...d.hist, ...new Map(fresh.map((h) => [h.id, { ...h, at: h.at || t }])).values()].sort((a, c) => (c.at || 0) - (a.at || 0)).slice(0, 3000);
    n.hist = fresh.length;
  }
  for (const lst of ["wl", "likes"]) {
    const ids = new Set(d[lst].map((x) => x.id));
    d[lst] = [...d[lst], ...(p[lst] || []).filter((m) => !ids.has(m.id))].slice(0, 5000);
  }
  d.dislikes = [...new Set([...d.dislikes, ...(p.dislikes || [])])];
  for (const [k, v] of Object.entries(p.pos || {})) if (!d.pos[k] || d.pos[k][2] < v[2]) d.pos[k] = v;
  const seen = new Set(Object.values(d.playlists).map((pl) => pl.title + "\n" + pl.items.map((x) => x.id).join(",")));
  for (const pl of p.playlists || []) {
    if (!pl.items?.length || seen.has(pl.title + "\n" + pl.items.map((x) => x.id).join(","))) continue;
    const pid = PLID.test(pl.id || "") && !Object.hasOwn(d.playlists, pl.id) ? pl.id
      : "HP" + [...crypto.getRandomValues(new Uint8Array(8))].map((x) => x.toString(16).padStart(2, "0")).join("");
    d.playlists[pid] = { title: pl.title || "Imported", items: pl.items.slice(0, 5000), updated: t };
    n.playlists++;
  }
  save(d);
  return n;
}

export const libAll = () => load();
export const subIds = () => Object.keys(load().subs);
