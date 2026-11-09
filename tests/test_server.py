"""Unit tests for the parts of the TubeHaus server that don't need the network.

    python3 -m unittest discover -s tests
"""
import base64
import importlib.util
import json
import os
import pathlib
import re
import tempfile
import time
import unittest
import urllib.error

STATE = tempfile.mkdtemp(prefix="tubehaus-test-")
os.environ.update(HT_STATE=STATE, HT_YT_PROXY="", HT_WARP_ROTATE="0")
spec = importlib.util.spec_from_file_location(
    "tubehaus_server", pathlib.Path(__file__).resolve().parent.parent / "server" / "tubehaus_server.py")
ts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ts)


class BotDetection(unittest.TestCase):
    def test_bot_wall(self):
        d = {"playabilityStatus": {"status": "LOGIN_REQUIRED",
                                   "reason": "Sign in to confirm you're not a bot"}}
        self.assertTrue(ts.yt_blocked(d))

    def test_login_required_without_reason_is_the_bot_wall(self):
        self.assertTrue(ts.yt_blocked({"playabilityStatus": {"status": "LOGIN_REQUIRED"}}))

    def test_age_gate_is_not_a_bot_wall(self):
        d = {"playabilityStatus": {"status": "LOGIN_REQUIRED",
                                   "reason": "Sign in to confirm your age"}}
        self.assertFalse(ts.yt_blocked(d))

    def test_unavailable_video_is_not_a_bot_wall(self):
        self.assertFalse(ts.yt_blocked({"playabilityStatus": {"status": "ERROR", "reason": "Video unavailable"}}))

    def test_html_body(self):
        self.assertTrue(ts.yt_blocked(body="Sign in to confirm you’re not a bot".encode()))
        self.assertFalse(ts.yt_blocked(body=b"<html>ok</html>"))


class LiveWindow(unittest.TestCase):
    def playlist(self, last):
        lines = ["#EXTM3U", "#EXT-X-MEDIA-SEQUENCE:%d" % (last - 2)]
        for sq in range(last - 2, last + 1):
            lines += ["#EXTINF:1.0,", "https://rr1.googlevideo.com/videoplayback/sq/%d/goap/x" % sq]
        return "\n".join(lines) + "\n"

    def decode(self, line):
        b64 = line[len(ts.LIVE_BASE):-len(".ts")]
        return base64.urlsafe_b64decode(b64 + "=" * (-len(b64) % 4)).decode()

    def test_window_is_rebuilt_ending_at_the_newest_segment(self):
        out = ts.live_window(self.playlist(500))
        segs = [l for l in out.splitlines() if l.startswith(ts.LIVE_BASE)]
        self.assertEqual(len(segs), ts.LIVE_WINDOW)
        first = 500 - ts.LIVE_WINDOW + 1
        self.assertIn("#EXT-X-MEDIA-SEQUENCE:%d" % first, out)
        self.assertEqual([ts._sq(self.decode(s)) for s in segs], list(range(first, 501)))

    def test_window_never_goes_below_zero(self):
        out = ts.live_window(self.playlist(5))
        self.assertIn("#EXT-X-MEDIA-SEQUENCE:0", out)
        self.assertEqual(sum(1 for l in out.splitlines() if l.startswith(ts.LIVE_BASE)), 6)

    def test_playlist_without_sq_is_only_proxied(self):
        text = "#EXTM3U\n#EXTINF:1.0,\nhttps://example.com/seg1.ts\n"
        out = ts.live_window(text)
        seg = [l for l in out.splitlines() if l.startswith(ts.LIVE_BASE)]
        self.assertEqual([self.decode(s) for s in seg], ["https://example.com/seg1.ts"])


class TransportStream(unittest.TestCase):
    @staticmethod
    def packet(pts):
        b = bytes([0x21 | ((pts >> 29) & 0x0E), (pts >> 22) & 0xFF, 0x01 | ((pts >> 14) & 0xFE),
                   (pts >> 7) & 0xFF, 0x01 | ((pts << 1) & 0xFE)])
        pes = b"\x00\x00\x01\xe0\x00\x00\x80\x80\x05" + b
        pk = bytes([0x47, 0x40, 0x100 >> 8 & 0x1F, 0x10]) + pes
        return pk + b"\xff" * (188 - len(pk))

    def test_first_video_pts(self):
        for pts in (0, 90000, 2**32 + 12345):
            self.assertEqual(ts.ts_first_pts(self.packet(pts)), pts)

    def test_no_video_packet(self):
        self.assertIsNone(ts.ts_first_pts(b"\x47" + b"\x00" * 187))


class Subtitles(unittest.TestCase):
    def test_vtt_cues(self):
        vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\nworld\n\n00:00:03.000 --> 00:00:04.000\nBye\n"
        self.assertEqual(ts._vtt_cues(vtt), [("00:00:01.000 --> 00:00:02.000", ["Hello", "world"]),
                                             ("00:00:03.000 --> 00:00:04.000", ["Bye"])])

    def test_subtitle_cache_path_cannot_escape(self):
        for vid, tl in (("../../etc/x", "en"), ("jNQXAC9IVRw", "../../x"), ("jNQXAC9IVRw", "en/../..")):
            with self.assertRaises(ValueError):
                ts.sub_vtt(vid, tl)

    def test_cc_time(self):
        self.assertAlmostEqual(ts._cc_time("01:02:03.500"), 3723.5)


