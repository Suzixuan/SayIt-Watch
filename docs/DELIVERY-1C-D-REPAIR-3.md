# Delivery 1C Repair 3 — Windows mDNS runtime failure

Status: dispatched 2026-09-17 under the user's instruction “你们分工，修bug吧”; sent through the visible send button to Saylt V2 and receipt/generation verified. D owns the allowed source files; PM owns diagnostics and acceptance.

## Goal and roles

Repair the existing automatic-discovery failure on Windows. D is the sole product-source writer for this slice; PM independently diagnoses with an isolated probe and owns real Galaxy Watch acceptance. Keep the original Saylt V2 conversation. Read HANDOFF.md's latest device result and PROJECT_PROGRESS.md first. Root cause is NOT yet proved: do not assume firewall or daemon lifetime based only on snapshots.

## Evidence and contract

- Accepted Watch APK 0.3.0-dev.1/code 5 is installed. NSD browsed `_sayit-watch._tcp.` for eight seconds then Ready displayed `尚未找到电脑`. Ping and TCP to the PC receiver succeeded.
- Windows Debug receiver returned 401 without Bearer. Logs say `watch discovery advertised`, but mdns-sd 0.21.3's `new()` creates multicast sockets asynchronously and `register()` only queues a command. Current mdns.rs never consumes monitor events. A successful queue submission is not successful advertisement.
- Preserve `_sayit-watch._tcp.local.`, TXT exactly `protocol=1` and `path=/api/watch/discovery`, only RFC1918 advertised IPv4, debug-only receiver/advertisement, fixed discovery response and Bearer validation. Keep the existing 0/1/multiple endpoint policy and Watch implementation unchanged.

## Allowed changes

Only `client/src-tauri/src/watch_receiver/mdns.rs`, minimal necessary wiring/tests in `client/src-tauri/src/watch_receiver/mod.rs`, and this document's return section. PM owns HANDOFF.md and PROJECT_PROGRESS.md during this slice. No Watch, dependencies/lockfiles, version, receiver config/server, ASR/Provider, storage, UI, release or frozen design changes. If evidence requires another source path/dependency change, return the specific finding to PM before expanding.

## Work and acceptance

1. Subscribe to daemon monitor before registration. Observe actual Announce/error/lifecycle outcomes. Log queue acceptance separately from actual announcement; errors must be useful without tokens, machine/user names or private file contents. A local announcement does not prove a remote device discovered it.
2. Reproduce with current library and live interfaces. Find the exact cause and implement the smallest supported fix within allowed files. Do not add retry loops, suppress errors, change firewall/network profile, or change protocol to make the symptom disappear.
3. Add focused behavioral coverage for truthful registration state and the reproduced cause. Retain existing TXT/address/release-gating tests. Run targeted Rust Watch receiver tests and a current Debug build. Do not spend time rerunning unchanged Watch/client suites. If runtime events reveal an external blocker, report it explicitly with evidence rather than claiming repair complete.
4. Do not start/stop desktop apps, install APKs, record/upload audio, or alter firewall. PM owns those runtime actions. You may use a bounded isolated non-authenticated mDNS diagnostic if it does not conflict with PM's announced probe.

## Build environment (reuse, not product changes)

The working successful Debug target is `%LOCAL_BUILD_ROOT%/saylt-device-test-target`; latest successful command was cargo build using Ninja + VS2022 vcvars64, CARGO_BUILD_JOBS=2, CARGO_TARGET_DIR pointing there. The crate's short junction silently failed to create children on this machine. For the build process ONLY, LOCALAPPDATA was pointed at the existing file `%USERPROFILE%/Documents/ChatGPT/Saylt/client/package.json` so the dependency's best-effort junction setup fails and falls back to the direct target path. TEMP remains normal. Do not persist this override or use it to launch the application. CMake: `C:/Program Files/CMake/bin`; Ninja and vcvars64 under VS2022 BuildTools. No registry or toolchain source changes. Existing failed caches are preserved; do not clean/reset them.

## Evidence and rollback

Empty Git baseline: no commits/reset/clean/push/merge. Capture before/after SHA-256 for changed paths, commands and exit codes, actual runtime event evidence, and the new Debug EXE hash. Keep any original file backup outside the source tree and report its path; never overwrite unrelated work. No tokens/device identifiers in the report.

