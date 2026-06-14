# Candy Control — iOS NFC app

A SwiftUI + Core NFC app to read (and later write) wash-cycle data on a Candy
washer's ST M24SR NFC chip (ISO 14443-4 / ISO 7816).

**Phase 1 (now):** dump the tag's NDEF file to reverse-engineer the cycle byte format.
**Phase 2 (after we have the bytes):** a cycle builder that writes STORE_PROGRAM_CYCLE +
START_PROGRAM_CYCLE.

## What's here
- `Sources/NFCManager.swift` — Core NFC session + raw APDU read/write engine (the core).
- `Sources/ContentView.swift` — UI: "Dump cycle data" button + raw-APDU console + share log.
- `project.yml` — XcodeGen spec. Regenerate the project with `xcodegen generate`.
- The project is pre-generated: open **CandyControl.xcodeproj**.

## One-time setup (needs a PAID Apple Developer account — $99/yr)
Core NFC is sandbox-blocked on free personal teams ("error 159"), so this step is required.

1. Open `CandyControl.xcodeproj` in Xcode.
2. Select the **CandyControl** target → **Signing & Capabilities**:
   - **Team**: choose your paid developer team.
   - **Bundle Identifier**: change `com.example.candycontrol` to something unique,
     e.g. `com.yourname.candycontrol`.
   - Signing is Automatic; Xcode will create the provisioning profile (the
     "Near Field Communication Tag Reading" capability + the declared AID are already
     in the entitlements/Info.plist).
3. Plug in your iPhone, select it as the run destination, and **Run** (⌘R).

> The NDEF AID `D2760000850101` is already declared in `Info.plist`
> (`com.apple.developer.nfc.readersession.iso7816.select-identifiers`). That's the
> standard NDEF Tag Application ID — public, not a blocked payment AID.

## Capturing the cycle format
1. On a phone with the **Candy/hOn app**, load a known program into the washer
   (e.g. Cotton 40°C / 1200 spin).
2. Open Candy Control on your iPhone → tap **Dump cycle data** → hold the top edge of
   the iPhone to the washer's NFC pad.
3. Read the log:
   - **Payload bytes appear** → great, the data is persistent. Tap **Share log** and
     send it over. Repeat changing one parameter at a time (temp→60, spin→800, etc.).
   - **"File is EMPTY / transient"** → the washer clears it after ingest; we pivot to
     live sniffing (Proxmark3).
4. The raw-APDU console lets you send any command by hand for experiments.

## Shipping to the family via TestFlight
1. In Xcode: **Product → Archive**.
2. In the Organizer: **Distribute App → App Store Connect → Upload**.
3. On [App Store Connect](https://appstoreconnect.apple.com): create the app record
   (same bundle id), go to **TestFlight**, add the build, and invite family by email
   (Internal/External testers, up to 100). They install via the **TestFlight** app.

## Regenerating the project
If you edit `project.yml`: `cd CandyControl && xcodegen generate`.
