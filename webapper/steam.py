"""Steam integration: add generated apps to the Steam library as
"non-Steam games" (shortcuts.vdf) with generated library artwork, so they
show up in Game Mode on Steam Deck / Steam Frame / any SteamOS device."""

from __future__ import annotations

import binascii
import glob
import os
import struct
import subprocess

# Binary VDF type tags
_MAP, _STR, _INT, _FLOAT, _PTR, _WSTR, _COLOR, _U64, _END, _I64 = 0, 1, 2, 3, 4, 5, 6, 7, 8, 10


class VdfError(Exception):
    pass


def _read_cstr(buf: bytes, pos: int) -> tuple[str, int]:
    end = buf.index(b"\x00", pos)
    return buf[pos:end].decode("utf-8", "replace"), end + 1


def loads(buf: bytes) -> dict:
    def parse_map(pos: int) -> tuple[dict, int]:
        out: dict = {}
        while True:
            if pos >= len(buf):
                raise VdfError("unexpected end of data")
            t = buf[pos]
            pos += 1
            if t == _END:
                return out, pos
            key, pos = _read_cstr(buf, pos)
            if t == _MAP:
                out[key], pos = parse_map(pos)
            elif t == _STR:
                out[key], pos = _read_cstr(buf, pos)
            elif t in (_INT, _PTR, _COLOR):
                out[key] = struct.unpack_from("<i", buf, pos)[0]
                pos += 4
            elif t == _FLOAT:
                out[key] = struct.unpack_from("<f", buf, pos)[0]
                pos += 4
            elif t == _U64:
                out[key] = ("u64", struct.unpack_from("<Q", buf, pos)[0])
                pos += 8
            elif t == _I64:
                out[key] = ("i64", struct.unpack_from("<q", buf, pos)[0])
                pos += 8
            else:
                raise VdfError(f"unsupported type 0x{t:02x}")

    if not buf:
        return {}
    data, _ = parse_map(0)
    return data


def dumps(data: dict) -> bytes:
    out = bytearray()

    def key(k: str) -> bytes:
        return k.encode("utf-8") + b"\x00"

    def write_map(d: dict) -> None:
        for k, v in d.items():
            if isinstance(v, dict):
                out.append(_MAP)
                out.extend(key(k))
                write_map(v)
            elif isinstance(v, bool) or isinstance(v, int):
                out.append(_INT)
                out.extend(key(k))
                out.extend(struct.pack("<i", int(v)))
            elif isinstance(v, float):
                out.append(_FLOAT)
                out.extend(key(k))
                out.extend(struct.pack("<f", v))
            elif isinstance(v, tuple) and v[0] == "u64":
                out.append(_U64)
                out.extend(key(k))
                out.extend(struct.pack("<Q", v[1]))
            elif isinstance(v, tuple) and v[0] == "i64":
                out.append(_I64)
                out.extend(key(k))
                out.extend(struct.pack("<q", v[1]))
            else:
                out.append(_STR)
                out.extend(key(k))
                out.extend(str(v).encode("utf-8") + b"\x00")
        out.append(_END)

    write_map(data)
    return bytes(out)


def shortcut_appid(exe: str, name: str) -> int:
    """Unsigned 32-bit id Steam uses for a non-Steam shortcut (and its artwork)."""
    return (binascii.crc32((exe + name).encode("utf-8")) & 0xFFFFFFFF) | 0x80000000


def _signed(u: int) -> int:
    return u - (1 << 32) if u >= (1 << 31) else u


def steam_roots() -> list[str]:
    cands = [
        "~/.steam/steam",
        "~/.local/share/Steam",
        "~/.var/app/com.valvesoftware.Steam/data/Steam",
    ]
    seen, out = set(), []
    for c in cands:
        p = os.path.realpath(os.path.expanduser(c))
        if os.path.isdir(os.path.join(p, "userdata")) and p not in seen:
            seen.add(p)
            out.append(p)
    return out


def user_config_dirs() -> list[str]:
    dirs = []
    for root in steam_roots():
        for d in glob.glob(os.path.join(root, "userdata", "*")):
            if os.path.basename(d).isdigit() and os.path.basename(d) != "0":
                dirs.append(os.path.join(d, "config"))
    return dirs


def steam_running() -> bool:
    try:
        return subprocess.run(["pgrep", "-x", "steam"], capture_output=True).returncode == 0
    except OSError:
        return False


