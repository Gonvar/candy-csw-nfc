# Candy CSW 475D NFC — full state & handoff

Master doc. Goal: program/start custom wash cycles on a **Candy CSW 475D/5-S** washer-dryer
over NFC, eventually from an iPhone app (whole household has iPhones). Everything below is
reverse-engineered and (where noted) validated on the real machine.

Working dir: `~/Desktop/candy-nfc-re/`

---

## STATUS IN ONE LINE
We can **write** the exact START-cycle command to the machine flawlessly (all APDUs return
`9000`, read-back proves the bytes land). The machine **does not act on it** — it never sends
its acknowledgment. The open problem is the **post-write handshake / RF↔I2C timing**, not the
command bytes. Last thing in flight: making our app release+reopen the NFC session while polling
for the ack (UNCONFIRMED — test pending).

---

## HARDWARE
- NFC chip: **ST M24SR02-Y**, NFC Forum **Type 4A**, ISO 7816-4 APDUs over IsoDep.
- Tag UID `02 82 66 99 85 01 83`, ATQA `0x0042`, SAK `0x20`.

## TWO NDEF FILES (from the CC file)
CC file (`00 B0 00 00 17`) = `00 17 20 00 F6 00 F6  04 06 00 01 00 80 00 FF  04 06 00 02 00 80 00 80`
- **File `0001`**: read `00` (free), write `FF` (locked). = **STATUS** file (read-only).
  Holds: URI record `http://www.candysmarttouch.com/vsa/wd/` + a status record
  (External type `01`, 25 ASCII digits + CRC). This is what "check statistics" reads.
- **File `0002`**: read `00`, write `80` (**password-protected, writable**). = **COMMAND** file.
  Phone writes a command here; the washer controller reads it over I²C and writes back a response.

## WRITE PASSWORD
- **16 zero bytes** (`NFCLibrary.DEFAULT_PASSWORD`, = M24SR factory default). The "AA" path in
  `initiatePasswordsWrite` is a RED HERRING (different code path; gave `63C2`).
- Presented via VERIFY: `00 20 00 02 10` + 16×`00` → `9000`. (P2=`02` write pwd; P2=`01` read pwd.)
- Empty verify `00 20 00 02 00` = status probe → `63 00` "pwd required" (does NOT burn the counter).
- Wrong pwd → `63Cx` (x retries left). Counter RESETS on any successful VERIFY or official-app write.

