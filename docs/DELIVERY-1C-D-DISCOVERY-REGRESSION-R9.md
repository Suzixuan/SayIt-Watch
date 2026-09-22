# 1C-D-04@R9 — 可取消探针、非阻塞串行调度与最终头证据

## 0. 决策和基线

上一版本 `1C-D-04@R8`，交付头 `8149d34c9aeb69b63806ab183b0a2615f9f274b8`。PM 增量审核结论：**NO-GO**。R8 修正了目标保持、连接态只探针和便携说明，ZIP 静态完整性也通过；但最终交付头的全量测试实际失败，并且 R8 明确要求消除的后台探针/取消交接缺口仍在生产代码中。

- 唯一工作树/分支不变：`C:/Users/suzix/Documents/ChatGPT/SayIt-Watch-sync` / `codex/watch-connection-r7`。
- 以 `8149d34` 为 R9 基线；保留 R7/R8 历史，不 amend、不 force。
- 这是同一核心状态机需求的第二次返修。不得再用局部补丁叠加：先把调度权收敛为一个非阻塞串行所有者，再修测试与打包证据。
- D 仍是唯一产品写入者。不得安装 APK、替换电脑软件、操作真实配置/Token/进程/防火墙，不 push/merge/tag/release。
- 不改协议、mDNS 实现、ASR、History、Paste、录音、正式 Release 或视觉布局。

## 1. PM 阻断证据

### P0：后台健康探针仍是不可取消的，会在 `onBackground()` 返回后修改状态

`ConnectionTaskOwner.loop()` 仍以 `withContext(NonCancellable) { probeCurrentTarget() }` 执行健康探针。真实探针最长 3 秒，而 `joinBounded()` 只等 1.5 秒；因此退后台时旧 Job 可以继续。`revalidateCurrentTarget()` 在返回前会清目标、更新 UI/Discovery，之后才回到 owner 检查 `foreground`，所以“检查 false 后不继续”不能阻止后台写入。

R8 新增测试只覆盖“browse 中退后台”，探针 fake 又同步立即返回，没有覆盖任务包要求的“健康探针进行中退后台”。

**必须修复：**去掉探针周围的 `NonCancellable`。取消/代际失效后，阻塞网络调用即使在线程中晚返回，也不得再触碰 Resolver、ViewModel、UI、设置或调度状态。给生产结果应用加当前回合/生命周期代际门，而不是只在回调结束后读取一个布尔值。

### P0：`cancel-and-join` 仍是假保证，并会阻塞 UI 线程

`restartNow()`、`stop()`、`dismissSwitchPicker()` 等 UI 路径进入 `joinBounded()`，内部使用 `runBlocking`；最多卡住调用线程 1.5 秒。超时后 `supersede()` 仍直接 `launch(intent)`，旧 Job 可能未结束，和新 Job 同时存在。旧 Job 若正处于不可取消探针，在返回后还能执行旧目标失效/清理。R8 要求的是完成取消交接后才启动替代任务，不是“等一会儿然后并发”。

**必须修复：**以单一 actor/Channel、单一 command coroutine 或同等可证明结构串行处理 `foreground / background / search / browse / dismiss / upload-finished` 意图。UI 入口只投递意图并立即返回；调度协程内部对旧回合执行 `cancelAndJoin`，确认结束后才启动下一个。不得在 Main/UI 路径 `runBlocking`，不得以固定超时后继续启动新回合。只有这一个所有者能写当前 Job/意图；旧回合的 finally/迟到结果不能清理或覆盖新回合。

平台阻塞调用无法立刻中止时，新回合可以等待，但 UI 不能阻塞；代际门必须让旧调用晚返回后无副作用。显式“重新搜索”不能丢失，连续意图可合并为最新意图，但顺序必须可证明。

### P0：最终交付头的全量测试并未通过

PM 在 `8149d34` 独立执行：

```text
gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain
```

最终树的 `ConnectionLifecycleR8Test` 在第 584 行失败：测试要求打包脚本含“手表访问令牌”，而 `50c1e52` 的最终脚本已把生成说明改成英文，仅含 `watch access token`。D 报告的 221/221 对应后续三个打包脚本修正之前的状态，不能代表交付头。

另外，“切换后跨多个健康周期”测试把 `healthMs` 设为 60 秒却只睡眠 700 ms，没有经过任何健康周期；这条用例名称和证据不一致。

