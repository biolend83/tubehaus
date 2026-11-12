// "This video at a glance" under the video, as in the apps: made in this browser from the captions (nothing
// is sent anywhere). The words the video keeps coming back to, the moments that say the most (a click jumps
// there) and a search for any word said in it. Plain counting, no AI model.
import { $, esc, icon, fmtTime } from "./util.js";
import { player } from "./core.js";
import { capText } from "./player.js";
import { transcriptCues } from "./watch.js";
import { $t, LANG } from "./i18n.js";

const STOP = new Set(("the and for you that this with are was but not have has had they them his her she him its it's our your from what when where which who how all any can could would should will just like so um uh yeah okay ok really very about there here then than into out get got going gonna know think one two also some more now well right because been being were did does doing don't i'm we're you're that's let's there's music applause laughter "
  + "그리고 그래서 그런데 하지만 그냥 진짜 정말 이제 이거 저거 그거 여기 거기 저기 우리 저는 제가 나는 내가 너무 많이 약간 조금 그런 이런 저런 어떤 있는 없는 있어요 있습니다 합니다 했어요 하는 해서 하고 에서 으로 그게 이게 뭐가 같은 같아요 됩니다 되는 그럼 근데 아니 네네 음악 박수").split(" "));
const PARTICLES = ["에서", "으로", "에게", "까지", "부터", "처럼", "은", "는", "이", "가", "을", "를", "에", "의", "로", "도", "와", "과", "만"];

