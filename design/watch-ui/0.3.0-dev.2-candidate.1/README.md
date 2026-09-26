# 1C-D-04@R7 visual candidate — 0.3.0-dev.2-candidate.1

- **Status at freeze:** `candidate`, awaiting PM visual/device acceptance. The parent is
  `0.3.0-dev.1-candidate.4` (the accepted `1C-PM-UI-01@R4` Ready dial). The parent Ready
  image is copied here as `parent-ready.png` and is **not** modified: R7 keeps the Ready
  dial, the microphone, the low-key Settings entry and the whole approved menu intact.
- **User request this round:** the switch page must always show the *real* current
  `IP:port`, must stop showing the long fixed "these computers have all been verified"
  paragraph, and an explicit switch must never change the computer on its own.

## What changed on screen

| Area | Before (R4/R5/R6) | After (R7) |
|---|---|---|
| Switch page header | title `选择电脑` + long paragraph `以下电脑都已验证通过。点选要使用的那一台；不需要手动输入地址。` | section `当前电脑` + the real `IP:port`, or `未连接` when nothing is usable; hint reduced to `点选要使用的那一台` |
| Current computer | only appeared as a row when it was also in the discovered list | always pinned at the top, even when it is not in the list |
| Row labels | `当前使用` / `已认证电脑` | `当前使用` / `可选电脑` |
| Search state | separate `正在搜索其他电脑…` block below the list | one state line under the header: `正在搜索…` / `未找到电脑` / the pick hint |
| Actions | `搜索其他电脑` + `取消` (disabled while searching) | `重新搜索` + `返回` (restarting the search is allowed: the newest intent wins) |
| Single new candidate | adopted automatically by the browse | **offered**, and only the user's tap switches |
| Ready dial | — | unchanged (no new big controls; R5 必修 3 stays satisfied) |

## Files

- `RecordingScreen.R7.kt` — full snapshot of the runtime UI file this candidate describes.
- `strings.R7.xml` — full snapshot of the string resources (`discovery_found` was removed:
  it had no production call site, and the Ready line never claims a discovery).
- `ConnectionRecoveryR7Test.kt` — the production-chain regression suite that drives the real
  `RecordingViewModel` → `ResolverBridge` → `DiscoveryCoordinator` chain for the recovery,
  retry, health-check, settings and explicit-pick behaviour.
- `render.py` — deterministic re-render of the previews below.
- `parent-ready.png` — the accepted R4 Ready dial, unchanged.

## Previews — how faithful they are

`render.py` draws each state from the **exact dp values** in `ComputerSwitchDialog` and the
**exact strings** in `strings.R7.xml`, on a 480×480 canvas at the Watch's nominal
`226dp` logical width. This is a **metrics render, not a runtime screenshot**: it makes a
layout/wording regression visible, but it cannot prove the runtime Compose layout, the
circular safe area, or text clipping on the real panel.

Therefore the acceptance for this candidate is:

1. a real 480×480 screenshot from the Galaxy Watch for at least the two-computer and the
   no-candidate states, compared against `01-switch-two-computers.png` and
   `04-switch-no-candidate.png`;
2. confirmation that the Ready dial is pixel-identical to `parent-ready.png`, as measured in
   the earlier rounds.

`05-switch-not-connected.png` documents the wording rule that a stored address may **not**
be presented as a live connection: with no usable target the current-computer line says
`未连接`. `02-switch-not-found.png` shows the empty result case.

The single-candidate rule (exactly ONE new computer is still only offered, never adopted) is
asserted by `ConnectionRecoveryR7Test` and by `DiscoverySwitchFlowR3Test`; it is not a
separate image, because its screen is identical to `01-switch-two-computers.png`'s candidate
list — only the number of rows differs.

No resource has been deployed and the Watch app is not installed by this freeze. Two-PC
discovery/switching and the recording-to-text chain remain PM device-verification gates.
