# WebApper

WebApper turns any website into an app, the way CEF/Electron wrappers do, except the engine is
**Firefox (Gecko)** instead of Chrome. It also lets you bundle **Firefox add-ons** into the app.

The WebApper app is an installer. You:

1. type the **web address**,
2. paste links to **Firefox add-ons**, e.g. `https://addons.mozilla.org/firefox/addon/ublock-origin/`
   or a direct `.xpi` URL, one per line,
3. choose **Local** or **Portable** install (where the OS supports it),
4. press **Create**.

WebApper then builds the app for the OS it's running on.

Each platform lives on its own branch:

| Platform | Branch | Engine in generated apps | Local install | Portable install |
|---|---|---|---|---|
| Linux (x86_64, arm64) | [`linux`](../../tree/linux) | Firefox (official Mozilla build) | menu entry + optional desktop icon, shared runtime in `~/.local/share/webapper` | self-contained folder (Firefox + profile + `run.sh` + `.desktop`) |
| Windows (x64, arm64) | [`windows`](../../tree/windows) | Firefox (official Mozilla build, no admin) | Start menu + desktop shortcut + "Apps & features" uninstall entry | self-contained folder with `<Name>.exe` launcher |
| macOS (Apple Silicon, Intel) | [`macos`](../../tree/macos) | Firefox (universal build) | `~/Applications/WebApper/<Name>.app` | one self-contained `<Name>.app` |
| SteamOS / Steam Frame (arm64, x86_64) | [`steamos`](../../tree/steamos) | Firefox (native arm64 on Steam Frame) | as Linux + added to the Steam library with artwork, full screen in Game Mode | as Linux |
| Android phones/tablets | [`android`](../../tree/android) | GeckoView (Firefox for Android's engine) | new APK built and installed on the device | APK file saved for other devices |
| Android TV / Google TV | [`android-tv`](../../tree/android-tv) | GeckoView + remote-control pointer | TV app with banner | APK file |
| Android XR | [`android-xr`](../../tree/android-xr) | GeckoView | XR app (Home Space panel or Full Space) | APK file |
| iOS / iPadOS | [`ios`](../../tree/ios) | WebKit (Apple does not allow Gecko) | Home Screen icon | `.webapper` file for other iPhones/iPads |

Each branch is self-contained. It has its own README, source and a GitHub Actions workflow that
builds the installer. Pushing a tag such as `linux-v1.0.0` or `android-v1.0.0` attaches the
build to a GitHub Release.

## How the generated apps work

* **Desktop (Linux, Windows, macOS, SteamOS):** WebApper downloads the official Firefox build
  from `download.mozilla.org`. It then creates an isolated profile for each app with these parts:
  * `user.js` sets the start page, turns off telemetry and onboarding, and opens new windows in place.
  * `userChrome.css` hides tabs, the URL bar and toolbars, so only the site is shown.
    You can opt in to a minimal back/forward/reload bar.
  * The add-ons go into `<profile>/extensions/<id>.xpi`. They are side-loaded and enabled
    automatically, with Mozilla's signatures intact.

  A **local** app shares one Firefox runtime and gets a menu or Start entry. A **portable** app
  carries its own Firefox copy and profile in one folder (or one `.app` on macOS) that you can move.
* **Android, Android TV, Android XR:** the installer contains a GeckoView "app shell" APK.
  On the device it builds a real new app from that shell:
  * it rewrites the package name and label in the binary `AndroidManifest.xml`,
  * swaps in the site icon (and the TV banner on Android TV),
  * embeds the configuration and the add-on XPIs,
  * signs the APK (APK Signature Scheme v2/v3) and installs it.

  Every generated app has its own Gecko profile and appears in the launcher like any other app.
  Portable mode saves the APK so you can install it elsewhere.
* **iOS:** Apple only lets third-party apps use WebKit, so a Gecko engine can't ship on iOS.
  The iOS branch uses one isolated WKWebView data store per app. From each add-on it injects the
  content scripts and CSS, with a small `browser.*` shim. It adds Home Screen icons through a
  Web Clip configuration profile. Portable mode exports a `.webapper` file.

## License

GPL-3.0. See [LICENSE](LICENSE).