class Library(unittest.TestCase):
    def setUp(self):
        if os.path.exists(ts.YTLIB):
            os.remove(ts.YTLIB)

    def test_resume_position_and_history(self):
        meta = {"id": "jNQXAC9IVRw", "title": "Me at the zoo", "author": "jawed", "len": 19}
        code, _ = ts.ytlib_post("/api/p/yt/pos", {"id": "jNQXAC9IVRw", "t": 7, "len": 19, "meta": meta})
        self.assertEqual(code, 200)
        self.assertEqual(ts.ytlib_query("/api/p/yt/pos", {"ids": "jNQXAC9IVRw"}), {"jNQXAC9IVRw": [7, 19]})
        hist = ts.ytlib_query("/api/p/yt/hist", {})
        self.assertEqual(hist["total"], 1)
        self.assertEqual(hist["items"][0]["title"], "Me at the zoo")

    def test_bad_ids_are_rejected(self):
        self.assertEqual(ts.ytlib_post("/api/p/yt/pos", {"id": "../../etc", "t": 1})[0], 400)
        self.assertEqual(ts.ytlib_post("/api/p/yt/sub", {"id": "not-a-channel", "on": True})[0], 400)
        self.assertEqual(ts.ytlib_query("/api/p/yt/pos", {"ids": "bad,also bad"}), {})

    def test_app_playlists_keep_their_id_and_reorder(self):
        pid = "HP" + "1" * 16
        code, r = ts.ytlib_post("/api/p/yt/pl/new", {"title": "Mix", "id": pid, "video": {"id": "aaaaaaaaaaa"}})
        self.assertEqual((code, r["id"]), (200, pid))
        code, r = ts.ytlib_post("/api/p/yt/pl/new", {"title": "Again", "id": pid})    # taken: the server picks one
        self.assertNotEqual(r["id"], pid)
        ts.ytlib_post("/api/p/yt/list", {"list": pid, "on": True, "video": {"id": "bbbbbbbbbbb"}})
        ts.ytlib_post("/api/p/yt/list", {"list": pid, "on": True, "video": {"id": "ccccccccccc"}})
        self.assertEqual(ts.ytlib_post("/api/p/yt/pl/move", {"id": pid, "from": 2, "to": 0})[0], 200)
        self.assertEqual(ts.ytlib_query("/api/p/yt/pl", {"id": pid})["items"][0]["id"], "ccccccccccc")
        self.assertEqual(ts.ytlib_post("/api/p/yt/pl/move", {"id": pid, "from": 9, "to": 0})[0], 400)

    def test_history_search_and_delete(self):
        for vid, title in (("aaaaaaaaaaa", "Cooking pasta"), ("bbbbbbbbbbb", "Live news")):
            ts.ytlib_post("/api/p/yt/pos", {"id": vid, "t": 1, "len": 60, "meta": {"id": vid, "title": title}})
        self.assertEqual(ts.ytlib_query("/api/p/yt/hist", {"q": "pasta"})["total"], 1)
        ts.ytlib_post("/api/p/yt/hist/del", {"id": "aaaaaaaaaaa"})
        self.assertEqual([x["id"] for x in ts.ytlib_query("/api/p/yt/hist", {})["items"]], ["bbbbbbbbbbb"])


class ShortsRecommender(unittest.TestCase):
    CAT, DOG = "UC" + "c" * 22, "UC" + "d" * 22

    def setUp(self):
        if os.path.exists(ts.SHORTS):
            os.remove(ts.SHORTS)

    def watch(self, vid, ch, watched, length=30, **kw):
        body = {"id": vid, "authorId": ch, "author": ch[-1], "title": kw.pop("title", ""), "watched": watched, "len": length}
        body.update(kw)
        return ts.shorts_signal(body)

    def test_engagement_order(self):
        e = ts._sh_engagement
        self.assertLess(e(1, 30), 0)                      # swiped away
        self.assertLess(e(10, 30), e(30, 30))             # partial < full
        self.assertLess(e(30, 30), e(90, 30))             # watched < looped
        self.assertLess(e(30, 30), e(30, 30, liked=True))
        self.assertLess(e(30, 30, disliked=True), e(1, 30))

    def test_tokens(self):
        t = ts._sh_tokens("Funny cat #CatsOfInstagram #shorts", ["Cat", "kitten", "viral"], "Pets & Animals")
        self.assertEqual(t, ["cat", "kitten", "catsofinstagram", "genre:pets & animals"])

    def test_signals_build_channel_and_keyword_taste(self):
        self.watch("aaaaaaaaaaa", self.CAT, 60, keywords=["cat"])
        self.watch("bbbbbbbbbbb", self.DOG, 1, keywords=["dog"])
        d = ts._sh_load()
        self.assertGreater(d["ch"][self.CAT], 0)
        self.assertLess(d["ch"][self.DOG], 0)
        self.assertGreater(d["kw"]["cat"], 0)
        self.assertIn("aaaaaaaaaaa", d["seen"])
        self.assertEqual(self.watch("../etc/pass", self.CAT, 1)[0], 400)

    def test_ranking_prefers_taste_skips_seen_and_diversifies(self):
        import random
        for i, vid in enumerate(("aaaaaaaaaaa", "aaaaaaaaaab")):
            self.watch(vid, self.CAT, 90, liked=True)
        for vid in ("ddddddddddd", "dddddddddde", "ddddddddddf"):
            self.watch(vid, self.DOG, 0.5, disliked=True)
        d = ts._sh_load()
        items = [{"id": "c%010d" % i, "title": "", "authorId": self.CAT, "views": 10, "src": "kw"} for i in range(5)]
        items += [{"id": "d%010d" % i, "title": "", "authorId": self.DOG, "views": 10**7, "src": "kw"} for i in range(5)]
        items += [{"id": "aaaaaaaaaaa", "title": "", "authorId": self.CAT, "views": 1, "src": "kw"}]
        out = ts.shorts_rank(d, items, 5, rnd=random.Random(1))
        ids = [v["id"] for v in out]
        self.assertNotIn("aaaaaaaaaaa", ids)                               # already seen
        self.assertTrue(all(v["authorId"] != self.DOG for v in out))       # swiped away + disliked
        self.assertLessEqual(sum(v["authorId"] == self.CAT for v in out), 2)  # at most 2 per channel

    def test_reset(self):
        self.watch("aaaaaaaaaaa", self.CAT, 60)
        ts.shorts_reset()
        self.assertEqual(ts._sh_load()["ch"], {})


