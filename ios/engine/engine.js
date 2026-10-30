// TubeHaus YouTube engine for iPad (JavaScript).
//
// Everything the iPad app asks YouTube is decided here: which requests to make and how to read
// the answers. The app runs this file in Apple's JavaScript engine and does the network calls
// itself. A newer copy can replace it without reinstalling the app: the app checks GitHub once a
// day and keeps the copy in Files → On My iPad → TubeHaus → engine.js; a file put there by hand
// is used too, whichever has the higher ENGINE_VERSION. Bump ENGINE_VERSION with every change.
//
// It is the same engine as the Android and desktop apps' Kotlin one (common/jvm/.../YouTube.kt,
// Parse.kt), line for line where possible, so a fix found in one carries over to the other.
//
// Talking to the app: __run(id, op, args, env) starts an operation; it asks the app for things
// through host({kind: ...}) (an HTTP request, the visitor identity, a new identity); the app
// collects those with __drain(), answers each with __reply(rid, json), and reads the outcome
// with __take(id).

var ENGINE_VERSION = 2;

// ------------------------------------------------------------------------------ host bridge
var ENV = { hl: "en", gl: "US", players: null };
var __calls = {};
var __pending = [];
var __waiters = {};
var __nextRid = 1;

function host(req) {
  return new Promise(function (resolve, reject) {
    var rid = __nextRid++;
    __waiters[rid] = { resolve: resolve, reject: reject };
    req.rid = rid;
    __pending.push(req);
  });
}

function __run(cid, op, argsJson, envJson) {
  var args = JSON.parse(argsJson);
  if (envJson) ENV = JSON.parse(envJson);
  __calls[cid] = { state: "pending" };
  Promise.resolve()
    .then(function () {
      var f = OPS[op];
      if (!f) throw ytError("Unknown operation " + op, "OTHER");
      return f.apply(null, args);
    })
    .then(
      function (v) { __calls[cid] = { state: "ok", value: v === undefined ? null : v }; },
      function (e) {
        __calls[cid] = { state: "error", kind: (e && e.kind) || "OTHER", code: (e && e.code) || 0, message: String((e && e.message) || e) };
      }
    );
}

function __drain() { return JSON.stringify(__pending.splice(0, __pending.length)); }

function __reply(rid, json) {
  var w = __waiters[rid];
  if (!w) return;
  delete __waiters[rid];
  w.resolve(JSON.parse(json));
}

function __take(cid) {
  var c = __calls[cid];
  if (!c || c.state === "pending") return '{"state":"pending"}';
  delete __calls[cid];
  return JSON.stringify(c);
}

function ytError(message, kind, code) {
  var e = new Error(message);
  e.kind = kind || "OTHER";
  e.code = code || 0;
  return e;
}

function nowSec() { return Math.floor(Date.now() / 1000); }

// ------------------------------------------------------------------------------ JSON walking
function str(e) {
  if (typeof e === "string") return e;
  if (typeof e === "number" || typeof e === "boolean") return String(e);
  return null;
}
function num(e) {
  var s = str(e);
  if (s === null) return null;
  var n = Number(s);
  return isFinite(n) ? Math.trunc(n) : null;
}
function bool(e) { return str(e) === "true"; }
function arr(e) { return Array.isArray(e) ? e : []; }
function isObj(e) { return e !== null && typeof e === "object" && !Array.isArray(e); }

/** obj.a.b.0... with dots; numbers index arrays */
function at(e, path) {
  var cur = e;
  var keys = path.split(".");
  for (var i = 0; i < keys.length; i++) {
    if (cur === null || cur === undefined) return null;
    var k = keys[i];
    if (Array.isArray(cur)) {
      var n = parseInt(k, 10);
      if (isNaN(n)) return null;
      cur = cur[n];
    } else if (isObj(cur)) {
      cur = cur[k];
    } else return null;
  }
  return cur === undefined ? null : cur;
}

/** the text of {simpleText}, {runs:[{text}]} or {content} */
function text(e) {
  if (!isObj(e)) return str(e);
  if (str(e.simpleText) !== null) return str(e.simpleText);
  if (str(e.content) !== null) return str(e.content);
  var runs = arr(e.runs);
  if (runs.length) return runs.map(function (r) { return str(at(r, "text")) || ""; }).join("");
  return null;
}

