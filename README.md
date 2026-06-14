# Candy CSW 475D — NFC protocol reference

Complete, on-machine-validated documentation of the **NFC protocol** used by the
**Candy CSW 475D/5-S** washer-dryer, so you can read its status and **program/start a
custom wash cycle** without the official app.

This repo is **information, not a product.** It is everything needed to implement a
client on any platform (Android `IsoDep`, iOS Core NFC, libnfc, PN532, Proxmark3,
phone NFC apps with raw-APDU support). A small Python reference implementation of the
CRC and the command builder lives in [`candy_cycle.py`](candy_cycle.py).

Validated against a real unit: the full write sequence below makes the machine load and
auto-start a cycle. Where something is a hypothesis or a dead end, it is labelled as
such.

---

## ⚠️ Disclaimer — read first

- **Independent interoperability research.** Not affiliated with, endorsed by, or
  supported by Candy, Haier, or any vendor. "Candy" and product names are trademarks of
  their owners, used only to describe what this protocol talks to.
- This repo contains **only original documentation and code** — no vendor APK, no
  decompiled source, no data files extracted from the official app. It explains how to
  capture model-specific values from **your own** device.
- **You are operating a mains-powered appliance with heaters and a motor.** The firmware
  validates parameters and rejects out-of-range combinations, but you are responsible
  for what you start. Don't run a cycle you can't supervise. No warranty — see
  [`LICENSE`](LICENSE).
- Reverse engineering for interoperability is permitted in many jurisdictions (e.g. the
  EU Software Directive); your local law is your responsibility.

---

## 1. Hardware & transport

- **NFC chip: ST M24SR02-Y** — a dual-interface dynamic NFC tag. RF side is an NFC Forum
  **Type 4A** tag; the other side is **I²C to the washer's controller (MCU)**. This
  dual nature is the whole story of the handshake (§9).
- The phone speaks **ISO 7816-4 APDUs** over **ISO 14443-4 / `IsoDep`**.
- Chip identifiers seen: **ATQA `0x0042`, SAK `0x20`** (these identify the M24SR chip
  type). The 7-byte UID is **unit-specific** and not needed to communicate.

## 2. Tag file system (Type 4)

A **Capability Container (CC) file** plus two **NDEF files**. Read the CC file to learn
the NDEF file IDs and their access rights:

```
SELECT CC:  00 A4 00 0C 02 E1 03
READ CC:    00 B0 00 00 17
CC bytes:   00 17 20 00 F6 00 F6   04 06 00 01 00 80 00 FF   04 06 00 02 00 80 00 80
                                    └─ file 0001 ─┘            └─ file 0002 ─┘
```

| File   | Read | Write | Role |
|--------|------|-------|------|
| `0001` | `00` (free) | `FF` (locked) | **STATUS** (read-only). Holds a URI record (`http://www.candysmarttouch.com/vsa/wd/`) + a status record (external type `01`, 25 ASCII digits + CRC). This is what "check statistics" reads. |
| `0002` | `00` (free) | `80` (password) | **COMMAND**. The phone writes a command record here; the MCU reads it over I²C and writes back a response. |

