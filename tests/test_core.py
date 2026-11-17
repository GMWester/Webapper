"""Offline tests: network calls are replaced with fakes."""

import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from webapper import core  # noqa: E402
from webapper.platforms import PLATFORM  # noqa: E402


def make_xpi(path, ext_id="test@example.com"):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("manifest.json", json.dumps({
            "manifest_version": 2, "name": "Test", "version": "1",
            "browser_specific_settings": {"gecko": {"id": ext_id}},
        }))


class CoreTests(unittest.TestCase):
    def test_normalize_url(self):
        self.assertEqual(core.normalize_url("example.com"), "https://example.com")
        self.assertEqual(core.normalize_url(" http://a.b/c?d "), "http://a.b/c?d")
        with self.assertRaises(core.WebApperError):
            core.normalize_url("")
        with self.assertRaises(core.WebApperError):
            core.normalize_url("ftp://x")

    def test_split_extensions(self):
        self.assertEqual(core.split_extension_input("a\nb, a\n\n c"), ["a", "b", "c"])
        self.assertEqual(core.split_extension_input(["a b", "c"]), ["a", "b", "c"])

    def test_amo_slug(self):
        self.assertEqual(core.amo_slug("https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/"), "ublock-origin")
        self.assertEqual(core.amo_slug("https://addons.mozilla.org/firefox/addon/darkreader?x=1"), "darkreader")
        self.assertEqual(core.amo_slug("https://addons.mozilla.org/android/addon/foo/"), "foo")
        self.assertIsNone(core.amo_slug("https://example.com/x.xpi"))

    def test_resolve_amo(self):
        api = {"guid": "uBlock0@raymondhill.net", "name": {"en-US": "uBlock Origin"},
               "current_version": {"file": {"url": "https://addons.mozilla.org/x.xpi"}}}
        with mock.patch.object(core, "http_get", return_value=json.dumps(api).encode()) as g:
            ext = core.resolve_extension("https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/")
            self.assertIn("/addons/addon/ublock-origin/", g.call_args[0][0])
        self.assertEqual(ext.id, "uBlock0@raymondhill.net")
        self.assertEqual(ext.name, "uBlock Origin")
        self.assertEqual(ext.xpi_url, "https://addons.mozilla.org/x.xpi")
        with mock.patch.object(core, "http_get", return_value=json.dumps(api).encode()):
            self.assertEqual(core.resolve_extension("ublock-origin").id, "uBlock0@raymondhill.net")
        ext = core.resolve_extension("https://example.com/my.xpi")
        self.assertEqual(ext.xpi_url, "https://example.com/my.xpi")
        with self.assertRaises(core.WebApperError):
            core.resolve_extension("not a link!")

    def test_read_xpi_id(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "a.xpi")
            make_xpi(p, "abc@def")
            self.assertEqual(core.read_xpi_id(p)[0], "abc@def")
            # id only in the signing certificate CN
            p2 = os.path.join(d, "b.xpi")
            cn = b"{1234-5678}"
            der = b"\x30\x00" + bytes.fromhex("0603550403") + b"\x0c" + bytes([len(cn)]) + cn
            with zipfile.ZipFile(p2, "w") as z:
                z.writestr("manifest.json", json.dumps({"name": "x"}))
                z.writestr("META-INF/mozilla.rsa", der)
            self.assertEqual(core.read_xpi_id(p2)[0], "{1234-5678}")

    def test_build_profile(self):
        with tempfile.TemporaryDirectory() as d:
            x = os.path.join(d, "e.xpi")
            make_xpi(x)
            prof = os.path.join(d, "profile")
            core.build_profile(prof, "https://example.com", [core.Extension("s", "test@example.com", "T", "", x)])
            with open(os.path.join(prof, "user.js")) as f:
                ujs = f.read()
            self.assertIn('user_pref("browser.startup.homepage", "https://example.com");', ujs)
            self.assertIn('user_pref("extensions.autoDisableScopes", 0);', ujs)
            self.assertTrue(os.path.exists(os.path.join(prof, "extensions", "test@example.com.xpi")))
            with open(os.path.join(prof, "chrome", "userChrome.css")) as f:
                css = f.read()
            # add-ons installed -> slim bar keeping the add-ons (puzzle) button
            self.assertIn("webextension-browser-action", css)
            self.assertNotIn("#nav-bar { visibility: collapse", css)
            core.build_profile(prof, "https://example.com", [])
            with open(os.path.join(prof, "chrome", "userChrome.css")) as f:
                self.assertIn("#nav-bar { visibility: collapse", f.read())

    def test_user_chrome_variants(self):
        self.assertIn("#urlbar-container", core.user_chrome_css(True, True))
        addons_css = core.user_chrome_css(False, True)
        self.assertIn("justify-content: flex-end", addons_css)
        # the puzzle button lives inside #nav-bar-customization-target: it must not be hidden
        self.assertIn(":not(.webextension-browser-action, #unified-extensions-button)", addons_css)
        self.assertIn("#unified-extensions-button { display: flex !important; }", addons_css)
        self.assertIn("#nav-bar { visibility: collapse", core.user_chrome_css(False, False))

    def test_letter_icon(self):
        img = core.letter_icon("Example", 64)
        self.assertEqual(img.size, (64, 64))