/** branches that only hold menus, sheets and logging */
var NOISE = {
  trackingParams: 1, loggingDirectives: 1, menu: 1, menuButton: 1, sheetViewModel: 1, topbar: 1,
  frameworkUpdates: 1, responseContext: 1, contextMenu: 1, overlay: 1, hotkeyDialog: 1, microformat: 1,
  playerOverlays: 1, onResponseReceivedEndpoints_unused: 1,
};

/** every value under [key] below e (depth first, document order) */
function findAll(e, key, skip, limit) {
  skip = skip || NOISE;
  limit = limit || Infinity;
  var out = [];
  (function walk(x) {
    if (out.length >= limit) return;
    if (Array.isArray(x)) { for (var i = 0; i < x.length; i++) walk(x[i]); }
    else if (isObj(x)) {
      for (var k in x) {
        if (k === key) out.push(x[k]);
        else if (!skip[k]) walk(x[k]);
      }
    }
  })(e);
  return out;
}
function findFirst(e, key, skip) {
  if (e === null || e === undefined) return null;
  var r = findAll(e, key, skip, 1);
  return r.length ? r[0] : null;
}

/** "1.2M views" / "1,234 views" → a number */
function countOf(s) {
  if (!s || !String(s).trim()) return 0;
  var m = /([\d.,]+)\s*([KMB])?/i.exec(s);
  if (!m) return 0;
  var mult = { K: 1e3, M: 1e6, B: 1e9 }[(m[2] || "").toUpperCase()] || 1;
  var n = mult === 1 ? parseFloat(m[1].replace(/,/g, "").replace(/\./g, "")) : parseFloat(m[1].replace(/,/g, "."));
  return Math.trunc((isNaN(n) ? 0 : n) * mult);
}

/** "1:02:03" → 3723 */
function secondsOf(s) {
  if (!s || !String(s).trim()) return 0;
  var parts = String(s).trim().split(":");
  var t = 0;
  for (var i = 0; i < parts.length; i++) {
    var p = parseInt(parts[i].trim(), 10);
    if (isNaN(p) || !/^\s*\d+\s*$/.test(parts[i])) return 0;
    t = t * 60 + p;
  }
  return t;
}

function https(u) {
  if (!u) return "";
  return u.indexOf("//") === 0 ? "https:" + u : u;
}

/** the largest image in {thumbnails:[...]} or {sources:[...]} */
function bestImage(e) {
  if (e === null || e === undefined) return "";
  var list = arr(findFirst(e, "thumbnails"));
  if (!list.length) list = arr(findFirst(e, "sources"));
  if (!list.length) return "";
  var best = null;
  for (var i = 0; i < list.length; i++) {
    if (!best || (num(at(list[i], "width")) || 0) > (num(at(best, "width")) || 0)) best = list[i];
  }
  return https(str(at(best, "url")));
}

// ------------------------------------------------------------------------------ list items
var ITEM_KEYS = {
  videoRenderer: 1, compactVideoRenderer: 1, gridVideoRenderer: 1, playlistVideoRenderer: 1,
  lockupViewModel: 1, shortsLockupViewModel: 1, reelItemRenderer: 1,
  channelRenderer: 1, playlistRenderer: 1, playlistPanelVideoRenderer: 1,
};
var ID = /^[\w-]{11}$/;
var UC = /^UC[\w-]{22}$/;
var VIEWS = /\bviews?\b|\bwatching\b|조회수|시청 ?중/i;
var AGO = /\bago\b|전$|^streamed|스트리밍/i;

function video(o) {
  return {
    type: "video", id: o.id, title: o.title || "", author: o.author || "", channelId: o.channelId || "",
    lengthSec: o.lengthSec || 0, viewsText: o.viewsText || "", views: o.views || 0, publishedText: o.publishedText || "",
    publishedAt: o.publishedAt || 0, live: !!o.live, short: !!o.short, avatar: o.avatar || "",
  };
}

/** every item below root, in order, without duplicates */
function parseItems(root) {
  if (root === null || root === undefined) return [];
  var out = [];
  var seen = {};
  (function walk(e) {
    if (Array.isArray(e)) { for (var i = 0; i < e.length; i++) walk(e[i]); }
    else if (isObj(e)) {
      for (var k in e) {
        if (ITEM_KEYS[k]) {
          var it = parseItem(k, e[k]);
          if (it && !seen[it.id]) { seen[it.id] = 1; out.push(it); }
        } else if (!NOISE[k] && k !== "continuationItemRenderer") walk(e[k]);
      }
    }
  })(root);
  return out;
}

