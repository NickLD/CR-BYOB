# How it works

CR-BYOB speaks the same protocols as Creality's Scan Bridge, so Creality Scan (PC) and CrealityScan (phone)
treat the phone as one. The scanner is driven through Android's USB host API (`UsbDeviceConnection` control
transfers, `UsbRequest` bulk and interrupt URBs).

## Network services

| Port | What |
|---|---|
| UDP 3956 | GVCP discovery responder: PID 0x0C01 "Scan Bridge", with an unsolicited ACK every second |
| TCP 8090 | HostProtocol control: answers bridge-owned properties itself and relays the rest to the scanner's command extension unit (XU) |
| TCP 8556 / 8557 / 8554 / 8888 | RTSP with RTP interleaved over TCP (IR / IR_LEFT, IR_RIGHT, DEPTH, COLOR) |
| TCP 8900 | Raw 248-byte HID reports (IMU and scanner button) |
| UDP 57301 (listen only) | Creality Scan's mirror broadcast (`OrbbecRTCServer`), for the Mirror button |
| TCP 8999 (debug builds) | Link test: sends zeros for 10 s to measure the Wi-Fi link |

None of these has authentication, as on the official bridge. Anyone on the same network or Wi-Fi Direct
group can view the streams and send allow-listed control commands. *This phone only* closes them to
everything except apps on the phone.

## Discovery: one network at a time

GVCP collects every local interface whose subnet contains the requester, keeps those allowed by the chosen
network (`advertise` = `p2p` / `wifi` / `eth` / `local` in `files/stream_map.json`), and prefers Wi-Fi Direct,
then Ethernet, then Wi-Fi. Keepalives re-evaluate the same rule every second. Exactly one network answers,
because Creality's SDK hangs at Preview when it sees the same bridge (same MAC) at two addresses.

**This phone only** (`local`): CrealityScan's SDK sends discovery out of every interface (loopback, Wi-Fi,
Ethernet, Wi-Fi Direct, mobile data). The bridge answers only requests from the phone's own addresses, never
broadcasts the reply, and advertises `127.0.0.1` in every reply, so the app sees one bridge at one address.
The control, RTSP and IMU servers refuse every peer that isn't the phone itself (`peer_refused` in the log).

## Control relay

- **Safety filter**: only opcodes {1,2,3,5,17,18,19,25,26,27,29,30,31} and the writes (28, 0x413) and
  (4, 1004) reach the scanner. Everything else gets status 2 and a `blocked_opcode` log line. Firmware and
  file-transfer opcodes are never forwarded.
- **XU relay**: SET_CUR on the smallest of the 64/512/1024-byte selectors that fits the frame, then GET_CUR on
  the selector for the expected reply size (0x200 for ops 3/5/27, 0x400 for 18/25/30, else 0x40), retried
  until magic `RB` and the request ID match.
- **Bridge-owned properties**: 111 → 0x0C01 (0 while no scanner is attached, as the official bridge reports a
  disconnected device), 0x91 → 1, and the 0x42E Wi-Fi security struct (stored in `files/wifi.json`, never
  applied to a real network).
- **Lists**: 0xFC1 (IR Y8 profiles, color MJPG, ACCEL/GYRO) and the fixed 0xFCB port table, built from the
  scanner's model entry in [`models/scanners.json`](../models/scanners.json).
- **Camera controls** 2000–2027: UVC GET/SET on the camera terminal and processing unit of the matching
  function, with uvcvideo's V4L2 value semantics.
- **Pre-stream sets**: properties 0x70=1 and 0x12=1 are relayed before every IR stream.

## Streams

The VideoStreaming format and frame descriptors are parsed from `getRawDescriptors()`. Then come
VS_PROBE/VS_COMMIT, several queued bulk `UsbRequest`s sized to `dwMaxPayloadTransferSize` (up to 4 MB of URBs
per stream; usbfs allows 16 MB in total), and payload decoding that follows uvcvideo's bulk decoder (header
strip, FID toggle, EOF, error bit). Errored frames and Y8 frames whose size isn't w×h are dropped. A stream is
stopped with CLEAR_FEATURE(ENDPOINT_HALT) and SET_INTERFACE(alt 0).

