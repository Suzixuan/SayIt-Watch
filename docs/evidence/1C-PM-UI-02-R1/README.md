# 1C-PM-UI-02@R1 — computer identity picker device evidence

Date: 2026-09-26

## Final artifact

- Package: `com.sayit.watch.debug`
- Version: `0.3.0-dev.8` / code `12`
- Debug APK SHA-256: `C6A1ABB905542C6F6CE22802CAA76B91CAA1B3B84F6D1F8C53EFAAD38F27646E`
- Preserve-data install: `adb install --no-streaming -r`, result `Success`
- Automated verification: 26 suites / 242 tests / 0 failures / 0 errors / 0 skipped;
  lint 0 errors / 38 warnings; Debug build success.

## Screenshots

- `picker-final-apk.png`: final APK, completed two-computer search. The current endpoint is
  presented as `电脑 · 142`; the other authenticated endpoint is `电脑 · 153`; both retain
  the real IP as secondary text.
- `picker-searching-final.png`: authenticated candidates are streamed into the picker while
  the bounded search still runs; the other computer is visible but disabled.
- `picker-153-current-final.png`: explicit selection moved the current marker to `.153`.
- `restored-142-final.png`: explicit selection returned to `.142` and Ready remained usable.

The last two interaction images were captured from the dev.8 build immediately before the
final removal of seven unused string resources and one unused display helper. That cleanup
did not change rendered strings, selection callbacks, discovery state or product behaviour;
the final artifact was then rebuilt, installed and `picker-final-apk.png` was captured from it.

## Boundaries and recovery

- No Watch Token, manual address, Windows receiver, mDNS/authentication contract, recording,
  upload, Provider, ASR, History or Paste code changed.
- The display name is a truthful IPv4 fallback, not a fabricated custom nickname. Optional
  authenticated desktop metadata remains future work.
- Device system rotation was read before/after the automation and remained
  `accelerometer_rotation=0`, `user_rotation=0`; no rotation setting was written.
- One extra coordinate-driven attempt ran after the app had already returned to Ready and
  accidentally started recording. It was immediately cancelled through the visible Cancel
  action; Stop was not pressed and no upload was initiated. Those temporary captures are not
  included as acceptance evidence.
