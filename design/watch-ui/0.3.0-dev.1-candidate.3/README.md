# Watch Settings refinement — 0.3.0-dev.1-candidate.3

- Status: `candidate`; visual study only. Parent iteration: `0.3.0-dev.1-candidate.2`, whose Ready dial remains locked. The R2 real-device Settings menu is copied as `parent-settings-menu.png`; the accepted R2 Ready dial is copied as `locked-ready.png`.
- `01-settings-menu.png` is a deterministic 480×480 preview rendered by `render.py`. It replaces the oversized rectangular panel and three stacked actions with a round-screen surface, two compact equal-priority rows, a close control, and concise supporting text. It does not claim Compose/runtime or two-computer device validation.
- `RecordingScreen.R2.kt`, `strings.R2.xml`, and `RecordingSettingsMenuR7Test.R2.kt` are complete pre-change recovery snapshots. No formal runtime source was changed while preparing this candidate.
- Inherited locks: leave the accepted Ready dial, recording, authenticated target selection, current-target retention, Token/storage, discovery/network logic, and Windows receiver unchanged. Only the Settings presentation may change after visual approval.
- Before acceptance, compare a real Galaxy Watch screenshot with this preview and the locked Ready dial; test close and both actions without clearing app data. Two-computer discovery/switch acceptance remains separate.