/** the last "load more" token below root */
function continuationOf(root) {
  if (root === null || root === undefined) return null;
  var r = findAll(root, "continuationItemRenderer").concat(findAll(root, "continuationItemViewModel"));
  var last = null;
  for (var i = 0; i < r.length; i++) {
    var t = str(findFirst(r[i], "token"));
    if (t !== null) last = t;
  }
  return last;
}

function parseItem(kind, v) {
  try {
    switch (kind) {
      case "videoRenderer": case "compactVideoRenderer": case "gridVideoRenderer":
      case "playlistVideoRenderer": case "playlistPanelVideoRenderer":
        return videoRenderer(v);
      case "lockupViewModel": return lockup(v);
      case "shortsLockupViewModel": return shortsLockup(v);
      case "reelItemRenderer": {
        var id = str(at(v, "videoId"));
        return id ? video({ id: id, title: text(at(v, "headline")) || "", viewsText: text(at(v, "viewCountText")) || "", short: true }) : null;
      }
      case "channelRenderer": return channelRenderer(v);
      case "playlistRenderer": return playlistRenderer(v);
    }
  } catch (e) { /* one odd item must never sink the whole list */ }
  return null;
}

function styles(e) {
  return e ? findAll(e, "style").map(str).filter(function (s) { return s !== null; }) : [];
}

function videoRenderer(v) {
  var id = str(at(v, "videoId"));
  if (!id || !ID.test(id)) return null;
  var byline = at(v, "longBylineText") || at(v, "ownerText") || at(v, "shortBylineText");
  var badges = styles(at(v, "badges"));
  var overlays = styles(at(v, "thumbnailOverlays"));
  var views = text(at(v, "viewCountText")) || text(at(v, "videoInfo")) || "";
  var lengthText = text(at(v, "lengthText"));
  return video({
    id: id,
    title: text(at(v, "title")) || text(at(v, "headline")) || "",
    author: text(byline) || "",
    channelId: str(byline ? findFirst(byline, "browseId") : null) || "",
    lengthSec: lengthText !== null ? secondsOf(lengthText) : (num(at(v, "lengthSeconds")) || 0),
    viewsText: views,
    views: countOf(views),
    publishedText: text(at(v, "publishedTimeText")) || "",
    live: badges.indexOf("BADGE_STYLE_TYPE_LIVE_NOW") >= 0 || overlays.indexOf("LIVE") >= 0,
    short: at(v, "navigationEndpoint.reelWatchEndpoint") !== null || overlays.indexOf("SHORTS") >= 0,
    avatar: bestImage(at(v, "channelThumbnailSupportedRenderers") || at(v, "channelThumbnail")),
  });
}

function lockup(v) {
  var id = str(at(v, "contentId"));
  if (!id) return null;
  var type = str(at(v, "contentType")) || "";
  var meta = at(v, "metadata.lockupMetadataViewModel");
  var title = text(at(meta, "title")) || "";
  var rows = meta ? arr(at(meta, "metadata.contentMetadataViewModel.metadataRows")).map(function (row) {
    return arr(at(row, "metadataParts")).map(function (p) { return text(at(p, "text")); }).filter(function (t) { return t !== null; });
  }).filter(function (r) { return r.length > 0; }) : null;
  var image = at(v, "contentImage");
  var badges = image ? findAll(image, "thumbnailBadgeViewModel") : [];
  var badgeText = "";
  for (var i = 0; i < badges.length; i++) { var t = str(at(badges[i], "text")); if (t !== null) { badgeText = t; break; } }
  var live = badges.some(function (b) { return (str(at(b, "badgeStyle")) || "").indexOf("LIVE") >= 0; });
  var ids = meta ? findAll(meta, "browseId", { menuButton: 1 }).map(str) : [];
  var channelId = "";
  for (var j = 0; j < ids.length; j++) { if (ids[j] && UC.test(ids[j])) { channelId = ids[j]; break; } }
  if (type === "LOCKUP_CONTENT_TYPE_VIDEO" || (type === "" && ID.test(id))) {
    // channel pages show [views, date]; everywhere else [channel] then [views, date]
    var author = "", stats = [];
    if (rows && rows.length === 1) stats = rows[0];
    else if (rows && rows.length > 1) { author = rows[0][0]; stats = rows[1]; }
    // where the views and the date sit differs by page and experiment: by what they say first, position second
    var parts = rows ? [].concat.apply([], rows) : [];
    var viewsText = parts.filter(function (p) { return VIEWS.test(p); })[0] || stats[0] || "";
    var dateText = parts.filter(function (p) { return p !== viewsText && AGO.test(p); })[0] || (stats[1] !== viewsText ? stats[1] : "") || "";
    return video({
      id: id, title: title, author: author, channelId: channelId,
      lengthSec: badgeText.indexOf(":") >= 0 ? secondsOf(badgeText) : 0,
      viewsText: viewsText, views: countOf(viewsText), publishedText: dateText,
      live: live, avatar: bestImage(at(meta, "image")),
    });
  }
  // playlists, mixes, albums, podcasts: all open as a playlist
  return {
    type: "playlist", id: id, title: title, thumb: bestImage(image), countText: badgeText,
    author: rows && rows.length && rows[0].length ? rows[0][0] : "",
  };
}

