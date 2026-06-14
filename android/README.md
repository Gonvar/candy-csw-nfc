# Candy Cycle Writer — Android app

A minimal, hands-on tool for the Candy CSW 475D washer's NFC tag. No Gradle, no
dependencies — a single `MainActivity.java` you build with the command-line SDK.

- **READ** — dumps the NDEF file (safe; proves comms).
- **WRITE** — unlocks file `0002` with the 16-zero password, writes a
  `START_PROGRAM_CYCLE` command built from the on-screen fields, then polls for the
  washer's ACK while releasing the RF field between reads (see
  [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) for why).

The default field values reproduce a captured, known-good command (selector 6,
temp 30 °C, spin 400) — a guaranteed-good replay to confirm your setup before you
start changing parameters.

## Prerequisites

- A **physical Android phone with NFC** (an emulator can't do this). USB debugging on.
- Android **command-line tools** + **build-tools 34.0.0** + **platform android-34**.
- **JDK 17** (Temurin works).
- `adb` on your PATH.

## One-time: create a debug keystore

This repo intentionally does **not** ship a signing key. Generate your own once:

```sh
keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

## Build & install

```sh
cd android
SDK=/usr/local/share/android-commandlinetools          # adjust to your SDK path
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$(/usr/libexec/java_home -v 17)        # macOS; otherwise point JAVA_HOME at JDK 17

rm -rf build && mkdir -p build/classes
$JAVA_HOME/bin/javac -classpath $AJ -source 11 -target 11 -nowarn -d build/classes \
  src/dev/candywriter/nfc/MainActivity.java
$BT/d8 --min-api 19 --lib $AJ --output build $(find build/classes -name '*.class')
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml \
  --min-sdk-version 19 --target-sdk-version 27
( cd build && zip -j -q base.apk classes.dex )
$BT/zipalign -f 4 build/base.apk build/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey build/aligned.apk
adb install -r build/aligned.apk
```

## Use

1. Open **Candy Cycle Writer** on the phone.
2. Tap **READ next tap**, hold the phone to the washer's NFC pad → you should see the
   status payload. This confirms comms.
3. Set the fields (start with the defaults), tap **WRITE cycle next tap**, and hold the
   phone steady to the pad. Keep it there — the app releases/reopens the session while
   polling for the ACK and reports `✅ WASHER ACK ...` when the machine accepts it.

If unlock fails with `63Cx` (wrong password / counter), send any cycle from the
official app once to reset the counter, then retry.
