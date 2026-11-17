"""Linux backend: Mozilla's official Firefox tarball + .desktop launchers."""

from __future__ import annotations

import os
import platform
import shlex
import shutil
import subprocess
import tarfile

from .core import AppRecord, AppSpec, Log, WebApperError, bouncer_url, cached_download


def _xdg(var: str, default: str) -> str:
    return os.environ.get(var) or os.path.expanduser(default)


class LinuxPlatform:
    key = "linux"
    display_name = "Linux"
    supported_modes = ("local", "portable")
    supports_desktop_shortcut = True
    supports_steam = False

    # ------------------------------------------------------------ locations
    def data_root(self) -> str:
        return os.path.join(_xdg("XDG_DATA_HOME", "~/.local/share"), "webapper")

    def cache_root(self) -> str:
        return os.path.join(_xdg("XDG_CACHE_HOME", "~/.cache"), "webapper")

    def default_portable_parent(self) -> str:
        return os.path.join(os.path.expanduser("~"), "WebApps")

    def portable_entry(self, name: str) -> str:
        """Name of the top-level file/folder a portable app occupies."""
        return name

    def portable_layout(self, parent: str, name: str) -> tuple[str, str, str]:
        app_dir = os.path.join(parent, name)
        return app_dir, os.path.join(app_dir, "runtime"), os.path.join(app_dir, "profile")

    def applications_dir(self) -> str:
        return os.path.join(_xdg("XDG_DATA_HOME", "~/.local/share"), "applications")

    def desktop_dir(self) -> str:
        try:
            out = subprocess.run(["xdg-user-dir", "DESKTOP"], capture_output=True, text=True, timeout=5)
            if out.returncode == 0 and out.stdout.strip():
                return out.stdout.strip()
        except (OSError, subprocess.SubprocessError):
            pass
        return os.path.expanduser("~/Desktop")

    # ------------------------------------------------------------ runtime
    def firefox_os(self) -> str:
        m = platform.machine().lower()
        if m in ("x86_64", "amd64"):
            return "linux64"
        if m in ("aarch64", "arm64"):
            return "linux64-aarch64"
        if m in ("i386", "i686", "x86"):
            return "linux"
        raise WebApperError(f"Mozilla does not publish Firefox builds for {m}.")

    def firefox_binary(self, runtime_dir: str) -> str:
        return os.path.join(runtime_dir, "firefox", "firefox")

    def ensure_runtime(self, runtime_dir: str, channel: str, log: Log, fresh_copy: bool = False) -> str:
        exe = self.firefox_binary(runtime_dir)
        if os.path.exists(exe) and not fresh_copy:
            log("Firefox runtime already installed.")
            return exe
        os_name = self.firefox_os()
        archive = cached_download(
            bouncer_url(channel, os_name), self.cache_root(), f"firefox-{channel}-{os_name}.tar", log
        )
        log("Extracting Firefox…")
        tmp = runtime_dir + ".extract"
        shutil.rmtree(tmp, ignore_errors=True)
        os.makedirs(tmp)
        try:
            with tarfile.open(archive, "r:*") as tf:
                try:
                    tf.extractall(tmp, filter="tar")
                except TypeError:  # Python < 3.12
                    tf.extractall(tmp)
        except tarfile.TarError as e:
            os.remove(archive)
            raise WebApperError(f"The Firefox download was corrupt, please retry ({e}).") from e
        os.makedirs(runtime_dir, exist_ok=True)
        shutil.rmtree(os.path.join(runtime_dir, "firefox"), ignore_errors=True)
        shutil.move(os.path.join(tmp, "firefox"), os.path.join(runtime_dir, "firefox"))
        shutil.rmtree(tmp, ignore_errors=True)
        if not os.path.exists(exe):
            raise WebApperError("Firefox was extracted but its binary is missing.")
        return exe

    # ------------------------------------------------------------ app
    def extra_prefs(self, spec: AppSpec) -> dict:
        # Use the XDG portal file picker when available (Flatpak/KDE/SteamOS friendly).
        return {"widget.use-xdg-desktop-portal.file-picker": 1}

    def wm_class(self, record: AppRecord) -> str:
        return "WebApper-" + record.id

    def launch_flags(self, record: AppRecord) -> list[str]:
        flags = ["--class", self.wm_class(record), "--name", self.wm_class(record)]
        if record.kiosk:
            flags.append("--kiosk")
        return flags

    def launcher_script(self, record: AppRecord, portable: bool) -> str:
        flags = " ".join(shlex.quote(f) for f in self.launch_flags(record))
        if portable:
            paths = (
                'HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"\n'
                'FIREFOX="$HERE/runtime/firefox/firefox"\n'
                'PROFILE="$HERE/profile"\n'
            )
        else:
            paths = (
                f"FIREFOX={shlex.quote(self.firefox_binary(record.runtime_dir))}\n"
                f"PROFILE={shlex.quote(record.profile_dir)}\n"
            )
        return (
            "#!/bin/sh\n"
            f"# {record.name} - generated by WebApper ({record.url})\n"
            + paths
            + self.launcher_env()
            + f'exec "$FIREFOX" --profile "$PROFILE" {flags} $EXTRA_FLAGS "$@"\n'
        )

    def launcher_env(self) -> str:
        return 'EXTRA_FLAGS=""\n'

    def desktop_entry(self, record: AppRecord, exec_line: str, icon: str) -> str:
        return (
            "[Desktop Entry]\n"
            "Type=Application\n"
            "Version=1.0\n"
            f"Name={record.name}\n"
            f"Comment={record.url}\n"
            f"Exec={exec_line}\n"
            f"Icon={icon}\n"
            "Terminal=false\n"
            "Categories=Network;WebApp;\n"
            f"StartupWMClass={self.wm_class(record)}\n"
            "StartupNotify=true\n"
            f"X-WebApper-Id={record.id}\n"
        )

    def finalize(self, record: AppRecord, spec: AppSpec, icon, log: Log) -> None:
        portable = record.mode == "portable"
        icon_path = os.path.join(record.app_dir, "icon.png")
        icon.save(icon_path)
        script_name = "run.sh" if portable else "launch.sh"
        script = os.path.join(record.app_dir, script_name)
        with open(script, "w", encoding="utf-8") as f:
            f.write(self.launcher_script(record, portable))
        os.chmod(script, 0o755)
        record.launcher = script

        if portable:
            # %k = location of the .desktop file, so it keeps working when the
            # folder is moved to another drive or computer.
            entry = self.desktop_entry(
                record, r'sh -c "exec \\"\\$(dirname \\"\\$0\\")/run.sh\\"" %k', icon_path
            )
            path = os.path.join(record.app_dir, f"{record.name}.desktop")
            with open(path, "w", encoding="utf-8") as f:
                f.write(entry)
            os.chmod(path, 0o755)
            with open(os.path.join(record.app_dir, "README.txt"), "w", encoding="utf-8") as f:
                f.write(
                    f"{record.name} (portable web app made with WebApper)\n\n"
                    "Start it with ./run.sh or by double-clicking the .desktop file.\n"
                    "Everything (Firefox, add-ons, logins, settings) lives in this folder,\n"
                    "so you can copy it to a USB stick or another Linux computer.\n"
                )
        else:
            entry = self.desktop_entry(record, shlex.quote(script), icon_path)
            apps = self.applications_dir()
            os.makedirs(apps, exist_ok=True)
            path = os.path.join(apps, f"webapper-{record.id}.desktop")
            with open(path, "w", encoding="utf-8") as f:
                f.write(entry)
            os.chmod(path, 0o755)
            record.shortcuts.append(path)
            if spec.desktop_shortcut:
                ddir = self.desktop_dir()
                if os.path.isdir(ddir):
                    dpath = os.path.join(ddir, f"{record.name}.desktop")
                    shutil.copyfile(path, dpath)
                    os.chmod(dpath, 0o755)
                    if shutil.which("gio"):  # GNOME: allow launching from the desktop
                        subprocess.run(
                            ["gio", "set", dpath, "metadata::trusted", "true"],
                            capture_output=True,
                            check=False,
                        )
                    record.shortcuts.append(dpath)
            if shutil.which("update-desktop-database"):
                subprocess.run(["update-desktop-database", apps], capture_output=True, check=False)

    def remove(self, record: AppRecord, log: Log) -> None:
        for s in record.shortcuts:
            try:
                os.remove(s)
            except FileNotFoundError:
                pass

    def launch(self, record: AppRecord) -> None:
        subprocess.Popen([record.launcher], start_new_session=True)


PLATFORM = LinuxPlatform()
