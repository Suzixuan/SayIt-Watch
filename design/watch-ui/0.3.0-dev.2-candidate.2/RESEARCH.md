# Research notes for the computer picker

Official sources checked on 2026-09-26:

- Wear OS list guidance: use a Wear-specific scrolling list with circular-screen margins;
  items scale/fade near the edge and should remain rotary-scrollable.
  <https://developer.android.com/training/wearables/compose/lists>
- Wear OS list design: keep list content centred for the round screen and separate logical
  sections clearly. <https://developer.android.com/design/ui/wear/guides/m2-5/components/lists>
- Wear OS swipe-to-reveal: reserve at most two hidden actions for list cards, with undo for
  destructive actions. <https://developer.android.com/design/ui/wear/guides/m2-5/components/swipe-to-reveal>
- Google Cast checklist: receiver selection should be simple and predictable, with distinct
  connected and available-device states.
  <https://developers.google.com/cast/docs/design_checklist>
- Microsoft device naming: custom names help users distinguish multiple computers, while
  default device names can disclose device/user hints.
  <https://support.microsoft.com/en-us/accounts-billing/manage/rename-your-windows-device>

Applied conclusion: make the human-controlled nickname the visual identity, retain network
data as secondary diagnostics, communicate current/available/searching states explicitly,
and keep personalization behind the authenticated path rather than advertising it.