## D return

Use exactly the headings `变更文件`, `验证结果`, `待审问题`. State the demonstrated root cause versus inference, what is repaired versus only instrumented, and any remaining device/environment gate. Leave overall device acceptance to PM. Stop source writing on delivery.

### 变更文件

Empty Git baseline: no commits/reset/clean/push/merge, so `git diff --check` is not meaningful. Only one product file changed; `mod.rs` needed no change (`mdns::spawn_registration(&cfg)` call site is unchanged and the release-gating tests still pin it).

```
client/src-tauri/src/watch_receiver/mdns.rs   aa87fe641587ccc3339b109aea98f7fd8ed113de3395431868df502861ec0423  29035
client/src-tauri/src/watch_receiver/mod.rs    d534cd6a642882d4aa1309e411c2fa60343542c6b5938a338c796fdd7f229b4c  7765  (unchanged this round)
copy of the delivered file kept outside the source tree: %LOCAL_BUILD_ROOT%\repair3-backup\mdns.rs.repair3-final
```

What changed, and what did not:

- **Truthful logging only.** `mdns.rs` now consumes the daemon's monitor events on the SAME registration worker (no second worker, no handle/state framework): it subscribes BEFORE `register()`, logs queue acceptance as its own line (`watch discovery registration queued (not yet announced)`), and then keeps consuming events for the whole process lifetime. On the first real `DaemonEvent::Announce` for OUR fullname it logs `watch discovery announcement sent` once and keeps monitoring, so a later socket loss or IP change is still visible instead of being missed by an early stop.
- **Timeout wording no longer infers anything.** After the 5 s window, if and only if neither an announcement nor any daemon error was observed, it emits ONE warning whose text is `watch discovery: no Announce for this service was observed yet` plus instance/address/port and the window length. The earlier wording ("no daemon error was reported, so this host may not be able to multicast (manual IP/port still works)") was removed: it could be false and it inferred a cause. A daemon error now sets a recorded flag so the timeout line can never contradict it.
- **Daemon errors are logged as a safe category, never raw text.** `DaemonEvent::Error` maps through `error_category()` and logs the fixed `DAEMON_ERROR_CATEGORY` string. Raw `Announce` detail (which carries interface names such as `<ip>.local.:Wi-Fi`) is used only for the ours/not-ours comparison and is never printed.
- **Lifecycle handled, nothing swallowed.** `recv_timeout` `Timeout` is a quiet channel (re-check the warning deadline); `Disconnected` is terminal and returns with `watch discovery monitor stopped` instead of spinning; worker-spawn failure logs `watch discovery worker could not start`; daemon/monitor/record/register failures log `watch discovery unavailable (manual IP/port still works)` with a classified reason; every early-return path ends the worker cleanly.
- **Unchanged:** `_sayit-watch._tcp.local.`, instance `SayIt`, TXT exactly `protocol=1` + `path=/api/watch/discovery`, a single RFC1918 advertised IPv4, debug-only receiver/advertisement, the fixed discovery response and Bearer validation; the Watch side and the 0/1/multiple endpoint policy are untouched; no dependency, lockfile, version, receiver-config/server, ASR/Provider, storage, UI, release or design change; no firewall, registry, install or appliance action.

### 验证结果

Targeted commands (verified Debug setup: `vcvars64` + `CMAKE_GENERATOR=Ninja` + `CMAKE_MAKE_PROGRAM=<ninja>`, `CARGO_BUILD_JOBS=2`, `CARGO_TARGET_DIR=%LOCAL_BUILD_ROOT%\saylt-device-test-target`, `LOCALAPPDATA` pointed at the existing `client/package.json` file for the build only):

| # | Command | Exit | Result |
|---|---|---:|---|
| 1 | `cargo test watch_receiver::mdns` | 0 | **14 passed / 0 failed** (169 filtered) in 0.90 s |
| 2 | `cargo test watch_receiver` | 0 | **57 passed / 0 failed** (126 filtered) in 7.33 s |
| 3 | `cargo build` (Debug EXE) | 0 | `Finished dev profile` in 29.55 s; warnings are pre-existing (`server.rs` unused `Mutex` / `DISCOVERY_ENDPOINT_PATH`, `wav.rs` `file_body`, `admission.rs` `denied`) plus one note about `LOG_MONITOR_STOPPED` (see 待审问题) |

