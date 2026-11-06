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
