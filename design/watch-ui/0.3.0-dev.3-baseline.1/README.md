# SayIt recording UI formal baseline — 0.3.0-dev.3-baseline.1

- **Status at freeze:** `baseline`; immutable pre-change snapshot.
- **Runtime:** Watch `0.3.0-dev.15` / code 19.
- **Visual parent:** `design/watch-ui/0.3.0-dev.2-candidate.7/`.
- **Next candidate:** `design/watch-ui/0.3.0-dev.3-candidate.2/`.

## Frozen formal state

- `RecordingScreen.dev15.kt` and `RecordingViewModel.dev15.kt` are exact pre-change source copies.
- `recording-reference.png` is the deterministic current recording preview.
- `recording-watch7.png` is the retained Watch7 runtime capture.

## Battery baseline

The installed dev.15 recording screen was measured for 165 seconds with the Watch disconnected
from power, fixed brightness 98, rotation locked at 0°, and no media playback. The run was ended
through Cancel, so its captured audio was discarded and History stayed at 81 records.

- Charge counter: 198,232 µAh → 195,108 µAh.
- Measured drain: 3,124 µAh / 3.124 mAh over 165 seconds.
- Battery temperature: 32.6 °C → 34.7 °C.
- Raw samples: `battery-baseline-dev15.csv`.

The charge counter updates in roughly 568 µAh steps on this device. This single run establishes a
same-device baseline, not a laboratory-grade battery claim.
