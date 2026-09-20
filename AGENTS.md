# SayIt Watch Transport project instructions

## Authority and current goal

- Read `HANDOFF.md`, `PROJECT_PROGRESS.md`, and the active delivery task before changing files.
- Delivery 1A and Delivery 1B stage-7 source/UI gates are PM-accepted. Delivery 1B device/ten-run acceptance remains incomplete. On 2026-09-15 the user separately unlocked the bounded Delivery 1C automatic-discovery task in `docs/DELIVERY-1C-D-AUTO-DISCOVERY-HANDOFF.md`; automatic discovery is not VERIFIED until PM device acceptance passes.
- `HANDOVER.md` documents the inherited security-hardened SayIt source. Preserve those fixes.
- `PROJECT_PROGRESS.md` is the only authoritative progress table for this project.

## Hard boundaries

- During device acceptance, do not modify product source or frozen design/baseline directories. During Delivery 1C, modify only the paths explicitly allowed by its task package. Preserve the existing AudioRelay path, ASR/Provider implementations, History, Paste, AI, target tracking, VAD, update, backup, storage-security and release HTTP behavior.
- Device acceptance remains a read-only product-source task. Delivery 1C is a separate development slice; any required work outside its allowed paths stops the task and returns to PM.
- Do not add streaming, WebSockets, Opus, QR codes, formal pairing, background recording, wake words, double-Home behavior, or a phone companion app. Standard DNS-SD/mDNS discovery is allowed only within Delivery 1C's frozen contract.
- HTTP receiver code must be debug-only, bind one explicit RFC1918 LAN IPv4, and fail closed when any required environment variable is missing or invalid.
- Never commit tokens, `.env` files, Android local paths, keystores, APK/AAB files, device serial numbers, received audio, models, installers, or build caches.

## Git and evidence

- Accepted Delivery 1A work is on `codex/review-watch-transport`. Delivery 1B history belongs to `codex/review-watch-pipeline`. The migrated checkout originally had no Git commit baseline. Its new local root commit was mistakenly pushed to a separate **private** `Suzixuan/Saylt` repository; the pre-existing user repository is the **public** `Suzixuan/SayIt-Watch` (renamed from `SayIt-watch-local` on 2026-09-20). These histories have no common ancestor. Preserve both; do not force-push, overwrite the pre-existing history, make the duplicate public, or delete either repository. Report commit SHA plus changed paths, verification, artifact hashes and unresolved risks.
- Do not merge, tag, release, force-push, or push to `crosswk/SayIt`.
- The reviewed local changes were imported on a branch from the original repository's `main` and merged via PR #4 as `b561dc6d64f28199e1ba2105a2b407ac0ccc57cb`, preserving the original history. Before claiming completion, return the commit SHA, changed paths, commands with exit codes, relevant test/build evidence, artifact hashes and unresolved risks.
- Real Galaxy Watch installation, permissions, Wi-Fi transfer, and playback evidence are production/device work and must be reported separately from source/build results.