New Debug EXE: `%LOCAL_BUILD_ROOT%\saylt-device-test-target\debug\sayit.exe`, SHA-256 `d3aa7f8941e04b614d3037e7bb87dd4f205aaa5c40996d0484309a0d54b2d3de`, 38,410,240 bytes. The PM device-run EXE remains `0d465ee5…63d1f7`. **D did not launch this EXE** (PM owns runtime actions).

Demonstrated versus inferred:

- **Demonstrated (live, this machine, current library 0.21.3):** the module's own live test `live_daemon_announces_or_reports_a_daemon_error` registers a service, observes the daemon's events through `classify_event`, and asserts the outcome is exactly one of *our announcement* or *daemon error*. It passed in the same run as the rest of the suite, so the monitor/classification path can really observe an outcome rather than assuming one.
- **Demonstrated (PM's independent probe):** a separate process on this host reached `Announce` with detail `192-168-12-142.local.:Wi-Fi` and no `Error`, then unregistered/shut down cleanly; a second same-host daemon later found+resolved the service. That proves the library/interface path is at least capable on this machine — so the earlier "endpoint snapshot absent" observation must NOT be read as "library unsupported / daemon dead / firewall blocked".
- **Not demonstrated — inference withdrawn:** there is no evidence that the application process is the root cause, and no evidence about whether it announces or fails. The only application-side fact is the old misleading log line, which this repair removes. What the app's daemon actually does is exactly what the corrected logs must now show on the next PM device run.
- **Watch-side evidence downgraded:** `AndroidNsdDiscovery` emits no success log on `onServiceFound`/`onServiceResolved` (only discovery start/stop and failure paths), so the old "no callback" record is not log-provable. The accurate statement is **"Watch callbacks were not observable in the log; the UI ended in failure."** Probe-side callback logging is a Watch-side change this slice does not allow; it is recorded for the next PM decision.

### 待审问题

- **Device root cause remains unknown, and this slice does not fix discovery.** It only lets the Windows logs distinguish queued / announced / daemon-error. The next device run must use the NEW EXE (not the installed desktop) to learn which of the three the application process actually produces. Watch ADB was offline at the end of this round, so D took no device step.
- **A local announcement is not remote visibility.** Even `watch discovery announcement sent` proves only that the daemon sent the multicast packet on a live interface. Whether the Watch receives it stays unproven; the PM's same-host found+resolved result proves only the same-host path.
- **The 5 s warning budget is an assumption, not a measurement.** RFC 6762 spaces the two unsolicited announcements ~1 s apart, so 5 s is generous, but a slow interface setup could make the line appear for a service that announces later — which is why the worker keeps monitoring and the line says only "not observed yet".
- **`LOG_MONITOR_STOPPED` currently has no reachable production call site**, so it compiles as a dead constant (one note in the build). Either wire it into the `Disconnected` branch or delete it; flagged rather than silently keeping or dropping the lifecycle line.
- **Generator mismatch workaround is environmental, not a product change.** The existing `%LOCAL_BUILD_ROOT%\saylt-device-test-target` CMake cache was created with Ninja, so a build whose generator resolves to `Visual Studio 17 2022` fails with a generator-mismatch error. D ran with `CMAKE_GENERATOR=Ninja` + `CMAKE_MAKE_PROGRAM` for the build process only; nothing was persisted and no cache was deleted.
- **No release-marker scan this round.** The task asked for targeted Rust tests plus a current Debug build. The frozen markers in this file are unchanged, and if PM wants the release gate re-proven it is one `cargo build --release` plus the 19-marker scan.
- **Pre-existing warnings untouched** (`server.rs` unused `Mutex` / `DISCOVERY_ENDPOINT_PATH`, `wav.rs` `file_body`, `admission.rs` `denied`): outside this slice's allowed files, deliberately not cleaned.

Stop source writing on delivery.

