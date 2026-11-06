# Changelog

User-facing changes to CR-BYOB. This file is the single source for release notes. The app shows it in
"What's new", the GitHub release body is cut from it, and so is the Google Play "What's new" text.

Format: one `## [X.Y.Z] - YYYY-MM-DD` section per release (newest first), with `### Added` /
`### Changed` / `### Fixed` / `### Known limitations` subsections and one short line per change, written
for people using the app. Collect changes under `## [Unreleased]` and rename it to the version and date
when tagging. A release build fails if its version has no dated section here.

## [Unreleased]

### Added
- Scanner frame rate (experimental): Stock, Boost or Max in the Network card. Behind a bridge Creality Scan
  asks for 15 fps from an Otter; Boost gets 20 and Max 30, worked out from the attached scanner's own modes.
  Max needs a very fast and stable link (Wi-Fi 6E/7 or Gigabit Ethernet). Creality Scan reads it
  when it connects, so the choice is locked while it's connected.
- Mirror quality: tap the "Mirror … fps" pill on the viewer to set the frame rate and the 3D view's
  resolution. "Adjust automatically" uses them as a ceiling: it lowers the resolution, then the frame rate,
  when the mirror stutters, and raises them again once it's smooth.
- The viewer's stats panel shows the bridge's link: Wi-Fi signal, link rate and band, or the Wi-Fi Direct
  channel (Android reports no signal for a group owner's clients). The dashboard's Wi-Fi line adds the Wi-Fi
  generation and the upload/download link rates.

### Changed
- The bridge keeps about a quarter second of frames per stream (was two frames), so short stalls on the
  phone's network no longer drop frames or halve the IR rate.
- A stream's dashboard badge turns amber only when frames are actually lost, not when a few go out late.
- Stream quality is judged on the last 10 s of drops (was 30 s) and shown as drops per second. In the
  viewer it reads "Stream: Good (0.0 drops/s)", colored, and the Wi-Fi line has signal bars instead.
- The viewer's "Frame rate" is now labeled "PC scan rate" (it's Creality Scan's processing rate), and the
  status line shows the rate each mirrored view arrives at.
- The CR-Scan Ferret Pro is now tested and supported, thanks to a device report. The dashboard no longer
  warns that its streams may be wrong.

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
