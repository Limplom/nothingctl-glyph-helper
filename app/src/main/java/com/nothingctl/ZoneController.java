package com.nothingctl;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;

/**
 * Controls Nothing Glyph LEDs via direct Binder transactions to the GlyphService.
 *
 * Transaction codes from rec0de/glyph-api (https://github.com/rec0de/glyph-api):
 *   1 = setFrameColors(IntArray colors)
 *   2 = openSession()
 *   3 = closeSession()
 *   4 = register(String apiKey)
 *
 * The GlyphService must be running before init() is called. If not running,
 * start it first via: adb shell su -c 'am startservice -a
 * com.nothing.thirdparty.bind_glyphservice com.nothing.thirdparty/.GlyphService'
 */
public class ZoneController {

    private static final int TRANSACTION_SET_FRAME_COLORS = 1;
    private static final int TRANSACTION_OPEN_SESSION     = 2;
    private static final int TRANSACTION_CLOSE_SESSION    = 3;
    private static final int TRANSACTION_REGISTER         = 4;

    private static final String GLYPH_SERVICE_NAME =
            "com.nothing.thirdparty.IGlyphService";
    private static final String API_KEY = "test";

    private final int zoneCount;
    private IBinder binder;

    public ZoneController(int zoneCount) {
        this.zoneCount = zoneCount;
    }

    /** Obtain the GlyphService Binder via reflection, register, and open a session. */
    public void init() throws Exception {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            binder = (IBinder) getService.invoke(null, GLYPH_SERVICE_NAME);
        } catch (ReflectiveOperationException e) {
            throw new Exception("ServiceManager reflection failed: " + e.getMessage(), e);
        }
        if (binder == null) {
            throw new Exception(
                "GlyphService Binder not found as '" + GLYPH_SERVICE_NAME + "'. " +
                "Ensure the service is running: am startservice -a " +
                "com.nothing.thirdparty.bind_glyphservice com.nothing.thirdparty/.GlyphService");
        }
        transactString(TRANSACTION_REGISTER, API_KEY);
        transactVoid(TRANSACTION_OPEN_SESSION);
    }

    /** Set all zones to the given brightness (0–4095). */
    public void allOn(int brightness) throws Exception {
        int[] colors = new int[zoneCount];
        for (int i = 0; i < zoneCount; i++) colors[i] = brightness;
        transactIntArray(TRANSACTION_SET_FRAME_COLORS, colors);
    }

    /** Turn all zones off. */
    public void allOff() throws Exception {
        transactIntArray(TRANSACTION_SET_FRAME_COLORS, new int[zoneCount]);
    }

    /**
     * Run one pulse cycle: brightness ramps up then back down following a
     * sine curve, with 150 ms per step.
     */
    public void pulse(int maxBrightness, int steps) throws Exception {
        int[] curve = buildSineCurve(maxBrightness, steps);
        for (int b : curve) {
            int[] colors = new int[zoneCount];
            for (int i = 0; i < zoneCount; i++) colors[i] = b;
            transactIntArray(TRANSACTION_SET_FRAME_COLORS, colors);
            Thread.sleep(150);
        }
        allOff();
    }

    /** Close the session and release the Binder. */
    public void close() throws Exception {
        if (binder != null) {
            transactVoid(TRANSACTION_CLOSE_SESSION);
            binder = null;
        }
    }

    // -----------------------------------------------------------------------
    // Binder helpers
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