- **Paired IR decimation**: when the link can't carry both raw IR streams (about 245 Mbit/s for the Otter), independent
  per-stream queues would drop different exposures on left and right. `IrGate` then sends only frames on a
  shared capture-time grid (every k-th 1/15 s slot, k ≤ 3, automatic), so drops stay paired. With no drops,
  k stays 1.
- **IMU**: the HID interrupt endpoint is read only while a TCP 8900 client is connected; every report is
  forwarded unchanged.
- **Dead peers**: a PC that walks out of Wi-Fi Direct range sends no FIN or RST, so a plain `write()` would
  block forever. Every write goes through `StallGuard` (no progress for 3 s closes the socket), accepted
  sockets get TCP keepalive and `TCP_USER_TIMEOUT`, a stalled holder of a stream can be taken over by a new
  session, and closing the PC's control connection closes its streams.
- **Limits**: RTSP requests are capped (16 KB header, 64 KB body), and each server caps its connections.

## Wi-Fi Direct

The phone becomes a Wi-Fi Direct group owner (192.168.49.1), as the official bridge is its own access point;
the phone's normal Wi-Fi keeps working. `files/p2p.json` holds the name, password and `freq` (0 = try the
current Wi-Fi channel on 5 GHz first, then any 5 GHz channel; a MHz value forces one). The bridge's serial
number is `CRBYOB` plus 4 hex digits from a random per-install MAC, which also ends the default network
name, so two phones never look like the same bridge.

## Mirror viewer

Java does Creality Scan's TCP signaling (int32-LE length + JSON; the PC sends the WebRTC offer). WebRTC runs
in a WebView (`assets/mirror.html`) with no ICE servers: three video tracks and a data channel carrying
JSON-RPC 2.0 (`init` with a 4:3 model texture size, a 2 s heartbeat, `trackInfo`, `stateSync`, scan
commands, `camera.zoom`). The session belongs to the app, not the viewer screen, so it survives rotation and
leaving the viewer. Readiness comes from passively listening for the PC's UDP 57301 broadcast; the app never
probes the PC's TCP port, because the PC treats any connection as a phone joining.

## Source map (`android/CRBYOB/src/com/crbyob/bridge`)

| File | Role |
|---|---|
| `MainActivity` | dashboard, Start/Stop, network choice; USB attach target |
| `BridgeService` | foreground service (`connectedDevice`), Wi-Fi and wake locks, USB broadcasts and permission |
| `Bridge` | ties everything together: roles, 0xFC1/0xFCB blobs, bridge properties, attach/detach, network mode |
| `Gvcp` | UDP 3956 discovery |
| `HostServer` | TCP 8090 control |
| `Otter` | USB device: claim, XU relay and safety filter, UVC requests, the URB reaper thread |
| `UsbDesc`, `UvcCapture` | descriptor parser; probe/commit, bulk URBs, frame assembly |
| `RtspServer`, `IrGate`, `StallGuard` | RTSP sessions and frame header; paired IR decimation; write-stall watchdog |
| `ImuServer`, `CameraControls` | TCP 8900 and HID reader; properties 2000–2027 → UVC controls |
| `ScannerModel`, `RleCodec` | per-model settings from `scanners.json`; RLE IR encoder |
| `P2pGroup` | Wi-Fi Direct group owner |
| `MirrorActivity`, `MirrorSession`, `MirrorWatch` | mirror viewer, its WebRTC session, the UDP 57301 listener |
| `Dash`, `Widgets`, `SessionStats`, `PermissionGuide` | dashboard state and widgets, session counters, permission explainers |
| `AppVersion`, `Changelog`, `Markdown`, `WhatsNew` | version, bundled changelog, release notes, "What's new" |
| `flavors/github/…/Updates`, `flavors/play/…/Updates` | GitHub Releases update check; no-op for Play |
| `flavors/github/…/SelfInstall`, `flavors/github/AndroidManifest.xml` | In-app update: download, checksum and signing-key check, PackageInstaller session (GitHub build only) |
| `Hp`, `BLog`, `LinkTest`, `TestPattern` | HostProtocol framing, logging, debug link test, synthetic frames |
