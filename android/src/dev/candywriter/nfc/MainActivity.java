package dev.candywriter.nfc;

import android.app.Activity;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Candy Cycle Writer — hands-on tool for the CSW 475D washer NFC tag.
 *
 * READ  = dump the status file 0001 (safe, proves comms).
 * WRITE = unlock file 0002 with the 16-zero write password, write a START_PROGRAM_CYCLE
 *         command, then pulse the M24SR GPO pin so the washer's MCU actually reads it.
 *
 * THE GPO PULSE IS THE WHOLE TRICK. Writing the bytes is necessary but not sufficient:
 * the MCU does not poll the EEPROM, it waits for an interrupt edge on the tag's GPO pin.
 * After committing the record we drive GPO low, wait, drive it high, and then poll file
 * 0002 until the washer flips byte[4] from 0x80 to 0x00 — that flip is the ACK.
 *
 * Keep the RF field UP the whole time. Closing and reopening the IsoDep session to "give
 * the MCU the bus" does NOT work — that was an early hypothesis and it never ACKs.
 *
 * The GPO write uses the ST-proprietary class byte 0xA2 against system file E101. That
 * command drops the ISO 7816 application context, so every following SELECT must be
 * preceded by a fresh SELECT of the NDEF app or it returns 6A82.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final int ACCENT = Color.parseColor("#1565C0");
    private static final int MUTED  = Color.parseColor("#666666");
    private static final int WARN   = Color.parseColor("#FFB300");

    private static final String SEL_APP  = "00 A4 04 00 07 D2 76 00 00 85 01 01 00";
    private static final String SEL_CMD  = "00 A4 00 0C 02 00 02";   // command file 0002
    private static final String SEL_STAT = "00 A4 00 0C 02 00 01";   // status file 0001
    private static final String SEL_SYS  = "00 A4 00 0C 02 E1 01";   // system file E101
    private static final String VERIFY_PROBE = "00 20 00 02 00";
    private static final String VERIFY_PWD   =
            "00 20 00 02 10 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00";

    // Handshake timing. See README section 9 before changing these: the required pulse
    // semantics depend on the M24SR GPO mode configured in system file E101.
    private static final long GPO_SETTLE_MS = 200;
    private static final long GPO_LOW_MS    = 1200;
    private static final long POLL_MS       = 350;
    private static final int  POLL_TRIES    = 12;

    private NfcAdapter nfc;
    private TextView log, status;
    private volatile int pendingOp = 0;            // 0 = read, 1 = write
    private volatile int[] params = new int[10];

    // START_PROGRAM_CYCLE (action 0x0B) field map.
    // temp: 0=cold 1=20 2=30 3=40 4=60   spin: 0=none 1=400 2=800 3=1000/1200 4=max
    // xr is an extra-rinse COUNT (0-3), not a flag.
    private final String[] LABELS = {
        "selector", "temp 0-4", "spin 0-4", "soil 0-3", "opt1",
        "opt2", "opt3", "xr 0-3", "delay 255=off", "dry"
    };
    // Defaults reproduce the captured known-good command (selector 6, 30C, 400 spin).
    private final int[] DEFAULTS = { 6, 2, 1, 0, 0, 0, 0, 0, 255, 0 };
    private final Map<String, EditText> fields = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        nfc = NfcAdapter.getDefaultAdapter(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(title("Candy Cycle Writer"));

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setPadding(0, 0, 0, dp(8));
        status.setText("Ready.");
        root.addView(status);

        for (int i = 0; i < LABELS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(this);
            tv.setText(LABELS[i]);
            tv.setWidth(dp(120));
            EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(DEFAULTS[i]));
            et.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            fields.put(LABELS[i], et);
            row.addView(tv);
            row.addView(et);
            root.addView(row);
        }

        Button readBtn = new Button(this);
        readBtn.setText("READ next tap (safe dump)");
        readBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pendingOp = 0;
                toast("Hold the phone to the washer to READ");
            }
        });
        root.addView(readBtn);

        Button writeBtn = new Button(this);
        writeBtn.setText("WRITE cycle next tap");
        writeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                snapshotParams();
                pendingOp = 1;
                toast("Hold the phone to the washer to WRITE");
            }
        });
        root.addView(writeBtn);

        Button clearBtn = new Button(this);
        clearBtn.setText("Clear log");
        clearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { log.setText(""); }
        });
        root.addView(clearBtn);

        log = new TextView(this);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(0, dp(10), 0, 0);
        root.addView(log);

        setContentView(scroll);
        if (nfc == null) live("No NFC on this device.\n");
        else live("Set the fields, tap READ or WRITE, then hold to the washer.\n");
    }

    private void snapshotParams() {
        int[] p = new int[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) {
            try { p[i] = Integer.parseInt(fields.get(LABELS[i]).getText().toString().trim()); }
            catch (Exception e) { p[i] = 0; }
        }
        params = p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfc != null) {
            nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableReaderMode(this);
    }

    // ----- NFC -----

    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) { live("Tag is not IsoDep.\n"); return; }
        live("\n● tag detected — working, KEEP THE PHONE ON THE PAD...\n");
        try {
            iso.connect();
            iso.setTimeout(4000);
            if (pendingOp == 1) doWrite(iso);
            else doRead(iso);
        } catch (Exception e) {
            live("ERROR: " + e + "\n");
            setStatus("Error: " + e, WARN);
        } finally {
            try { iso.close(); } catch (Exception ignored) {}
        }
    }

    private void doRead(IsoDep iso) throws Exception {
        live("=== READ status file 0001 ===\n");
        if (!ok(iso, hx(SEL_APP)))  { fail("Couldn't talk to the machine."); return; }
        if (!ok(iso, hx(SEL_STAT))) { fail("Couldn't open the status file."); return; }
        byte[] r = tx(iso, hx("00 B0 00 00 02"));
        if (r == null || r.length < 4) { fail("Couldn't read the length."); return; }
        int nlen = ((r[0] & 0xFF) << 8) | (r[1] & 0xFF);
        live("NDEF length = " + nlen + "\n");
        if (nlen == 0) { live("(empty)\n"); return; }
        int off = 2, rem = nlen;
        StringBuilder hex = new StringBuilder();
        while (rem > 0) {
            int c = Math.min(rem, 0xFA);
            byte[] d = tx(iso, new byte[]{0x00, (byte)0xB0,
                    (byte)((off >> 8) & 0xFF), (byte)(off & 0xFF), (byte)c});
            if (d == null) { fail("Read failed."); return; }
            for (int i = 0; i < d.length - 2; i++) hex.append(String.format("%02X ", d[i]));
            off += c; rem -= c;
        }
        setStatus("Read OK ✓ (" + nlen + " bytes)", ACCENT);
        live("STATUS: " + hex.toString().trim() + "\n");
    }

    private void doWrite(IsoDep iso) throws Exception {
        byte[] rec = buildStartCycle(params);
        setStatus("Sending cycle… keep the phone on the pad", MUTED);
        live("cmd: " + toHex(rec) + "\n");

        if (!ok(iso, hx(SEL_APP))) { fail("Couldn't talk to the machine."); return; }
        if (!ok(iso, hx(SEL_CMD))) { fail("Couldn't open the command file."); return; }

        // The empty VERIFY is a lock-state probe: 63 00 means "password required" and does
        // NOT burn the retry counter. Only present the password if the probe says we're locked.
        if (!ok(iso, hx(VERIFY_PROBE)) && !ok(iso, hx(VERIFY_PWD))) {
            fail("Locked. Send any cycle from the official Candy app once, then retry.");
            return;
        }

        ok(iso, hx("00 D6 00 00 02 00 00"));                       // NLEN = 0 (invalidate)
        byte[] w = new byte[5 + rec.length];
        System.arraycopy(new byte[]{0x00, (byte)0xD6, 0x00, 0x02, (byte)rec.length}, 0, w, 0, 5);
        System.arraycopy(rec, 0, w, 5, rec.length);
        if (!ok(iso, w)) { fail("Write was refused — try different settings."); return; }
        ok(iso, new byte[]{0x00, (byte)0xD6, 0x00, 0x00, 0x02,
                (byte)((rec.length >> 8) & 0xFF), (byte)(rec.length & 0xFF)});  // NLEN = real

        // --- the GPO handshake: this is what makes the machine act ---
        setStatus("Waiting for the washer to accept… keep the phone on the pad", MUTED);
        sleep(GPO_SETTLE_MS);
        live("GPO low: "  + (gpo(iso, 0) ? "ok" : "fail") + "\n");
        sleep(GPO_LOW_MS);
        live("GPO high: " + (gpo(iso, 1) ? "ok" : "fail") + "\n");

        boolean acked = false;
        for (int i = 0; i < POLL_TRIES && !acked; i++) {
            byte[] cf = readCmdFile(iso);
            if (cf != null && cf.length > 6 && (cf[4] & 0xFF) == 0x00) {
                acked = true;
                setStatus("✅ Accepted! The cycle is starting.", ACCENT);
                live("ACK: " + toHex(cf) + "\n");
            } else {
                if (i % 3 == 0) {
                    live("…waiting (byte4=" +
                         ((cf == null || cf.length <= 4) ? "?" : String.format("%02X", cf[4]))
                         + ")\n");
                }
                sleep(POLL_MS);
            }
        }
        if (!acked) setStatus("⚠️ No response yet. Hold steady on the pad and try again.", WARN);
        pendingOp = 0;
        live(">> You can remove the phone now.\n");
    }

    /**
     * Drive the M24SR GPO pin. level 0 = low, 1 = high; the low->high edge is the
     * interrupt the washer's MCU is waiting for.
     *
     * Class byte 0xA2 is ST-proprietary and drops the ISO 7816 application context, so we
     * re-SELECT the NDEF app first every single time.
     */
    private boolean gpo(IsoDep iso, int level) {
        tx(iso, hx(SEL_APP));
        tx(iso, hx(SEL_SYS));
        byte[] r = tx(iso, new byte[]{(byte)0xA2, (byte)0xD6, 0x00, 0x1F, 0x01, (byte)level});
        return r != null && r.length >= 2 && (r[r.length - 2] & 0xFF) == 0x90;
    }

    /** Read back command file 0002 so we can watch byte[4] flip 0x80 -> 0x00. */
    private byte[] readCmdFile(IsoDep iso) {
        tx(iso, hx(SEL_APP));
        byte[] s = tx(iso, hx(SEL_CMD));
        if (s == null || s.length < 2 || (s[s.length - 2] & 0xFF) != 0x90) return null;
        byte[] n = tx(iso, hx("00 B0 00 00 02"));
        if (n == null || n.length < 4) return null;
        int nlen = ((n[0] & 0xFF) << 8) | (n[1] & 0xFF);
        if (nlen < 6 || nlen > 0x80) return null;
        return tx(iso, new byte[]{0x00, (byte)0xB0, 0x00, 0x02, (byte)nlen});
    }

    // ----- command + CRC -----

    static int crc16(byte[] data) {
        int i = 0xFFFF;
        for (byte b : data) {
            i ^= (b & 0xFF);
            for (int k = 0; k < 8; k++) i = ((i & 1) != 0) ? (i >>> 1) ^ 0x6363 : (i >>> 1);
        }
        return (~i) & 0xFFFF;
    }

    /** START_PROGRAM_CYCLE (action 0x0B), 18-byte NDEF external record for file 0002. */
    static byte[] buildStartCycle(int[] p) {
        byte[] buf = new byte[18];
        buf[0] = (byte)0xD4;   // NDEF external record header (MB+ME+SR, TNF=4)
        buf[1] = 0x01;         // type length
        buf[2] = 0x0E;         // payload length = 14
        buf[3] = 0x02;         // type name 0x02 (command)
        buf[4] = (byte)0x80;   // command marker — washer flips this to 0x00 as its ACK
        buf[5] = 0x0B;         // action = START_PROGRAM_CYCLE
        for (int i = 0; i < 10; i++) buf[6 + i] = (byte)p[i];
        byte[] crcData = new byte[12];
        System.arraycopy(buf, 4, crcData, 0, 12);   // CRC over [4..15]
        int crc = crc16(crcData);
        buf[16] = (byte)((crc >> 8) & 0xFF);        // big-endian
        buf[17] = (byte)(crc & 0xFF);
        return buf;
    }

    // ----- helpers -----

    private byte[] tx(IsoDep iso, byte[] cmd) {
        try {
            byte[] r = iso.transceive(cmd);
            live("C " + toHex(cmd) + "\n");
            if (r != null && r.length >= 2) {
                live("R " + String.format("%02X%02X", r[r.length-2], r[r.length-1])
                     + (r.length > 2 ? " data=" + toHex(slice(r, 0, r.length-2)) : "") + "\n");
            }
            return r;
        } catch (Exception e) { return null; }
    }

    private boolean ok(IsoDep iso, byte[] cmd) {
        byte[] r = tx(iso, cmd);
        return r != null && r.length >= 2
                && (r[r.length-2] & 0xFF) == 0x90 && (r[r.length-1] & 0xFF) == 0x00;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    static byte[] slice(byte[] a, int from, int to) {
        byte[] o = new byte[to - from]; System.arraycopy(a, from, o, 0, to - from); return o;
    }
    static byte[] hx(String s) {
        String[] t = s.trim().split("\\s+");
        byte[] o = new byte[t.length];
        for (int i = 0; i < t.length; i++) o[i] = (byte) Integer.parseInt(t[i], 16);
        return o;
    }
    static String toHex(byte[] a) {
        StringBuilder s = new StringBuilder();
        for (byte b : a) s.append(String.format("%02X ", b));
        return s.toString().trim();
    }

    private void fail(String s) { live("✗ " + s + "\n"); setStatus(s, WARN); }
    private void setStatus(final String s, final int color) {
        runOnUiThread(new Runnable() {
            public void run() { status.setText(s); status.setTextColor(color); }
        });
    }
    private void live(final String s) {
        runOnUiThread(new Runnable() { public void run() { log.append(s); } });
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private TextView title(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(20);
        t.setTextColor(ACCENT); t.setPadding(0, 0, 0, dp(4)); return t;
    }
}
