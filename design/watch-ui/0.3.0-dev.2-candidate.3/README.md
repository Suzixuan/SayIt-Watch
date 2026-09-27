# SayIt immediate-pick correction — 0.3.0-dev.2-candidate.3

- **Status at freeze:** `candidate`, approved correction of user-reported dev.8 defects.
- **Parent:** implemented `0.3.0-dev.2-candidate.2`, Watch dev.8/code 12,
  commit `38a779e`. Exact pre-change runtime source, resources, test and build file are
  included here with the real parent screenshots.
- **User evidence:** every picker entry currently waits for the full bounded search before
  it can be tapped, and the close control is partly covered by the physical round-screen edge.

## Correction

1. A candidate becomes tappable as soon as it has passed the existing Bearer-authenticated
   probe and appears in the list. A tap stops the active browse while keeping the current
   computer, then runs the existing explicit-pick confirmation probe. It never adopts an
   unverified service and never switches automatically.
2. The close action keeps its 48 dp touch target but moves left into the round-screen safe
   region. The title remains optically centred and no other card geometry changes.
3. Searching copy changes from `已验证 · 搜索结束后可选` to `已验证 · 现在可选择`.

## Files

- `01-device-picker.png` — completed-state layout with the safe close position.
- `02-searching-with-results.png` — authenticated candidate active during search.
- `05-before-after.png` — dev.8 search state beside the corrected candidate.
- `RecordingScreen.dev8.kt`, `strings.dev8.xml`, `build.gradle.dev8.kts`,
  `ComputerPickerIdentityR8Test.kt` — complete formal pre-change snapshots.
- `parent-picker-dev8.png`, `parent-searching-dev8.png` — real-device parent evidence.
- `render.py` — deterministic preview generator.
- `SHA256SUMS` — frozen artifact manifest; excludes itself.

This candidate changes neither discovery/authentication policy nor the Windows protocol.
Runtime replacement and device evidence are recorded separately after implementation.