class ImportExport(unittest.TestCase):
    A, B = "UC" + "a" * 22, "UC" + "b" * 22

    def setUp(self):
        for f in (ts.YTLIB, ts.SHORTS):
            if os.path.exists(f):
                os.remove(f)
        ts._yt_import_fill = lambda *a: None          # no network in tests

    def parse(self, text):
        return ts.ytlib_parse_import(text)

    def test_takeout_csv(self):
        p = self.parse("﻿Channel Id,Channel Url,Channel Title\n"
                       f"{self.A},http://www.youtube.com/channel/{self.A},Cat Channel\n"
                       f"{self.B},http://www.youtube.com/channel/{self.B},\"Dogs, Inc\"\n")
        self.assertEqual(p["format"], "csv")
        self.assertEqual(p["subs"][self.A]["name"], "Cat Channel")
        self.assertEqual(p["subs"][self.B]["name"], "Dogs, Inc")

    def test_invidious_export(self):
        import json
        p = self.parse(json.dumps({"subscriptions": [self.A, self.B], "watch_history": ["jNQXAC9IVRw"],
                                   "playlists": [{"title": "Mix", "videos": ["jNQXAC9IVRw", "bad"]}]}))
        self.assertEqual(p["format"], "invidious")
        self.assertEqual(set(p["subs"]), {self.A, self.B})
        self.assertEqual([h["id"] for h in p["hist"]], ["jNQXAC9IVRw"])
        self.assertEqual([x["id"] for x in p["playlists"][0]["items"]], ["jNQXAC9IVRw"])

    def test_newpipe(self):
        import json
        p = self.parse(json.dumps({"app_version": "0.27", "subscriptions": [
            {"service_id": 0, "url": f"https://www.youtube.com/channel/{self.A}", "name": "Cat Channel"},
            {"service_id": 1, "url": "https://soundcloud.com/x", "name": "Not YouTube"}]}))
        self.assertEqual(p["format"], "newpipe")
        self.assertEqual(p["subs"], {self.A: {"name": "Cat Channel", "thumb": ""}})

    def test_freetube_profiles(self):
        import json
        lines = [json.dumps({"name": "All Channels", "subscriptions": [{"id": self.A, "name": "Cat", "thumbnail": "t"}]}),
                 json.dumps({"name": "Music", "subscriptions": [{"id": self.B, "name": "Dog", "thumbnail": ""}]})]
        p = self.parse("\n".join(lines))
        self.assertEqual(p["format"], "freetube")
        self.assertEqual(set(p["subs"]), {self.A, self.B})

    def test_opml(self):
        p = self.parse('<?xml version="1.0"?><opml version="1.1"><body><outline text="YouTube">'
                       f'<outline text="Cats &amp; Co" title="Cats &amp; Co" type="rss" '
                       f'xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id={self.A}"/></outline></body></opml>')
        self.assertEqual(p["format"], "opml")
        self.assertEqual(p["subs"][self.A]["name"], "Cats & Co")

    def test_takeout_watch_history(self):
        import json
        p = self.parse(json.dumps([{"header": "YouTube", "title": "Watched Me at the zoo",
                                    "titleUrl": "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                                    "subtitles": [{"name": "jawed", "url": f"https://www.youtube.com/channel/{self.A}"}],
                                    "time": "2024-05-01T10:00:00.000Z"}]))
        self.assertEqual(p["format"], "takeout-history")
        h = p["hist"][0]
        self.assertEqual((h["id"], h["title"], h["author"], h["authorId"]), ("jNQXAC9IVRw", "Me at the zoo", "jawed", self.A))
        self.assertGreater(h["at"], 1700000000)

    def test_garbage_is_rejected(self):
        self.assertEqual(ts.ytlib_import({"text": "hello world"})[0], 400)
        self.assertEqual(ts.ytlib_import({"text": "{\"a\": 1}"})[0], 400)

    def test_backup_round_trip(self):
        ts.ytlib_post("/api/p/yt/sub", {"id": self.A, "name": "Cat Channel", "on": True})
        ts.ytlib_post("/api/p/yt/pos", {"id": "jNQXAC9IVRw", "t": 7, "len": 19,
                                         "meta": {"id": "jNQXAC9IVRw", "title": "Me at the zoo"}})
        ts.ytlib_post("/api/p/yt/pl/new", {"title": "Mix", "video": {"id": "jNQXAC9IVRw", "title": "Me at the zoo"}})
        name, ctype, data = ts.ytlib_export("tubehaus")
        self.assertTrue(name.startswith("tubehaus-backup-"))
        os.remove(ts.YTLIB)
        code, n = ts.ytlib_import({"text": data.decode()})
        self.assertEqual((code, n["format"], n["subs"], n["hist"], n["playlists"]), (200, "tubehaus", 1, 1, 1))
        lib = ts.ytlib_get()
        self.assertEqual(lib["subs"][self.A]["name"], "Cat Channel")
        self.assertEqual(ts.ytlib_query("/api/p/yt/pos", {"ids": "jNQXAC9IVRw"}), {"jNQXAC9IVRw": [7, 19]})
        # importing the same file again adds nothing
        self.assertEqual(ts.ytlib_import({"text": data.decode()})[1]["playlists"], 0)

    def test_other_export_formats(self):
        ts.ytlib_post("/api/p/yt/sub", {"id": self.A, "name": "Cats & \"Co\"", "on": True})
        for fmt in ("invidious", "csv", "opml"):
            _, _, data = ts.ytlib_export(fmt)
            p = self.parse(data.decode())
            self.assertIn(self.A, p["subs"], fmt)
            if fmt != "invidious":
                self.assertEqual(p["subs"][self.A]["name"], "Cats & \"Co\"", fmt)


class PublicMode(unittest.TestCase):
    def setUp(self):
        ts.PUBLIC_MODE = True
        for f in (ts.YTLIB, ts.SHORTS):
            if os.path.exists(f):
                os.remove(f)

    def tearDown(self):
        ts.PUBLIC_MODE = False

    def test_only_stateless_endpoints_are_open(self):
        b = ts.public_blocked
        for m, p in (("GET", "/api/p/yt/lib"), ("POST", "/api/p/yt/sub"), ("POST", "/api/p/yt/pos"),
                     ("GET", "/api/p/yt/hist"), ("GET", "/api/p/yt/cast/devices"), ("POST", "/api/p/yt/cast/send"),
                     ("POST", "/api/p/yt/dl"), ("GET", "/api/p/yt/export"), ("POST", "/api/p/yt/shorts/sig"),
                     ("GET", "/latest_version")):
            self.assertTrue(b(m, p), (m, p))
        for m, p in (("GET", "/api/p/yt/feed"), ("GET", "/api/p/yt/shorts/next"), ("POST", "/api/p/yt/import"),
                     ("POST", "/api/p/yt/export"), ("GET", "/api/v1/videos/jNQXAC9IVRw"), ("GET", "/api/p/health"),
                     ("GET", "/api/p/live/abc/cc.json")):
            self.assertFalse(b(m, p), (m, p))
        ts.PUBLIC_MODE = False
        self.assertFalse(b("GET", "/api/p/yt/lib"))

    def test_import_is_only_parsed_never_stored(self):
        code, p = ts.ytlib_import({"text": "Channel Id,Channel Url,Channel Title\nUC" + "a" * 22 + ",x,Cats\n"})
        self.assertEqual((code, p["dry"], p["format"]), (200, True, "csv"))
        self.assertIn("UC" + "a" * 22, p["subs"])
        self.assertFalse(os.path.exists(ts.YTLIB))

    def test_export_converts_the_visitors_library(self):
        lib = {"subs": {"UC" + "a" * 22: {"name": "Cats"}}, "hist": [], "playlists": {}}
        name, _, data = ts.ytlib_export("csv", lib)
        self.assertIn(b"Cats", data)
        _, _, data = ts.ytlib_export("tubehaus", lib)
        import json
        self.assertEqual(json.loads(data)["shorts"], {})

    def test_health_says_public(self):
        self.assertTrue(ts.health()["public"])


