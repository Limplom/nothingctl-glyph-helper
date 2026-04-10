package com.nothingctl;

import android.os.SystemProperties;

public class DeviceInfo {

    public static String codename() {
        return SystemProperties.get("ro.product.device", "unknown").toLowerCase();
    }

    public static String model() {
        return SystemProperties.get("ro.product.model", "unknown");
    }

    /**
     * Number of Glyph zones — the expected IntArray size for setFrameColors().
     * Model is checked before codename to distinguish Phone 3a (A001, 4 zones)
     * from Phone 3a Lite (A001T, 2 zones) which share the "galaxian" codename.
     */
    public static int zoneCount() {
        String m = model();
        if (m.contains("A001T")) return 2; // Phone 3a Lite
        switch (codename()) {
            case "spacewar": return 5; // Phone 1
            case "pong":     return 7; // Phone 2
            case "pacman":   return 3; // Phone 2a
            case "galaxian": return 4; // Phone 3a
            default:         return 0;
        }
    }

    public static boolean isSupported() {
        return zoneCount() > 0;
    }
}