function words(text) {
  const out = [];
  for (let w of text.toLowerCase().split(/[^\p{L}\p{N}']+/u)) {
    w = w.replace(/^'+|'+$/g, "");
    if (w.length < 2 || /^\d+$/.test(w)) continue;
    if (/^[가-힣]/.test(w) && w.length >= 3) { const p = PARTICLES.find((x) => w.endsWith(x) && w.length - x.length >= 2); if (p) w = w.slice(0, -p.length); }
    if (w.length < 2 || STOP.has(w) || (/^[a-z]/.test(w) && w.length < 3)) continue;
    out.push(w);
  }
  return out;
}

export function analyze(cues, len) {
  if (cues.length < 8) return null;
  const wins = [];
  let cur = null;
  for (const c of cues) {
    if (!cur) { cur = { s: c.s, e: c.e, text: "" }; wins.push(cur); }
    cur.text += (cur.text ? " " : "") + c.text;
    cur.e = c.e;
    if (c.e - cur.s > 18 || /[.?!。]$/.test(c.text)) cur = null;
  }
  for (const w of wins) w.terms = words(w.text);
  const n = wins.length, tf = new Map(), df = new Map();
  for (const w of wins) {
    for (const t of w.terms) tf.set(t, (tf.get(t) || 0) + 1);
    for (const t of new Set(w.terms)) df.set(t, (df.get(t) || 0) + 1);
  }
  const idf = (t) => Math.log(1 + n / (df.get(t) || 1));
  const keywords = [...tf].filter(([t, c]) => c >= 3 && (df.get(t) || 0) < n * 0.6).sort((a, b) => b[1] * idf(b[0]) - a[1] * idf(a[0])).map(([t]) => t).slice(0, 8);
  const weight = new Map([...tf].map(([t, c]) => [t, Math.log(1 + c) * idf(t)]));
  const total = len || wins.at(-1).e;
  const k = Math.max(3, Math.min(6, Math.floor(n / 14)));
  const gap = total / (k * 1.6);
  const scored = wins.filter((w) => w.text.length >= 25).map((w) => {
    const d = new Set(w.terms);
    let s = 0; for (const t of d) s += weight.get(t) || 0;
    return [w, d.size ? s / Math.sqrt(w.terms.length + 4) : 0];
  }).sort((a, b) => b[1] - a[1]);
  const picked = [];
  for (const [w] of scored) { if (picked.length >= k) break; if (picked.every((p) => Math.abs(p.s - w.s) >= gap)) picked.push(w); }
  const moments = picked.sort((a, b) => a.s - b.s).map((w) => ({ s: w.s, text: w.text.length > 160 ? w.text.slice(0, 157).trimEnd() + "…" : w.text }));
  // chapters for a long video that has none: about one every four minutes, each starting at the longest
  // pause near its place, named by the words said most in it
  const chapters = [];
  if (total >= 360 && n >= 20) {
    const parts = Math.max(3, Math.min(8, Math.floor(total / 240)));
    const starts = [0];
    for (let i = 1; i < parts; i++) {
      const target = total * i / parts, reach = total / (parts * 2.5);
      let best = -1, bestGap = -1;
      for (let j = 1; j < n; j++) {
        if (Math.abs(wins[j].s - target) > reach) continue;
        const g = wins[j].s - wins[j - 1].e;
        if (g > bestGap) { bestGap = g; best = j; }
      }
      if (best > starts.at(-1)) starts.push(best);
    }
    starts.forEach((s, ci) => {
      const e = starts[ci + 1] ?? n, local = new Map();
      for (const w of wins.slice(s, e)) for (const t of w.terms) local.set(t, (local.get(t) || 0) + 1);
      const top = [...local].filter(([, c]) => c >= 2).sort((a, b) => b[1] * idf(b[0]) - a[1] * idf(a[0])).slice(0, 2).map(([t]) => t[0].toUpperCase() + t.slice(1));
      chapters.push({ start: ci ? wins[s].s : 0, title: top.join(" · ") || wins[s].text.split(" ").slice(0, 5).join(" ") });
    });
    chapters.forEach((c, i) => (c.end = chapters[i + 1]?.start ?? total));
  }
  return { keywords, moments, chapters: chapters.length >= 3 ? chapters : [] };
}

/** the card, put into `box` once the captions have come */
export async function glance(box, info) {
  let list = [];
  for (let i = 0; i < 12 && box?.isConnected; i++) {   // the player lists the captions a moment after it starts
    list = player.captionList().filter((c) => !c.liveCc && !c.poll);
    if (list.length) break;
    await new Promise((ok) => setTimeout(ok, 700));
  }
  const pick = list.find((c) => c.lang.startsWith(LANG) && !/auto/i.test(c.label)) || list.find((c) => c.lang.startsWith(LANG)) || list[0];
  if (!pick || !box) return;
  let cues;
  try { cues = transcriptCues(await capText(pick, info.videoId)); } catch { return; }
  if (!box.isConnected) return;
  const a = analyze(cues, info.lengthSeconds || 0);
  if (!a || (!a.moments.length && !a.keywords.length)) return;
  const seek = (s) => { player.seek(s); if (player.video.paused) player.video.play().catch(() => { }); };
  // no chapters of its own: the seek bar shows the ones made here
  const made = a.chapters.length && !player.chapters.length && player.info?.videoId === info.videoId;
  if (made) { player.chapters = a.chapters; player.drawSegs(); }
  box.innerHTML = `<section class="glance">
    <button class="gl-h">${icon("sparkle")}<b>${$t("이 영상 한눈에")}</b><span class="gl-more">${$t("더 보기")}</span></button>
    <small class="gl-sub">${$t("이 브라우저에서 자막으로 만들었어요")}</small>
    <div class="gl-kw">${a.keywords.map((k) => `<button class="chip" data-kw="${esc(k)}">#${esc(k)}</button>`).join("")}</div>
    <div class="gl-mo">${a.moments.map((m, i) => `<button class="gl-it${i ? " gl-x" : ""}" data-s="${m.s}"><em>${fmtTime(m.s)}</em><span>${esc(m.text)}</span></button>`).join("")}</div>
    ${made ? `<small class="gl-sub gl-x">${$t("이 영상에 챕터 {0}개를 만들었어요. 재생 막대에 보여요", a.chapters.length)}</small>
    <div class="gl-mo gl-x">${a.chapters.map((c) => `<button class="gl-it" data-s="${c.start}"><em>${fmtTime(c.start)}</em><span>${esc(c.title)}</span></button>`).join("")}</div>` : ""}
    <div class="gl-find gl-x"><input type="search" placeholder="${$t("영상에서 찾기")}" aria-label="${$t("영상에서 찾기")}"><div class="gl-hits"></div></div>
  </section>`;
  const sec = $(".glance", box), input = $(".gl-find input", box), hits = $(".gl-hits", box);
  const find = () => {
    const q = input.value.trim().toLowerCase();
    if (q.length < 2) { hits.innerHTML = ""; return; }
    const seen = new Set();
    const found = cues.filter((c) => c.text.toLowerCase().includes(q) && !seen.has(Math.floor(c.s / 4)) && seen.add(Math.floor(c.s / 4)));
    hits.innerHTML = found.length
      ? `<small>${$t("{0}번 나와요", found.length)}</small>` + found.slice(0, 8).map((c) => `<button class="gl-it" data-s="${c.s}"><em>${fmtTime(c.s)}</em><span>${esc(c.text)}</span></button>`).join("")
      : `<small>${$t("이 영상에서 나오지 않는 말이에요")}</small>`;
  };
  input.addEventListener("input", find);
  sec.addEventListener("click", (e) => {
    const it = e.target.closest("[data-s]");
    if (it) return seek(+it.dataset.s);
    const kw = e.target.closest("[data-kw]");
    if (kw) { sec.classList.add("open"); $(".gl-more", sec).textContent = $t("접기"); input.value = kw.dataset.kw; find(); input.focus(); return; }
    if (e.target.closest(".gl-h")) { const o = sec.classList.toggle("open"); $(".gl-more", sec).textContent = o ? $t("접기") : $t("더 보기"); }
  });
}
