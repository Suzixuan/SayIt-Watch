# Ready status optical alignment — 0.3.0-dev.1-candidate.4

- Status at freeze: `candidate`; parent `0.3.0-dev.1-candidate.3` R3 real-device Ready. The parent image and current Settings menu are copied here unchanged as locked references.
- User request: move the “已连接电脑” line toward the horizontal visual center without disturbing the dial. The parent status glyphs have a measured pixel-weighted x center near 244 on a 480 px dial, even though the Text box is mathematically centered at 240. `01-ready-status-centered.png` shifts only that status line 4 px left (approximately 2 dp); its remaining pixels come from `parent-ready.png`.
- `render.py` deterministically reproduces the study from the copied parent image. `RecordingScreen.R3.kt` and `RecordingReadyEntryR5Test.R3.kt` are full pre-change snapshots for recovery.
- Inherited locks: MIC READY title, microphone, Settings entry/menu, recording/upload, current-target retention, discovery and Token behavior. No runtime resource has changed at freeze.
- Acceptance requires a real 480×480 Galaxy Watch screenshot and a comparison with both this preview and the unchanged parent. Build/tests cannot by themselves prove the optical alignment; two-PC discovery remains a separate gate.
