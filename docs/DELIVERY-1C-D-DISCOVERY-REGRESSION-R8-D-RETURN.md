# 1C-D-04@R8 — D 回传（变更文件 / 验证结果 / 待审问题）

- 任务包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R8.md`
- 唯一产品工作树：`C:/Users/suzix/Documents/ChatGPT/SayIt-Watch-sync`
- 分支：`codex/watch-connection-r7`（R8 就在同一分支上，保留 R7 提交，未改写历史、未 amend 他人提交）
- R8 基线（PM 指定的 R7 交付头）：`89cedd04bda3609e2286c92172fafbc896d640b8`
- 本回传时的分支头（本文件所在的文档提交，故不自我引用哈希）：`codex/watch-connection-r7` 的当前 tip
- 交付状态：**工作树干净**；未 push、未 merge、未 tag、未 release；未安装 APK、未替换任何电脑上的软件、未改真实 Token/配置/防火墙/进程。
- 未测场景与设备项全部在第 3 栏声明，本文件不把未测项写成通过。

### 提交顺序（`89cedd0` 之后，均为 D 的提交）

| # | 提交 | 内容 |
|---|---|---|
| 1 | `9aecaa9032496b0b6e8686c708300c566dbbc13c` | `fix(watch): R8 single target, single scheduler and visible portable notes`（产品修复 + 全部测试） |
| 2 | `ac1d0e2b6d862bab5564a582bbbfc55d00a5b2a5` | `fix(packaging): parse the notes checks under Windows PowerShell 5.1` |
| 3 | `6cad109bee49cde8df42034d04e0e3f1b06a42c6` | `fix(packaging): write and read the generated notes as explicit UTF-8` |
| 4 | `50c1e520dec932d174c8c595307c9d65e09ff23f` | `fix(packaging): keep the generated-notes checks ASCII-only`（**干净产品头，便携包即由此构建**） |
| 5 | 本回传文档 | `docs: R8 D return` |

（`ee7f5fa` / `2b954fe` 是 PM 的 R8 派发文档提交，不是 D 的改动。）

---

## 1. 变更文件

`89cedd0` 之后 D 共改动 **8 个产品/测试/脚本路径**，外加本回传文档。

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/net/ConnectionTaskOwner.kt` | **重写**：两态循环（连接态只做探针 / 断连态搜索）；`SEARCH_KEEP` 与 `SEARCH` 分离；cancel-and-join 串行交接 + 回合身份隔离；`pauseForeground`/`resumeForeground`/`revalidateNow` | `2663e17d9f94886f9dbd9c835cae33db4311aa239a7b9888e670bd77d893acaa` |
| `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt` | 新增 `ResolverEngine.cancelAutomaticRunKeepTarget()`（递增代际但不丢目标）与 `ResolverBridge.releaseAutomaticRunKeepTarget()`；新增 `DiscoveryCoordinator.stopBrowseOnly()`（只停浏览、不清 `resolved`） | `8d153980d7489720e235f5ab5554fb6d61fa2d3d2febfc995041d6a72160f1c7` |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` | `cancelForRefresh` 改走保目标路径；`onForeground/onBackground` 改为 pause/resume 且只在真实前台继续；`dismissSwitchPicker()` 先停并 join 浏览再交还稳定循环；`resumeConnectionTask()` **不再写** `appInForeground`；`resolveAutomatically(force)`；`setVerifiedDestination` 先写字段再发布 UI | `d5ddd43d45fd8112e989da0a21c420dd978c89a88640e6712c297d9f7868419e` |
| `watch/app/src/test/java/com/sayit/watch/net/ConnectionLifecycleR8Test.kt` | **新增 11 条**：五条阻断的真实链回归（目标四层一致、空闲只探针、探针失败才恢复、连点不重叠监听、返回取消浏览、后台作废 + 后台上传不复活、便携说明可执行） | `467df8a221040ef6a42d624570afb61206df8c08051047bbf14e588f355f8cce` |
| `watch/app/src/test/java/com/sayit/watch/net/ConnectionRecoveryR7Test.kt` | 清掉 3 处 R7 假阳性/时序依赖（异步期间数 browse、后台循环干扰测量），保留晚启动、显式点选、设置未改不丢目标 | `fb3c5351e2e09185fb5e2aabd8653a59685d9efbcd6d83ae26cd3626e3828457` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoverySwitchFlowR3Test.kt` | `switchAndWait` 改为等 owner 与 transport **都**稳定；无目标用例先冻结循环再测量 | `47e41ee79900c64ccb1023c6d6f9bb14dafd6f4823936a68b1dadca2e6a05f0f` |
| `client/src-tauri/src/watch_receiver/mod.rs` | `MissingConfig` 文案改为指向 `%LOCALAPPDATA%\com.sayit.app\watch-receiver.config.json`，不再提不存在的桌面设置入口；新增/加强 2 条文案测试 | `8e02634fccbd51e411a968fda425578c2111de4e56c329c576f52bce676633b8` |
| `client/scripts/package-watch-portable.ps1` | 说明改由**单引号 here-string**生成（修掉被解释成控制字符的 `frontendDist`）；加入真实配置路径与安全迁移步骤；构建后做**控制字符 + ASCII 内容**硬校验；脚本拒绝在脏工作树构建；脚本改为 **UTF-8 with BOM**（Windows PowerShell 5.1 解析中文必需） | `7e2d307932102f2a7040f94dc17ed50e5b39aa861605a1441190a46490215318` |

