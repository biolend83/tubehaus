// Runs engine.js the way the iPad app does (__run / __drain / __reply / __take in a separate
// JavaScript context), against the real YouTube, and checks what comes back.
//   node ios/engine/engine-test.mjs
import fs from "node:fs";
import vm from "node:vm";

const src = fs.readFileSync(new URL("./engine.js", import.meta.url), "utf8");
const ctx = vm.createContext({ console });
vm.runInContext(src, ctx);
const js = (code) => vm.runInContext(code, ctx);

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
let visitor = null;
async function fetchVisitor() {
  const body = { context: { client: { clientName: "WEB", clientVersion: "2.20260708.00.00", hl: "en", gl: "US", userAgent: UA } } };
  const r = await fetch("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", {
    method: "POST", headers: { "Content-Type": "application/json", "User-Agent": UA, "X-YouTube-Client-Name": "1", "X-YouTube-Client-Version": "2.20260708.00.00" },
    body: JSON.stringify(body),
  });
  return (await r.json())?.responseContext?.visitorData ?? null;
}

// what the app does for each request the engine makes
async function answer(req) {
  if (req.kind === "visitor") return { value: visitor ??= await fetchVisitor() };
  if (req.kind === "reroll") { visitor = await fetchVisitor(); return { value: !!visitor }; }
  if (req.kind === "http") {
    try {
      const r = await fetch(req.url, { method: req.method, headers: req.headers, body: req.body ?? undefined });
      return { status: r.status, body: await r.text() };
    } catch (e) { return { error: String(e) }; }
  }
  return { error: "unknown kind " + req.kind };
}

let cid = 0;
async function run(op, ...args) {
  const id = ++cid;
  ctx.__args = JSON.stringify(args);
  ctx.__env = JSON.stringify({ hl: "en", gl: "US", players: null });
  js(`__run(${id}, ${JSON.stringify(op)}, __args, __env)`);
  for (let round = 0; round < 200; round++) {
    await new Promise((r) => setTimeout(r, 0));          // let promises settle, as the app's next call does
    const done = JSON.parse(js(`__take(${id})`));
    if (done.state === "ok") return done.value;
    if (done.state === "error") throw Object.assign(new Error(done.message), done);
    const reqs = JSON.parse(js("__drain()"));
    await Promise.all(reqs.map(async (req) => {
      ctx.__in = JSON.stringify(await answer(req));
      js(`__reply(${req.rid}, __in)`);
    }));
  }
  throw new Error("no answer");
}

const ok = (cond, what) => { console.log((cond ? "  ok   " : "  FAIL ") + what); if (!cond) process.exitCode = 1; };

console.log("engine version", js("ENGINE_VERSION"));
const s = await run("search", "kurzgesagt", null);
ok(s.items.length > 5 && s.next, `search: ${s.items.length} items, next ${!!s.next}, first "${s.items[0]?.title}" (${s.items[0]?.type})`);
const sv = await run("search", "lofi", "EgIQAQ%3D%3D");
ok(sv.items.every((i) => i.type === "video"), `search videos only: ${sv.items.length}`);
const sm = await run("searchMore", s.next);
ok(sm.items.length > 3, `searchMore: ${sm.items.length}`);
const sug = await run("suggestions", "minecr");
ok(sug.length > 3, `suggestions: ${sug.slice(0, 3).join(", ")}`);
const st = await run("stream", "jNQXAC9IVRw");
ok(st.hls && st.title, `stream: "${st.title}" by ${st.author}, hls ${!!st.hls}, ${st.video.length} video + ${st.audio.length} audio formats, client ${st.client}`);
const d = await run("details", "dQw4w9WgXcQ");
ok(d.related.length > 5 && d.commentsToken, `details: ${d.related.length} related, likes ${d.likesText}, subs ${d.subsText}, comments ${!!d.commentsToken}`);
ok(d.title && d.author && d.channelId.startsWith("UC"), `details: title "${d.title}" by ${d.author} (${d.channelId})`);
const c = await run("comments", d.commentsToken);
ok(c.items.length > 5, `comments: ${c.items.length}, first by ${c.items[0]?.author}: "${c.items[0]?.text.slice(0, 40)}"`);
const ch = await run("channel", "UCsXVk37bltHxD1rDPwtNM8Q", "EgZ2aWRlb3PyBgQKAjoA");
ok(ch.name && ch.items.items.length > 5, `channel: ${ch.name} ${ch.handle} ${ch.subsText}, ${ch.items.items.length} videos, next ${!!ch.items.next}`);
const bm = await run("browseMore", ch.items.next);
ok(bm.items.length > 5, `browseMore: ${bm.items.length}`);
const pl = await run("playlist", "PLFs4vir_WsTwEd-nJgVJCZPNL3HALHHpF");
ok(pl.items.items.length > 3, `playlist: "${pl.title}" ${pl.countText}, ${pl.items.items.length} videos`);
const rs = await run("resolve", "@kurzgesagt");
ok(rs && rs[0] === "channel", `resolve @kurzgesagt → ${rs}`);
const fd = await run("feed", "UCsXVk37bltHxD1rDPwtNM8Q");
ok(fd.length > 3 && fd[0].publishedAt > 0, `feed: ${fd.length} videos, newest "${fd[0]?.title}"`);
try { await run("stream", "xxxxxxxxxxx"); ok(false, "bad id should fail"); }
catch (e) { ok(e.kind === "UNAVAILABLE" || e.kind === "OTHER" || e.kind === "LOGIN", `bad id fails cleanly: ${e.kind} "${e.message}"`); }

