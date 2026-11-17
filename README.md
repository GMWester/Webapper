# WebApper for Windows

Part of [WebApper](../..), which turns any website into an app built on the Firefox engine. Every platform has its own branch.

Creates Firefox-powered single-site apps on Windows 10/11 (x64 and ARM64). No admin rights needed.

* The Firefox runtime is Mozilla's full installer, unpacked with `/ExtractDir`, so Firefox is
  never installed system-wide. On ARM64 PCs the native ARM64 build is used.
* **Local install**: the runtime and apps live in `%LOCALAPPDATA%\WebApper`. Each app gets a
  Start menu shortcut (`WebApper` folder), an optional desktop shortcut, its own taskbar group
  (`taskbar.grouping.useprofile`) and an entry in **Settings › Apps** so it can be uninstalled
  like any other program.
* **Portable install**: one folder with `runtime\`, `profile\` and `<Name>.exe`. The launcher
  carries the site icon. It is compiled on the fly with the C# compiler built into Windows
  (.NET Framework 4); if that compiler is missing, WebApper writes a `.cmd` launcher instead.
  Copy the folder to a USB drive and it runs anywhere.

## Using it

1. Download the installer for your platform from this branch's GitHub Actions artifacts or Releases.
2. Start it and fill in:
   * **Website address**, e.g. `music.example.com`. `https://` is added for you.
   * **App name**. Leave it empty to use the page title.
   * **Firefox add-ons**, one per line: an addons.mozilla.org page
     (`https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/`), a bare slug (`ublock-origin`)
     or a direct `.xpi` link.
   * **Install type**: Local or Portable.
   * Options: navigation buttons, kiosk/full screen, desktop shortcut, Firefox ESR.
3. Press **Create**. WebApper downloads Firefox (cached for 24 h), fetches the add-ons and the
   site icon, builds the app and offers to launch it.

The same thing from the command line:

```bash
webapper create https://music.example.com --name "Music" \
  --ext https://addons.mozilla.org/firefox/addon/ublock-origin/ --mode local
```

```bash
webapper list
```

```bash
webapper remove <id>
```

## What a generated app is

A Firefox runtime (official Mozilla build) started with a dedicated profile:

* `user.js` sets the home page, turns off onboarding, telemetry and the default-browser check,
  and makes pop-ups open in place.
* `chrome/userChrome.css` hides tabs, the URL bar and toolbars.
* `extensions/<add-on id>.xpi` holds the signed add-ons, enabled automatically.

* **Add-ons button (new in 1.1.0):** when an app has add-ons, a slim bar at the top holds
  Firefox's add-ons (puzzle) button. Click it to open an add-on, pin its icon to the bar, or use
  **Manage extension** for its settings. Turn the bar off with the "Add-ons button" option
  (`--no-addons-button`).

Each app has its own profile, so logins, cookies, storage and add-on settings are separate.

## Building from source

```bash
pip install -r requirements.txt pyinstaller
```

```bash
python -m unittest discover -s tests -v
```

```bash
python main.py
```

```bash
pyinstaller --onefile --windowed --name webapper --collect-submodules webapper main.py
```

CI (`.github/workflows/build.yml`) runs the unit tests. It then does an end-to-end run: it
downloads real Firefox, installs uBlock Origin from addons.mozilla.org and creates local and
portable apps. Finally it builds the installer for both CPU architectures.

## License

GPL-3.0
