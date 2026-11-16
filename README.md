# Aura Browser 2.2

A native Android browser written in Kotlin and Jetpack Compose. Package `com.gr4nt.aurabrowser`, Android 8.0+ (API 26), signed with the same development key as earlier releases so it updates 1.x in place.

## Engine

The engine is **Android System WebView (Chromium)**, not WebKit. Keep Android System WebView updated. Safari-style and iOS-style looks are visual only.

## Layouts

- **Phone (< 600dp):** floating glass toolbar below the page (the WebView sits in its own area, never underneath controls), collapsing on scroll. The tab switcher is a horizontal snap-scrolling card deck modelled on iOS Recents: cards scale and fade away from the centre, swipe a card up to close it, tap to open.
- **Tablet (600dp+) and desktop / resizable windows (1000dp+):** Safari-for-Mac style toolbar (sidebar toggle, back/forward, centred address field with suggestions, share, new tab, tab overview), a tab strip, an optional bookmarks/history sidebar and a grid tab overview.
- Liquid-glass surfaces are built from a blurred sample of the page edge, frost, specular highlight and a bright rim. This is an interpretation, not Apple's renderer.

## Blocking

Request-level blocking from compiled EasyList, EasyPrivacy, AdGuard base and StevenBlack hosts data (`tools/compile_filters.py` rebuilds `app/src/main/assets/filters`), generic and per-site element hiding, a cookie-banner list, a pop-up gate (new windows need approval) and a guard against script-driven redirects to ad hosts. Per-site "allow ads" switch. No blocker removes every ad; first-party and server-inserted video ads can get through.

## Downloads

Segmented downloader (`dl/Engine.kt`): up to 32 parallel ranged HTTP/1.1 connections per file (default 16, adjustable), a persisted bitmap of finished chunks so pause/resume survives the app being killed, automatic back-off on 429/503, restart when the file changes on the server, single-stream fallback when a server ignores `Range`. Speed is limited by your network and the server; more connections cannot guarantee a particular speed. Finished files are saved to `Downloads/Aura Browser`.

Videos: media requests (mp4, webm, mp3, HLS .m3u8 and more) and `<video>` sources are detected per page; More > Save Video / Audio downloads them (HLS is fetched in parallel and joined into one file; DRM/encrypted and blob/MSE streams can't be saved).

## Uploads, camera and links

- Website file inputs open the system picker (documents, photo picker, camera capture), so uploads work on sites such as Google Play Console. Sites may still restrict sign-in inside embedded WebViews.
- Camera, microphone and location requests are confirmed per site, then use Android runtime permissions.
- Links can be opened in other apps (ask / automatically / never), and any link or page can be sent to another app from the menus.

## Build

CI (`.github/workflows/android-build.yml`) runs the downloader unit tests, then builds and signs the APK and AAB with the keystore stored in Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Locally: JDK 21, Android SDK 36, Gradle 8.14, then `gradle :app:assembleRelease`.

## Verification status

CI has built and signed this revision and the downloader tests pass. It has **not** been run on a phone, tablet or desktop window in this environment, so layout, gestures, scrolling smoothness and site compatibility still need checking on a device.
