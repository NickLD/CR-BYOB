# Adding or fixing a scanner model

The app gets every model-specific setting from one table: [`models/scanners.json`](../models/scanners.json),
bundled into the APK. The CR-Scan Otter (PID `0x06DA`) and the CR-Scan Ferret Pro (PID `0x06A1`) are the
only models tested end to end (`status: verified`). Every other entry comes from reverse engineering and has
never been run on hardware.
If you own one of those scanners, a test report helps a lot: open a
[Device report](https://github.com/NickLD/CR-BYOB/issues/new?template=device-report.yml).

## How the app picks a model

1. It opens the first Orbbec USB device (VID `0x2BC5`) and reads its PID.
2. It looks up `models["0x<PID>"]` and merges it over `defaults`. Objects merge key by key; lists and plain
   values replace. `"inherit": "0x06DA"` merges another model first.
3. A local override, `files/scanners.json` in the app's private dir, is merged over the whole table. It is
   re-read at Start and on every scanner attach. Writing it needs a **debug build**
   (`adb shell run-as com.crbyob.bridge ...`; Android allows `run-as` only for debuggable apps). See
   [Building](building.md).
4. The model's `interfaces` decide which USB interface carries which stream. If they are `"auto"`, the app
   guesses from the UVC interface names (`auto_interfaces`: "Depth", "IR", "RGB"/"Color").

The app logs what it picked as `scanner_model` and `scanner_roles`, and shows the model on the dashboard.
**Include those lines when you report a problem.** Forcing a model: `{"model_pid": "0x06DA"}` in
`files/stream_map.json`.

## Fields

| Field | Meaning | Otter |
|---|---|---|
| `name`, `status`, `notes` | For people and logs. `status`: `verified` / `partial` / `unverified` / `unknown` | |
| `product_model` | ExtensionInfo `ProductModel`. If null, the USB product string is used, then `name` | `"CR-Scan Otter"` |
| `interfaces` | stream → USB interface number of its UVC **VideoControl** interface (Windows `MI_xx`), or `[vc, n]` for that VC's n-th VideoStreaming interface (two streams on one function, as the Sermoon/Pika IR_L + IR_R on MI_00 [I]), or `"auto"`. Streams: `ir`, `ir_l`, `ir_r`, `depth`, `color` | `ir`/`ir_l` 2, `ir_r`/`depth` 0, `color` 4 |
| `fc1_order` | Order of sensors in the 0xFC1 profile list (the SDK's `getSensorList()` order) | `ir_l, ir_r, color, ir` |
| `advertise` | V4L2 fourccs to list in 0xFC1 per kind (`ir` covers ir/ir_l/ir_r) | ir `GREY`, color `MJPG`, depth none |
| `ir_synth` | Extra encodings of Y8 IR profiles that the official bridge adds (`MJPG`, `RLE`). The app offers only `RLE` | `MJPG` |
| `ir_synth_min_fw` | Per synthesized encoding, the lowest scanner firmware for which it is offered, as `major*10000+minor*100+patch` [I]. The Raptor's client only decodes this RLE from 10107 [O]. Unknown firmware means it isn't offered | none |
| `rle` | `params`: default of struct 0x42A (5 × i32; field 0 = RLE flat-block variance threshold), which the bridge answers and accepts sets for on RLE models; `pad_to`: RLE frames are zero-padded to this size | `{100,60,6,15,15}`, 1500 |
| `ir_pre_stream_props` | `[prop, value]` bool sets sent to the scanner before an IR stream starts | `[[112,1],[18,1]]` (0x70, 0x12) |
| `ir_jpeg_quality` | Quality for IR→JPEG (the official bridge uses 98, and 90 for PID 0x0C05) | 98 |
| `bridge_fps` | The IR frame rate Creality Scan asks a bridge for (its "Stock" rate). It comes from the app's built-in profile table: Otter 15, Ferret 10 [O]. When unset, the bridge learns it the first time Creality Scan starts IR at Stock. Boost and Max are worked out from the scanner's own UVC modes | unset |
| `scan_options` | Scan settings Creality Scan offers for this model: `projection` (`infrared`, `blue_light`), `scan_mode` (`face`, `obj_small`, `obj_medium`, `obj_big`, `human`), `accuracy` (`fast`, `high`), and for laser scanners `light_type` and `fov`. From the scan library's built-in profile table (lib_orbbec_scan.dll) [O]; names as in `SupportedScanConfig`. Not every combination is valid: Creality Scan sends the exact list live while mirroring | unset |
| `creality_scan_config` | The `SupportedScanConfig` Creality Scan sent for this model, captured from a mirror session [O]: per scan mode, which fuse modes (geometry, texture/RGBD, marker) and which accuracy and FOV options are allowed. Only the Otter has one so far | unset |
| `imu.present` | Advertise ACCEL/GYRO and the TCP 8900 IMU port | true |
| `imu.hid_interface` | USB interface of the IMU HID. Null means the first HID interface | 6 |
| `imu.accel_profiles`, `imu.gyro_profiles` | `[fullScaleRange, sampleRate]` pairs for 0xFC1 | see defaults |
| `extra_opcodes`, `extra_writes` | Extra HostProtocol opcodes, or `[opcode, prop]` struct writes, that the relay may forward to this model. The base allow-list is in the code. Never add file-transfer/upgrade opcodes | none |

Top-level tables shared by all models: `stream_ports` (the official bridge's fixed RTSP ports), `imu_port`,
`formats` (V4L2 fourcc → Orbbec fourcc, RTP encoding name, RTSP URL suffix, and whether 0xFC1 may list it),
and `auto_interfaces`.

## Bringing up a new model (owner with the hardware)

1. Install a **debug build** ([Building](building.md)), plug the scanner in and start the bridge.
2. Save the log (`adb logcat -s CRBYOB`, or `adb shell run-as com.crbyob.bridge cat files/bridge.jsonl`) and the
   raw USB descriptors the app writes on every attach:
   `adb shell run-as com.crbyob.bridge cat files/otter_descriptors.bin > descriptors.bin`.
3. Connect Creality Scan and look for:
   - `scanner_roles`: is each stream on the right interface? If not, set `interfaces` in `files/scanners.json`.
   - `usb_vs_modes`: the formats and frame sizes each streaming interface offers.
   - `blocked_opcode`: the app needs an opcode the relay refuses. Check what it is before adding it to
     `extra_opcodes`.
   - `rtsp_req` URLs: the app asks for `<stream>/<w>_<h>_<fps>_<suffix>`. A suffix like `_rle` means the
     model wants an encoding the bridge doesn't produce yet.
4. Once it works, move the entry from your local file into `models/scanners.json`, set `status`, and open a
   pull request (or a [Device report](https://github.com/NickLD/CR-BYOB/issues/new?template=device-report.yml) with your file and logs).

Example `files/scanners.json` for an Otter variant that looks like the Otter:

```json
{"models": {"0x0C04": {"inherit": "0x06DA", "status": "partial", "notes": "works for me, fw 1.0.3"}}}
```

## Known gaps

- **RLE** (BlockStdDevCompression V3) is implemented in `RleCodec.java` (~1.4 ms per 1280×800 frame on a
  desktop JVM), but nobody has decoded it with a real client yet. It's offered for models with `"RLE"` in
  `ir_synth` that pass `ir_synth_min_fw`.
- **Depth streams**: the Otter's "Depth Camera" interface carries the right IR image. Models that stream real
  depth (Raptor: Y14 480×300@30 per the SDK config) need `advertise.depth` set to what the device actually
  outputs. The capture path has only been tested with GREY and MJPG.
- Two streams on one VC (`[vc, n]`) assume the n-th VideoStreaming interface carries the n-th stream. That is
  inferred, not seen. If a Sermoon/Pika instead has one VS carrying both eyes, this needs new code.
- Synthesized MJPG IR isn't offered: the app has no IR JPEG encoder in the default path.
- Bandwidth: Raptor IR defaults to 1920×1200@50 per eye, more than Wi-Fi carries even with RLE. Which profile
  the app picks over a bridge is unknown. *This phone only* mode avoids the Wi-Fi limit.
