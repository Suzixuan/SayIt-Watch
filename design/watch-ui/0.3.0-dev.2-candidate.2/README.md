# SayIt computer identity picker — 0.3.0-dev.2-candidate.2

- **Status at freeze:** `candidate`, visual/product-contract review only.
- **Parent:** `0.3.0-dev.2-candidate.1`, whose dev.7 implementation and real two-PC
  screenshot are preserved here. The parent and current runtime remain unchanged.
- **Why this iteration exists:** a full `IP:port` is useful diagnostic data but a poor
  human identity. The new hierarchy is **friendly name → connection state → short IP**.

## Recommended screen

`01-device-picker.png` is the recommended first screen:

- one card per authenticated computer;
- friendly name and device icon are the primary identity;
- `当前` is both text and a check mark, so it is not communicated by colour alone;
- the IP remains visible in a smaller secondary line;
- the current computer stays pinned while a search runs;
- refresh is a bottom edge action; dismiss remains explicit and also compatible with the
  platform back gesture.

`02-searching-with-results.png` preserves the dev.7 safety/lifecycle rule: streamed,
authenticated candidates can appear immediately but are not selectable until the bounded
search closes. `03-unnamed-fallback.png` shows the backwards-compatible fallback when the
desktop does not yet provide metadata. `04-device-profile.png` moves the complete
`IP:port` and optional management into a secondary page.

## Proposed data contract (not implemented by this candidate)

Friendly names must never become authentication or routing identity. The preferred,
backwards-compatible response adds optional authenticated metadata:

```text
deviceId: opaque stable random ID
displayName: user-set name, e.g. "工作笔记本"
deviceType: desktop | laptop | computer
```

- Return the metadata only from the existing Bearer-authenticated discovery response.
- Keep DNS-SD/mDNS free of Windows host names, user names and custom labels.
- Route and authenticate exactly as today; `deviceId` is only a UI/preferences key.
- Old desktop builds without metadata fall back to `电脑 · <last IPv4 octet>` plus the
  short address. No automatic switch is introduced.
- Prefer renaming in the Windows app, where text entry is comfortable. The Watch can keep
  safe local preferences such as favourite, colour and order keyed by `deviceId`.

## Candidate feature set

1. **First delivery:** friendly/default names, device icon, current/online/search state,
   short address, scroll/rotary support, bottom refresh action.
2. **Small personalization:** rename on Windows, desktop/laptop icon, recognition colour,
   favourite/pin, last-used ordering.
3. **Secondary management:** full endpoint, last seen, forget local preferences. Use one
   visible management entry or one right-to-left reveal action; do not overload the card.

Explicitly excluded: exposing the Token, putting personal device names in mDNS, selecting
by nickname, formal pairing/QR, phone companion, or changing the verified explicit-pick
contract.

## Files and reproduction

- `render.py` — deterministic Pillow renderer; run `python render.py` here.
- `RecordingScreen.dev7.kt`, `strings.dev7.xml` — exact pre-change runtime snapshots.
- `dev7-real-switch.png` — real Galaxy Watch dev.7 screenshot.
- `parent-switch-ip-first.png`, `parent-ready.png` — immutable parent references.
- `RESEARCH.md` — official-source design rationale.
- `SHA256SUMS` — frozen artifact manifest; excludes itself.

These are concept/metrics renders, not runtime Compose screenshots. Formal source changes,
builds and device installation wait for explicit visual approval.
