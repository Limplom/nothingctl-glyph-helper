# nothingctl-glyph-helper

Android DEX helper for [nothingctl](https://github.com/Limplom/nothingctl) — controls Nothing Glyph LEDs on all supported devices via the official Nothing Glyph SDK, invoked via `app_process` from ADB root.

## What this is

[nothingctl](https://github.com/Limplom/nothingctl) is a Go CLI tool that manages Nothing phones over ADB (firmware, root, backups, diagnostics). During long operations like `backup`, it pulses the Glyph LEDs as progress feedback.

On Nothing Phone 1, this works via direct sysfs writes to the `aw210xx_led` kernel driver. On all newer devices, the kernel blocks direct LED writes — control requires the proprietary Nothing Glyph SDK which needs an Android app context.

This helper bridges that gap: a minimal Java program that uses the official SDK, compiled to a standalone DEX and invoked via `app_process` (no APK install needed).

## Supported devices

| Device | SDK used |
|--------|----------|
| Nothing Phone 1, 2, 2a, 3a, 3a Lite | Glyph Developer Kit |
| Nothing Phone 3 | GlyphMatrix Developer Kit |

## Build

Requires JDK 11 and Android SDK.

```bash
./gradlew assembleRelease
```

## CLI interface

```
GlyphHelper info                    — print device codename + glyph type
GlyphHelper on [brightness]         — all zones on
GlyphHelper off                     — all zones off
GlyphHelper pulse [brightness]      — one pulse cycle (up + down)
```

## Manual test via ADB

```bash
adb push app/build/outputs/dex/classes.dex /data/local/tmp/glyph-helper.dex
adb shell su -c 'app_process -cp /data/local/tmp/glyph-helper.dex / com.nothingctl.GlyphHelper info'
adb shell su -c 'app_process -cp /data/local/tmp/glyph-helper.dex / com.nothingctl.GlyphHelper on 3000'
adb shell su -c 'app_process -cp /data/local/tmp/glyph-helper.dex / com.nothingctl.GlyphHelper off'
```

## Usage

This is not a standalone app. It is embedded in the nothingctl binary and invoked automatically when Glyph feedback is needed.
