package com.nothingctl;

import android.content.Context;
import android.os.Looper;

/**
 * Entry point for the nothingctl Glyph LED helper.
 *
 * Invoked via app_process from ADB root:
 *   adb shell su -c 'app_process -cp /data/local/tmp/glyph-helper.dex /
 *       com.nothingctl.GlyphHelper <cmd> [args]'
 *
 * Commands:
 *   info                        — print device codename, model, zone count, supported flag
 *   on [brightness]             — turn all zones on (default: 4000, range 0–4095)
 *   off                         — turn all zones off
 *   pulse [brightness] [steps]  — one sine-curve pulse cycle (default: 4000, 10 steps)
 *
 * LED control strategy (tried in order):
 *   1. Sysfs: writes directly to /sys/class/leds/noth_leds/brightness.
 *      Works on Phone (3a Lite / galaxian) and potentially other newer devices.
 *      Requires root (run via su) but needs no Android Context or Looper.
 *
 *   2. GlyphService Binder (fallback): binds to com.nothing.thirdparty.GlyphService.
 *      Requires a Context obtained via ActivityThread.systemMain() and a running Looper.
 *      Looper.prepareMainLooper() must be called before systemMain() in app_process
 *      since the Looper is not auto-prepared in this execution context.
 */
public class GlyphHelper {

    private static volatile int exitCode = 0;

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        // "info" does not need any LED connection — answer immediately.
        if (args[0].equals("info")) {
            System.out.println("device="    + DeviceInfo.codename());
            System.out.println("model="     + DeviceInfo.model());
            System.out.println("zones="     + DeviceInfo.zoneCount());
            System.out.println("supported=" + DeviceInfo.isSupported());
            System.exit(0);
        }

        if (!DeviceInfo.isSupported()) {
            System.err.println("[WARN] Device not supported: "
                    + DeviceInfo.codename() + " / " + DeviceInfo.model());
            System.exit(2);
        }

        ZoneController ctrl = new ZoneController(DeviceInfo.zoneCount());

        // Strategy 1: sysfs — no Context or Looper needed.
        if (ctrl.initSysfs()) {
            try {
                runCommand(ctrl, args);
            } catch (Exception e) {
                System.err.println("[ERROR] " + describeError(e));
                System.exit(1);
            } finally {
                ctrl.close(null);
            }
            System.exit(0);
        }

        // Strategy 2: GlyphService Binder.
        // Requires ActivityThread + Looper on the main thread.
        Context ctx = createContext();

        final String[] mainArgs = args;
        Thread worker = new Thread(() -> {
            try {
                ctrl.initBinder(ctx);
                runCommand(ctrl, mainArgs);
            } catch (Exception e) {
                System.err.println("[ERROR] " + describeError(e));
                exitCode = 1;
            } finally {
                ctrl.close(ctx);
                Looper.getMainLooper().quitSafely();
            }
        }, "glyph-worker");
        worker.setDaemon(false);
        worker.start();

        Looper.loop(); // blocks until worker calls quitSafely()

        try { worker.join(); } catch (InterruptedException ignored) {}
        System.exit(exitCode);
    }

    private static void runCommand(ZoneController ctrl, String[] args) throws Exception {
        switch (args[0]) {
            case "on": {
                int brightness = parseIntArg(args, 1, 4000, "brightness");
                ctrl.allOn(brightness);
                break;
            }
            case "off": {
                ctrl.allOff();
                break;
            }
            case "pulse": {
                int brightness = parseIntArg(args, 1, 4000, "brightness");
                int steps      = parseIntArg(args, 2, 10,   "steps");
                ctrl.pulse(brightness, steps);
                break;
            }
            default:
                System.err.println("[ERROR] Unknown command: " + args[0]);
                exitCode = 1;
        }
    }

    /**
     * Creates a Context by bootstrapping ActivityThread on the main thread.
     *
     * Looper.prepareMainLooper() must be called first: ActivityThread.systemMain()
     * creates a Handler internally which requires a prepared Looper. In app_process
     * the main Looper is never auto-prepared, so we prepare it explicitly here.
     */
    private static Context createContext() {
        // Prepare the main Looper first — ActivityThread.systemMain() creates Handlers.
        try {
            Looper.prepareMainLooper();
        } catch (IllegalStateException ignored) {
            // Already prepared — that's fine.
        }

        // Strategy 1: ActivityThread.systemMain() + getSystemContext()
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("systemMain").invoke(null);
            if (at != null) {
                Context ctx = (Context) atClass.getMethod("getSystemContext").invoke(at);
                if (ctx != null) return ctx;
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.err.println("[WARN] ActivityThread.systemMain() failed: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }

        // Strategy 2: ContextImpl.getSystemContext() — fallback for alternate Android variants
        try {
            Class<?> ciClass = Class.forName("android.app.ContextImpl");
            java.lang.reflect.Method m = ciClass.getDeclaredMethod("getSystemContext");
            m.setAccessible(true);
            Context ctx = (Context) m.invoke(null);
            if (ctx != null) return ctx;
        } catch (Throwable ignored) {}

        System.err.println("[ERROR] Could not create Android context — "
                + "GlyphService Binder path is unavailable from this process");
        System.exit(1);
        return null; // unreachable
    }

    /** Parse args[index] as int, returning defaultVal if the argument is absent. */
    private static int parseIntArg(String[] args, int index, int defaultVal, String name) {
        if (args.length <= index) return defaultVal;
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException e) {
            System.err.println("[ERROR] Invalid " + name + " '" + args[index] + "': must be an integer");
            exitCode = 1;
            throw new RuntimeException(e);
        }
    }

    private static String describeError(Exception e) {
        String msg = e.getMessage();
        return msg != null ? msg : e.toString();
    }

    private static void printUsage() {
        System.err.println("Usage: GlyphHelper <cmd> [args]");
        System.err.println("  info                        — device info + supported flag");
        System.err.println("  on [brightness]             — all zones on (0–4095, default 4000)");
        System.err.println("  off                         — all zones off");
        System.err.println("  pulse [brightness] [steps]  — one pulse cycle (default 4000, 10 steps)");
    }
}
