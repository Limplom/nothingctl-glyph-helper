package com.nothingctl;

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
 */
public class GlyphHelper {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String cmd = args[0];

        if (cmd.equals("info")) {
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
        try {
            ctrl.init();
            switch (cmd) {
                case "on": {
                    int brightness = args.length > 1 ? Integer.parseInt(args[1]) : 4000;
                    ctrl.allOn(brightness);
                    break;
                }
                case "off": {
                    ctrl.allOff();
                    break;
                }
                case "pulse": {
                    int brightness = args.length > 1 ? Integer.parseInt(args[1]) : 4000;
                    int steps      = args.length > 2 ? Integer.parseInt(args[2]) : 10;
                    ctrl.pulse(brightness, steps);
                    break;
                }
                default:
                    System.err.println("[ERROR] Unknown command: " + cmd);
                    ctrl.close();
                    System.exit(1);
            }
            ctrl.close();
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            try { ctrl.close(); } catch (Exception ignored) {}
            System.exit(1);
        }
        System.exit(0);
    }

    private static void printUsage() {
        System.err.println("Usage: GlyphHelper <cmd> [args]");
        System.err.println("  info                        — device info + supported flag");
        System.err.println("  on [brightness]             — all zones on (0–4095, default 4000)");
        System.err.println("  off                         — all zones off");
        System.err.println("  pulse [brightness] [steps]  — one pulse cycle (default 4000, 10 steps)");
    }
}