### 1.1 五条阻断的修法（可核查的因果）

**P0-A 目标分裂。** 根因确认为 `cancelForRefresh = resolver.onTargetInvalidated()` → `ResolverEngine.cancelLocked()` → `clearTargetLocked()`：切换一开始，Resolver 的 `verifiedTarget/hasVerifiedTarget` 就空了，而 ViewModel 的 `verifiedDestination` 还是旧电脑。现在交接走 `releaseAutomaticRunKeepTarget()`（递增 generation 丢弃迟到结果）+ `stopBrowseOnly()`（真正停掉在跑的浏览），**从不触碰 `verifiedTarget`**；`setVerifiedDestination` 也改成先写字段、后发布 UI，`WatchUiState.currentTarget` 不再可能由旧值发布。

**P0-B 已连接仍整轮搜索。** 根因是 `waitForNextRound()` 在探针后无条件返回 `Pending.SEARCH`，而 SEARCH 轮先 `cancelTransport()` 清目标。现在 `Pending` 分为 `SEARCH_KEEP`（有目标就只做一次有界认证探针，**不丢目标、不开浏览**）与 `SEARCH`（从零开始）。连接态循环只做探针；**只有探针失败**才进入一次恢复 SEARCH。"正在连接"只在实际会改变连接结果的轮次展示，空闲连接不再闪烁。

**P0-C 生命周期未封闭。** 三处一起改：① `supersede()` 先 `cancel()` 再**有界 join**，且回合清理改为**按身份**判定，迟到的清理不会清掉新回合的槽位（此前会静默丢掉用户最新意图）；transport 的停止是同步调用、位于回合内第一个挂起点之前，所以被取消也一定送达。② `dismissSwitchPicker()` 现在会取消并等待它启动的浏览，然后把 owner 交还给**当前目标**的稳定循环（有目标走健康循环，无目标走恢复搜索）。③ `pauseForeground()` 同时停止探针与搜索；`resumeConnectionTask()` 只**读**真实前台状态，后台上传结束不会再把它写回 true。

**P1 便携说明。** 见 §2.4 的逐项字节级核对。

---

## 2. 验证结果

### 2.1 Watch 全量（退出码 0）

```
gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain
```
日志 `I:\Deepseek\r8-verify1.log`：**BUILD SUCCESSFUL in 6m 17s**，退出码 0
- 单测 **221 tests / 0 failures / 0 errors / 0 skipped**（按 XML 独立重算）
- lint **0 error / 37 warning**（全部为既有类别：依赖版本、未使用资源、SDK 目标；无新增代码类告警）
- 计数变化：R7 的 210 → **221**（新增 `ConnectionLifecycleR8Test` 11 条；R7/R3 既有用例被收紧而非删除）

**稳定性。** 针对"背景调度 + 时序断言"导致的偶发失败，D 连续 **4 次** `--rerun-tasks` 全量重跑全部 **221/221、退出码 0**（日志 `r8-c1..c4.log`）。期间定位并修掉的真实不稳定来源有三类，都是测试写法问题而非产品随机性：异步交接期间数 browse、后台循环在断言窗口内插队、以及 `ui.currentTarget` 在清空瞬间的发布顺序（后者是产品顺序问题，已修 `setVerifiedDestination`）。

### 2.2 Windows / Rust

| 命令 | 退出码 | 结果 |
|---|---|---|
| `cargo test watch_receiver` | 0 | **62 passed / 0 failed / 0 ignored**（`I:\Deepseek\r8-rust.log`） |
| 其中 `watch_receiver::mdns::tests::live_daemon_announces_or_reports_a_daemon_error` | — | **ok** |