class LoadLimits(unittest.TestCase):
    def setUp(self):
        self.saved = (ts.MAX_VIEWERS, ts.RATE_PER_MIN, ts.vid_fetch)
        ts.viewers.clear()
        ts.rate.clear()

    def tearDown(self):
        ts.MAX_VIEWERS, ts.RATE_PER_MIN, ts.vid_fetch = self.saved
        ts.viewers.clear()
        ts.rate.clear()

    def test_empty_env_means_default(self):
        for v, want in (("", 7), ("  ", 7), ("abc", 7), ("12", 12)):
            os.environ["HT_TEST_INT"] = v
            self.assertEqual(ts.env_int("HT_TEST_INT", 7), want)
        del os.environ["HT_TEST_INT"]
        self.assertEqual(ts.env_int("HT_TEST_INT", 7), 7)

    def test_viewer_cap_lets_current_viewers_stay(self):
        ts.MAX_VIEWERS = 2
        self.assertTrue(ts.viewer_admit("1.1.1.1"))
        self.assertTrue(ts.viewer_admit("2.2.2.2"))
        self.assertFalse(ts.viewer_admit("3.3.3.3"))      # full
        self.assertTrue(ts.viewer_admit("1.1.1.1"))       # already watching
        ts.viewers["1.1.1.1"] -= 600                      # left a while ago
        self.assertTrue(ts.viewer_admit("3.3.3.3"))

    def test_rate_limit_per_ip(self):
        ts.RATE_PER_MIN = 5
        self.assertEqual([ts.rate_ok("9.9.9.9") for _ in range(7)], [True] * 5 + [False] * 2)
        self.assertTrue(ts.rate_ok("8.8.8.8"))            # someone else is unaffected

    def test_same_cold_video_is_fetched_once(self):
        import threading
        calls = []

        def slow_fetch(key):
            calls.append(key)
            time.sleep(0.3)
            body = b'{"videoId": "jNQXAC9IVRw"}'
            ts.vid_store(key, 200, body)
            return 200, body
        ts.vid_fetch = slow_fetch
        key = "/api/v1/videos/jNQXAC9IVRw?test=once"
        out = []
        threads = [threading.Thread(target=lambda: out.append(ts.vid_fetch_once(key))) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(out), 8)
        self.assertTrue(all(code == 200 for code, _ in out))


class Accounts(unittest.TestCase):
    def setUp(self):
        for f in (ts.USERS, ts.SESSION_KEY):
            if os.path.exists(f):
                os.remove(f)
        ts.auth_tries.clear()

    def test_signup_then_session(self):
        code, d = ts.auth_signup({"username": "Alice_1", "password": "correct horse"}, "1.1.1.1")
        self.assertEqual((code, d["user"]), (200, "alice_1"))
        self.assertEqual(ts.session_user(d["cookie"]), "alice_1")
        self.assertNotIn("correct horse", open(ts.USERS).read())          # only a hash is stored

    def test_bad_input_and_duplicates(self):
        sign = lambda u, p, **kw: ts.auth_signup(dict(username=u, password=p, **kw), "2.2.2.2")[0]
        self.assertEqual(sign("ab", "password123"), 400)                    # too short a name
        self.assertEqual(sign("bad name!", "password123"), 400)
        self.assertEqual(sign("bob", "short"), 400)
        self.assertEqual(sign("bob", "12345678"), 400)                      # too guessable
        self.assertEqual(sign("bob", "a fine password", website="spam"), 400)   # honeypot
        self.assertEqual(sign("bob", "a fine password"), 200)
        ts.auth_tries.clear()
        self.assertEqual(sign("bob", "another password"), 409)

    def test_login_and_wrong_password(self):
        ts.auth_signup({"username": "carol", "password": "carol's secret"}, "3.3.3.3")
        self.assertEqual(ts.auth_login({"username": "carol", "password": "nope nope"}, "3.3.3.3")[0], 401)
        self.assertEqual(ts.auth_login({"username": "nobody", "password": "nope nope"}, "3.3.3.3")[0], 401)
        code, d = ts.auth_login({"username": "Carol", "password": "carol's secret"}, "3.3.3.3")
        self.assertEqual((code, ts.session_user(d["cookie"])), (200, "carol"))

    def test_signup_rate_limit_per_network(self):
        codes = [ts.auth_signup({"username": f"user{i}", "password": "a fine password"}, "4.4.4.4")[0] for i in range(4)]
        self.assertEqual(codes, [200, 200, 200, 429])
        self.assertEqual(ts.auth_signup({"username": "user9", "password": "a fine password"}, "5.5.5.5")[0], 200)

    def test_tampered_or_expired_sessions_are_rejected(self):
        _, d = ts.auth_signup({"username": "dave", "password": "dave's secret"}, "6.6.6.6")
        user, exp, gen, sig = d["cookie"].split(".")
        self.assertIsNone(ts.session_user(f"mallory.{exp}.{gen}.{sig}"))      # other user, same signature
        self.assertIsNone(ts.session_user(f"{user}.{int(exp) + 999}.{gen}.{sig}"))
        self.assertIsNone(ts.session_user(f"{user}.1.{gen}.{sig}"))
        self.assertIsNone(ts.session_user("garbage"))
        old = ts.session_make("dave", 1)
        import json
        users = json.load(open(ts.USERS))
        users["dave"]["gen"] = 2                                            # password changed / sessions revoked
        json.dump(users, open(ts.USERS, "w"))
        self.assertIsNone(ts.session_user(old))


class ExactPublished(unittest.TestCase):
    """Recent videos Invidious dates at 00:00 UTC get their real time from the channel's RSS feed."""
    CID = "UC" + "a" * 22

    def setUp(self):
        self.old_get = ts.get
        self.calls = []
        ts.PUB_RSS.clear()
        ts.yt_feed["items"].clear()
        ts.RSS_DOWN["until"] = 0
        self.midnight = int(time.time()) // 86400 * 86400      # today 00:00 UTC, so within the 3 day window

    def tearDown(self):
        ts.get = self.old_get
        ts.PUB_RSS.clear()
        ts.yt_feed["items"].clear()

    def feed(self, vid, iso):
        return ('<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">'
                "<entry><yt:videoId>%s</yt:videoId><published>%s</published></entry></feed>" % (vid, iso))

    def fake_get(self, body):
        def get(url, *a, **k):
            self.calls.append(url)
            return body
        ts.get = get

    def video(self, pub):
        return {"videoId": "vid00000001", "authorId": self.CID, "published": pub}

    def iso(self, ts_):
        return time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime(ts_))

    def test_not_midnight_is_left_alone(self):
        self.fake_get(self.feed("vid00000001", self.iso(self.midnight + 3600)))
        self.assertIsNone(ts.exact_published(self.video(self.midnight + 3600)))
        self.assertEqual(self.calls, [])

    def test_old_video_is_left_alone(self):
        old = self.midnight - 4 * 86400
        self.fake_get(self.feed("vid00000001", self.iso(old + 3600)))
        self.assertIsNone(ts.exact_published(self.video(old)))
        self.assertEqual(self.calls, [])

    def test_recent_midnight_gets_time_from_feed(self):
        exact = self.midnight + 5 * 3600 + 17 * 60
        self.fake_get(self.feed("vid00000001", self.iso(exact)))
        self.assertEqual(ts.exact_published(self.video(self.midnight)), exact)
        self.assertEqual(len(self.calls), 1)

    def test_subscription_feed_cache_is_used_before_fetching(self):
        exact = self.midnight + 7 * 3600
        ts.yt_feed["items"][self.CID] = [{"id": "other", "published": 1},
                                         {"id": "vid00000001", "published": exact}]
        self.fake_get("not xml")
        self.assertEqual(ts.exact_published(self.video(self.midnight)), exact)
        self.assertEqual(self.calls, [])

    def test_feed_time_too_far_from_the_date_is_ignored(self):
        self.fake_get(self.feed("vid00000001", self.iso(self.midnight + 2 * 86400 + 60)))
        self.assertIsNone(ts.exact_published(self.video(self.midnight)))


