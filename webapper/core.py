"""Platform-independent core of WebApper.

Everything here is shared by every desktop build (Linux, SteamOS, Windows,
macOS).  It knows how to:

* normalise the website address the user typed,
* resolve Firefox add-on links (addons.mozilla.org pages or direct .xpi links)
  into signed XPI files plus their add-on IDs,
* fetch a good icon for the site,
* build an isolated Firefox profile that turns Firefox into a single-site
  "embedded" app shell (no tabs, no URL bar, add-ons side-loaded and
  auto-enabled, telemetry/onboarding off).

The platform modules (``platform_*.py``) take care of fetching the Firefox
runtime and writing launchers/shortcuts for their OS.
"""

from __future__ import annotations

import hashlib
import html
import io
import json
import os
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import asdict, dataclass, field
from typing import Callable, Iterable

from . import __version__

USER_AGENT = f"WebApper/{__version__} (+https://github.com/GMWester/Webapper)"
AMO_API = "https://addons.mozilla.org/api/v5/addons/addon/{slug}/"
AMO_RE = re.compile(
    r"^https?://addons\.mozilla\.org/(?:[a-zA-Z-]+/)?(?:firefox|android)/addon/([^/?#]+)",
    re.IGNORECASE,
)

Log = Callable[[str], None]


def _noop(_msg: str) -> None:
    pass


class WebApperError(Exception):
    """Error that is shown to the user as-is."""


# ---------------------------------------------------------------- networking


def http_get(url: str, timeout: float = 60, accept: str | None = None) -> bytes:
    headers = {"User-Agent": USER_AGENT}
    if accept:
        headers["Accept"] = accept
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        raise WebApperError(f"{url}: HTTP {e.code}") from e
    except urllib.error.URLError as e:
        raise WebApperError(f"{url}: {e.reason}") from e


