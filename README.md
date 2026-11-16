# Aura Browser for Android

A native Android browser with a Safari-style mobile toolbar, Pearl Mist glass styling, a recent-app-style overlapping tab deck, and responsive tablet / Android desktop layouts.

## Install

Install `AuraBrowser-1.2.apk` on Android 8.0 or newer. Allow your download app to install APKs if Android asks. This is a development-signed build. It installs separately from the former Prism application because its package ID is now `com.gr4nt.aurabrowser`.

## New in 1.2

The app is now named Aura Browser and uses application ID `com.gr4nt.aurabrowser` with provider authority `com.gr4nt.aurabrowser.files`. This package installs separately from the former Prism package; it cannot automatically inherit that app's private tabs, cookies or download metadata.

The mobile dock no longer sits on a fixed white strip. The reserved dock area and navigation inset are painted using the same sampled webpage backdrop as the glass capsules; navigation-bar contrast scrims are disabled where supported. The WebView still occupies its own area above the controls. This is a sampled visual continuation, not hidden content or a WebView drawn underneath the buttons.

Every app-owned alert/list/settings/confirmation dialog now uses `GlassDialog`, a rounded, sampled-glass surface shared across phone, tablet and desktop windows. Phone dialogs appear near the bottom; larger windows use a centered surface capped at 560dp. Privacy Report, Settings, downloads, bookmarks, history, permissions, text inputs, video lists and website JavaScript dialogs use it. The compact More and Page Menu sheets also use the frosted surface. Android system permission dialogs, file pickers and share choosers remain controlled by Android.

A signing export bundle is supplied separately, including the actual signing keystore and Base64. This is the same development key used for earlier test builds. Keep it in Actions secrets; it is not included in Git.

## Earlier compact UI (1.1)

The phone interface now uses a compact floating toolbar: separate back and More capsules, a pill-shaped address field with a page-menu glyph and reload/stop, and an in-capsule progress line. More opens a rounded action sheet with Share, Bookmarks and Tabs shortcuts. The page menu includes inline text-sizing controls.

The toolbar shrinks from 70dp to 52dp on downward scrolling and expands on upward scrolling. Both sizes reserve their own layout space outside web content. Swiping the unfocused address field sideways changes tabs; swiping up or long-pressing More opens the existing Recents-style tab deck. These interactions are implemented but have not been verified on a device in this environment.

The glass capsules use a throttled, in-memory sample of the bottom of the current web viewport. Android 12+ blurs that sampled image with RenderEffect; Android 8–11 use a coarser downsampled image. The glyphs and text are drawn above the frosted coating and stay sharp. This is sampled backdrop blur, not Apple's rendering engine or optical refraction.

Start-page favourites have larger typography, white glass icon plates and vector-drawn site glyphs. Tablet/desktop layouts retain the existing top tab strip and sidebar.

## What is implemented

- Mobile: compact Safari-inspired bottom controls as described above. The address field shows the domain at rest and the full URL while editing. Forward, Share, Bookmarks, Tabs and downloads are available in More.
- Phone tab overview: overlapping screenshot cards, inertial horizontal scrolling, snapping, tap-to-select and swipe-up-to-close. This is a native interpretation of iOS Recents; it is not a pixel-verified reproduction of Apple's software.
- Tablet (600dp+): top navigation, center address field, horizontal tabs and optional sidebar. Android desktop / large window (1000dp+): persistent sidebar and wider desktop controls. This is an Android APK running in resizable windows; it is not a separate Windows/macOS application.
- Pearl Mist pink/lilac/blue background glows, translucent rounded surfaces, bright rims and subtle shadows, plus system/light/dark appearance. Capsules combine sampled backdrop blur with translucent gradients and highlights; no arbitrary live view hierarchy is blurred.
- Offline host-based ad/tracker blocking using the bundled StevenBlack unified hosts file, additional URL rules, generic cosmetic filtering, blocking exceptions per site and a session counter. Strict mode blocks all new windows by default. Disable strict mode to approve user-tapped pop-ups. Filtering cannot remove every ad, including some first-party video ads.
- Persistent download engine with up to six byte-range workers per file, two active jobs, streamed disk writes, retries, progress, pause/resume and metadata restoration after process death. Incomplete jobs restore paused, rather than silently consuming data. Servers without range support restart the transfer; missing/changed validators also restart it to avoid mixing file versions. Speed depends on bandwidth, server limits and storage; six connections cannot guarantee a particular speed.
- Video discovery from video/audio elements, performance entries, media links and intercepted media URLs. Direct HTTP(S) media and completed unencrypted MPEG-TS HLS VOD are supported. HLS chooses the highest-bandwidth muxed variant and saves a `.ts` file. DRM/encrypted media, blob-only sources, live HLS, HLS byte-range/fMP4 playlists and separate audio/video muxing are unsupported.
- Foreground download service, pause-all notification, bounded wake lock, files exported to `Downloads/Aura Browser` on Android 10+. Android 8–9 keep files within Aura Browser and expose Open/Share. Downloads interrupted by Android's foreground-service time limit pause safely.
- Android system file/photo picker with multiple-file selection and MIME filtering; image capture through a scoped content URI. No broad photo/storage permission is needed for user-selected files. Secure-origin camera/microphone requests have a website confirmation plus Android runtime permissions.
- Links can be shared or opened in other apps through a confirmation and chooser. Long-press links for new tab, external app, download or copy.
- Bookmarks, persistent tab URLs, history, clear browsing data, find-in-page, text sizing, third-party cookie setting, fullscreen media, suspended background tabs and desktop keyboard shortcuts.

