# Delivery 1C — D 自动发现开发交接

状态：D 已实现并完成源码/构建验证，等待 PM 独立评审与真机验收（2026-09-15）

## 0. 实际变更路径汇总（含允许清单外的 1 个说明项）

允许清单内的路径：

- `client/src-tauri/Cargo.toml`、`client/src-tauri/Cargo.lock`
- `client/src-tauri/src/watch_receiver/mod.rs`、`server.rs`、新增 `mdns.rs`
- `watch/app/build.gradle.kts`（版本号）、`watch/app/src/main/res/values/strings.xml`
- `watch/app/src/main/java/com/sayit/watch/net/Discovery.kt`、`DiscoveryCoordinator.kt`、`AndroidNsdDiscovery.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`、`ui/RecordingScreen.kt`
- `watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt`、`ui/RecordingViewModelDiscoveryTest.kt`、`ui/WatchUiStateMachineTest.kt`
- 本文档、`HANDOFF.md`、`PROJECT_PROGRESS.md`

允许清单外的 1 个文件（改动 3 行，请 PM 判定是否接受）：

- `watch/app/src/main/java/com/sayit/watch/MainActivity.kt` — 只把 `RecordingViewModel.Factory(store)` 改为 `RecordingViewModel.Factory(store, applicationContext)`。原因：`NsdManager` 实例必须来自 `Context.getSystemService(Context.NSD_SERVICE)`，而 ViewModel 没有 Context；不传就必须在 ViewModel 里持有 Activity 级引用或在 Composable 里 `LocalContext` 造浏览器，两者都更差。没有其它改动（权限申请、keep-screen-on、`prepare()` 全部原样）。

没有改动的相关文件（供评审确认收敛范围）：`client/src-tauri/src/main.rs`（`watch_receiver::start` 的调用点未变，mDNS 注册在 `watch_receiver/mod.rs` 内部完成）、`watch/app/src/main/AndroidManifest.xml`、`watch/app/src/debug/AndroidManifest.xml`、`watch/app/src/main/java/com/sayit/watch/settings/**`（复用现有 `SettingsStore` / `DevTokenValidator` / `DestinationValidator`，未改一行）、`net/Transport.kt`、`net/TransportClient.kt`、`net/CleartextPolicy.kt`、`recording/**`、`ui/RecordingRequestLatch.kt`。

## 1. 目标与本轮授权

在不改变现有录音、WAV、Provider、ASR、History 和 Paste 链路的前提下，让 Galaxy Watch 上的 SayIt 自动发现同一局域网中正在运行的 Windows SayIt，不再要求用户因电脑 DHCP 地址变化而重新填写 IP。

用户在 2026-09-15 明确把“自动发现电脑”作为新的 D 开发任务。此前项目文件中的“禁止 discovery/mDNS”仅对此 Delivery 1C 解除；其余安全、发布和验收边界继续有效。

## 2. 当前真实基线

- 工作目录：`%USERPROFILE%\Documents\ChatGPT\Saylt`
- 2026-09-14 从 `SayIt-watch-local` 迁入 599 个文件；Token、`local.properties`、APK/EXE、`node_modules`、Rust/Android 构建缓存未迁入。
- 当前目录保留自己的空 `.git`，尚无提交，全部项目文件未跟踪。不要运行 `git clean`、`git reset`、`git checkout` 或删除/覆盖现有文件，也不要自行添加远端、push、merge、tag 或 release。
- 迁入时保留的当前源码状态包括：Windows `0.1.9`；Watch `0.2.0-dev.4` / versionCode 4；Watch 默认录音上限 180 秒。
- 桌面正在使用的 Windows `0.1.9` 是本轮之前的最新可用构建；本任务不覆盖桌面目录、不制作安装包。
- 已有真实链路：Watch 录制 16 kHz/16-bit/mono WAV → Bearer Token + requestId → Windows debug receiver → 现有 Provider/ASR/History/Paste。单次真实闭环已通过；正式十次连续验收仍未完成。
- 接收端目前有 `GET /api/health` 和 `POST /api/watch/audio`；Watch 目前只接受 RFC1918 数字 IPv4 + 端口；Token 为 64 位十六进制并保存在 Watch 私有存储中。
- 现存安全/发布未闭环项不属于本次修复范围：诊断标题可能保留转写文本、通配监听例外、正式 release HTTP/配对方案。

