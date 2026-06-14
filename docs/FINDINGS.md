# Candy CSW 475D/5-S — NFC protocol reverse-engineering notes

Source: static analysis of `/Applications/simply-Fi.app` (bundle id `it.candy.remote.candy-ios`,
the iOS app running on Apple Silicon Mac). All facts below are pulled from the app's own
frameworks and bundled data — nothing here is from sniffing the machine yet.

## Hardware / transport stack (confirmed)

- The washer's NFC chip is an **ST M24SR02-Y** — a dual-interface dynamic NFC tag
  (RF NFC Forum **Type 4A** tag on one side, I²C to the washer MCU on the other).
- The phone talks to it with **ISO 7816-4 APDUs** over RF (the app's `m24sr7816*`,
  `ndefSelectcmd`, `ndefreadlengthcmd`, ReadBinary/UpdateBinary routines).
- File system on the tag: **CC file + NDEF file** (classic Type 4). Decoded by
  `decodeTagType4A` in `CHGNFC.framework`.
- **Write protection:** the chip supports a password (`m24srVerify`, "password must be
  empty or equal to 0x10" → 16-byte password). MAY or MAY NOT be enabled on this machine.
  If writes are refused, this is the gate. Unknown until tested.

## Message layer

The cycle data rides inside an **NDEF External Type record**:

- External type domain seen in binary: **`candy.com:transp.ctrl`**
  (i.e. `urn:nfc:ext:candy.com:transp.ctrl`), plus a non-transparent `CandyCtrl` variant.
- Internal classes: `NDEFCandyCtrlMessage`, `NDEFCandyCtrlTranspMessage`.
- So when reading the tag with NFC TagInfo you are looking for an **External record**
  whose type contains `candy.com` / `transp.ctrl`. Its payload = the command message.

## Application command set (from CHGNFC binary class names)

Each command is `CandyNFCCommandMessage_NN_*`. The NN index almost certainly = the wire
opcode. Washing-machine / washer-dryer relevant ones:

| NN | Command | Direction | Use |
|----|---------|-----------|-----|
| 01 | GET_PROGRAM_CYCLES_COUNTERS | read | stats |
| 02 | GET_WASHING_TEMPERATURE_COUNTER | read | stats |
| 03 | GET_SPIN_COUNTER | read | stats |
| 04 | GET_MICROCONTROLLER_ERROR_COUNTERS | read | **diagnose the board fault** |
| 05 | GET_DSP_ERROR_COUNTERS | read | diagnostics |
| 06 | GET_LAST_ERROR | read | **diagnose the board fault** |
| 07 | GET_MAIN_SW_VERSION | read | firmware id |
| 08 | GET_UI_SW_VERSION | read | firmware id |
| 09 | GET_EEPROM_CRC | read | integrity |
| **10** | **STORE_PROGRAM_CYCLE** | **write** | **load a custom cycle ← the goal** |
| **11** | **START_PROGRAM_CYCLE** | **write** | **start it** |
| 12 | START_LINE_TEST_CYCLE | write | factory line test (careful!) |
| 17 | GET_DRYING_CYCLE_COUNTERS | read | dryer stats |
| TD_05 | START_PROGRAM_CYCLE_DRY | write | start a dry cycle (washer-dryer) |
| DW_* / 31-33 | dishwasher / fridge | — | not relevant here |

Full list in `extracted/nfc_command_catalogue.txt`.

APDU-level validation exists: the machine returns "Incorrect Parameter P1-P2" and
"Incorrect Parameter in cmd data field" → **out-of-range values are rejected by firmware.**
You compose within allowed bounds; you can't invent arbitrary behaviour.

## Cycle parameter fields (from Demo Data/demo-washer-nfc.json)

A program (what STORE_PROGRAM_CYCLE carries) is built from these named fields:

- `selector_position`  — which base program (dial position) the cycle is based on
- `default_temperature`, `maximum_temperature`
- `default_spin_speed`, `maximum_spin_speed`
- `default_soil_level`, `minimum_soil_level`, `maximum_soil_level`
- `available_options`, `av0`, `av1`, `av2`  — option bitmasks (prewash, extra rinse, etc.)
- `default_duration`, `remaining_time_default`,
  `remaining_time_soil_min` / `_medium` / `_max`

The `max*`/`min*` fields are bounds the firmware enforces — set the `default_*` values
within them. 18 base programs are defined (NFC_PROGRAM_NAME_2D_* : COTTONS, RAPID_14/30/44,
DELICATES, WOOL, HAND_WASH, JEANS, ECOMIX_20, BABY_CARE, RINSE, DRAIN_SPIN, SYNTHETICS …).

## What is still unknown (the only gaps left)

1. **Exact byte offsets/widths** of each field inside the STORE_PROGRAM_CYCLE data block.
2. **Value encoding** per field: literal (60 = 0x3C) vs index (3 = "4th temp option").
   The `alc-cycles.csv` / `alc.sqlite` `selector_position` values help map program → index.
3. **Whether the NDEF write password is enabled** on this specific unit.

All three are answered by **one or two targeted reads** with the Android (NFC TagInfo),
now that we know to look at the `candy.com:transp.ctrl` External record and that data
byte[0] ≈ command id (0x0A = 10 store, 0x0B = 11 start).

## Recommended next steps

1. With Android (Candy app installed → it writes; NFC TagInfo → it reads), capture the
   `candy.com:transp.ctrl` record for 3-4 known cycles, changing one parameter at a time.
2. Map the bytes using the field list above (most are already named — this is fill-in,
   not blind diffing).
3. Build the custom STORE+START payload; write it via Android (libnfc/Core NFC) or a
   PN532. iPhone Shortcuts can't emit this proprietary External record.

## Side benefit — the board fault

Commands **06 GET_LAST_ERROR** and **04 GET_MICROCONTROLLER_ERROR_COUNTERS** let you read
the machine's own error log over NFC. That may reveal exactly *why* the logic board pauses
mid-cycle — potentially more useful than any custom cycle.

## Files in this folder

- `extracted/alc-cycles.csv`, `alc-wash.csv`, `alc-dry.csv` — À-La-Carte program tables
- `extracted/alc.sqlite` — choice/recommendation tables (fabric+soil → cycle/temp/spin)
- `extracted/download_programs.sqlite` — downloadable program priorities
- `extracted/demo-washer-nfc.json`, `demo-washer_dryer-nfc.json` — program field schema
- `extracted/nfc_command_catalogue.txt` — full command list from the binary
