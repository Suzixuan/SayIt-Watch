# 1C-D-04@R3 — D 定向返修回传

返修包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R3.md`
上一有效版本：`1C-D-04@R2`（回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R2-D-RETURN.md`）
状态：三项必修已修，源码与构建验证完成；**真机验收仍属 PM**

---

## 一、R2 → R3 变化（对应三项必修）

### 必修 1：第一次发现另一台电脑却无法采用

**R2 的实现错在哪**：`requestSwitch()` 拿到 `found` 后调用 `onTargetPicked(found)`，而后者只检查 `_targets.value`；`publishTargets()` 只在 `found == null` 分支执行，所以第一次切换必然被成员校验拒绝并关闭弹窗。R2 的测试只调 `bridge.onTargetChosen()`，正好绕过这个检查。

**R3 的修法**：把「已认证电脑列表 + 成员的接受/拒绝判定」整体移入 `ResolverBridge`，让**合并与判定不可能再分开**：

- `openPicker()` / `cancelPicker()` / `isPickerOpen` 拥有弹窗状态；
- `handleSwitchBrowse(runId)` 先 `mergeKnown(collected)` **再**返回唯一的「新」目标；
- `pick(candidate)` 只接受**本会话已认证且仍在列表内**的端点，并落盘；
- `requestSwitch()` 拿到 `found` 后走 `onTargetPicked(found)` → `resolver.pick()`，与手动点选**同一条路径**、同一份校验。

安全性质没放宽：`pick()` 仍然拒绝任何不在列表里的地址（有测试专门钉这条）。

### 必修 2：浏览结束后解析线程仍永久轮询

**R2 的实现错在哪**：`AndroidNsdDiscovery` 构造对象即 `Executors.newSingleThreadExecutor{...}.also{ execute(resolveLoop) }`，`resolveLoop()` 每 25 ms 轮询且**永不退出**；`stop()` 只清队列。离开 Ready / `onCleared()` 后线程仍在空转，且每 new 一个浏览器就多一条线程。

**R3 的修法**：新增 `BrowseWorker<T>` 作为**浏览会话作用域**的 worker（`watch/app/src/main/java/com/sayit/watch/net/BrowseWorker.kt`）：

- `start()` 开一次会话、**恰好一条**线程；`stop()` 关会话、`notifyAll`、`interrupt`、并 **join** —— `stop()` 返回时线程**可证明已退出**（即使它正卡在等一个永不到来的平台回调）；
- 队列为空时线程 `wait()` **阻塞**，空闲会话**零轮询**（不再是每秒 ~40 次唤醒）；
- 同一实例不重复排队；容量上限 8、溢出丢最旧；重复/超发都计入 `discardedCount`；
- 会话关闭后到达的平台回调被丢弃，不再写入已死的浏览；
- `AndroidNsdDiscovery` 改为在 `start()` 创建 worker、`stop()` 关闭并等待它，一次重试一条（单飞）保持。

修这条时**自己找出并修掉两个新缺陷**（都是 R3 测试逼出来的）：
1. `pollOrPark()` 非局部 `return entry` 跳过了 `queuedKeys.remove(...)`，导致同一实例**永久**被判为重复——连瞬态重试都发不出去。现在在交接时就释放 key。
2. `process()` 抛异常会静默杀死整条 worker 线程。现在单条失败被吞入 `failedCount`，worker 继续服务本窗口剩余实例。

`SerialResolveQueue` 保留（R2 交付、有 4 条语义测试、仍被 R3 测试引用为常量来源），但**已不在生产热路径上**；生产路径由 `BrowseWorker` 承担，其规则与之等价并在 R3 测试中被重新钉住。

### 必修 3：无当前目标时的切换状态与重复等待

**R2 的实现错在哪**：`onSwitchBrowse()` 在 `engine.verifiedTarget == null` 时直接 `return only`，跳过「回到 Idle」；浏览返回 0/多个目标时 engine 可能停在 Automatic。`requestSwitch()` 还会先跑一次完整自动解析（3 s 旧址 + 8 s 浏览），再无条件浏览 8 s。

**R3 的修法**：

