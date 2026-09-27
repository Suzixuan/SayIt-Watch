# SayIt low-power recording UI — 0.3.0-dev.3-candidate.1

- **Status at freeze:** `candidate`; visual review pending.
- **Parent design:** `design/watch-ui/0.3.0-dev.2-candidate.7/`.
- **Direct runtime reference:** `parent-recording.png`, copied unchanged from
  `docs/images/readme/watch/recording.png`.
- **Formal/runtime resources changed:** no.

## Candidate intent

1. Keep the recording screen awake for the existing reliable foreground capture path.
2. Replace the large light dial with an OLED-friendly near-black face.
3. Animate the waveform only for the first two seconds, then hold the shown static shape.
4. Publish the visible timer at 1 Hz while retaining the exact internal sample count.
5. Preserve the center stop target, cancel-and-discard action, four blue cardinal marks,
   current wording hierarchy, and automatic upload behavior.

## Frozen previews

- `01-low-power-recording.png`: full 480×480 candidate state.
- `02-one-hz-timer.png`: static waveform with two consecutive one-second timer states.
- `03-before-after.png`: current light/animated presentation versus the candidate.
- `render.py`: deterministic renderer for all three previews.

This is a visual candidate only. The timer cadence, two-second animation cutoff, battery impact,
and real-device appearance have not been implemented or measured.
