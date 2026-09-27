# 1C-PM-UI-02@R5 device interaction repair evidence

Date: 2026-09-26. Candidate: Watch `0.3.0-dev.13` / code 17.

## Correction

- The computer glyph and row body are independent touch regions. Glyph tap edits; body tap
  switches; body long-press edits.
- Picker and customization scroll viewports end above the fixed bottom-centred close action, so
  content can scroll without being covered.
- Alias storage, normalization, discovery, authentication and live-presence behavior are unchanged
  from dev.12.

## Device verification

- Data-preserving install returned Success; the device read back dev.13/code 17.
- Two authenticated computers were visible: current `.142` and selectable `.153`.
- Tapping the `.142` glyph opened `.142` customization.
- Long-pressing the `.153` row body opened `.153` customization without changing current `.142`.
- Tapping the name field opened the edit dialog and Samsung input method.
- `sayit-dev13-picker-final.png` shows the two rows and the close action in separate safe regions.
- `sayit-dev13-customize-scrolled.png` shows the customization scroll viewport clipped above the
  fixed close action; `sayit-dev13-name-edit.png` shows the edit dialog.
- A cold-start coordinate sequence briefly entered Recording; the visible Cancel action was used
  immediately. Stop was not pressed and no upload was initiated.

Manual acceptance remains for entering a real nickname with Samsung T9, saving it, observing the
picker update, and restoring the fallback. ADB text injection did not reliably drive that IME, so
this is not represented as passed.

## Final verification

- Full `testDebugUnitTest --rerun-tasks lintDebug assembleDebug`: exit 0.
- Tests: 27 suites / 246 tests / 0 failures / 0 errors / 0 skipped.
- Lint: 0 errors / 38 warnings.
- Final APK SHA-256: `174BAEC8F03A00FB215281473DC855DC77D63DFF3C7E7461F2F5714C2F1BA265`.
- `git diff --check`: exit 0.
- Frozen candidate: `design/watch-ui/0.3.0-dev.2-candidate.7/`; manifest recomputation: 0 failures.

Evidence SHA-256:

```text
488F885EF9862384BB74A8C16248767E68D3A6844486EF9A8E6A86C2E88FC540  sayit-dev13-picker-final.png
DD0D8798084A779FE8AE116E67EA186E2DC1E4A0BB956D81F92954CBC9A1C940  sayit-dev13-customize-scrolled.png
B87F3FA440D2F5BF0D9F9ED70258D7B108021E223EF47B38698CA36C1621BF4D  sayit-dev13-name-edit.png
```

No Windows receiver, discovery/authentication contract, target verification, live presence loop,
recording/upload, Provider, ASR, History, Paste, credentials, Release, push, merge, or tag changed.
