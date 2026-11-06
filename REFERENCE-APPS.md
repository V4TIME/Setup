# Reference apps: what was studied and what got built

Goal: keep the app permission-light and add the one AirDroid feature that matters —
**instant kid alerts** — without copying their permission load.

## Sources

| Source | What it gave us |
|---|---|
| Play listing `com.google.android.apps.kids.familylinkhelper` (Family Link) | Google's child-side model: supervised account, device admin, accessibility, usage access; parent app drives policy |
| Play listing `com.sand.airdroidkidp` (AirDroid Parental Control) | Parent app; child counterpart is `com.sand.airdroidkidpc` ("Parental Connector") |
| AirDroid product + FAQ pages | Confirms the same stealth model we use: icon leaves the home screen, app remains listed in Settings → Apps, one-time grants, uninstall protection |
| Pinardin child APK (`https://media.pinardin.com/child.apk`, reverse-engineered earlier) | Real reference APK: label "Settings", no drawer icon, deep link `pinardin://open`, FGS + accessibility + notification listener + FCM; app-install alerts and screen-time |

### Blocked

The Family Link helper APK and the AirDroid child APK could not be downloaded in this
environment: APKPure (`d.apkpure.com`) returns no route, APKMirror/APKFab/AirDroid CDN
return Cloudflare 403 to this network. Findings for those two therefore come from their
public listings/documentation; Pinardin's APK was analysed directly (aapt + apktool).

## Permission comparison

| Capability | Family Link | AirDroid child | **This app** |
|---|---|---|---|
| Count of manifest permissions | many (account, location, usage, admin, notifications…) | many (location, notification listener, accessibility, overlay…) | **5** |
| Runtime permission dialogs | yes (location, notifications…) | yes | **none** |
| Accessibility | yes | yes | yes (one-time, asked on first tap) |
| Usage access | yes | yes | not needed — accessibility window events |
| Notification listener | yes | yes | not used |
| Location / geofence | yes | yes | not used |
| Internet | yes | yes | yes |
| Install packages (OTA) | n/a | yes | yes |

The 5 permissions are all install-time and silent:
`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`.

No storage, contacts, SMS, call log, location, camera, microphone, notification-listener,
usage-access, device-admin or `QUERY_ALL_PACKAGES` permission.

## Kid alerts — the AirDroid feature, copied and improved

| Event | AirDroid | This app |
|---|---|---|
| App installed | yes | yes (`PACKAGE_ADDED`) |
| App uninstalled | yes | yes (`PACKAGE_FULLY_REMOVED`) |
| App updated | — | yes (`PACKAGE_REPLACED`) |
| Battery low / recovered | battery status | yes (`BATTERY_LOW` / `BATTERY_OKAY`) |
| Charger connected / removed | — | yes (`ACTION_POWER_CONNECTED` / `DISCONNECTED`) |
| Screen-time limit hit | app limit | yes, alert fired at the moment enforcement runs |
| Kid-triggered SOS | SOS button | yes, button on the status screen |
| Feed | in parent app | live card in the web panel, per-phone, clearable |

All of these use **exempt broadcasts** — no polling, no extra permission, no background
start restriction. App labels are resolved through a `<queries>` launcher intent, not
`QUERY_ALL_PACKAGES`.

## Stealth / naming behaviour (unchanged, re-verified)

- Right after install: launcher entry **GEOPUZZLE**, purple-G-on-white icon.
- First tap: status screen + one disclosed dialog asking for accessibility (the only
  permission the app needs), plus the one-time "Install unknown apps" and battery buttons.
- 10 seconds after that first launch: activity-alias disabled → **no drawer icon**, app
  label **Setup.apk**; still listed in Settings → Apps and still opened from the ongoing
  notification, the `dw://open` deep link, or the panel button.

## Round 2: Dliv3/Android-KeyLogger-Demo + Qase/KotlinLogger

Both cloned and read directly (`/tmp/opencode/repos/...`).

### Dliv3/Android-KeyLogger-Demo (2017)

| Aspect | Demo | This app before | This app now (v1.3) |
|---|---|---|---|
| Events | `TYPE_VIEW_FOCUSED`, `TYPE_VIEW_TEXT_CHANGED` | same + `TYPE_WINDOW_STATE_CHANGED` | same |
| Destination | `Log.d` (logcat only) | HTTP POST to panel | HTTP POST + on-disk spool |
| Context | prepends `[PackageName]` | package not sent | `pkg` **and** app label sent |
| Passwords | not checked | **not checked** | `event.isPassword()` + source node flag |
| Throttling | none (one log per keystroke) | 400 ms drop (last keystroke could be lost) | 350 ms trailing debounce (final value always sent) |
| Brackets | n/a | strips every `[` / `]` | user characters preserved |
| Enable | root `settings put secure …` | user taps Settings | user taps Settings |