class UsageStats(unittest.TestCase):
    """Public instances count people and plays per hour, and nothing else."""

    def setUp(self):
        self.was = ts.PUBLIC_MODE
        ts.PUBLIC_MODE = True
        ts.stats.clear()
        ts.stats_seen.update(hour="", visitors=set(), watchers=set())

    def tearDown(self):
        ts.PUBLIC_MODE = self.was
        ts.stats.clear()

    def test_people_once_an_hour_events_each_time(self):
        for ip in ("1.2.3.4", "1.2.3.4", "5.6.7.8"):
            ts.stat("visitors", ip)
        ts.stat("watchers", "1.2.3.4"); ts.stat("watchers", "1.2.3.4")
        for _ in range(3):
            ts.stat("plays")
        ts.stat("signups")
        (row,) = ts.stats.values()
        self.assertEqual(row, {"visitors": 2, "watchers": 1, "plays": 3, "signups": 1})
        # nothing that identifies anyone is kept in the counts
        self.assertNotIn("1.2.3.4", json.dumps(ts.stats))

    def test_admin_page_sums_signups_by_day(self):
        ts.stat("plays")
        d = ts.admin_stats()
        self.assertEqual(sum(r.get("plays", 0) for r in d["hours"].values()), 1)
        self.assertIn("watching", d)
        self.assertIn("signups_by_day", d)

    def test_private_servers_count_nothing(self):
        ts.PUBLIC_MODE = False
        ts.stat("visitors", "1.2.3.4")
        self.assertEqual(ts.stats, {})


class FeedFallback(unittest.TestCase):
    """YouTube's RSS feeds go down now and then (404 for every channel): the subscription feed falls back to Invidious."""
    CID = "UC" + "b" * 22

    def setUp(self):
        self.old_get = ts.get
        self.calls = []
        ts.RSS_DOWN.update(until=0, fails=0)
        ts.yt_feed["items"].clear()

    def tearDown(self):
        ts.get = self.old_get
        ts.RSS_DOWN.update(until=0, fails=0)
        ts.yt_feed["items"].clear()

    def fake(self, rss=None, rss_error=None, videos=None):
        inv = json.dumps({"videos": videos or [{"videoId": "inv00000001", "title": "From Invidious", "author": "Chan",
                                                "published": 1790000000, "viewCount": 5}]}).encode()
        def get(url, *a, **k):
            self.calls.append(url)
            if "feeds/videos.xml" in url:
                if rss_error:
                    raise urllib.error.HTTPError(url, rss_error, "Not Found", None, None)
                return rss
            return inv
        ts.get = get

    def test_rss_404_uses_invidious_and_skips_rss_for_a_while(self):
        self.fake(rss_error=404)
        ts.yt_feed_one(self.CID)
        self.assertEqual([v["id"] for v in ts.yt_feed["items"][self.CID]], ["inv00000001"])
        self.assertEqual(ts.RSS_DOWN["until"], 0)    # one 404 is probably a deleted channel
        for _ in range(ts.RSS_DOWN_AFTER - 1):
            ts.yt_feed_one(self.CID)
        self.assertGreater(ts.RSS_DOWN["until"], time.time())
        self.calls.clear()
        ts.yt_feed_one(self.CID)                     # RSS is down: straight to Invidious
        self.assertFalse(any("feeds/videos.xml" in u for u in self.calls))

    def test_invidious_just_now_with_no_views_is_not_trusted(self):
        now = int(time.time())
        bogus = {"title": "Old", "author": "Chan", "published": now, "viewCount": 0}
        self.fake(rss_error=404, videos=[dict(bogus, videoId="old00000001")])
        ts.yt_feed_one(self.CID)                     # nothing known yet: no date rather than "just now"
        self.assertEqual(ts.yt_feed["items"][self.CID][0]["published"], 0)
        ts.yt_feed["items"][self.CID] = [{"id": "old00000001", "published": 1780000000, "views": 900}]
        self.fake(rss_error=404, videos=[dict(bogus, videoId="old00000001"), dict(bogus, videoId="new00000001")])
        ts.yt_feed_one(self.CID)
        got = {v["id"]: v for v in ts.yt_feed["items"][self.CID]}
        self.assertEqual((got["old00000001"]["published"], got["old00000001"]["views"]), (1780000000, 900))
        self.assertGreaterEqual(got["new00000001"]["published"], now)   # the channel was known: really new

    def test_working_rss_is_used(self):
        rss = ('<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">'
               '<entry><yt:videoId>rss00000001</yt:videoId><title>From RSS</title>'
               '<published>2026-10-20T10:00:00+00:00</published></entry></feed>')
        self.fake(rss=rss.encode())
        ts.yt_feed_one(self.CID)
        self.assertEqual([v["id"] for v in ts.yt_feed["items"][self.CID]], ["rss00000001"])
        self.assertEqual(ts.RSS_DOWN["until"], 0)