- `handleSwitchBrowse()` **总是**结束搜索：有当前目标就 `stateAdoptedByUser(keep)`，没有任何目标就 `engine.onConfigEntered()`（回到 Idle）；另有 `endPickerSearch()` 兜底，任何提前返回路径都会清掉 Automatic；
- `requestSwitch()` **删除 stage A**，只做**一次**有界浏览（阶段 A 的 `onSwitchAutoAttempt` 连同 `switchOpensPicker` 等 R2 遗留一并删除）；
- 0 / 2 个目标时不选任何东西，弹窗保持打开并刷新列表，用户可点选、重试或走手动兜底；1 个新目标则走显式选择路径采用。

### 其他 R2 → R3 的机械改动

| 改动 | 原因 |
|---|---|
| `DiscoverySettings` 新增 `hasValidToken()` 与 `saveDevToken()` | 让真实 `requestSwitch()` 能在 JVM 测试里用假实现驱动（必修 1 要求的行为测试需要它）；写入接口显式声明，ViewModel 不再伸手进具体 store |
| `RecordingViewModel` 主构造参数改为 `DiscoverySettings`，另加 `constructor(store, context)` | 生产路径不变（`Factory` 仍传 `SettingsStore`），测试可注入假设置 |
| `RecordingViewModel` 新增 `coordinatorOverride` 与 `onReadyEnteredForTest()` | 让真实入口被驱动 / 让「已验证目标使 Ready 成为 no-op」可断言，且不动已有源码守护签名 |

---

## 二、变更文件

路径相对 `%USERPROFILE%\Documents\ChatGPT\Saylt`。Git 仍无提交基线，以下为**实际文件哈希**。

### 产品源码（6 个，含 1 个新增）

