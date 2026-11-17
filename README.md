# WebApper for Linux

Part of [WebApper](../..), which turns any website into an app built on the Firefox engine. Every platform has its own branch.

Creates Firefox-powered single-site apps on any glibc Linux distribution (x86_64 and arm64).

* **Local install**: the shared runtime lives in `~/.local/share/webapper/runtime/<channel>` and the
  apps in `~/.local/share/webapper/apps/<id>`. Each app gets a menu entry in
  `~/.local/share/applications/webapper-<id>.desktop` and, optionally, a desktop icon. Every app
  has its own `StartupWMClass`, so it gets its own taskbar icon.
* **Portable install**: one folder, `~/WebApps/<Name>/` by default, holding `runtime/firefox`,
  `profile/`, `run.sh` and `<Name>.desktop`. The `.desktop` file finds `run.sh` through `%k`, so the
  folder still works after you move it to another disk or computer.
* No root access needed. Nothing is installed system-wide.

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
