# 1C-PM-UI-02@R3 source/build evidence

Date: 2026-09-26. Candidate: Watch `0.3.0-dev.11` / code 15.

## Implemented behavior

- The visible computer picker continuously repeats the existing bounded authenticated browse.
- A newly started desktop app streams into the same open picker immediately after authentication.
- At each cycle boundary, all visible rows receive one concurrent Bearer confirmation probe.
- A non-current computer that is absent from discovery and fails confirmation is removed.
- The selected computer remains pinned as `未连接` when offline and returns online in place.
- Closing, backgrounding, selecting, recording hand-off, and ViewModel teardown stop picker refresh.
- The top-right close control is removed; candidate.4's 48 dp close control stays fixed at bottom
  centre with an 8 dp inset.

## Verification

- Focused production-chain regression covers: second computer appears; stopped non-current
  computer disappears; selected computer remains offline; another computer starts and appears;
  selected computer returns online without duplication; close/background stops further browse.
- Full `testDebugUnitTest --rerun-tasks lintDebug assembleDebug`: exit 0; 26 suites / 243 tests /
  0 failures / 0 errors; lint 0 errors / 38 warnings; Debug APK built.
- The strengthened focused regression was rerun after its final assertion update: exit 0.
- APK: `watch/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `934CC882968ADC4FC6748FEC475547CB86FE40AC112EE62C162F75073494D305`
- `git diff --check`: exit 0.

## Device boundary

The newly supplied endpoint `192.168.12.126:44703` accepted a data-preserving
`adb install --no-streaming -r`; the device readback is `0.3.0-dev.11` / code 15. A cold launch
reached the normal Ready screen (`sayit-dev11-home.png`, SHA-256
`C7AAE753D03FD34561FFBF0A33A94149365BFC1C539E6D34E8CBC25CFA6B9DC7`). System rotation was
read-only checked as `accelerometer_rotation=0` and `user_rotation=0` before the UI attempt.

The Watch then entered Doze and its wireless-debugging mDNS service disappeared; port 44703 now
refuses connections. The local receiver at `.142:18099` remains reachable (unauthenticated probe
returns the expected 401), while the second laptop `.153:18099` is unreachable. Therefore the
bottom-centred close control and two-computer live add/remove round trip remain **not device
accepted**. Blurred/black transition captures were discarded rather than represented as evidence.

No Windows receiver, discovery protocol, authentication policy, upload/recording path, Provider,
ASR, History, Paste, credentials, Release, push, merge, or tag changed.
