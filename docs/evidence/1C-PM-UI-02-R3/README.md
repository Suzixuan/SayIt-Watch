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

The previously supplied wireless ADB endpoint `192.168.12.126:36727` currently refuses the
connection, so dev.11 has not been installed or device-verified. The Watch remains on the earlier
dev.9 package until a current wireless-debugging endpoint is supplied. Do not claim the
bottom-centred control or live add/remove behavior as real-device accepted yet.

No Windows receiver, discovery protocol, authentication policy, upload/recording path, Provider,
ASR, History, Paste, credentials, Release, push, merge, or tag changed.
