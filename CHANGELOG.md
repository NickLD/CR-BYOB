# Changelog

User-facing changes to CR-BYOB. This file is the single source for release notes. The app shows it in
"What's new", the GitHub release body is cut from it, and so is the Google Play "What's new" text.

Format: one `## [X.Y.Z] - YYYY-MM-DD` section per release (newest first), with `### Added` /
`### Changed` / `### Fixed` / `### Known limitations` subsections and one short line per change, written
for people using the app. Collect changes under `## [Unreleased]` and rename it to the version and date
when tagging. A release build fails if its version has no dated section here.

## [Unreleased]

### Changed
- Self-contained wireless scanners (Sermoon P1, Sermoon P1 Lite, Creality Pika) are listed as not supported and
  no longer offered in the device report's scanner list: they connect on their own and don't work through a
  Scan Bridge. The Elegoo M1 Ultra, which Creality Scan's SDK happens to know, isn't listed any more either.

## [0.2.5] - 2026-11-09

### Added
- Device reports can include the log, and do by default. Opened in the GitHub app, the report's Log field gets
  the recent log, as much as GitHub allows (around 500 lines), including lines from before a restart or crash.
  Opened in a browser, whose links are much shorter, it gets a digest (the scanner setup and the latest events
  that matter), and the whole log is saved to Downloads as a zip for you to attach. Serial numbers, Wi-Fi names,
  passwords, MAC and IP addresses are removed. Thanks to @ravngr for pointing out that the log couldn't be
  copied.
- *Export log* under Events on the dashboard shares the same zip at any time.

### Changed
- The device report link now carries only what the app you open it in reads (the GitHub app or the web form),
  which leaves room for the log.

- A step closer to CR-Scan Raptor support. The first Raptor report showed Creality Scan stopping before the
  preview started; the bridge now handles the Raptor-specific setting behind that, and passes on whether each
  IR frame was lit by the laser lines or infrared. Not confirmed on a Raptor yet. Thanks to @ravngr for
  testing and for the log that pinned it down. If you have any Raptor-series scanner (Raptor, Raptor X,
  Raptor Pro, Raptor Pro Max), a device report helps, whether it works or not.

## [0.2.4] - 2026-11-08

### Added
- If CR-BYOB crashed, stopped responding, or was closed by Android while you were using it, the dashboard
  says so the next time you open it, once per crash, and offers to send a device report about it.
- That report covers the run that stopped: how long after starting it stopped, what it was doing (streaming,
  waiting for a PC, ...), its measurements up to then, its last events, and for a crash, where in the app it
  happened. CR-BYOB saves this while the bridge runs (every 15 s, and right after anything happens), without
  network names, addresses or the serial number.

### Changed
- Device reports say more, so problems are easier to pin down. As before, they never include the serial
  number, network names, IP addresses or the log.
- Device report, streams: frame rates against the rate Creality Scan asked for ("28.1 of 30 fps"), dropped
  frames and time at reduced rate per stream, latency, and connection errors.
- Device report, power: external power or battery and the kind of charger, the battery level at the start
  and end, and whether the scanner disconnected while streaming or while idle (a deliberate unplug and a
  power drop used to look the same).
- Device report, network: the lowest Wi-Fi link rate, and whether the phone was also on a Wi-Fi network
  during Wi-Fi Direct. With This phone only, the phone's Wi-Fi is left out.
- Device report, phone: its chip, its worst heat level, whether battery optimization can stop CR-BYOB in the
  background, and whether CR-BYOB crashed or Android stopped it in the last 24 hours.

## [0.2.3] - 2026-11-08

### Added
- Troubleshooting: "The Wi-Fi Direct network isn't in Creality Scan's Wi-Fi list?" That list only shows
  names starting with Creality's own (`Creality_Scan`, `Ferret`, `Lite Bridge`, `Pika_`, `Sermoon P1`), and
  Android makes networks created by an app start with `DIRECT-`, so the bridge's network never appears there.

### Changed
- Wi-Fi Direct: the dashboard now says to join the network from the PC's Wi-Fi menu (the taskbar on
  Windows), not from Creality Scan, and links to the new question. Once the PC has joined, the bridge shows
  up in Creality Scan by itself.

## [0.2.2] - 2026-11-07

### Added
- Help: "? Troubleshooting / Help" on the dashboard opens the user guide in the app at Troubleshooting. It's
  the same guide as on GitHub, with screenshots and photos, for the installed version and without a
  connection. Troubleshooting is a list of questions that open with a tap, and "View on GitHub" opens the
  page in the browser.
- "Send a device report" (in Session stats and at the bottom of the dashboard) shows the whole report first,
  with the scanner, firmware, phone, app version, network and this session's frame rates filled in, for you
  to add the result and what you saw. It then opens in the GitHub app or your browser, your pick, where you
  submit it. The scanner's serial number, Wi-Fi network names, IP addresses and the log are never included.
- After your first real scan (a minute or more of streaming), if there's no compatibility report yet for your
  phone or scanner, the dashboard asks once whether it went well and offers to send one.
- Warnings on the dashboard and in the mirror viewer (power, USB 2, slow or 2.4 GHz Wi-Fi, Wi-Fi Direct
  sharing the radio, Ethernet without an address, weak link) end in "How to fix", which opens the matching
  question in the guide. The untested-scanner warning asks "Tested it? Send a device report" instead.

### Changed
- The Start/Stop button on the dashboard is now a full-width button under the status, and it shows a
  spinner ("Starting…" / "Stopping…") until the bridge has finished starting or stopping.
- The dashboard shows the plugged-in scanner (model, serial, firmware, USB 3 or 2) before you tap Start,
  instead of "Scanner: bridge off". It also says when no scanner is plugged in, and when Android is still
  waiting for you to allow USB access.
- Start is disabled while the scanner is connected at USB 2 speed, which is too slow for its streams. The
  dashboard says so and links to the fixes in the guide (re-plug the hub, connect a powered hub's power last,
  check that the cable and hub are USB 3).
- The mirror viewer's status panel no longer repeats the Wi-Fi Direct channel or the phone's battery
  level, which are already on the mirrored screen and the phone's status bar.

### Fixed
- Stopping the bridge no longer freezes the app for a moment while it shuts down.

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
