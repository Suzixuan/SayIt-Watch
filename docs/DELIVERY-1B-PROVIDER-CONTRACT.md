# Delivery 1B — Provider Contract (revision 3 + Z2 runtime resolution)

- Author: colleague Z (Z1 contract gate).
- Status: PM accepted as **MATCH** after independently accepting the Z2 packaged-Windows binary-IPC spike. The next unlocked slice is the PC-only external WAV ingress in `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`; Watch UI remains locked.
- Branch: `codex/review-watch-pipeline`. Review base of this revision: `dd7efb233f3ab85a85c740cecbfe644078cc7827`. Revision 2 (`0d62130`) answered Repair 1 (`docs/DELIVERY-1B-Z-CONTRACT-REPAIR-1.md`); revision 3 answers Repair 2 (`docs/DELIVERY-1B-Z-CONTRACT-REPAIR-2.md`) and changes **only** the final-chunk sample accounting (§B.4, §B.5 steps 7-9, §C.1, §E row 7, §G). Every other Repair 1 decision of revision 2 is carried over verbatim.
- All line references are relative to the repository root at `dd7efb2` (source lines cited in revision 2 were verified unchanged between `8240d4a` and `dd7efb2`; those two commits touch documentation only). Pinned-dependency references cite the exact locked sources: `tauri 2.10.3` (`client/src-tauri/Cargo.lock`) resolved at `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-2.10.3/`, and `@tauri-apps/api 2.10.1` (`client/node_modules/@tauri-apps/api/`).
- **Revision-2 corrections** (mapping table in §G): truthful per-run Provider lifecycle (Repair 1.1), two-phase sync/async reservation API (Repair 1.2), correlated two-sided abort (Repair 1.3), Rust-owned lease replacing the unsafe JS watchdog (Repair 1.4), post-save acknowledgement before `201` (Repair 1.5), explicit external audio accounting (Repair 1.6), and the then-conditional binary-IPC gate (Repair 1.7). Z2 resolved that condition at runtime.
- **Revision-3 correction** (Repair 2): exact final-chunk sample accounting — the feed loop counts every chunk by its actual `byteLength / 2` (never the configured maximum chunk size), asserts the totals against the admission metadata, and aborts before `provider.stop` on any mismatch (§B.4, §B.5 steps 7-9, §E row 7).
- **2026-09-26 product correction**: the user confirmed that Watch recordings must use the same AI-cleanup behavior as microphone recordings. This supersedes the historical Delivery 1B instruction to force `disableAi: true`; the original task document is retained unchanged as audit history. The external path now follows `aiEnabled`, prompt routing, context-aware writing, and `aiMinDurationSec` without changing the stored user preference.

## 0. Verdict

**MATCH.**

All six proposed audio-contract clauses remain confirmed by two-sided source evidence (§A.3), the `409` admission boundary is fully specified without a second ASR path, without refactoring `RecorderOrchestrator`, and without any new focus-tracking machinery, and every abort path now closes on both sides of the bridge.

The former PCM-transfer condition (Repair 1.7) is resolved. In the packaged Windows debug WebView2 runtime, pinned Tauri delivered a deterministic 9,438,418-byte `tauri::ipc::Response::new(Vec<u8>)` payload to JavaScript as an identical raw `ArrayBuffer`; the full SHA-256 and sentinels matched, and no `customProtocolIpcFailed`/postMessage fallback warning appeared. PM independently rebuilt and reran the native harness before accepting Z2. An undocumented base64 fallback for the full 10 MiB body remains explicitly **not authorized**.

Per-clause verdicts:

| # | Proposed contract clause | Verdict |
|---|---|---|
| 1 | Raw PCM only, no WAV header, into `sendAudio` | **CONFIRMED** (capture path sends headerless PCM; the Watch WAV header is stripped exactly once, in Rust, during ingress — §A.3, §B.4) |
| 2 | 16,000 Hz | **CONFIRMED** (capture and receiver-side WAV validation both enforce it) |
| 3 | Mono | **CONFIRMED** (both sides enforce it) |
| 4 | Little-endian signed i16 | **CONFIRMED** (both sides enforce it) |
| 5 | `ArrayBuffer` chunks | **CONFIRMED** at the Provider interface and by the accepted packaged-Windows Z2 runtime proof (§B.4) |
| 6 | Duration derived from `bytes / 2 / 16000` | **CONFIRMED**, and now frozen as the external run's duration source (§B.5) |

---

## A. Required source evidence

### A.1 `RecorderOrchestrator` singleton and run lifecycle

- Module-level singleton: `client/src/services/recorder.ts:5` (`let orchestrator = new RecorderOrchestrator()`); exactly one orchestrator per WebView process.
- Active provider is a module-level singleton: `client/src/services/transcription/index.ts:13-14`, lazily created by `getProvider()` (31-36), replaced wholesale by `switchProvider()` (48-77). The orchestrator reaches it via `get provider()` (`RecorderOrchestrator.ts:204`).
- Run identity: `runSequence`/`activeRunId` (`RecorderOrchestrator.ts:126-127`); `runId = ++this.runSequence; this.activeRunId = runId` (1351-1352). `isRunCurrent(runId)` (258-260) guards every async boundary.
- Idempotent teardown: `finishRun(runId)` (262-275) is the single terminal funnel — reached from cancel recording (834), cancel processing (881), `onDone` (1060), `onError` (1149), paste success (1262), fallback card (1298), empty final (2361), and the timeout-fallback history write (1990, 1995).
- State machine: `'idle' | 'recording' | 'processing'`, transitions `idle→recording`, `recording→processing`, `recording→idle`, `processing→idle` (`recorder/types.ts:1`, `RecorderOrchestrator.ts:74-85`, enforced at 655-668).
- Run creation (`startRecording`, 1317-1648): synchronous preconditions at entry — `state === 'idle'`, `!startRecordingLock`, `!textInsertionInFlight`, `finalizingLateRunId === 0` (1318-1331); pre-flight `provider.isReady()` (1334, not-ready → error card, no run); leftover timed-out generation discarded with `provider.cancel()` (1344-1349); runId assigned (1351-1352).
- Run finalization (`stopRecording`, 1801-2003): waits capture setup (max 3 s, 1811-1819), `stopCapture()` (mic-only, 1825), discards audio < 0.5 s via `provider.cancel()` (1858-1865), `provider.stop({...})` (1876-1886), `transition('processing')` (1894), `processingCancelable = true` (1899), processing timeout armed (1901, 1907-2002).
- Timeout: `computeProcessingTimeoutMs` (`recorder/helpers.ts:108-132`) = 15 s base + 500 ms/audio-second capped at +30 s; ≥30 s floor for non-server modes; cloud API clamped ≤90 s. On timeout: `TimedOutProcessingContext` snapshot (1913-1922), `resetToIdle({ preserveLateFinalContext: true })` (2001), 15 s late-final grace (`LATE_FINAL_GRACE_MS`, line 87), then the grace-fallback history write (1935-1999).

