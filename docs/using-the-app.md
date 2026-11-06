# Using the app

## What you need

- An Android 8+ phone with USB host (OTG) support and a **USB 3** port (required: the scanner's streams need
  more bandwidth than USB 2 provides).
- A USB 3 hub with power passthrough, so the scanner and phone don't run on the phone's battery. Without it
  the scanner can brown out mid-scan; the dashboard shows a red warning when the phone is on battery.
- For scanning from a PC: Creality Scan on the PC and a fast link between them (below). For scanning on the
  phone: the CrealityScan app on the same phone.

**The link.** With a CR-Scan Otter at full rate the bridge sends about 265 Mbit/s (two IR streams and color).
Other scanners differ, and higher-resolution ones need more. So:

- **Ethernet is the most reliable.** Many powered USB-C hubs also have an Ethernet port: one hub then gives the
  scanner its USB 3 port, keeps the phone charged and adds a wired link. It must be **Gigabit** (100 Mbit
  Ethernet can't carry the streams). Cable it to the router or switch your PC is on; thin, flat Ethernet cables
  are cheap and easy to route. Android sets up USB Ethernet by DHCP, so a cable straight to the PC (no router)
  won't give the phone an address.
- **Wi-Fi Direct** needs a phone and a PC with Wi-Fi 5 (802.11ac) or newer on 5 GHz; Wi-Fi 6 or newer is better.
- **Home Wi-Fi** needs a Wi-Fi 5 or newer router on 5 GHz with a strong signal at the phone. On 2.4 GHz expect
  about 6 fps instead of 15.

## First start

1. Install the APK from [Releases](https://github.com/NickLD/CR-BYOB/releases) and open **CR-BYOB**.
2. Allow what it asks for. Each permission is explained before Android's prompt:
   - **Camera** (required): Android only lets apps with it open USB cameras, and the scanner is one.
     CR-BYOB never uses the phone's own cameras.
   - **Notifications**: the running bridge shows a status notification with a Stop button.
   - **Location** (optional): Android hides the connected Wi-Fi's name behind it. It's used only to show
     the network name and to warn about weak or 2.4 GHz Wi-Fi.
   - **Nearby devices**: asked when you choose Wi-Fi Direct.
3. Plug the scanner in. Android asks whether CR-BYOB may open it; tick "always" and accept.
4. Choose a network (below) and tap **Start**.

## Choosing a network

<img src="screenshots/network-choices.png" alt="Network choices" width="380">

*Run streaming server on* decides where the bridge can be found. The choice is fixed while the bridge runs;
stop it to change.

| Choice | Use it for |
|---|---|
| **Wi-Fi Direct** | The phone makes its own network, like the official Scan Bridge. Join it from the PC with the name and password shown, then start Creality Scan. |
| **Your Wi-Fi** | Phone and PC on the same home network. Needs a strong 5 GHz connection for the full frame rate. |
| **Ethernet** | Shown when a USB Ethernet adapter (or a hub's Ethernet port) is connected. The most reliable choice: no radio interference, full rate. Use Gigabit. |
| **This phone only (for CrealityScan mobile app)** | Scan on the phone itself with Creality's CrealityScan app, no PC. Nothing on the network can find or reach the bridge, and Wi-Fi speed doesn't matter. |

The bridge answers on exactly one network at a time: a PC that saw it on two would hang at Preview.

**Wi-Fi Direct details.** The network name is `DIRECT-SB-CRBYOB-` plus 4 characters unique to the phone, and the
password is shown below it (both copyable). *Edit Wi-Fi Direct settings* (while stopped) changes the part after
`DIRECT-SB-` and the password (8–63 characters). If the phone also stays on another Wi-Fi network, the two share
the phone's radio; the dashboard warns and offers to disconnect it. To join from Windows without the Wi-Fi
menu, use the profile template [`android/CRBYOB/DIRECT-SB-CRBYOB.xml`](../android/CRBYOB/DIRECT-SB-CRBYOB.xml)
(put your network name and password in first):

```bat
netsh wlan add profile filename=DIRECT-SB-CRBYOB.xml user=current
netsh wlan connect name="DIRECT-SB-CRBYOB-XXXX" ssid="DIRECT-SB-CRBYOB-XXXX" interface="Wi-Fi"
```

The group hands out a default gateway (192.168.49.1); if the PC also has Ethernet, make sure the Wi-Fi
route's metric is higher (`route print 0.0.0.0`).

**Scanner frame rate (experimental).** Behind a bridge, Creality Scan asks for less than the scanner can do:
15 fps for an Otter (30 on USB) and 10 for a Ferret. *Scanner frame rate* in the Network card raises that.
Nothing in Creality Scan is changed: the bridge advertises only the faster modes, and Creality Scan picks the
closest rate on offer.

| Choice | Otter | What it costs |
|---|---|---|
| **Stock** | 15 fps, ~280 Mbit/s | Creality Scan's own rate |
| **Boost** | 20 fps, ~380 Mbit/s | a middle step from the scanner's own modes |
| **Max** | 30 fps, ~570 Mbit/s | the scanner's fastest mode; needs a very fast and stable link (Wi-Fi 6E/7 or Gigabit Ethernet) |

Wired Gigabit is the safe choice for Max. A phone very close to a Wi-Fi 6E/7 access point can link faster
than Gigabit and has handled Max too (in one test it ran smoother than USB Ethernet); the viewer's stats panel
shows the Wi-Fi signal and link rate to judge it. The rates come from the attached scanner, so the card shows
them once the bridge is running. A scanner that
Creality Scan already runs at its top rate shows only Stock. Creality Scan reads the rate when it connects, so
the choice is locked while it's connected: close Creality Scan, change it, and open Creality Scan again (the
bridge can keep running).

## The dashboard

<p>
  <img src="screenshots/dashboard.png" alt="Dashboard while streaming" width="300">
  <img src="screenshots/dashboard-network.png" alt="Network section and events" width="300">
</p>

From top to bottom:

- **Status**: a light (grey off, amber ready, blue PC connected, pulsing green streaming, red problem), a
  one-line status such as "Streaming to 192.168.49.224 via Wi-Fi Direct", and Start/Stop. Below: scanner
  model, firmware and USB speed, then battery and power source.
- **Mirror** button and the auto-mirror and keep-screen-on switches (see Mirror viewer below).
- **Link quality** and **Streams** (while a PC or the phone app is connected): frames sent vs captured per
  stream, Mbit/s and a 60 s graph. Link quality is estimated from delivery, because Android reports no
  per-client signal for a Wi-Fi Direct group owner. When the link can't carry both IR streams, the bridge
  sends both at half rate in step (a "½ rate" badge) rather than dropping left and right frames unevenly.
- **Session stats**: time, data sent, average and peak bitrate, and per-stream delivery. *Details* has
  reliability counters and latency.
- **Network**: the choice above, plus the Wi-Fi signal and speed warnings.
- **Events**: recent events in plain words, with the raw log below.
- **Version** at the bottom: tap for *What's new* (and *Check for updates* in GitHub builds); long-press to
  copy your app version, phone and Android version for a bug report. GitHub builds also have a Ko-fi button
  under it.

<img src="screenshots/dashboard-session.png" alt="Session statistics" width="300">

## Mirror viewer

The **Mirror** button shows Creality Scan's color, IR and 3D views on the phone, like Creality's own phone
app, with the scan controls (Start, Pause, Finish, Cancel) and a distance bar.

<p>
  <img src="screenshots/mirror-portrait.png" alt="Mirror viewer, portrait" width="300">
  <img src="screenshots/mirror-landscape.png" alt="Mirror viewer, landscape" width="560">
</p>

- In Creality Scan on the PC, press **Mobile connection**. The PC only accepts a phone while that dialog is
  open. With *Auto-start screen mirroring* on (the default), the phone connects by itself and the button reads
  *Mirror · connected, tap to view*.
- Back returns to the dashboard and keeps the session; ✕ closes it. ⋮ offers a manual PC address and
  Reconnect, for networks that drop the PC's broadcast.
- Pinch zooms the 3D view on the PC; drag pans and double-tap resets. In landscape, the side column folds
  away with the edge tab.
- While the viewer is hidden, the phone asks the PC for a low-bandwidth mirror stream, so it doesn't compete
  with the scanner streams.

**Mirror quality**: tap the *Mirror … fps ▾* pill on the main view.
- *Frame rate* (30, 20, 15 or 10 fps) and *3D view resolution* (Full, 75% or 50%). Creality Scan only takes the
  resolution when the mirror connects, so changing it reconnects the mirror for a second or two.
- *Adjust automatically* (on by default) treats both as a ceiling. When more than 5% of the last 20 s of mirror
  video froze, it lowers the resolution (Full → 75% → 50%) and, once that's at 50%, the frame rate
  (30 → 20 → 15 → 10): smooth motion matters more than detail for aiming the scanner. After 30 s of smooth
  video it raises the frame rate again, and after 2 smooth minutes the resolution. Creality Scan
  sends mirror frames unevenly while it processes a fast scan; in testing a 15 fps mirror froze about a third
  as often as a 30 fps one. The pill shows what's in effect, e.g. *Mirror 20 fps · 960×720 · auto*, in amber while Auto runs below
  your choices.

Neither changes what the scanner sends to the PC. The viewer's status line shows the rate each view actually
arrives at ("Mirror: RGB 15 · IR 10 · 3D 15 fps").
*PC scan rate* is Creality Scan's own figure: how many scanner frames per second it processes.

## Updates

GitHub builds check GitHub Releases once a day and offer new versions with their release notes; *Download*
opens the APK in your browser. Google Play builds update through Play. After each update, *What's new*
shows what changed.

## Troubleshooting

- **Restart Creality Scan after restarting the bridge.** It reads the bridge's details once per session.
- **Low frame rate.** With an Otter the bridge sends about 265 Mbit/s at full rate. Ethernet (Gigabit) fixes this for
  good; otherwise use 5 GHz, keep the phone near the router or the PC, or use Wi-Fi Direct or *This phone
  only*. On 2.4 GHz expect about 6 fps.
- **Scanner disconnects.** Usually power: use a powered hub (the dashboard shows "on battery" in red).
- **Bridge not found.** Check that the PC is on the network chosen under *Run streaming server on*, and that
  Creality Scan isn't also seeing the scanner over USB on the same PC.
- **Reporting a problem.** Long-press the version at the bottom of the dashboard to copy your app and phone
  details, and open an [issue](https://github.com/NickLD/CR-BYOB/issues) (or a
  [Device report](https://github.com/NickLD/CR-BYOB/issues/new?template=device-report.yml) for scanner or phone
  results). Logs are in the dashboard under *Raw log*; passwords never appear in them.
