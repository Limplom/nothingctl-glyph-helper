package com.nothingctl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Controls Nothing Glyph LEDs.
 *
 * Strategy (tried in order):
 *   1. ILightsExtension — vendor Binder extension on the ILights HAL service.
 *      No Android Context needed; only requires ServiceManager access (root).
 *      The extension exposes setLightExtensionState(id, state, brightness) and
 *      setLightFrame(state, flag, colors[], extra) for Glyph LED control.
 *
 *   2. GlyphService Binder (fallback) — direct Binder transactions to the bound
 *      GlyphService. Requires a Context from ActivityThread + a running Looper.
 *      Transaction codes from rec0de/glyph-api:
 *        1 = setFrameColors(int[] colors)   2 = openSession()
 *        3 = closeSession()                  4 = register(String apiKey)
 *      AIDL descriptor: "com.nothing.thirdparty.IGlyphService"
 */
public class ZoneController {

    // -----------------------------------------------------------------------
    // ILightsExtension (HAL vendor extension)
    // -----------------------------------------------------------------------

    private static final String LIGHTS_HAL_SERVICE = "android.hardware.light.ILights/default";
    private static final String LIGHTS_EXT_DESCRIPTOR = "vendor.hardware.light.ILightsExtension";

    // AIDL transaction codes (alphabetical declaration order, FIRST_CALL_TRANSACTION = 1):
    //  1 = getLights           2 = getTempCsvBuildID    3 = registerStateListener
    //  4 = setLightBatteryState  5 = setLightExclamationState
    //  6 = setLightExtensionState  7 = setLightFrame
    //  8 = setLightMusicState  9 = setLightRearCameraState
    // 10 = setLightSettingState  11 = unregisterStateListener
    private static final int TX_EXT_GET_LIGHTS        = 1;
    private static final int TX_EXT_SET_EXT_STATE     = 6;
    private static final int TX_EXT_SET_LIGHT_FRAME   = 7;

    // Known Glyph light IDs from dumpsys lights (custom Nothing IDs ≥ 100).
    private static final int[] GLYPH_LIGHT_IDS = {
        102, 103, 106, 108, 109, 113, 115, 117, 118
    };

    private IBinder lightsExtBinder = null;  // non-null once extension init succeeds

    // -----------------------------------------------------------------------
    // Sysfs (legacy fallback)
    // -----------------------------------------------------------------------

    // Candidate LED brightness files, tried in order.
    private static final String[] SYSFS_CANDIDATES = {
        "/sys/class/leds/noth_leds/brightness",
    };

    // Standard Linux LED class uses 0–255.  Our input range is 0–4095.
    private static final int SYSFS_MAX = 255;
    private static final int API_MAX   = 4095;

    private String sysfsPath = null;  // non-null once sysfs init succeeds

    // -----------------------------------------------------------------------
    // Binder / GlyphService
    // -----------------------------------------------------------------------

    private static final int TRANSACTION_SET_FRAME_COLORS = 1;
    private static final int TRANSACTION_OPEN_SESSION     = 2;
    private static final int TRANSACTION_CLOSE_SESSION    = 3;
    private static final int TRANSACTION_REGISTER         = 4;

    private static final String GLYPH_SERVICE_PKG  = "com.nothing.thirdparty";
    private static final String GLYPH_SERVICE_CLS  = "com.nothing.thirdparty.GlyphService";
    private static final String GLYPH_SERVICE_NAME = "com.nothing.thirdparty.IGlyphService";
    private static final String GLYPH_BIND_ACTION  = "com.nothing.thirdparty.bind_glyphservice";
    private static final String API_KEY            = "test";

    private final int zoneCount;
    private IBinder binder;
    private ServiceConnection connection;
    private boolean sessionOpen = false;

    public ZoneController(int zoneCount) {
        this.zoneCount = zoneCount;
    }

    /** Returns the active sysfs path, or null if sysfs is not in use. */
    public String getSysfsPath() { return sysfsPath; }

    // -----------------------------------------------------------------------
    // Public init API
    // -----------------------------------------------------------------------

    /**
     * Try the ILightsExtension vendor HAL extension.
     * Gets the ILights/default binder, calls getExtension() to obtain the
     * vendor.hardware.light.ILightsExtension binder, then verifies it
     * responds to getLights().
     * No Android Context or Looper required — just ServiceManager access.
     */
    public boolean initLightsExtension() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder lightsBinder = (IBinder) getService.invoke(null, LIGHTS_HAL_SERVICE);
            if (lightsBinder == null) {
                System.err.println("[DEBUG] ILights/default not in ServiceManager");
                return false;
            }
            System.err.println("[DEBUG] Got ILights binder: " + lightsBinder.getClass().getName());