**与 PM 结论的差异（必须记录）：** PM 报告"Rust 独立复验受既有构建缓存问题阻断、D 的实时 mDNS 用例也未通过"。本轮 D 在本机 `client/src-tauri` 下 `cargo test watch_receiver` **退出码 0、62/62 通过**，包括那个 live mDNS 用例；未出现 FTK1011/MSB3491。`client/src-tauri/src/watch_receiver/mdns.rs` 本轮**零改动**（`git diff 89cedd0..HEAD` 不含该文件），D 也没有为了让该门禁变绿而改它。PM 那次失败的环境原因 D 无法从本机复现，请以 PM 复跑为准；若在 PM 主机仍失败，请按其原始日志单独派发，D 不擅自改 `mdns.rs`。

### 2.3 统一便携包（从干净产品头构建）

- 命令：`powershell -ExecutionPolicy Bypass -File client/scripts/package-watch-portable.ps1`
- 日志 `I:\Deepseek\r8-package5.log`，退出码 0
- 构建前工作树 **clean**（脚本的脏树门禁就是这次拦下了我自己两次未提交的改动）
- 产物：`dist-portable/SayIt-Watch-0.3.0-dev.2-windows-x64-portable.zip`
  - ZIP SHA-256 `12ccd863d787a902a803c55a56117b3e49be5c65cd78a45987f5ea3945cdbcc2`
  - 字节数 `18,085,448`；包内 29 个文件
  - 包内 `SayIt.exe` SHA-256 `faf1f984e53910f43e613b9a28b2224e3f009882da8c000e387ffe0cf4b6542e`
  - 包内 `BUILD-INFO.txt` 记录 **`git_head=50c1e520dec932d174c8c595307c9d65e09ff23f`**，即本次产品提交；同时记录 `receiver_config=%LOCALAPPDATA%\com.sayit.app\watch-receiver.config.json`
- 另一空目录 `I:\Deepseek\r8-smoke` 解压后独立核对：
  - 包内 `SHA256SUMS` **28 条全部重算一致，0 mismatch**
  - EXE 内嵌前端资源名命中 **10** 个；不含 `node_modules`、`vite.config` 引用
  - `README-PORTABLE.txt` 控制字符 **0**（含 form feed 检测：无），提及真实配置路径 = True，提及 `frontendDist` = True，提及不存在的 `SayIt 设置` = **False**
  - 包内只有既有测试夹具 `resources/test_en.wav`、`resources/test_zh.wav`；**无**配置、Token、模型、用户记录
- **自我纠错（本轮三次失败的脚本）**：① 中文写在 here-string 之外 + BOM-less `.ps1` → Windows PowerShell 5.1 按 ANSI 解码，把多字节字符错解成引号直接语法失败；② `Set-Content -Encoding UTF8` 的 BOM 语义使校验读到错误文本；③ 用中文字面量做构建期校验本身不可靠。现已分别修为 **UTF-8 with BOM** + 显式 UTF-8 读写 + **校验只用 ASCII 属性**（内容措辞由 Watch 单测读模板覆盖）。这些都记在提交信息里，不掩盖。

### 2.4 哈希汇总

