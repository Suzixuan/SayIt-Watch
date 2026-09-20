# 1C-PM-UI-01@R4 — connected-status optical center

- `ready.png` is a fresh 480×480 screenshot from the Galaxy Watch 7 after preserving app data with `adb install -r` of the R4 Debug APK. App package reads `com.sayit.watch.debug`, versionCode 5 / versionName `0.3.0-dev.1`, updated 2026-09-20 01:13:35. No Token or device identifier appears in this image.
- Compared pixel by pixel with `design/watch-ui/0.3.0-dev.1-candidate.4/parent-ready.png`: 925 changed pixels, all inside the connected-status region x=175..305, y=326..357. The visible text's weighted x center changes from about 244 to 240 on a 480 px image. No other dial pixels differ.
- The pixels of `ready.png` are identical to the approved `design/watch-ui/0.3.0-dev.1-candidate.4/01-ready-status-centered.png` preview; the PNG file hashes differ only in encoding. The R3 Settings menu remains locked and unchanged.
- This is a layout-only device check. It does not reverify two-PC discovery/switch or a new recording-to-text run.