class TopicPages(unittest.TestCase):
    """YouTube's topic pages (Music, Live, ...): shelves read from its browse answer."""

    def answer(self):
        by = {"runs": [{"text": "Some Channel", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC" + "a" * 22}}}]}
        video = {"videoRenderer": {"videoId": "aaaaaaaaaaa", "title": {"runs": [{"text": "A video"}]}, "longBylineText": by,
                                   "lengthText": {"simpleText": "1:02:03"}, "viewCountText": {"simpleText": "1,234 views"},
                                   "publishedTimeText": {"simpleText": "2 days ago"}}}
        members = {"videoRenderer": {"videoId": "bbbbbbbbbbb", "title": {"simpleText": "Members"}, "longBylineText": by,
                                     "badges": [{"metadataBadgeRenderer": {"style": "BADGE_STYLE_TYPE_MEMBERS_ONLY"}}]}}
        playlist = {"lockupViewModel": {
            "contentId": "PL" + "c" * 32, "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
            "contentImage": {"collectionThumbnailViewModel": {"primaryThumbnail": {"thumbnailViewModel": {
                "image": {"sources": [{"url": "https://i.ytimg.com/vi/ccccccccccc/hqdefault.jpg", "width": 480, "height": 360}]},
                "overlays": [{"thumbnailOverlayBadgeViewModel": {"thumbnailBadges": [{"thumbnailBadgeViewModel": {"text": "100 videos"}}]}}]}}}},
            "metadata": {"lockupMetadataViewModel": {"title": {"content": "Top 100"}, "metadata": {"contentMetadataViewModel": {"metadataRows": [
                {"metadataParts": [{"text": {"content": "Charts", "commandRuns": [{"onTap": {"innertubeCommand": {"browseEndpoint": {"browseId": "UC" + "d" * 22}}}}]}},
                                   {"text": {"content": "Playlist"}}]}]}}}}}}
        station = {"lockupViewModel": {"contentId": "RD" + "e" * 30, "contentType": "LOCKUP_CONTENT_TYPE_STATION",
                                       "metadata": {"lockupMetadataViewModel": {"title": {"content": "Mix"}}}}}
        shelf = lambda title, items: {"richSectionRenderer": {"content": {"richShelfRenderer": {
            "title": {"runs": [{"text": title}]}, "contents": [{"richItemRenderer": {"content": i}} for i in items]}}}}
        return {"contents": {"twoColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"selected": True, "content": {
            "richGridRenderer": {"contents": [shelf("Charts", [playlist, station]), shelf("Best of the week", [video, members, video]),
                                              shelf("Nothing", [station])]}}}}]}}}

    def setUp(self):
        ts.topic_cache.clear()

    def test_shelves_in_order_with_videos_and_playlists(self):
        shelves = ts.topic_shelves(self.answer())
        self.assertEqual([s["title"] for s in shelves], ["Charts", "Best of the week"])   # an empty shelf is left out
        pl = shelves[0]["items"]
        self.assertEqual(len(pl), 1)                                                    # the mix is left out
        self.assertEqual(pl[0]["type"], "playlist")
        self.assertEqual(pl[0]["videoCount"], 100)
        self.assertEqual(pl[0]["author"], "Charts")                                     # not "Playlist"
        v = shelves[1]["items"]
        self.assertEqual(len(v), 1)                                                     # members only and the repeat are left out
        self.assertEqual((v[0]["videoId"], v[0]["author"], v[0]["authorId"], v[0]["lengthSeconds"]),
                         ("aaaaaaaaaaa", "Some Channel", "UC" + "a" * 22, 3723))

    def test_grid_video_length_from_its_overlay(self):
        g = {"videoId": "fffffffffff", "title": {"simpleText": "Game"},
             "thumbnailOverlays": [{"thumbnailOverlayTimeStatusRenderer": {"text": {"simpleText": "53:08"}, "style": "DEFAULT"}}]}
        self.assertEqual(ts.topic_item("gridVideoRenderer", g)["lengthSeconds"], 3188)

    def test_unknown_topic(self):
        self.assertEqual(ts.topic_page({"key": "nope"}), {"error": "unknown topic"})

    def test_cached_then_an_old_copy_when_youtube_fails(self):
        calls = []
        real = ts.yt_browse
        try:
            ts.yt_browse = lambda body, region, hl: calls.append((body["browseId"], region, hl)) or self.answer()
            a = ts.topic_page({"key": "music", "region": "kr", "hl": "ko"})
            self.assertEqual(calls, [(ts.TOPICS["music"], "KR", "ko")])
            self.assertEqual(ts.topic_page({"key": "music", "region": "KR", "hl": "ko"}), a)
            self.assertEqual(len(calls), 1)
            ts.topic_cache[("music", "KR", "ko")] = (0, a)                              # gone stale

            def down(*args):
                raise OSError("timed out")
            ts.yt_browse = down
            self.assertEqual(ts.topic_page({"key": "music", "region": "KR", "hl": "ko"}), a)
            self.assertIn("error", ts.topic_page({"key": "live", "region": "KR", "hl": "ko"}))
        finally:
            ts.yt_browse = real


class MostReplayed(unittest.TestCase):
    """The "Most replayed" heat line, from YouTube's /next answer."""

    def answer(self, n=100, peak="2140"):
        markers = [{"startMillis": str(i * 2000), "durationMillis": "2000", "intensityScoreNormalized": (i % 10) / 9} for i in range(n)]
        ml = {"markerType": "MARKER_TYPE_HEATMAP", "markers": markers,
              "markersDecoration": {"timedMarkerDecorations": [{"decorationTimeMillis": peak, "label": {"runs": [{"text": "Most replayed"}]}}]}}
        return {"frameworkUpdates": {"entityBatchUpdate": {"mutations": [
            {"payload": {"somethingElse": {}}}, {"payload": {"macroMarkersListEntity": {"markersList": ml}}}]}}}

    def setUp(self):
        ts.heat_cache.clear()

    def test_heat_points_span_and_peak(self):
        h = ts.heat_from_next(self.answer())
        self.assertEqual(len(h["heat"]), 100)
        self.assertEqual(h["heat"][9], 1.0)
        self.assertEqual(h["span"], 200.0)
        self.assertEqual(h["peak"], 2.1)

    def test_no_heat_for_small_videos(self):
        self.assertEqual(ts.heat_from_next({})["heat"], [])
        self.assertEqual(ts.heat_from_next(self.answer(n=3))["heat"], [])
        self.assertIsNone(ts.heat_from_next(self.answer(peak=None))["peak"])

    def test_bad_id_and_cache(self):
        self.assertIn("error", ts.heat_page({"v": "../etc"}))
        calls = []
        real = ts.yt_next
        try:
            ts.yt_next = lambda vid: calls.append(vid) or self.answer()
            a = ts.heat_page({"v": "aaaaaaaaaaa"})
            self.assertEqual(ts.heat_page({"v": "aaaaaaaaaaa"}), a)
            self.assertEqual(calls, ["aaaaaaaaaaa"])
        finally:
            ts.yt_next = real

    def test_dearrow_thumb_checks_its_input(self):
        self.assertEqual(ts.dearrow_thumb({"v": "aaaaaaaaaaa", "t": "1;x"})[0], 400)
        self.assertEqual(ts.dearrow_thumb({"v": "a/b", "t": "1.5"})[0], 400)