| 产物 | SHA-256 | 大小 |
|---|---|---|
| Debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` | `deb0e980590eacb2adfdb83e590733334af0a3658965d2027cffdd945bb8e10f` | 20,679,396 |
| 便携 ZIP | `12ccd863d787a902a803c55a56117b3e49be5c65cd78a45987f5ea3945cdbcc2` | 18,085,448 |
| 包内 `SayIt.exe` | `faf1f984e53910f43e613b9a28b2224e3f009882da8c000e387ffe0cf4b6542e` | — |
| 干净产品头（打包来源） | `50c1e520dec932d174c8c595307c9d65e09ff23f` | — |

三个二进制产物均**未提交**（在 `.gitignore` 范围内）。日志：`r8-verify1.log`、`r8-c1..c4.log`、`r8-rust.log`、`r8-package5.log`。

---

## 3. 待审问题（未测场景、范围、风险）

### 3.1 未验证（不得当作通过）

1. **真机与双电脑全部未验。** R8 的五条修复都由真实链的 JVM 回归覆盖，但**没有任何一项在 Galaxy Watch 真机或两台电脑上验证过**。D 未连接/安装手表、未替换任何电脑软件、未改真实 Token/配置/防火墙。220+ 条绿灯只能证明状态机与调度契约，不能证明 mDNS 发现、显式换机、换机后录音→文本框。
2. **便携包的独立启动冒烟仍未做。** 本机 18099 端口被用户正在运行的
   `C:\Users\suzix\OneDrive\Desktop\SayIt-Watch-Debug-20260917\sayit.exe` 占用，任务包禁止停止用户现用程序，因此 D 未从解压目录直接启动新 EXE。已替代为静态零侵入证据（§2.3）；"解压到任意空目录双击即可用"仍待 PM 在不占用该端口的条件下确认。
3. **Compose 渲染未验。** 无 JVM 渲染；`0.3.0-dev.2-candidate.1` 的预览仍是按 dp 值绘制的**度量图**，不是运行时截图。
4. **前台/后台的真机接线未验。** `MainActivity` 的 `onStart`/`onStop` 在 JVM 里由测试直接驱动，Activity 生命周期本身未在设备上观察。
5. **PM 主机的 Rust 复验状态未知。** D 本机 62/62 通过（含 live mDNS），与 PM 报告的失败不一致，见 §2.2。

### 3.2 范围与实现偏差（请 PM 裁定）

6. **`Pending` 枚举新增 `SEARCH_KEEP`**（原为 `SEARCH`/`BROWSE`）。这是把"核查已有连接"与"重新找连接"分开的最小手段；否则 P0-B 无法在不丢目标的前提下满足"连接态只探针"。属 D 主动设计选择。
7. **`ConnectionTaskOwner` 新增 4 个公开入口**：`pauseForeground()`、`resumeForeground()`、`revalidateNow()`、只读 `roundKind`。前三个是 P0-C 的必需（原 `onForegroundChanged(false)` 无法在暂停后安全恢复），第四个是 UI 投影与测试 seam。
8. **`DiscoveryCoordinator.stopBrowseOnly()` 与 `ResolverEngine.cancelAutomaticRunKeepTarget()`** 是 P0-A 要求的"不清目标但取消当前自动 run"的专用操作；按任务包 §1 P0-A 原文实现，未再复用 `onTargetInvalidated()`。
9. **测试构造器参数未变**（仍是 R7 的 `retryDelayMs` / `healthCheckIntervalMs`），但 R8 的测试大量使用一个长间隔来把后台循环与测量隔离；这是测试写法，不改生产默认值（生产仍是 5 s / 5 s）。
10. **视觉候选未新增。** 按任务包 §3，只有显示变化才需要 `candidate.2`；R8 未改切换页布局与文案（只改了连接状态投影的内部一致性），故保留 `0.3.0-dev.2-candidate.1` 不覆盖。若 PM 认为状态文案在探针期间的表现也算显示变化，请指示再冻结候选。
11. **`client/src-tauri/gen/schemas/*.json` 每次 cargo build 后都出现纯 CRLF 差异**，D 每次都已 `git checkout` 还原，交付工作树干净。这不是 R8 引入的，但会让后续构建者看到两个"已修改"文件。
12. **未提交任何** `local.properties` / Token / APK / EXE / ZIP / 缓存 / 用户音频；`dist-portable/` 已在 `.gitignore`。

### 3.3 风险

13. **`joinBounded` 的上限是 1.5 s**（`DefaultCancelJoinBudgetMs`）。正常取消在微秒级返回；上限只在平台调用真的卡住时触及。`Activity.onStop` 与录音入口都会走这条路径，D 选择不长时间阻塞 UI 线程。若 PM 要求"必须等到旧 browse 确认结束、永不超时"，需要改成异步交接并单独设计，因为那会让 `onStop` 阻塞到 8 s 级。
14. **`resumeConnectionTask()` 现在在后台什么都不做**：后台上传结束后不会恢复探针，直到 `onForeground()`。这是 P0-C 的直接要求，但意味着"应用切到后台再切回来"是恢复连接的唯一时机。
15. **回滚**：恢复 `89cedd0` 即完全回到 R8 之前；R8 只改上述 8 个文件（无删除文件、无配置/格式变更），不需要删配置或重装。

---

## 4. 一句话范围

`89cedd0` → `9aecaa9`（产品+测试）→ `ac1d0e2` / `6cad109` / `50c1e52`（打包脚本三次修正，最后一次即干净产品头并据此打包）→ 本回传文档；
共 9 个路径（Watch 产品 3、Watch 测试 3、Windows 源码 1、打包脚本 1、本回传文档 1），**无未提交修改**。

D 已停止源码写入，等待 PM 独立核对范围/测试，并进行真机与双电脑 A–H 验收。