// v3: what the channel pages, chapters, search filters and comment sorts need
const sf = await run("search", "lofi", "CAMSAhAB");   // sorted by views, videos only (as SearchOptions makes it)
ok(sf.items.length > 5 && sf.items.every((i) => i.type === "video" && i.title), `search with filters: ${sf.items.length}, first "${sf.items[0]?.title}"`);
const sb = await run("stream", "dQw4w9WgXcQ");   // a 19-second video has none
ok(typeof sb.storyboard === "string" && sb.storyboard.includes("$"), `storyboard: ${String(sb.storyboard).slice(0, 50)}`);
ok(d.commentSorts.length >= 2 && d.commentsCount, `comment sorts: ${d.commentSorts.map((x) => x[0]).join(" / ")}, count ${d.commentsCount}`);
const dc = await run("details", "Yocja_N5s1I");
ok(dc.chapters.length >= 3 && dc.chapters[0].startMs === 0, `chapters: ${dc.chapters.length}, "${dc.chapters[1]?.title}" at ${dc.chapters[1]?.startMs} ms`);
const cs = await run("comments", d.commentSorts[1][1]);
ok(cs.items.length > 3, `newest comments: ${cs.items.length}`);
const home = await run("channel", "UCsXVk37bltHxD1rDPwtNM8Q", null);
ok(home.tabs.length > 2 && home.shelves.items.length > 0, `channel home: tabs ${home.tabs.map((t) => t.kind).join(",")}, ${home.shelves.items.length} shelves, featured ${home.featured?.title}, search ${home.searchable}`);
ok(home.description && home.aboutToken, `channel description "${home.description.slice(0, 40)}", link "${home.linkText}" ${home.linkMore}`);
const ab = await run("channelAbout", home.aboutToken);
ok(ab.description && ab.joinedText, `about: ${ab.links.length} links (${ab.links[0]?.url}), ${ab.country}, ${ab.joinedText}, ${ab.viewsText}`);
const csr = await run("channelSearch", "UCsXVk37bltHxD1rDPwtNM8Q", "black hole");
ok(csr.items.length > 2, `channel search: ${csr.items.length}, "${csr.items[0]?.title}"`);
const postsTab = home.tabs.find((t) => t.kind === "posts" || t.kind === "community");
if (postsTab) {
  const pc = await run("channel", "UCsXVk37bltHxD1rDPwtNM8Q", postsTab.params);
  ok(pc.posts.items.length > 2, `posts: ${pc.posts.items.length}, first "${pc.posts.items[0]?.text.slice(0, 40)}", ${pc.posts.items[0]?.images.length} images, next ${!!pc.posts.next}`);
  if (pc.posts.next) { const pm = await run("postsMore", pc.posts.next); ok(pm.items.length > 0, `postsMore: ${pm.items.length}`); }
} else ok(false, "no posts tab");
if (home.shelves.next) { const sh = await run("shelvesMore", home.shelves.next, "UCsXVk37bltHxD1rDPwtNM8Q"); ok(Array.isArray(sh.items), `shelvesMore: ${sh.items.length} (the last token is often a row's own, as on Android)`); }
