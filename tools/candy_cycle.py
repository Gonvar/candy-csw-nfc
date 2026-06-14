#!/usr/bin/env python3
"""
Candy CSW 475D — NFC command payload + APDU builders.

Two command builders:
  - build_start_cycle()  -> action 0x0b, 18 bytes  (the VALIDATED path that drives the machine)
  - build_store_program_cycle() -> action 0x0a, 21 bytes  (earlier STORE path; structure only)

CRC validated against a real tag read (reproduces 9C 40). See docs/PROTOCOL.md for the
field index tables and the post-write ACK handshake.
"""

POLY = 0x6363  # NFCUtility.POLYNOMIAL = 25443

def crc16(data: bytes) -> int:
    i = 0xFFFF
    for b in data:
        i ^= b & 0xFF
        for _ in range(8):
            i = (i >> 1) ^ POLY if (i & 1) else (i >> 1)
    return (~i) & 0xFFFF


def build_store_program_cycle(
    chart=0, sub_cycle=0, def_temp=0, max_temp=0, def_soil=0,
    def_spin=0, max_spin=0, opt1=0, opt2=0, opt3=0, charge=0, b17=0, b18=0,
) -> bytes:
    """Return the 21-byte NDEF command record for STORE_PROGRAM_CYCLE (action 10)."""
    buf = bytearray(21)
    buf[0] = 0xD4            # NDEF external record header (MB+ME+SR, TNF=4)
    buf[1] = 0x01            # type length
    buf[2] = 0x11            # payload length = 17
    buf[3] = 0x02            # type name 0x02 (command)
    buf[4] = 0x80            # payload constant
    buf[5] = 0x0A            # action = 10 STORE_PROGRAM_CYCLE
    buf[6] = chart & 0xFF
    buf[7] = sub_cycle & 0xFF
    buf[8] = def_temp & 0xFF
    buf[9] = max_temp & 0xFF
    buf[10] = def_soil & 0xFF
    buf[11] = def_spin & 0xFF
    buf[12] = max_spin & 0xFF
    buf[13] = opt1 & 0xFF
    buf[14] = opt2 & 0xFF
    buf[15] = opt3 & 0xFF
    buf[16] = charge & 0xFF
    buf[17] = b17 & 0xFF
    buf[18] = b18 & 0xFF
    crc = crc16(bytes(buf[4:19]))          # over [4..18], 15 bytes
    buf[19] = (crc >> 8) & 0xFF            # big-endian
    buf[20] = crc & 0xFF
    return bytes(buf)


def build_start_cycle(
    selector=6, temp=2, spin=1, soil=0, opt1=0, opt2=0, opt3=0, xr=0, delay=0xFF, dry=0,
) -> bytes:
    """Return the 18-byte NDEF command record for START_PROGRAM_CYCLE (action 0x0b).

    This is the VALIDATED path that drives the machine. Index encodings:
      temp: 0=cold 1=20 2=30 3=40 4=60   spin: 0=none 1=400 2=800 3=1000/1200 4=max
      soil: 0-3   delay: 0xFF = off
    Defaults reproduce a captured known-good command (selector 6, 30C, 400 spin).
    """
    buf = bytearray(18)
    buf[0] = 0xD4           # NDEF external record header (MB+ME+SR, TNF=4)
    buf[1] = 0x01           # type length
    buf[2] = 0x0E           # payload length = 14
    buf[3] = 0x02           # type name 0x02 (command)
    buf[4] = 0x80           # command marker (washer flips 80->00 as ACK)
    buf[5] = 0x0B           # action = 11 START_PROGRAM_CYCLE
    buf[6] = selector & 0xFF
    buf[7] = temp & 0xFF
    buf[8] = spin & 0xFF
    buf[9] = soil & 0xFF
    buf[10] = opt1 & 0xFF
    buf[11] = opt2 & 0xFF
    buf[12] = opt3 & 0xFF
    buf[13] = xr & 0xFF
    buf[14] = delay & 0xFF
    buf[15] = dry & 0xFF
    crc = crc16(bytes(buf[4:16]))          # over [4..15], 12 bytes
    buf[16] = (crc >> 8) & 0xFF            # big-endian
    buf[17] = crc & 0xFF
    return bytes(buf)


def hexs(b: bytes) -> str:
    return " ".join(f"{x:02X}" for x in b)


def write_apdus(record: bytes, file_id="00 02") -> list[str]:
    """Full APDU script to unlock + write the record to the washer.

    VALIDATED path: command file 0002, 16-zero write password.
    (The older "AA" password + file 0001 path is a dead end — see docs/STATE.md.)
    """
    nlen = len(record)
    pwd = ("00 " * 16).strip()
    return [
        "00 A4 04 00 07 D2 76 00 00 85 01 01 00",          # SELECT NDEF app
        f"00 A4 00 0C 02 {file_id}",                        # SELECT command file 0002
        f"00 20 00 02 10 {pwd}",                            # VERIFY write pwd (16 zeros)
        "00 D6 00 00 02 00 00",                             # NLEN = 0 (invalidate)
        f"00 D6 00 02 {nlen:02X} {hexs(record)}",          # write record at offset 2
        f"00 D6 00 00 02 {(nlen >> 8) & 0xFF:02X} {nlen & 0xFF:02X}",  # NLEN = real (commit)
    ]


if __name__ == "__main__":
    # self-test: reproduce the captured status-record CRC
    assert crc16(b"3100832519036063243025033") == 0x9C40, "CRC self-test FAILED"
    print("CRC self-test OK (9C40)\n")

    rec = build_start_cycle(selector=6, temp=2, spin=1)
    print("START_PROGRAM_CYCLE record (validated path):")
    print(" ", hexs(rec), "\n")
    print("Write APDU script (paste into the Android probe / Core NFC):")
    for a in write_apdus(rec):
        print("  C", a)
    print("\nThen poll file 0002 (release the RF field between reads) until byte[4] flips 80 -> 00.")
