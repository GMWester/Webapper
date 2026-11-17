"""SteamOS backend (Steam Frame, Steam Deck, other SteamOS devices).

Builds on the Linux backend and adds:
* native arm64 Firefox on the Steam Frame (x86_64 on Steam Deck),
* everything installed under $HOME (SteamOS' root filesystem is read-only),
* the app is added to the Steam library with generated artwork,
* Game Mode (gamescope) automatically starts the app in kiosk/full screen and
  restores the host library path that Steam overrides for shortcuts.
"""

from __future__ import annotations

import os

from . import steam
from .core import AppRecord, AppSpec, Log
from .platform_linux import LinuxPlatform


def is_steamos() -> bool:
    try:
        with open("/etc/os-release", encoding="utf-8") as f:
            return "steamos" in f.read().lower()
    except OSError:
        return False


class SteamOSPlatform(LinuxPlatform):
    key = "steamos"
    display_name = "SteamOS (Steam Frame)"
    supports_steam = True

    def launcher_env(self) -> str:
        return (
            'EXTRA_FLAGS=""\n'
            "# Launched from Steam: undo Steam's runtime library path override.\n"
            'if [ -n "${SYSTEM_LD_LIBRARY_PATH+x}" ]; then export LD_LIBRARY_PATH="$SYSTEM_LD_LIBRARY_PATH"; fi\n'
            "# Game Mode (gamescope): run full screen, controller drives the pointer.\n"
            'if [ -n "$GAMESCOPE_WAYLAND_DISPLAY" ] || [ "$XDG_CURRENT_DESKTOP" = "gamescope" ]; then\n'
            '  EXTRA_FLAGS="--kiosk"\n'
            "  export MOZ_ENABLE_WAYLAND=0\n"
            "fi\n"
        )

    def extra_prefs(self, spec: AppSpec) -> dict:
        prefs = super().extra_prefs(spec)
        # Larger UI + touch/controller friendliness on handheld/VR screens.
        prefs.update({
            "dom.w3c_touch_events.enabled": 1,
            "apz.allow_zooming": True,
        })
        return prefs

    def finalize(self, record: AppRecord, spec: AppSpec, icon, log: Log) -> None:
        super().finalize(record, spec, icon, log)
        if not spec.add_to_steam:
            return
        if not steam.user_config_dirs():
            log("Steam user data not found; skipping 'Add to Steam'.")
            return
        files = steam.add_shortcut(
            record.name,
            record.launcher,
            os.path.dirname(record.launcher),
            os.path.join(record.app_dir, "icon.png"),
            icon,
        )
        record.extra["steam_exe"] = record.launcher
        record.shortcuts.extend(f for f in files if not f.endswith("shortcuts.vdf"))
        log("Added to the Steam library.")
        if steam.steam_running():
            log("Steam is running: restart Steam (or switch to Game Mode) to see the new app.")

    def remove(self, record: AppRecord, log: Log) -> None:
        super().remove(record, log)
        exe = record.extra.get("steam_exe")
        if exe:
            steam.remove_shortcut(exe)


PLATFORM = SteamOSPlatform()