### A.2 `TranscriptionProvider` contract — real order and return semantics (corrected in revision 2)

Interface: `client/src/services/transcription/types.ts:80-103`.

**The true per-run lifecycle observed in `startRecording` is: pre-flight `isReady()` → per-run `connect(fresh callbacks)` → `start()` (checked) → `sendAudio*` → `stop()`.** Revision 1 froze this incorrectly as `connect → isReady → start`; the source says:

1. `provider.isReady()` pre-flight at `RecorderOrchestrator.ts:1334` (before any run state is touched).
2. `await Promise.all([this.provider.connect(this.buildProviderCallbacks()), startCapture(...)])` at 1521-1572 — **`connect` is invoked every run with a fresh callbacks object**; the init-time preconnect (`ensureConnection`, 1307-1313) does not remove the per-run connect. `connect` resolves when the transport is ready (`ServerProvider.ts:18-65`) and `BufferedProvider.connect` lets a subclass veto readiness — a falsy hook result leaves `isReady() === false` (`BufferedProvider.ts:30-49`). Failure rejects; `ensureConnection` retries every 5 s outside a run.
3. `const started = this.provider.start(promptOpts); if (!started) throw new Error('sendStart failed')` at 1580-1583 — `start` is a synchronous boolean send (`types.ts:87`; `ServerProvider.start` records `activeRunId = opts.runId` and resets it on send failure, `ServerProvider.ts:67-72`). **No audio is sent before `start` returns true**; a false return lands in the catch path (1629-1647) which stops capture, cancels the provider, finishes the run and resets to idle.
4. `sendAudio(buffer: ArrayBuffer)` fire-and-forget per captured chunk (`types.ts:92-93`), driven by the capture `onData` callback with the `isRunCurrent` guard (1526-1534).
5. `provider.stop({...})` only after capture has stopped and audio ≥ 0.5 s (1858-1886); `StopOptions` may append `disableAi` — it can only turn AI off, never on (`types.ts:58-73`).
6. `cancel(): void` — immediate session teardown valid in any state (`types.ts:89-90`); the server provider zeroes `activeRunId` so late `onASR/onFinal/onDone/onError` are dropped at the provider boundary (`ServerProvider.ts:32, 40, 54, 60, 74-77`); server-mode cancels are followed by `ensureConnection()` (`RecorderOrchestrator.ts:854, 895, 1863, 1942`).
7. `disconnect(): void` — full teardown (`BufferedProvider.ts:81-82` also cancels).
8. `isReady(): boolean` — synchronous: server = WebSocket connected (`ServerProvider.ts:92-94`), cloud = cached flag (`CloudAPIProvider.ts:263-265`), local = model selected and downloaded (`LocalProvider.ts:29-47`).

Consequence frozen for the external run (Repair 1.1): the external path **must** perform steps 2 and 3 — `await provider.connect(this.buildProviderCallbacks())` and a checked `provider.start(StartOptions)` — before the first `sendAudio`. A reservation alone starts nothing.

### A.3 Audio contract — confirm/reject from source evidence

**CONFIRMED in full.** Provider input is raw PCM, 16,000 Hz, mono, little-endian signed i16, as `ArrayBuffer` chunks, duration = `bytes / 2 / 16000`.

Capture side (`client/src/services/audio.ts`): `TARGET_SAMPLE_RATE = 16000` (21); the AudioWorklet resamples to that rate (72-124) and converts Float32 → Int16 with saturation (127-131), posting the transferable `int16.buffer` (133); mono — only `inputs[0]?.[0]` is read (80), fallback `createScriptProcessor(4096, 1, 1)` (291), source `channelCount: 1` (524); duration from bytes — `(totalPCMBytes / 2) / 16000` (340, 469), orchestrator `audioSentSamples / 16000` (`RecorderOrchestrator.ts:738-740`, samples accumulated at 1539).

Receiver side (`client/src-tauri/src/watch_receiver/wav.rs`): the 1A validator enforces PCM format 1 (120-122), mono (117-121, `CHANNELS = 1` line 18), 16,000 Hz (123-125, `SAMPLE_RATE` line 17), 16-bit (126-128), block align 2 / byte rate 32000 (129-136), non-empty even-length data chunk (140-149); `sample_count = data_size / 2`, `duration_ms = sample_count * 1000 / 16000` (152-154).

Consequence: a validated `received_watch.wav` data chunk **is** a valid `sendAudio` payload byte-for-byte; the only transformation is dropping the RIFF container once, in Rust. No resampling, conversion, or channel folding on the PC.

### A.4 Callback association and stale-result rejection

One callbacks object per run is built by `buildProviderCallbacks()` (`RecorderOrchestrator.ts:900-1154`) and supplied on every `connect` (1522); the server provider additionally filters callbacks through its own `activeRunId !== 0` check (`ServerProvider.ts:32-62`). Generation guards: `onPartialASR` only while `recording` (902-906); `onASR` (empty terminal) only `processing`, no final handled, current run (908-916); `onFinal` only `processing` (997) or the late-final grace path via `consumeTimedOutProcessingContext` (2196-2207, 15 s check at 2204), duplicates rejected via `finalHandledInCurrentRun` (1021-1024); `onDone` only `processing`, no final, no insertion in flight (1054-1057); `onError` requires `isRunCurrent` and `recording|processing` (1064-1069). Cancellation leaves the state and invalidates the run **before any await** (825-835, 874-881).

### A.5 Normal final → History → Paste (including the insertion probe)

`onFinal` → `processFinalResult` (`RecorderOrchestrator.ts:2209-2384`): generation guard (2215); context-aware output resolution (2222-2227); History first — subject to `historyEnabled` (2274) and `audioRetentionEnabled` (2279), audio chunks are saved via `saveRecordingAudio` from `context.audioChunks` (2281-2288) and one `addHistory` record written (2301-2328; `services/store.ts:326`); `history-updated` emitted (2333); then insertion — `textInsertionInFlight = true` (2370) → `handleTextInsertion` (2372 → 1158-1281), which uses **the probe snapshot carried by the run context** (`options.probeResult`, 1180-1181; captured once per run at `startRecording` via `captureActiveInsertionTarget` + `bridge.getRecordingContext` into `cachedProbeResult`, 1369-1391) and falls back to a live probe only if no snapshot exists (1181). Non-editable → fallback card (1224-1236, 1287-1303); editable → modifier settle wait (1239), `processingCancelable = false` + Esc disabled (1242-1246), `pasteService.pasteText(text, probe, cachedProtectClipboard)` (1250; `PasteService.ts:54-71` → `bridge.pasteText`, `bridge.ts:112`); success → `finishRun` + idle (1262-1265); failure → fallback card (1271-1280).

### A.5b Synchronous vs asynchronous capture APIs (evidence for the two-phase design)

