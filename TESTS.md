# Candy CSW 475D — community test plan

Open questions in the protocol, ordered so the cheap, safe tests settle the expensive ones
first. Every test says what it **answers**. If a result is unexpected, stop and re-plan
instead of pushing on. Anything marked **verified** in the [README](README.md) was measured
on one CSW 475D/5-S; your unit may differ, so record what *you* see.

Method throughout: capture traffic, change **one** parameter at a time, diff the records
(README §11). Results are only worth publishing if they come from captures or from what the
machine does when you send a command.

Capture recipe (Android, `adb logcat`; see README §11):

```bash
adb logcat -G 16M; adb logcat -c
# ... do the thing on the phone ...
adb logcat -d > cap-NAME.log
grep -oE 'request: 00 [0-9a-f ]+' cap-NAME.log
grep -oE 'd4 01 [0-9a-f ]+ 90 00' cap-NAME.log | sort -u
```

The dial must be in NFC mode for anything that touches the machine. Don't start a cycle you
can't supervise.

---

## Tier 0 — read-only, cannot start a cycle

### T1. Read the error log
`GET_LAST_ERROR` (opcode `06`) and `GET_MICROCONTROLLER_ERROR_COUNTERS` (`04`) from the README
§4 table. They are reads, so they cannot start anything. Same flow as a write: put the
command in file `0002`, pulse GPO, read the response back.
- **Answers:** why the board pauses mid-cycle, if yours does.
- **Record:** the raw response bytes.

### T2. Read system file E101
`SELECT E101`, then `READ`, no write.
- **Answers:** which GPO mode the M24SR is configured in. Know this before tuning the
  200 / 1200 / 350 ms timings in the example apps.
- Pure read, zero risk.

### T3. Read status file 0001
Already works. It is the 10-second sanity check that comms are healthy before a session.

---

## Tier 1 — capture traffic from the official app (observe only)

### T4. Does this machine use STORE (`0A`) at all?
Run the official app's custom-program flow and capture. Look at byte `[5]` of the record
written to file `0002`.
- `0A` → STORE exists on this unit; README §6 can be tested and Tier 3 is real.
- `0B` → this unit builds custom cycles through START only; skip Tier 3.
- **Why it matters:** README §6 is currently unverified. No STORE record has been accepted by
  the unit this repo was validated on.

### T5. Option bit map
Baseline capture with every option off. Then one capture per option, with only that option on.
- **Diff:** START bytes `[10]`, `[11]`, `[12]`.
- **Answers:** which bit is which option on your machine. Turns three opaque bytes into
  named switches. About a dozen taps; fully black-box.

### T6. Is extra rinse a count?
Set 1, then 2, then 3 extra rinses and capture each.
- **Expect:** byte `[13]` = `01`, `02`, `03`.

### T7. Pin the ends of the temperature index
Send 60 °C from the official app.
- **Expect:** byte `[7]` = `04` (README §7).

---

## Tier 2 — your own writer, parameter probing (starts cycles — supervise)

### T8. Re-check which programs honour temperature and spin
README §7 records which selectors accept your temp/spin and which force their own. Confirm
for the ones marked "by symmetry": send each at 60 °C / spin index 4 and read the display.
- Shown as sent → honoured. Something else → forced; note what.

### T9. Fill gaps in the selector table
Any selector you haven't measured: send at a reference config, note duration and displayed
temperature/spin. Check for duplicates (two selectors with the same behaviour).

### T10. Spin ceiling
Send spin index 4 on a program that normally shows 1000. Does it reach 1400?

---

## Tier 3 — the frontier (only if T4 showed `0A`)

### T11. Can STORE raise a program's ceiling?
STORE a program based on a rapid sub-cycle with `maxTemp` raised, then START it above the
temperature that program normally allows.
- **Answers:** whether you can get combinations the machine otherwise refuses.
- Change **one** field at a time. Out-of-range is rejected with `6A 80` / `6B 00`, so a
  refusal is a clean, safe answer.

### T12. Unknown STORE bytes
Take a known-good STORE record. Set byte 14 = `01`, recompute the CRC, send. Then 15, 17, 18.
- **Answers:** whether the firmware reads bytes whose function is unknown (README §6).
- One byte per attempt. Record `90 00` + ACK flip versus rejection for each.

---

## Do not send

- **`0C` START_LINE_TEST_CYCLE** — factory line test.
- Anything that writes persistent machine state (EEPROM or calibration writes), or changes
  the tag's lock or password state. A wrong byte there is not recoverable the way a bad wash
  cycle is, and you can lock yourself out of file `0002`.
- If you find opcodes beyond the README §4 table, **read** commands are fine to probe; treat
  anything that might write as off limits unless you fully understand it.

## If you only do three

**T4** (does STORE exist), **T5** (option bits), **T8** (confirm the forcing table). They decide
the shape of everything else and are all low-risk.
