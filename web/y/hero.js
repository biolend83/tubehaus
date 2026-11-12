// The 2.0 home's top, as in the apps: a greeting for the time of day, then the videos left half-watched.
import { el, esc, fmtTime, thumb, lib } from "./util.js";
import { $t } from "./i18n.js";

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? $t("늦은 밤이에요") : h < 12 ? $t("좋은 아침이에요") : h < 18 ? $t("좋은 오후예요") : $t("좋은 저녁이에요");
}

/** put the greeting (and, under it, what was left half-watched) before `before`; returns no ids (the grid keeps all) */
export function hero(before) {
  if (!before) return [];
  before.before(el(`<section class="h2"><div class="h2-hello"><h1>${greeting()}</h1><p>${$t("오늘 고른 영상이에요")}</p></div></section>`));
  continueRow(before);
  return [];
}

/** under the greeting: the videos left half-watched, each from where it stopped (as the apps' "Continue watching") */
export async function continueRow(before) {
  if (!before) return;
  let items = [];
  try { items = (await lib("hist?n=30", { ttl: 30000, quiet: true })).items || []; } catch { return; }
  items = items.filter((h) => h.id && !h.live);
  if (!items.length) return;
  let pos = {};
  try { pos = await lib("pos?ids=" + items.map((h) => h.id).join(","), { quiet: true }); } catch { return; }
  const left = items.map((h) => ({ h, p: pos[h.id] })).filter(({ p }) => p && p[1] && p[0] > 30 && p[0] / p[1] < 0.93).slice(0, 10);
  if (left.length < 1 || !before.isConnected) return;
  const row = el(`<section class="h2-cont"><div class="shelf-h"><b>${$t("이어서 보기")}</b></div><div class="h2-cont-row">${left.map(({ h, p }) => `
    <a class="h2-c" href="/watch?v=${h.id}&t=${Math.floor(p[0])}" data-nav>
      <span class="h2-c-th"><img src="${thumb(h.id, "mqdefault")}" alt="" loading="lazy"><i style="width:${(p[0] / p[1]) * 100}%"></i></span>
      <b>${esc(h.title)}</b><small>${$t("{0} 남음", fmtTime(p[1] - p[0]))}</small></a>`).join("")}</div></section>`);
  before.before(row);
}