- `captureActiveInsertionTarget(...): TextInsertionAttempt` is **synchronous** — `textInsertion.ts:316-321` delegates to `captureResolvedTarget` (248-276), which is pure DOM: `getDeepActiveElement()` → `resolveInsertionTarget` → `storeCapturedTarget`, no `await`, no IPC.
- `bridge.getRecordingContext(includeTextContext)` is **asynchronous** — `bridge.ts:127-132` returns `invoke<{ appContext, probe }>('get_recording_context', ...)`. It cannot complete inside a synchronous function. The orchestrator awaits it at `RecorderOrchestrator.ts:1377`.
- `resolvePromptRouting(...)` is synchronous over cached settings — called without `await` at `RecorderOrchestrator.ts:1399-1407`.

This is the factual basis for Phase A / Phase B in §B.2: revision 1 wrongly claimed both captures inside a fully synchronous reserve.

### A.6 State inventory an external run must respect

| State | Meaning / evidence | External-run obligation |
|---|---|---|
| `idle` + provider ready + no reservation | `RecorderOrchestrator.ts:1318, 1334` | Only state from which a run may be created. |
| busy (not ready) | `startRecording` refuses when `!isReady()` (1334-1340) | `409` `provider_not_ready`. |
| busy (not idle) | `recording`/`processing` reject PTT-down (417-425); `startRecording` also refuses while `startRecordingLock`, `textInsertionInFlight`, `finalizingLateRunId` (1318-1331) | `409` `orchestrator_not_idle`. |
| **reservation held (new, pre-run)** | Between admission and `transition('recording')` the state machine is still `idle`, so the **reservation**, not the state, is what blocks PTT. The design adds reservation checks to both `startRecording` (PTT down) and `stopRecording` (PTT up) entry guards — §B.2, §B.6 | PTT down/up are no-ops for the whole reservation lifetime. |
| `recording` | Between `start` and `stop` (1588, 1894) | External run occupies it during ingest; **must not call mic-only `stopCapture()`** (§B.5). |
| `processing` | After `stop` until final handling; Esc-cancelable while `processingCancelable` (1899, 858-896); permanently closed before system paste (1244) | Reused verbatim, including thinking overlay and Esc. |
| cancellation | `cancelRecording` (805-856) / `cancelProcessing` (858-896); canceled-generation artifacts discarded (795-803) | External aborts route through the same paths plus the correlated release (§B.6). |
| late final | 15 s grace (997-1019, 2196-2207); expiry → provider session discarded + fallback history entry (1935-1999) | No change; gate stays reserved until the grace path reaches `finishRun`. |
| timeout | `helpers.ts:108-132`, armed at 1907; insertion in flight extends (1909-1912) | No change; timeout input is the external run's sample-derived duration (§B.5). |
| text insertion | `textInsertionInFlight` blocks new runs and `onDone` (1318-1331, 1057) | Reused verbatim. |

### A.7 AI-cleanup parity for microphone and Watch recordings

- Setting key **`aiEnabled`**: read at `RecorderOrchestrator.ts:572` into `cachedAiEnabled` (175, 590); written only via `stores/aiEnabled.ts` (`setAiEnabled` → `setSetting('aiEnabled', next)` + `setAiEnabledCache`); per-run short-speech skip via `aiMinDurationSec` (177, 591, 1873-1875).
- How a run disables AI today: `StartOptions.disableAi` (`transcription/types.ts:44`) computed as `disableAi: !this.cachedAiEnabled` (1493, 1504); when AI is off, `systemPrompt` is deliberately omitted (`systemPrompt: this.cachedAiEnabled ? … : undefined`, 1492); server mode may append `disableAi` at stop (`StopOptions.disableAi`, types 65; orchestrator 1876-1886) because the server runs AI right after ASR.
- External runs use `disableAi: !cachedAiEnabled`, pass the resolved `systemPrompt` only when AI is enabled, and apply the same short-speech `aiMinDurationSec` stop override as microphone runs. The user's `aiEnabled` preference is read but never modified or persisted by the run.

---

## B. Required boundary design — race-safe `409` admission between the blocking Rust receiver and the frontend Orchestrator

### B.1 Topology and surface

The authoritative busy/ready state lives in the WebView; the receiver is a blocking tiny_http accept loop on its own thread (`watch_receiver/mod.rs:21-44`, `server.rs:45-49`). The smallest synchronous decision channel is one admission request across the existing Tauri event/IPC bridge, decided atomically in JS (Phase A) and completed only after asynchronous preparation succeeds or fails closed (Phase B). All new Rust code is debug-only under the same `#[cfg(debug_assertions)]` gate as the receiver.

Components:

1. **Rust `AdmissionGate`** (`watch_receiver/admission.rs`, new): `Arc<Mutex<GateState>>`, `Idle | Reserved { request_id: String, reserved_at: Instant }`, plus the two pending oneshot slots (admission, ack). Registered as managed Tauri state, shared by the receiver thread and all commands.
2. **Events Rust→JS** (3): `watch://admission-request { requestId, bytes, sampleCount, durationMs }`; `watch://audio-ready { requestId }` (after durable save); `watch://run-abort { requestId, reason }` (receiver-initiated abort).
3. **Commands JS→Rust** (5):
   - `watch_admission_resolve(requestId, accepted, reason?)` — completes the admission oneshot; `accepted=false` moves the gate to `Idle`.
   - `watch_run_started(requestId)` — the post-save acknowledgement; completes the ack oneshot → receiver responds `201`.
   - `watch_run_aborted(requestId, reason)` — JS-initiated correlated abort: completes any pending oneshot with failure (→ non-2xx HTTP), moves the gate to `Idle`.
   - `watch_read_reserved_pcm(requestId)` — returns the saved WAV's data chunk as raw bytes via binary IPC (§B.4) plus `{ sampleRate: 16000, channels: 1, bitsPerSample: 16, sampleCount }`.
   - `watch_gate_state()` — boot-time reconciliation (reload recovery, §B.7).
4. **Additive orchestrator surface** (`RecorderOrchestrator`): `tryReserveExternalRun(requestId): boolean` (Phase A, synchronous), `prepareExternalRun(requestId): Promise<boolean>` (Phase B), `beginExternalRun(requestId, pcm: ArrayBuffer): Promise<void>` (connect → start → feed → finalize), `abortExternalRun(requestId, reason)`; one reservation guard clause in `startRecording`'s existing entry block **and** one in `stopRecording`'s entry; one `finishRun` hook releasing the reservation.
5. **JS ingress module** (`services/watchIngress.ts`, new, thin): event listeners, command wrappers, boot-time `watch_gate_state()` reconciliation. No watchdog timer anywhere in JS (Repair 1.4).

### B.2 Two-phase reservation (Repair 1.2) — truthful sync/async split

