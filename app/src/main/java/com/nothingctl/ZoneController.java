package com.nothingctl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Controls Nothing Glyph LEDs via direct Binder transactions to the GlyphService.
 *
 * Transaction codes from rec0de/glyph-api (https://github.com/rec0de/glyph-api):
 *   1 = setFrameColors(IntArray colors)
 *   2 = openSession()
 *   3 = closeSession()
 *   4 = register(String apiKey)
 *
 * AIDL interface descriptor: "com.nothing.thirdparty.IGlyphService"
 * This value is used for both ServiceManager lookup AND writeInterfaceToken().
 * Both must match the descriptor in the service's AIDL definition.
 * Source: rec0de/glyph-api; cross-checked against com.nothing.thirdparty package.
 *
 * Binding: GlyphService is a bound service (not registered with ServiceManager).
 * init() uses bindService() with a 5 s timeout. The caller must ensure the main
 * Looper is running (via Looper.loop()) on the main thread, or the ServiceConnection
 * callback will never fire.
 *
 * Start the service first if needed:
 *   adb shell su -c 'am startservice -a
 *   com.nothing.thirdparty.bind_glyphservice com.nothing.thirdparty/.GlyphService'
 */
public class ZoneController {

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

    /**
     * Obtain the GlyphService Binder, register, and open a session.
     *
     * Strategy:
     *   1. Try ServiceManager (hidden API via reflection) — works if the service
     *      happens to be registered there on some devices/ROMs.
     *   2. Fall back to bindService() — the canonical path for bound services.
     *      Requires ctx != null and the main Looper to be running.
     */
    public void init(Context ctx) throws Exception {
        // 1. Try ServiceManager (may return null on most devices for bound services)
        binder = tryServiceManager();

        // 2. Fall back to bindService
        if (binder == null) {
            if (ctx == null) {
                throw new Exception(
                    "GlyphService not found in ServiceManager and no Context provided for bindService()");
            }
            binder = bindBlocking(ctx);
        }

        transactString(TRANSACTION_REGISTER, API_KEY);
        transactVoid(TRANSACTION_OPEN_SESSION);
        sessionOpen = true;
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
    }

    // -----------------------------------------------------------------------
    // Binding helpers
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
