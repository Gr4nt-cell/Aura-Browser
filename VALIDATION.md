# Validation — Aura Browser 1.2

Built November 16, 2026 using JDK 21, Android SDK 36 and Build Tools 36.0.0.

## Passed

- Resource and Java compilation, DEX conversion, alignment, APK packaging and v2/v3 signature verification.
- APK package metadata: Aura Browser; com.gr4nt.aurabrowser; versionCode 3; versionName 1.2; minimum Android API 26; target API 36.
- Source audit: no old com.grant.prism references in application Java, and no app-owned AlertDialog.Builder call sites. App-owned menu/confirmation/list/input dialogs share GlassDialog. Main compact sheets use the same frosted capsule surface.
- Android SDK Lint NewApi and MissingPermission checks: no issues. Standalone source/resource analysis, not a Gradle lint run.
- Signing keystore password and alias verification; Base64 byte-for-byte round-trip; APK signer fingerprint matches the exported keystore.
- Download HTTP fixture tests rerun under the renamed Java namespace:

```text
PASS parallel ranges + pause/resume + byte-for-byte integrity (peak 6 HTTP connections)
PASS changed ETag discards old partial bytes
PASS non-range server fallback
PASS HTTP redirects
PASS HLS VOD segments merged in playlist order
PASS encrypted HLS rejection
PASS HTTP failure report
PASS disk-restored partial transfer resume and completed job inventory
PASS filename sanitization
```

## Implemented UI behavior

The fixed white dock strip is replaced with an in-memory sampled page backdrop covering the reserved dock and navigation inset. Android navigation contrast scrims are disabled on supported versions. Web content remains in a sibling layout above controls; this is sampled backdrop continuity, not live content hidden beneath the toolbar.

Rounded sampled-glass dialogs cover Privacy Report, Settings, downloads, bookmarks/history, confirmations, file/camera choices, origin permission confirmations, video lists, find/text inputs and website JS alert/confirm/prompt/before-unload dialogs. Phones use bottom-positioned dialogs; larger windows use a centered surface capped at 560dp. System-owned pickers, permission requests and share choosers retain Android styling.

## Runtime verification limit

This revision has not been visually or interactively verified on a physical phone, tablet or desktop window here. The user's screenshots establish rendering of the prior revision. The prior software-only Android 15 emulator did not finish booting within eight minutes; no successful emulator run is claimed for 1.2. Device confirmation is still needed for rendering, navigation inset behavior, gestures and dialog interaction.

## Identity and signing

The package ID changed at the user's request. Aura Browser installs separately from com.grant.prism and does not automatically migrate that app's private browser data. The signing key is the same public development key used previously; it is not a private production key. Its original certificate subject is retained. The Base64 export is an encoding of that exact keystore.

The engine remains Chromium Android System WebView, not WebKit. Video and website compatibility limits remain documented in README.md.

## Repository build integration

The repository copy excludes all signing material. build.py reads an external keystore and signing passwords from environment variables. The manual GitHub Actions workflow expects KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD. Its signing path was tested locally using the supplied key; configuring GitHub secrets is a separate account action.