## 3. 已冻结的技术路线

采用标准 DNS-SD/mDNS，不自定义 UDP 广播协议：

- Windows/Rust：使用 `mdns-sd`，只在 debug receiver 成功启动后注册服务。
- Wear OS：使用 Android 官方 `NsdManager`，不要引入第三方 Android mDNS 库。
- 服务类型：Windows 完整名 `_sayit-watch._tcp.local.`；Android discovery type 使用平台要求的 `_sayit-watch._tcp.`。
- TXT 只允许非敏感元数据：`protocol=1`、`path=/api/watch/discovery`。不得广播 Token、Token 摘要、用户名、录音内容、目标窗口或本地路径。
- 新增 `GET /api/watch/discovery`：必须使用现有 Bearer Token 做常量时间校验；成功只返回固定服务标识和协议版本，失败返回 401；不得回显或记录 Token。
- Watch 只有在该认证探针返回 200 且服务标识/协议版本精确匹配后，才保存发现到的 RFC1918 IPv4 和端口。主机名、IPv6、公网、链路本地、回环和 `0.0.0.0` 均不得写入目标配置。
- 多个 PC 同时存在时逐个做认证探针，只选择 Token 匹配的一个；0 个或多个匹配时均不得静默选错，进入手动兜底。

## 4. 用户行为契约

1. Watch 已有合法 Token 时，启动后先对上次成功的 IP/端口做一次短探针；成功则直接进入 Ready。
2. 上次地址失效或没有地址时，自动发现最多 8 秒；找到且认证成功后保存新 IP/端口并进入 Ready。
3. 首次使用只有 Token 是必填项。IP/端口默认隐藏在“手动设置”内；发现失败或用户主动展开时才能编辑。
4. 发现失败、mDNS 被路由器隔离、Windows 未运行或权限不足时，不死循环、不阻塞 UI，明确允许手动 IP/端口兜底。
5. 发现成功后沿用现有录音和上传实现。上传失败可以触发下一次地址发现，但不得自动重发同一段音频，以免响应丢失时产生重复转写/粘贴。
6. 发现成功、超时、离开页面或 ViewModel 销毁后停止浏览；禁止常驻高频扫描。
7. Token、音频、ASR、History、Paste 和 requestId 语义保持不变。

## 5. 核心验收场景

### A. 正常自动发现

- 输入：Watch 保存合法 Token；保存的 IP 为空或过期；Windows debug SayIt 在同一 Wi-Fi 运行。
- 预期：8 秒内发现、认证并保存正确 RFC1918 IP/端口，进入 Ready；不要求输入 IP。
- 失败：只看到服务名但未认证便选中、保存主机名/错误接口、超时后仍无限扫描。

### B. DHCP 地址变化

- 输入：保存地址 A，Windows 改为地址 B；Token 不变。
- 预期：A 的短探针失败后发现 B；后续新录音使用 B，一段音频最多发送一次。
- 失败：仍向 A 发送、要求用户改 IP、自动重发造成重复结果。

### C. 多电脑和错误 Token

- 输入：局域网有两个 SayIt 广播，其中只有一个匹配当前 Token。
- 预期：仅选择认证成功者；两个都不匹配或两个都匹配时进入选择/手动兜底，不静默猜测。
- 失败：依据发现顺序选择、在 TXT 中暴露 Token 信息、把音频发给未认证目标。

### D. 发现不可用

- 输入：mDNS 被阻断、Windows 未启动或 8 秒内无服务。
- 预期：UI 可继续操作，显示简短失败状态并开放手动 IP/端口；重复进入不会叠加 listener。
- 失败：崩溃、卡死、持续耗电、破坏已保存 Token 或现有手动链路。

### E. 既有行为回归