function shortsLockup(v) {
  var id = str(at(v, "onTap.innertubeCommand.reelWatchEndpoint.videoId"));
  if (!id) {
    var ent = str(at(v, "entityId"));
    var tail = ent ? ent.substring(ent.lastIndexOf("-") + 1) : null;
    id = tail && ID.test(tail) ? tail : null;
  }
  if (!id) return null;
  var views = text(at(v, "overlayMetadata.secondaryText")) || "";
  return video({ id: id, title: text(at(v, "overlayMetadata.primaryText")) || "", viewsText: views, views: countOf(views), short: true });
}

function channelRenderer(v) {
  var id = str(at(v, "channelId"));
  if (!id) return null;
  // since 2024 "subscriberCountText" holds the @handle and "videoCountText" the subscribers
  var a = text(at(v, "subscriberCountText")) || "";
  var b = text(at(v, "videoCountText")) || "";
  var pair = [a, b];
  var handle = pair.filter(function (x) { return x.indexOf("@") === 0; })[0] || "";
  var subs = pair.filter(function (x) { return x.indexOf("@") !== 0 && x.trim(); })[0] || "";
  return { type: "channel", id: id, name: text(at(v, "title")) || "", avatar: bestImage(at(v, "thumbnail")), subsText: subs, handle: handle };
}

function playlistRenderer(v) {
  var id = str(at(v, "playlistId"));
  if (!id) return null;
  return {
    type: "playlist", id: id, title: text(at(v, "title")) || "", thumb: bestImage(at(v, "thumbnails")),
    countText: text(at(v, "videoCountText")) || str(at(v, "videoCount")) || "",
    author: text(at(v, "shortBylineText")) || text(at(v, "longBylineText")) || "",
  };
}

// ------------------------------------------------------------------------------ InnerTube
var WEB = {
  name: "WEB", version: "2.20260708.00.00", id: 1,
  userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
  extra: {},
};
var DEFAULT_PLAYERS = [
  {
    name: "VISIONOS", version: "1.02", id: 101,
    userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
    extra: { deviceMake: "Apple", deviceModel: "RealityDevice17,1", osName: "visionOS", osVersion: "26.5.23O471" },
  },
  {
    name: "ANDROID_VR", version: "1.65.10", id: 28,
    userAgent: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    extra: { deviceMake: "Oculus", deviceModel: "Quest 3", androidSdkVersion: 32, osName: "Android", osVersion: "12L" },
  },
];
function players() { return ENV.players && ENV.players.length ? ENV.players : DEFAULT_PLAYERS; }

function headers(c, visitor) {
  var h = {
    "User-Agent": c.userAgent, "X-YouTube-Client-Name": String(c.id), "X-YouTube-Client-Version": c.version,
    "X-Goog-Api-Format-Version": "1", "Origin": "https://www.youtube.com", "Referer": "https://www.youtube.com/",
    "Accept-Language": ENV.hl, "Content-Type": "application/json",
  };
  if (visitor) h["X-Goog-Visitor-Id"] = visitor;
  return h;
}

