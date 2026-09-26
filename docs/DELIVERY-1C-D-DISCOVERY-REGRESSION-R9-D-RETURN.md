# 1C-D-04@R9 — D 回传（变更文件 / 验证结果 / 待审问题）

- 任务包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R9.md`
- 唯一产品工作树：`C:/Users/suzix/Documents/ChatGPT/SayIt-Watch-sync`
- 分支：`codex/watch-connection-r7`（保留 R7/R8 历史，未 amend、未 force）
- R9 基线（PM 指定的 R8 交付头）：`8149d34c9aeb69b63806ab183b0a2615f9f274b8`
- **最终产品提交（全量命令、APK 与便携 ZIP 全部来自此 SHA）**：`e33b5361c3517f35d75bf210bfc1077d4f730658`
- 交付状态：**工作树干净**；未 push、未 merge、未 tag、未 release；未安装 APK、未替换任何电脑上的软件、未改真实 Token/配置/防火墙/进程。
- 未测场景与设备项全部在第 3 栏声明，本文件不把未测项写成通过。

### 提交顺序（`8149d34` 之后）

| # | 提交 | 内容 |
|---|---|---|
| 1 | `1ee8c517cf823ca8822b94447b2b9c6048d64ce6` | PM 的 R9 任务包与交接文档（**不是 D 的改动**） |
| 2 | `299720c59548b437a825eebab229b5187fb79763` | `fix(watch): R9 cancellable probe, non-blocking serial scheduler and artefact notes gate`（产品 2 + 测试 3 + 脚本 1） |
| 3 | `e33b5361c3517f35d75bf210bfc1077d4f730658` | `fix(watch): R9 retire queued cadence ticks on stop and observe the real round settle`（**最终产品头**） |
| 4 | 本回传文档 | `docs: R9 D return` |

**为什么有两个产品提交（必须记录）。** 我在 `299720c` 上跑全量，`ConnectionRecoveryR7Test > a recording pauses the connection task so no health check preempts the transfer` 失败：暂停后探针从 1 增到 6。那不是测试时序，是我实现的真实缺陷（见 §1.1 的"自我纠错"）。按任务包 §4，第一次全量证据因此作废；修好产品后建立新的最终产品提交 `e33b536`，**全部证据在那个 SHA 上重跑**。`299720c` 的证据不对外主张有效。

---

## 1. 变更文件

`8149d34` 之后 D 共改动 **6 个产品/测试/脚本路径**（外加本回传文档）。哈希为最终提交 `e33b536` 的工作树内容。

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/net/ConnectionTaskOwner.kt` | **重写**：单一 command loop（Channel + 一个调度协程）取代"调用方栈上调度"；UI 入口只 `trySend` 立即返回；`cancelAndJoin`（**无超时、无 `runBlocking`**）确认旧回合结束后才启动下一个；探针去掉 `NonCancellable`；新增 `isCurrent(round)` 代际/生命周期门与 `onProbeFailed` 后果回调；`stop()` 递增代际使在途 cadence tick 失效；`awaitRoundSettledForTest()` 测试 seam | `12ede24bba239cb76fbbefff1a1f43c6db4fcf72775f26210eb040708df3b12c` |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` | 探针拆分：`probeVerifiedTarget()`（纯探针）+ `onHealthProbeFailed()`（被 owner 代际门保护后才应用的后果）；`cancelForRefresh`/`dismissSwitchPicker` 路径随 owner 异步化；`browseForSwitch()` 去掉包裹被取消回合的 `withContext(NonCancellable)` 并给候选刷新加代际判断；`pauseConnectionTaskForTest()` 等回合真正结束 | `7c66ade301c75e5815478c818d02e79a0bc8cc44e48c1ee4f008a3dc94b70687` |
| `watch/app/src/test/java/com/sayit/watch/net/ConnectionLifecycleR9Test.kt` | **新增 7 条**（见 §2.1 的清单）：探针中退后台、探针中切换（晚成功/晚失败各一）、慢停止交接、取消后跨两个真实短健康周期、空闲周期只探针、脚本生成的真实说明产物 | `13fb42393896e925dca157b95a835f15a3f52ed6df49488d27ffa2d2c3ccf673` |
| `watch/app/src/test/java/com/sayit/watch/net/ConnectionLifecycleR8Test.kt` | **删除**用源码字符串代替产物的那条说明测试（它断言中文「手表访问令牌」而交付说明是英文，正是 PM 复现的那条失败）；文案断言移交 R9 的真实产物测试 | `a6778367cca01af7e4e9b524e67ac8df0668cc64092f5557313848da035a2791` |
| `watch/app/src/test/java/com/sayit/watch/net/ConnectionRecoveryR7Test.kt` | 仅收紧一处等待：「尚未开始搜索」不再被当成「已结束」，先等 browse 真正打开再等它收敛（R7 用例本身保留，未删除、未降断言） | `568503764bd1c16b5c41ab09cf7eeaaacabbcd1f4487eb0800c014c81aef8060` |
| `client/scripts/package-watch-portable.ps1` | 说明生成/校验收敛为 `New-PortableNotes` / `Test-PortableNotes` **单一来源**；新增 `-NotesOnly`（把真实说明写进指定目录、跑同一套静态校验、不构建不打包即退出）；脚本保持 **UTF-8 with BOM**（PS 5.1 解析中文必需） | `8abd9160f4e8d302eb267ca1475051ae50d53d7d34c343f4de6d03922ca71725` |

### 1.1 五条阻断的修法（可核查的因果）

**P0-A 不可取消的后台探针。** 根因是 `loop()` 里 `withContext(NonCancellable) { probeCurrentTarget() }`：真实探针最长 3 s 而取消只等 1.5 s，探针返回后 `revalidateCurrentTarget()` 会清目标、更新 UI/Discovery，之后 owner 才读 `foreground`。现在 ①探针在可取消上下文里运行；②**后果与探针分离**——`probeCurrentTarget` 只报告答案，"失败要清状态"变成新的 `onProbeFailed` 回调；③owner 只在 `isCurrent(round) = foreground && current === round` 成立时才调用它。探针协程被取消后，`withContext(Dispatchers.IO)` 的 block 返回时抛 `CancellationException`，连门都不会走到。R9 新增用例 1 就是"探针阻塞中退后台、随后放行、跨 5 个短周期，四层状态与设置计数全部不变，回前台才重新验证"。

**P0-B 假 cancel-and-join 与 UI 阻塞。** `restartNow` / `stop` / `dismissSwitchPicker` / 录音入口原先都进 `joinBounded()`，即 `runBlocking { withTimeoutOrNull(1_500) { job.join() } }`，在 **Main 线程**最多卡 1.5 s，超时后仍 `launch(intent)`，旧回合可能还活着。现在整个调度是一个 `Channel<Command>` + **唯一一个 command loop 协程**：UI 入口只 `commands.trySend(...)` 立即返回；loop 在做替换时 `job.cancel(); job.join()`——**不设超时**，因此"确认旧回合结束才启动下一个"是真的；旧回合的 `finally` 只能按身份清理自己的槽位。平台调用无法立刻中止时，新回合照实等待，UI 不阻塞。新增用例 3 用 `stop()` 延迟 2 s（**超过 R8 的整个 1.5 s 预算**）证明：入口 < 500 ms 返回、延迟期间 `active == 0`、释放后才出现新 listener、`peakActive == 1`。

**P0-C 最终头全量未通过。** PM 在 `8149d34` 复现的 `ConnectionLifecycleR8Test.kt:584` 失败，我在本机**独立复现且一致**：`221 tests completed, 1 failed`，同一个断言、同一行（日志 `I:\Deepseek\r9-baseline.log`）。原因是测试读脚本源码断言中文「手表访问令牌」，而最终脚本生成的说明是英文。现在该断言改为**运行脚本的 `-NotesOnly` 模式**，读它真正写出的 `README-PORTABLE.txt` / `BUILD-INFO.txt` 断言（配置路径、安全迁移提示、无控制字符、无不存在的设置入口、措辞与实际英文一致），源码字符串断言已删除。切换/取消用例改为真实跨过**两个**短健康周期（`healthMs = 120 ms`，等待 3 个周期长度）而非 60 s 周期只睡 700 ms。

**P1 打包脚本说明的来源唯一化。** 说明模板、替换、写入与静态校验现在只存在于两个函数里：正式打包与 `-NotesOnly` 走的是**同一段代码**，所以测试不可能对"永远不会被生成的措辞"通过，正式包也不可能绕过测试覆盖的措辞。

**自我纠错（本轮新发现的实现缺陷，已修并留证）。**

1. `299720c` 上全量失败：`a recording pauses the connection task so no health check preempts the transfer` 报告暂停后探针 `expected:<1> but was:<6>`。根因是我把 `stop()` 改成异步后，**一个已经进入 channel 的 cadence `Tick` 会在 `Stop` 之后被处理并重启循环**——"已暂停"的调度器仍按期探针，正是录音暂停要禁止的。修法：`Command.Stop` 递增回合代际，使任何已投递或正在投递的 tick 全部失效（`e33b536`）。
2. `cancelAndJoinCurrent()` 原先在 `cancel()` 之前就清空槽位，于是"取消了但还没 join 完"与"真的没有回合"对外不可区分，两个既有测试（`DiscoverySwitchFlowR3Test`、`ConnectionRecoveryR7Test`）会偶尔把仍在收尾的 browse 计入断言。修法：**join 之后才按身份清槽**，并加 `awaitRoundSettledForTest()` 测试 seam；生产暂停路径仍然立即返回，只有准备**测量**暂停状态的测试才等它。

两条都不是为了让测试变绿而放宽断言：第 1 条是产品行为缺陷，第 2 条是把"观测不确定"改成"观测确定"，断言强度未降低。

---

## 2. 验证结果

命令均在最终产品头 `e33b5361c3517f35d75bf210bfc1077d4f730658` 上、工作树干净时执行。

### 2.1 Watch 全量（退出码 0）

```
gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain
```

- 日志 `I:\Deepseek\r9-verify2.log`：**BUILD SUCCESSFUL in 2m 41s**，`53 actionable tasks: 53 executed`，**退出码 0**
- 单测**按 XML 独立重算**（`watch/app/build/test-results/testDebugUnitTest/TEST-*.xml`，24 个 suite）：
  **227 tests / 0 failures / 0 errors / 0 skipped**
- 计数变化：R8 的 221 → **227** = 删除 1 条源码字符串说明用例 + 新增 7 条 R9 用例
- lint：**0 error / 37 warning**（`lint-results-debug.xml` 统计；全部为既有类别，无新增代码类告警）

**R9 新增的 7 条（全部走 `RecordingViewModel → ConnectionTaskOwner → ResolverBridge → DiscoveryCoordinator` 真实链）：**

1. `a health probe in flight cannot outlive the background pause` —— 探针在 `probe()` 内阻塞时退后台，入口 < 500 ms 返回；放行旧探针后跨 5 个短周期，目标/Resolver/UI/设置/录音门全不变，无新 probe、无新 browse；回前台才重验证。
2. `a switch during a blocked probe waits for it and a late success changes nothing`
3. `a switch during a blocked probe survives a late FAILED probe without losing the target`
   —— 旧 probe 被取消后晚返回的成功/失败各一例；新 browse 只在旧回合**完成取消后**启动，`startCount` 恰好 +1，`peakActive == 1`，目标四层一致。
4. `a slow stop hands over only after the old listener is really gone` —— `stop()` 延迟 2 s（>R8 的 1.5 s 预算），UI 快速返回、延迟期无第二 listener、释放后才启动、`peakActive == 1`。
5. `a cancelled switch keeps the target across two real health periods without browsing` —— 短周期真实跨过两轮，probe 增加而 `ServiceDiscovery.startCount` 不增加。
6. `the idle health cadence probes without ever browsing and still reports idle` —— 空闲期只探针、`RoundKind.IDLE_CONNECTED`、不显示"正在连接"。
7. `the packaging script generates notes that name the real config path in their own language` —— 运行脚本 `-NotesOnly` 到隔离目录，断言真实产物。

**稳定性：** 修复后针对连接调度相关的 4 个 suite（R9 / R8 / R7 / R3）连续 **4 次** `--rerun-tasks` 全量重跑 **4/4 全绿、退出码 0**（日志 `r9-steady-b1..b4.log`）。此前 `r9-steady1..4` 与 `r9-stab1..3` 的失败也正是上面两条自我纠错定位到的原因，不是随机放行。

### 2.2 Windows / Rust

| 命令 | 退出码 | 结果 |
|---|---|---|
| `cargo test watch_receiver` | 0 | **62 passed / 0 failed / 0 ignored**（`I:\Deepseek\r9-rust.log`） |
| 其中 `watch_receiver::mdns::tests::live_daemon_announces_or_reports_a_daemon_error` | — | **ok** |

**与 PM 结论的差异（必须记录）：** PM 报告的 Rust 复验受既有 `transcribe-cpp-sys` CMake/MSBuild 缓存错误（FTK1011）阻断。本轮 D 在 `client/src-tauri` 下 `cargo test watch_receiver` **退出码 0、62/62 通过**，未出现 FTK1011/MSB3491。`client/src-tauri/src/watch_receiver/mod.rs` 本轮**零改动**（`git diff 8149d34..HEAD` 不含该文件），D 也没有为了让该门禁变绿而改它或清任何全局缓存/系统配置。两次结果不一致的环境原因 D 无法从本机复现，**请以 PM 主机复跑为准**；若在 PM 主机仍失败，按其原始日志单独处理。

### 2.3 统一便携包（从同一干净产品头构建）

- 命令：`powershell -ExecutionPolicy Bypass -File client/scripts/package-watch-portable.ps1`
- 日志 `I:\Deepseek\r9-package1.log`，**退出码 0**；构建时工作树 **clean**（脚本的脏树门禁通过）
- 产物：`dist-portable/SayIt-Watch-0.3.0-dev.2-windows-x64-portable.zip`
  - ZIP SHA-256 `f3487b7385c0fa8637332c5bccea2a1f5eb08318a80b8b0905736022093749ee`，`18,085,445` 字节
  - 包内 `SayIt.exe` SHA-256 `a56442cf489b765355933fa7930448997f0b661c1d2ed071570496c240cb6b0b`（`38,775,296` 字节）
  - 包内 `BUILD-INFO.txt` 记录 **`git_head=e33b5361c3517f35d75bf210bfc1077d4f730658`**，即最终产品提交；并记录 `receiver_config=%LOCALAPPDATA%\com.sayit.app\watch-receiver.config.json`
- 另解压到空目录 `I:\Deepseek\r9-smoke` 后**独立核对**（与打包进程无共享状态）：
  - 包内 `SHA256SUMS` **28 条全部重算一致，0 mismatch**；包内共 29 个文件
  - EXE 内嵌前端资源命中 **10** 个（`embedded frontend assets found in the EXE: 10`）
  - `README-PORTABLE.txt` 控制字符 **0**（2569 字节）、`BUILD-INFO.txt` 控制字符 **0**（345 字节）；无 `U+FFFD`
  - 说明提及真实配置路径 = True，提及 `Bring the token over yourself` = True，提及 `frontendDist` = True，提及不存在的 `SayIt 设置` = **False**，提及 `服务器访问令牌` = **False**
  - 包内**无**任何配置、Token、凭据、模型、用户记录、`node_modules`（按文件名扫描 config/token/credential/history/.env/node_modules 全部为 0 命中）；仅既有测试夹具 `resources/test_en.wav`、`resources/test_zh.wav`
- **未启动**包内 EXE，**未**替换或停止用户当前软件。

### 2.4 哈希汇总

| 产物 | SHA-256 | 大小 |
|---|---|---|
| Debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` | `daf765735c9a6eff6d699608523fa8f2f902ccccd1429c7e81c826a33045e318` | 20,679,396 |
| 便携 ZIP | `f3487b7385c0fa8637332c5bccea2a1f5eb08318a80b8b0905736022093749ee` | 18,085,445 |
| 包内 `SayIt.exe` | `a56442cf489b765355933fa7930448997f0b661c1d2ed071570496c240cb6b0b` | 38,775,296 |
| 最终产品头（全量 + 打包来源） | `e33b5361c3517f35d75bf210bfc1077d4f730658` | — |

