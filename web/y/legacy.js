// Older TV browsers (Chromium 85: 2022 Samsung TVs, #106) don't know color-mix(), :is(), aspect-ratio or dvh, and
// drop every rule that uses them. Here the page's own stylesheets are rewritten for them: color-mix() becomes the
// mixed colour (worked out again when the theme changes), :is(a, b) x becomes a x, b x, dvh becomes vh, and
// aspect-ratio becomes a height from the element's width. Newer browsers never load this file.
(function () {
  var links = [].slice.call(document.querySelectorAll('link[rel="stylesheet"][href*="/y/"]'));
  var sources = [], style = document.createElement("style"), probe = document.createElement("canvas").getContext("2d");
  document.head.appendChild(style);

  function rgba(c) {
    c = c.trim();
    if (c === "transparent") return [0, 0, 0, 0];
    probe.fillStyle = "#000"; probe.fillStyle = c;
    var v = probe.fillStyle, m;
    if (v[0] === "#") return [parseInt(v.substr(1, 2), 16), parseInt(v.substr(3, 2), 16), parseInt(v.substr(5, 2), 16), 1];
    m = v.match(/[\d.]+/g) || [0, 0, 0, 1];
    return [+m[0], +m[1], +m[2], m[3] == null ? 1 : +m[3]];
  }
  function value(s, root) {
    return s.replace(/var\((--[\w-]+)(?:,([^()]*))?\)/g, function (_, n, d) { return root.getPropertyValue(n).trim() || (d || "").trim(); });
  }
  // color-mix(in srgb, A p%, B [q%]) with plain arguments (no nested parentheses but var())
  function mix(css, root) {
    return css.replace(/color-mix\(in srgb,\s*((?:[^(),]|\([^()]*\))+?)\s+([\d.]+)%\s*,\s*((?:[^(),]|\([^()]*\))+?)(?:\s+[\d.]+%)?\s*\)/g, function (_, a, p, b) {
      var x = rgba(value(a, root)), y = rgba(value(b, root)), t = +p / 100;
      var al = x[3] * t + y[3] * (1 - t);
      if (al <= 0) return "rgba(0,0,0,0)";
      var ch = [0, 1, 2].map(function (i) { return Math.round((x[i] * x[3] * t + y[i] * y[3] * (1 - t)) / al); });
      return "rgba(" + ch.join(",") + "," + al.toFixed(3) + ")";
    });
  }
  // :is(a, b) inside a selector: one selector per choice
  function expand(sel) {
    var m = sel.match(/:is\(([^()]*)\)/);
    if (!m) return [sel];
    var out = [];
    m[1].split(",").forEach(function (c) { out = out.concat(expand(sel.replace(m[0], c.trim()))); });
    return out;
  }
  function selectors(css) {
    return css.replace(/([^{}@;]+)\{/g, function (all, sel) {
      if (sel.indexOf(":is(") < 0) return all;
      // a comment (or the line break) in front stays as it was
      var cut = sel.lastIndexOf("*/") + 1;
      var lead = cut > 0 ? sel.slice(0, cut + 1) : (sel.match(/^\s*/) || [""])[0];
      sel = sel.slice(lead.length);
      var list = [];
      sel.split(/,(?![^()]*\))/).forEach(function (s) { list = list.concat(expand(s.trim())); });
      return lead + list.join(", ") + " {";
    });
  }
  // aspect-ratio: kept for a script that sets the height (below); the rest as it is
  var ratios = [];
  function aspects(css) {
    return css.replace(/([^{}@;]+)\{([^{}]*aspect-ratio:\s*([\d.]+)\s*(?:\/\s*([\d.]+))?[^{}]*)\}/g, function (all, sel, body, w, h) {
      ratios.push([sel.trim(), (+h || 1) / +w]);
      return all;
    });
  }
  function build() {
    var root = getComputedStyle(document.documentElement);
    style.textContent = sources.map(function (s) { return mix(s, root); }).join("\n");
  }
  function sized() {
    ratios.forEach(function (r) {
      var els; try { els = document.querySelectorAll(r[0]); } catch (e) { return; }
      for (var i = 0; i < els.length; i++) {
        var el = els[i], cs = getComputedStyle(el);
        if (cs.aspectRatio) continue;
        var w = el.getBoundingClientRect().width;
        // only heights set here (data-arh) are set again: one the page set itself is left alone
        if (w > 0 && (!el.style.height || el.dataset.arh)) { el.style.height = Math.round(w * r[1]) + "px"; el.dataset.arh = "1"; }
      }
    });
  }

  Promise.all(links.map(function (l) { return fetch(l.href).then(function (r) { return r.text(); }); })).then(function (texts) {
    sources = texts.map(function (t) { t = selectors(t.replace(/(\d)dvh/g, "$1vh")); aspects(t); return t; });
    build();
    links.forEach(function (l) { l.disabled = true; });
    // the theme lives on <html> (its class and data attributes): mixed colours again when it changes
    new MutationObserver(build).observe(document.documentElement, { attributes: true });
    var due = false;
    function later() { if (!due) { due = true; requestAnimationFrame(function () { due = false; sized(); }); } }
    new MutationObserver(later).observe(document.body, { childList: true, subtree: true });
    addEventListener("resize", later);
    sized();
  }).catch(function () {});
})();