function context(c, visitor) {
  var client = { clientName: c.name, clientVersion: c.version, hl: ENV.hl, gl: ENV.gl, userAgent: c.userAgent };
  if (visitor) client.visitorData = visitor;
  var extra = c.extra || {};
  for (var k in extra) client[k] = typeof extra[k] === "number" ? extra[k] : String(extra[k]);
  return { client: client };
}

async function call(endpoint, body, c) {
  c = c || WEB;
  var visitor = (await host({ kind: "visitor" })).value || null;
  var payload = { context: context(c, visitor) };
  for (var k in body) if (body[k] !== null && body[k] !== undefined) payload[k] = body[k];
  var r = await host({
    kind: "http", method: "POST", url: "https://www.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false",
    headers: headers(c, visitor), body: JSON.stringify(payload),
  });
  if (r.error) throw ytError("No connection to YouTube", "NETWORK");
  if (r.status < 200 || r.status > 299) throw ytError("YouTube answered " + r.status, "NETWORK", r.status);
  return JSON.parse(r.body);
}

async function get(url) {
  var r = await host({ kind: "http", method: "GET", url: url, headers: {} });
  if (r.error) throw ytError("No connection", "NETWORK");
  if (r.status < 200 || r.status > 299) throw ytError("HTTP " + r.status, "NETWORK", r.status);
  return r.body;
}

// ------------------------------------------------------------------------------ operations
async function search(query, params) {
  var d = await call("search", { query: query, params: params ? decodeURIComponent(params) : null });
  var root = at(d, "contents");
  return { items: parseItems(root), next: continuationOf(root) };
}

async function searchMore(token) {
  var d = await call("search", { continuation: token });
  var root = at(d, "onResponseReceivedCommands");
  return { items: parseItems(root), next: continuationOf(root) };
}

async function suggestions(q) {
  if (!q || !q.trim()) return [];
  var t = await get("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=" + ENV.hl + "&q=" + encodeURIComponent(q));
  return arr(at(JSON.parse(t), "1")).map(str).filter(function (s) { return s !== null; });
}

/** the streams of one video: each player client in turn until one gives playable URLs */
async function stream(id) {
  var first = null;
  for (var attempt = 0; attempt <= 1; attempt++) {
    var walled = true;
    var list = players();
    for (var i = 0; i < list.length; i++) {
      try {
        return await player(id, list[i]);
      } catch (e) {
        if (!first || first.kind === "LOGIN") first = e;
        if (e.kind === "NETWORK") throw e;
        if (e.kind !== "LOGIN") walled = false;
      }
    }
    // every client asked to sign in: the wall is on this visitor identity, so try a new one
    if (!walled || attempt === 1 || !(await host({ kind: "reroll" })).value) break;
    first = null;
  }
  throw first || ytError("This video can't be played");
}

function range(e) {
  var s = num(at(e, "start")), t = num(at(e, "end"));
  return s === null || t === null ? null : [s, t];
}

function format(f) {
  var url = str(at(f, "url"));
  if (!url) return null;                  // ciphered formats need the web player's JS: skipped
  var mimeFull = str(at(f, "mimeType"));
  if (!mimeFull) return null;
  var mime = mimeFull.split(";")[0].trim();
  var ci = mimeFull.indexOf('codecs="');
  var codecs = ci >= 0 ? mimeFull.substring(ci + 8).split('"')[0] : "";
  var def = str(at(f, "audioTrack.audioIsDefault"));
  return {
    itag: num(at(f, "itag")) || 0, url: url, mime: mime, codecs: codecs,
    width: num(at(f, "width")) || 0, height: num(at(f, "height")) || 0, fps: num(at(f, "fps")) || 0,
    bitrate: num(at(f, "bitrate")) || 0, contentLength: num(at(f, "contentLength")) || 0,
    initRange: range(at(f, "initRange")), indexRange: range(at(f, "indexRange")),
    qualityLabel: str(at(f, "qualityLabel")) || "", sampleRate: num(at(f, "audioSampleRate")) || 0,
    channels: num(at(f, "audioChannels")) || 2,
    audioTrack: str(at(f, "audioTrack.id")), audioTrackName: str(at(f, "audioTrack.displayName")),
    audioDefault: def === null ? true : def === "true", drc: bool(at(f, "isDrc")),
  };
}

