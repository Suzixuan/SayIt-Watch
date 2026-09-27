# SayIt bottom-centred close correction — 0.3.0-dev.2-candidate.4

- **Status at freeze:** `candidate`; supersedes but does not overwrite candidate.3.
- **Parent:** candidate.3 / installed Watch dev.9/code 13.
- **User evidence:** moving the close control left did not solve the physical round-screen
  obstruction. The user requested that it be placed below and centred.

## Correction

1. Remove the close control from the narrow top-right arc entirely.
2. Keep its 48 dp touch target fixed at the bottom centre with an 8 dp bottom inset.
3. The list and restart action scroll independently with 60 dp bottom content padding, so the
   fixed close action does not hide a selectable computer.
4. Preserve candidate.3's immediate authenticated-candidate selection behavior unchanged.

`RecordingScreen.candidate3.kt` and `parent-picker-candidate3.png` freeze the rejected parent.
The generated previews show the corrected bottom-centred action. They are deterministic layout
renders, not device screenshots. Runtime and device verification are recorded separately.