class Sidx(unittest.TestCase):
    @staticmethod
    def box(version, timescale, first_offset, refs, earliest=0):
        n = 8 if version else 4
        body = bytes([version, 0, 0, 0]) + (1).to_bytes(4, "big") + timescale.to_bytes(4, "big")
        body += earliest.to_bytes(n, "big") + first_offset.to_bytes(n, "big")
        body += (0).to_bytes(2, "big") + len(refs).to_bytes(2, "big")
        for size, duration, *flag in refs:
            body += (size | (flag[0] if flag else 0)).to_bytes(4, "big")
            body += duration.to_bytes(4, "big") + (0x90000000).to_bytes(4, "big")
        payload = b"sidx" + body
        return (len(payload) + 4).to_bytes(4, "big") + payload

    def test_version_0(self):
        b = self.box(0, 1000, 0, [(1000, 2500), (2000, 4000), (3000, 500)])
        refs, pos = ts._sidx(b)
        self.assertEqual([r[0] for r in refs], [1000, 2000, 3000])
        self.assertEqual([r[1] for r in refs], [2.5, 4.0, 0.5])
        self.assertEqual(pos, len(b))

    def test_version_1(self):
        b = self.box(1, 90000, 0, [(500, 180000), (700, 45000)], earliest=2 ** 33)
        refs, pos = ts._sidx(b)
        self.assertEqual([r[0] for r in refs], [500, 700])
        self.assertEqual([r[1] for r in refs], [2.0, 0.5])
        self.assertEqual(pos, len(b))

    def test_first_offset_moves_the_returned_offset(self):
        for version in (0, 1):
            b = self.box(version, 1000, 64, [(100, 1000)])
            self.assertEqual(ts._sidx(b)[1], len(b) + 64)

    def test_version_1_first_offset_is_64_bit(self):
        b = self.box(1, 1000, 2 ** 32 + 5, [(100, 1000)])
        self.assertEqual(ts._sidx(b)[1], len(b) + 2 ** 32 + 5)

    def test_offset_counts_bytes_before_the_box(self):
        prefix = b"\x00" * 20
        b = self.box(0, 1000, 8, [(100, 1000)])
        self.assertEqual(ts._sidx(prefix + b)[1], len(prefix) + len(b) + 8)

    def test_reference_type_bit_is_not_part_of_the_size(self):
        b = self.box(0, 1000, 0, [(1234, 1000, 0x80000000)])
        self.assertEqual(ts._sidx(b)[0], [(1234, 1.0)])

    def test_no_references(self):
        b = self.box(0, 1000, 0, [])
        self.assertEqual(ts._sidx(b), ([], len(b)))

    def test_without_a_sidx_box(self):
        for data in (b"", b"\x00\x00\x00\x08ftyp", b"sidx", b"\x00sidx"):
            with self.assertRaises(ValueError):
                ts._sidx(data)


class Health(unittest.TestCase):
    def test_health(self):
        h = ts.health()
        self.assertTrue(h["ok"])
        self.assertFalse(h["warp_proxy"])


class InstanceLinks(unittest.TestCase):
    def test_links_are_checked(self):
        raw = json.dumps([{"name": "Hub", "url": "https://home.example/"}, {"name": "TV app", "url": "/y/tv.apk"},
                          {"name": "bad", "url": "javascript:alert(1)"}, {"name": "", "url": "https://x/"},
                          {"name": "other site", "url": "//evil.example/"}, "junk"])
        self.assertEqual(ts._links(raw), [{"name": "Hub", "url": "https://home.example/"}, {"name": "TV app", "url": "/y/tv.apk"}])
        self.assertEqual(ts._links(""), [])
        self.assertEqual(ts._links("not json"), [])
        self.assertEqual(ts._links('{"name": "x"}'), [])


class HlsOneQuality(unittest.TestCase):
    def setUp(self):
        fmt = lambda itag, size, fps, br: {"itag": itag, "url": "u", "init": "0-1", "index": "2-3", "size": size, "fps": fps,
                                           "bitrate": br, "type": 'video/mp4; codecs="avc1.64002a"'}
        self.d = {"adaptiveFormats": [fmt("137", "1920x1080", 30, 4000000), fmt("299", "1920x1080", 60, 6000000),
                                      fmt("136", "1280x720", 30, 2000000), fmt("160", "256x144", 30, 100000),
                                      {"itag": "140", "url": "u", "init": "0-1", "index": "2-3", "bitrate": 128000,
                                       "type": 'audio/mp4; codecs="mp4a.40.2"'}]}
        self._vj = ts._video_json
        ts._video_json = lambda vid: self.d

    def tearDown(self):
        ts._video_json = self._vj

    def variants(self, h):
        return re.findall(r"\n(\w{11}/\d+)\.m3u8", ts.hls_master("aaaaaaaaaaa", h))

    def test_all_qualities_without_a_cap(self):
        self.assertEqual(self.variants(0), ["aaaaaaaaaaa/160", "aaaaaaaaaaa/136", "aaaaaaaaaaa/137", "aaaaaaaaaaa/299"])

    def test_best_one_up_to_the_cap(self):
        self.assertEqual(self.variants(4320), ["aaaaaaaaaaa/299"])     # 1080p60 over 1080p30
        self.assertEqual(self.variants(720), ["aaaaaaaaaaa/136"])
        self.assertEqual(self.variants(100), ["aaaaaaaaaaa/160"])      # nothing that small: the smallest


class OtherSites(unittest.TestCase):
    """Personal parts answer only TubeHaus' own pages; client addresses can't be made up."""

    class Fake:
        def __init__(self, headers, peer="127.0.0.1"):
            self.headers, self.client_address, self.sent = headers, (peer, 1234), None
            self.close_connection = False

        def send(self, code, obj):
            self.sent = code

    def ask(self, path, headers, peer="127.0.0.1"):
        f = self.Fake(headers, peer)
        return ts.H.cross_site(f, path), f.sent

    def ip(self, xff, peer="127.0.0.1"):
        return ts.H.client_ip(self.Fake({"X-Forwarded-For": xff} if xff else {}, peer))

    def test_which_parts_are_personal(self):
        for p in ("/api/p/yt/lib", "/api/p/yt/pl/new", "/api/p/yt/dl", "/api/p/auth/login", "/api/p/admin/stats"):
            self.assertTrue(ts.personal(p), p)
        for p in ("/api/p/health", "/api/p/live/x.m3u8", "/api/p/hls/aaaaaaaaaaa.m3u8", "/api/p/sub/aaaaaaaaaaa.vtt",
                  "/api/p/yt/dathumb?v=1", "/api/p/auth/check", "/api/v1/videos/aaaaaaaaaaa", "/companion/x"):
            self.assertFalse(ts.personal(p), p)

    def test_own_page_and_apps_pass(self):
        self.assertEqual(self.ask("/api/p/yt/lib", {"Sec-Fetch-Site": "same-origin", "Origin": "https://t.example"}), (False, None))
        self.assertEqual(self.ask("/api/p/yt/lib", {}), (False, None))             # apps and scripts send neither
        self.assertEqual(self.ask("/api/p/yt/lib", {"Origin": "https://t.example", "Host": "t.example"}), (False, None))

    def test_other_sites_are_refused(self):
        self.assertEqual(self.ask("/api/p/yt/lib", {"Sec-Fetch-Site": "cross-site"}), (True, 403))
        self.assertEqual(self.ask("/api/p/yt/pl/new", {"Origin": "https://evil.example", "Host": "t.example"}), (True, 403))
        self.assertEqual(self.ask("/api/p/yt/pl/new", {"Sec-Fetch-Site": "same-site", "Origin": "https://evil.sslip.io",
                                                      "Host": "t.sslip.io"}), (True, 403))
        self.assertEqual(self.ask("/api/p/yt/pl/new", {"Origin": "null", "Host": "t.example"}), (True, 403))

    def test_playback_stays_open(self):
        self.assertEqual(self.ask("/api/p/live/aaaaaaaaaaa.m3u8", {"Sec-Fetch-Site": "cross-site"}), (False, None))

    def test_client_address(self):
        self.assertEqual(self.ip(""), "127.0.0.1")
        self.assertEqual(self.ip("9.9.9.9"), "9.9.9.9")
        self.assertEqual(self.ip("1.2.3.4, 9.9.9.9"), "9.9.9.9")          # the visitor wrote 1.2.3.4
        self.assertEqual(self.ip("9.9.9.9, 172.18.0.1"), "9.9.9.9")       # nginx, then Caddy
        self.assertEqual(self.ip("192.168.1.5"), "192.168.1.5")                   # at home
        self.assertEqual(self.ip("1.2.3.4", peer="8.8.4.4"), "8.8.4.4") # not from a proxy: ignored

    def test_client_address_behind_cloudflare(self):
        old, ts.PUBLIC_PROXIES = ts.PUBLIC_PROXIES, 1
        try:
            self.assertEqual(self.ip("1.2.3.4, 9.9.9.9, 104.16.0.1"), "9.9.9.9")
        finally:
            ts.PUBLIC_PROXIES = old


