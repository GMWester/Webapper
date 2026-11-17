# WebApper for Android XR

Part of [WebApper](../..), which turns any website into an app built on the Firefox engine. Every platform has its own branch.

Creates real **Android XR** apps (Galaxy XR and other Android XR headsets) from websites, on the
headset itself, using **GeckoView**, Mozilla's embeddable Firefox engine, plus Firefox add-ons.

## Using it

1. Install `WebApper-androidxr-arm64.apk` (most devices), `armv7` (older 32-bit devices) or
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
2. fetch the site's best icon (apple-touch-icon, web manifest or favicon),
3. rebuild the binary `AndroidManifest.xml` string pool (`AxmlPatcher`): the placeholder package
   `com.webapper.gen.t0000000000000000` becomes a unique package everywhere it appears, and the
   label becomes the app name,
4. copy the APK without recompressing the large native libraries, swap the icon, add
   `assets/webapp.json` and the XPIs, and align uncompressed entries (4 bytes, 16 KiB for `.so`) (`ZipRewriter`),
5. sign it with APK Signature Scheme v2 + v3 (Google's `apksig`). The installer's RSA key is
   created on first run, so re-creating an app installs as an update.

Each generated app:

* opens the site full-window in GeckoView, with its own private Gecko profile,
* installs its add-ons on first start. An add-ons manager (the 🧩 button; the MENU key on TV) lists every add-on and lets you open its popup, open its **Settings** page, or turn it on or off,
* gives native dialogs for alert/confirm/prompt, `<select>`, file uploads and date/colour inputs,
* asks before granting camera, microphone, location or notification access,
* saves downloads to the Downloads folder,
* supports full-screen video, back navigation, and hands mailto:/tel:/intent: links to other apps,
* has an optional desktop mode and keep-screen-on setting.

**XR-specific:**

* Generated apps open as a resizable **Home Space** panel (`PROPERTY_XR_ACTIVITY_START_MODE`).
  Tick **Open in Full Space** in the installer and WebApper rewrites that property to
  `XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED` in the generated app.
* WebXR permission requests are shown as an allow/block prompt.
* On other Android devices the same APKs run as ordinary apps.

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
