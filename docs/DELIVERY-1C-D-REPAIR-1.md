# Delivery 1C — D Repair 1（自动发现核心契约修正）

状态：D 已回传；PM 复审 NO-GO，两个剩余核心缺口转入 `docs/DELIVERY-1C-D-REPAIR-2.md`。

## 1. 目标

只修正本轮自动发现实现中已经定位的核心偏差，使真实 Android `NsdManager` 回调能够产生候选、任何录音只发往已通过 Bearer 探针的目标，并严格实现 0/1/多个认证成功者的冻结选择规则。不要扩展功能或改动既有录音、WAV、Provider、ASR、History、Paste、焦点、VAD、备份、更新、发布链路。

## 2. PM 复审基线与结论

- 复审对象：`docs/DELIVERY-1C-D-AUTO-DISCOVERY-HANDOFF.md` §9 所列文件及哈希。
- 交付哈希复算：除交接文档的自指哈希外，其余所列文件均与回传一致。
- 范围：`MainActivity.kt` 是原允许清单外改动。PM 确认其三行 `applicationContext` 接线属于实现 `NsdManager` 的必要最小改动；Repair 1 将它正式纳入允许路径，但这不追认其它扩围。
- 独立验证：`watch\gradlew.bat testDebugUnitTest --rerun-tasks` 退出码 0，100 tests / 0 failed；Debug APK SHA-256 仍为 `653D98DCDF6E8E443AC4D5A1089DD766CDA5EF21A6044E5B574A2EFD1FFFD00C`。
- Rust 独立重编译：`cargo test watch_receiver` 在外部 `transcribe-cpp-sys` CMake/MSBuild 缓存处因 `FTK1011` 退出 101，尚未进入本项目测试；保留 D 本轮 `cargo test` 174（170 passed / 4 ignored）为执行者证据，不提升为 PM 新证据。
- 结论：自动发现源代码门槛不通过，不能安装或做真机验收；完成以下四项后回传 PM。

## 3. 必修 1 — 使用真实 service type，不把实例名当类型

当前 `AndroidNsdDiscovery.addResolved()` 把 `NsdServiceInfo.serviceName` 写入 `ResolvedService.serviceName`，而 `DiscoveryPolicy.candidate()` 又要求该字段以 `_sayit-watch._tcp.` 结尾。Android API 将 `serviceName`（实例名，例如 `SayIt`）与 `serviceType` 分开提供，因此真实回调会被当前纯 Kotlin 策略拒绝；现有测试通过，是因为 fake 把 `serviceName` 人工构造成 `SayIt_sayit-watch._tcp.`。

修正要求：

- `ResolvedService` 明确分离实例名与服务类型；候选策略对真实 `serviceType` 做精确校验，不再从实例名后缀推断类型。
- `AndroidNsdDiscovery` 从 `NsdServiceInfo.serviceType` 传入类型，并继续只解析冻结类型。
- 新增回归：实例名仅为 `SayIt`、类型为 `_sayit-watch._tcp.` 时可进入认证探针；实例名任意但类型错误时拒绝。
- 覆盖连续/并发发现两项服务的解析，不得因多个 `resolveService` 同时在途而丢掉第二项；可以串行化解析或采用与 minSdk/target SDK 兼容的官方 API，但不得引入第三方 mDNS 库。

## 4. 必修 2 — 未认证地址绝不能成为上传目标

当前 `applySettings()` 在探针完成前把手动 IP/端口写入 `SettingsStore` 并切到 Ready；`currentDestination()` 在 `coordinator.resolved == null` 时又回退到任何格式合法的保存地址。启动时同样先进入 Ready，再异步复查旧地址。这会让用户在探针完成前录音，并可能把音频发给未认证、已过期或 DHCP 后被另一设备占用的地址，直接违反“只向认证目标发送”的核心边界。

修正要求：

- 手动地址只有在认证探针成功后才能持久化并成为 `resolved`；探针失败不得覆盖上次已认证记录，也不得留下可上传的新地址。
- `currentDestination()` 只能返回本轮已认证的内存目标；不得以“格式合法”为由回退到未复查的保存值。
- 启动、旧地址复查、自动浏览和手动探针期间，录音入口必须禁用或成为明确 no-op；只有得到唯一已认证目标后才可录音。发现失败时允许进入手动设置，但仍需手动地址认证成功后才可录音。
- 上传失败可以为下一次新录音触发/准备重新发现，但同一 WAV 仍必须丢弃且绝不重发。
- 新增 ViewModel/状态机回归：旧地址尚未复查、手动探针在途、手动探针失败、自动发现失败四种状态均无法得到上传目标；认证成功后恰好得到一个目标。

