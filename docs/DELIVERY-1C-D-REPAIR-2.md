# Delivery 1C — D Repair 2（超时预算与手动/自动互斥）

状态：2026-09-17 返修派发与源码/构建验收完成；PM 接管适配层后独立复验通过。最终结论与文件哈希以 §8 为准；自动发现真机仍 NOT VERIFIED。

## 1. 目标

Repair 1 已修复真实 service type、认证前上传目标、0/1/≥2 选择规则和 Windows 响应体，但 PM 复审仍发现两个核心缺口。Repair 2 只修正严格总超时和手动/自动发现互斥；其它 Repair 1 决策冻结，不重做架构、不扩大产品范围。

## 2. PM 独立证据

- D 所列 9 个源文件/测试 SHA-256 均与实际文件一致；删除项确实不存在，没有发现清单外的新源文件改动。
- `watch\gradlew.bat testDebugUnitTest --rerun-tasks`：退出码 0，113 tests / 0 failed，独立耗时 2m42s。
- 当前 Debug APK SHA-256：`4E7976884D5BD2EE84EC0BD4A8F3A7F6B7CE2BDD30C837255A1E14CF4D2EE44A`，与 D 回传一致。
- 当前 Rust 测试产物从正确 crate 工作目录运行 `watch_receiver` 过滤集：48 passed / 0 failed，退出码 0。第一次从项目根运行时 6 个源码路径测试因错误 CWD 失败，不计为产品失败；更正工作目录后全部通过。
- 结论：自动化证据真实，但现有测试没有覆盖以下两个源码缺口，Repair 1 仍不能进入真机验收。

## 3. 必修 1 — 连接和读取共享一个总预算

当前 `HttpDiscoveryProbe.probe()` 把 `connectTimeout = minOf(budget, 1000)`、`readTimeout = budget`。`HttpURLConnection` 的连接与读取阶段串行发生，最坏总耗时可达 `connectTimeout + readTimeout`，因此单次探针仍可超出调用方给出的剩余预算；协调器的 3 s/8 s 墙钟检查只能在阻塞探针返回后生效。

修正要求：

- 一个探针的连接、等待响应头、读取固定小响应体必须共享同一个绝对 deadline；任何阶段开始前都用剩余时间设置下一次阻塞上限，预算耗尽立即拒绝并关闭连接。
- 不得再次把完整 budget 同时赋给串行阶段。若保留 `HttpURLConnection`，至少显式连接后扣除已耗时，再把剩余预算用于响应/读取；读取体必须有很小的字节上限，不能被慢速无限延长。
- 抽出可单测的预算分配/剩余时间逻辑，断言所有串行阻塞上限之和不大于调用方 `timeoutMs`。
- 新增真实本地 socket 回归：服务端接受连接后延迟/不返回响应，探针必须在传入预算加小量调度容差内返回 `Rejected`；测试不能只用一个会主动遵守 timeout 的 fake probe。
- 保留协调器 3 s/8 s 总窗口、每 endpoint 一次探针、listener 恰好停止一次和 0/1/≥2 规则。

## 4. 必修 2 — 手动探针与自动发现必须互斥

当前 `RecordingScreen` 在每次进入 Ready 时通过 `LaunchedEffect(ui.screen)` 调用 `startDiscovery()`；`applySettings()` 又在 `settingsApplied()` 切到 Ready 后直接启动手动探针。用户从正在自动发现的 Ready 打开 Config 时，`DisposableEffect(Unit)` 也不会停止发现。结果是自动发现与手动探针可能同时运行、互相清空 `verifiedDestination`，并由较晚完成者覆盖较早的认证结果。

修正要求：

- 同一时刻只能存在一个解析模式：自动（旧地址复查/mDNS）或手动地址探针。切到 Config 或开始手动探针前，必须取消并停止自动发现；自动发现的迟到回调不得改变手动结果。
- 手动地址存在时，不得先切 Ready 再同时触发自动发现。建议手动探针在 Config/明确 searching 状态完成：成功后 `adopt + setVerifiedDestination` 再进入 Ready；失败保持手动兜底且不可录音。
- Ready 入口只在“没有已认证目标且没有运行中的解析”时启动自动发现。手动认证成功后进入 Ready不得立刻清空该目标并重新发现；从 Recording 返回 Ready 也不得无条件重跑发现。
- 自动发现与手动探针都应具有 generation/run id；取消后的迟到结果不得持久化、不得开启录音。
- 新增行为回归而非仅源码字符串断言，至少覆盖：
  1. 自动发现进行中打开 Config 并提交手动地址，自动迟到成功不能覆盖手动成功；
  2. 手动成功进入 Ready 不再启动自动发现；
  3. 手动失败保持无目标且不可录音；
  4. 从 Recording 返回 Ready、目标仍有效时不重跑发现；
  5. 上传失败清目标后才允许为下一次录音重新发现，同一 WAV 仍不重发。

