# WebApper for Android TV

Part of [WebApper](../..), which turns any website into an app built on the Firefox engine. Every platform has its own branch.

Creates real **Android TV / Google TV** apps from websites, on the TV itself, using
**GeckoView**, Mozilla's embeddable Firefox engine, plus Firefox add-ons. Generated apps appear on
the TV home screen with a generated banner.

## Using it

1. Install `WebApper-androidtv-arm64.apk` (most devices), `armv7` (older 32-bit devices) or
   `x86_64` (emulators, Chromebooks) from this branch's Actions artifacts or Releases.
2. Fill in the **website address**, an optional **app name**, and **Firefox add-ons**, one per
   line: addons.mozilla.org links, slugs or `.xpi` URLs. You can also share a link to WebApper
   from any app.
3. Choose the **install type**:
   * **Install on this device (local)**: the new app is installed with Android's package
     installer and shows up in the launcher. The first time, Android asks you to allow
     "Install unknown apps" for WebApper.
   * **Portable**: the APK is saved where you choose, so you can sideload it on other devices.
     It keeps every CPU architecture the installer carries.
4. Press **Create**.

## How it works

The installer carries a prebuilt **GeckoView** app shell (`template/`), the Firefox engine that
Firefox for Android uses. On the device, `Generator` and `apk/*` do the following:

1. resolve the add-ons through the addons.mozilla.org API and download the signed XPIs,
2. fetch the site's best icon (apple-touch-icon, web manifest or favicon), and generate a 16:9 TV banner from it,
3. rebuild the binary `AndroidManifest.xml` string pool (`AxmlPatcher`): the placeholder package
   `com.webapper.gen.t0000000000000000` becomes a unique package everywhere it appears, and the
   label becomes the app name,
4. copy the APK without recompressing the large native libraries, swap the icon, add
   `assets/webapp.json` and the XPIs, and align uncompressed entries (4 bytes, 16 KiB for `.so`) (`ZipRewriter`),
5. sign it with APK Signature Scheme v2 + v3 (Google's `apksig`). The installer's RSA key is
   created on first run, so re-creating an app installs as an update.

Each generated app:

* opens the site full-window in GeckoView, with its own private Gecko profile,
* installs its add-ons on first start, with a 🧩 button for add-on popups,
* gives native dialogs for alert/confirm/prompt, `<select>`, file uploads and date/colour inputs,
* asks before granting camera, microphone, location or notification access,
* saves downloads to the Downloads folder,
* supports full-screen video, back navigation, and hands mailto:/tel:/intent: links to other apps,
* has an optional desktop mode and keep-screen-on setting.

**TV-specific:**

* **Remote-control pointer**: the D-pad moves an on-screen mouse pointer that accelerates as you
  hold it, OK clicks, and pushing against an edge scrolls the page, so any website works with a
  remote.
* The **MENU** key (or **Y** on a gamepad) opens a menu to switch between pointer and focus
  navigation, reload, go to the home page, or open add-on popups.
* "Request desktop site" is on by default, because sites give desktop browsers their big-screen
  layout.
* Apps and the installer declare `LEANBACK_LAUNCHER` with a banner and don't require a touchscreen.

## Building

Requirements: JDK 17, Android SDK (platform 37) and Gradle 9.3.1. The project has no wrapper jar.

```bash
gradle :app:testX86_64DebugUnitTest
```

```bash
gradle :app:assembleRelease
```

The unit test builds and signs a real app from the GeckoView template on the JVM, then verifies
the signature and the rewritten manifest. CI then installs that app on an emulator and launches
it. Release installers are signed with the debug key so they install straight away. For
distribution, set your own `signingConfig` in `app/build.gradle.kts`. GeckoView comes from
`maven.mozilla.org`, and `geckoviewVersion` in `gradle.properties` tracks the latest Firefox 160.x.

## License

GPL-3.0