async function player(id, c) {
  var d = await call("player", { videoId: id, contentCheckOk: true, racyCheckOk: true }, c);
  var ps = at(d, "playabilityStatus");
  var status = str(at(ps, "status")) || "ERROR";
  if (status !== "OK") {
    var es = at(ps, "errorScreen");
    var reason = str(at(ps, "reason")) || (es ? text(findFirst(es, "reason")) : null) || "This video is unavailable";
    var kind = status === "LOGIN_REQUIRED" && /age/i.test(reason) ? "AGE" : status === "LOGIN_REQUIRED" ? "LOGIN" : "UNAVAILABLE";
    throw ytError(reason, kind);
  }
  var vd = at(d, "videoDetails");
  var sd = at(d, "streamingData");
  var formats = arr(at(sd, "adaptiveFormats")).map(format).filter(function (f) { return f && !f.drc; });
  var hls = str(at(sd, "hlsManifestUrl"));
  if (!formats.length && !hls) throw ytError("No playable streams", "UNAVAILABLE");
  var caps = arr(at(d, "captions.playerCaptionsTracklistRenderer.captionTracks")).map(function (t) {
    var url = str(at(t, "baseUrl"));
    return url ? { lang: str(at(t, "languageCode")) || "", name: text(at(t, "name")) || "", url: url, auto: str(at(t, "kind")) === "asr" } : null;
  }).filter(function (x) { return x; });
  return {
    id: id, title: str(at(vd, "title")) || "", author: str(at(vd, "author")) || "", channelId: str(at(vd, "channelId")) || "",
    lengthSec: num(at(vd, "lengthSeconds")) || 0, views: num(at(vd, "viewCount")) || 0,
    description: str(at(vd, "shortDescription")) || "", keywords: arr(at(vd, "keywords")).map(str).filter(function (s) { return s !== null; }),
    live: bool(at(vd, "isLive")), hls: hls,
    video: formats.filter(function (f) { return f.mime.indexOf("video") === 0; }),
    audio: formats.filter(function (f) { return f.mime.indexOf("video") !== 0; }),
    captions: caps, expiresAt: nowSec() + (num(at(sd, "expiresInSeconds")) || 18000), client: c.name,
  };
}

async function details(id) {
  var d = await call("next", { videoId: id });
  var watch = at(d, "contents.twoColumnWatchNextResults");
  var primary = watch ? findFirst(watch, "videoPrimaryInfoRenderer") : null;
  var owner = watch ? findFirst(watch, "videoOwnerRenderer") : null;
  var like = watch ? findFirst(watch, "segmentedLikeDislikeButtonViewModel") : null;
  var related = at(watch, "secondaryResults.secondaryResults");
  var panels = arr(at(d, "engagementPanels")).map(function (p) { return at(p, "engagementPanelSectionListRenderer"); });
  var comments = panels.filter(function (p) { return str(at(p, "panelIdentifier")) === "engagement-panel-comments-section"; })[0] || null;
  var token = comments && at(comments, "content") ? str(findFirst(at(comments, "content"), "token")) : null;
  if (!token && watch) {
    var secs = findAll(watch, "itemSectionRenderer").filter(function (s) { return str(at(s, "sectionIdentifier")) === "comment-item-section"; });
    if (secs.length) token = str(findFirst(secs[0], "token"));
  }
  return {
    likesText: str(at(like, "likeButtonViewModel.likeButtonViewModel.toggleButtonViewModel.toggleButtonViewModel.defaultButtonViewModel.buttonViewModel.title")) || "",
    subsText: text(at(owner, "subscriberCountText")) || "",
    avatar: bestImage(at(owner, "thumbnail")),
    publishedText: text(at(primary, "relativeDateText")) || text(at(primary, "dateText")) || "",
    viewsText: text(at(primary, "viewCount.videoViewCountRenderer.viewCount")) || "",
    related: parseItems(related), relatedNext: continuationOf(related), commentsToken: token,
  };
}

async function relatedMore(token) {
  var d = await call("next", { continuation: token });
  var root = at(d, "onResponseReceivedEndpoints");
  return { items: parseItems(root), next: continuationOf(root) };
}