## Engine and website compatibility

**The engine is Android System WebView (Chromium), not WebKit.** The `android.webkit` Java package name does not mean the renderer is WebKit. A maintained Android WebKit engine is not included in this project. Keep Android System WebView updated on the device.

Some websites restrict embedded browser authentication or unsupported web APIs. Google Play Console and other complex sites may reject WebView sign-in even though standard file input upload is implemented. No claim of compatibility with every website or all videos is made.

## Controls

- Tap the address field to enter a URL or search.
- Tap the page-menu icon inside the address capsule for page controls. Tap the separate **More** button for Share, Bookmarks, Tabs, downloads and settings.
- Use More → Add Bookmark to save the page. Long-press More to open the tab deck directly.
- Downloads are accessible from More and the desktop toolbar.
- Keyboard: Ctrl+L address, Ctrl+T new tab, Ctrl+W close, Ctrl+R reload, Ctrl+F find, Ctrl+Tab next tab.

## Build without Gradle

Requires JDK 21, Android SDK platform 36 and Build Tools 36.0.0.

```sh
export ANDROID_HOME=/path/to/android-sdk
export PATH=/path/to/jdk-21/bin:$PATH
python3 build.py
```

The APK is written to `build/AuraBrowser-1.2.apk`. `build.py` compiles resources with aapt2, Java with javac, DEX with d8, aligns with zipalign and signs with apksigner. Gradle project files are also included for Android Studio; the tested build path is `build.py`.

## GitHub Actions signing

Run **Actions → Build signed Aura Browser APK → Run workflow** after configuring these repository Actions secrets:

| Secret | Value from the supplied signing kit |
| --- | --- |
| `KEYSTORE_BASE64` | Full contents of the Base64 keystore export, without a trailing newline |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing alias |
| `KEY_PASSWORD` | Private-key password |

The workflow decodes the key into runner temporary storage, validates all four secrets, runs download tests, builds/signs the APK, uploads an Actions artifact and removes the temporary key. It is manually triggered so an initial source upload does not launch a build before secrets are configured.

Signing material is deliberately excluded from the repository. Supply `ANDROID_KEYSTORE_PATH`, `KEY_ALIAS`, `KEYSTORE_PASSWORD` and `KEY_PASSWORD` to sign locally using the supplied key. Without an explicit keystore path, the local build generates an ignored development key; that new key will not update an APK signed with a different certificate.


## Download tests

The Java test uses a local HTTP fixture, actual simultaneous range requests and byte-for-byte comparisons:

```sh
mkdir -p /tmp/prism-tests
javac --add-modules jdk.httpserver -d /tmp/prism-tests app/src/main/java/com/gr4nt/aurabrowser/DownloadCore.java tests/DownloadCoreTest.java
java --add-modules jdk.httpserver -cp /tmp/prism-tests DownloadCoreTest
```

It covers six parallel workers, partial pause/resume, ETag changes, no-range fallback, redirects, HLS segment order, encrypted HLS rejection, HTTP errors, disk-persistent restoration and filename sanitization. Runtime/visual validation results for the delivered build are in `VALIDATION.md`.

## Third-party material

`app/src/main/assets/hosts.txt`: StevenBlack unified hosts, downloaded November 16, 2026 from <https://github.com/StevenBlack/hosts>. See its embedded source attributions and `hosts-license.txt`. No third-party browsing engine binaries are packaged in the APK.
