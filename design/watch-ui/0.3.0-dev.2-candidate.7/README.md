# SayIt local computer aliases — 0.3.0-dev.2-candidate.7

- **Status at freeze:** `candidate`; candidate.6 is preserved as the dev.12 parent rejected by
  real-device touch/layout evidence.
- **Runtime candidate:** Watch `0.3.0-dev.13` / code 17.
- **Parent:** `design/watch-ui/0.3.0-dev.2-candidate.6/`.

## Real-device correction

1. dev.12 nested the glyph click inside the row's `combinedClickable`; on Galaxy Watch the parent
   consumed the tap, so the visible pencil did not open customization.
2. dev.12 placed bottom padding inside the scroll content rather than outside its viewport. With
   two rows, the fixed close control covered the refresh action; the same pattern initially covered
   the customization field.
3. dev.13 splits the glyph and row body into independent touch regions: glyph tap edits, body tap
   switches, body long-press edits. Both scroll viewports are constrained above the fixed close
   control.

## Verified device behavior

- `03-dev12-overlap-rejected.png`: rejected dev.12 overlap.
- `04-dev13-picker-safe.png`: two live PCs, edit affordances, and unobstructed bottom close.
- `05-dev13-customize-safe.png`: customization page with its close control below the clipped safe
  scroll viewport rather than over the field.
- `06-dev13-name-edit.png`: tapping the name field opens the edit dialog.
- Glyph tap opened `.142`; long-pressing the `.153` row body opened `.153` without switching the
  current `.142` target. Samsung T9 text entry itself remains for user manual acceptance.

The two concept renders are inherited references only; the four real-device captures are the
authoritative layout evidence. Source snapshots and the alias-policy test match the dev.13 runtime.