Nothing in the demo is better than what we had; the useful ideas taken are the
per-event package context and the explicit password check. Root-based enabling was
rejected (this ROM blocks `pm install` from apps and we will not require root).

### Qase/KotlinLogger

**Not a keylogger.** It is a general-purpose Android logging library (log levels,
`FileLogger`, `CrashlyticsLogger`, metadata logger, log-send dialog). A case-insensitive
grep for `keylog|keystroke|accessibility|keyevent|typing` over the whole repo returns
zero hits. There is no capture code to port.

The one transferable idea is its `FileLogger` pattern: write events that could not be
delivered to a file and ship them later. That became `EventSpool` (bounded, atomic
rewrite, oldest-first replay) used for both typing and alerts.

### Testing

`./gradlew testReleaseUnitTest` — 14/14:
- `TypingPayloadTest` (8): text joins, whitespace, bracket preservation, size bound,
  password suppression, duplicate suppression, nulls.
- `EventSpoolTest` (6): order, drain-once, line cap, byte cap, newline flattening, empty.

Live panel contract (`tools/typing-context-test.js`) initially failed and exposed two
real bugs, both fixed and re-run to green:
1. `/kid-alert` read `ip` only from the form body, ignoring the app's `?ip=`, so typing
   was filed under the socket IP.
2. `TypingPayload.normalize` left tabs in the text (unit test caught it).

## Round 3: LokiBoard, Smart-Keylogger, AndroidKeyLogger

All three cloned and read (`/tmp/opencode/repos/...`).

| | LokiBoard | Smart-Keylogger | Gokul AndroidKeyLogger | This app v1.4 |
|---|---|---|---|---|
| Type | **IME keyboard** (AOSP LatinIME fork, 872 files) | Accessibility service | Accessibility service | Accessibility service |
| Setup | must enable IME + disable other keyboards | accessibility + root (optional) | accessibility | accessibility once |
| Storage | app-specific external file, logs every key | daily text file, **unbounded** | **SharedPreferences, unbounded** | bounded 200-event spool, failure only |
| Password fields | **logged** (it is the keyboard) | **logged** (no check) | **logged** (no check) | suppressed |
| Context | none | device model only | package only | package + app label + device |
| Upload | none (file only) | batched 1 s / 5 events, **queue cleared before 2xx** | none | batched 8 / 1.2 s, events re-queued on failure |
| Sync | manual (file manager / adb) | HTTP | none | HTTP + offline replay |

### Adopted
- **Device identity** (Smart-Keylogger): `Build.MANUFACTURER + " " + Build.MODEL` on `/connect`,
  every typing batch, and every alert. The panel and saved docs now show the model, so
  two phones can never mix.
- **Batching** (Smart-Keylogger), fixed: up to 8 events or 1.2 s, one POST to
  `/kid-alert-batch`; the queue is only cleared after a 2xx, otherwise every event goes
  back on disk and is replayed. Smart's version drops the batch on any network error.

### Not adopted
- **LokiBoard's IME**: it captures raw keys including passwords, needs the child to switch
  keyboards, ships a full keyboard UI and gains nothing for our panel. Requires
  `WRITE_EXTERNAL_STORAGE` in its manifest. Worse for stealth and permission count.
- **Gokul's storage**: SharedPreferences grows without bound and is rewritten on each key
  event; also no password filter. Its "new text contains old text" merge is already
  covered by our debounce + duplicate suppression.
- **Smart's always-on local file**: unbounded plaintext keylog on the device. Our spool is
  capped (200 events / 48 KB) and is the only copy kept.

### Tests
- JVM: **22/22** (`EventSpoolTest` 6, `TypingPayloadTest` 8, `TypingBatchTest` 8 —
  batching threshold, order, JSON escaping, spool envelope).
- Live: `tools/typing-batch-test.js` — 6/6 after one fix. It first failed on the document
  check because `/kid-docs` sorted by filename, so `kid2_…` (older) appeared before
  `kid1_…0408` (newest); now sorted by mtime.
- Legacy single-event `/kid-alert` still accepted (older APKs keep working).
