# Wear OS dev.14 Watch7 sidecar acceptance

Date: 2026-09-26

- Device: Samsung Galaxy Watch7 (`SM-L310`), Android API 36.
- Existing app preserved: `com.sayit.watch.debug`, dev.13/code 17; its `lastUpdateTime` remained `2026-09-26 16:55:32` before and after this run.
- Sidecar identity: `com.sayit.watch.universaltest`, dev.14/code 18, label `SayIt 通用测试`.
- Sidecar APK SHA-256: `05E74E0FCD01B1D466DF2A4AFCCCB107A12DFB6C63D18040C94384AFD8E8AEBD`.
- Capability readback passed: Watch form factor, microphone and Wi-Fi; `minSdk=30`, `targetSdk=34`.
- The sidecar authenticated to the discovered Windows receiver, recorded two seconds, uploaded, and returned to `MIC READY / 已连接电脑`.
- Windows received a new 83,244-byte WAV at `2026-09-26 20:28:55.486 -07:00`; SHA-256 `0DA6D64080749FE0475F32A283D2BF86CCE52AEC3DBEC24016BE3B7ACBBE3615`. The received audio itself is not committed.
- This verifies the generic build on the existing Watch7 only. It does not replace the still-required non-Samsung Wear OS device acceptance.

Evidence:

- `01-ready-connected.png`: sidecar launched and authenticated.
- `02-recording-2s.png`: real Watch microphone capture at two seconds.
- `03-ready-after-upload.png`: returned to the connected ready state after upload.