**Phase A — `tryReserveExternalRun(requestId): boolean`, fully synchronous and atomic.** No `await`, no IPC. Checks, in one task: `state === 'idle'`, `!startRecordingLock`, `!textInsertionInFlight`, `finalizingLateRunId === 0`, `provider.isReady()`, `externalReservation === null`. On success: `runId = ++runSequence`, `activeRunId = runId`, `externalReservation = { requestId, runId }`, and the synchronous in-process capture `captureActiveInsertionTarget(undefined, { preserveExistingOnFailure: true })` is stored (legitimately synchronous — `textInsertion.ts:248-276, 316-321`). Atomicity: the WebView event loop is single-threaded, so no PTT event can interleave between check and reservation; conversely the new reservation guard in `startRecording`'s existing synchronous entry block (1318-1331) blocks PTT-down for the whole reservation lifetime, and the same guard in `stopRecording`'s entry (1802) blocks PTT-up from hijacking the external run's `recording` phase. `pttToggle` delegates to those two guarded methods, so no third guard is needed.

**Phase B — `prepareExternalRun(requestId): Promise<boolean>`, asynchronous, while the reservation stays held.** Bounded at 3 s (`Promise.race` against a timeout — the race loser is not a watchdog, it is a one-shot bounded await, and its failure is an abort, not a release-and-hope):

1. `const includeTextContext = cachedContextAwareWriting && cachedAiEnabled`; then `await bridge.getRecordingContext(includeTextContext)` — the native one-shot focus/app-context probe, invoked **exactly once** for the run. Editor text is read only when both AI cleanup and context-aware writing are enabled, matching the microphone privacy boundary.
2. Store `ctx.appContext` → `currentActiveAppContext`, `ctx.probe` → `cachedProbeResult` (the same fields mic runs use, so the `onFinal` and timeout snapshots need zero changes), and `resolvePromptRouting({...})` → `currentPromptResolution`. When usable editor context exists, wrap the resolved system prompt with the same context-aware instructions as the microphone path.
3. After every `await`, re-verify `externalReservation?.requestId === requestId && isRunCurrent(runId)`; any mismatch → correlated abort (§B.6).

**The receiver's admission oneshot is completed only when Phase B resolves.** Success → `watch_admission_resolve(accepted=true)`. Failure or 3 s timeout → `watch_admission_resolve(accepted=false, reason)` **and** the JS-side half of the correlated abort. Documented HTTP result for capture failure: the receiver answers **`500 {"error":"admission failed","detail":"context_capture_failed"|"context_capture_timeout","requestId":…}`** — not `409`, which stays reserved for busy/not-ready; both are non-2xx so the Watch retains its WAV for retry, and no disk write has occurred, so the previously accepted file is intact. A silent fallback to a later live probe is forbidden — it would violate the upload-arrival focus snapshot.

### B.3 Corrected `handle_audio` order (Repairs 1.1, 1.2, 1.3, 1.5)

1. Bearer auth → `401` (unchanged, `server.rs:75-78`).
2. `X-Request-Id` UUID → `400` (unchanged, 82-105).
3. Content-Type `audio/wav` → `400` (unchanged, 108-120).
4. 10 MiB cap → `413` (unchanged, 124-148). `MAX_RECORDING_SEC = 300 s` (`recorder/types.ts:53`) × 32,000 B/s = 9.6 MiB < 10 MiB.
5. WAV validation → `400` (unchanged, 154-164; guarantees §A.3).
6. **Admission** (new): emit `watch://admission-request`, block on the oneshot ≤ **5 s**. Outcomes:
   - rejected + reason `orchestrator_not_idle` | `provider_not_ready` | `already_reserved` → **`409 {"error":"busy","detail":…,"requestId":…}`**; gate stays/returns `Idle`.
   - rejected + reason `context_capture_failed` | `context_capture_timeout` → **`500`** (§B.2); gate `Idle`.
   - oneshot timeout (WebView dead or stuck) → **`409 {"error":"busy","detail":"bridge_timeout"}`**; gate `Idle`; fail-closed, never 201.
7. **Durable atomic save** → `500` on failure (unchanged, 166-175) **plus receiver-initiated correlated abort** (`watch://run-abort {requestId, reason:"save_failed"}` + gate → `Idle`) so the JS reservation and runId are also cleared — Repair 1.3's original gap (release-only-on-Rust-side) is closed here and everywhere else by §B.6.
8. **Post-save handoff + acknowledgement** (new, Repair 1.5): emit `watch://audio-ready {requestId}` (safe to emit before responding — the file is `sync_all`-ed by `save_atomically`, 239-271), then block on the ack oneshot ≤ **10 s**. On `audio-ready` the WebView runs `beginExternalRun` (§B.5) through `provider.start` success, then invokes `watch_run_started(requestId)`. Only then does the receiver respond **`201`** with the existing metadata body (179-193). Any preparation failure (PCM read, sampleCount mismatch, connect failure/timeout, `start` false/throw) or ack timeout → `watch_run_aborted` / receiver-initiated abort → **non-2xx** (`500 {"error":"run failed to start","detail":…}` or `500 {"error":"run start ack timeout"}`) so the Watch retains its WAV, and both sides abort. **`201` therefore means: transport + validation + durable save + admission + provider session established. It still does not mean transcription completion.** The impossible-in-revision-1 case "Watch received 201 but ASR never started" is excluded by construction.
9. Post-`201`, the run proceeds independently of HTTP: feed → stop → `processing` → existing callbacks → History → Paste (§B.5, §C.1/C.5).

Receiver-thread budget note: the accept loop blocks once per upload (5 s admission + 10 s ack worst case). Single-Watch debug use is serialized anyway (`server.rs:45-49`); this is accepted and documented.

### B.4 PCM transfer — binary IPC, source evidence, and the mandatory spike (Repair 1.7)

Transfer path: validated bytes → durable file → `watch_read_reserved_pcm(requestId)` reads it back and returns the data-chunk slice (header stripped exactly once, in Rust, using offsets already proven by `wav::parse`). The WebView never parses the RIFF container.

Pinned-version source evidence (documentation-phase evidence only — this is why the verdict is CONDITIONAL, not MATCH):

- Rust: `InvokeResponseBody::Raw(Vec<u8>)` and `impl From<Vec<u8>>` (`tauri-2.10.3/src/ipc/mod.rs:99-112`); `Response::new(body: impl Into<InvokeResponseBody>)` (200-205); raw responses are served with `mime::APPLICATION_OCTET_STREAM` (`src/ipc/protocol.rs:344`).
- JS (injected runtime): `tauri-2.10.3/scripts/ipc-protocol.js:50-53` — responses whose content-type is neither `application/json` nor `text/plain` are consumed via `response.arrayBuffer()`. So on the custom-protocol path, `tauri::ipc::Response::new(bytes)` reaches `@tauri-apps/api`'s `invoke` as an **`ArrayBuffer` with no base64 and no JSON coercion** (`node_modules/@tauri-apps/api/core.js:201-203` forwards to `window.__TAURI_INTERNALS__.invoke`; delivery completes via `runCallback`, `ipc-protocol.js:55-57`).
- Known hazard: if the custom protocol fails, the injected runtime **falls back to postMessage** (`ipc-protocol.js:58-67`), and the postMessage path serializes binary as a JSON number array (`scripts/process-ipc-message-fn.js:26-29`) — for a 9.6 MiB payload that is ~4× inflation and unacceptable. The fallback is observable (`console.warn`, `ipc-protocol.js:59-62`).