## 5. 必修 3 — 0/1/多个认证成功者按冻结规则收敛

当前协调器遇到第一个认证成功者即 `return`，与任务包“0 个或多个匹配进入手动兜底；不得依据发现顺序选择”冲突。相同 Token 的两台 PC 仍是两个不同 endpoint，必须按冻结规则视为歧义，而不是取先到者。

修正要求：

- 在一个有界发现周期内对不同 endpoint 去重并逐个认证，记录所有认证成功者。
- 周期结束时：恰好 1 个成功者才保存并进入可录音 Ready；0 个或至少 2 个均进入 `ManualFallback`，不保存新目标。
- 新增至少三项回归：两个服务仅一个认证成功；两个都失败；两个不同 endpoint 都认证成功。第三项必须断言不保存、不选择、与到达顺序无关。
- Android 解析层必须能把两台服务都送到纯 Kotlin 协调器，不能只测人为构造的候选列表。

## 6. 必修 4 — 严格时间边界与 discovery 响应体

- “旧地址短探针最多 3 秒”和“自动发现最多 8 秒”是总时限。当前阻塞探针自身可用 2 秒 connect + 2 秒 read，循环只在探针返回后检查墙钟，两个阶段都可能越过总时限。修正为端到端有界；测试须用可控的慢探针证明超时后不再保存/发布成功状态，并验证 listener 最终只停止一次。
- Windows `GET /api/watch/discovery` 的成功体按冻结契约只返回固定 `service` 与 `protocol`。移除当前额外返回的 `path` 字段；TXT 中的 `path=/api/watch/discovery` 保持不变。401 体和常量时间 Bearer 校验保持不变。

## 7. 允许修改

