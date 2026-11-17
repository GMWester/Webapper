# WebApper for macOS

Part of [WebApper](../..), which turns any website into an app built on the Firefox engine. Every platform has its own branch.

Creates Firefox-powered single-site apps on macOS 10.15+ (Apple Silicon and Intel).

* The runtime is Mozilla's universal `Firefox.app`, copied out of the official disk image with `ditto`.
* Every app is a real `.app` bundle with its own name, icon (`.icns`) and bundle identifier
  (`io.webapper.app.<id>`), so it shows up in Launchpad and Spotlight. The bundle is ad-hoc
  signed so Apple Silicon runs it without warnings.
* **Local install**: `~/Applications/WebApper/<Name>.app`. The shared runtime and profiles live
  in `~/Library/Application Support/WebApper`.
* **Portable install**: one self-contained `<Name>.app` that carries Firefox and its profile
  inside `Contents/Resources`. Drag it anywhere or onto another Mac.

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