def make_artwork(icon, name: str, grid_dir: str, appid: int) -> list[str]:
    from PIL import Image, ImageDraw, ImageFont, ImageStat

    os.makedirs(grid_dir, exist_ok=True)
    icon = icon.convert("RGBA")
    mean = ImageStat.Stat(icon.convert("RGB"), mask=icon.split()[3]).mean if icon.getbbox() else [60, 60, 60]
    bg = tuple(int(c * 0.45) for c in mean[:3])
    accent = tuple(min(255, int(c * 0.8) + 30) for c in mean[:3])

    def font(px: int):
        for f in ("DejaVuSans-Bold.ttf", "NotoSans-Bold.ttf", "LiberationSans-Bold.ttf"):
            try:
                return ImageFont.truetype(f, px)
            except OSError:
                continue
        return ImageFont.load_default(size=px)

    def canvas(w: int, h: int, icon_px: int, text: bool, text_px: int = 0):
        img = Image.new("RGBA", (w, h), bg + (255,))
        d = ImageDraw.Draw(img)
        for y in range(h):  # vertical gradient
            t = y / max(1, h - 1)
            c = tuple(int(bg[i] * (1 - t) + accent[i] * t * 0.6) for i in range(3))
            d.line([(0, y), (w, y)], fill=c + (255,))
        ic = icon.resize((icon_px, icon_px), Image.LANCZOS)
        y = (h - icon_px) // 2 - (text_px if text else 0)
        img.alpha_composite(ic, ((w - icon_px) // 2, max(0, y)))
        if text:
            f = font(text_px)
            label = name if len(name) <= 24 else name[:23] + "…"
            d.text((w / 2, y + icon_px + text_px * 1.2), label, font=f, fill="white", anchor="mt")
        return img

    files = {
        f"{appid}p.png": canvas(600, 900, 300, True, 48),
        f"{appid}.png": canvas(920, 430, 200, True, 40),
        f"{appid}_hero.png": canvas(1920, 620, 320, False),
    }
    logo = Image.new("RGBA", (800, 300), (0, 0, 0, 0))
    ImageDraw.Draw(logo).text((400, 150), name[:28], font=font(72), fill="white", anchor="mm",
                              stroke_width=3, stroke_fill="black")
    files[f"{appid}_logo.png"] = logo
    files[f"{appid}_icon.png"] = icon.resize((256, 256), Image.LANCZOS)
    paths = []
    for fn, img in files.items():
        p = os.path.join(grid_dir, fn)
        img.save(p)
        paths.append(p)
    return paths


def add_shortcut(name: str, exe: str, start_dir: str, icon_path: str, icon_img, tags=("Web Apps",)) -> list[str]:
    """Add (or replace) a shortcut in every Steam user's shortcuts.vdf.

    Returns the list of files written (shortcuts.vdf + artwork)."""
    q_exe = f'"{exe}"'
    appid = shortcut_appid(q_exe, name)
    written = []
    for cfg in user_config_dirs():
        os.makedirs(cfg, exist_ok=True)
        path = os.path.join(cfg, "shortcuts.vdf")
        data = {"shortcuts": {}}
        if os.path.exists(path):
            with open(path, "rb") as f:
                raw = f.read()
            try:
                data = loads(raw) or {"shortcuts": {}}
            except (VdfError, ValueError, struct.error):
                with open(path + ".webapper-backup", "wb") as f:
                    f.write(raw)
                data = {"shortcuts": {}}
        shortcuts = data.setdefault("shortcuts", {})
        entries = [v for v in shortcuts.values() if isinstance(v, dict) and v.get("Exe") != q_exe]
        entries.append({
            "appid": _signed(appid),
            "AppName": name,
            "Exe": q_exe,
            "StartDir": f'"{start_dir}"',
            "icon": icon_path,
            "ShortcutPath": "",
            "LaunchOptions": "",
            "IsHidden": 0,
            "AllowDesktopConfig": 1,
            "AllowOverlay": 1,
            "OpenVR": 0,
            "Devkit": 0,
            "DevkitGameID": "",
            "DevkitOverrideAppID": 0,
            "LastPlayTime": 0,
            "FlatpakAppID": "",
            "tags": {str(i): t for i, t in enumerate(tags)},
        })
        data["shortcuts"] = {str(i): e for i, e in enumerate(entries)}
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(dumps(data))
        os.replace(tmp, path)
        written.append(path)
        written.extend(make_artwork(icon_img, name, os.path.join(cfg, "grid"), appid))
    return written


def remove_shortcut(exe: str) -> None:
    q_exe = f'"{exe}"'
    for cfg in user_config_dirs():
        path = os.path.join(cfg, "shortcuts.vdf")
        if not os.path.exists(path):
            continue
        try:
            with open(path, "rb") as f:
                data = loads(f.read())
        except Exception:
            continue
        sc = data.get("shortcuts", {})
        entries = [v for v in sc.values() if isinstance(v, dict) and v.get("Exe") != q_exe]
        if len(entries) != len(sc):
            data["shortcuts"] = {str(i): e for i, e in enumerate(entries)}
            with open(path, "wb") as f:
                f.write(dumps(data))