## COMMAND FORMAT — START_PROGRAM_CYCLE (action 0x0b), written to file 0002
18-byte NDEF external record (the whole record = the NDEF file content; NLEN = `0x12` = 18):
```
 idx  val   meaning
 [0]  D4    NDEF ext header (MB+ME+SR, TNF=4)
 [1]  01    type length
 [2]  0E    payload length = 14
 [3]  02    type name 0x02  (0x02 = command; 0x01 = status/response)
 [4]  80    command marker  <-- washer flips this 80->00 as its ACK
 [5]  0B    action = 11 (START_PROGRAM_CYCLE)
 [6]  ..    selector (program number; varies per program — seen 02 and 06)
 [7]  ..    temperature INDEX
 [8]  ..    spin INDEX
 [9]  ..    soil (0-3)
 [10] ..    opt1
 [11] ..    opt2
 [12] ..    opt3
 [13] ..    xr (extra rinse)
 [14] FF    delay (0xFF = off)
 [15] 00    dry
 [16] ..    CRC hi  } crc16 over bytes [4..15] (12 bytes), big-endian
 [17] ..    CRC lo  }
```
(There is also STORE_PROGRAM_CYCLE action `0x0a`, 17-byte payload, different map — but the app uses
START `0x0b` for "send special cycle", so that's what we replicate.)

## INDEX TABLES (confirmed by 2 live captures)
- **Temperature**: `0`=cold, `1`=20°C, `2`=30°C, `3`=40°C, `4`=60°C  (30→2, 20→1 confirmed)
- **Spin**: `0`=none, `1`=400, `2`=800, `3`=1000/1200, `4`=max  (400→1, 1200→3 confirmed)
- **Soil**: 0-3
- **Selector** = program; not a clean table yet. Seen: program A → selector 6, program B → selector 2.
  To map more: capture official sends of named programs and read the selector byte.

## CRC (NFCUtility.crc16) — VALIDATED against 4 real commands
poly `0x6363`, init `0xFFFF`, reflected (LSB-first), final XOR `0xFFFF`, output big-endian.
Computed over the payload minus its last 2 bytes (for commands: bytes [4..15]).
Validated: `"3100832519036063243025033"`→`9C40`; `80 0b 06 02 01 00..ff 00`→`898B`;
`80 0b 06 04 03 00..`→`C900`; `80 0b 02 01 03 02 00..`→`AC69`. Python impl in `candy_cycle.py`.

## FULL WRITE SEQUENCE (from official-app logcat capture — all return 9000)
```
00 A4 04 00 07 D2 76 00 00 85 01 01 00     SELECT NDEF app
00 A4 00 0C 02 00 02                        SELECT command file 0002
00 20 00 02 00                              VERIFY (empty)  -> 63 00
00 20 00 02 10 00 00 ... 00  (16 zeros)     VERIFY (write pwd) -> 90 00
00 D6 00 00 02 00 00                        set NLEN = 0
00 D6 00 02 12 <18-byte record>             UpdateBinary (write command)
00 D6 00 00 02 00 12                        set NLEN = 18
```

## THE HANDSHAKE / ACK (this is the crux)
After the write, the washer controller reads file `0002` over I²C, processes it, and **rewrites
file 0002 with byte[4] flipped `80`→`00`** (command→response) and a recomputed CRC. That flip is
the ACK; the machine loads + auto-starts the cycle at that point.
- In the capture: command written at t=34.2s, ACK (`00 0b...`) appeared at t=36.1s → ~**2 s** later.
- The official app does **~18 separate "SELECT app" cycles** (reconnects) during that window.
- The user's description of the official UX: "does the handshake, tells you to WAIT, then says OK to move."

## WHAT WORKS vs WHAT DOESN'T (empirically)
WORKS:
- Reading file 0001 (status) — perfect.
- Writing the command to file 0002 — all `9000`; read-back of 0002 == exact bytes written.
- VERIFY with 16 zeros → `9000` (write unlocked).
DOESN'T:
- The washer never ACKs our write (byte[4] stays `80`); cycle never loads.
TRIED & FAILED:
- Writing to file 0001 → `6982` (wrong file).
- "AA" password → `63C2` (wrong pwd).
- Keep phone on, poll within ONE continuous session → no ack.
- Remove phone after write → no reaction.

## LEADING HYPOTHESIS for the no-ACK
**M24SR RF/I²C arbitration**: while the phone holds an open RF session, the washer's controller is
locked out of the EEPROM and can't read file 0002. The official app keeps releasing+reopening the
session (the 18 reconnects), handing the controller read windows. Our app held one session.

## IN FLIGHT (built & installed, NOT yet confirmed)
App's ack-poll now does `iso.close()` → `sleep 250ms` → `iso.connect()` → re-SELECT app → read
file 0002 each iteration (up to ~6 s), watching byte[4] flip `80`→`00`. Also converted to LIVE
logging (was dumping only at the end → looked frozen). TEST RESULT PENDING.

## NEXT IDEAS if close/reopen still fails
1. **Full field drop**: after writing, fully `disableReaderMode` for ~1.5 s (drops the RF field so
   the controller definitely gets the bus), then re-enable and read 0002 to check the ack. Or the
   simple manual version: write → lift phone → tap again to read 0002 and confirm `00`.
2. **Match the official app's exact inter-pass behavior**: does it send an ISO-DEP DESELECT (S-block)
   between passes? Re-capture and inspect frame-level timing.
3. The official app also presents the **READ** password (`00 20 00 01`, ~24×) — maybe part of the
   required handshake; try interleaving it.
4. The "OK to move" prompt implies the controller may only **commit when the field drops** — test
   write-then-lift carefully with the new live build so timing is visible.
5. Inspect M24SR **GPO** config / whether the controller is interrupt- or poll-driven.

---

## ENVIRONMENT / TOOLING (all on the Mac)
- `adb`; Android SDK cmdline-tools + build-tools `34.0.0` + platform `android-34` at
  `/usr/local/share/android-commandlinetools`; Temurin JDK 17; `jadx` 1.5.5; Python; frida venv (`.venv`).
- Device: **Nexus 5X** (bullhead), Android 8.1, arm64, USB-debug authorized.
  Official app pkg `it.candy.simplyfi`; our app pkg `dev.candywriter.nfc` ("Candy Cycle Writer").

## KEY FILES under ~/Desktop/candy-nfc-re/
- `STATE.md` (this file) · `PROTOCOL.md` · `FINDINGS.md`
- `candy_cycle.py` — validated CRC + record/APDU builder (Python).
- `CandyCycleWriter/` — the Android writer app (source `src/dev/candywriter/nfc/MainActivity.java`).
  Build = manual javac→d8→aapt2→zipalign→apksigner (JDK17 + build-tools 34); `debug.keystore` at root.
- `android-apk/` — pulled official APK, `out/sources/` (jadx decompile), `capture_official_write.log`
  (temp30/spin400), `capture2.log` (temp20/spin1200).
- `extracted/` — `Programs.json` (29 à-la-carte programs), `alc.sqlite`, `download_programs.sqlite`,
  CSVs, `nfc_command_catalogue.txt`.
- `CandyControl/` — iOS (SwiftUI + Core NFC) app scaffold for TestFlight. **NEEDS UPDATING** to the
  final protocol: write file 0002, START action 0x0b, 16-zero password (Core NFC `NFCISO7816Tag.sendCommand`,
  declare AID `D2760000850101` in Info.plist, requires PAID Apple Developer acct, distribute via TestFlight).

## REBUILD THE ANDROID APP
```
cd ~/Desktop/candy-nfc-re/CandyCycleWriter
SDK=/usr/local/share/android-commandlinetools; BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-34/android.jar; JDK17=$(/usr/libexec/java_home -v 17); export JAVA_HOME=$JDK17
rm -rf build && mkdir -p build/classes
$JDK17/bin/javac -classpath $AJ -source 11 -target 11 -nowarn -d build/classes src/dev/candywriter/nfc/MainActivity.java
$BT/d8 --min-api 19 --lib $AJ --output build $(find build/classes -name '*.class')
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml --min-sdk-version 19 --target-sdk-version 27
( cd build && zip -j -q base.apk classes.dex )
$BT/zipalign -f 4 build/base.apk build/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey build/aligned.apk
adb install -r build/aligned.apk
```

## DECODED REFERENCE: re-capture an official send (to map selectors / debug handshake)
```
adb logcat -G 16M ; adb logcat -c          # grow + clear buffer
# ... do an official-app send on the phone ...
adb logcat -d > cap.log
grep -oE 'request: 00 [0-9a-f ]+' cap.log   # all APDUs the app sent
grep -oE 'd4 01 [0-9a-f ]+ 90 00' cap.log | sort -u   # file-0002 command/ack history
```
The app logs every APDU via `Type4TagOperationBasicOp: ==> transcievecmd request: <hex>` / `==> answer:`.