- `client/src-tauri/src/watch_receiver/**`
- 与成功响应精确体相关的 Rust 测试
- `watch/app/src/main/java/com/sayit/watch/net/**`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`（仅录音可用状态/必要文案）
- `watch/app/src/main/java/com/sayit/watch/MainActivity.kt`（只保留/必要修正现有 NSD Context 接线）
- 对应的 `watch/app/src/test/**` 与必要字符串资源
- 本文档“D 回传”小节、根 `HANDOFF.md`、`PROJECT_PROGRESS.md`

不得修改 Cargo/Gradle 依赖版本、Android manifest、版本号、录音/WAV/上传协议、Provider/ASR/History/Paste、设计冻结目录或其它产品路径。需要扩围时先停止并写入待审问题。

## 8. 验证与回传

完成后运行并回传退出码与摘要：

- `watch\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease`
- `cd client && npm test -- --run && npm run build`
- `cd client\src-tauri && cargo test && cargo build --release`
- release marker 扫描继续证明 Watch receiver、discovery endpoint、mDNS 注册入口均不进入 release 二进制。
- 当前空 Git 仓库继续用变更路径 + SHA-256，不执行 reset/clean/checkout，不添加远端、不 push/merge/tag/release。

严格用三个标题回传：`变更文件`、`验证结果`、`待审问题`。完成源代码与自动化验证后停止；不安装手表、不修改桌面软件、不做真机闭环。

## 9. D 回传

### 变更文件

当前 `Saylt` 检出仍是空 Git 仓库（`git log` 报 `your current branch 'master' does not have any commits yet`），无基线可 diff，故不执行 `git diff --check`；以下为变更路径 + SHA-256。

本轮（Repair 1）改动 9 个源文件/测试 + 3 个文档，删除 1 个测试文件；其中 `Cargo.toml`、`Cargo.lock`、`watch_receiver/mod.rs`、`watch_receiver/mdns.rs`、`build.gradle.kts`、`MainActivity.kt`、`strings.xml` 内容与上轮完全一致（哈希同上轮回传），本轮没有改依赖、版本号或 manifest。

```
client/src-tauri/src/watch_receiver/server.rs                       0dae35f78f471edd6fb4b4f4bde9e45879c9d9b6f069086bcc8e4cbd9167a7c2  52604
watch/app/src/main/java/com/sayit/watch/net/Discovery.kt            1e2f99f292c36945be2e3c1dfb2e5eb4e0f273cf45f0f4dab7c4c5347fb26da5  7220
watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt  dcd7679b9ec666949c5e0066e3409d0eb1605922ef057b9b4ed25d847515ba9f  18153
watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt   3ff941a9376159db858a297312e8f61f8b96185b46f42956291f4637479689d5  6866
watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt     9bb332da5d6f81f36d5474f22d7527d99786969d15b93813e236bea1d67a7909  28156
watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt       27eb4e6d5b380316b9bf90f697c26c301ec6e77cbbd07ae36616ad35a7398f37  22221
watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt     e70187189a038aae0cdfdbc6d6c27aeffaa08b2ee9e1e509609fc1404b12f3bf  30618
watch/app/src/test/java/com/sayit/watch/ui/RecordingEntryGuardTest.kt d81a64da9da7da59c727f7c25253466c097411028415ceb71b78388295e53bfe  7249
watch/app/src/test/java/com/sayit/watch/ui/WatchUiStateMachineTest.kt 5d66c3daa9a1e48e820f5f4b99a2081f286d813f5b52f426fda7bd2f91c623e7  9801
删除：watch/app/src/test/java/com/sayit/watch/ui/RecordingViewModelDiscoveryTest.kt
未改动（哈希与上轮一致）：Cargo.toml f5057342… / Cargo.lock ce0c43e9… / watch_receiver/mod.rs d534cd6a… / watch_receiver/mdns.rs 765ab233… / build.gradle.kts e15dce30… / MainActivity.kt 48cda0ad… / strings.xml 95188fa1…
本文档、根 HANDOFF.md、PROJECT_PROGRESS.md 亦更新（本文档自指哈希无法自洽：写入哈希必然改动文件，评审时以重新计算值为准；其余 9 个文件的行上哈希均为最终值）。
```

四项阻断的修正方式：

1. **必修 1（真实 service type）**：`ResolvedService` 拆成 `instanceName` 与 `serviceType` 两个字段，`DiscoveryPolicy.candidate()` 只对 `serviceType` 做精确校验（`_sayit-watch._tcp.` / 全名），实例名可为 `SayIt`、`SayIt (2)`、空串等任意值；`AndroidNsdDiscovery` 分别从 `NsdServiceInfo.serviceName` 与 `serviceType` 取值。并发解析不丢失：Android 层给每次浏览一个 generation，回调携带自己的 generation，过期会话的迟到回调直接丢弃（不会污染下一次浏览），同一会话内多个 `resolveService` 全部按 endpoint 去重后汇总快照下发；累加逻辑抽成纯函数 `DiscoveryPolicy.foldResolved()`，用双线程并发投递的 JVM 测试钉住「第二台不会被丢掉」。
2. **必修 2（未认证地址绝不能成为上传目标）**：`applySettings()` 不再把手动 IP/端口写进设置——只有认证成功后才由 `coordinator.adopt()` 持久化；失败时上一次已认证记录不被覆盖，也不留下新地址。`currentDestination()` 现在只返回本轮认证过的内存目标（`verifiedDestination`），彻底删除「格式合法即回退」的旧逻辑。录音入口双重闸门：`WatchUiState.canRecord`（Ready + 非上传 + 已认证）与 `startRecording()` 内 `verifiedDestination == null` 检查；旧地址复查、浏览、手动探针在途时 `transportAvailable=false`，录音按钮变灰且点击为 no-op。上传失败仍然只让「下一次录音」重新发现，同一段 WAV 丢弃且绝不重发。
3. **必修 3（0/1/≥2 冻结规则）**：`browseAndAuthenticate()` 在 8 秒窗口内对**每个不同 endpoint 各探一次**，把全部认证成功者收集起来后由调用方按 `size == 1` 判定：恰好 1 个才保存并进入 `Discovered`，0 个或 ≥2 个一律 `ManualFallback` 且不保存任何目标——与到达顺序无关（测试对两种投递顺序各断言一次）。「每个 endpoint 只探一次」同时修掉了本轮自查发现的一个真实缺陷：被拒绝的候选原先会被反复重探（8 秒窗口内对同一地址约 160 次请求）。
4. **必修 4（端到端时限与响应体）**：`DiscoveryProbe.probe()` 现在必须接收 `timeoutMs`，由协调器按「阶段剩余预算」下发（旧地址复查阶段 3 000 ms、浏览阶段 8 000 ms 为总上限；单次尝试上限 1 000 ms / 1 500 ms），阶段开始时间用注入时钟计算，因此任何一次阻塞探针都不可能越过阶段预算——测试用「吃掉 90% 预算的慢探针」验证真实耗时仍落在 3 s+8 s 之内、且所有下发超时都不超过阶段预算。Windows `GET /api/watch/discovery` 成功体现在只返回 `{"service":…,"protocol":…}`，`path` 字段已移除（TXT 里的 `path=/api/watch/discovery` 保持不变，401 体与常量时间 Bearer 校验不变）。

### 验证结果

| # | 命令 | 退出码 | 结果 |
|---|---|---:|---|
| 1 | `watch\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease`（`JAVA_HOME`=Temurin 17.0.20.1，`ANDROID_HOME`=`%LOCAL_BUILD_ROOT%\sdk`） | 0 | BUILD SUCCESSFUL，**113 tests / 0 failed**（`DiscoveryPureTest` 26、`RecordingEntryGuardTest` 5、`WatchUiStateMachineTest` 16，其余为既有用例）；lint 无 error；Debug 与 Release APK 均产出 |
| 2 | `client\src-tauri` → `cargo test`（PATH 前置 `C:\Program Files\CMake\bin`） | 0 | 174 tests：**170 passed / 0 failed / 4 ignored** |
| 3 | `client\src-tauri` → `cargo build --release` | 0 | `Finished release profile [optimized] in 6m 25s` |
| 4 | `client` → `npm test -- --run` | 0 | Test Files 31 passed (31)，Tests **370 passed (370)** |
| 5 | `client` → `npm run build` | 0 | tsc + vite `✓ built in 3.23s` |

APK：

- Debug：`watch/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `4e7976884d5bd2ee84ec0bd4a8f3a7f6b7ce2bdd30c837255a1e14cf4d2ee44a`，20,475,788 字节（未提交；版本仍为 `0.3.0-dev.1` / versionCode 5，依赖与 manifest 未改）
- Release（未签名，仅证明可构建）：`watch/app/build/outputs/apk/release/app-release-unsigned.apk`，SHA-256 `aabd9cf9f65c2698b506ae32e9726f4af8c4c9c98d73f4d3fdc4e09fe65fb2f0`

Release marker 扫描：对 `client/src-tauri/target/release/sayit.exe`（21,508,096 字节，SHA-256 `627caf344d96649be38ddf79512774726f243f6cbf6690f6c1eb99efc4cba91c`）ASCII 全文扫描 19 个标记 —— `watch-receiver`、`SAYIT_WATCH_BIND_IP`、`SAYIT_WATCH_DEV_TOKEN`、`received_watch.wav`、`X-Request-Id`、`api/watch/audio`、`api/health`、`watch_admission_resolve`、`watch_run_started`、`watch_run_aborted`、`watch_read_reserved_pcm`、`watch_gate_state`、`AdmissionGate`、`api/watch/discovery`、`_sayit-watch._tcp`、`sayit-watch-debug-receiver`、`build_service_info`、`spawn_registration`、`watch-mdns-register` —— **19 检查 / 0 命中**；另查 `mdns-sd`、`_services._dns-sd`、`ServiceDaemon`、`if-addrs`、`sayit-watch` 也全部 0 命中。

新增/改写的回归（按 §8 要求）：

- **真实回调形态**：`a real instance name with the frozen service type is accepted`（实例名 `SayIt` / `SayIt (2)` / 空串 + 类型 `_sayit-watch._tcp.` 均进入探针）、`a wrong service type is rejected regardless of the instance name`（含实例名伪装成类型的反例）。
- **并发解析不丢失**：`concurrent resolves for two hosts both survive folding`、`folding is thread safe under simultaneous deliveries`（两线程同时投递，两项都在）。
- **未认证上传窗口**：`WatchUiStateMachineTest` 四态（只有 Token 未探针、复查/浏览在途、手动探针在途、手动/自动探针失败）+ 认证成功后恰好一个目标；`RecordingEntryGuardTest`（源码级）钉住 `currentDestination()` 只返回 `verifiedDestination`、两个录音入口都要求已验证目标、`applySettings()` 绝不预先持久化手动地址、手动探针失败分支不 `adopt()`、`send()` 失败后清目标且不重发。
- **双匹配歧义**：`two endpoints authenticating is ambiguity and saves nothing`（两种到达顺序都断言不保存、不选择、两地址都被认证过）。
- **慢探针超时**：`a hanging saved-address probe cannot exceed the 3 s phase budget`、`a hanging browse probe cannot exceed the discovery window`（真实墙钟计时 + 吃掉预算的探针，断言总耗时与下发超时都在 3 s / 8 s 之内，且 listener 恰好停止一次）。
- **响应体**：Rust `discovery_probe_returns_only_the_frozen_identity` 现在逐字节断言 `{"service":"…","protocol":1}` 且 `!body.contains("path")`；Watch 侧 `the success body is service plus protocol with no extra field` 钉住冻结体。

### 待审问题

- **未验证真机**（与上轮相同，仍需 PM 设备验收）：mDNS 组播是否被路由器隔离、Windows 防火墙 UDP 5353、多网卡下默认路由选出的地址是否可达、Galaxy Watch 7 上 `NsdManager` 真实解析时延能否在 8 秒窗口内送出至少一个候选。本轮全部为源码 + JVM/真机无关验证。
- **发现阶段测试的计时方式**：`DiscoveryPureTest` 里依赖阶段预算的用例使用**真实墙钟**（生产 3 s / 8 s）而不是虚拟时间——因为协调器用真实 `delay` 轮询并读取真实时钟，虚拟延时不计入墙钟会让预算失去意义。代价是该测试类整体约需 1.5–2 分钟。若要更快，可把 `DiscoveryTiming` 注入成更小的值（接口已支持），但那就不再验证冻结的 3 s / 8 s 数值；本轮选择保真。
- **`RecordingViewModel` 仍无直接单测**：构造它需要 Context 绑定的 `SettingsStore`，本 JVM 环境没有可用 Android Context，且 `viewModelScope` 在纯 JVM 下需要 Main dispatcher。因此 ViewModel 侧行为由状态机测试（四态 + 单目标态）与源码级守卫测试（`RecordingEntryGuardTest`）覆盖；本轮删除了上轮那个能编过但断言不充分的 `RecordingViewModelDiscoveryTest`。如需真正的 ViewModel 单测，建议单独授权引入 `Dispatchers.setMain` 与 mock Context（属测试基建扩围）。
- **同 Token 的第二台 PC**：本轮按 §5 冻结规则把「≥2 个认证成功」判为歧义 → 手动兜底。需要说明协议上仍无法区分「同一服务的重复广播」与「两台都持有同一 Token 的 PC」；重复广播已按 endpoint 去重（算 1 个），两台真实不同 endpoint 才算歧义。若要区分，需要新的非敏感唯一实例字段，属扩围。
- **`0.0.0.0` 绑定下的广播地址**（上轮已记录，本轮未动）：`bootstrap_from_dev_token` 仍会落回 `0.0.0.0:18099`，此时广播地址取默认路由的本地 IPv4；无默认路由则放弃广播、只保留手动路径。通配监听例外不在本任务范围。
- **Android 17 本地网络权限**：未新增任何 Android 17 专属权限（manifest 未改，有单测钉住）。
- **环境差异（PM 复现提示）**：本机 Android SDK 在 `%LOCAL_BUILD_ROOT%\sdk`（`ANDROID_HOME` 未持久化），`cargo` 需 `C:\Program Files\CMake\bin` 在 PATH；本机执行策略禁用 `npm.ps1` 与 `.ps1` 脚本，我改用 `npm.cmd` 与内联命令运行。
- **环境噪声（非本任务引入）**：`cargo test` 仍有既有告警 `src/watch_receiver/wav.rs:54 unused variable: file_body`；`AndroidNsdDiscovery.kt` 使用 `NsdServiceInfo.host` 有一条 deprecated 告警。均未顺手修改。