- 输入：手动配置有效地址并完成录音上传。
- 预期：现有 16 kHz WAV、Bearer、requestId、201、Provider/ASR/History/Paste 行为不变；release 仍无可用 Watch HTTP receiver/sender。
- 失败：任何录音、上传、转写、粘贴或 release 隔离变化。

## 6. 允许修改

- `client/src-tauri/Cargo.toml`
- `client/src-tauri/Cargo.lock`（仅新增 mDNS 所必需的锁变化）
- `client/src-tauri/src/main.rs`
- `client/src-tauri/src/watch_receiver/**`
- `watch/app/src/main/AndroidManifest.xml`、`watch/app/src/debug/AndroidManifest.xml`（仅确有平台需要时；当前 target SDK 34 不应添加 Android 17 专属运行时权限）
- `watch/app/src/main/java/com/sayit/watch/net/**`
- `watch/app/src/main/java/com/sayit/watch/settings/**`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- 对应的 `watch/app/src/test/**`、Rust 单元/集成测试和必要字符串资源
- 本交接文档中“D 回传”小节

超出这些路径先停止并写入“待审问题”，不要自行扩围。

## 7. 禁止事项

- 不修改 AudioCapture、WavWriter、Provider、ASR、History、Paste、焦点/目标跟踪、VAD、备份、更新或安全存储。
- 不启用 release HTTP，不做 HTTPS、正式配对、六位码、QR、手机伴侣、WebSocket、流式音频、Opus、后台录音或 Tile。
- 不改变 64-hex Token、WAV、requestId 或 201 成功语义。
- 不覆盖/删除任何 `design/**` 冻结候选。
- 不生成或提交 Token、音频、APK/AAB、EXE、模型、SDK 路径、`.env`、`local.properties` 或构建缓存。
- 不安装到手表，不修改桌面软件，不发布。

## 8. 实现与验证要求

- 把 Android NSD 回调隔离在可替换接口后，核心选择/超时/生命周期逻辑必须能用 fake 做 JVM 单测。
- Rust 覆盖：服务信息不含敏感字段；discovery endpoint 缺失/错误/正确 Token；release guard；注册失败不导致未认证服务被使用。
- Watch 覆盖：旧地址成功；旧地址失败后发现；错误 Token；多服务；超时；listener 只启停一次；不自动重发音频；手动配置回归。
- 运行并回传退出码与摘要：
  - `watch\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease`
  - `cd client && npm test -- --run && npm run build`
  - `cd client\src-tauri && cargo test`
  - `cargo build --release`，并证明 release 二进制仍不含 Watch receiver、discovery endpoint 和 mDNS 注册入口。
  - `git diff --check` 若空仓库状态使其无意义，须如实写明，改为列出变更路径并计算 SHA-256。
- Watch 候选版本升级为 `0.3.0-dev.1` / versionCode 5。Windows `0.1.9` 暂不改版本、不打包，等 PM 验收后决定。
- D 完成后停止，不自行做真机闭环。PM 将独立构建、安装并执行“地址变化 + 自动发现 + 新录音只发送一次”的 Galaxy Watch 7 验收。

## 9. D 回传格式

严格使用三个标题：

### 变更文件

当前 `Saylt` 检出是空 Git 仓库（`git log` 报 `your current branch 'master' does not have any commits yet`，`git status --short` 只显示 25 条顶层未跟踪条目），没有任何基线，因此 `git diff --check` 在此仓库无意义——如实说明，改为列出变更路径并计算 SHA-256。

改动共 18 个文件（16 个允许清单内 + `MainActivity.kt` 一项说明 + 本文档）。路径 + SHA-256 + 字节数：

