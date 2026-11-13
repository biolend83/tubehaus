// TV mode: use it with nothing but an Android TV remote (arrows, OK, back)
// Arrows = move to the nearest item (spatial navigation), OK = click, Back = previous screen.
// On the watch page the player fills the screen: left/right = 10 s, OK = play/pause, down = into the control bar.
import { $t } from "./i18n.js";
import { $, $$, settings, toast, icon } from "./util.js";
import { player, state, prefetch, closePopup } from "./core.js";

const UA = navigator.userAgent;
export function isTV() {
  const s = settings.tv || "auto";
  if (s === "on") return true;
  if (s === "off") return false;
  if (/TubeHausTV|HomeTubeTV|Android TV|GoogleTV|Google TV|AFT[A-Z]|BRAVIA|SMART-TV|SmartTV|HbbTV|Tizen|Web0S|webOS|TV Bro|Puffin.*TV|CrKey|AndroidTV/i.test(UA)) return true;
  // a large-screen Android with neither touch nor mouse = a TV
  return /Android/i.test(UA) && !/Mobile/i.test(UA) && matchMedia("(pointer: none)").matches && screen.width >= 960;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([type=hidden]), select, [data-tvfocus]';
function visible(el) {
  const r = el.getBoundingClientRect();
  if (r.width < 4 || r.height < 4) return false;
  const cs = getComputedStyle(el);
  return cs.visibility !== "hidden" && cs.display !== "none" && cs.opacity !== "0";
}
function scope() {
  // stay inside an open dialog, popup or menu
  if ($("#modal")?.classList.contains("show")) return $("#modal");
  if ($("#popup")?.classList.contains("show")) return $("#popup");
  if (document.body.classList.contains("guide-open")) return $("#guide");
  const pm = player.root.querySelector(".ytp-menu.show");
  if (pm) return pm;
  return document;
}
function candidates() {
  const root = scope();
  return $$(FOCUSABLE, root).filter((el) => {
    if (!visible(el)) return false;
    if (root === document && el.closest("#guide, #modal, #popup")) return false;
    // one stop per card, on the thumbnail (stopping on title and channel links too means far too many presses)
    const card = el.closest(".card");
    if (card && !card.classList.contains("chan") && !el.matches(".th")) return false;
    // buttons inside the player are handled by the player (only an open menu is walked here)
    if (el.closest(".ytp") && root === document) return false;
    return true;
  });
}
const mid = (r) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
function move(dir) {
  const list = candidates();
  if (!list.length) return false;
  let cur = document.activeElement;
  if (!cur || cur === document.body || !list.includes(cur)) {
    const first = list.find((el) => { const r = el.getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; }) || list[0];
    focusEl(first);
    return true;
  }
  const a = cur.getBoundingClientRect(), ac = mid(a);
  let best = null, bestScore = Infinity;
  for (const el of list) {
    if (el === cur) continue;
    const b = el.getBoundingClientRect(), bc = mid(b);
    let along, across;
    if (dir === "right") { if (b.left < a.right - 8) continue; along = b.left - a.right; across = Math.abs(bc.y - ac.y); }
    else if (dir === "left") { if (b.right > a.left + 8) continue; along = a.left - b.right; across = Math.abs(bc.y - ac.y); }
    else if (dir === "down") { if (b.top < a.bottom - 8) continue; along = b.top - a.bottom; across = Math.abs(bc.x - ac.x); }
    else { if (b.bottom > a.top + 8) continue; along = a.top - b.bottom; across = Math.abs(bc.x - ac.x); }
    // strongly prefer items in the same row / column
    const score = Math.max(0, along) + across * (dir === "left" || dir === "right" ? 3 : 1.2);
    if (score < bestScore) { bestScore = score; best = el; }
  }
  if (!best) return false;
  focusEl(best);
  return true;
}
function focusEl(el) {
  el.focus({ preventScroll: true });
  const r = el.getBoundingClientRect();
  // scroll it to about the middle (so the header does not cover it)
  if (r.top < 80 || r.bottom > innerHeight - 40) el.scrollIntoView({ block: "center", inline: "nearest", behavior: "smooth" });
  const id = el.closest("[data-vid]")?.dataset.vid;
  if (id) prefetch(id);
}

// close the top layer first; with nothing left to close go back. On the first screen return "exit" (the TV app quits)
function back() {
  if ($("#modal")?.classList.contains("show")) { $("#modal").className = ""; $("#modal").innerHTML = ""; return "ok"; }
  if ($("#popup")?.classList.contains("show")) { closePopup(); return "ok"; }
  if (player.root.querySelector(".ytp-menu.show")) { player.closeMenu(); (player.root.querySelector(".b-set") || player.root).focus(); player.showCtl(true); return "ok"; }
  if (inBar()) { player.root.focus(); player.autoHide(); return "ok"; }
  if (document.body.classList.contains("guide-open")) { document.body.classList.remove("guide-open"); return "ok"; }
  if (document.fullscreenElement) { document.exitFullscreen(); return "ok"; }
  if (history.state?.k && history.state.k !== "first") { history.back(); return "ok"; }
  return "exit";
}
// the TubeHaus TV app (Android) calls this when the remote's Back is pressed
window.__tvBack = back;

// Samsung (Tizen) and LG (webOS) TV apps: the media keys reach a page only once asked for, and Back on
// the first screen closes the app
try { window.tizen?.tvinputdevice?.registerKeyBatch(["MediaPlayPause", "MediaPlay", "MediaPause", "MediaFastForward", "MediaRewind"]); } catch { }
function tvExit() {
  try { window.tizen?.application?.getCurrentApplication().exit(); return; } catch { }
  if (window.webOSSystem || window.PalmSystem) { try { window.close(); } catch { } }
}

// ---- player control bar (settings, captions, fullscreen …): enter with down, pick with left/right, press with OK
const bar = () => player.root.querySelector(".ytp-bar");
const inBar = () => !!bar()?.contains(document.activeElement);
const barButtons = () => [...bar().querySelectorAll("button")].filter(visible);
function focusBar() {
  player.showCtl(true);
  const bs = barButtons();
  const b = bs.find((x) => x.matches(".b-set")) || bs[0];
  if (b) b.focus({ preventScroll: true });
}
function leaveBarDown() {
  // leaving fullscreen: go to the video info below
  if (document.body.classList.contains("tvfull")) setFull(false);
  player.autoHide();
  setTimeout(() => {
    const below = candidates().filter((el) => el.getBoundingClientRect().top > player.root.getBoundingClientRect().bottom - 8)[0];
    if (below) focusEl(below); else player.root.focus();
  }, 50);
}
// "fullscreen" on a TV = the player fills the page (real fullscreen can cut off remote input inside the TV app)
function setFull(on) {
  document.body.classList.toggle("tvfull", on);
  player.root.classList.toggle("fs", on);
  const b = player.root.querySelector(".b-fs");
  if (b) b.innerHTML = icon(on ? "fsx" : "fs");
}
function tvifyMenu() {
  const m = player.root.querySelector(".ytp-menu");
  if (!m || !m.classList.contains("show")) return;
  $$("[data-a]", m).forEach((x) => { x.tabIndex = 0; x.dataset.tvfocus = ""; });
  const f = m.querySelector(".sel[data-a], [data-a]");
  if (f && !m.contains(document.activeElement)) f.focus();
}
function playerKey(e) {
  const k = e.key;
  if (k === "Enter" || k === " ") { if (player.autoMuted) player.unmute(); else player.toggle(); return true; }
  if (k === "ArrowRight") { player.seek(10, true); player.seekFx(1, 10); return true; }
  if (k === "ArrowLeft") { player.seek(-10, true); player.seekFx(-1, 10); return true; }
  if (k === "ArrowUp") { player.showCtl(true); player.openMenu("main"); return true; }   // straight to the settings (quality, captions, speed)
  if (k === "ArrowDown") { focusBar(); return true; }         // into the control bar
  return false;
}

export function initTV() {
  const on = isTV();
  document.documentElement.classList.toggle("tv", on);
  if (!on) return;
  player.root.setAttribute("tabindex", "0");
  player.root.insertAdjacentHTML("beforeend", `<div class="ytp-tvhint">${$t("↑ 설정 · ↓ 버튼 · ←/→ 10초 · 확인 재생/정지")}</div>`);
  // make the items of the player's settings menu reachable by remote whenever it opens or changes
  new MutationObserver(tvifyMenu).observe(player.root.querySelector(".ytp-menu"), { childList: true, attributes: true, attributeFilter: ["class"] });
  // TVs default to the dark theme (once; after that the setting wins)
  if (!settings.tvThemed) { settings.tvThemed = true; settings.theme = "dark"; }
  // theater mode, large
  if (!settings.theater) settings.theater = true;
  // back to the lists stops the video (no small corner player on a TV)
  if (settings.miniplayer) settings.miniplayer = false;

  addEventListener("keydown", (e) => {
    const k = e.key;
    const typing = e.target.closest?.("input,textarea,select");
    // Back button (remotes deliver Back differently per browser; Samsung Tizen sends key code 10009, LG webOS 461)
    if (k === "GoBack" || k === "BrowserBack" || k === "XF86Back" || k === "Escape" || (k === "Backspace" && !typing) || e.keyCode === 10009 || e.keyCode === 461) {
      e.preventDefault(); e.stopImmediatePropagation();
      // nothing left to go back to: close the Samsung / LG TV app (the Android TV app asks __tvBack itself)
      if (back() === "exit") tvExit();
      return;
    }
    // media keys
    // Samsung and LG remotes give their media keys as key codes
    const byCode = { 415: "MediaPlay", 19: "MediaPause", 10252: "MediaPlayPause", 417: "MediaFastForward", 412: "MediaRewind" }[e.keyCode];
    const mk = byCode || k;
    const media = { MediaPlayPause: () => player.toggle(), MediaPlay: () => player.play(), MediaPause: () => player.video.pause(),
      MediaFastForward: () => player.seek(10, true), MediaRewind: () => player.seek(-10, true),
      MediaTrackNext: () => player.on.next?.(), MediaTrackPrevious: () => player.on.prev?.() }[mk];
    if (media && player.info) { e.preventDefault(); e.stopImmediatePropagation(); media(); return; }

    const arrows = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right" };
    if (typing) {
      // search box: up/down leaves it (left/right moves between letters)
      if (k === "ArrowDown" || k === "ArrowUp") { e.preventDefault(); e.stopImmediatePropagation(); e.target.blur(); move(arrows[k]); }
      return;
    }
    // the remote's menu (≡) button = settings menu
    if ((k === "ContextMenu" || k === "Menu") && player.info) { e.preventDefault(); e.stopImmediatePropagation(); player.showCtl(true); player.openMenu("main"); return; }
    // on the (full-page) video, if focus slipped out of the player, bring it back
    if (state.route === "watch" && document.body.classList.contains("tvfull") && player.info && !player.root.contains(document.activeElement)
        && !$("#modal")?.classList.contains("show") && !$("#popup")?.classList.contains("show")) {
      player.root.focus({ preventScroll: true });
    }
    // inside the control bar: left/right = pick a button, up = back to the video, down = the list below, OK = press
    if (inBar() && !player.root.querySelector(".ytp-menu.show")) {
      if (k === "ArrowLeft" || k === "ArrowRight") {
        e.preventDefault(); e.stopImmediatePropagation();
        const bs = barButtons(), i = bs.indexOf(document.activeElement);
        const n = bs[Math.max(0, Math.min(bs.length - 1, i + (k === "ArrowRight" ? 1 : -1)))];
        if (n) n.focus({ preventScroll: true });
        player.showCtl(true);
        return;
      }
      if (k === "ArrowUp") { e.preventDefault(); e.stopImmediatePropagation(); player.root.focus({ preventScroll: true }); player.autoHide(); return; }
      if (k === "ArrowDown") { e.preventDefault(); e.stopImmediatePropagation(); player.showCtl(true); return; }
      if (k === "Enter" || k === " ") { setTimeout(() => player.showCtl(true), 0); return; }   // the button's own action (click) as usual
    }
    // with the player focused, control the player
    if (document.activeElement === player.root && player.info && !player.root.querySelector(".ytp-menu.show")) {
      if (playerKey(e)) { e.preventDefault(); e.stopImmediatePropagation(); }
      else if (arrows[k]) { e.preventDefault(); e.stopImmediatePropagation(); move(arrows[k]); }
      return;
    }
    if (arrows[k]) {
      e.preventDefault(); e.stopImmediatePropagation();
      if (!move(arrows[k]) && k === "ArrowUp") { scrollTo({ top: 0, behavior: "smooth" }); }
      return;
    }
    if (k === "Enter" && document.activeElement?.matches?.("[data-tvfocus]")) { e.preventDefault(); document.activeElement.click(); }
  }, true);

  // first focus after a page change: watch page = the player, elsewhere = the first video
  player.fullscreen = () => setFull(true);   // a TV always uses the full-page player (never leaves it)
  // the moment a video is chosen (OK) show just the player, full page - a black player before the watch page renders
  document.addEventListener("click", (e) => {
    const a = e.target.closest?.('a[href^="/watch"]');
    if (!a) return;
    setFull(true);
    player.root.classList.remove("ctl");
  }, true);
  // once playback starts, hide the buttons and title soon so only the video shows (any key brings them back)
  player.video.addEventListener("playing", () => {
    if (!document.body.classList.contains("tvfull") || inBar()) return;
    clearTimeout(player.__tvHide);
    player.__tvHide = setTimeout(() => { if (!inBar() && !player.menuOpen) player.root.classList.remove("ctl"); }, 1500);
  });
  addEventListener("routed", () => {
    if (state.route !== "watch") setFull(false);
    if (state.route === "watch") { setFull(true); player.root.focus({ preventScroll: true }); }
    setTimeout(() => {
      if (state.route === "watch") { player.root.focus({ preventScroll: true }); return; }
      if (state.route === "shorts") { player.root.focus({ preventScroll: true }); return; }
      const first = candidates().find((el) => el.closest(".card"));
      if (first && (!document.activeElement || document.activeElement === document.body)) focusEl(first);
    }, 350);
  });
  toast($t("TV 모드: 방향키로 이동 · 확인으로 선택 · 뒤로로 돌아가기"), null, null, 5000);
}
