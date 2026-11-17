import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from webapper import core, steam  # noqa: E402


class SteamTests(unittest.TestCase):
    def test_roundtrip(self):
        data = {"shortcuts": {"0": {"appid": -123, "AppName": "X", "Exe": '"/a"', "tags": {"0": "t"},
                                    "big": ("u64", 2 ** 40)}}}
        self.assertEqual(steam.loads(steam.dumps(data)), data)

    def test_appid(self):
        a = steam.shortcut_appid('"/x/launch.sh"', "App")
        self.assertTrue(a & 0x80000000)
        self.assertLess(a, 2 ** 32)

    def test_add_and_remove(self):
        with tempfile.TemporaryDirectory() as home:
            cfg = os.path.join(home, ".local/share/Steam/userdata/12345/config")
            os.makedirs(cfg)
            # pre-existing shortcut must be preserved
            with open(os.path.join(cfg, "shortcuts.vdf"), "wb") as f:
                f.write(steam.dumps({"shortcuts": {"0": {"AppName": "Other", "Exe": '"/other"'}}}))
            with mock.patch.dict(os.environ, {"HOME": home}):
                files = steam.add_shortcut("My App", "/apps/x/launch.sh", "/apps/x", "/apps/x/icon.png",
                                           core.letter_icon("M", 256))
                self.assertTrue(any(f.endswith("p.png") for f in files))
                data = steam.loads(open(os.path.join(cfg, "shortcuts.vdf"), "rb").read())
                names = sorted(v["AppName"] for v in data["shortcuts"].values())
                self.assertEqual(names, ["My App", "Other"])
                # adding again replaces instead of duplicating
                steam.add_shortcut("My App", "/apps/x/launch.sh", "/apps/x", "", core.letter_icon("M", 256))
                data = steam.loads(open(os.path.join(cfg, "shortcuts.vdf"), "rb").read())
                self.assertEqual(len(data["shortcuts"]), 2)
                steam.remove_shortcut("/apps/x/launch.sh")
                data = steam.loads(open(os.path.join(cfg, "shortcuts.vdf"), "rb").read())
                self.assertEqual([v["AppName"] for v in data["shortcuts"].values()], ["Other"])


if __name__ == "__main__":
    unittest.main()
