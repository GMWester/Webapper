"""macOS backend: Mozilla's universal Firefox .dmg (Apple Silicon + Intel),
wrapped in a real .app bundle per web app with its own name, icon and
bundle identifier."""

from __future__ import annotations

import os
import plistlib
import shlex
import shutil
import subprocess
import tempfile

from . import __version__
from .core import AppRecord, AppSpec, Log, WebApperError, bouncer_url, cached_download


def _try(cmd: list[str]) -> None:
    """Run a best-effort helper command, ignoring failures."""
    try:
        subprocess.run(cmd, capture_output=True)
    except OSError:
        pass


class MacOSPlatform:
    key = "macos"
    display_name = "macOS"
    supported_modes = ("local", "portable")
    supports_desktop_shortcut = False
    supports_steam = False

    def data_root(self) -> str:
        return os.path.expanduser("~/Library/Application Support/WebApper")

    def cache_root(self) -> str:
        return os.path.expanduser("~/Library/Caches/WebApper")

    def default_portable_parent(self) -> str:
        return os.path.expanduser("~/WebApps")

    def applications_dir(self) -> str:
        return os.path.expanduser("~/Applications/WebApper")

    # A portable app is one self-contained .app bundle.
    def portable_entry(self, name: str) -> str:
        return f"{name}.app"

    def portable_layout(self, parent: str, name: str) -> tuple[str, str, str]:
        res = os.path.join(parent, f"{name}.app", "Contents", "Resources")
        app_dir = os.path.join(res, "webapp")
        return app_dir, os.path.join(res, "runtime"), os.path.join(app_dir, "profile")

    # ------------------------------------------------------------ runtime
    def firefox_binary(self, runtime_dir: str) -> str:
        return os.path.join(runtime_dir, "Firefox.app", "Contents", "MacOS", "firefox")

    def ensure_runtime(self, runtime_dir: str, channel: str, log: Log, fresh_copy: bool = False) -> str:
        exe = self.firefox_binary(runtime_dir)
        if os.path.exists(exe) and not fresh_copy:
            log("Firefox runtime already installed.")
            return exe
        dmg = cached_download(bouncer_url(channel, "osx"), self.cache_root(), f"firefox-{channel}.dmg", log)
        log("Copying Firefox out of the disk image…")
        mnt = tempfile.mkdtemp(prefix="webapper-dmg-")
        res = subprocess.run(
            ["hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mnt, dmg],
            capture_output=True, text=True,
        )
        if res.returncode != 0:
            os.remove(dmg)
            raise WebApperError(f"Could not open the Firefox disk image: {res.stderr.strip()}")
        try:
            src = next((os.path.join(mnt, n) for n in os.listdir(mnt) if n.endswith(".app")), None)
            if not src:
                raise WebApperError("Firefox.app not found in the disk image.")
            dest = os.path.join(runtime_dir, "Firefox.app")
            shutil.rmtree(dest, ignore_errors=True)
            os.makedirs(runtime_dir, exist_ok=True)
            # ditto keeps code signatures, symlinks and extended attributes intact.
            subprocess.run(["ditto", src, dest], check=True)
        finally:
            subprocess.run(["hdiutil", "detach", mnt, "-quiet", "-force"], capture_output=True)
            shutil.rmtree(mnt, ignore_errors=True)
        _try(["xattr", "-dr", "com.apple.quarantine", runtime_dir])
        return exe

    # ------------------------------------------------------------ app
    def extra_prefs(self, spec: AppSpec) -> dict:
        return {"browser.shell.checkDefaultBrowser": False}

    def bundle_path(self, record: AppRecord) -> str:
        if record.mode == "portable":
            return os.path.dirname(os.path.dirname(os.path.dirname(record.app_dir)))
        return os.path.join(self.applications_dir(), f"{record.name}.app")

    def write_bundle(self, record: AppRecord, bundle: str, icon) -> str:
        contents = os.path.join(bundle, "Contents")
        macos_dir = os.path.join(contents, "MacOS")
        res = os.path.join(contents, "Resources")
        os.makedirs(macos_dir, exist_ok=True)
        os.makedirs(res, exist_ok=True)
        icon.resize((1024, 1024)).save(os.path.join(res, "AppIcon.icns"))
        icon.save(os.path.join(record.app_dir, "icon.png"))
        info = {
            "CFBundleName": record.name,
            "CFBundleDisplayName": record.name,
            "CFBundleIdentifier": f"io.webapper.app.{record.id}",
            "CFBundleExecutable": "launcher",
            "CFBundleIconFile": "AppIcon",
            "CFBundlePackageType": "APPL",
            "CFBundleShortVersionString": "1.0",
            "CFBundleVersion": "1",
            "CFBundleInfoDictionaryVersion": "6.0",
            "LSMinimumSystemVersion": "10.15",
            "NSHighResolutionCapable": True,
            "LSApplicationCategoryType": "public.app-category.productivity",
            "WebApperURL": record.url,
            "WebApperVersion": __version__,
        }
        with open(os.path.join(contents, "Info.plist"), "wb") as f:
            plistlib.dump(info, f)
        with open(os.path.join(contents, "PkgInfo"), "w") as f:
            f.write("APPL????")
        flags = "-kiosk" if record.kiosk else ""
        if record.mode == "portable":
            paths = (
                'CONTENTS="$(cd "$(dirname "$0")/.." && pwd)"\n'
                'FIREFOX="$CONTENTS/Resources/runtime/Firefox.app/Contents/MacOS/firefox"\n'
                'PROFILE="$CONTENTS/Resources/webapp/profile"\n'
            )
        else:
            paths = (
                f"FIREFOX={shlex.quote(self.firefox_binary(record.runtime_dir))}\n"
                f"PROFILE={shlex.quote(record.profile_dir)}\n"
            )
        launcher = os.path.join(macos_dir, "launcher")
        with open(launcher, "w", encoding="utf-8") as f:
            f.write(
                "#!/bin/sh\n"
                f"# {record.name} - generated by WebApper ({record.url})\n"
                + paths
                + 'if [ ! -x "$FIREFOX" ]; then\n'
                '  osascript -e "display alert \\"Firefox runtime missing\\" message \\"$FIREFOX\\""\n'
                "  exit 1\n"
                "fi\n"
                f'exec "$FIREFOX" -profile "$PROFILE" {flags} "$@"\n'
            )
        os.chmod(launcher, 0o755)
        # Ad-hoc sign the wrapper so Apple Silicon/Gatekeeper treat it as a normal local app.
        _try(["codesign", "--force", "--sign", "-", bundle])
        # Refresh Launchpad/Spotlight/Finder icon caches.
        lsregister = (
            "/System/Library/Frameworks/CoreServices.framework/Frameworks/"
            "LaunchServices.framework/Support/lsregister"
        )
        if os.path.exists(lsregister):
            _try([lsregister, "-f", bundle])
        return launcher

    def finalize(self, record: AppRecord, spec: AppSpec, icon, log: Log) -> None:
        bundle = self.bundle_path(record)
        if record.mode == "local":
            if os.path.exists(bundle):
                bundle = os.path.join(self.applications_dir(), f"{record.name} ({record.id[-8:]}).app")
            os.makedirs(self.applications_dir(), exist_ok=True)
            record.shortcuts.append(bundle)
        self.write_bundle(record, bundle, icon)
        record.launcher = bundle
        record.extra["bundle"] = bundle

    def remove(self, record: AppRecord, log: Log) -> None:
        for s in record.shortcuts:
            if os.path.isdir(s):
                shutil.rmtree(s, ignore_errors=True)
            elif os.path.exists(s):
                os.remove(s)

    def launch(self, record: AppRecord) -> None:
        subprocess.Popen(["open", "-n", record.extra.get("bundle", record.launcher)])


PLATFORM = MacOSPlatform()