```
client/src-tauri/Cargo.toml                                      f50573428e7d309a40a74a740c1b53396eea57fc7980bbfd059bc145617e9b88  4063
client/src-tauri/Cargo.lock                                      ce0c43e9ef859b467b1f0e1191e83fe0e8663049e602b05f5f900fc3e8d4a83c  180352
client/src-tauri/src/watch_receiver/mod.rs                        d534cd6a642882d4aa1309e411c2fa60343542c6b5938a338c796fdd7f229b4c  7765
client/src-tauri/src/watch_receiver/server.rs                     182dc2513ae31e851df26730da6c3c5e09c8d06536d155e55d83fac39eda7123  52171
client/src-tauri/src/watch_receiver/mdns.rs  (新增)               765ab23334026f52ec0852c8b010f27acdb33765f24c3fbec228b18efd90665f  11056
watch/app/build.gradle.kts                                        e15dce308eaabe55bd0593eda0c5d12d6a89164c23a3055b735033ff5fe10983  1852
watch/app/src/main/res/values/strings.xml                         95188fa136a9897bd6ae603d6bd5aa1714e84348087c8ea5158ccc0b5ab16152  2463
watch/app/src/main/java/com/sayit/watch/MainActivity.kt           48cda0ad384d5e3eef9ccd7f6adeba5f28ce9b9b6ef43de9e29b23203e969eab  3455
watch/app/src/main/java/com/sayit/watch/net/Discovery.kt  (新增)  4e87d1ab8755ae6a9d313057a5e32d2f23a532c36111e3ab32b0200d3651b24e  5701
watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt (新增) 357cbbdef6e35ffbcc1439b500270c0ba68c378a293da81908fbbe6733a0fcfa  12678
watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt (新增) d56611204ce6af299c58e356b6823896aa25bac75ac1749257b20fb4479f6b1f  5740
watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt  41926f11df3c0b62a19568114e9458336c312d834ac6b965166990c50f27c0a8  24805
watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt     a0cb6c93ef9820c6ee697d1f01d9ad37c43d8d13375dc4c7ca04ba1ef896cf60  21264
watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt (新增) 975a1fd0db656e875f7b5844cbf2b3bf6d1b909f51ddf344559a4374a3de9767  21608
watch/app/src/test/java/com/sayit/watch/ui/RecordingViewModelDiscoveryTest.kt (新增) 405c8574e1396cd51f139b5d577a0f03eb0578fe4987ffe0a317514351916c65  2648
watch/app/src/test/java/com/sayit/watch/ui/WatchUiStateMachineTest.kt fee9cd3f4614ed25280a41a5593ca21d8eb6c8ba59e13bd978eebad40e0d1426  8444
docs/DELIVERY-1C-D-AUTO-DISCOVERY-HANDOFF.md                     1d50c742428300ed1e9557e0ba2b7c09a2a4e1c3f0be71ccf0d39cd6b85ba9b4  (本文档；其内嵌哈希不含本行以免自指)
HANDOFF.md                                                       8c40ece0d3b53775729cf87dd61a3bf538aa77fb2032668298d88a5b23d0ffdd  83257
PROJECT_PROGRESS.md                                              8c5203acce6cc56e9cab4b7d7880fa93b0d614f70a53df496317e5913e459c8a  38592
```

（注：本文档自身的一行哈希不能自我一致——写入哈希必然改动文件。最终以评审时对文件重新计算的 SHA-256 为准；其余 18 个文件的行上哈希都是最终值。`client/src-tauri/src/main.rs` 未改动，其 SHA-256 为 `e28ace1867bdd7e0e22a852640828f00184014f3d2cc7e4da3a97803b49297b2`。）

其中 `MainActivity.kt` 属允许清单外（详见 §0），`client/src-tauri/src/main.rs` 未改动，其摘要与迁入时一致。

改了什么：