            // IBinder.getExtension() — @hide API, available since API 30 (Android 11).
            // Must use reflection since it's not in the public SDK.
            Method getExtension = IBinder.class.getMethod("getExtension");
            IBinder ext = (IBinder) getExtension.invoke(lightsBinder);
            if (ext == null) {
                System.err.println("[DEBUG] ILights/default has no vendor extension");
                return false;
            }
            // Verify the interface descriptor matches.
            String iface = ext.getInterfaceDescriptor();
            if (!LIGHTS_EXT_DESCRIPTOR.equals(iface)) {
                System.err.println("[DEBUG] Extension descriptor mismatch: " + iface);
                return false;
            }
            System.err.println("[DEBUG] ILightsExtension binder acquired");

            lightsExtBinder = ext;
            return true;
        } catch (Exception e) {
            System.err.println("[DEBUG] ILightsExtension init failed: " + e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null) {
                System.err.println("[DEBUG]   cause: " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
            }
            return false;
        }
    }

    /**
     * Try sysfs LED control. Returns true if a writable LED file was found.
     * No Android Context or Looper required.
     */
    public boolean initSysfs() {
        for (String path : SYSFS_CANDIDATES) {
            if (testSysfsWrite(path)) {
                sysfsPath = path;
                return true;
            }
        }
        return false;
    }

    /**
     * Fall back to GlyphService Binder. Requires ctx != null and the main
     * Looper running on the main thread so bindService() callbacks fire.
     */
    public void initBinder(Context ctx) throws Exception {
        binder = tryServiceManager();
        if (binder == null) {
            if (ctx == null) {
                throw new Exception(
                    "GlyphService not in ServiceManager and no Context for bindService()");
            }
            binder = bindBlocking(ctx);
        }
        transactString(TRANSACTION_REGISTER, API_KEY);
        transactVoid(TRANSACTION_OPEN_SESSION);
        sessionOpen = true;
    }

    // -----------------------------------------------------------------------
    // Zone control
    // -----------------------------------------------------------------------

    /**
     * Diagnostic mode: run every probe variant individually with a 2 s pause
     * between each, so the user can visually identify which variant physically
     * lights the LEDs. Requires initLightsExtension() to have succeeded.
     */
    public void probeVariants(int brightness) throws Exception {
        if (lightsExtBinder == null) {
            throw new Exception("ILightsExtension not initialised — cannot probe");
        }

        int scaled = brightness > 0 ? Math.max(1, brightness * 255 / API_MAX) : 0;
        int argbInt = (0xFF << 24) | (scaled << 16) | (scaled << 8) | scaled;
        long argbLong = (0xFFL << 24) | ((long)scaled << 16) | ((long)scaled << 8) | scaled;

        int[] colors = new int[GLYPH_LIGHT_IDS.length];
        for (int i = 0; i < colors.length; i++) colors[i] = argbInt;

        // --- setLightFrame variants ---
        System.err.println("\n>>> PROBE 1/7: setLightFrame A (state=1, flag=true, 9 colors, extra=0)");
        attemptSetLightFrame(1, true, colors, 0, "probe-1");
        Thread.sleep(2000);

        System.err.println("\n>>> PROBE 2/7: setLightFrame B (state=br, flag=false, extra=br)");
        attemptSetLightFrame(brightness, false, colors, brightness, "probe-2");
        Thread.sleep(2000);

        System.err.println("\n>>> PROBE 3/7: setLightFrame C (single color)");
        attemptSetLightFrame(1, true, new int[]{argbInt}, 0, "probe-3");
        Thread.sleep(2000);

        // --- setLightExtensionState variants (for every Glyph ID) ---
        System.err.println("\n>>> PROBE 4/7: setExtState V1 (state=1, br=0-255) for all 9 zones");
        for (int id : GLYPH_LIGHT_IDS) attemptSetExtState(id, 1L, scaled, "probe-4 id=" + id);
        Thread.sleep(2000);

        System.err.println("\n>>> PROBE 5/7: setExtState V2 (state=br 0-4095, br=0-4095) for all 9 zones");
        for (int id : GLYPH_LIGHT_IDS) attemptSetExtState(id, (long)brightness, brightness, "probe-5 id=" + id);
        Thread.sleep(2000);

        System.err.println("\n>>> PROBE 6/7: setExtState V3 (state=ARGB, br=0-255) for all 9 zones");
        for (int id : GLYPH_LIGHT_IDS) attemptSetExtState(id, argbLong, scaled, "probe-6 id=" + id);
        Thread.sleep(2000);

        System.err.println("\n>>> PROBE 7/7: setExtState V4 (state=0, br=0-4095) for all 9 zones");
        for (int id : GLYPH_LIGHT_IDS) attemptSetExtState(id, 0L, brightness, "probe-7 id=" + id);
        Thread.sleep(2000);

        System.err.println("\n>>> PROBE complete — turning off");
        // Explicit off via setLightFrame with zeros.
        int[] off = new int[GLYPH_LIGHT_IDS.length];
        attemptSetLightFrame(0, false, off, 0, "probe-off-frame");
        for (int id : GLYPH_LIGHT_IDS) attemptSetExtState(id, 0L, 0, "probe-off id=" + id);
    }

    /** Set all zones to the given brightness (0–4095). */
    public void allOn(int brightness) throws Exception {
        if (lightsExtBinder != null) {
            setAllLightsExt(brightness);
            return;
        }
        if (sysfsPath != null) {
            writeSysfs(scale(brightness));
            return;
        }
        int[] colors = new int[zoneCount];
        for (int i = 0; i < zoneCount; i++) colors[i] = brightness;
        transactIntArray(TRANSACTION_SET_FRAME_COLORS, colors);
    }

    /** Turn all zones off. */
    public void allOff() throws Exception {
        if (lightsExtBinder != null) {
            setAllLightsExt(0);
            return;
        }
        if (sysfsPath != null) {
            writeSysfs(0);
            return;
        }
        transactIntArray(TRANSACTION_SET_FRAME_COLORS, new int[zoneCount]);
    }

    /**
     * Run one pulse cycle: brightness ramps up then back down following a
     * sine curve, with 150 ms per step.
     */
    public void pulse(int maxBrightness, int steps) throws Exception {
        int[] curve = buildSineCurve(maxBrightness, steps);
        for (int b : curve) {
            if (lightsExtBinder != null) {
                setAllLightsExt(b);
            } else if (sysfsPath != null) {
                writeSysfs(scale(b));
            } else {
                int[] colors = new int[zoneCount];
                for (int i = 0; i < zoneCount; i++) colors[i] = b;
                transactIntArray(TRANSACTION_SET_FRAME_COLORS, colors);
            }
            Thread.sleep(150);
        }
        allOff();
    }

    /** Close the session, release the Binder, and unbind the service. */
    public void close(Context ctx) {
        if (binder != null && sessionOpen) {
            try { transactVoid(TRANSACTION_CLOSE_SESSION); } catch (Exception ignored) {}
            sessionOpen = false;
        }
        if (ctx != null && connection != null) {
            try { ctx.unbindService(connection); } catch (Exception ignored) {}
            connection = null;
        }
        binder = null;
        lightsExtBinder = null;
        sysfsPath = null;
    }

    // -----------------------------------------------------------------------
    // ILightsExtension helpers
    // -----------------------------------------------------------------------

    /**
     * Set all Glyph LEDs to the given brightness. Probes the vendor extension
     * in priority order (setLightFrame first, then setLightExtensionState
     * with multiple parameter variants) and logs reply bytes on success.
     *
     * The first variant that accepts our parcel without IllegalArgumentException
     * is treated as "working" — but the user must verify LEDs physically illuminate.
     */
    private void setAllLightsExt(int brightness) throws Exception {
        // --- Try setLightFrame(int state, bool flag, int[] colors, int extra) ----
        // setLightFrame is the most likely "paint all zones" method.
        System.err.println("[PROBE] === setLightFrame (TX 7) ===");
        if (trySetLightFrame(brightness)) {
            return;
        }

        // --- Try setLightExtensionState(int id, long state, int brightness) -----
        // Multiple value format variants — the IllegalArgumentException we saw
        // previously was likely server-side value validation.
        System.err.println("[PROBE] === setLightExtensionState (TX 6) variants ===");
        if (trySetLightExtensionStateVariants(brightness)) {
            return;
        }

        throw new Exception("All ILightsExtension probes failed — LEDs may require a different backend");
    }

    /**
     * Try setLightFrame(int state, bool flag, int[] colors, int extra).
     * Sends one int per zone, each zone's color = ARGB white scaled to brightness.
     */
    private boolean trySetLightFrame(int brightness) {
        int scaled = brightness > 0 ? Math.max(1, brightness * 255 / API_MAX) : 0;
        int color = brightness > 0
                ? (0xFF << 24) | (scaled << 16) | (scaled << 8) | scaled
                : 0;

        int[] colors = new int[GLYPH_LIGHT_IDS.length];
        for (int i = 0; i < colors.length; i++) colors[i] = color;

        // Variant A: (state=1, flag=true, colors[9], extra=0)
        if (attemptSetLightFrame(1, true, colors, 0, "A: state=1,flag=true")) return true;
        // Variant B: (state=brightness, flag=false, colors[9], extra=brightness)
        if (attemptSetLightFrame(brightness, false, colors, brightness, "B: state=br,flag=false,extra=br")) return true;
        // Variant C: single-element color array (in case it expects one overall color)
        int[] single = new int[]{color};
        if (attemptSetLightFrame(1, true, single, 0, "C: single-color")) return true;

        return false;
    }

    private boolean attemptSetLightFrame(int state, boolean flag, int[] colors, int extra, String label) {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(LIGHTS_EXT_DESCRIPTOR);
            data.writeInt(state);
            data.writeInt(flag ? 1 : 0);
            data.writeIntArray(colors);
            data.writeInt(extra);
            boolean ok = lightsExtBinder.transact(TX_EXT_SET_LIGHT_FRAME, data, reply, 0);
            if (!ok) {
                System.err.println("[PROBE] setLightFrame " + label + ": transact=false");
                return false;
            }
            System.err.println("[PROBE] setLightFrame " + label
                    + ": replySize=" + reply.dataSize()
                    + " reply=" + dumpReply(reply));
            if (reply.dataAvail() >= 4) {
                try {
                    reply.readException();
                } catch (Exception svcEx) {
                    System.err.println("[PROBE]   server rejected: "
                            + svcEx.getClass().getSimpleName() + ": " + svcEx.getMessage());
                    return false;
                }
            }
            System.err.println("[PROBE] setLightFrame " + label + ": ACCEPTED");
            return true;
        } catch (Exception e) {
            System.err.println("[PROBE] setLightFrame " + label + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Try setLightExtensionState(int id, long state, int brightness) with
     * multiple state/brightness value formats. The server previously rejected
     * ARGB-encoded state with IllegalArgumentException, so we probe other
     * likely encodings here.
     */
    private boolean trySetLightExtensionStateVariants(int brightness) {
        int id = GLYPH_LIGHT_IDS[0]; // probe with first zone
        int scaled255 = brightness > 0 ? Math.max(1, brightness * 255 / API_MAX) : 0;
        long argb = brightness > 0
                ? (0xFFL << 24) | ((long)scaled255 << 16) | ((long)scaled255 << 8) | scaled255
                : 0L;

        // Variant 1: state=1 (simple on-flag), brightness=0-255
        if (attemptSetExtState(id, brightness > 0 ? 1L : 0L, scaled255, "V1: state=on-flag,br=0-255")) {
            applyToRemainingIds(brightness > 0 ? 1L : 0L, scaled255);
            return true;
        }
        // Variant 2: state=brightness (0-4095), brightness=0-4095
        if (attemptSetExtState(id, (long)brightness, brightness, "V2: state=br,br=0-4095")) {
            applyToRemainingIds((long)brightness, brightness);
            return true;
        }
        // Variant 3: state=ARGB, brightness=0-255 (instead of 0-4095)
        if (attemptSetExtState(id, argb, scaled255, "V3: state=ARGB,br=0-255")) {
            applyToRemainingIds(argb, scaled255);
            return true;
        }
        // Variant 4: state=0 with brightness set (legacy HAL-style "off with level")
        if (attemptSetExtState(id, 0L, brightness, "V4: state=0,br=0-4095")) {
            applyToRemainingIds(0L, brightness);
            return true;
        }
        return false;
    }

    private void applyToRemainingIds(long state, int brightness) {
        for (int i = 1; i < GLYPH_LIGHT_IDS.length; i++) {
            attemptSetExtState(GLYPH_LIGHT_IDS[i], state, brightness, "apply id=" + GLYPH_LIGHT_IDS[i]);
        }
    }

    private boolean attemptSetExtState(int id, long state, int brightness, String label) {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(LIGHTS_EXT_DESCRIPTOR);
            data.writeInt(id);
            data.writeLong(state);
            data.writeInt(brightness);
            boolean ok = lightsExtBinder.transact(TX_EXT_SET_EXT_STATE, data, reply, 0);
            if (!ok) {
                System.err.println("[PROBE] setExtState " + label + ": transact=false");
                return false;
            }
            System.err.println("[PROBE] setExtState " + label
                    + " id=" + id + " state=0x" + Long.toHexString(state) + " br=" + brightness
                    + " replySize=" + reply.dataSize()
                    + " reply=" + dumpReply(reply));
            if (reply.dataAvail() >= 4) {
                try {
                    reply.readException();
                } catch (Exception svcEx) {
                    System.err.println("[PROBE]   server rejected: "
                            + svcEx.getClass().getSimpleName() + ": " + svcEx.getMessage());
                    return false;
                }
            }
            System.err.println("[PROBE] setExtState " + label + ": ACCEPTED");
            return true;
        } catch (Exception e) {
            System.err.println("[PROBE] setExtState " + label + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Hex dump the first N bytes of a Parcel reply (after rewinding). */
    private static String dumpReply(Parcel reply) {
        int size = Math.min(reply.dataSize(), 64);
        if (size <= 0) return "(empty)";
        int pos = reply.dataPosition();
        reply.setDataPosition(0);
        byte[] buf = reply.marshall();
        reply.setDataPosition(pos);
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(buf.length, 64);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02x", buf[i] & 0xFF));
            if ((i & 3) == 3) sb.append(' ');
        }
        if (buf.length > limit) sb.append("...");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Sysfs helpers
    // -----------------------------------------------------------------------

    /** Scale brightness from [0, API_MAX] to [0, SYSFS_MAX]. */
    private static int scale(int br) {
        return Math.min(SYSFS_MAX, br * SYSFS_MAX / API_MAX);
    }

    private boolean testSysfsWrite(String path) {
        try {
            FileWriter fw = new FileWriter(path);
            fw.write("0");
            fw.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeSysfs(int value) throws Exception {
        try {
            FileWriter fw = new FileWriter(sysfsPath);
            fw.write(Integer.toString(value));
            fw.close();
        } catch (Exception e) {
            throw new Exception("sysfs write to " + sysfsPath + " failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Binder binding helpers
    // -----------------------------------------------------------------------

    /** Try to get the Binder via hidden ServiceManager API. Returns null if not found. */
    private static IBinder tryServiceManager() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, GLYPH_SERVICE_NAME);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Bind to GlyphService and block until the ServiceConnection fires (up to 5 s).
     * The main Looper must be running on the main thread for the callback to fire.
     */
    private IBinder bindBlocking(Context ctx) throws Exception {
        Intent intent = new Intent(GLYPH_BIND_ACTION);
        intent.setComponent(new ComponentName(GLYPH_SERVICE_PKG, GLYPH_SERVICE_CLS));

        CountDownLatch latch = new CountDownLatch(1);
        IBinder[] result = {null};

        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                result[0] = service;
                latch.countDown();
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                result[0] = null;
            }
        };

        boolean bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            throw new Exception(
                "bindService() failed — GlyphService not available. " +
                "Start it first: am startservice -a " + GLYPH_BIND_ACTION +
                " " + GLYPH_SERVICE_PKG + "/." + "GlyphService");
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            ctx.unbindService(connection);
            connection = null;
            throw new Exception(
                "Timed out waiting for GlyphService connection (5 s). " +
                "Ensure the service is running.");
        }

        if (result[0] == null) {
            throw new Exception("GlyphService connected but returned null Binder");
        }
        return result[0];
    }

    // -----------------------------------------------------------------------
    // Binder transaction helpers
    // -----------------------------------------------------------------------

    private void transactVoid(int code) throws Exception {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(GLYPH_SERVICE_NAME);
            binder.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transactString(int code, String value) throws Exception {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(GLYPH_SERVICE_NAME);
            data.writeString(value);
            binder.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transactIntArray(int code, int[] values) throws Exception {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(GLYPH_SERVICE_NAME);
            data.writeIntArray(values);
            binder.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Sine-shaped brightness curve: up from 0 to max then back to 0. */
    private static int[] buildSineCurve(int max, int steps) {
        int[] curve = new int[steps * 2];
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * i / steps;
            int b = (int) (max * Math.sin(angle));
            curve[i]                 = b;
            curve[steps * 2 - 1 - i] = b;
        }
        return curve;
    }
}