**Frozen outcome (Repair 1.7 option b), resolved by Z2:** the mandatory isolated test on pinned `tauri 2.10.3` + `@tauri-apps/api 2.10.1` passed in the packaged Windows debug WebView2 runtime. `tauri::ipc::Response::new(Vec<u8>)` resolved as a byte-identical raw `ArrayBuffer` for 9,438,418 bytes; expected/actual SHA-256 was `ebf783be13a56bd212a474803c6b8d6da391b10bc0cfc5721f9422c90c045750`, sentinels matched, and no `customProtocolIpcFailed` fallback warning appeared. PM independently rebuilt and reran the harness and accepted the gate. The transfer verdict is therefore **MATCH**. An undocumented base64 fallback for the full 10 MiB body is **not authorized**; rejected alternatives on record: base64-in-JSON admission body (+33 % and double serialization for up to 9.6 MiB), shipping the WAV header to `sendAudio` (violates §A.3).

Chunking: PC-internal chunk size is a **target, not an invariant**. The feed loop slices `pcm` into consecutive even-length raw-PCM byte chunks of the configured target — recommended 4096 samples (8,192 bytes), the ScriptProcessor fallback buffer size (`audio.ts:290-291`) — and the **final slice carries the exact remainder** (`pcm.byteLength - consumedBytes`), which is guaranteed even because both the target and the validated total byte length are even (§B.5 step 1). With a `setTimeout(0)` yield between chunks the ingest loop never starves the event loop; ≈1,170 `sendAudio` calls for a 5-minute file, the last one generally short. Per-chunk sample accounting is exact — §B.5 step 7. Watch upload remains whole-file HTTP.

### B.5 External run body: lifecycle, audio accounting, shared finalization (Repairs 1.1, 1.6)

`beginExternalRun(requestId, pcm)` runs on `audio-ready`, after Phase A+B succeeded:

