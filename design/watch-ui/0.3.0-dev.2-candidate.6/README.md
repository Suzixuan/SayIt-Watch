# SayIt local computer aliases — 0.3.0-dev.2-candidate.6

- **Status at freeze:** `candidate`; candidate.5 remains immutable.
- **Parent:** candidate.5 live-presence behavior and bottom-centred close layout.
- **Runtime candidate:** Watch `0.3.0-dev.12` / code 16.
- **User correction:** the earlier identity design showed a computer profile concept, but dev.11
  shipped no icon/long-press entry and no rename persistence. This candidate restores that
  expected interaction without changing discovery or authentication.

## Behavior contract

1. A pencil badge makes the left computer glyph an explicit edit target. Tapping the glyph or
   long-pressing the row opens the same full-screen customization pane.
2. A normal row tap keeps its existing meaning: explicitly switch to that authenticated computer.
3. The user can edit and save a 1–16 character local alias or restore the deterministic
   `电脑 · <IPv4末段>` fallback. Whitespace/control input is normalized before storage.
4. Aliases are kept only in app-private Watch preferences and never enter mDNS, the Bearer probe,
   upload requests, logs, or the Windows application.
5. With no authenticated stable device ID, an alias is intentionally keyed by `IP:port`. A DHCP
   address change does not silently migrate the old alias to a possibly different computer.
6. Candidate.5 live refresh, immediate pick, current/offline semantics and bottom-centred close
   remain unchanged.

## Frozen contents

- `01`–`05` are deterministic 480×480 concept renders; `04-device-profile.png` is the new
  customization pane and `05-before-after.png` compares the picker affordance with candidate.5.
- `parent-picker-candidate5.png` is the immutable visual parent used in the comparison.
- `RecordingScreen.dev12.kt`, `SettingsStore.dev12.kt`, `strings.dev12.xml`,
  `build.gradle.dev12.kts`, and `ComputerAliasPolicyTest.kt` freeze the matching runtime slice.
- Older inherited rollback/reference files retain their original contents and names.

These images are design renders, not photographic device evidence. Formal runtime resources were
changed in the dev.12 candidate; real Watch interaction remains subject to installation and device
acceptance.
