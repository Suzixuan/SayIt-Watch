# SayIt Watch UI handoff for colleague Z

Status: PM-reviewed UI direction. Reference only; this document does not unlock product-code changes.

## Product boundary

This is a **Wear OS app**, not a watch face. The Watch only records a whole WAV and uploads it. Existing SayIt on Windows performs ASR, AI cleanup, History, and Paste. The historical Delivery 1B AI-off rule was superseded by the user's 2026-09-26 correction: Watch recordings now follow the existing desktop AI switch and prompt routing.

The runtime flow is:

`Ready -> Recording -> Stop -> automatic upload -> Uploaded to PC | Upload failed (retryable)`

There is no separate Send page and no transcription-success page on the Watch.

## Screen and state model

Use three screens plus inline states, not four independent pages:

1. **Developer configuration**
   - PC RFC1918 IPv4
   - Port
   - 64-character hexadecimal Dev Token
   - Save and apply
2. **Ready**
   - One explicit health check or refresh action
   - `Transport available` means `GET /api/health` is reachable; it does not mean a persistent connection, Provider readiness, or ASR readiness.
   - One primary record button
3. **Recording**
   - Sample-derived duration
   - Stop button
   - If Cancel is retained, it means: stop capture, discard this recording, do not upload, return to Ready. It must not be confused with Stop.

Inline states on the runtime screen:

- **Uploading**: recording stopped; automatic whole-WAV upload is in progress.
- **Upload failed**: retain the WAV and offer `Retry` plus `Later`. `Later` returns to Ready with an obvious `Pending upload` state. Starting a new recording must not silently overwrite the retained WAV; require an explicit discard decision first.
- **Uploaded to PC**: brief transport-only confirmation, then return to Ready. Never display `Transcribed`, `Recognition complete`, or `Text inserted` based on HTTP `201`.

## Copy and visual corrections

- Title/copy: use `SayIt · Galaxy Watch 7 手表端 UI` or `SayIt Wear OS App`; do not use `表盘`.
- Never show a real or full Dev Token in mockups, screenshots, logs, or handoff evidence. Use a masked example such as `A1B2••••••••7890`, while the helper text states that input validation requires exactly 64 hexadecimal characters.
- Use placeholders such as `192.168.x.x` and `<PORT>` in documentation. Do not turn a mock IP or port into an implied deployment default.
- Remove carousel pagination dots unless the implemented navigation actually uses pages.
- Keep all primary controls inside the 480x480 round safe area, use Wear scrolling/rotary input for the configuration form, and preserve practical touch targets.
- The app, not the user, should keep the activity awake while recording or uploading. Do not instruct the user to keep tapping the screen. This does not authorize background recording or a persistent background service.
- Haptics stay simple: start, stop, upload success, and upload failure only.

## HTTP meaning

- `201` means the PC durably accepted the upload and, in Delivery 1B, acknowledged that the external Provider run was established. It still does not prove final ASR or Paste success.
- `409` means SayIt/Provider is busy or not ready. Retain the WAV and allow retry.
- Network errors and non-2xx results retain the WAV. A retry uses a new HTTP attempt without re-recording.

## Out of scope

No formal pairing, Streaming, WebSocket, Opus, mDNS, QR code, auto-discovery, background recording, wake word, double-Home shortcut, Target Manager, Focus Tracking, Target Lock, VAD work, AI-cleanup UI, microphone sensitivity setting, About page, or major SayIt desktop UI changes.

## Acceptance for a later UI implementation slice

- On a real Galaxy Watch 7, every visible state and transition above is exercised.
- Stop automatically uploads; failure preserves the exact WAV and Retry sends it without re-recording.
- No displayed status overclaims transcription or Paste success.
- Configuration remains usable on the real 480x480 round screen without clipped controls.
- Existing Delivery 1A transport tests and release cleartext restrictions remain green.
