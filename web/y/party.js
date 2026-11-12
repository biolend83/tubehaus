// Watch together: a room on this server that keeps everyone on the same video and moment. Open one from
// the watch page (or join with its six-letter code / link); whoever plays, pauses, seeks or opens another
// video moves everyone, and there is a short chat beside it. The server keeps rooms in memory only.
import { $, esc, icon, api, local, toast, el } from "./util.js";
import { player, navigate, popup, mi } from "./core.js";
import { watchState as W } from "./watch.js";
import { $t } from "./i18n.js";

const P = { code: "", me: "", seq: 0, state: null, members: [], chat: [], box: null, polling: false, applying: 0, skew: 0, bound: false, reactAt: 0 };
const REACTS = ["❤️", "😂", "😮", "👏", "🔥"];
const saved = () => { try { return JSON.parse(sessionStorage.getItem("party") || "null"); } catch { return null; } };
const keep = () => { try { sessionStorage.setItem("party", JSON.stringify(P.code ? { code: P.code, me: P.me } : null)); } catch { } };

async function myName() {
  let n = local.get("party.name", "");
  if (n) return n;
  try { const d = await api("/api/p/auth/me", { quiet: true }); if (d && d.user) n = d.user; } catch { }
  if (!n) n = (prompt($t("같이 볼 때 보일 이름")) || "").trim().slice(0, 30);
  if (n) local.set("party.name", n);
  return n || $t("손님");
}

const now = () => Date.now() / 1000 + P.skew;
const shareUrl = () => `${location.origin}/watch?v=${encodeURIComponent(P.state?.v || W.id || "")}&party=${P.code}`;

/** the button on the watch page: open a room, join one, or (in one) show it */
export function partyMenu(anchor) {
  if (P.code) { panel(true); return; }
  popup(anchor, mi("new", "add", $t("같이 보기 방 만들기")) + mi("join", "link", $t("코드로 들어가기")), async (a) => {
    if (a === "new") create();
    else if (a === "join") {
      const code = (prompt($t("방 코드 6자리")) || "").trim().toUpperCase();
      if (code) join(code);
    }
  });
}

async function create() {
  const v = player.video;
  try {
    const d = await api("/api/p/yt/party/create", { method: "POST", body: { v: W.id, t: v.currentTime || 0, playing: !v.paused, name: await myName() } });
    took(d);
    toast($t("방을 만들었어요. 링크를 보내 친구를 초대하세요"), $t("링크 복사"), copyLink, 8000);
  } catch (e) { toast(e.message); }
}

async function join(code) {
  try {
    const old = saved();
    const d = await api("/api/p/yt/party/join", { method: "POST", body: { code, name: await myName(), me: old && old.code === code ? old.me : "" } });
    took(d);
    follow();
  } catch (e) { toast(e.message); P.code = ""; keep(); }
}

/** just came in (or opened the room's video): follow the room, not the player's own autoplay or resume point,
 *  and say nothing to the others until it has caught up */
function follow() {
  P.quiet = Date.now() + 5000;
  apply(P.state, true);
  for (const ms of [1200, 2800, 4500]) setTimeout(() => { if (P.code) apply(P.state, true); }, ms);
}

function took(d) {
  P.code = d.code; P.me = d.me; P.seq = d.seq; P.state = d.state; P.members = d.members; P.chat = d.chat;
  if (d.now) P.skew = d.now - Date.now() / 1000;
  P.reactAt = d.now || Date.now() / 1000;
  keep();
  bind();
  panel(true);
  poll();
}

async function leave() {
  const code = P.code, me = P.me;
  P.code = ""; P.me = ""; keep();
  P.box?.remove(); P.box = null;
  try { await api("/api/p/yt/party/update", { method: "POST", body: { code, me, leave: true }, quiet: true }); } catch { }
  toast($t("같이 보기에서 나왔어요"));
}

function copyLink() {
  navigator.clipboard?.writeText(shareUrl()).then(() => toast($t("링크를 복사했어요")), () => prompt($t("이 링크를 보내세요"), shareUrl()));
}

// ------------------------------------------------------------------ keeping in step
async function poll() {
  if (P.polling) return;
  P.polling = true;
  while (P.code) {
    try {
      const d = await api(`/api/p/yt/party/wait?code=${P.code}&me=${P.me}&since=${P.seq}`, { quiet: true });
      if (!P.code) break;
      if (d.now) P.skew = d.now - Date.now() / 1000;
      const moved = d.state && (!P.state || d.state.at !== P.state.at);
      P.seq = d.seq; P.members = d.members; P.chat = d.chat;
      for (const x of d.reacts || []) if (x.at > P.reactAt) { P.reactAt = x.at; if (x.from !== P.me) float(x.e); }
      if (moved) { P.state = d.state; if (d.state.from !== P.me) apply(d.state); }
      paint();
    } catch (e) {
      if (e.status === 404) { P.code = ""; keep(); P.box?.remove(); P.box = null; toast($t("같이 보기 방이 닫혔어요")); break; }
      await new Promise((ok) => setTimeout(ok, 3000));
    }
  }
  P.polling = false;
}