如果直接构造 `RecordingViewModel` 仍受 Android `SettingsStore`/Main dispatcher 限制，可把“Ready 入口决策 + 自动/手动 run generation”提取为纯 Kotlin 小状态机并由 ViewModel 唯一调用；不得再用只搜索源码字符串的测试代替竞态行为验证。

## 5. 允许修改

- `watch/app/src/main/java/com/sayit/watch/net/Discovery.kt`
- `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- 对应的 `watch/app/src/test/**`
- 本文档“D 回传”、根 `HANDOFF.md`、`PROJECT_PROGRESS.md`

只有真实修复需要时才可最小修改 `AndroidNsdDiscovery.kt` 的 generation 接线；不得修改依赖、manifest、版本号、Windows/Rust 端、录音/WAV/上传协议、Provider/ASR/History/Paste、设计目录或其它产品路径。需要扩围先停止并写入待审问题。

## 6. 验证与回传

- `watch\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease`
- `cd client && npm test -- --run && npm run build`
- `cd client\src-tauri && cargo test && cargo build --release`
- release marker 扫描继续为 0 命中。
- 空 Git 仓库继续回传变更路径 + SHA-256；不 reset/clean/checkout，不添加远端、不 push/merge/tag/release。

严格使用 `变更文件`、`验证结果`、`待审问题` 三个标题。完成源码和自动化验证后停止，不做真机验收。

## 7. D 回传

**历史回传：源码写入已停止。** PM 于 2026-09-17 通知接管核心适配与回归，D 在收到通知后未再做任何源码修改、未再启动新构建。本节记录中间状态；哈希采样与 PM 接管有交叠，不是独立冻结的 D 快照，最终以 §8 为准。

### 变更文件

空 Git 仓库，无基线可 diff，故不执行 `git diff --check`；以下为变更路径 + SHA-256。本轮只动了契约 §5 允许的 4 个源文件与 2 个测试文件；Windows/Rust 端、依赖、manifest、版本号均未改（哈希与 Repair 1 一致：`watch_receiver/server.rs` `0dae35f7…`、`build.gradle.kts` `e15dce30…`、`AndroidManifest.xml` `36087918…`）。

```
watch/app/src/main/java/com/sayit/watch/net/Discovery.kt            dc642c4b7cc9c04a98b1e836738a8d11dde9a9425b62679c7bb26bbbc475b8b7  14843
watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt d7cb0322cc2cf93baadcc8a18743a9d162fab7c55c6338696861c1edfab8b169  37876
watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt    365b3c9180f9e05f459a4a1c95410ae4d27a260a53eb83909758958025634c66  29852
watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt       8b7928a27220580b4545811eaecedae986297ed3801095c41f3db2f858ecdffc  22421
watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt    e70187189a038aae0cdfdbc6d6c27aeffaa08b2ee9e1e509609fc1404b12f3bf  30618（本轮未改）
watch/app/src/test/java/com/sayit/watch/net/DiscoveryRepairTest.kt  74af69cd4a47761af2d89718e6ab953ec589be7492b1af044bf4f088b0454a12  28596（新增）
watch/app/src/test/java/com/sayit/watch/ui/RecordingEntryGuardTest.kt be8c62a5d997c630c166bc16f76c64408c277d5954674675e188791372316916 8410
（本文件、HANDOFF.md、PROJECT_PROGRESS.md 亦更新；本文件自指哈希见下）
```

两个阻断的改动要点：

1. **必修 1 — 连接/响应/读取共享绝对 deadline**（`Discovery.kt`、`DiscoveryCoordinator.kt`）：
   - 新增纯 Kotlin `ProbeBudget`（实现 `ReadDeadline`）：把一次探针切成 CONNECT / HEADERS / BODY 三段，每段上限 = min(剩余时间, 段配额)，最后一段可用剩余全部；断言任何串行阻塞上限之和 ≤ 调用方 `timeoutMs`。
   - `HttpDiscoveryProbe` 从 `HttpURLConnection` 改为裸 `Socket` 手写 HTTP/1.1：**每次底层 read 前**都用 `budget.remainingMs()` 重新武装 `soTimeout`，并在循环里硬检查 deadline；`DiscoveryHttpResponse` 改为逐字节读取（`readUntilHeaderEnd` 自带 `\r\n\r\n` 终止与 8192 字节上限），不再用 `readLine()` 先无限累积；正文读取硬上限 4096 字节。**PM 补充的滴流问题已覆盖**：`soTimeout` 是单次空闲超时，慢滴流会不断续命，现在由总 deadline 与逐次重算共同收敛。
2. **必修 2 — 手动/自动互斥 + generation 隔离**（`DiscoveryCoordinator.kt`、`RecordingViewModel.kt`、`RecordingScreen.kt`）：
   - 新增纯 Kotlin `ResolverEngine`（模式 Idle/Automatic(gen)/Manual(gen)，`onReadyEntered` / `onConfigEntered` / `onManualRequested` / `onTargetInvalidated` / `onStopped` 返回 `ResolverAction`，结果按 generation 过滤）与 `ResolverBridge`（把引擎接到实际自动/手动传输上，并把最终 verdict 落成 `lastPublishedState`）。
   - `RecordingScreen` 的 Ready 入口从 `startDiscovery()` 改为 `viewModel.onReadyEntered()`（无目标且无在途解析才启动，有已认证目标时是 no-op）；Config 入口调用 `openConfig()` 释放自动运行；`DisposableEffect` 仍在离开时 `stopDiscovery()`。
   - **PM 补充的 verdict 缺口已修**：`ensureResolved(onState)` 现在把 `runId` 与 `onState` 透传进 `runDiscovery`，`ExistingAddress` / `Discovered` / `ManualFallback` 都通过 `publishCurrent(runId, …)` 真正回调给调用者（原先 `publish(…, {})` 把终态丢掉了）；被取代的 run 既不发布也不 `saveDestination`。

### 验证结果

| # | 命令 | 退出码 | 结果 |
|---|---|---:|---|
| 1 | `watch\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease` | 0 | BUILD SUCCESSFUL，**138 tests / 0 failed**（新增 `DiscoveryRepairTest` 23；`DiscoveryPureTest` 26、`RecordingEntryGuardTest` 7、`WatchUiStateMachineTest` 16 等）；lint 无 error；Debug/Release APK 均产出 |
| 2 | `client\src-tauri` → `cargo test` | 0 | 174 tests：170 passed / 0 failed / 4 ignored |
| 3 | `client\src-tauri` → `cargo build --release` | 0 | 6m25s |
| 4 | `client` → `npm test -- --run` | 0 | 31 files / 370 passed |
| 5 | `client` → `npm run build` | 0 | ✓ built in 3.47s |

APK（Debug）：`watch/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `eae2d649b9b6d6aeb389c1432a3c56481526d609282435f3869781561797c4b4`（未提交；版本仍 `0.3.0-dev.1` / versionCode 5）。

Release marker 扫描（对 `cargo build --release` 产物）：`client/src-tauri/target/release/sayit.exe`，21,508,096 字节，SHA-256 `038cbe0eb3e684a79aff08af4c6027286a1984895e3510c98c74dfc8264f3ab3`，19 个标记（含 `api/watch/discovery`、`_sayit-watch._tcp`、`spawn_registration` 等）**0 命中**。

`DiscoveryRepairTest`（23 项）里与本契约直接对应的关键项与实测耗时：

- 预算：`every serial blocking slice together never exceeds the caller budget`（多组 timeout×elapsed 组合，断言切片总和 ≤ `timeoutMs`）、`the final phase may use what is left and an exhausted budget grants nothing`。
- **真实 socket 且不靠协作式 fake**：`a peer that never answers is rejected inside the caller budget`（0.405s）、`a peer dripping the header one byte per tick cannot extend the total budget`（0.606s，每 5ms 一字节，`soTimeout` 永远不触发，靠总 deadline 收敛）、`a peer dripping the body one byte per tick cannot extend the total budget`（0.602s）、`a normal local discovery response still authenticates`、`a 401 with a drained body is a rejection`；另有 `an oversized header block is rejected without unbounded buffering`、`the body reader stops at the byte cap`。
- 互斥与 generation：`a late automatic result cannot overwrite a manual success`、`a successful manual target is not re-discovered on Ready entry`、`a failed manual probe keeps the manual fallback and no target`、`returning from Recording with a valid target does not restart discovery`、`an upload failure clears the target and only then allows re-resolution`、`a cancelled run cannot publish after teardown`、`an invalid token fails closed without any transport call`。
- 生产协调器真实回调（用户补充项）：`the coordinator publishes its final authenticated verdict to the caller`（断言回调恰好收到 `[Searching, Discovered]` 且 `saveDestination` 落盘一次）、`the coordinator publishes the manual fallback when nothing authenticates`、`a stale generation neither publishes nor persists after a stop`、`a saved address verdict is published as ExistingAddress and persists nothing new`。

### 待审问题

- **PM 已指出的两处 ViewModel 适配缺陷（D 未修补，按接管通知保留现场）**：
  1. `RecordingViewModel.executeAutomatic` 里 `active.ensureResolved { … }` 只是启动 job 的非 suspend 调用，随后立即读取 `AtomicReference`，实际总会先拿到 `Searching`，因此在真实异步运行下永远返回 `null` —— 自动发现成功无法经此路径成为已认证目标。
  2. `executeManual` 在 bridge 的 generation 校验之前就调用了 `active.adopt(candidate)`，所以一个已被取代的旧手动结果仍可能先持久化。
  这两点都落在 ViewModel 适配层；本轮的 23 项 `DiscoveryRepairTest` 是**纯 bridge** 行为测试，不覆盖该适配层，故不能据此认为自动/手动链路端到端可用。
- **因此本轮不能被判定为可用**：自动发现的「成功 → 已认证目标 → 可录音」闭环尚未被任何测试证明（`existingAddress/discovered` 的生产回调测试只证明协调器会发布终态，不证明 ViewModel 会正确消费）。
- `RecordingViewModel` 仍无直接单测（需要 Context 绑定的 `SettingsStore` 与 Main dispatcher），这也是上述适配层缺陷未被自动发现的原因；若要根治，建议由 PM 在接管时引入 `Dispatchers.setMain` + mock Context，或把适配层进一步提取为纯 Kotlin 并由行为测试覆盖。
- 真机与设备验收未做（契约 §6 明确不做）；Release marker 扫描只证明 release 二进制不含 receiver/discovery/mDNS 入口，不证明自动发现功能可用。
- 其余 Repair 1 遗留项（`0.0.0.0` 广播地址、Android 17 本地网络权限、同 Token 第二台 PC 的歧义判定口径）状态不变，见 `docs/DELIVERY-1C-D-REPAIR-1.md` §9。
- 环境提示：本机 Android SDK 在 `%LOCAL_BUILD_ROOT%\sdk`，`cargo` 需 `C:\Program Files\CMake\bin` 在 PATH；本机执行策略禁用 `.ps1` 与 `npm.ps1`，改用内联命令与 `npm.cmd`。

## 8. PM 最终验收（2026-09-17）

同步记录：最终结论已通过原 Saylt V2 对话的可见发送按钮送达，界面显示 18:27 的 PM 最终同步消息；明确要求 D 停止写入/构建，无新派单。

**结论：Repair 2 源码/自动化构建阶段通过并关闭；自动发现真机 NOT VERIFIED。** 已实际向原 Saylt V2 对话派发返修包。D 停止源码写入后，PM 接管同一核心需求反复遗漏的生产适配层；没有启动新的外包阶段。

### 最终修正与核心场景

- 生产 ViewModel 与测试共用 `ResolverBridge.forCoordinator`。自动分支等待真实协调器终态，解决“启动后立即读 Searching/null”；手动结果通过 generation 校验后才 adopt/save，旧结果不能持久化。
- cancel 同时使 mode 归 Idle；Config、停止、目标失效清理已认证目标。异步方法返回是否真正接纳结果，过期协程不能更新 UI。
- 协调器先登记 LAZY job 再启动，避免同步成功留下错位句柄；listener 的启动/停止与 run ownership 一致，旧 finally 不能停止新浏览。发布结果与保存目标在 run lock 内校验。
- 时钟统一为单调时钟；逐次读取重算剩余时间、头/正文限制保留，并添加总截止时间 socket closer 覆盖写入阻塞。

| 核心场景 | 本轮证据与结论 |
|---|---|
| 不响应、慢滴流头/正文仍受总预算限制 | 实际本地 socket 测试通过；不是仅 cooperative fake |
| 异步浏览成功成为已认证目标；失败进入手动兜底 | 生产 factory + 真实 Coordinator 的异步测试通过 |
| Config 取消自动、手动成功；旧自动/手动结果不得覆盖或保存 | 同一生产 adapter 回归通过，晚到手动结果持久化次数为 0 |
| Ready 已有认证目标不重复解析；停止不重复停 listener | bridge 与 integration 回归通过 |
| 同步旧地址成功后下一次异步运行仍可取消 | 新增 Unconfined 调度回归通过 |
| 真机发现后录音、上传以及实际 mDNS 网络行为 | **未验证**；上述测试不替代 Android NSD/Compose/设备端到端验收 |

### 验证与证据归属

- PM 强制全量：`gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease`，最终日志 **BUILD SUCCESSFUL in 2m 21s**，101 tasks executed；XML 汇总 **144 tests / 0 failures / 0 errors**。日志：`%TEMP%/saylt-pm-repair2-final.log`；报告：`watch/app/build/test-results/testDebugUnitTest/`、`watch/app/build/reports/lint-results-debug.html`。当前会话恢复后进程句柄已失效，最终成功由落盘日志及 XML 确认，不伪造重新取得的退出码。
- 新增 PM `DiscoveryIntegrationTest` **6/6**；D `DiscoveryRepairTest` **23/23**。第一次 PM 全量运行 144 项有 1 项失败：立即 stop 的旧断言要求尚未启动的 listener 必须 stop 一次；修正为 start/stop 次数配对并加强生产 listener 互斥，最终重跑全通过。首次失败日志：`%TEMP%/saylt-pm-repair2-full.log`，保留。
- 未改变 Windows/client 源码，复用本轮 D 已执行日志：`%LOCAL_BUILD_ROOT%/r2-client-test.log`（370 passed）、`r2-client-build.log`（成功）、`r2-cargo-test.log`（170 passed / 4 ignored）、`r2-cargo-release.log`（成功）。这些是 D 执行证据，不冒充 PM 重新构建。
- PM 独立扫描 release EXE：19 marker **全部 0 命中**；SHA-256 `038CBE0EB3E684A79AFF08AF4C6027286A1984895E3510C98C74DFC8264F3AB3`。
- 最终 Debug APK：`watch/app/build/outputs/apk/debug/app-debug.apk`；SHA-256 **`CC79402608FEB54F5B18566C54B0FCB78B5A4943A715B16592039DD3BE705315`**。版本不变：0.3.0-dev.1 / versionCode 5；未安装、未提交。
- 空 Git 仓库仍无提交基线。与派发前文件哈希集合比较，仅下列 8 个源码/测试文件及本任务文档、HANDOFF、PROJECT_PROGRESS 改变；无删除。锁文件不在该集合中，本轮未编辑依赖/锁文件。未 reset/clean、未加远端、未 push/merge/tag/release。

### 最终源码 SHA-256（取代 §7 中间快照）

| 路径 | SHA-256 |
|---|---|
| `watch/app/src/test/java/com/sayit/watch/ui/RecordingEntryGuardTest.kt` | `BE8C62A5D997C630C166BC16F76C64408C277D5954674675E188791372316916` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRepairTest.kt` | `74AF69CD4A47761AF2D89718E6AB953EC589BE7492B1AF044BF4F088B0454A12` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt` | `EECED41FDEF4ECA40A09565435DE1F36352E1944919D7685D75FBE6ED2E39CA6` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryIntegrationTest.kt` | `8947086E13D1937F30193E5DE3CDDD2F6FB40417A564EDC80C0D246C14B1B163` |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` | `365B3C9180F9E05F459A4A1C95410AE4D27A260A53EB83909758958025634C66` |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` | `8B7928A27220580B4545811EAECEDAE986297ED3801095C41F3DB2F858ECDFFC` |
| `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt` | `68BFD41FB61D943B5C64DEA39DAC6CD425F1FD9B78EF7C0735052A66EFF8E3F7` |
| `watch/app/src/main/java/com/sayit/watch/net/Discovery.kt` | `DC642C4B7CC9C04A98B1E836738A8D11DDE9A9425B62679C7BB26BBBC475B8B7` |

### 未解决项与交接

真实 Galaxy Watch、组播隔离、防火墙 UDP 5353、多网卡/地址变化、8 秒窗口体验、发现后真实录音上传仍需设备验收。Repair 1 §9 的既有环境/权限/绑定地址边界维持，不在本轮扩大。源码通过不等于完整产品 VERIFIED。进度权威为 `PROJECT_PROGRESS.md`；D 保持停止，不再修改源码或启动构建，下一任务由用户决定。