三个二进制产物均**未提交**（在 `.gitignore` 范围内）。日志：`r9-baseline.log`、`r9-focused1..3.log`、`r9-stab1..3.log`、`r9-steady1..4.log`、`r9-steady-b1..b4.log`、`r9-pausefix1..3.log`、`r9-verify1..2.log`、`r9-rust.log`、`r9-package1.log`。

---

## 3. 待审问题（未测场景、范围、风险）

### 3.1 未验证（不得当作通过）

1. **真机与双电脑全部未验。** 五条阻断都由真实链的 JVM 回归覆盖，但**没有任何一项在 Galaxy Watch 真机或两台电脑上验证过**。227 条绿灯只能证明状态机与调度契约，不能证明 mDNS 发现、显式换机、换机后录音→文本框。
2. **便携包的独立启动冒烟仍未做。** 本机 18099 端口仍被用户正在运行的
   `C:\Users\suzix\OneDrive\Desktop\SayIt-Watch-Debug-20260917\sayit.exe` 占用，任务包禁止停止用户现用程序，因此 D 未从解压目录启动新 EXE；替代证据是 §2.3 的静态零侵入核对。
3. **`Activity.onStart/onStop` 的真实接线未在设备上观察**，JVM 里由测试直接驱动 `onForeground()` / `onBackground()`。
4. **Compose 渲染未验**；`0.3.0-dev.2-candidate.1` 的预览仍是按 dp 值绘制的度量图，不是运行时截图。
5. **PM 主机的 Rust 复验状态未知**（本机 62/62 通过，与 PM 报告不一致，见 §2.2）。
6. **`-NotesOnly` 依赖 `powershell.exe`。** 说明产物测试通过 `ProcessBuilder("powershell.exe", ...)` 运行打包脚本；这是 Windows 上的开发验收环境，但若在无 PowerShell 的环境跑单测，这一条会失败（其余 226 条不受影响）。

