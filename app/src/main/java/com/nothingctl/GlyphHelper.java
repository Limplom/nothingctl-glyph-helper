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
 * Architecture note:
 *   GlyphService is a bound service — not registered with ServiceManager.
 *   bindService() requires a Context and a running Looper for its callbacks.
 *   We initialise ActivityThread.systemMain() on the main thread (which also
 *   prepares the main Looper), run the command logic on a worker thread, and
 *   run Looper.loop() on the main thread to dispatch the ServiceConnection
 *   callback back to ZoneController.
 */
public class GlyphHelper {

    private static volatile int exitCode = 0;

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        // "info" does not need a Binder connection — answer immediately.
        if (args[0].equals("info")) {
            System.out.println("device="    + DeviceInfo.codename());
            System.out.println("model="     + DeviceInfo.model());
            System.out.println("zones="     + DeviceInfo.zoneCount());
            System.out.println("supported=" + DeviceInfo.isSupported());
            System.exit(0);
        }

        // Set up the main Looper (needed for bindService callbacks).
        // ActivityThread.systemMain() calls Looper.prepareMainLooper() internally.
        Context ctx = createContext();

        final String[] mainArgs = args;
        Thread worker = new Thread(() -> {
            try {
                runCommand(ctx, mainArgs);
            } catch (Exception e) {
                String msg = e.getMessage();
                System.err.println("[ERROR] " + (msg != null ? msg : e.toString()));
                exitCode = 1;
            } finally {
                Looper.getMainLooper().quitSafely();
            }
        }, "glyph-worker");
        worker.setDaemon(false);
        worker.start();

        Looper.loop(); // blocks until worker calls quitSafely()

        try { worker.join(); } catch (InterruptedException ignored) {}
        System.exit(exitCode);
    }

    private static void runCommand(Context ctx, String[] args) throws Exception {
        if (!DeviceInfo.isSupported()) {
            System.err.println("[WARN] Device not supported: "
                    + DeviceInfo.codename() + " / " + DeviceInfo.model());
            exitCode = 2;
            return;
        }

        ZoneController ctrl = new ZoneController(DeviceInfo.zoneCount());
        try {
            ctrl.init(ctx);
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
        } finally {
            ctrl.close(ctx);
        }
    }

    /** Creates a Context by bootstrapping ActivityThread on the main thread. */
    private static Context createContext() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("systemMain").invoke(null);
            return (Context) atClass.getMethod("getSystemContext").invoke(at);
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to create Android context: " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }
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

    private static void printUsage() {
        System.err.println("Usage: GlyphHelper <cmd> [args]");
        System.err.println("  info                        — device info + supported flag");
        System.err.println("  on [brightness]             — all zones on (0–4095, default 4000)");
        System.err.println("  off                         — all zones off");
        System.err.println("  pulse [brightness] [steps]  — one pulse cycle (default 4000, 10 steps)");
    }
}
