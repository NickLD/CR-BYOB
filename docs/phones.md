# Phone compatibility

How each tested phone performs on each connection. All results are with a **CR-Scan Otter** on the phone's
USB 3 port, the only scanner tested so far.

| Phone | Android | Ethernet | Wi-Fi | Wi-Fi Direct | This phone only (Local Mobile App Scanning) | Reported by |
|---|---|:---:|:---:|:---:|:---:|---|
| [Google Pixel 10 Pro XL](#google-pixel-10-pro-xl) | 16 | ✅ | ✅ | ✅ | ✅ | [@NickLD](https://github.com/NickLD) |
| [Google Pixel 5](#google-pixel-5) | 14 | ✅ | 🟡 | 🟡 | ⚠️ | [@NickLD](https://github.com/NickLD) |

✅ Full performance &nbsp;·&nbsp; 🟡 Works, with limits &nbsp;·&nbsp; ⚠️ Not recommended

At full rate the Otter's three video streams need about **265 Mbit/s** between the phone and the PC, so the
phone's network hardware matters as much as its processor. See
[Choosing a connection](../README.md#choosing-a-connection).

## Tested your phone? Send a device report

Tried CR-BYOB on a phone that isn't listed, or got different results on one that is? File a
[Device report](https://github.com/NickLD/CR-BYOB/issues/new?template=device-report.yml), whether it works or
not, and say which connections you tried. Long-press the version at the bottom of the dashboard to copy your
app version, phone model and Android version for the report. Your phone gets added here, credited to your
GitHub username.

## Google Pixel 10 Pro XL

Android 16 · Reported by [@NickLD](https://github.com/NickLD)

**Full performance on every connection**: Ethernet, Wi-Fi, Wi-Fi Direct and This phone only.
It carries the full ~265 Mbit/s on Wi-Fi Direct, and on Wi-Fi when the phone is close to the access point.

## Google Pixel 5

Android 14 · Reported by [@NickLD](https://github.com/NickLD)

| Connection | Result |
|---|---|
| Ethernet | ✅ Works perfectly. The least demanding link for the phone. |
| Wi-Fi | 🟡 OK, as long as the signal from the access point is strong. |
| Wi-Fi Direct | 🟡 Decent, as long as the phone isn't also connected to a Wi-Fi network. |
| This phone only (Local Mobile App Scanning) | ⚠️ Not recommended for scanning on the phone with the CrealityScan app. |

**Wi-Fi and Wi-Fi Direct at the same time** severely hinders performance. The two share the phone's radio.
The app warns when the phone is still on a Wi-Fi network while Wi-Fi Direct runs. Disconnect from that
network, or use Ethernet.
