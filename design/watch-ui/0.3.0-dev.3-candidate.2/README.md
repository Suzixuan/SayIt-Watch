# SayIt Watch low-power recording UI — 0.3.0-dev.3-candidate.2

Status: implemented and initially accepted on Galaxy Watch7. The configurable UI and its runtime boundary are verified; precise battery-life savings remain a future measurement task.

## Lineage

- Parent visual candidate: `design/watch-ui/0.3.0-dev.3-candidate.1/` (preserved unchanged).
- Pre-change runtime baseline: `design/watch-ui/0.3.0-dev.3-baseline.1/`.
- Product build: Watch `0.3.0-dev.16`, versionCode `20`.

## Accepted behavior

- Connection Settings contains `低功耗界面（秒）`, default `10`, valid range `1–180`.
- Before the threshold the existing light dial and animated waveform remain unchanged.
- At the threshold the screen switches to a black dial and static waveform; elapsed time continues at 1 Hz.
- Exact audio sample accounting, Stop/Cancel, keep-screen-on, the 180-second capture limit, upload, ASR, and AI processing paths are unchanged.
- Real-device persistence was checked by saving `15`, force-stopping and cold-starting the app, then restoring `10`.
- Real-device screenshots show the 5-second bright state and 12-second low-power state. Cancel returns to `MIC READY` and discards the recording.

## Visual correction retained for audit

`06-rejected-watch7-cancel-overlap.png` is the rejected first device pass: the cancel label overlapped the bottom blue tick. The final source moves the cancel hit target from `98.dp` to `86.dp`; `05-watch7-low-power-final.png` is the accepted device result.

## Initial power evidence

Conditions: same dev.16 binary, Galaxy Watch7, rotation locked at 0°, manual brightness 98 during measurement, no media playback, 165-second recording canceled at the end. The `180`-second threshold keeps the legacy bright animation for the whole sample; the `10`-second threshold enables the new low-power state. Start temperatures were 24.1°C and 24.3°C.

- Charge counter: both runs measured `3.692 mAh`. The device counter moves in coarse steps (about `0.568 mAh`), so this single pair does **not** resolve an mAh saving.
- Temperature: bright animation `24.1→27.1°C` (`+3.0°C`); low-power `24.3→24.4°C` (`+0.1°C`).
- 30-second render load: `1815→632` frames, a `65.2%` reduction. This 30-second low-power sample includes the first 10 animated seconds.

The safe initial conclusion is reduced display/render work and substantially lower observed heating, not a proven battery-percentage claim. Repeated longer runs or power-rail instrumentation are deferred.

Raw readings are in `battery-ab-dev16.csv` and `render-frame-ab-dev16.csv`.

## Verification

- Watch unit tests: 30 suites / 258 tests / 0 failures / 0 errors / 0 skipped.
- `lintDebug`: 0 errors / 38 warnings.
- Debug and Release APK builds: passed.
- Final Debug APK: 21,126,192 bytes; SHA-256 `95B0BD3BA2096805685AF0BF26C83282D4A52F920E4919274D559F1B2E6DEE5C`.
- Installed package readback: `0.3.0-dev.16` / versionCode `20`.
- Automatic brightness was restored after measurement; rotation remains locked at 0°; threshold remains `10`.

No desktop product code, network protocol, received audio, transcript, token, APK, or build cache is included in this frozen design evidence.