class HouseholdAccounts(unittest.TestCase):
    """HT_ACCOUNTS=1 without public mode: one library per account, password change, casting per account."""

    def setUp(self):
        self.old = (ts.ACCOUNTS, ts.PUBLIC_MODE, ts.USERS, getattr(ts._req, "user", None))
        ts.ACCOUNTS, ts.PUBLIC_MODE = True, False
        ts.USERS = os.path.join(tempfile.mkdtemp(prefix="th-users-"), "users.json")
        ts.auth_tries.clear()

    def tearDown(self):
        ts.ACCOUNTS, ts.PUBLIC_MODE, ts.USERS, ts._req.user = self.old
        ts.auth_tries.clear()

    def as_user(self, u):
        ts._req.user = u

    def test_each_account_has_its_own_library(self):
        self.as_user("hh_anna")
        self.assertEqual(ts.ytlib_post("/api/p/yt/pos", {"id": "aaaaaaaaaaa", "t": 30, "len": 100})[0], 200)
        self.as_user("hh_ben")
        self.assertNotIn("aaaaaaaaaaa", ts._yt_load()["pos"])
        ts.ytlib_post("/api/p/yt/pos", {"id": "bbbbbbbbbbb", "t": 5, "len": 100})
        self.as_user("hh_anna")
        self.assertEqual(set(ts._yt_load()["pos"]), {"aaaaaaaaaaa"})
        self.assertTrue(os.path.exists(os.path.join(ts.STATE, "users", "hh_anna", "ytlib.json")))
        self.assertIn("hh_ben", ts._lib_owners())
        self.assertIsNone(ts._lib_owners()[0])                      # and the shared one
        self.assertNotIn("bbbbbbbbbbb", ts._yt_load(None)["pos"])   # nothing went into the shared library

    def test_account_folders_stay_inside_users(self):
        for bad in ("../etc", "a/b", "..", "AB", "x", "", None, "a"*25):
            self.assertIsNone(ts._udir(bad), bad)
        self.assertTrue(ts._udir("hh_anna").endswith(os.path.join("users", "hh_anna")))

    def test_no_accounts_means_the_shared_library(self):
        ts.ACCOUNTS = False
        self.as_user("hh_anna")
        self.assertEqual(ts._ytlib_file(), ts.YTLIB)

    def test_public_instances_keep_libraries_in_browsers(self):
        ts.PUBLIC_MODE = True
        self.as_user("hh_anna")
        self.assertEqual(ts._ytlib_file(), ts.YTLIB)

    def test_first_account_takes_over_the_shared_library(self):
        ts.jsave(ts.YTLIB, {"subs": {"UCaaaaaaaaaaaaaaaaaaaaaa": {"name": "x"}}, "hist": [], "pos": {}})
        try:
            self.assertEqual(ts.auth_signup({"username": "hh_first", "password": "a fine password"}, "7.7.7.7")[0], 200)
            self.assertIn("UCaaaaaaaaaaaaaaaaaaaaaa", ts._yt_load("hh_first")["subs"])
            self.assertEqual(ts.auth_signup({"username": "hh_second", "password": "a fine password"}, "7.7.7.8")[0], 200)
            self.assertEqual(ts._yt_load("hh_second")["subs"], {})
        finally:
            os.remove(ts.YTLIB)

    def test_password_change(self):
        _, d = ts.auth_signup({"username": "hh_pw", "password": "old password 1"}, "8.8.8.1")
        old_cookie = d["cookie"]
        self.assertEqual(ts.auth_password({"old": "wrong one!", "new": "new password 2"}, "hh_pw", "8.8.8.1")[0], 403)
        self.assertEqual(ts.auth_password({"old": "old password 1", "new": "short"}, "hh_pw", "8.8.8.1")[0], 400)
        self.assertEqual(ts.auth_password({"old": "old password 1", "new": "x"}, None, "8.8.8.1")[0], 401)
        code, _, gen = ts.auth_password({"old": "old password 1", "new": "new password 2"}, "hh_pw", "8.8.8.1")
        self.assertEqual(code, 200)
        self.assertIsNone(ts.session_user(old_cookie))              # other devices are signed out
        self.assertEqual(ts.session_user(ts.session_make("hh_pw", gen)), "hh_pw")
        self.assertEqual(ts.auth_login({"username": "hh_pw", "password": "new password 2"}, "8.8.8.2")[0], 200)
        self.assertEqual(ts.auth_login({"username": "hh_pw", "password": "old password 1"}, "8.8.8.3")[0], 401)

    def test_casting_stays_within_an_account(self):
        self.as_user("hh_tvowner")
        ts.cast_wait({"id": "tv-living-room", "name": "TV", "tv": "1"})
        self.assertIn("tv-living-room", [x["id"] for x in ts.cast_devices()["devices"]])
        self.as_user("hh_other")
        self.assertNotIn("tv-living-room", [x["id"] for x in ts.cast_devices()["devices"]])
        code, _ = ts.cast_send({"to": "tv-living-room", "cmd": {"type": "toggle"}})
        self.assertEqual(code, 404)
        self.assertEqual(ts.cast_wait({"id": "tv-living-room", "name": "x"}), {"error": "bad id"})

    def test_library_calls_need_a_sign_in(self):
        class F:
            sent = None
            def send(self, code, obj): self.sent = code
        f = F()
        self.as_user(None)
        self.assertTrue(ts.H.needs_sign_in(f, "/api/p/yt/lib"))
        self.assertEqual(f.sent, 401)
        self.assertFalse(ts.H.needs_sign_in(f, "/api/p/auth/login"))
        self.assertFalse(ts.H.needs_sign_in(f, "/api/p/live/aaaaaaaaaaa.m3u8"))
        self.as_user("hh_anna")
        self.assertFalse(ts.H.needs_sign_in(f, "/api/p/yt/lib"))



if __name__ == "__main__":
    unittest.main()
