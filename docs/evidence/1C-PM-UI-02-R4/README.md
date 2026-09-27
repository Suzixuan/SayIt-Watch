# 1C-PM-UI-02@R4 local computer alias evidence

Date: 2026-09-26. Candidate: Watch `0.3.0-dev.12` / code 16.

## Corrected gap

dev.11 did not hide a customization screen: it had no edit event on the computer glyph or row,
and no alias persistence. That contradicted the identity/customization direction shown earlier.

## Implemented behavior

- A pencil badge and 40 dp target make the left computer glyph explicitly editable.
- Tapping the glyph or long-pressing the row opens the same full-screen customization pane.
- A normal row tap keeps the existing explicit-switch behavior.
- The pane edits a normalized 1–16 character name, saves it, or restores the deterministic
  `电脑 · <IPv4 suffix>` fallback.
- The alias is app-private Watch data keyed by the already-authenticated `IP:port`; it never enters
  mDNS, probe/upload requests, logs, or the Windows app.
- Candidate.5 live refresh, immediate pick, offline/current behavior, and bottom-centred close are
  unchanged.

## Verification

- Focused alias and existing picker identity tests: exit 0.
- Full `testDebugUnitTest --rerun-tasks lintDebug assembleDebug`: exit 0.
- Tests: 27 suites / 246 tests / 0 failures / 0 errors / 0 skipped.
- Lint: 0 errors / 38 warnings (plus one informational issue in the XML report).
- APK: `watch/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `CC7CF1DB890F446B82CCB507BAEF886F04BDBBCD78C6F69D35CE3F263978E282`
- `git diff --check`: exit 0.
- Frozen visual/runtime slice: `design/watch-ui/0.3.0-dev.2-candidate.6/`; manifest recomputation:
  0 failures.

## Device boundary

dev.12 was later installed through `192.168.12.126:40131` with data preserved and read back as
code 16. Real-device evidence rejected this candidate: the row's parent gesture consumed the
nested glyph tap, and the fixed close control covered the refresh action with two visible rows.
`sayit-dev12-picker.png` (SHA-256
`417DF615EA32078466DAE9BFFE1180DD61045D88854486C59C660169ADDFA10F`) preserves the rejected
layout. The correction and accepted device evidence continue in `1C-PM-UI-02@R5` / dev.13.

No Windows receiver, discovery/authentication contract, target verification, live presence loop,
recording/upload, Provider, ASR, History, Paste, credentials, Release, push, merge, or tag changed.
