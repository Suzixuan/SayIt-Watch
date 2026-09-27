# Watch AI parity dev.15 runtime evidence

Date: 2026-09-26 (America/Tijuana)

## Regression being closed

The Watch external-ingress path used to force `disableAi: true` at both Provider start and stop. The previous real Watch record therefore had identical ASR/LLM text and `llm_ms=0`, even though the desktop `aiEnabled` setting was true.

## Runtime acceptance

- Product commit: `9d93a104ce4a64d21e420e7f71f6fb3816aed55e`.
- The fresh Debug EXE was built with the corrected embedded frontend and launched against the existing local SayIt profile.
- A previously received real Watch WAV was replayed to the same authenticated `POST /api/watch/audio` receiver. The audio and its transcript are intentionally not stored in Git.
- HTTP admission returned `201` and a new History row was created.
- New row checks: `asr_text != llm_text`; `llm_ms=568`; AI provider/model=`deepseek/deepseek-v4-flash`; duration=`2.64s`.
- This verifies an actual AI call through the Watch external-ingress path, not only mocked Provider options.

## Automated/build evidence

- Windows frontend: 31 test files, 371 tests, 0 failures; `tsc && vite build` passed.
- Watch: 28 suites, 253 tests, 0 failures/errors/skips; `lintDebug` had 0 errors; `assembleDebug` passed.
- APK manifest readback: versionName `0.3.0-dev.15`, versionCode 19, minSdk 30, targetSdk 34, required Watch/microphone/Wi-Fi features.
- ZIP: 34 entries; 33/33 payload hashes independently recomputed; isolated extracted startup and port-listener smoke passed.

## Artifact hashes

- `SayIt-Watch-0.3.0-dev.15-windows-wear-os-bundle.zip`: `437F4BDEC4DD21DD8802E83FB9E535B1860FB7FBD97FE9F416B18BB26C24E1CB`
- `SayIt-Watch-0.3.0-dev.15.apk`: `DECCC3914ED98E4575D8EB0EE4CBD88B92A4449B536705FE36551BDEF4FE2F99`
- packaged `SayIt.exe`: `664565E7ECCCE49A0E5818C9E827502BDA5FD2A635FD9D4139DFC50F33794959`

No token, credential, receiver config, received recording, transcript body, device serial, keystore, APK, EXE, ZIP or build cache is committed by this evidence note.