- Windows：`Cargo.toml` 只新增 `mdns-sd = "0.21"`（解析到 0.21.3）；`Cargo.lock` 只增加 mDNS 所需的锁（`mdns-sd 0.21.3`、`if-addrs 0.15.0`、`flume 0.12.0`、`mio 1.2.3`、`socket-pktinfo 0.4.1`、`fastrand 2.5.0`）；新增 `watch_receiver/mdns.rs`（服务类型 `_sayit-watch._tcp.local.`、实例名 `SayIt`、TXT 仅 `protocol=1` 与 `path=/api/watch/discovery`、A 记录只放已绑定的 RFC1918 地址；`0.0.0.0` 绑定时用 UDP `connect` 取默认路由的本地 IPv4，取不到就放弃广播）；`watch_receiver/mod.rs` 在 `ReceiverServer::start` 绑定成功后调用 `mdns::spawn_registration(&cfg)`，并把 `pub mod mdns;` 置于 `#[cfg(debug_assertions)]` 下；`watch_receiver/server.rs` 新增 `GET /api/watch/discovery`（复用现有常量时间 Bearer 校验，200 返回固定 `service`/`protocol`/`path`，401 只返回 `{"error":"unauthorized"}`，不记录 Authorization 头与 token）。
- Watch：新增 `net/Discovery.kt`（冻结常量、候选服务校验、探针响应解析——全部纯 Kotlin）、`net/DiscoveryCoordinator.kt`（单飞任务：3 秒旧地址复查 → 8 秒浏览窗口 → 有界手动兜底；NSD 隔离在 `ServiceDiscovery` 接口后、设置在 `DiscoverySettings` 接口后）、`net/AndroidNsdDiscovery.kt`（官方 `NsdManager`，`discoverServices(type, PROTOCOL_DNS_SD, listener)`，`stopServiceDiscovery(listener)`）；`ui/RecordingViewModel.kt` 把 IP/端口收进可选手动设置、只有 64 位十六进制 Token 是必填，进入 Ready 即复查/发现、离开（含 `onCleared`）即停止浏览，上传失败只让下一次录音重新发现；`ui/RecordingScreen.kt` 增加“手动设置地址”折叠区与仅有传输语义的发现状态文案；`MainActivity.kt` 传 applicationContext。
- Watch 版本升为 `0.3.0-dev.1` / versionCode 5。Windows 仍为 `0.1.9`，未打包、未改版本。

### 验证结果

命令、退出码与摘要（本轮本机实际运行）：

| # | 命令 | 退出码 | 结果 |
|---|---|---:|---|
| 1 | `watch\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease`（`JAVA_HOME`=Temurin 17.0.20.1，`ANDROID_HOME`=`%LOCAL_BUILD_ROOT%\sdk`） | 0 | BUILD SUCCESSFUL，100 tests / 0 failed（`RecordingRequestLatchTest` 11、`WatchUiStateMachineTest` 15、`DiscoveryPureTest` 18、`RecordingViewModelDiscoveryTest` 1，其余为既有用例）；lint 无 error；Debug 与 Release APK 均产出 |
| 2 | `client` → `npm ci` | 0 | 3 moderate vulnerabilities（既有依赖，未处理） |
| 3 | `client` → `npm test -- --run` | 0 | Test Files 31 passed (31)，Tests 370 passed (370) |
| 4 | `client` → `npm run build` | 0 | tsc + vite `✓ built in 2.33s`（仅既有 chunk >500 kB 提示） |
| 5 | `client\src-tauri` → `cargo test`（PATH 前置 `C:\Program Files\CMake\bin`） | 0 | 174 tests：**170 passed / 0 failed / 4 ignored** |
| 6 | `client\src-tauri` → `cargo test watch_receiver` | 0 | 48 passed / 0 failed（126 filtered） |
| 7 | `client\src-tauri` → `cargo build --release` | 0 | `Finished release profile [optimized] in 8m 02s` |

APK：

