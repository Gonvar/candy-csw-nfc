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
 * Candy Cycle Writer — dumb, hands-on tool for the CSW 475D washer NFC tag.
 *
 * READ  = dump the NDEF file (safe, proves comms).
 * WRITE = unlock with the discovered password (AA + 15x00) and write a
 *         STORE_PROGRAM_CYCLE command built from the fields below.
 *
 * Field encodings (from the decompiled app): temp = literal C, soil = 1..3,
 * spin = rpm/100 (guess), chart/sub-cycle select the program (experiment).
 * The washer validates parameters and rejects bad combos, so trying is safe.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private NfcAdapter nfc;
    private TextView log;
    private volatile int pendingOp = 0;          // 0 = read, 1 = write
    private volatile int[] params = new int[11]; // snapshot taken on button press

    // START_PROGRAM_CYCLE (action 0x0b) field map, confirmed by live capture.
    // temp index: 0=cold 1=20 2=30 3=40 4=60 ; spin index: 0=none 1=400 2=800 3=1200 4=max
    private final String[] LABELS = {
        "selector", "temp 0-4", "spin 0-4", "soil", "opt1",
        "opt2", "opt3", "xr", "delay 255=off", "dry"
    };
    // Defaults reproduce the captured working command (selector6 temp30 spin400): a guaranteed-good replay.
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

        for (int i = 0; i < LABELS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(this);
            tv.setText(LABELS[i]);
            tv.setWidth(dp(110));
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
        if (nfc == null) append("No NFC on this device.");
        else append("Ready. Set fields, tap READ or WRITE, then hold to the washer.\n");
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
        live("\n● tag detected — working, KEEP PHONE ON...\n");
        try {
            iso.connect();
            iso.setTimeout(4000);
            if (pendingOp == 1) doWrite(iso);
            else doRead(iso);
        } catch (Exception e) {
            live("ERROR: " + e + "\n");
        } finally {
            try { iso.close(); } catch (Exception ignored) {}
        }
    }

    private void doRead(IsoDep iso) throws Exception {
        live("=== READ ===\n");
        if (!ok(iso, "SELECT app", hx("00 A4 04 00 07 D2 76 00 00 85 01 01 00"))) return;
        if (!ok(iso, "SELECT NDEF file", hx("00 A4 00 0C 02 00 01"))) return;
        byte[] r = send(iso, "READ NLEN", hx("00 B0 00 00 02"));
        if (r == null || r.length < 4) return;
        int nlen = ((r[0] & 0xFF) << 8) | (r[1] & 0xFF);
        live("NDEF length = " + nlen + "\n");
        if (nlen == 0) { live("(empty)\n"); return; }
        int off = 2, rem = nlen;
        StringBuilder hex = new StringBuilder();
        while (rem > 0) {
            int c = Math.min(rem, 0xFA);
            byte[] cmd = { 0x00, (byte)0xB0, (byte)((off >> 8) & 0xFF), (byte)(off & 0xFF), (byte)c };
            byte[] d = send(iso, "READ @" + off, cmd);
            if (d == null) return;
            for (int i = 0; i < d.length - 2; i++) hex.append(String.format("%02X ", d[i]));
            off += c; rem -= c;
        }
        live("PAYLOAD: " + hex.toString().trim() + "\n");
    }

    private void doWrite(IsoDep iso) throws Exception {
        live("=== WRITE ===\n");
        byte[] rec = buildStartCycle(params);
        live("record: " + toHex(rec) + "\n");
        if (!ok(iso, "SELECT app", hx("00 A4 04 00 07 D2 76 00 00 85 01 01 00"))) return;
        if (!ok(iso, "SELECT cmd file 0002", hx("00 A4 00 0C 02 00 02"))) return;
        boolean unlocked = ok(iso, "VERIFY probe (no pwd)", hx("00 20 00 02 00"));
        if (!unlocked) {
            if (!ok(iso, "VERIFY write pwd (zeros)", hx("00 20 00 02 10 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"))) {
                live("Unlock failed (63Cx=wrong pwd). Send a cycle from the official app to reset, then retry.\n");
                return;
            }
        }
        ok(iso, "NLEN=0", hx("00 D6 00 00 02 00 00"));
        byte[] w = new byte[5 + rec.length];
        byte[] head = { 0x00, (byte)0xD6, 0x00, 0x02, (byte)rec.length };
        System.arraycopy(head, 0, w, 0, 5);
        System.arraycopy(rec, 0, w, 5, rec.length);
        if (!ok(iso, "UPDATE record", w)) return;
        byte[] setlen = { 0x00, (byte)0xD6, 0x00, 0x00, 0x02,
                (byte)((rec.length >> 8) & 0xFF), (byte)(rec.length & 0xFF) };
        ok(iso, "NLEN=real", setlen);
        // Washer ACKs by rewriting file 0002 byte[4] 80->00. It can only read the chip when WE release
        // the RF session (M24SR RF/I2C arbitration). So close+reopen between polls, like the official app.
        live("written — releasing session & polling for ACK (KEEP PHONE ON)...\n");
        boolean acked = false;
        int waitedMs = 0;
        for (int i = 0; i < 25 && !acked; i++) {
            try { iso.close(); } catch (Exception ignored) {}   // release -> washer controller gets a window
            try { Thread.sleep(250); } catch (Exception ignored) {}
            waitedMs += 250;
            try { iso.connect(); iso.setTimeout(4000); } catch (Exception e) { continue; }
            tx(iso, hx("00 A4 04 00 07 D2 76 00 00 85 01 01 00")); // fresh session
            tx(iso, hx("00 A4 00 0C 02 00 02"));
            byte[] nl = tx(iso, hx("00 B0 00 00 02"));
            if (nl == null || nl.length < 4) continue;
            int n = ((nl[0] & 0xFF) << 8) | (nl[1] & 0xFF);
            if (n < 6 || n > 0x80) continue;
            byte[] ct = tx(iso, new byte[]{0x00, (byte)0xB0, 0x00, 0x02, (byte)n});
            if (ct != null && ct.length > 6 && (ct[4] & 0xFF) == 0x00) {
                acked = true;
                live("✅ WASHER ACK after ~" + waitedMs + "ms — accepted! cycle should start.\n");
            } else if (i % 3 == 0) {
                live("  ...waiting " + waitedMs + "ms (byte4=" + (ct != null && ct.length > 6 ? String.format("%02X", ct[4]) : "?") + ")\n");
            }
        }
        if (!acked) live("⚠️ no ACK after " + waitedMs + "ms — ensure washer is in NFC mode; hold steady.\n");
        pendingOp = 0;
        live(">> You can remove the phone now.\n");
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

    // START_PROGRAM_CYCLE (action 0x0b), 18-byte NDEF external record written to file 0002.
    static byte[] buildStartCycle(int[] p) {
        byte[] buf = new byte[18];
        buf[0] = (byte)0xD4; buf[1] = 0x01; buf[2] = 0x0E; buf[3] = 0x02;
        buf[4] = (byte)0x80; buf[5] = 0x0B;
        buf[6]  = (byte)p[0]; // selector (program)
        buf[7]  = (byte)p[1]; // temperature index
        buf[8]  = (byte)p[2]; // spin index
        buf[9]  = (byte)p[3]; // soil
        buf[10] = (byte)p[4]; // opt1
        buf[11] = (byte)p[5]; // opt2
        buf[12] = (byte)p[6]; // opt3
        buf[13] = (byte)p[7]; // xr
        buf[14] = (byte)p[8]; // delay (255 = off)
        buf[15] = (byte)p[9]; // dry
        byte[] crcData = new byte[12];
        System.arraycopy(buf, 4, crcData, 0, 12);
        int crc = crc16(crcData);
        buf[16] = (byte)((crc >> 8) & 0xFF);
        buf[17] = (byte)(crc & 0xFF);
        return buf;
    }

    // ----- helpers -----

    // Quiet transceive for the ACK poll loop (no logging).
    private byte[] tx(IsoDep iso, byte[] cmd) {
        try { return iso.transceive(cmd); } catch (Exception e) { return null; }
    }

    private byte[] send(IsoDep iso, String label, byte[] cmd) throws Exception {
        byte[] r = iso.transceive(cmd);
        String sw = r.length >= 2 ? String.format("%02X%02X", r[r.length-2], r[r.length-1]) : "??";
        live("C " + label + ": " + toHex(cmd) + "\n");
        live("R " + sw + (r.length > 2 ? " data=" + toHex(slice(r, 0, r.length-2)) : "") + "\n");
        return r;
    }

    private boolean ok(IsoDep iso, String label, byte[] cmd) throws Exception {
        byte[] r = send(iso, label, cmd);
        return r != null && r.length >= 2 && (r[r.length-2] & 0xFF) == 0x90 && (r[r.length-1] & 0xFF) == 0x00;
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

    private void append(String s) { log.append(s + "\n"); }
    // Live log from the NFC (background) thread — posts to the UI immediately so progress is visible.
    private void live(final String s) { runOnUiThread(new Runnable() { public void run() { log.append(s); } }); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private TextView title(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(20);
        t.setTextColor(Color.parseColor("#1565C0")); t.setPadding(0, 0, 0, dp(8)); return t;
    }
}
