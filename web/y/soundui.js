// The sound panel: loudness matching, the equalizer and the effects; from the music player also its speed,
// the fade between songs and the sleep timer. Opened from the music player's full screen and from Settings.
import { $, $$, icon, settings, toast } from "./util.js";
import { dialog } from "./core.js";
import { $t } from "./i18n.js";
import { BANDS, PRESETS, CAN_FX, fx, setFx } from "./sound.js";

const PRESET_NAMES = () => [["flat", $t("평탄")], ["bass", $t("저음 강화")], ["treble", $t("고음 강화")], ["vocal", $t("보컬")],
  ["pop", $t("팝")], ["rock", $t("록")], ["jazz", $t("재즈")], ["classical", $t("클래식")], ["electronic", $t("일렉트로닉")],
  ["acoustic", $t("어쿠스틱")], ["hiphop", $t("힙합")], ["speech", $t("말소리")]];
const hz = (f) => (f >= 1000 ? f / 1000 + "k" : String(f));
const db = (g) => (g > 0 ? "+" : "") + g;

/**
 * music: { speed, setSpeed, pitch, setPitch, fade, setFade, sleep, setSleep, sleepLeft } from the music player
 */
export function soundDialog(music = null) {
  const f = fx();
  const tog = (k, on, label, sub = "") => `<label class="sx-row"><span class="sx-tx"><b>${label}</b>${sub ? `<small>${sub}</small>` : ""}</span><input type="checkbox" class="sw" data-s="${k}" ${on ? "checked" : ""}></label>`;
  const seg = (k, val, opts) => `<div class="sx-seg snd-seg" role="radiogroup">${opts.map(([v, l]) => `<label><input type="radio" name="snd-${k}" data-s="${k}" value="${v}" ${String(val) === String(v) ? "checked" : ""}><span>${l}</span></label>`).join("")}</div>`;
  const range = (k, v, min, max, step, out) => `<span class="sx-range"><input type="range" data-s="${k}" min="${min}" max="${max}" step="${step}" value="${v}"><output>${out}</output></span>`;
  const row = (label, ctl, sub = "", cls = "") => `<div class="sx-row ${cls}"><span class="sx-tx"><b>${label}</b>${sub ? `<small>${sub}</small>` : ""}</span>${ctl}</div>`;
  const widthOut = (w) => (+w === 0 ? $t("모노") : w + "%");
  const balOut = (b) => (+b === 0 ? $t("가운데") : (b < 0 ? $t("왼쪽") : $t("오른쪽")) + " " + Math.abs(b));
  const speeds = [0.5, 0.75, 0.9, 1, 1.1, 1.25, 1.5, 2];
  const sleepLabel = () => {
    const l = music?.sleepLeft?.();
    return l == null ? "" : l === -1 ? $t("이 곡이 끝나면 멈춰요") : $t("{0}분 뒤에 멈춰요", Math.max(1, Math.ceil(l / 60000)));
  };
  const { box, close } = dialog(`
    <div class="dh"><b>${$t("소리")}</b><button data-close aria-label="${$t("닫기")}">${icon("close")}</button></div>
    <div class="snd">
      ${tog("norm", settings.soundNorm !== false, $t("음량 맞추기"), $t("유튜브가 알려 주는 영상마다의 음량으로, 큰 영상·곡은 줄여서 비슷한 크기로 들려요"))}
      ${CAN_FX ? `
      ${tog("eq", f.eq, $t("이퀄라이저"))}
      <div class="snd-eq${f.eq ? "" : " off"}">
        <div class="snd-presets">${PRESET_NAMES().map(([k, l]) => `<button class="chip${f.preset === k ? " on" : ""}" data-preset="${k}">${l}</button>`).join("")}<button class="chip${f.preset === "custom" ? " on" : ""}" data-preset="custom" ${f.preset === "custom" ? "" : "hidden"}>${$t("사용자 지정")}</button></div>
        <div class="snd-bands">${BANDS.map((b, i) => `<label class="snd-band"><output>${db(f.gains[i])}</output><input type="range" min="-12" max="12" step="1" value="${f.gains[i]}" data-band="${i}" aria-label="${hz(b)}Hz"><span>${hz(b)}</span></label>`).join("")}</div>
      </div>
      ${row($t("스테레오 폭"), range("width", f.width, 0, 200, 10, widthOut(f.width)), $t("0이면 모노, 100이 원래, 넘으면 더 넓게"))}
      ${row($t("좌우 균형"), range("balance", f.balance, -100, 100, 10, balOut(f.balance)))}
      ${tog("night", f.night, $t("야간 모드"), $t("작은 소리는 키우고 큰 소리는 줄여요. 밤에 작게 들을 때 좋아요"))}
      ${tog("video", !!settings.soundVideo, $t("동영상에도 적용"), $t("이퀄라이저와 효과를 동영상에도 써요 (끄면 음악에만)"))}
      ` : `<p class="sx-note">${$t("iPhone·iPad 브라우저에서는 화면이 꺼지면 효과가 멈춰서, 이퀄라이저와 효과는 앱이나 컴퓨터에서 쓸 수 있어요")}</p>`}
      ${music ? `
      <h4 class="snd-h">${$t("음악")}</h4>
      ${row($t("재생 속도"), seg("speed", music.speed(), speeds.map((x) => [x, x + "×"])), "", "wide")}
      ${tog("pitch", music.pitch(), $t("음 높이 유지"), $t("끄면 빠르게 할 때 음도 높아져요 (나이트코어처럼)"))}
      ${row($t("곡 사이 페이드"), seg("fade", music.fade(), [[0, $t("끄기")], [2, $t("{0}초", 2)], [4, $t("{0}초", 4)], [6, $t("{0}초", 6)], [10, $t("{0}초", 10)]]), $t("곡 끝은 줄어들고 다음 곡은 커지면서 시작해요"), "wide")}
      ${row($t("수면 타이머"), seg("sleep", "", [[0, $t("끄기")], [15, $t("{0}분", 15)], [30, $t("{0}분", 30)], [60, $t("{0}분", 60)], [90, $t("{0}분", 90)], [-1, $t("이 곡 끝")]]), `<span class="snd-sleep">${sleepLabel()}</span>`, "wide")}
      ` : ""}
      <div class="snd-foot"><button class="pill ghost" data-s="reset">${$t("처음대로")}</button><button class="pill" data-close>${$t("완료")}</button></div>
    </div>`, { cls: "snd-dlg" });

  const paintBands = (gains) => $$("[data-band]", box).forEach((r, i) => { r.value = gains[i]; r.previousElementSibling.textContent = db(gains[i]); });
  const paintPreset = (p) => $$("[data-preset]", box).forEach((b) => { b.classList.toggle("on", b.dataset.preset === p); if (b.dataset.preset === "custom") b.hidden = p !== "custom"; });
  box.addEventListener("click", (e) => {
    const p = e.target.closest("[data-preset]");
    if (p) {
      const k = p.dataset.preset;
      if (k === "custom") return;
      setFx({ preset: k, gains: PRESETS[k], eq: true });
      $("[data-s=eq]", box).checked = true;
      $(".snd-eq", box).classList.remove("off");
      paintPreset(k); paintBands(PRESETS[k]);
      return;
    }
    if (e.target.closest("[data-s=reset]")) {
      settings.fx = null;
      setFx({});
      settings.soundNorm = true;
      settings.soundVideo = false;
      if (music) { music.setSpeed(1); music.setPitch(true); music.setFade(0); music.setSleep(0); }
      close();
      toast($t("처음대로 돌렸어요"));
    }
  });
  box.addEventListener("input", (e) => {
    const t = e.target;
    if (t.dataset.band != null) {
      const g = fx().gains.slice();
      g[+t.dataset.band] = +t.value;
      t.previousElementSibling.textContent = db(+t.value);
      const named = Object.entries(PRESETS).find(([, v]) => v.every((x, i) => x === g[i]))?.[0];
      setFx({ gains: g, preset: named || "custom", eq: true });
      $("[data-s=eq]", box).checked = true;
      $(".snd-eq", box).classList.remove("off");
      paintPreset(named || "custom");
      return;
    }
    const k = t.dataset.s;
    if (k === "width") { setFx({ width: +t.value }); t.nextElementSibling.textContent = widthOut(t.value); }
    else if (k === "balance") { setFx({ balance: +t.value }); t.nextElementSibling.textContent = balOut(+t.value); }
  });
  box.addEventListener("change", (e) => {
    const t = e.target, k = t.dataset.s;
    if (k === "norm") settings.soundNorm = t.checked;
    else if (k === "eq") { setFx({ eq: t.checked }); $(".snd-eq", box).classList.toggle("off", !t.checked); }
    else if (k === "night") setFx({ night: t.checked });
    else if (k === "video") settings.soundVideo = t.checked;
    else if (k === "speed") music.setSpeed(+t.value);
    else if (k === "pitch") music.setPitch(t.checked);
    else if (k === "fade") music.setFade(+t.value);
    else if (k === "sleep") {
      music.setSleep(+t.value);
      $(".snd-sleep", box).textContent = sleepLabel();
      toast(+t.value ? sleepLabel() : $t("수면 타이머를 껐어요"));
      t.checked = false;
    }
  });
}
