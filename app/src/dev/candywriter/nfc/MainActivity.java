package dev.candywriter.nfc;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Build;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.util.Log;

/**
 * Candy Cycle Writer — control panel for the CSW 475D washer NFC tag.
 *
 * UI is an HTML panel in assets/panel.html rendered in a WebView; all NFC work stays in
 * Java. This is still an ordinary offline APK — the WebView is only the drawing surface,
 * there is no server and no network access anywhere in this app.
 *
 * PROTOCOL — unchanged from the version proven on this machine. Do not "simplify" it:
 *
 *   write file 0002  ->  pulse the M24SR GPO pin low/high  ->  poll for the ACK
 *
 * Writing the bytes is necessary but not sufficient. The washer's MCU does not poll the
 * tag EEPROM; it waits for an interrupt edge on the GPO pin. Driving GPO is done with the
 * ST-proprietary class byte 0xA2 against system file E101, WITH THE RF FIELD STILL UP.
 * Closing and reopening the IsoDep session to "give the MCU the bus" does NOT work.
 *
 * The 0xA2 command drops the ISO 7816 application context, so every SELECT after it must
 * be preceded by a fresh SELECT of the NDEF app, or it returns 6A82.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final String SEL_APP  = "00 A4 04 00 07 D2 76 00 00 85 01 01 00";
    private static final String SEL_CMD  = "00 A4 00 0C 02 00 02";   // command file 0002
    private static final String SEL_STAT = "00 A4 00 0C 02 00 01";   // status file 0001
    private static final String SEL_SYS  = "00 A4 00 0C 02 E1 01";   // system file E101
    private static final String VERIFY_PROBE = "00 20 00 02 00";
    private static final String VERIFY_PWD   =
            "00 20 00 02 10 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00";

    // Handshake timing. See README section 9 before touching these: the correct pulse
    // semantics depend on the M24SR GPO mode configured in system file E101.
    private static final long GPO_SETTLE_MS = 200;
    private static final long GPO_LOW_MS    = 1200;
    private static final long POLL_MS       = 350;
    private static final int  POLL_TRIES    = 12;

    private NfcAdapter nfc;
    private WebView web;

    private volatile boolean armed = false;    // false = read, true = write
    // START_PROGRAM_CYCLE params: sel, temp, spin, soil, opt1, opt2, opt3, xr, delay, dry
    private volatile int[] params = { 6, 2, 1, 0, 0, 0, 0, 0, 255, 0 };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);   // the panel draws its own masthead
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF14110D);
            getWindow().setNavigationBarColor(0xFF14110D);
        }
        nfc = NfcAdapter.getDefaultAdapter(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(0xFF14110D);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onConsoleMessage(ConsoleMessage m){
                Log.e("PANEL", m.message()+" @"+m.sourceId()+":"+m.lineNumber());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/panel.html");
        setContentView(web);

        if (nfc == null) status("No NFC on this device", "bad");
    }

    /** Called from the HTML panel. */
    private class Bridge {
        @JavascriptInterface
        public void setParams(int sel, int temp, int spin, int soil, int xr) {
            int[] p = { sel, temp, spin, soil, 0, 0, 0, xr, 255, 0 };
            params = p;
        }
        @JavascriptInterface
        public void arm() {
            armed = true;
            status("Ready — hold the phone to the washer", "live");
        }
        @JavascriptInterface
        public void armRead() {
            armed = false;
            status("Ready — hold the phone to the washer", "live");
        }
    }

    // ----- JS calls (always marshalled to the UI thread) -----

    private void js(final String call) {
        if (web == null) return;
        runOnUiThread(new Runnable() {
            public void run() { web.evaluateJavascript(call, null); }
        });
    }
    private void status(String text, String state) {
        js("setStatus('" + esc(text) + "','" + state + "')");
    }
    private void live(String line) { js("addLog('" + esc(line) + "','')"); }
    private void live(String line, String kind) { js("addLog('" + esc(line) + "','" + kind + "')"); }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "");
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
        if (iso == null) { status("Not an ISO-DEP tag", "bad"); return; }
        status("Talking to the washer — keep holding", "busy");
        try {
            iso.connect();
            iso.setTimeout(4000);
            if (armed) doWrite(iso); else doRead(iso);
        } catch (Exception e) {
            live("ERROR " + e + "\n", "er");
            status("Connection lost — try again", "bad");
        } finally {
            try { iso.close(); } catch (Exception ignored) {}
        }
    }

    private void doRead(IsoDep iso) {
        live("\n— READ status —\n", "ok");
        if (!ok(iso, hx(SEL_APP)))  { fail("Couldn't talk to the machine"); return; }
        if (!ok(iso, hx(SEL_STAT))) { fail("Couldn't open the status file"); return; }
        byte[] r = tx(iso, hx("00 B0 00 00 02"));
        if (r == null || r.length < 4) { fail("Couldn't read the length"); return; }
        int nlen = ((r[0] & 0xFF) << 8) | (r[1] & 0xFF);
        if (nlen == 0) { status("Status file is empty", "bad"); return; }
        int off = 2, rem = nlen;
        StringBuilder hex = new StringBuilder();
        while (rem > 0) {
            int c = Math.min(rem, 0xFA);
            byte[] d = tx(iso, new byte[]{0x00, (byte)0xB0,
                    (byte)((off >> 8) & 0xFF), (byte)(off & 0xFF), (byte)c});
            if (d == null) { fail("Read failed"); return; }
            for (int i = 0; i < d.length - 2; i++) hex.append(String.format("%02X ", d[i]));
            off += c; rem -= c;
        }
        live("STATUS " + hex.toString().trim() + "\n");
        status("Read OK — " + nlen + " bytes", "ok");
    }

    private void doWrite(IsoDep iso) {
        byte[] rec = buildStartCycle(params);
        live("\n— SEND cycle —\n", "ok");
        live("cmd " + toHex(rec) + "\n");

        if (!ok(iso, hx(SEL_APP))) { fail("Couldn't talk to the machine"); return; }
        if (!ok(iso, hx(SEL_CMD))) { fail("Couldn't open the command file"); return; }

        // Empty VERIFY is a lock-state probe (63 00 = locked) and does NOT burn the retry
        // counter. Only present the password if the probe says we're locked.
        if (!ok(iso, hx(VERIFY_PROBE)) && !ok(iso, hx(VERIFY_PWD))) {
            fail("Locked — send one cycle from the Candy app, then retry");
            return;
        }

        ok(iso, hx("00 D6 00 00 02 00 00"));                        // NLEN = 0 (invalidate)
        byte[] w = new byte[5 + rec.length];
        System.arraycopy(new byte[]{0x00, (byte)0xD6, 0x00, 0x02, (byte)rec.length}, 0, w, 0, 5);
        System.arraycopy(rec, 0, w, 5, rec.length);
        if (!ok(iso, w)) { fail("Write refused — try different settings"); return; }
        ok(iso, new byte[]{0x00, (byte)0xD6, 0x00, 0x00, 0x02,
                (byte)((rec.length >> 8) & 0xFF), (byte)(rec.length & 0xFF)});   // NLEN = real

        // --- the GPO handshake: this is what makes the machine act ---
        status("Waiting for the washer — keep holding", "busy");
        sleep(GPO_SETTLE_MS);
        live("GPO low  " + (gpo(iso, 0) ? "ok" : "FAIL") + "\n");
        sleep(GPO_LOW_MS);
        live("GPO high " + (gpo(iso, 1) ? "ok" : "FAIL") + "\n");

        boolean acked = false;
        for (int i = 0; i < POLL_TRIES && !acked; i++) {
            byte[] cf = readCmdFile(iso);
            if (cf != null && cf.length > 6 && (cf[4] & 0xFF) == 0x00) {
                acked = true;
                live("ACK " + toHex(cf) + "\n", "ok");
                status("Accepted — the cycle is starting", "ok");
            } else {
                sleep(POLL_MS);
            }
        }
        if (!acked) status("No response — hold steady and try again", "bad");
        armed = false;
    }

    /**
     * Drive the M24SR GPO pin. level 0 = low, 1 = high; the low->high edge is the
     * interrupt the washer's MCU is waiting for. Class byte 0xA2 is ST-proprietary and
     * drops the ISO 7816 application context, hence the re-SELECT every time.
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
            if (r != null && r.length >= 2) {
                live("> " + toHex(cmd) + "\n< "
                     + String.format("%02X%02X", r[r.length-2], r[r.length-1]) + "\n");
            }
            return r;
        } catch (Exception e) { return null; }
    }

    private boolean ok(IsoDep iso, byte[] cmd) {
        byte[] r = tx(iso, cmd);
        return r != null && r.length >= 2
                && (r[r.length-2] & 0xFF) == 0x90 && (r[r.length-1] & 0xFF) == 0x00;
    }

    private void fail(String s) { live(s + "\n", "er"); status(s, "bad"); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception ignored) {} }

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
}