### 3.2 范围与实现偏差（请 PM 裁定）

7. **`ConnectionTaskOwner` 新增/变更的公开面**：新增 `awaitRoundSettledForTest()`（测试 seam，生产路径不得调用）；`isTaskRunning` 语义改为"有回合在跑且尚未被要求停止"（`stop()` 后立即为 false，供录音入口读取）；`stop()` 改为投递命令并递增代际；移除构造参数 `cancelJoinBudgetMs` 与常量 `DefaultCancelJoinBudgetMs`（超时 join 已被无超时 join 取代，语义消失）。`Pending` / `RoundKind` 与其余入口名保持不变。
8. **`RecordingViewModel` 新增 `onHealthProbeFailed()`**，并把原 `revalidateCurrentTarget()` 拆成"纯探针 + 失败后果"。这是 P0-A 要求的生产结果代际门所必需的接线；`revalidateCurrentTarget()` 仍在（SEARCH_KEEP 回合复用同一组合）。
9. **`pauseConnectionTaskForTest()` 现在会等待回合真正结束**（有界 5 s）。它是 **test seam**；生产 `pauseSchedulingForTransfer()` 仍立即返回、不阻塞。
10. **打包脚本新增 `-NotesOnly` 开关并抽出两个函数**；正式打包的产物格式、字节与 R8 一致（说明仍是 UTF-8 no BOM、单引号 here-string、SHA256SUMS 仍以 LF 连接）。脚本仍为 UTF-8 with BOM，编辑它时必须保留 BOM，否则 Windows PowerShell 5.1 会把中文校验字面量按 ANSI 解码而语法失败（R8 已踩过，本轮我一度也丢过 BOM，已恢复并核对字节 `EF BB BF`）。
11. **UI 布局与文案无变化**，故保留 `0.3.0-dev.2-candidate.1`，未新建视觉候选。
12. **未提交任何** `local.properties` / Token / APK / EXE / ZIP / 缓存 / 用户音频；`dist-portable/` 在 `.gitignore` 内。`client/src-tauri/gen/schemas/*.json` 在每次 cargo 构建后出现纯 CRLF 差异，本轮每次都已 `git checkout` 还原，最终工作树干净。