1. **Validate PCM**: non-empty, even byte length, `byteLength === sampleCount × 2` against the admission metadata. Mismatch → correlated abort, reason `pcm_mismatch`.
2. **Reset the run fields exactly as `startRecording` does** (1450-1467): `finalHandledInCurrentRun = false`, `audioSentSamples = 0`, `wallTimeAtStopSec = 0`, `recordedChunks = []`, waveform/warning state untouched (no mic session exists).
3. **`await provider.connect(this.buildProviderCallbacks())`** — the same per-run connect mic runs perform (1521-1522); fresh guarded callbacks so provider-side `activeRunId` filtering (§A.4) binds to this run. Re-verify reservation + run currency after the await; on failure → correlated abort, reason `provider_connect_failed`.
4. **Re-verify `provider.isReady()`** (Repair 1.1 step 3). Not ready → correlated abort, reason `provider_not_ready_after_connect`.
5. **`provider.start({ runId, disableAi: !cachedAiEnabled, systemPrompt: cachedAiEnabled ? resolvedSystemPrompt : undefined, aiMinDurationSec, clientMeta, appContext, textContext, hotwords, language })`** — checked boolean per mic runs. `false`/throw → correlated abort, reason `provider_start_failed`. `appContext` and optional `textContext` are Phase B's captured context; `streamingDisplay` follows the existing setting. No PCM has moved yet.
6. `transition('recording')` (mirrors 1588) — from here the state machine itself blocks PTT-down; the reservation guard still blocks PTT-up (§B.2). Then `watch_run_started(requestId)` → receiver answers `201`.
7. **Feed with exact sample accounting (Repair 2)**: iterate `pcm` as consecutive even-length raw-PCM byte chunks of the configured target (§B.4); the final chunk is the exact remainder slice. Per chunk: `chunkSamples = chunk.byteLength / 2` computed **for that exact slice**; `recordedChunks.push(exact copy of chunk)` (mirrors 1533 — these are the copies `saveRecordingAudio` consumes in `processFinalResult`); `audioSentSamples += chunkSamples` — **never the configured maximum chunk size** (mirrors 1539, which drives `getAudioDurationSec`, 738-740); `provider.sendAudio(chunk)`; yield (`setTimeout(0)`); re-verify run currency per chunk; a lost run mid-feed aborts. **After the feed and before any call to `provider.stop` / `finalizeRecording`, assert** `audioSentSamples === admission.sampleCount` **and** the sum of `recordedChunks` byte lengths `=== pcm.byteLength`; any mismatch takes the correlated abort path (§B.6) with the fixed non-secret reason `sample_accounting_mismatch` (only numeric expected/actual counts may accompany it — length metadata per §B.8) — so no stop, no processing, no History entry, and no gate leak can result from a miscounted feed.
8. **Minimum-duration rule** (mirrors 1858-1865): if `audioSentSamples / 16000 < 0.5` → `provider.cancel()`, `finishRun(runId)`, correlated release; no history entry (the Watch already got `201` — transport success; a <0.5 s recording legitimately produces nothing, same as mic).
9. **Finalize via the smallest shared helper (Repair 1.6)**: extract `stopRecording`'s tail (1876-2002: `provider.stop` call → `transition('processing')` → `processingCancelable` → timeout arming → thinking overlay) into a private `finalizeRecording(runId, stopOpts)` used by **both** mic and external paths. `stopRecording` keeps its mic-only prologue: capture-ready wait (1811-1819), `stopCapture()` (1825), `restoreSystemMuteIfNeeded()` (1832), duration-ratio consistency check (1846-1857). The external path calls `finalizeRecording` **without ever touching `stopCapture`**. Before finalizing, the external path derives `wallTimeAtStopSec`, `pttHoldMs`, and the `computeProcessingTimeoutMs` input **only from the exact final `audioSentSamples`** (i.e. after the step-7 assertions have passed): `durationSec = audioSentSamples / 16000`, `wallTimeAtStopSec = durationSec`, `pttHoldMs = durationSec × 1000` — sample-derived truth, never the configured chunk size and never a fake wall clock. `stop` options: `{ pttHoldMs, disableAi: durationSec < aiMinDurationSec || undefined }`; `audioStats` is omitted (mic-only meters; optional in `StopOptions`). From here `onFinal → History → Paste`, `onError`, `onDone`, timeout, late-final and Esc behave identically to mic runs (§A.4-§A.6), with `context.audioChunks` = the external chunks → History audio reuse is real, not claimed.
10. **Gate release**: the `finishRun` hook (262-275) fires on every terminal path; when the finished generation is the external run it clears `externalReservation` and invokes `watch_run_aborted(requestId, "run_finished")` (command name shared with abort; the gate returns to `Idle` regardless of outcome). Overlay during ingest: none (the Watch is the user's feedback surface; the listening ticker/bars are mic visuals); the `processing` phase shows the ordinary thinking/fallback overlay, so Esc-cancel of external processing works exactly like mic processing.

### B.6 Correlated abort — both sides, every path (Repair 1.3)

**One operation, two halves, idempotent and correlation-checked.** `abortExternalRun(requestId, reason, origin)`:

- Rust half (`admission.rs`): only if `gate == Reserved{request_id}` (requestId match) → complete any pending admission/ack oneshot with failure, `gate = Idle`. Mismatched or already `Idle` → no-op + warn log. Never touches a newer reservation.
- JS half (`watchIngress.ts` + orchestrator): only if `externalReservation?.requestId === requestId` (and runId matches when known) → clear `externalReservation`; invalidate the generation with `finishRun(runId)` semantics (clears `pendingHistoryArtifact` / `timedOutProcessingContext` / `activeRunId`, 262-275); cancel pending Phase-B/PCM promises via the post-await currency re-checks (§B.2, §B.5); if `connect` or `start` already succeeded → `provider.cancel()` and, for server mode, `ensureConnection()` (mirrors 854, 895); restore overlay/Esc state only if the processing overlay had been shown (`resetToIdle({ keepOverlay: false })` semantics). Mismatch → no-op + warn log.

Trigger matrix (all nine required cases):

| # | Trigger | Origin | HTTP outcome | Rust gate | JS reservation / runId | Provider session |
|---|---|---|---|---|---|---|
| 1 | Admission oneshot timeout (5 s) | Receiver | `409 bridge_timeout` | Idle (abort) | if JS alive: cleared via `watch://run-abort`; if dead: gate already clean | none reached |
| 2 | Phase B capture failure / 3 s timeout | JS | `500 context_capture_failed / timeout` | Idle via `resolve(rejected)` | cleared by JS half | never connected |
| 3 | Durable-save failure | Receiver | `500 storage failure` | Idle (abort + `watch://run-abort`) | cleared via event | never connected |
| 4 | PCM read / `pcm_mismatch` | JS | `500 run failed to start` (via `watch_run_aborted`) | Idle | cleared by JS half | never connected |
| 5 | `provider.connect` failure / timeout | JS | `500 run failed to start` | Idle | cleared | never established |
| 6 | `provider.start` false / throw | JS | `500 run failed to start` | Idle | cleared | `cancel()` + server reconnect |
| 7 | Ack timeout (10 s) | Receiver | `500 run start ack timeout` | Idle (abort + `watch://run-abort`) | cleared via event | `cancel()` if connected |
| 8 | WebView reload (any phase) | Environment | in-flight POST fails with the oneshot timeouts above | reclaimed by lease / boot reconciliation (§B.7) | JS state vanishes with the WebView | Rust side has no session; nothing to cancel |
| 9 | Stale / mismatched requestId | Any | unchanged for the live request | no-op + warn | no-op + warn | untouched |

Post-`201` failures (stop false/throw, provider error, cancel) are **run failures**, not admission failures: they are handled by the existing error paths (error history entry, fallback card, timeout machinery) and still end in `finishRun` → gate release. No HTTP response remains to fix; the Watch's `201` correctly recorded transport/admission success.

### B.7 Rust-owned lease and WebView-reload recovery (Repair 1.4)

The revision-1 30 s JS watchdog is **removed**. It was wrong twice: 30 s is below the legitimate run lifetime (processing timeout alone reaches 90 s on cloud mode, plus the 15 s late-final grace), and a WebView-owned timer dies with the WebView it is meant to police.

Replacement — a Rust-owned, lazily-evaluated lease inside `AdmissionGate`:

- `Reserved { reserved_at }` carries the reservation timestamp. `LEASE_MS = 300_000` — exactly `MAX_RECORDING_SEC × 1000` (`recorder/types.ts:53`), a frozen project constant.
- Derivation of the legitimate upper bound: admission oneshot 5 s + Phase B 3 s + durable save ≈1 s + prepare/ack 10 s + feed ≤10 s + processing timeout ≤90 s (cloud cap, `helpers.ts:108-132`) + late-final grace 15 s + insertion/fallback margin 30 s ≈ **164 s < 300 s**. A healthy run can never be lease-reclaimed.
- Evaluation points (lazy — the blocking receiver needs no timer thread): (a) every new admission attempt — if `Reserved` and older than `LEASE_MS`, log the reclamation, release, and continue with the fresh admission (self-healing without failing the new upload); (b) `watch_gate_state()` at WebView boot.
- **Reload recovery without assuming any JS timer runs**: when the WebView reloads, JS state (reservation, runId) vanishes and Rust may hold `Reserved` forever. Recovery has two independent layers: (1) at boot, `watchIngress` calls `watch_gate_state()`; a `Reserved` gate with no matching local reservation is orphaned → the JS side issues `watch_run_aborted(requestId, "orphaned_after_reload")` → gate `Idle`; (2) if JS never boots, the lease at the next admission attempt reclaims the gate. A reload mid-run loses that run's audio only in the sense that the in-flight ASR dies; the durable `received_watch.wav` persists on disk and the Watch-side WAV remains (the upload had already been saved), so nothing is corrupted — the abort simply ends the run without transcription, consistent with "201 ≠ transcription completion".

### B.8 Probe, reuse, retryability, and no new tracking

- **Probe exactly once at Watch upload admission**: Phase A's synchronous in-process capture + Phase B's single native `getRecordingContext(cachedContextAwareWriting && cachedAiEnabled)`; both stored into the same fields mic runs use (`cachedProbeResult`, `currentActiveAppContext`, snapshot into the run context at `onFinal`/timeout). No Target Manager, no Focus Tracking, no Target Lock, no second probe later in the run.
- **Full reuse**: active provider instance (`getProvider()`, `index.ts:31-36`), the same `buildProviderCallbacks()` object, the same History write (`processFinalResult` 2301-2328), the same `PasteService`/paste path, the same overlay/Esc/timeout/late-final machinery. No second ASR path.
- **AI parity**: the Watch path follows the existing AI switch, resolved prompt, optional editor context and short-speech threshold (§A.7).
- **Failed admission leaves the WAV retryable**: admission precedes save; `409`/`5xx` never touch disk, so the previously accepted file survives; post-admission failures respond non-2xx and abort cleanly on both sides (§B.6). No partial or duplicate run can remain — the run only exists after `provider.start` success, and every teardown funnels through `finishRun`.
- **Request-ID and error crossing without secrets**: `X-Request-Id` (UUID, mandatory and echoed end-to-end, `server.rs:80-105, 179-193`) is the single correlation key across HTTP, all three events, all five commands, and every log line. Error bodies/log lines carry only fixed reason strings, the requestId, and numeric metadata. Transcript text, PCM, tokens, editor text, and the Authorization header are never logged (preserves `server.rs:9-11` and the length-only logging at `RecorderOrchestrator.ts:1209, 2268`).

---

## C. Ordered event sequences (revised — each shows Rust gate, JS reservation/runId, capture, Provider order, save + ack, HTTP timing, cleanup owner)

### C.1 Accepted request

```
Watch        Receiver(Rust)                    Gate                    WebView(JS)
 | POST /api/watch/audio ->| auth/id/type/size OK                         |
 |                         | wav::parse OK                                |
 |                         | emit admission-request --+> Idle             |
 |                         | block oneshot (<=5s)     |                   |
 |                         |                          |  Phase A (SYNC):  |
 |                         |                          |  checks + runId + externalReservation
 |                         |                          |  + sync captureActiveInsertionTarget
 |                         |                          |  Phase B (async, <=3s):
 |                         |                          |  getRecordingContext(contextAware && aiEnabled) once
 |                         |                          |  -> probe/appContext/routing stored
 |                         |<------ resolve(accepted) --| Reserved{reqId} |
 |                         | save_atomically OK       | Reserved          |
 |                         | emit audio-ready --------+> Reserved        |
 |                         | block ack oneshot(<=10s) |                   |
 |                         |                          |  watch_read_reserved_pcm -> ArrayBuffer
 |                         |                          |  validate byteLength == sampleCount*2
 |                         |                          |  reset run fields (chunks/samples/wall)
 |                         |                          |  await provider.connect(buildProviderCallbacks())
 |                         |                          |  re-verify reservation+run; isReady()
 |                         |                          |  provider.start({runId, disableAi:!aiEnabled, prompt, ...}) == true
 |                         |                          |  transition('recording')
 |                         |<------ run_started(ack) ---| Reserved        |
 |<=== 201 {requestId, bytes, sampleCount, durationMs, sha256} ===      |
 |                         |                          |  sendAudio*(target-size chunks, yield; per-chunk
 |                         |                          |    samples = byteLength/2 — exact short final chunk)
 |                         |                          |  assert audioSentSamples == admission.sampleCount
 |                         |                          |    && Σ chunk bytes == pcm.byteLength
 |                         |                          |    (mismatch -> correlated abort BEFORE provider.stop)
 |                         |                          |  wallTimeAtStopSec = exact audioSentSamples/16000
 |                         |                          |  finalizeRecording -> provider.stop({disableAi:shortSpeechOnly})
 |                         |                          |  -> processing -> onFinal
 |                         |                          |  History -> Paste
 |                         |<------ run_aborted("run_finished") ------| Idle
```

### C.2 Busy / not-ready request

```
 | POST /api/watch/audio ->| auth/id/type/size OK, wav OK                 |
 |                         | emit admission-request --> Idle              |
 |                         |                          |  Phase A (SYNC): state!=='idle' OR !isReady()
 |                         |                          |  OR reservation exists -> false (no runId, no capture)
 |                         |<------ resolve(rejected, reason)           |
 |<=== 409 {"error":"busy","detail":"orchestrator_not_idle"|"provider_not_ready"|"already_reserved","requestId":…}
 |          (no disk write; previous received_watch.wav intact; retryable)
 |
 | bridge_timeout variant: no JS answer within 5s -> 409 "bridge_timeout", gate stays Idle
```

### C.3 Abort matrix (owners and both-side cleanup)

See §B.6 table — nine triggers, each with: HTTP outcome, Rust gate owner (receiver abort / resolve(rejected) / JS `watch_run_aborted` / lease), JS reservation + runId owner (JS half, `watch://run-abort` handler, or vanishes with reload), and provider-session owner (`cancel()` + server reconnect only when `connect`/`start` had succeeded). Every entry is requestId(+runId)-conditional and idempotent; stale cleanups are no-ops with a warn log.

### C.4 WebView reload recovery

```
 reload at any phase:
   JS state (reservation/runId/callbacks) ceases to exist
   in-flight POST: admission or ack oneshot expires -> 409 bridge_timeout / 500 ack timeout
     receiver aborts its half (gate -> Idle)
   if gate somehow remains Reserved:
     (1) on next WebView boot: watch_gate_state() -> Reserved but no local reservation
         -> watch_run_aborted(requestId, "orphaned_after_reload") -> Idle
     (2) if JS never boots: next admission attempt finds lease expired (>=300s)
         -> log reclamation -> release -> proceed with fresh admission
   durable received_watch.wav persists on disk; no corruption; no ghost run can start
```

### C.5 Successful `onFinal → History → Paste` (unchanged machinery, external inputs)

```
 WebView: onFinal(FinalResult)
   -> generation + duplicate guards
   -> processFinalResult:
        historyEnabled/audioRetentionEnabled checks
        -> saveRecordingAudio(recordId, externalChunks)     (recordedChunks: §B.5 step 7)
        -> addHistory({asrText, llmText, durationSec: wallTimeAtStopSec (= samples/16000),
                       audioDurationSec, providerMeta, appMeta})
        -> history-updated
   -> textInsertionInFlight = true
   -> handleTextInsertion(probe snapshot from admission):
        editable? -> modifier settle -> Esc disabled -> pasteText(text, probe, protectClipboard)
        ok:     finishRun -> gate release ("run_finished") -> Idle
        failed: fallback card -> finishRun -> gate release -> Idle
```

---

## D. Stop-condition evaluation (task §Stop conditions)

1. "Provider input is not raw 16 kHz mono LE i16 PCM" — **Not triggered** (§A.3).
2. "A normal run cannot be created without bypassing or substantially refactoring `RecorderOrchestrator`" — **Not triggered**: two sibling entry methods, one extracted shared finalize helper inside the same class, two guard clauses, one `finishRun` hook. State machine, callback wiring, History/Paste and timeout machinery untouched.
3. "`onASR/onFinal` cannot safely reuse the current History/Paste path" — **Not triggered** (§A.4-§A.5, §B.5 step 9).
4. "Exact 409 admission requires a new second Provider, direct `local_transcribe`, or duplicated ASR/History/Paste logic" — **Not triggered** (§B): the gate consults the existing singletons; the external run uses the same provider instance and the shared finalize helper.
5. "The proposed bridge introduces a focus tracker, persistent target lock, Watch streaming, or release HTTP receiver" — **Not triggered** (§B.8).

---

## E. Minimal implementation file list proposed for the next Z slice

| Order | File | Change |
|---|---|---|
| **0 (completed gate)** | `spikes/watch-binary-ipc/` | **PM-accepted PASS.** Packaged Windows WebView2 proved ≥9 MiB raw binary IPC on the pinned stack; contract upgraded to MATCH. |
| 1 | `client/src-tauri/src/watch_receiver/admission.rs` | **New.** `AdmissionGate` (`Idle`/`Reserved{request_id, reserved_at}`), two oneshots, lease, correlated abort; unit tests (accept/reject/timeout/double-resolve/stale-id/lease-reclaim). |
| 2 | `client/src-tauri/src/watch_receiver/server.rs` | **Modify.** Insert admission step 6, save-failure abort, audio-ready + ack oneshot, `201`-after-ack; integration tests for the full §B.3 order and every §B.6 row. |
| 3 | `client/src-tauri/src/main.rs` | **Modify (debug-only).** Register gate state; pass handle to receiver thread. |
| 4 | `client/src/services/watchIngress.ts` | **New.** Event/command plumbing, boot-time `watch_gate_state()` reconciliation, `abortExternalRun` JS half. No timers. |
| 5 | `client/src/services/recorder/RecorderOrchestrator.ts` | **Modify (additive).** Phase A/B methods, `beginExternalRun` per §B.5, extract `finalizeRecording` from `stopRecording`'s tail (1876-2002), reservation guards in `startRecording` and `stopRecording`, `finishRun` release hook. |
| 6 | `client/src/services/recorder.ts` | **Modify.** Thin delegation. |
| 7 | Vitest + Rust tests | Reserve atomicity (PTT-vs-admission interleave), §B.5 field accounting, §B.6 matrix, mic-path regression (finalize extraction is behavior-preserving). **Repair-2 case (explicit)**: a feed whose sample count is **not divisible by the chunk target** (e.g. `16000×N + r`) — the final short chunk is counted exactly (`chunk.byteLength / 2`), the post-feed assertions pass (`audioSentSamples === admission.sampleCount`, `Σ chunk bytes === pcm.byteLength`), and the concatenation of `recordedChunks` reproduces the admitted PCM **byte-for-byte**; plus a deliberate mismatch case proving the `sample_accounting_mismatch` correlated abort fires **before** `provider.stop`. |

Not touched: any `TranscriptionProvider*` file, History/store, `PasteService`, text insertion, AI, settings, update/backup/storage-security, watch app, release builds.

---

## F. Risks and open questions (revised)

1. **Binary IPC runtime behavior — resolved.** Z2 proved the raw custom-protocol path in packaged Windows WebView2, and PM independently reproduced the exact `ArrayBuffer`, full hash, sentinels, and zero fallback warnings. Keep the accepted dependency locks; dependency drift requires a new runtime check.
2. **Receiver-thread blocking.** Each upload can hold the accept loop up to ~15 s (5 s admission + 10 s ack). Single-Watch debug use is serialized anyway; a second concurrent Watch is out of scope (same as 1A) and would queue.
3. **Lease reclamation of a genuinely hung (not dead) WebView.** A run stuck ≥300 s would be reclaimed at the next admission attempt. Healthy worst case is ≈164 s, so this only fires on a hung or vanished WebView — accepted fail-safe, logged.
4. **Watch-side retry semantics.** A retry after `201` (run still active) gets `409`; a retry after completion is a new run (duplicate transcript). Suppression beyond the busy window stays out of scope; PM should confirm the Watch retries only on transport errors.
5. **`switchProvider` during a reserved run** disconnects the provider unconditionally (`index.ts:48-77`) — pre-existing hazard for mic runs too, unchanged by Z1; recommended follow-up: defer mode switches while not idle.
6. **Single-slot storage.** `received_watch.wav` is one atomically-replaced file (`server.rs:166-175`); admission-before-save means a `409`/`5xx` never clobbers it, but each *accepted* upload replaces the previous sample (unchanged from 1A).
7. **`/api/health` `asrReady:false`** is hardcoded (`server.rs:69`); exposing gate state is a cheap diagnostic but changes the health contract — PM decision.
8. **Overlay UX.** External ingest is silent (no listening overlay); processing/fallback overlays are reused. Esc-cancel of external processing works; ingest (seconds) is not Esc-cancelable. PM may want a distinct "receiving watch audio" indicator later.
9. **Local mode.** `isReady()` covers the model-downloaded case; a 9.6 MiB WAV plus a resident local model fits memory budgets; end-to-end local-mode acceptance remains device work outside Z1.

---

## G. Repair 1 checklist — correction → contract section

| Repair item | Where addressed |
|---|---|
| 1.1 Real per-run lifecycle (`isReady` pre-flight → `connect(fresh callbacks)` → checked `start` → `sendAudio*` → `stop`); external flow performs both indispensable steps; no PCM before `start` success; rollback per stage | §A.2 (corrected freeze), §B.3 step 8, §B.5 steps 3-6, §C.1 |
| 1.2 Two-phase API: synchronous reservation (Phase A) vs asynchronous native focus/context capture (Phase B); admission response accepted only after Phase B completes or fails closed; documented HTTP result for capture failure; no silent live-probe fallback | §A.5b (sync/async evidence), §B.2, §B.3 step 6, §C.1, §C.2 |
| 1.3 Correlated abort clearing Rust gate + JS reservation + runId + pending promises + overlay/Esc + provider session; all nine trigger cases; requestId+runId conditional | §B.6 (operation + matrix), §B.3 steps 6-8, §C.3 |
| 1.4 Remove 30 s JS watchdog; Rust-owned lease (300 s = `MAX_RECORDING_SEC`) derived from existing maxima; WebView-reload recovery without a JS timer | §B.7, §C.4 |
| 1.5 No `201` before bounded post-save acknowledgement (PCM obtained+validated, callbacks connected, `start` succeeded, run owns reservation); timeout/failure → non-2xx + both-side abort; `201` ≠ transcription completion | §B.3 steps 8-9, §C.1, §F.5 rationale |
| 1.6 External audio accounting: `recordedChunks`, `audioSentSamples`, sample-derived `wallTimeAtStopSec`, timeout input, prompt/app/probe context fields; no mic-only `stopCapture()`; smallest shared finalize helper | §A.6, §B.5 (steps 2, 7, 9), §E row 5 |
| 1.7 Binary IPC resolved before unconditional MATCH: pinned-version source evidence plus PM-accepted packaged runtime proof; verdict **MATCH**; base64 fallback for the full body remains unauthorized | §B.4, §E row 0, §F.1 |
| R2 (Repair 2) Exact final-chunk sample accounting: per-chunk `chunkSamples = chunk.byteLength / 2` on the exact slice, exact copied chunk appended to `recordedChunks`, `audioSentSamples += chunkSamples` (never the configured maximum), post-feed assertions `audioSentSamples === admission.sampleCount` and `Σ chunk bytes === pcm.byteLength`, mismatch → correlated abort **before `provider.stop`** with fixed reason `sample_accounting_mismatch`, and `wallTimeAtStopSec`/`pttHoldMs`/timeout input/History duration derived **only** from the exact final `audioSentSamples`; explicit non-divisible-length test case | §B.4, §B.5 steps 7-9, §C.1, §E row 7 |

---

*End of the frozen Provider contract. PC-only external WAV ingress is authorized only through `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`; this MATCH does not mark Delivery 1B or the real Watch closed loop complete.*
