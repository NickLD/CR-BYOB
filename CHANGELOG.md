# Changelog

User-facing changes to CR-BYOB. This file is the single source for release notes. The app shows it in
"What's new", the GitHub release body is cut from it, and so is the Google Play "What's new" text.

Format: one `## [X.Y.Z] - YYYY-MM-DD` section per release (newest first), with `### Added` /
`### Changed` / `### Fixed` / `### Known limitations` subsections and one short line per change, written
for people using the app. Collect changes under `## [Unreleased]` and rename it to the version and date
when tagging. A release build fails if its version has no dated section here.

## [Unreleased]

## [0.2.1] - 2026-11-07

### Changed
- Updates install from inside the app (GitHub build): Update downloads the new version with a progress bar
  and hands it to Android's installer, instead of going through the browser. Android asks once to allow
  installs from CR-BYOB. On Android 12 and later, updates after the first one may skip Android's confirm
  screen (Google Play Protect may still ask to scan a version it hasn't seen).
  The download is checked against GitHub's checksum and CR-BYOB's signing key before it installs. If a
  scan is streaming, the app asks first, since installing closes it.
- If an update doesn't install (the install permission was left off, it was canceled, or Android blocked it),
  the app says why and offers the release page to download and install it yourself, or another try.
- The version line at the bottom now reads "What's new / Check for app updates" in the GitHub build.
- Softer wording above the donation button: "Avoided buying a Scan Bridge, and want to say thanks with a donation?"

## [0.2.0] - 2026-11-06

### Added
- Scanner frame-rate override (experimental), in the new Experimental card below Network: Stock, Boost or
  Max. Behind a bridge Creality Scan asks an Otter for 15 fps; Boost gets 20 and Max 30, worked out from
  the attached scanner's own modes. Max needs a very fast and stable link (Wi-Fi 6E/7 or Gigabit
  Ethernet). Creality Scan reads it when it connects, so the choice is locked while it's connected.
- Run a scan from the mirror viewer: set up a new scan (object, alignment, accuracy and more, as Creality
  Scan offers for the scanner, with its own descriptions), change the settings during the preview
  (Settings…), then Start, Pause, Finish, and Delete once paused. Delete replaces Cancel.
- Fill light and exposure in the viewer: 💡 and AUTO/MAN buttons on the 3D view, with an IR exposure
  slider. "Lighting…" also sets laser power and color exposure.
- Creality Scan's messages show along the bottom of the 3D view, and its pop-ups show on the phone; your
  answer goes back to the PC.
- Mirror quality: tap the "Mirror … fps" pill to set the mirror's frame rate and 3D resolution.
  "Adjust automatically" lowers them when the mirror stutters and raises them again once it's smooth.
- The viewer's stats panel shows the bridge's link (Wi-Fi signal, link rate and band, or the Wi-Fi Direct
  channel). The dashboard's Wi-Fi line adds the Wi-Fi generation and the link rates.
- The CR-Scan Ferret Pro is now tested and supported, thanks to a device report.

### Fixed
- Short stalls on the phone's network no longer drop frames or halve the IR rate: the bridge keeps about
  a quarter second of frames per stream (was two frames).
- Bug fixes and improvements.

## [0.1.0] - 2026-11-05

First public release. CR-BYOB turns an Android phone into a Wi-Fi bridge for Creality's CR-Scan Otter,
so Creality Scan on a PC can use the scanner without a USB cable to the PC.

### Added
- Bridge for the **CR-Scan Otter** plugged into the phone over USB. Creality Scan discovers it the same
  way it discovers a Scan Bridge.
- Streams left and right IR, color and IMU data to Creality Scan.
- Choice of network: Wi-Fi Direct (the phone makes its own network), your home Wi-Fi, or USB Ethernet.
- "This phone only" mode: scan with the CrealityScan app on the same phone, no PC needed. Nothing on the
  network can find or reach the bridge in this mode.
- Dashboard with scanner and power status, per-stream frame rates, estimated link quality, session
  statistics and an event log.
- Mirror viewer: Creality Scan's color, IR and 3D views on the phone.
- App version at the bottom of the dashboard. Long-press it to copy the details for a bug report.
- "What's new" after each update. GitHub builds also check GitHub Releases for new versions.
- Ko-fi button at the bottom of the dashboard (GitHub builds).

### Known limitations
- Only the CR-Scan Otter has been tested. Raptor, Ferret, Sermoon, Pika and M1 Ultra are recognized
  but untested. P1 and P1S are not supported.
- Full frame rate needs a strong 5 GHz connection. On 2.4 GHz or a weak signal it drops to about 6 fps.
- Restart Creality Scan after restarting the bridge.
- Like the device it replaces, the bridge's network services have no authentication. Use it on networks
  you trust, or use "This phone only".