| 文件 | 变更 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/net/BrowseWorker.kt` | **新增**：浏览作用域 worker（会话 start/stop、join 保证退出、空闲零轮询、去重/容量、异常不杀线程） | `00dc319df6f57e679462ca80d472dc79cd467a2488edc3c75c4b05234ff86e88` | 8458 |
| `watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt` | worker 绑到 `start()`/`stop()`；单飞重试改为逐条；构造不再起线程 | `3490414de559119931a879dff4e4dd8e98e5eca73d286d5a8d212618c1889d7b` | 16657 |
| `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt` | `ResolverBridge` 拥有弹窗/列表/`pick()`；`handleSwitchBrowse` 总是回 Idle；删除 stage A | `6a8e7b2315b48c8c273e54ea58a7818243a5f35abd8147cedbf7d324f5feb8d4` | 57354 |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` | `requestSwitch()` 只做一次有界浏览、采用走 `onTargetPicked`；设置端口注入；测试 seam | `89ab36aeb01412af8df06cb66b1b86d5fd7c1cd0f03fb354e3c19c6166b497c9` | 39250 |
| `watch/app/src/main/java/com/sayit/watch/net/Discovery.kt` | **未改**（R2 哈希一致） | `3c7be6b4bcf8ebedfbe23300451d320dbcaf5ee3d6afe15657e0314d242acde5` | 24270 |
| `watch/app/src/main/java/com/sayit/watch/net/SerialResolveQueue.kt` | **未改**（R2 哈希一致） | `458c3f970e4d49568bee197285434feb5efe0c35e78199ffe8f8294a43154b40` | 4877 |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` | **未改**（R2 哈希一致） | `8498de6186afe2d5c0d023e78779282cb6809158bf2fbf7e2e543336a0f593cf` | 26572 |
| `watch/app/src/main/res/values/strings.xml` | **未改**（R2 哈希一致） | `475da0fbd1e90eda325eabc2e8f895ce6f9cf4b44b770277355510ad89a82e0a` | 3058 |

> 变更的产品源码实际为 4 个文件（1 新增 + 3 修改）；其余 4 个只列出以证明未动。

### 测试（6 个，含 1 个新增）

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `watch/app/src/test/java/com/sayit/watch/net/DiscoverySwitchFlowR3Test.kt` | **新增**：14 条 R3 行为测试 | `e221e3789dbeba165d1b562138ce0018e306e75b741e3f86d1fa9dd179581773` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt` | 假设置补 2 个接口方法 | `0faeb97843361222e8e7343243d5295629cf960756006d9f7f7481b7c1407845` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRepairTest.kt` | 同上 | `4806eac5080aaf41c3df57f62418fa13f01cdbfae41343d653cb5f1171d5188b` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryIntegrationTest.kt` | 同上 | `586f75db6474e1651161f6eaa11b7d9d05333550b068d5a10334d939d345e427` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRegressionTest.kt` | 同上 | `4d75df20787bbe70de806fd5dcf6968b64254ad523b3fb7b635dec0d8f9b386b` |

### 未改动

`mdns.rs`、`watch_receiver/*`、`MainActivity.kt`、`settings/**`、`build.gradle.kts`、`AndroidManifest.xml`、`Cargo.toml`、`Cargo.lock`、依赖、权限、版本号、`HANDOFF.md`、`PROJECT_PROGRESS.md`、任务包 —— **零改动**；窗口未做任何网络安全设置变更，未安装任何设备。

### 回滚材料

1. **新增文件**（删除即回滚对应改动）：`BrowseWorker.kt`、`DiscoverySwitchFlowR3Test.kt`。
2. **R3 未触碰、可直接作为还原源的 R2 文件**：`Discovery.kt`、`SerialResolveQueue.kt`、`RecordingScreen.kt`、`strings.xml`（哈希见上）。
3. 需要还原 R2 的 4 个文件，请用 R2 回传里的哈希定位其 R2 版本：
   `AndroidNsdDiscovery.kt` = `aa1317f372f6c70d4ab3f503a17ad460a5c3fe3921e050e0940da3e050c79391`、
   `DiscoveryCoordinator.kt` = `77e33cfa17c15a2bb4955ec18d73b21f71778eda9d395a82a2487e3a2408170d`、
   `RecordingViewModel.kt` = `99617e11f9356230eff2f39633033443809a50c1e7961ece0d9b8cdb241a0e5c`、
   以及 R2 的 3 个测试文件。**D 侧没有保存这些旧副本**，还原依赖 PM 侧留存的 R2 交付物。
4. 回滚后应回到 `161 tests / 0 failures`（R2 状态）。

---

## 三、验证结果

命令均在 `%USERPROFILE%\Documents\ChatGPT\Saylt\watch`，`JAVA_HOME=jdk-17.0.20.101-hotspot`、`ANDROID_HOME=%LOCAL_BUILD_ROOT%\sdk`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `gradlew.bat testDebugUnitTest --rerun-tasks --tests com.sayit.watch.net.DiscoverySwitchFlowR3Test`（先做最小定向） | 0 | `14 tests / 0 failures` |
| `gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain` | **0** | BUILD SUCCESSFUL；`175 tests / 0 failures / 0 errors / 0 skipped`；lint 0 error / 38 warning（与 R2 同数，均既有）；APK 产出 |

**Debug APK**：`watch/app/build/outputs/apk/debug/app-debug.apk`
SHA-256 `5695122f8a11b5ee6811c6fc168e2f09504a6a7c8d4c450a6280d9d9b901e846`，20,593,448 bytes。

完整日志：`%LOCAL_BUILD_ROOT%\r3-verify4.log`（最终状态）。此前三轮 `r3-verify*.log` 记录了定位过程中的失败与修正。

### 测试数变化

R2 `161` → R3 `175`（+14：`DiscoverySwitchFlowR3Test`）。测试只增不减、无跳过。

### 对三项必修的直接覆盖

| 必修 | 覆盖测试 |
|---|---|
| 1 首次切换可被采用 | `the real switch entry point adopts the first newly discovered computer`（真实 `requestSwitch()` → 收集 → 采用 → 落盘 → Ready，断言只浏览 1 次） |
| 1 旧目标未确认前继续可用 | `the legacy target keeps working until the user confirms the switch` |
| 1 两个目标不静默选 / 未认证不可选 | `two newly authenticated computers are offered and never chosen silently`、`an endpoint this session never authenticated can never be picked` |
| 1 取消/再次进入 | `dismissing the picker changes nothing and a second switch still works` |
| 2 stop 后线程确定退出、无残留 | `the resolve worker exists only for the browse and stop joins it`（含 `Thread.getAllStackTraces()` 计数为 0） |
| 2 不累积线程 | `a stopped worker discards late work and threads are not accumulated`（5 轮 start/stop，每轮线程数归零） |
| 2 等不到回调也能停 | `the worker stops even while it waits for a platform callback that never arrives` |
| 2 单飞 + 去重 + 空闲不轮询 | `the worker keeps one outstanding request and de-duplicates work`、`a retry of the item being processed is accepted and queued behind the work`、以及 idle 段断言 |
| 3 0/1/2 目标与重试 | `a switch that finds nothing ends in the bounded fallback and can be retried`、`no current target and one new computer still adopts in one browse`、两个目标那条 |
| 3 取消 | `a cancelled switch does not verify a target and does not leave a run behind` |
| 3 不重复等待 | `requestSwitch` 仅一次浏览的断言（`discovery.startCount == 1`） |
| 3 不留 Automatic | 0 目标后 `requestSwitch()` 重试成功（若停在 Automatic，`engine.onReadyEntered` 会返回 NoOp、第二次必失败） |
| Ready 不再重启发现 | `re-entering Ready after a completed switch does not restart discovery` |

### 既有回归

`DiscoveryPureTest`（含 3 s/8 s 真实时钟预算）、`DiscoveryRepairTest` 23 条、`DiscoveryRegressionTest` 17 条、`RecordingEntryGuardTest` 7 条源级守护、`WatchUiStateMachineTest` 16 条 —— **全部保持通过**。

---

## 四、待审问题

### A. 需要 PM 判定

1. **真机仍未验证。** 本轮只做源码与构建；没有设备、没有安装 APK、没有改任何网络/安全设置。自动发现根因与台式机↔笔记本切换依旧待 PM 真机复测。
2. **`viewModelScope` 的测试方式。** 真实入口测试用 `Dispatchers.setMain(UnconfinedTestDispatcher())`，即真的让 ViewModel 启动协程，只把 Android 主 looper 换掉。若 PM 认为应改用 Robolectric 或 instrumentation 级验证，请指示——那会引入新依赖，超出本包范围。
3. **`onReadyEnteredForTest()` 这个 seam。** 为了让「已验证目标使 Ready 成为 no-op」可断言而新增，它复用 `acceptsAutomatic(...)` 而不是复制决策逻辑。若 PM 不接受为测试新增产品方法，我可以改为断言 `discovery.startCount` 不变来覆盖（现已同时断言两者）。
4. **`SerialResolveQueue` 的处置。** 它已不在生产热路径（R2 的 worker 被 `BrowseWorker` 取代），但仍是 R2 交付物、有 4 条测试、且被 R3 测试用作常量来源。本轮**保留未改**以免扩大范围。若 PM 希望删除以消除重复概念，请单独派发。

### B. 本轮自己发现并修掉的缺陷（诚实记录）

5. `BrowseWorker.pollOrPark()` 最初用非局部 `return` 提前返回，跳过了释放 key，导致同一实例**永远**被判重复——瞬态重试完全发不出去。R3 的 `a retry of the item being processed...` 测试把它逼出来了，已在交接时释放 key。
6. `process()` 抛出的异常会静默终止整条 worker 线程。现在单条异常计入 `failedCount`，worker 继续工作。

两条都是 **R3 新增测试直接抓到的**，也说明 R2 的绿灯确实没有覆盖这些路径。

### C. 限制与边界声明

7. **`AndroidNsdDiscovery` 适配器本身仍无 JVM 单测**（需要真实 `Context`/`NsdManager`）。可测部分已抽到 `BrowseWorker`（6 条）与 `DiscoveryPolicy`；适配器内部只剩「平台调用与队列的接线」，靠源码守护 + 真机日志。
8. **`addResolved` 的迟到回调路径**仍无直接单测（参数是 Android 的 `NsdServiceInfo`）。
9. **回滚基线**：R3 自身可回滚（见二、回滚材料第 1–2 条）；R2 版本需 PM 侧留存副本。
10. **lint 仍为 38 warning / 0 error**，与 R2 同数，非本轮引入。

未安装 APK、未改 Windows/Token/防火墙、未清缓存、未 `git reset/clean/checkout`、未 push/merge/tag/release。

交付后 D **停止源码写入**。
