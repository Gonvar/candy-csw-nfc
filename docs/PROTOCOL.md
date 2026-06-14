# Candy CSW 475D — NFC protocol (validated)

This is the canonical, on-machine-validated protocol used by the Android app in
this repo. It is reconstructed from the wire behaviour (APDU exchanges) of a real
unit. The historical research journey — including dead ends — is in
[`STATE.md`](STATE.md) and [`FINDINGS.md`](FINDINGS.md).

> Two things in the older notes are **superseded**: the `"AA"` write password and
> writing to file `0001` with the `STORE` action (`0x0a`). The path that actually
> drives the machine is **file `0002`**, a **16-zero** password, and the **`START`
> action (`0x0b`)**, described below.

## Tag / transport

- NFC chip: **ST M24SR02-Y** — a dual-interface dynamic tag (NFC Forum **Type 4A**
  on the RF side, I²C to the washer's MCU on the other).
- The phone talks to it with **ISO 7816-4 APDUs** over `IsoDep` / ISO 14443-4.
- The tag exposes a **CC file** plus two **NDEF files**:
  - **File `0001`** — read `00` (free), write `FF` (locked). The **STATUS** file
    (read-only): URI record + a status record of 25 ASCII digits + CRC. This is
    what "check statistics" reads.
  - **File `0002`** — read `00`, write `80` (password-protected). The **COMMAND**
    file: the phone writes a command here, the washer MCU reads it over I²C and
    writes back a response.

The chip UID, ATQA and SAK are unit-specific and not needed to talk to it.

## Write password

The write password is the **M24SR factory default: 16 zero bytes**.

```
00 20 00 02 10 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00   VERIFY write pwd -> 90 00
```

- `00 20 00 02` = VERIFY, `P2 = 02` selects the **write** password (`P2 = 01` is the
  read password). `10` = Lc (16 bytes follow).
- An **empty** verify `00 20 00 02 00` is a status probe → `63 00` ("pwd required").
  It does **not** burn the retry counter.
- A wrong password returns `63 Cx` where `x` = retries left. The counter resets on
  any successful VERIFY or on any official-app write.

## START_PROGRAM_CYCLE command (action `0x0b`)

An 18-byte NDEF external record. The whole record **is** the file-`0002` content
(NLEN = `0x12` = 18).

```
 idx  val   meaning
 [0]  D4    NDEF external record header (MB+ME+SR, TNF=4)
 [1]  01    type length
 [2]  0E    payload length = 14
 [3]  02    type name 0x02   (0x02 = command; 0x01 = status/response)
 [4]  80    command marker   <-- the washer flips this 80 -> 00 as its ACK
 [5]  0B    action = 11 (START_PROGRAM_CYCLE)
 [6]  ..    selector (program / dial position)
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

There is also `STORE_PROGRAM_CYCLE` (action `0x0a`, a longer payload, different map),
but the app uses `START` `0x0b` for "send special cycle", so that is what we
replicate. See `tools/candy_cycle.py` for both builders.

### Index tables (confirmed by live captures)

- **Temperature**: `0`=cold, `1`=20 °C, `2`=30 °C, `3`=40 °C, `4`=60 °C
- **Spin**: `0`=none, `1`=400, `2`=800, `3`=1000/1200, `4`=max
- **Soil**: `0`–`3`
- **Selector** = the base program; not a clean table yet. Observed: program A →
  selector `6`, program B → selector `2`. Map more by capturing official sends of
  named programs and reading byte `[6]` (see "Mapping your own programs" below).

The firmware **validates** parameters and rejects out-of-range combinations
(`6A 80` / `6B 00`), so experimenting is safe — you cannot invent arbitrary
behaviour, only compose within allowed bounds.

## CRC (`NFCUtility.crc16`) — validated

```
poly = 0x6363, init = 0xFFFF, reflected (LSB-first), final XOR = 0xFFFF, output big-endian.
```

For a command record the CRC is computed over bytes `[4..15]` (12 bytes) and written
big-endian at `[16..17]`. Validated against four real commands and the status record
(`crc16("3100832519036063243025033") == 0x9C40`). Reference implementations in
`tools/candy_cycle.py` (Python) and `android/.../MainActivity.java` (Java).

## Full write sequence

```
00 A4 04 00 07 D2 76 00 00 85 01 01 00     SELECT NDEF app
00 A4 00 0C 02 00 02                        SELECT command file 0002
00 20 00 02 00                              VERIFY (empty probe)        -> 63 00
00 20 00 02 10 <16x 00>                     VERIFY (write pwd, 16 zeros)-> 90 00
00 D6 00 00 02 00 00                        set NLEN = 0 (invalidate)
00 D6 00 02 12 <18-byte record>             UPDATE BINARY (write command)
00 D6 00 00 02 00 12                        set NLEN = 18 (commit)
```

All steps return `90 00`, and a read-back of file `0002` matches the bytes written.

## The post-write handshake (the crux)

Writing the bytes is necessary but **not sufficient**: after the write, the washer's
MCU has to read file `0002` over I²C, act on it, and **rewrite the file with byte
`[4]` flipped `80 → 00`** (command → response) plus a recomputed CRC. That flip is
the **ACK**; the cycle loads/auto-starts at that point.

The catch is **M24SR RF/I²C arbitration**: while the phone holds an open RF session,
the MCU is locked out of the EEPROM and can't read the command. The official app
works around this by repeatedly releasing and reopening the RF session (~18 reconnects)
during a ~2 s window, handing the MCU read slots.

**So the working recipe is: write → then poll for the ACK while releasing the field
between reads.** Each poll iteration: `IsoDep.close()` → sleep ~250 ms → `connect()`
→ re-SELECT app → SELECT file `0002` → READ → check whether byte `[4]` became `00`.
Keep the phone on the pad the whole time. See `doWrite()` in `MainActivity.java` for
the reference loop.

## Mapping your own programs

The program tables are Candy's proprietary data and are **not** shipped here. To map
selector/option values for your model, capture what the official app sends:

1. Install the Candy/hOn (`it.candy.simplyfi`) app on an Android phone, connect it
   over USB, and grow + clear the logcat buffer:
   `adb logcat -G 16M ; adb logcat -c`
2. Send a known program from the app to the washer, then dump: `adb logcat -d > cap.log`
3. The app logs every APDU. Extract them:
   - `grep -oE 'request: 00 [0-9a-f ]+' cap.log` — all APDUs sent
   - `grep -oE 'd4 01 [0-9a-f ]+ 90 00' cap.log | sort -u` — file-0002 command/ack history
4. Change one parameter at a time between captures and diff byte `[6]` (selector) and
   the option bytes. Plug the values into `candy_cycle.py` or the Android app fields.