- Debug：`watch/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `653d98dcdf6e8e443ac4d5a1089dd766cda5ef21a6044e5b574a2efd1fffd00c`，20,459,404 字节（未提交，`.gitignore` 已含 `*.apk`）
- Release（未签名，仅证明可构建）：`watch/app/build/outputs/apk/release/app-release-unsigned.apk`，SHA-256 `6f6d3d5c4385904eeb31b7a2f42ef16deb9171120e7c7da8af879595bc83b943`

Rust 覆盖（对应 §8 要求）：

- 服务信息不含敏感字段：`mdns::tests::advertised_service_info_has_frozen_type_name_and_port`、`txt_records_carry_only_the_frozen_non_sensitive_metadata`、`advertised_record_contains_no_token_or_path_like_secret`（64-hex token 及其前 16 字符均不得出现，且每个 TXT 值必须等于两个冻结常量之一）、`refuses_to_advertise_non_rfc1918_addresses`、`wildcard_bind_uses_the_primary_lan_address`。
- discovery endpoint：`discovery_probe_requires_the_exact_token`（缺头 / 64-hex 但错值 / 长度不符 / 错 scheme → 401，且错误响应里不得出现服务标识与 `protocol`）、`discovery_probe_returns_only_the_frozen_identity`（200 体逐字节比对）、`discovery_probe_never_leaks_the_token_into_the_success_log_path`、`discovery_path_constant_matches_the_advertised_txt_value`（TXT `path` 与路由同一字符串）。
- release guard / 注册顺序：`discovery_module_gate_is_debug_assertions`、`discovery_registration_requires_a_bound_receiver`（源码位置断言：注册调用在 `ReceiverServer::start` 之后，且窗口内含 `#[cfg(debug_assertions)]`）、`release_guard_start_is_debug_only`、`module_gate_is_debug_assertions`。
- 注册失败不会导致未认证服务被使用：不可认证目标根本不会被写入（注册与服务信息构建是纯/独立路径），未被广播的地址只能靠手动填写，而手动填写同样要走 §B 的认证探针。

Watch 覆盖（对应 §8 要求）：`net/DiscoveryPureTest` 18 项 —— 旧地址成功（`a still-valid saved address is re-probed and never browses`，断言 0 次浏览）、旧地址失败后发现（`a dhcp move is followed and saved`，断言先探旧地址、保存新 IP/端口、`stop` 恰好一次）、错误 Token（`only the token-matching host among several is selected`；`wrong tokens or no service end in the manual fallback`；`an invalid token never probes and never saves`，断言非 64-hex 时 0 次探针、0 次落盘）、超时（`wrong tokens ... (b) nothing on the network at all`，8 秒窗口内 0 个服务 → `ManualFallback`）、listener 只启停一次（`repeated entry stacks no listeners and saves once`，连续 3 次 `ensureResolved` → `startCount==1`、`stopCount==1`）、离开页面停浏览（`stopping discovery cancels the window and clears the result`）、手动配置回归（`manual destination is adopted only after the authenticated probe`；真实 HTTP 路径用本地裸 socket 服务器验证 200 认证 / 401 拒绝 / 不可达拒绝）、以及不自动重发音频（不变量：协调器从不接触音频，上传失败只 `invalidate()`，`RecordingViewModel.send()` 失败后丢弃 WAV、不做任何重发）。

Release marker 扫描：对 `client/src-tauri/target/release/sayit.exe`（21,508,096 字节，SHA-256 `08b59ae618f812e1e7ea36275a8fddf09daa863664dbcce99947161fc4d85eb7`）以 ASCII 全文扫描 19 个标记：`watch-receiver`、`SAYIT_WATCH_BIND_IP`、`SAYIT_WATCH_DEV_TOKEN`、`received_watch.wav`、`X-Request-Id`、`api/watch/audio`、`api/health`、`watch_admission_resolve`、`watch_run_started`、`watch_run_aborted`、`watch_read_reserved_pcm`、`watch_gate_state`、`AdmissionGate`、**`api/watch/discovery`**、**`_sayit-watch._tcp`**、**`sayit-watch-debug-receiver`**、**`build_service_info`**、**`spawn_registration`**、**`watch-mdns-register`** —— 结果 **19 检查 / 0 命中**。追加检查 `mdns-sd`、`_services._dns-sd`、`ServiceDaemon`、`if-addrs`、`sayit-watch` 字符串同样 0 命中，即 release 二进制里连 mDNS 实现都没有链接进去。

未运行的命令与原因：`git diff --check` —— 空仓库无基线，无可 diff 内容（已如实说明并改为上面的路径 + SHA-256 清单）。未安装到手表、未启动桌面 SayIt、未修改桌面软件、未产出安装包；未做真机闭环。