> Writing a START/STORE command goes to **file `0002`**. (An earlier hypothesis used file
> `0001` — that's wrong; it returns `6982`.)

## 3. Write password

The write password is the **M24SR factory default: 16 zero bytes**, presented via the
M24SR `VERIFY` command:

```
00 20 00 02 10  00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00     -> 90 00
└INS 20┘ │  │  └ Lc=16 ┘ └──────────── 16-byte password ───────────┘
        P1 P2=02 (write pwd; P2=01 would be the read password)
```

- **Empty** verify `00 20 00 02 00` is a status probe → `63 00` ("password required").
  It does **not** burn the retry counter — useful to check lock state.
- Wrong password → `63 Cx`, where `x` = retries left. The counter **resets** on any
  successful VERIFY or any write by the official app.
- **Dead end:** the `"AA" + 15×00` password (which appears in the app's
  `initiatePasswordsWrite`) is a different, unused code path and returns `63 C2`. Use the
  16 zeros.

## 4. Command set (opcodes)

Each command is an NDEF external record whose payload begins with a marker byte and an
**action opcode**. Read commands fetch counters/diagnostics; write commands change state.

| Opcode | Command | Dir | Notes |
|-------:|---------|-----|-------|
| 01 | GET_PROGRAM_CYCLES_COUNTERS | read | stats |
| 02 | GET_WASHING_TEMPERATURE_COUNTER | read | stats |
| 03 | GET_SPIN_COUNTER | read | stats |
| 04 | GET_MICROCONTROLLER_ERROR_COUNTERS | read | diagnostics |
| 05 | GET_DSP_ERROR_COUNTERS | read | diagnostics |
| 06 | GET_LAST_ERROR | read | diagnostics |
| 07 | GET_MAIN_SW_VERSION | read | firmware id |
| 08 | GET_UI_SW_VERSION | read | firmware id |
| 09 | GET_EEPROM_CRC | read | integrity |
| **0A** | **STORE_PROGRAM_CYCLE** | **write** | load a custom cycle (§6) |
| **0B** | **START_PROGRAM_CYCLE** | **write** | start a cycle (§5) — the validated path |
| 0C | START_LINE_TEST_CYCLE | write | factory line test — **do not send** |
| 11 | GET_DRYING_CYCLE_COUNTERS | read | dryer stats |
| TD_05 | START_PROGRAM_CYCLE_DRY | write | start a dry cycle (washer-dryer) |

The external record type domain seen in the binaries is **`candy.com:transp.ctrl`**
(`urn:nfc:ext:candy.com:transp.ctrl`), with a `CandyCtrl` variant. The firmware rejects
out-of-range parameters (`6A 80` / `6B 00` / "Incorrect Parameter"), so you compose
within allowed bounds — you can't invoke arbitrary behaviour.

## 5. START_PROGRAM_CYCLE record (action `0x0B`) — validated

An **18-byte** NDEF external record. The whole record **is** the file-`0002` content
(NLEN = `0x12` = 18).

```
 idx  val   meaning
 [0]  D4    NDEF external record header (MB+ME+SR, TNF=4)
 [1]  01    type length
 [2]  0E    payload length = 14
 [3]  02    type name 0x02   (0x02 = command; 0x01 = status/response)
 [4]  80    command marker   <-- the washer flips this 80 -> 00 as its ACK (§9)
 [5]  0B    action = START_PROGRAM_CYCLE
 [6]  ..    selector (base program / dial position)
 [7]  ..    temperature INDEX   (table below)
 [8]  ..    spin INDEX          (table below)
 [9]  ..    soil (0-3)
 [10] ..    opt1
 [11] ..    opt2
 [12] ..    opt3
 [13] ..    xr (extra rinse)
 [14] FF    delay (0xFF = off)
 [15] 00    dry
 [16] ..    CRC hi   } crc16 over bytes [4..15] (12 bytes), big-endian (§7)
 [17] ..    CRC lo   }
```

Example validated command (selector 6, 30 °C, 400 spin):
`D4 01 0E 02 80 0B 06 02 01 00 00 00 00 00 FF 00 89 8B`.

## 6. STORE_PROGRAM_CYCLE record (action `0x0A`) — structure

A **21-byte** record (NLEN = `0x15`). Structure confirmed from the app; field value
encodings less proven than START. Carries the full program definition with min/max bounds
the firmware enforces.

```
 idx  val   meaning
 [0]  D4    NDEF external header (MB+ME+SR, TNF=4)
 [1]  01    type length
 [2]  11    payload length = 17
 [3]  02    type name 0x02 (command)
 [4]  80    payload marker
 [5]  0A    action = STORE_PROGRAM_CYCLE
 [6]  ..    chart page
 [7]  ..    sub-cycle (selects the base program)
 [8]  ..    default temp
 [9]  ..    max temp
 [10] ..    default soil
 [11] ..    default spin
 [12] ..    max spin
 [13] ..    option 1
 [14] ..    option 2
 [15] ..    option 3
 [16] ..    charge
 [17] ..    (unused / 0)
 [18] ..    (unused / 0)
 [19] CRC hi  } crc16 over bytes [4..18] (15 bytes), big-endian
 [20] CRC lo  }
```

The program definition uses named fields seen in the app's demo data:
`selector_position`, `default_temperature`/`maximum_temperature`,
`default_spin_speed`/`maximum_spin_speed`,
`default_soil_level`/`minimum_soil_level`/`maximum_soil_level`,
`available_options`/`av0`/`av1`/`av2` (option bitmasks),
and duration/remaining-time fields. The `min*`/`max*` values are the bounds the firmware
enforces; set the `default_*` values within them.

## 7. Index tables (parameter encodings)

Confirmed from live captures:

- **Temperature index:** `0`=cold, `1`=20 °C, `2`=30 °C, `3`=40 °C, `4`=60 °C
- **Spin index:** `0`=none, `1`=400, `2`=800, `3`=1000/1200, `4`=max
- **Soil:** `0`–`3`
- **Selector** = base program; not a clean table yet. Observed: program A → selector `6`,
  program B → selector `2`. Map the rest with the capture method in §11.

## 8. CRC-16 (`NFCUtility.crc16`) — validated

```
polynomial = 0x6363   init = 0xFFFF   reflected (LSB-first)   final XOR = 0xFFFF   output big-endian
```

Computed over the payload bytes excluding the trailing 2 CRC bytes, then written
big-endian at the end. For a START command that is bytes `[4..15]` (12 bytes); for STORE,
`[4..18]` (15 bytes).

Validated reproductions: `crc16("3100832519036063243025033") = 0x9C40` (the status
record), and the four captured commands including the START example above
(`… FF 00` → `89 8B`). See [`candy_cycle.py`](candy_cycle.py).

```python
def crc16(data: bytes) -> int:
    i = 0xFFFF
    for b in data:
        i ^= b & 0xFF
        for _ in range(8):
            i = (i >> 1) ^ 0x6363 if (i & 1) else (i >> 1)
    return (~i) & 0xFFFF
```

## 9. The post-write ACK handshake — the crux

Writing the bytes is necessary but **not sufficient.** After the write, the washer's MCU
must read file `0002` over I²C, act on it, and **rewrite the file with byte `[4]` flipped
`80 → 00`** (command → response) plus a recomputed CRC. **That flip is the ACK** — the
cycle loads/auto-starts at that point.

The obstacle is **M24SR RF↔I²C arbitration:** while the phone holds an open RF session,
the MCU is locked out of the EEPROM and can never read the command. In a capture, the
command was written at t=34.2 s and the ACK appeared at t≈36.1 s (~2 s later); during that
window the official app performs **~18 separate SELECT-app cycles** (it keeps releasing and
reopening the RF session), handing the MCU read slots.

**Working recipe:** after the write, **poll for the ACK while releasing the RF field
between reads.** Each iteration: drop the session (e.g. `IsoDep.close()` / fully drop the
field) → wait ~250 ms → reconnect → re-`SELECT app` → `SELECT 0002` → `READ` → check
whether byte `[4]` is now `00`. Keep the phone on the pad the whole time; expect the ACK
within a few seconds. On platforms without explicit field control (iOS Core NFC), the
levers are `restartPolling()` and invalidating/reopening the session — this is the part
to validate per platform.

## 10. Full APDU sequences

### Read status (file 0001, free)
```
00 A4 04 00 07 D2 76 00 00 85 01 01 00     SELECT NDEF app
00 A4 00 0C 02 E1 03                        SELECT CC file
00 B0 00 00 17                              READ CC  (gives the NDEF file IDs)
00 A4 00 0C 02 00 01                         SELECT status file 0001
00 B0 00 00 02                              READ NLEN
00 B0 00 02 <nlen>                          READ content
```

### Write + start a cycle (file 0002)
```
00 A4 04 00 07 D2 76 00 00 85 01 01 00     SELECT NDEF app
00 A4 00 0C 02 00 02                        SELECT command file 0002
00 20 00 02 00                              VERIFY (empty probe)         -> 63 00
00 20 00 02 10 <16x 00>                     VERIFY (write pwd, 16 zeros) -> 90 00
00 D6 00 00 02 00 00                        set NLEN = 0 (invalidate)
00 D6 00 02 12 <18-byte START record>       UPDATE BINARY (write command) at offset 2
00 D6 00 00 02 00 12                        set NLEN = 18 (commit)
--- then the §9 ACK poll: release field / reconnect / read 0002 until byte[4] == 00 ---
```

All steps return `90 00`, and a read-back of file `0002` matches the bytes written. The
machine acts only after the ACK flip.

## 11. Capture & map your own programs

Program/option/selector values are model-specific and are **not** shipped here (that data
is Candy's). Capture them from your own device:

1. Install the Candy/hOn app (`it.candy.simplyfi`) on an Android phone, connect over USB,
   then grow + clear the log buffer:
   ```
   adb logcat -G 16M ; adb logcat -c
   ```
2. Send a known program from the app to the washer, then dump: `adb logcat -d > cap.log`.
   The app logs every APDU (look for `Type4TagOperationBasicOp: ==> transcievecmd request:`).
3. Extract them:
   ```
   grep -oE 'request: 00 [0-9a-f ]+' cap.log              # all APDUs sent
   grep -oE 'd4 01 [0-9a-f ]+ 90 00' cap.log | sort -u    # file-0002 command/ACK history
   ```
4. Change one parameter at a time between captures and diff byte `[6]` (selector) and the
   option bytes. Plug the values into [`candy_cycle.py`](candy_cycle.py).

## 12. What works, what doesn't

**Works**
- Reading file `0001` (status) — perfect.
- Writing the command to file `0002` — all `90 00`; read-back matches exactly.
- VERIFY with 16 zeros → `90 00` (write unlocked).
- Write **+ the §9 release-and-poll ACK loop** → machine accepts and starts the cycle.

**Dead ends**
- Writing to file `0001` → `6982` (wrong file).
- `"AA"` password → `63 C2` (wrong password path).
- Holding one continuous RF session and polling → **no ACK** (MCU never gets the bus).
- Removing the phone immediately after the write → no reaction.

## 13. Reference implementation

[`candy_cycle.py`](candy_cycle.py): the validated CRC, a `START_PROGRAM_CYCLE` builder, a
`STORE_PROGRAM_CYCLE` builder, and the write APDU script. Run it to print a ready-to-send
command and self-test the CRC:

```
python3 candy_cycle.py
```

## License

[MIT](LICENSE) for the code and documentation here. Trademarks belong to their owners.