class FakeRuntimeMixin:
    """Replaces runtime download with a fake firefox executable."""

    def fake_runtime(self, runtime_dir, channel, log, fresh_copy=False):
        exe = PLATFORM.firefox_binary(runtime_dir)
        os.makedirs(os.path.dirname(exe), exist_ok=True)
        with open(exe, "w") as f:
            f.write("#!/bin/sh\n")
        return exe


class BuilderTests(unittest.TestCase, FakeRuntimeMixin):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        home = self.tmp.name
        self.env = mock.patch.dict(os.environ, {
            "HOME": home, "XDG_DATA_HOME": os.path.join(home, "data"),
            "XDG_CACHE_HOME": os.path.join(home, "cache"), "APPDATA": os.path.join(home, "appdata"),
            "LOCALAPPDATA": os.path.join(home, "localappdata"), "USERPROFILE": home,
        })
        self.env.start()
        self.addCleanup(self.env.stop)

    def _create(self, mode):
        xpi_src = os.path.join(self.tmp.name, "src.xpi")
        make_xpi(xpi_src, "ext@test")

        def fake_download(url, dest, log=None, label=""):
            import shutil
            shutil.copyfile(xpi_src, dest)
            return dest

        spec = core.AppSpec(name="My App", url="example.com",
                            extensions=["https://example.com/a.xpi"], mode=mode,
                            dest=os.path.join(self.tmp.name, "portable"), desktop_shortcut=False)
        with mock.patch.object(core, "download", side_effect=fake_download), \
             mock.patch.object(core, "fetch_icon", return_value=core.letter_icon("M", 256)), \
             mock.patch.object(PLATFORM, "ensure_runtime", side_effect=self.fake_runtime):
            return core.Builder(PLATFORM).create(spec)

    def test_local(self):
        if "local" not in PLATFORM.supported_modes:
            self.skipTest("no local mode")
        rec = self._create("local")
        self.assertTrue(os.path.exists(os.path.join(rec.app_dir, "webapp.json")))
        self.assertTrue(os.path.exists(os.path.join(rec.profile_dir, "extensions", "ext@test.xpi")))
        self.assertTrue(rec.launcher)
        recs = core.Builder(PLATFORM).list_local()
        self.assertEqual([r.id for r in recs], [rec.id])
        core.Builder(PLATFORM).remove(rec)
        self.assertFalse(os.path.exists(rec.app_dir))

    def test_portable(self):
        if "portable" not in PLATFORM.supported_modes:
            self.skipTest("no portable mode")
        rec = self._create("portable")
        self.assertTrue(rec.app_dir.startswith(os.path.join(self.tmp.name, "portable")))
        self.assertTrue(os.path.exists(os.path.join(rec.app_dir, "profile", "user.js")))
        self.assertTrue(rec.launcher)


if __name__ == "__main__":
    unittest.main()
