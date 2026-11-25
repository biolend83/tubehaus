// A faster "player" preprocess for the yt-dlp EJS solver (yt.solver.core.min.js), with the same result.
// The solver parses the whole player (2-3 MB) into a syntax tree and prints it back, which takes most of a minute
// in QuickJS, only to: drop the player's top-level statements that are plain expressions (not assignments), find
// the function whose body calls x.set("alr","yes"), and append _result.n/_result.sig. Here a character scanner
// finds the top-level statements; only the few it cannot classify, and those mentioning "alr"/"yes", go through
// the parser; everything else is kept as the player's own text. The solver itself then runs on a small player of
// just the candidates, and its _result lines are moved onto the full text.
// thFastPre(player, jsc, meriyah) returns the preprocessed player, or null (the caller then uses jsc as is).
var thFastPre = (function () {
  var KEEP = /^(?:var|let|const|function|class|async|if|for|while|do|try|switch|return|throw|break|continue|debugger|with|else|catch|finally)\b/;
  var REWORD = /(?:^|[^\w$.])(?:return|typeof|instanceof|in|of|new|delete|void|throw|case|do|else|yield|await)$/;
  var NEXT = /["'`\/(){}\[\];\n]/g;
  var DQ = /"(?:[^"\\\n]|\\[\s\S])*"/y, SQ = /'(?:[^'\\\n]|\\[\s\S])*'/y, TPL = /(?:[^`\\$]|\\[\s\S]|\$(?!\{))*/y;
  var RE = /\/(?:[^\/\\\[\n]|\\.|\[(?:[^\]\\\n]|\\.)*\])+\/[a-z]*/y;
  var HINT = /["'`]alr["'`]\s*,\s*["'`]yes["'`]/;

  function lead(s) { // s without leading spaces and comments
    var i = 0;
    for (;;) {
      while (i < s.length && /\s/.test(s[i])) i++;
      if (s.startsWith("//", i)) { var e = s.indexOf("\n", i); i = e < 0 ? s.length : e + 1; }
      else if (s.startsWith("/*", i)) { var e2 = s.indexOf("*/", i + 2); i = e2 < 0 ? s.length : e2 + 2; }
      else return s.slice(i);
    }
  }
  function assignment(t) { // t starts with an identifier or member chain followed by an assignment operator
    var m = /^[A-Za-z_$][\w$]*/.exec(t); if (!m) return false;
    var i = m[0].length;
    for (;;) {
      while (t[i] === " ") i++;
      if (t[i] === ".") { var m2 = /^\s*[A-Za-z_$][\w$]*/.exec(t.slice(i + 1, i + 200)); if (!m2) return false; i += 1 + m2[0].length; }
      else if (t[i] === "[") { var d = 0, j = i; for (; j < t.length; j++) { if (t[j] === "[") d++; else if (t[j] === "]" && --d === 0) break; else if (/["'`\/]/.test(t[j])) return false; } if (j >= t.length) return false; i = j + 1; }
      else break;
    }
    return /^(?:=(?![=>])|(?:[-+*\/%&|^]|\*\*|<<|>>>?|&&|\|\||\?\?)=)/.test(t.slice(i, i + 5));
  }

  // splits body (a function body's text) into top-level statements: [{s, nl}], each s ending with its own ";"
  function split(b) {
    var out = [], st = [], i = 0, n = b.length, seg = 0, nl = false, m;
    function end(at) { out.push({ s: b.slice(seg, at), nl: nl }); seg = at; nl = false; }
    function sticky(re, at) { re.lastIndex = at; return re.exec(b) ? re.lastIndex : -1; }
    while (i < n) {
      NEXT.lastIndex = i;
      if (!(m = NEXT.exec(b))) break;
      var p = m.index, c = m[0];
      i = p + 1;
      if (c === "\n") { if (st.length === 0 && !nl && lead(b.slice(seg, p))) nl = true; continue; }
      if (c === '"' || c === "'") { i = sticky(c === '"' ? DQ : SQ, p); if (i < 0) return null; continue; }
      if (c === "`") { i = tpl(p + 1); continue; }
      if (c === "/") {
        if (b[p + 1] === "/") { var e = b.indexOf("\n", p); i = e < 0 ? n : e; continue; }
        if (b[p + 1] === "*") { var e2 = b.indexOf("*/", p + 2); i = e2 < 0 ? n : e2 + 2; continue; }
        if (regexAt(p)) { i = sticky(RE, p); if (i < 0) return null; }
        continue;
      }
      if (c === "(" || c === "[" || c === "{") { st.push(c); continue; }
      if (c === ")" || c === "]") { st.pop(); continue; }
      if (c === "}") {
        if (st.pop() === "T") { i = tpl(i); continue; }
        if (st.length === 0 && /^(?:function|class|async|if|for|while|try|switch|do|\{)/.test(lead(b.slice(seg, seg + 300)))) {
          var r = lead(b.slice(i, i + 400));
          if (!/^(?:else|catch|finally|while)\b/.test(r) && !/^[.(\[,?:=+\-*\/%&|^<>!]/.test(r) && r[0] !== ";") end(i);
        }
        continue;
      }
      if (c === ";" && st.length === 0) end(i);
    }
    if (st.length) return null;
    if (seg < n) end(n);
    return out;
    function regexAt(p) { // a "/" where an expression starts: a regular expression, not a division
      var j = p - 1;
      while (j >= 0 && (b[j] === " " || b[j] === "\n" || b[j] === "\t" || b[j] === "\r")) j--;
      if (j < 0) return true;
      var ch = b[j];
      if ("(,=:[!&|?{};+-*%<>~^".indexOf(ch) >= 0) return true;
      if (!/[\w$]/.test(ch)) return false;
      var k = j; while (k >= 0 && /[\w$]/.test(b[k])) k--;
      return REWORD.test(b.slice(Math.max(0, k), j + 1));
    }
    function tpl(q) { // from inside a template literal: to after its closing backtick, or into a ${
      TPL.lastIndex = q; TPL.exec(b); q = TPL.lastIndex;
      if (b[q] === "`") return q + 1;
      if (b[q] === "$") { st.push("T"); return q + 2; }
      return n;
    }
  }

  return function (player, jsc, meriyah) {
    // the two shapes the solver knows: var _yt_player={};(function(g){...})(_yt_player) (the web players; their first
    // statement, var window=this, goes) and (function(){...}).call(this) (the TV player)
    var web = true, head = /^\s*var\s+_yt_player\s*=\s*\{\}\s*;?\s*\(function\s*\(\s*g\s*\)\s*\{/.exec(player), tail = /\}\s*\)\s*\(\s*_yt_player\s*\)\s*;?\s*$/.exec(player);
    if (!head || !tail) { web = false; head = /^\s*\(function\s*\(\s*\)\s*\{/.exec(player); tail = /\}\s*\)\s*\.\s*call\s*\(\s*this\s*\)\s*;?\s*$/.exec(player); }
    if (!head || !tail) return null;
    var segs = split(player.slice(head[0].length, tail.index));
    if (!segs || segs.length < 2) return null;
    var keep = [], cand = [];
    for (var x = web ? 1 : 0; x < segs.length; x++) {
      var s = segs[x].s, t = lead(s);
      if (HINT.test(s)) cand.push(s);
      if (!t || t === ";") { keep.push(s); continue; }
      if (!segs[x].nl && (KEEP.test(t) || t[0] === "{" || /^[\w$]+\s*:(?!:)/.test(t) || assignment(t))) { keep.push(s); continue; }
      var ast;
      try { ast = meriyah.parse(s, { ranges: true }); } catch (e) { keep.push(s); continue; }
      var part = "";
      for (var y = 0; y < ast.body.length; y++) {
        var a = ast.body[y];
        if (a.type !== "ExpressionStatement" || a.expression.type === "AssignmentExpression" || a.expression.type === "Literal") part += s.slice(a.start, a.end) + ";\n";
      }
      keep.push(part);
    }
    var names = [];
    for (var c = 0; c < cand.length; c++) { var nm = match(cand[c], meriyah); if (nm) names.push(nm); }
    if (!names.length) return null;
    var nl = [], sl = [];
    for (var q = 0; q < names.length; q++) { var A = URLFN.replace("NAME", function () { return names[q]; }); nl.push("(n) => (" + A + ")({n}).n"); sl.push("(sig) => (" + A + ")({sig}).sig"); }
    return PRELUDE + (web ? "var _yt_player={};(function(g){" : "(function(){") + keep.join("") + ";\n_result.n = " + ANY.replace("LIST", function () { return nl.join(", "); }) +
      ";\n_result.sig = " + ANY.replace("LIST", function () { return sl.join(", "); }) + ";\n})" + (web ? "(_yt_player)" : ".call(this)") + ";\n";
  };

  // the solver's own: a statement (assignment, declaration or var) of a function whose body has x.set("alr","yes")
  function match(s, meriyah) {
    var ast; try { ast = meriyah.parse(s, { ranges: true }); } catch (e) { return null; }
    for (var k = 0; k < ast.body.length; k++) {
      var e = ast.body[k], fns = [];
      if (e.type === "FunctionDeclaration" && !e.async && e.id) fns.push([e.id, e.body && e.body.body]);
      else if (e.type === "ExpressionStatement" && e.expression.type === "AssignmentExpression" && e.expression.operator === "=" &&
        /^(?:Identifier|MemberExpression)$/.test(e.expression.left.type) && e.expression.right.type === "FunctionExpression" && !e.expression.right.async)
        fns.push([e.expression.left, e.expression.right.body && e.expression.right.body.body]);
      else if (e.type === "VariableDeclaration" && e.declarations.some(function (d) { return d.init && d.init.type === "FunctionExpression" && !d.init.async; }))
        e.declarations.forEach(function (d) { if (d.init && d.init.body) fns.push([d.id, d.init.body.body]); });
      for (var f = 0; f < fns.length; f++) {
        var body = fns[f][1];
        if (body && body.some(alr)) return s.slice(fns[f][0].start, fns[f][0].end);
      }
    }
    return null;
  }
  function alr(e) {
    var x = e.type === "ExpressionStatement" && e.expression;
    return !!x && x.type === "CallExpression" && !x.optional && x.callee.type === "MemberExpression" && x.callee.object.type === "Identifier" && !x.callee.optional &&
      x.arguments.length === 2 && x.arguments[0].type === "Literal" && x.arguments[0].value === "alr" && x.arguments[1].type === "Literal" && x.arguments[1].value === "yes";
  }
})();
var PRELUDE = 'if (typeof globalThis.XMLHttpRequest === "undefined") { globalThis.XMLHttpRequest = { prototype: {} }; }\n' +
  'if (typeof URL === "undefined") { globalThis.location = { hash: "", host: "www.youtube.com", hostname: "www.youtube.com", href: "https://www.youtube.com/watch?v=yt-dlp-wins", origin: "https://www.youtube.com", password: "", pathname: "/watch", port: "", protocol: "https:", search: "?v=yt-dlp-wins", username: "" }; }\n' +
  'else { globalThis.location = new URL("https://www.youtube.com/watch?v=yt-dlp-wins"); }\n' +
  'if (typeof globalThis.document === "undefined") { globalThis.document = Object.create(null); }\n' +
  'if (typeof globalThis.navigator === "undefined") { globalThis.navigator = Object.create(null); }\n' +
  'if (typeof globalThis.self === "undefined") { globalThis.self = globalThis; }\n' +
  'if (typeof globalThis.window === "undefined") { globalThis.window = globalThis; }\n';
var URLFN = '({sig, n}) => { const url = (NAME)("https://youtube.com/watch?v=yt-dlp-wins", "s", sig ? encodeURIComponent(sig) : undefined); url.set("n", n); ' +
  'const proto = Object.getPrototypeOf(url); const keys = Object.keys(proto).concat(Object.getOwnPropertyNames(proto)); ' +
  'for (const key of keys) { if (!["constructor", "set", "get", "clone"].includes(key)) { url[key](); break; } } ' +
  'const s = url.get("s"); return { sig: s ? decodeURIComponent(s) : null, n: url.get("n") ?? null }; }';
var ANY = '((_input) => { const _results = new Set(); const errors = []; for (const _generator of [LIST]) { try { _results.add(_generator(_input)); } catch (e) { errors.push(e); } } ' +
  'if (!_results.size) { throw `no solutions: ${errors.join(", ")}`; } if (_results.size !== 1) { throw `invalid solutions: ${[..._results].map(x => JSON.stringify(x)).join(", ")}`; } ' +
  'return _results.values().next().value; })';
// jsc with the fast preprocess first; the solver's own whenever the fast one gives nothing or cannot solve an n
if (typeof jsc === "function" && !jsc.thFast) jsc = (function (full) {
  function fast(input) {
    if (!input || input.type !== "player") return null;
    var pre; try { pre = thFastPre(input.player, full, meriyah); } catch (e) { return null; }
    if (!pre) return null;
    var reqs = input.requests && input.requests.length ? input.requests : [{ type: "n", challenges: ["thFastPreTest"] }];
    var r = full({ type: "preprocessed", preprocessed_player: pre, requests: reqs });
    if (!r || r.type !== "result" || r.responses.some(function (x) { return x.type !== "result"; })) return null;
    if (reqs !== input.requests) r.responses = [];
    if (input.output_preprocessed) r.preprocessed_player = pre;
    return r;
  }
  var f = function (input) { return fast(input) || full(input); };
  f.thFast = true;
  return f;
})(jsc);
