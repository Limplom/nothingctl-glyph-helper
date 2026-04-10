package com.nothingctl;

public class LightState {
    public final int brightness; // 0–4095
    public final long durationMs;

    public LightState(int brightness, long durationMs) {
        this.brightness = brightness;
        this.durationMs = durationMs;
    }
}