/** someone else moved: the same video, the same moment, playing or paused as they have it */
function apply(st, first = false) {
  if (!st) return;
  if (st.v !== W.id) { if (P.going !== st.v) { P.going = st.v; navigate(`/watch?v=${st.v}&party=${P.code}`); } return; }
  const v = player.video;
  const at = st.t + (st.playing ? Math.max(0, now() - st.at) : 0);
  P.applying = Date.now();
  if (Math.abs((v.currentTime || 0) - at) > 1.5) player.seek(at);
  if (st.playing && v.paused) v.play().catch(() => { });
  else if (!st.playing && !v.paused) v.pause();
  if (!first && st.by) toast($t("{0}님이 {1}", st.by, st.playing ? $t("재생했어요") : $t("멈췄어요")), null, null, 1800);
}

/** what this player did, for the others (not what it did because of them) */
function send() {
  if (!P.code || Date.now() - P.applying < 1200 || Date.now() < (P.quiet || 0) || !W.id) return;
  const v = player.video, st = P.state;
  // already where the room is (a late echo of a change that came from the room): nothing to tell
  if (st && st.v === W.id && st.playing === !v.paused && Math.abs((v.currentTime || 0) - (st.t + (st.playing ? Math.max(0, now() - st.at) : 0))) < 1.5) return;
  api("/api/p/yt/party/update", { method: "POST", body: { code: P.code, me: P.me, v: W.id, t: v.currentTime || 0, playing: !v.paused }, quiet: true })
    .catch(() => { });
}

function bind() {
  if (P.bound) return;
  P.bound = true;
  const v = player.video;
  let timer = 0;
  const soon = () => { clearTimeout(timer); timer = setTimeout(send, 250); };
  v.addEventListener("play", soon);
  v.addEventListener("pause", soon);
  v.addEventListener("seeked", soon);
}

/** the watch page opened a video: in a room, it is the room's video now (or it came from the room) */
export function onWatch(id, code) {
  P.going = "";
  if (code && code !== P.code) { join(code.toUpperCase()); return; }
  if (!P.code) { const s = saved(); if (s && s.code) { join(s.code); return; } return; }
  panel(false);
  if (P.state && P.state.v === id) { follow(); return; }
  // this one was opened here: everyone comes along (once it has started)
  setTimeout(send, 1500);
}

// ------------------------------------------------------------------ the panel
function panel(open) {
  if (!P.code) return;
  if (!P.box) {
    P.box = el(`<div id="party" role="region" aria-label="${$t("같이 보기")}">
      <div class="py-head"><button class="py-fold" aria-label="${$t("접기")}">${icon("person")}<b></b><span class="py-n"></span></button>
        <button class="ib py-link" title="${$t("링크 복사")}">${icon("link")}</button>
        <button class="ib py-x" title="${$t("나가기")}">${icon("close")}</button></div>
      <div class="py-body">
        <div class="py-who"></div>
        <div class="py-chat" aria-live="polite"></div>
        <div class="py-react" role="group" aria-label="${$t("반응 보내기")}">${REACTS.map((e) => `<button class="ib" data-e="${e}">${e}</button>`).join("")}</div>
        <form class="py-say"><input maxlength="300" placeholder="${$t("메시지 보내기")}" aria-label="${$t("메시지 보내기")}"><button class="ib">${icon("right")}</button></form>
      </div></div>`);
    document.body.append(P.box);
    $(".py-fold", P.box).onclick = () => P.box.classList.toggle("folded");
    $(".py-link", P.box).onclick = copyLink;
    $(".py-x", P.box).onclick = leave;
    $(".py-react", P.box).onclick = (e) => {
      const b = e.target.closest("[data-e]");
      if (!b) return;
      float(b.dataset.e);
      api("/api/p/yt/party/update", { method: "POST", body: { code: P.code, me: P.me, react: b.dataset.e }, quiet: true }).catch(() => { });
    };
    $(".py-say", P.box).onsubmit = (e) => {
      e.preventDefault();
      const i = $("input", P.box), text = i.value.trim();
      if (!text) return;
      i.value = "";
      api("/api/p/yt/party/update", { method: "POST", body: { code: P.code, me: P.me, say: text }, quiet: true }).catch((er) => toast(er.message));
    };
  }
  if (open) P.box.classList.remove("folded");
  paint();
}

let shown = 0;
function paint() {
  const b = P.box;
  if (!b) return;
  $(".py-head b", b).textContent = $t("같이 보기") + " · " + P.code;
  $(".py-n", b).textContent = $t("{0}명 보는 중", P.members.length);
  $(".py-who", b).innerHTML = P.members.map((n) => `<span class="chip">${esc(n)}</span>`).join("");
  const chat = $(".py-chat", b);
  chat.innerHTML = P.chat.length ? P.chat.map((m) => `<p><b>${esc(m.name)}</b> ${esc(m.text)}</p>`).join("")
    : `<p class="py-empty">${$t("링크를 보내 친구를 부르세요. 누가 재생·멈춤·이동해도 모두 같이 움직여요.")}</p>`;
  chat.scrollTop = chat.scrollHeight;
  if (P.chat.length > shown && shown && b.classList.contains("folded")) toast(`${P.chat.at(-1).name}: ${P.chat.at(-1).text}`, null, null, 2500);
  shown = P.chat.length;
}

/** a reaction rises over the video (anyone's, this one's at once) */
function float(e) {
  const host = $(".w-playerbox") || document.body;
  host.classList.add("py-fx-host");
  const fx = el(`<span class="py-fx" aria-hidden="true">${esc(e)}</span>`);
  fx.style.left = 55 + Math.random() * 35 + "%";
  host.append(fx);
  setTimeout(() => fx.remove(), 2500);
}