### 3.3 风险

13. **无超时 join 的取舍。** 现在替换回合时会一直等到旧回合真正结束；若某个平台调用**永久**卡住，该 owner 的调度会停止推进（UI 仍不阻塞、命令仍入队，回前台/新意图也不会覆盖它）。这是任务包"不得以固定超时后继续启动新回合"的直接后果，D 按其原文实现；真实探针有 3 s 预算、浏览有 8 s 窗口，正常路径远小于此。
14. **`requestRefresh` 会忽略"同一意图正在进行"的重复请求**（例如 Ready 反复进入时的 `SEARCH_KEEP`）。不同意图仍按到达顺序处理，最新意图胜出。
15. **回滚**：恢复 `8149d34` 即完全回到 R8 交付头；R9 只改上述 6 个路径（无删除文件、无配置/格式变更），不需要删配置或重装。

---

## 4. 一句话范围

`8149d34` → `1ee8c51`（PM 的 R9 包）→ `299720c`（产品+测试+脚本）→ `e33b536`（暂停/收敛修正，**最终产品头**）→ 本回传文档；
共 6 个产品/测试/脚本路径（Watch 产品 2、Watch 测试 3、打包脚本 1），外加本回传文档，**无未提交修改**。

D 已停止源码写入，等待 PM 独立核对范围/测试，并进行真机与双电脑 A–H 验收。
