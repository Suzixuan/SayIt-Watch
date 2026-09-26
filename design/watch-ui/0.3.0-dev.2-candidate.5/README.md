# SayIt live computer presence — 0.3.0-dev.2-candidate.5

- **Status at freeze:** `candidate`; candidate.4 remains immutable.
- **Parent:** candidate.4 bottom-centred close layout, Watch dev.10/code 14 source candidate.
- **User request:** while the picker stays open, stopping a desktop app should remove that
  non-current computer after a bounded refresh, and starting it should add it without reopening
  the page or pressing refresh.

## Behavior contract

1. The visible picker continuously repeats the existing bounded authenticated browse; it stops on
   close, background, selection, recording hand-off, or ViewModel teardown.
2. Authenticated computers stream into the existing list immediately.
3. At the end of a cycle every visible row receives one concurrent Bearer confirmation probe.
   A non-current row that is neither discovered nor reachable is removed. This prevents an mDNS
   miss alone from erasing a live computer.
4. The selected computer remains pinned when offline and changes to `未连接`; it returns online in
   place when the app comes back. No automatic target switch or persistence occurs.
5. Candidate.4's bottom-centred 48 dp close action and immediate explicit pick stay unchanged.

The five PNGs are unchanged layout previews inherited from candidate.4. The pre-change owner,
resolver and screen sources are frozen here for rollback and review. Runtime/device evidence is
recorded separately; this candidate itself is not a device-verification claim.
