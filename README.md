# candy-csw-nfc

Reverse-engineering notes and working tools for talking to a **Candy CSW 475D/5-S**
washer-dryer over **NFC** — reading its status and **programming/starting a custom
wash cycle** without the official app.

The whole protocol is documented so you can **do your own** on your own machine. A
working Android app is included; an iOS path is scoped out below.

> **Status:** validated end-to-end on Android against a real unit — the app writes a
> `START_PROGRAM_CYCLE` command, the machine ACKs it, and the cycle loads. iOS is a
> scaffold + feasibility analysis (see [iOS](#ios-feasibility)).

---

## ⚠️ Disclaimer — read first

- This is **independent interoperability research**. It is **not affiliated with,
  endorsed by, or supported by Candy, Haier, or any vendor.** "Candy" and product
  names are trademarks of their owners, used here only to describe what the code
  talks to.
- This repo contains **only original work** — protocol notes reconstructed from
  observed behaviour, plus apps written from scratch. It deliberately contains **no
  vendor APK, no decompiled source, and no data files extracted from the official
  app.** If you need program/option values for your model, the docs show how to
  capture them yourself from your own device.
- **You operate a large mains-powered appliance with heaters and a motor at your own
  risk.** The firmware validates parameters and rejects out-of-range combinations,
  but you are responsible for what you start. Don't run a cycle you can't supervise.
  No warranty — see [`LICENSE`](LICENSE).
- Reverse engineering for interoperability is permitted in many jurisdictions (e.g.
  the EU Software Directive); your local law is your responsibility.

---

## How it works (one paragraph)

The washer's NFC pad is an **ST M24SR02-Y** dual-interface tag: NFC Forum Type 4A on
the phone side, I²C to the machine's controller on the other. You speak **ISO 7816-4
APDUs** to it. There are two NDEF files — `0001` (read-only **status**) and `0002`
(password-protected **command**). To start a cycle you VERIFY the write password
(the chip's **16-zero factory default**), write an 18-byte `START_PROGRAM_CYCLE`
record (action `0x0b`, CRC-16 over the parameter bytes) to file `0002`, then **poll
for the ACK while releasing the RF field between reads** — the controller can only
read the command over I²C when the phone isn't holding the RF session. When it
accepts, it flips a marker byte `80 → 00` and the cycle loads.

Full details, byte map, index tables and APDU sequences: **[`docs/PROTOCOL.md`](docs/PROTOCOL.md)**.

## Repo layout

```
docs/
  PROTOCOL.md   canonical, on-machine-validated protocol (start here)
  STATE.md      full research handoff: hardware, every APDU, the ACK problem & fix
  FINDINGS.md   earlier static-analysis notes (command catalogue, field schema)
android/        Candy Cycle Writer — the working app (single MainActivity.java + build steps)
ios/            CandyControl — SwiftUI + Core NFC scaffold (read/dump; write path is TODO)
tools/
  candy_cycle.py  CRC + command-record + APDU-script builder; `python3 tools/candy_cycle.py`
```

## Quick start (Android — the path that works)

1. You need a physical Android phone with NFC and the command-line Android SDK + JDK 17.
2. Build & install the app — see **[`android/README.md`](android/README.md)**
   (includes generating your own debug keystore; no key is shipped here).
3. In the app: **READ** (hold to the NFC pad) to confirm comms, then **WRITE** with the
   default fields to replay a known-good cycle, then change parameters.

To map your model's program/option values, capture the official app's APDUs over
`adb logcat` — instructions in [`docs/PROTOCOL.md`](docs/PROTOCOL.md#mapping-your-own-programs).

## iOS feasibility

Short version: **technically possible, with a paid Apple Developer account.**

- **Core NFC can do it.** `NFCTagReaderSession` + `NFCISO7816Tag.sendCommand` sends the
  exact APDUs used on Android, and you declare the NDEF AID `D2760000850101` in
  `Info.plist`. The `ios/` scaffold already reads/dumps the tag this way.
- **The catch is signing.** Core NFC's tag-reading capability is **blocked on free
  personal teams** (you'll hit "error 159"). You need the **paid Apple Developer
  Program ($99/yr)** to enable the entitlement and install on a device / ship via
  TestFlight.
- **The harder catch is the ACK handshake.** The write path needs the
  *release-the-field-between-polls* loop (the M24SR RF/I²C arbitration trick). On
  Android that's `IsoDep.close()/connect()`. Core NFC manages the session more opaquely
  — `restartPolling()` and invalidating/reopening the session are the levers to try,
  and this is the one part not yet proven on iOS.
- **Off-the-shelf option:** general NFC apps (e.g. **NFC Tools** / **TagInfo**) can read
  the tag and *some* support raw APDU/"advanced" commands, so a determined user can
  send the write sequence by hand — but none speak Candy's command format or do the
  ACK-poll loop, so they won't cleanly "start a cycle" out of the box. A purpose-built
  app (the `ios/` scaffold, finished) is the realistic route for a whole household of
  iPhones.

**Recommendation:** finish the `ios/` app's write+ACK flow, mirroring `MainActivity.java`,
and distribute to the family via TestFlight. That's the cleanest "everyone on iPhone can
use it" answer. See [`ios/README.md`](ios/README.md) for setup and TestFlight steps.

## License

[MIT](LICENSE) for the code and docs in this repo. Trademarks belong to their owners.