**必须修复：**测试断言必须和实际生成说明一致，优先直接验证脚本生成后的 `README-PORTABLE.txt`，不以源码字符串代替产物。切换/取消用例用短健康周期真实跨过至少两个周期，并证明目标/录音门不变、browse 不增加。所有产品、测试和脚本修改先归入一个最终产品提交；在这个精确 SHA 上重新执行全量命令，之后从同一 SHA 构建 ZIP。若再修任何产品/测试/脚本，证据作废并全部重跑。

### 环境项：Rust 仍未能由 PM 复验

PM 再次执行 `cargo test watch_receiver`，仍在既有 `transcribe-cpp-sys` CMake/MSBuild 缓存处因 `FTK1011` 失败，未进入测试。R9 不修改 `mdns.rs` 或扩大范围掩盖环境问题；D 如能通过应保留日志，但不得写成 PM 已复验。

## 2. 必须新增或改正的回归

测试须走真实 `RecordingViewModel → ConnectionTaskOwner → ResolverBridge → DiscoveryCoordinator` 链：

1. **健康探针中退后台：**可控阻塞 probe 已开始后调用 `onBackground()`；入口应快速返回，不等待 1.5 秒。随后放行旧 probe，等待超过两个短周期，Resolver/ViewModel/UI/设置/录音门均无变化，且无新 probe/browse。回前台才重新验证。
2. **探针中显式切换/重新搜索：**旧 probe 被取消后晚返回（成功与失败各一例），不能清目标、覆盖候选或取消新回合；新 browse 只在旧回合完成取消后启动。
3. **慢停止交接：**fake 的 stop/旧回合完成可控延迟超过 1.5 秒。UI 调用快速返回；延迟期间第二个 listener 为 0，释放后才启动新 listener；`peakActive == 1`。不得通过缩短 fake 延迟绕过。
4. **切换取消后两个健康周期：**短周期真实跨过至少两轮，当前目标在 Resolver/ViewModel/UI/录音门一致，健康 probe 增加而 `ServiceDiscovery.startCount` 不增加。
5. **最终产物说明：**运行脚本生成真实说明到隔离目录，断言配置路径、安全迁移提示、无控制字符、无不存在的设置入口；测试措辞与实际语言一致。
6. 保留 R7 晚启动、显式点选、多个候选、取消保持当前目标和上传不重发等既有用例。不得用 `Thread.sleep` + 没跨周期的参数制造假覆盖。

## 3. 允许范围与验证

- 可修改 R8 已涉及的 `ConnectionTaskOwner.kt`、必要的 Resolver/ViewModel/Activity 接线及其测试。若 actor 需要一个小型同包文件可新增；不得维护第二套连接状态。
- Windows 仅允许修打包脚本/说明测试；`watch_receiver/mod.rs` 文案已静态通过，除非最终测试证明有必要，不再改产品逻辑。
- UI 布局和文案无变化时继续保留 `0.3.0-dev.2-candidate.1`，不创建视觉候选。
- Watch 定向回归通过后，在**最终产品提交 SHA**上执行一次 `testDebugUnitTest --rerun-tasks lintDebug assembleDebug`，从 XML 重算计数并记录退出码。
- Windows：执行受影响 receiver 测试；若仍被 FTK1011 阻断，如实记录。不得清用户全局缓存或修改系统配置来追绿。
- 便携 ZIP 必须由上述同一个干净产品 SHA 构建；独立解压，重算全部 SHA256SUMS，核对 `BUILD-INFO.git_head`、EXE、内嵌前端、配置/Token 缺失和说明字节。不得启动或覆盖用户当前软件。

## 4. 提交与回传

- 提交顺序：**一个最终产品+测试+脚本提交** → 从该干净 SHA 跑全量并打包 → R9-D-RETURN 文档提交。若产品提交之后又需修代码/测试/脚本，建立新的最终产品提交并重新执行全部证据。
- 回传固定三栏：**变更文件、验证结果、待审问题**。列基线 `8149d34` 后全部提交、最终产品 SHA、命令/退出码/XML 计数、APK/ZIP/EXE 哈希、包内 `git_head`，以及未测真机场景。
- 工作树干净后停止。PM 源码与最终头证据通过后，才安装 APK、替换统一包并进入两台电脑真机 A–H 验收。