/** one page of comments, or of replies when token is a thread's replies token */
async function comments(token) {
  var d = await call("next", { continuation: token });
  var payloads = {};
  arr(at(d, "frameworkUpdates.entityBatchUpdate.mutations")).forEach(function (m) {
    var p = at(m, "payload.commentEntityPayload");
    var cid = str(at(p, "properties.commentId"));
    if (p && cid) payloads[cid] = p;
  });
  var items = [];
  arr(at(d, "onResponseReceivedEndpoints")).forEach(function (e) {
    items = items.concat(arr(at(e, "reloadContinuationItemsCommand.continuationItems") || at(e, "appendContinuationItemsAction.continuationItems")));
  });
  var out = [];
  items.forEach(function (it) {
    var thread = at(it, "commentThreadRenderer");
    var vm = (thread ? at(thread, "commentViewModel.commentViewModel") : null) || at(it, "commentViewModel");
    if (!vm) return;
    var cid = str(at(vm, "commentId"));
    var p = cid ? payloads[cid] : null;
    if (!p) return;
    var replies = thread ? at(thread, "replies") : null;
    out.push({
      id: cid, author: str(at(p, "author.displayName")) || "", avatar: https(str(at(p, "author.avatarThumbnailUrl"))),
      text: str(at(p, "properties.content.content")) || "", likes: (str(at(p, "toolbar.likeCountNotliked")) || "").trim(),
      replies: countOf(str(at(p, "toolbar.replyCount"))), published: str(at(p, "properties.publishedTime")) || "",
      channelId: str(at(p, "author.channelId")) || "", repliesToken: replies ? str(findFirst(replies, "token")) : null,
      byCreator: bool(at(p, "author.isCreator")), pinned: at(vm, "pinnedText") !== null,
    });
  });
  var last = items.length ? items[items.length - 1] : null;
  var cr = last ? at(last, "continuationItemRenderer") : null;
  return { items: out, next: cr ? str(findFirst(cr, "token")) : null };
}

function metaRows(head) {
  return arr(at(head, "metadata.contentMetadataViewModel.metadataRows")).map(function (r) {
    return arr(at(r, "metadataParts")).map(function (p) { return text(at(p, "text")); }).filter(function (t) { return t !== null; });
  });
}

async function channel(id, tabParams) {
  var d = await call("browse", { browseId: id, params: decodeURIComponent(tabParams) });
  var tabs = arr(at(d, "contents.twoColumnBrowseResultsRenderer.tabs"));
  var sel = null;
  for (var i = 0; i < tabs.length; i++) if (bool(at(tabs[i], "tabRenderer.selected"))) { sel = at(tabs[i], "tabRenderer.content"); break; }
  if (sel === null && tabs.length) sel = at(tabs[0], "tabRenderer.content");
  var head = at(d, "header") ? findFirst(at(d, "header"), "pageHeaderViewModel") : null;
  var rows = [].concat.apply([], metaRows(head));
  var stats = rows.filter(function (r) { return r.indexOf("@") !== 0 && /\d/.test(r); });
  var old = at(d, "header.c4TabbedHeaderRenderer");
  var meta = at(d, "metadata.channelMetadataRenderer");
  return {
    id: id,
    name: text(at(head, "title")) || str(at(old, "title")) || str(at(meta, "title")) || "",
    avatar: bestImage(at(head, "image") || at(old, "avatar") || at(meta, "avatar")),
    banner: bestImage(at(head, "banner") || at(old, "banner")),
    handle: rows.filter(function (r) { return r.indexOf("@") === 0; })[0] || "",
    subsText: stats[0] || text(at(old, "subscriberCountText")) || "",
    videoCountText: stats[1] || "",
    items: { items: parseItems(sel), next: continuationOf(sel) },
  };
}

async function browseMore(token) {
  var d = await call("browse", { continuation: token });
  var root = at(d, "onResponseReceivedActions");
  return { items: parseItems(root), next: continuationOf(root) };
}

async function playlist(id) {
  var d = await call("browse", { browseId: id.indexOf("VL") === 0 ? id : "VL" + id });
  var head = at(d, "header") ? findFirst(at(d, "header"), "pageHeaderViewModel") : null;
  var rows = metaRows(head);
  var flat = [].concat.apply([], rows);
  var content = at(d, "contents");
  return {
    id: id,
    title: text(at(head, "title")) || str(at(d, "metadata.playlistMetadataRenderer.title")) || "",
    author: rows.length && rows[0].length ? rows[0][0] : "",
    countText: flat.filter(function (r) { return /\d/.test(r); })[0] || "",
    items: { items: parseItems(content).filter(function (x) { return x.type === "video"; }), next: continuationOf(content) },
  };
}

