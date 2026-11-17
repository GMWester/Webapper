# WebApper for SteamOS (Steam Frame)

Part of [WebApper](../..), which turns any website into an app built on the Firefox engine. Every platform has its own branch.

Creates Firefox-powered single-site apps on SteamOS. Targets the **Steam Frame**, which runs arm64
SteamOS, and also works on Steam Deck and other x86_64 SteamOS devices. Run it in Desktop Mode.

* Uses Mozilla's **native Linux arm64 Firefox** on Steam Frame and x86_64 elsewhere, chosen automatically.
* Installs everything under `$HOME`. SteamOS' root filesystem is read-only, and nothing in
  WebApper touches it.
* **Add to Steam library** is on by default. WebApper writes the app into every Steam user's
  `shortcuts.vdf` as a non-Steam game and generates library artwork: portrait capsule, wide
  capsule, hero, logo and icon. Restart Steam, or switch to Game Mode, to see it.
* In **Game Mode** (gamescope) the launcher starts the app full screen (`--kiosk`). It also
  restores the system `LD_LIBRARY_PATH` that Steam overrides for shortcuts, and keeps the desktop
  controller layout, so the controller works as a mouse and keyboard.
* Local and Portable work as on Linux. A portable folder on an SD card or USB drive works on any SteamOS device.

## Using it

1. Download the installer for your platform from this branch's GitHub Actions artifacts or Releases.
2. Start it and fill in:
   * **Website address**, e.g. `music.example.com`. `https://` is added for you.
   * **App name**. Leave it empty to use the page title.
   * **Firefox add-ons**, one per line: an addons.mozilla.org page
     (`https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/`), a bare slug (`ublock-origin`)
     or a direct `.xpi` link.
   * **Install type**: Local or Portable.
   * Options: navigation buttons, kiosk/full screen, desktop shortcut, Firefox ESR, Add to Steam.
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
