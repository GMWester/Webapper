# WebApper for iOS / iPadOS

Part of [WebApper](../..), which turns any website into an app. Every platform has its own branch.

> **The engine on iOS is WebKit, not Firefox.** Apple requires every third-party iOS app to use
> WebKit for web content. The EU-only "alternative browser engine" entitlement is limited to
> dedicated browser apps, and Mozilla does not ship an embeddable Gecko for iOS. So no iOS app can
> embed Gecko today. This branch keeps the same workflow and gets as close as Apple allows.

## Using it

1. Tap **+**. Enter the **website address**, an optional **app name** and **Firefox add-on**
   links (addons.mozilla.org pages, slugs or `.xpi` URLs, one per line).
2. Choose the **install type**:
   * **Local**: the web app is added to WebApper. WebApper then opens Safari with a
     configuration profile that puts the app's own icon on your Home Screen. Install it in
     **Settings › Profile Downloaded**. Tapping the icon opens the app full screen
     (`webapper://open/<id>`).
   * **Portable**: exports `<Name>.webapper`, one file holding the settings, icon and add-ons.
     AirDrop or copy it to another iPhone or iPad and open it with WebApper there.
3. Tap **Create**.

Long-press an app in the list to add it to the Home Screen again, export it or delete it.

## What each web app gets

* Its own isolated `WKWebsiteDataStore`, so cookies, logins and storage are separate per app.
* Full-screen display, swipe back/forward, pull to refresh, and downloads saved to
  Files › WebApper › Downloads.
* Native alert/confirm/prompt dialogs, and camera/microphone prompts.
* An optional desktop-site mode.
* An add-ons button (puzzle icon, top right) to turn each add-on on or off for that app.
* **Firefox add-ons, partly.** WebApper downloads and unpacks each add-on, reads its
  `manifest.json` and injects its **content scripts and CSS** with the declared match patterns,
  `run_at` timing and `all_frames` setting. It provides a `browser`/`chrome` shim with
  `storage.local`/`sync`, `i18n.getMessage`, `runtime.id` and `runtime.getManifest`.
  Add-ons that only change pages work: dark modes, element hiders, page tweaks. Parts that
  depend on background pages, `webRequest` blocking or toolbar popups need Firefox and are not
  available on iOS.

## Building

```bash
brew install xcodegen
```

```bash
xcodegen generate
```

```bash
open WebApper.xcodeproj
```

Pick your team under Signing & Capabilities, then run. CI (`.github/workflows/build.yml`) builds
for the simulator and produces an **unsigned IPA**. Sign it yourself, for example with Xcode,
AltStore or Sideloadly.

## License

GPL-3.0
