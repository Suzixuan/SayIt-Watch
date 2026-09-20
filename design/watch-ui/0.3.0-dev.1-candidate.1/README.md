# Watch Settings navigation — 0.3.0-dev.1-candidate.1

- Status: `candidate`; frozen placement study. The user asked to keep the original minimal Ready dial and delegated the Settings placement decision to PM. This is not yet a real-device acceptance.
- Visual parent: `design/watch-ui/0.2.0-dev.3-final/ready.png` (copied as `parent-ready.png`). Current pre-change R6 runtime and source are preserved as `runtime-R6-ready.png`, `RecordingScreen.R6.kt`, and `strings.R6.xml`.
- `01-ready.png`: original approved white dial, microphone and low-key Settings entry, with only the already-accepted neutral transport status added. No large switch button on Ready.
- `02-settings-menu.png`: a small, dismissible menu opened by Settings. It has `切换电脑` (or `搜索电脑` when there is no current target) and `连接设置`. The first uses the existing authenticated switch picker and bounded browse; the second opens the existing Token/manual-address page.
- `render.py` deterministically renders both 480×480 placement studies from the preserved parent screenshot. These are illustrations, not Compose screenshots; the real Watch rendering and interaction must be checked after implementation.
- Locked: Recording screen, white dial and tick marks, microphone action, status truthfulness, Token/authentication policy, switch selection policy, original target retention, manual fallback, upload/ASR paths.
- Scope for runtime change: `RecordingScreen.kt`, necessary UI strings and tests only. Do not change `RecordingViewModel.kt`, discovery/network code, credentials, or app data.
- Before showing this candidate, inspect both full-size previews and compare with `parent-ready.png`/`runtime-R6-ready.png`; do not overwrite this folder in later iterations.