/** youtube.com/@handle, /c/name, /watch?v=, youtu.be/... → [kind, id] */
async function resolve(url) {
  var m;
  if ((m = /(?:v=|youtu\.be\/|shorts\/|live\/|embed\/)([\w-]{11})/.exec(url))) return ["video", m[1]];
  if ((m = /list=([\w-]+)/.exec(url))) return ["playlist", m[1]];
  if ((m = /channel\/(UC[\w-]{22})/.exec(url))) return ["channel", m[1]];
  var full = url.indexOf("@") === 0 ? "https://www.youtube.com/" + url : url;
  var d = await call("navigation/resolve_url", { url: full });
  var b = str(at(d, "endpoint.browseEndpoint.browseId"));
  if (b) return ["channel", b];
  var v = str(at(d, "endpoint.watchEndpoint.videoId"));
  if (v) return ["video", v];
  return null;
}

// RSS failed lately (YouTube takes it down for hours now and then): go straight to the channel page
var rssDownUntil = 0;
var TAB_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA";

/** "3 days ago" / "3일 전" → seconds; null when it can't be read */
function ago(t) {
  var m = /(\d+)\s*(second|minute|hour|day|week|month|year|초|분|시간|일|주|개월|달|년)/i.exec(t || "");
  if (!m) return null;
  var unit = { second: 1, "초": 1, minute: 60, "분": 60, hour: 3600, "시간": 3600, day: 86400, "일": 86400,
    week: 604800, "주": 604800, month: 2592000, "개월": 2592000, "달": 2592000 }[m[2].toLowerCase()] || 31536000;
  return parseInt(m[1], 10) * unit;
}

function unxml(s) {
  return s.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&apos;/g, "'");
}

/** a channel's latest uploads: its RSS feed, or when that fails its Videos tab */
async function feed(channelId) {
  if (Date.now() > rssDownUntil) {
    try {
      return await rss(channelId);
    } catch (e) {
      if (e.kind === "NETWORK" && e.code && !(e.code >= 500 && e.code <= 599) && e.code !== 404) throw e;
      rssDownUntil = Date.now() + 3600000;
    }
  }
  var c = await channel(channelId, TAB_VIDEOS);
  var now = nowSec();
  return c.items.items.filter(function (x) { return x.type === "video"; }).map(function (v) {
    var a = ago(v.publishedText);
    v.author = v.author || c.name;
    v.channelId = channelId;
    v.publishedAt = a === null ? 0 : now - a;
    return v;
  });
}

async function rss(channelId) {
  var xml = await get("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
  var am = /<author>\s*<name>([^<]*)<\/name>/.exec(xml);
  var author = am ? unxml(am[1]) : "";
  var out = [];
  var re = /<entry>([\s\S]*?)<\/entry>/g, m;
  while ((m = re.exec(xml))) {
    var e = m[1];
    var idm = /<yt:videoId>([\w-]{11})<\/yt:videoId>/.exec(e);
    if (!idm) continue;
    var tm = /<title>([^<]*)<\/title>/.exec(e);
    var pm = /<published>([^<]+)<\/published>/.exec(e);
    var vm = /views="(\d+)"/.exec(e);
    var pub = pm ? Date.parse(pm[1]) : NaN;
    out.push(video({
      id: idm[1], title: tm ? unxml(tm[1]) : "", author: author, channelId: channelId,
      views: vm ? parseInt(vm[1], 10) : 0, publishedAt: isNaN(pub) ? 0 : Math.floor(pub / 1000),
      short: e.indexOf("/shorts/") >= 0,
    }));
  }
  return out;
}

var OPS = {
  search: search, searchMore: searchMore, suggestions: suggestions, stream: stream, details: details,
  relatedMore: relatedMore, comments: comments, channel: channel, browseMore: browseMore, playlist: playlist,
  resolve: resolve, feed: feed,
};

// for the PC test (tools: node engine-test.mjs); ignored in the app
if (typeof module !== "undefined") module.exports = { OPS: OPS, ENGINE_VERSION: ENGINE_VERSION };
