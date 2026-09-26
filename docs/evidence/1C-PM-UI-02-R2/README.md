# 1C-PM-UI-02@R2 evidence

Date: 2026-09-26. Watch candidate: `0.3.0-dev.9` / code 13.

## Result

- A computer streamed into the picker has already passed the existing Bearer-authenticated
  discovery probe. It is now selectable immediately; selecting it stops the remaining browse
  window and uses the existing explicit-pick confirmation probe before adoption.
- The 48 dp close action is shifted 18 dp left into the round-screen safe region. On the real
  Galaxy Watch 7 an automated screenshot/tap returned from the searching picker to Ready.
- The installed package retained app data and read back as dev.9/code 13. Device rotation
  settings were `accelerometer_rotation=0`, `user_rotation=0` after acceptance; neither was
  written by this run.

## Verification

- Targeted production-path tests: exit 0.
- `gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain`:
  exit 0; 26 suites / 242 tests / 0 failures / 0 errors; lint 0 errors / 38 warnings.
- APK: `watch/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `64B4ACAEF12703B00B6D7A3338E5F6AC8B6B52F2A7B3211F4B32ED54DFDA4BE6`
- `adb install --no-streaming -r`: Success.

## Evidence boundary

`dev9-picker-searching-close-safe.png` and `dev9-close-result-ready.png` are real-device
screenshots. The user later confirmed the control was still physically obstructed; this overrides
the screenshot-only visual judgement, rejects dev.9's close placement, and is corrected by the
bottom-centred candidate.4/dev.11. During this run `.142` was reachable and returned the expected unauthenticated
401, while `.153` returned no HTTP response. Therefore the real-device close-control result is
verified, but a fresh two-PC immediate-tap run is not claimed. The same ViewModel -> owner ->
resolver -> coordinator production chain is covered by the passing regression that selects an
authenticated streamed candidate before the full collection window closes and verifies that
the browse stops, the destination persists, and recording remains available.

`candidate-searching-immediate-pick.png` is the frozen candidate preview showing the intended
two-computer searching state; it is not presented as a device screenshot.

No Windows receiver, mDNS/authentication policy, upload protocol, recording, Provider, ASR,
History, Paste, release, or credentials were changed.