def http_get_final(url: str, timeout: float = 30) -> tuple[str, bytes]:
    """GET returning (final_url_after_redirects, body)."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.geturl(), resp.read()


def download(url: str, dest: str, log: Log = _noop, label: str = "") -> str:
    """Stream ``url`` to ``dest`` with coarse progress messages."""
    os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    tmp = dest + ".part"
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            last = 0.0
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                now = time.monotonic()
                if now - last > 0.5:
                    last = now
                    if total:
                        log(f"{label or 'Downloading'}: {done * 100 // total}% of {total // (1 << 20)} MB")
                    else:
                        log(f"{label or 'Downloading'}: {done // (1 << 20)} MB")
    except urllib.error.URLError as e:
        raise WebApperError(f"Download failed ({url}): {getattr(e, 'reason', e)}") from e
    os.replace(tmp, dest)
    return dest


# ---------------------------------------------------------------- input


def normalize_url(text: str) -> str:
    text = (text or "").strip()
    if not text:
        raise WebApperError("Please enter a website address.")
    if not re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", text):
        text = "https://" + text
    parts = urllib.parse.urlsplit(text)
    if parts.scheme not in ("http", "https", "file"):
        raise WebApperError(f"Unsupported address scheme: {parts.scheme}")
    if parts.scheme != "file" and not parts.netloc:
        raise WebApperError(f"'{text}' is not a valid web address.")
    return urllib.parse.urlunsplit(parts)


def split_extension_input(text: str | Iterable[str]) -> list[str]:
    if isinstance(text, str):
        items = re.split(r"[\s,]+", text)
    else:
        items = []
        for t in text:
            items.extend(re.split(r"[\s,]+", t))
    seen, out = set(), []
    for item in items:
        item = item.strip()
        if item and item not in seen:
            seen.add(item)
            out.append(item)
    return out


def safe_name(name: str) -> str:
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", name).strip().strip(".")
    return name[:80] or "WebApp"


def slugify(name: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return s[:40] or "webapp"


def make_app_id(name: str, url: str) -> str:
    h = hashlib.sha1(f"{name}\n{url}\n{time.time_ns()}".encode()).hexdigest()[:8]
    return f"{slugify(name)}-{h}"


def page_title(url: str) -> str | None:
    try:
        body = http_get(url, timeout=20)[:512 * 1024].decode("utf-8", "replace")
    except Exception:
        return None
    m = re.search(r"<title[^>]*>(.*?)</title>", body, re.IGNORECASE | re.DOTALL)
    if not m:
        return None
    t = html.unescape(re.sub(r"\s+", " ", m.group(1))).strip()
    return t or None


# ---------------------------------------------------------------- extensions


@dataclass
class Extension:
    source: str
    id: str = ""
    name: str = ""
    xpi_url: str = ""
    xpi_path: str = ""


def amo_slug(url: str) -> str | None:
    m = AMO_RE.match(url.strip())
    return urllib.parse.unquote(m.group(1)) if m else None


def _localized(value) -> str:
    if isinstance(value, dict):
        return value.get("en-US") or next(iter(value.values()), "")
    return value or ""


def resolve_extension(source: str) -> Extension:
    """Turn a user-supplied add-on link into an Extension with a download URL."""
    ext = Extension(source=source)
    slug = amo_slug(source)
    if (
        slug is None
        and "://" not in source
        and not source.lower().endswith(".xpi")
        and re.fullmatch(r"[A-Za-z0-9._@{}-]+", source)
    ):
        # A bare slug or add-on GUID (e.g. "ublock-origin" or "uBlock0@raymondhill.net")
        slug = source
    if slug is not None:
        api = AMO_API.format(slug=urllib.parse.quote(slug, safe="@{}"))
        try:
            data = json.loads(http_get(api, accept="application/json"))
        except WebApperError as e:
            raise WebApperError(f"Could not find add-on '{slug}' on addons.mozilla.org ({e})") from e
        ext.id = data.get("guid", "")
        ext.name = _localized(data.get("name")) or slug
        file_info = (data.get("current_version") or {}).get("file") or {}
        ext.xpi_url = file_info.get("url") or (
            f"https://addons.mozilla.org/firefox/downloads/latest/{slug}/latest.xpi"
        )
        return ext
    if not re.match(r"^https?://", source):
        raise WebApperError(f"'{source}' is not an addons.mozilla.org link or .xpi URL.")
    ext.xpi_url = source
    ext.name = os.path.basename(urllib.parse.urlsplit(source).path) or source
    return ext


_CN_OID = bytes.fromhex("0603550403")  # OID 2.5.4.3 (commonName)


def _cn_values(der: bytes) -> list[str]:
    out = []
    i = der.find(_CN_OID)
    while i != -1:
        j = i + len(_CN_OID)
        if j + 2 <= len(der) and der[j] in (0x0C, 0x13, 0x14, 0x16):  # UTF8/Printable/T61/IA5
            ln = der[j + 1]
            start = j + 2
            if ln & 0x80:
                n = ln & 0x7F
                ln = int.from_bytes(der[start:start + n], "big")
                start += n
            out.append(der[start:start + ln].decode("utf-8", "replace"))
        i = der.find(_CN_OID, j)
    return out


def read_xpi_id(path: str) -> tuple[str, str]:
    """Return (id, name) of a signed XPI."""
    with zipfile.ZipFile(path) as z:
        manifest = {}
        try:
            manifest = json.loads(z.read("manifest.json").decode("utf-8-sig"))
        except (KeyError, ValueError):
            pass
        gecko = (manifest.get("browser_specific_settings") or manifest.get("applications") or {}).get("gecko") or {}
        name = manifest.get("name", "")
        if gecko.get("id"):
            return gecko["id"], name
        # AMO-signed add-ons without an explicit id: the id is the CN of the
        # end-entity signing certificate.
        for sig in ("META-INF/cose.sig", "META-INF/mozilla.rsa"):
            try:
                der = z.read(sig)
            except KeyError:
                continue
            for cn in _cn_values(der):
                if "mozilla" in cn.lower() and "signing" in cn.lower():
                    continue
                if "@" in cn or cn.startswith("{"):
                    return cn, name
    raise WebApperError(f"Could not determine the add-on ID of {os.path.basename(path)}")


def fetch_extensions(sources: list[str], workdir: str, log: Log = _noop) -> list[Extension]:
    exts = []
    for n, src in enumerate(sources, 1):
        log(f"Resolving add-on {n}/{len(sources)}: {src}")
        ext = resolve_extension(src)
        dest = os.path.join(workdir, f"ext{n}.xpi")
        download(ext.xpi_url, dest, log, f"Add-on {ext.name or n}")
        xid, xname = read_xpi_id(dest)
        ext.id = ext.id or xid
        ext.name = ext.name or xname
        ext.xpi_path = dest
        exts.append(ext)
    return exts


# ---------------------------------------------------------------- icons


def _icon_candidates(page_url: str, body: str) -> list[tuple[int, str]]:
    cands: list[tuple[int, str]] = []
    for m in re.finditer(r"<link\b[^>]*>", body, re.IGNORECASE):
        tag = m.group(0)
        rel = re.search(r'rel\s*=\s*["\']?([^"\'>]+)', tag, re.IGNORECASE)
        href = re.search(r'href\s*=\s*["\']?([^"\'\s>]+)', tag, re.IGNORECASE)
        if not rel or not href:
            continue
        rel_v = rel.group(1).lower()
        href_v = urllib.parse.urljoin(page_url, html.unescape(href.group(1)))
        if href_v.lower().split("?")[0].endswith(".svg"):
            continue
        if "manifest" in rel_v:
            try:
                man = json.loads(http_get(href_v, timeout=15))
                for ic in man.get("icons", []):
                    src = ic.get("src")
                    if not src or src.lower().split("?")[0].endswith(".svg"):
                        continue
                    sizes = [int(x.split("x")[0]) for x in str(ic.get("sizes", "0x0")).split() if "x" in x and x.split("x")[0].isdigit()]
                    cands.append((max(sizes or [0]) + 1, urllib.parse.urljoin(href_v, src)))
            except Exception:
                pass
            continue
        if "icon" not in rel_v:
            continue
        size = 0
        sz = re.search(r'sizes\s*=\s*["\']?(\d+)x', tag, re.IGNORECASE)
        if sz:
            size = int(sz.group(1))
        elif "apple-touch-icon" in rel_v:
            size = 180
        cands.append((size, href_v))
    parts = urllib.parse.urlsplit(page_url)
    cands.append((-1, f"{parts.scheme}://{parts.netloc}/apple-touch-icon.png"))
    cands.append((-2, f"{parts.scheme}://{parts.netloc}/favicon.ico"))
    cands.sort(key=lambda c: -c[0])
    return cands


def letter_icon(name: str, size: int = 256):
    from PIL import Image, ImageDraw, ImageFont

    hue = int(hashlib.md5(name.encode()).hexdigest()[:2], 16)
    import colorsys

    r, g, b = (int(c * 255) for c in colorsys.hsv_to_rgb(hue / 255, 0.55, 0.85))
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=size // 5, fill=(r, g, b, 255))
    letter = (name.strip()[:1] or "W").upper()
    font = None
    for f in ("DejaVuSans-Bold.ttf", "Arial Bold.ttf", "arialbd.ttf", "Helvetica.ttc"):
        try:
            font = ImageFont.truetype(f, int(size * 0.6))
            break
        except OSError:
            continue
    if font is None:
        font = ImageFont.load_default(size=int(size * 0.6)) if hasattr(ImageFont, "load_default") else None
    d.text((size / 2, size / 2), letter, fill="white", font=font, anchor="mm")
    return img


def fetch_icon(page_url: str, name: str, log: Log = _noop, size: int = 256):
    """Best-effort site icon as a square RGBA PIL image of ``size``."""
    from PIL import Image

    try:
        final, raw = http_get_final(page_url)
        body = raw[:1024 * 1024].decode("utf-8", "replace")
    except Exception:
        final, body = page_url, ""
    best = None
    for _declared, url in _icon_candidates(final, body):
        try:
            data = http_get(url, timeout=15)
            img = Image.open(io.BytesIO(data))
            if img.format == "ICO":  # pick the largest frame of an .ico
                img = img.ico.getimage(max(img.ico.sizes()))
            img = img.convert("RGBA")
        except Exception:
            continue
        if best is None or img.width > best.width:
            best = img
        if best.width >= 128:
            break
    if best is None or best.width < 32:
        log("No usable site icon found; generating one.")
        return letter_icon(name, size)
    log(f"Using site icon ({best.width}x{best.height}).")
    return square_icon(best, size)


def square_icon(img, size: int = 256):
    from PIL import Image

    img = img.convert("RGBA")
    w, h = img.size
    s = max(w, h)
    canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    canvas.paste(img, ((s - w) // 2, (s - h) // 2))
    return canvas.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- app spec


@dataclass
class AppSpec:
    name: str
    url: str
    extensions: list[str] = field(default_factory=list)
    mode: str = "local"  # "local" or "portable"
    dest: str = ""  # parent folder for portable installs
    channel: str = "release"  # "release" or "esr"
    show_toolbar: bool = False
    kiosk: bool = False
    desktop_shortcut: bool = True
    add_to_steam: bool = False  # SteamOS only
    id: str = ""


@dataclass
class AppRecord:
    """What is written next to every generated app as webapp.json."""

    id: str
    name: str
    url: str
    mode: str
    channel: str
    app_dir: str
    profile_dir: str
    runtime_dir: str
    launcher: str
    extensions: list[dict] = field(default_factory=list)
    show_toolbar: bool = False
    kiosk: bool = False
    shortcuts: list[str] = field(default_factory=list)
    created: float = field(default_factory=time.time)
    webapper_version: str = __version__
    extra: dict = field(default_factory=dict)

    def save(self) -> None:
        with open(os.path.join(self.app_dir, "webapp.json"), "w", encoding="utf-8") as f:
            json.dump(asdict(self), f, indent=2)

    @classmethod
    def load(cls, app_dir: str) -> "AppRecord":
        with open(os.path.join(app_dir, "webapp.json"), encoding="utf-8") as f:
            data = json.load(f)
        known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(**known)


# ---------------------------------------------------------------- profile


def _js_value(v) -> str:
    return json.dumps(v)


BASE_PREFS: dict[str, object] = {
    "toolkit.legacyUserProfileCustomizations.stylesheets": True,
    "browser.startup.page": 1,
    "browser.newtabpage.enabled": False,
    "browser.shell.checkDefaultBrowser": False,
    "browser.tabs.inTitlebar": 0,
    "browser.tabs.warnOnClose": False,
    "browser.tabs.warnOnCloseOtherTabs": False,
    "browser.tabs.closeWindowWithLastTab": True,
    "browser.tabs.firefox-view": False,
    "browser.warnOnQuit": False,
    "browser.warnOnQuitShortcut": False,
    # Tabs are hidden, so pages that open new windows/tabs open in place.
    "browser.link.open_newwindow": 1,
    "browser.link.open_newwindow.restriction": 0,
    "browser.link.open_newwindow.override.external": 1,
    "browser.aboutwelcome.enabled": False,
    "startup.homepage_welcome_url": "",
    "startup.homepage_welcome_url.additional": "",
    "startup.homepage_override_url": "",
    "browser.startup.homepage_override.mstone": "ignore",
    "browser.startup.firstrunSkipsHomepage": False,
    "browser.rights.3.shown": True,
    "browser.uitour.enabled": False,
    "browser.discovery.enabled": False,
    "browser.messaging-system.whatsNewPanel.enabled": False,
    "browser.newtabpage.activity-stream.feeds.section.topstories": False,
    "browser.preferences.moreFromMozilla": False,
    "browser.toolbars.bookmarks.visibility": "never",
    "browser.translations.automaticallyPopup": False,
    "browser.sessionstore.resume_from_crash": False,
    "browser.sessionstore.max_resumed_crashes": 0,
    "browser.privatebrowsing.vpnpromourl": "",
    "browser.vpn_promo.enabled": False,
    "sidebar.revamp": False,
    "sidebar.verticalTabs": False,
    "datareporting.policy.dataSubmissionEnabled": False,
    "datareporting.healthreport.uploadEnabled": False,
    "toolkit.telemetry.reportingpolicy.firstRun": False,
    "toolkit.telemetry.enabled": False,
    "app.normandy.enabled": False,
    "app.shield.optoutstudies.enabled": False,
    "extensions.pocket.enabled": False,
    "browser.ml.chat.enabled": False,
    # Side-loaded add-ons in <profile>/extensions are enabled without a prompt.
    "extensions.autoDisableScopes": 0,
    "extensions.enabledScopes": 15,
    "extensions.startupScanScopes": 15,
    "extensions.htmlaboutaddons.recommendations.enabled": False,
    "extensions.getAddons.showPane": False,
}

USER_CHROME_HIDE_ALL = """\
/* Generated by WebApper: turn Firefox into a single-site app window. */
#TabsToolbar, #nav-bar, #PersonalToolbar, #toolbar-menubar:not([inactive="false"]),
#sidebar-box, #sidebar-main, #sidebar-splitter, #sidebar-launcher-splitter,
#tabbrowser-tabs, #firefox-view-button, #alltabs-button {
  visibility: collapse !important;
}
#navigator-toolbox { border-bottom: none !important; }
"""

USER_CHROME_TOOLBAR = """\
/* Generated by WebApper: single-site window with a minimal navigation bar. */
#TabsToolbar, #PersonalToolbar, #sidebar-box, #sidebar-main, #sidebar-splitter,
#sidebar-launcher-splitter, #firefox-view-button, #alltabs-button,
#urlbar-container, #search-container, #fxa-toolbar-menu-button,
#save-to-pocket-button, #home-button, #customizableui-special-spring1,
#customizableui-special-spring2 {
  visibility: collapse !important;
}
#navigator-toolbox { border-bottom: none !important; }
"""


def build_profile(
    profile_dir: str,
    url: str,
    extensions: list[Extension],
    show_toolbar: bool = False,
    extra_prefs: dict | None = None,
) -> None:
    os.makedirs(os.path.join(profile_dir, "chrome"), exist_ok=True)
    os.makedirs(os.path.join(profile_dir, "extensions"), exist_ok=True)
    prefs = dict(BASE_PREFS)
    prefs["browser.startup.homepage"] = url
    if extra_prefs:
        prefs.update(extra_prefs)
    with open(os.path.join(profile_dir, "user.js"), "w", encoding="utf-8") as f:
        f.write("// Generated by WebApper. Applied on every start.\n")
        for k, v in prefs.items():
            f.write(f"user_pref({_js_value(k)}, {_js_value(v)});\n")
    with open(os.path.join(profile_dir, "chrome", "userChrome.css"), "w", encoding="utf-8") as f:
        f.write(USER_CHROME_TOOLBAR if show_toolbar else USER_CHROME_HIDE_ALL)
    for ext in extensions:
        shutil.copyfile(ext.xpi_path, os.path.join(profile_dir, "extensions", f"{ext.id}.xpi"))


# ---------------------------------------------------------------- orchestration


class Builder:
    """Drives the creation of an app using a platform backend."""

    def __init__(self, platform, log: Log = _noop):
        self.platform = platform
        self.log = log

    def create(self, spec: AppSpec) -> AppRecord:
        p = self.platform
        log = self.log
        spec.url = normalize_url(spec.url)
        if spec.mode not in p.supported_modes:
            raise WebApperError(f"{spec.mode} installs are not supported on {p.display_name}.")
        if not spec.name.strip():
            log("Reading the page title…")
            spec.name = page_title(spec.url) or urllib.parse.urlsplit(spec.url).netloc
        spec.name = safe_name(spec.name)
        spec.id = spec.id or make_app_id(spec.name, spec.url)
        sources = split_extension_input(spec.extensions)

        with tempfile.TemporaryDirectory(prefix="webapper-") as work:
            exts = fetch_extensions(sources, work, log)
            log("Fetching the site icon…")
            icon = fetch_icon(spec.url, spec.name, log)

            if spec.mode == "portable":
                parent = spec.dest or p.default_portable_parent()
                os.makedirs(parent, exist_ok=True)
                folder = spec.name
                if os.path.exists(os.path.join(parent, p.portable_entry(folder))):
                    folder = f"{spec.name} ({spec.id.rsplit('-', 1)[-1]})"
                app_dir, runtime_dir, profile_dir = p.portable_layout(parent, folder)
            else:
                app_dir = os.path.join(p.data_root(), "apps", spec.id)
                runtime_dir = os.path.join(p.data_root(), "runtime", spec.channel)
                profile_dir = os.path.join(app_dir, "profile")
            os.makedirs(app_dir, exist_ok=True)

            log("Preparing the Firefox runtime…")
            p.ensure_runtime(runtime_dir, spec.channel, log, fresh_copy=(spec.mode == "portable"))

            log("Building the app profile…")
            build_profile(profile_dir, spec.url, exts, spec.show_toolbar, p.extra_prefs(spec))

            record = AppRecord(
                id=spec.id,
                name=spec.name,
                url=spec.url,
                mode=spec.mode,
                channel=spec.channel,
                app_dir=app_dir,
                profile_dir=profile_dir,
                runtime_dir=runtime_dir,
                launcher="",
                extensions=[{"id": e.id, "name": e.name, "source": e.source} for e in exts],
                show_toolbar=spec.show_toolbar,
                kiosk=spec.kiosk,
            )
            log("Creating launcher and shortcuts…")
            p.finalize(record, spec, icon, log)
            record.save()
        log(f"Done! '{record.name}' was created in {record.app_dir}")
        return record

    def list_local(self) -> list[AppRecord]:
        root = os.path.join(self.platform.data_root(), "apps")
        out = []
        if os.path.isdir(root):
            for d in sorted(os.listdir(root)):
                try:
                    out.append(AppRecord.load(os.path.join(root, d)))
                except Exception:
                    continue
        return out

    def remove(self, record: AppRecord) -> None:
        self.platform.remove(record, self.log)
        shutil.rmtree(record.app_dir, ignore_errors=True)
        self.log(f"Removed '{record.name}'.")


def firefox_product(channel: str) -> str:
    return "firefox-esr-latest-ssl" if channel == "esr" else "firefox-latest-ssl"


def bouncer_url(channel: str, os_name: str, lang: str = "en-US") -> str:
    return (
        "https://download.mozilla.org/?product="
        f"{firefox_product(channel)}&os={os_name}&lang={lang}"
    )


def cached_download(url: str, cache_dir: str, filename: str, log: Log, max_age_h: float = 24) -> str:
    """Download into a cache, reusing files younger than ``max_age_h`` hours."""
    path = os.path.join(cache_dir, filename)
    if os.path.exists(path) and time.time() - os.path.getmtime(path) < max_age_h * 3600:
        log(f"Using cached {filename}")
        return path
    return download(url, path, log, "Firefox")