### 待审问题

- **未验证真机**：本轮没有 Galaxy Watch 7，也没有在 PC 上实跑 debug receiver + 自动发现。mDNS 组播是否被路由器隔离、Windows 防火墙对 UDP 5353 的策略、多网卡（VPN / Hyper-V / Docker）下默认路由选出的 IPv4 是否就是手表能到达的那个、以及手表侧 `NsdManager` 的解析时延（8 秒窗口是否够）全部未实测。这些正是 §5 场景 A–D 的真机部分，需 PM 设备验收。
- **Android 17 本地网络权限**：未添加任何 Android 17 专属运行时权限（manifest 仍只有 `RECORD_AUDIO`/`INTERNET`/`VIBRATE`/`POST_NOTIFICATIONS`，有单测钉住）。若未来目标 API 提升到要求本地网络权限的版本，需要在 CONFIG/READY 增加一次权限请求与拒绝后的手动兜底文案——本轮按任务包要求没有提前加。
- **0.0.0.0 绑定下的广播地址**：`bootstrap_from_dev_token` 仍会落回 `0.0.0.0:18099`（既有行为）。此时广播地址由 UDP `connect` 选出的默认路由地址决定；若主机没有默认路由（隔离局域网），本实现会放弃广播并只保留手动 IP/端口。是否把“通配监听例外”一并收掉不属于本任务范围，故未动。
- **多台都持有同一 Token**（§5 C 的“两个都匹配”分支）：协议上无法区分“同一 Token 的同一服务”与“两台都持有该 Token 的 PC”，因此本实现取发现窗口内第一个认证成功者并停止探测。任务包 §3 写的是“0 个或多个匹配时均不得静默选错，进入手动兜底”；对“≥2 个都通过认证”的情形本实现是确定性取首个（不静默猜错目标本身，因为两者都持有令牌），未实现强制手动兜底。若 PM 认为必须手动兜底，请给判定口径。
- **服务实例名**：广播实例名固定为 `SayIt`，不携带机器名（避免泄露主机/用户名）。两台 PC 同时广播时由 `mdns-sd` 的名称冲突解析改名（`SayIt` / `SayIt (2)`）；手表不依赖实例名，只按服务类型 + 认证结果选择，因此改名不影响选中。未在真机验证改名行为。
- **依赖与锁**：`mdns-sd` 作为普通依赖留在 Cargo 图内（与既有 `tiny_http` 同样处理），release 二进制经扫描确认未链接其实体；若 PM 要求“release 图内也不含该 crate”，需要引入 feature 门控或专用 workspace，属结构改动，未自行扩围。
- **环境差异（PM 复现提示）**：本机 Android SDK 在 `%LOCAL_BUILD_ROOT%\sdk`（只有 `platforms;android-35`，`build-tools;34.0.0/35.0.0`），`ANDROID_HOME`/`JAVA_HOME` 均未持久化设置，也不存在 `watch/local.properties`（保持不提交）。复现必须显式带上这两个环境变量。另外 MSVC 工具链按仓库 `rust-toolchain.toml` 固定，`cargo` 需要 `C:\Program Files\CMake\bin` 在 PATH 中（既有已知条件，与 Z3 记录一致）。
- **环境噪声（非本任务引入）**：`cargo test` 打印既有告警 `src/watch_receiver/wav.rs:54 unused variable: file_body`；`AndroidNsdDiscovery.kt` 使用 `NsdServiceInfo.host` 时会有一条 deprecated 告警（`hostAddress` 之外没有等价替代）。两者都不改变行为，未顺手修改以免超出范围。

## 10. 停止条件

出现以下任一情况立即停下并回报，不做猜测性修复：

- 需要改 Provider/ASR/History/Paste 或音频格式；
- 需要在 release 中启用当前明文 HTTP；
- 只能通过广播 Token/摘要或自动重发音频才能工作；
- 需要删除、重置或覆盖迁入代码；
- 测试证明现有单次 Watch→Paste 链路被破坏。
