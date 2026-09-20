# 1C-D-04@R4 — D 限域返修回传

返修包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R4.md`
上一版本：`1C-D-04@R3`（回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R3-D-RETURN.md`）
状态：两处阻断已修，源码与构建验证完成；**真机自动发现/切换仍未验收**

---

## 一、R3 → R4 变化（对应两处阻断）

### 必修 1：两个候选时内部已暗选第一台

**R3 错在哪**：`ResolverBridge.handleSwitchBrowse()` 在有当前目标时用 `engine.verifiedTarget`，否则退化到 `collected.firstOrNull()` 并 `stateAdoptedByUser(keep)`。于是「无当前目标 + 两台同时认证」时，engine 内部**已经**把第一台当作 verified target 存下；R3 测试只断言浏览刚结束时的 `currentDestination()==null`（此时 `_canRecord` 也是 false）所以绿，但 `RecordingViewModel.onReadyEntered()` 会用 `resolver.verifiedTarget?.let { setVerifiedDestination(it) }` 把这一台变成可录音/可上传目标——绕过用户选择。

**R4 修法**：把「搜索结束/恢复 Idle」与「目标已被采用」彻底分开。

```kotlin
val keep = engine.verifiedTarget ?: only      // R3 是 ... ?: collected.firstOrNull()
if (keep != null) engine.stateAdoptedByUser(keep) else engine.onConfigEntered()
```

语义现在是：

| 情况 | 内部 verified target | 说明 |
|---|---|---|
| 有旧目标 | 保持旧目标（`stateAdoptedByUser`） | 旧目标在确认前继续可用，规格承认 |
| 无旧目标 + 恰好一台新候选 | 采用那一台（`stateAdoptedByUser`） | 用户显式点了「切换电脑」，返回的就是唯一新候选 |
| 无旧目标 + 0 或多台 | **保持 null**（`engine.onConfigEntered()` 回 Idle） | 不再按发现顺序暗选；选择面板列出所有已认证电脑 |

`endPickerSearch()` 兜底保证任何提前返回路径都不会留在 Automatic；`engine.onConfigEntered()` 同时 bump 世代，所以迟到的 `AutomaticSucceeded` 也无法把第一台再塞回来。

### 必修 2：`stop()` 的线程退出保证不成立

**R3 错在哪**：`BrowseWorker.stop()` 在 join **之前**执行 `thread = null`，然后只 `join(2000)`。若 `process` 在 2 秒内不响应 interrupt，`isStopped()`（以及所有基于它的「无残留线程」测试）会因为句柄为空而谎报 true，`start()` 还能再起第二条线程。

**R4 修法**：

- `stop()` **不再提前清句柄**：join 返回后重新检查 `toJoin.isAlive`；只有确认已退出时才清 `thread`、置 `CLOSED`。
- 超时则保留存活句柄、`state=CLOSING`、`joinTimeouts++`，并返回 `false`。`isRunning` / `canStart` 继续报真实情况。
- `start()` 在旧线程仍存活时**拒绝**开新会话（返回 `null`、`state=CLOSING`），因此**不可能叠加线程**。
- `stop()` 仍是有界的（`joinTimeoutMs`，默认 2000 ms），不会为等平台回调而无界卡住 UI 停止路径。
- **诚实声明**：平台调用无法被本层强杀，所以 **`stop()` 返回不再等于「线程已死」**。调用方必须看 `isRunning` / `canStart`；`AndroidNsdDiscovery.stop()` 与 `isResolveWorkerAlive` 都按这个语义更新。

### 其他

- `AndroidNsdDiscovery.start()` 在 `resolveWorker.start()` 返回 null（旧线程未退）时记录 `BROWSE_START_FAILED`，浏览仍然打开，只是暂不接受解析——不叠线程、也不让整条发现失败。
- 新增只读 seam：`BrowseWorker.joinTimeouts` / `hasLingeringThread` / `canStart`、`AndroidNsdDiscovery.resolveWorkerJoinTimeouts` / `resolveWorkerState`、`RecordingViewModel.isAutomaticResolutionActiveForTest`。
- **没有做第四轮架构重写**：`ResolverBridge` 的列表/pick 归属、`handleSwitchBrowse` 的单次浏览、`BrowseWorker` 的会话模型全部沿用 R3。

---

## 二、变更文件

路径相对 `%USERPROFILE%\Documents\ChatGPT\Saylt`。Git 仍无提交基线，以下为**实际文件哈希**。

### 产品源码（4 个，均为修改，无新增）

| 文件 | R4 变更 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt` | `handleSwitchBrowse` 去掉 `collected.firstOrNull()` 暗选；无唯一候选时目标保持 null 并回 Idle | `4d84ba7612c77f525bb0bbb1f492500e73bb77e6739de1d153dc0591024c8969` | 57925 |
| `watch/app/src/main/java/com/sayit/watch/net/BrowseWorker.kt` | 保留未退出线程句柄、`CLOSING` 状态、`joinTimeouts`、`stop(): Boolean`、`start()` 拒绝叠线程、`canStart` | `161d4ff2e3107cedc79a95eb3349a39edd1d1fd553f7760e7d46a32a6177f79b` | 11198 |
| `watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt` | `start()` 处理 worker 拒绝；`stop()` 记录有界 join 结果；新增 2 个 seam | `cf99fc0216a9e0778a81e341ea6cb104f63377ad4ed321cfadce52674e016231` | 17638 |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` | 新增 `isAutomaticResolutionActiveForTest` seam（无行为改动） | `ab493e41957ad78cf1c4882229d35d443417a2fd3b02c4f3a769f6928968843f` | 39580 |

**R3 → R4 未改动**（哈希与 R3 回传一致）：`Discovery.kt` `3c7be6b4…`、`SerialResolveQueue.kt` `458c3f97…`、`RecordingScreen.kt` `8498de61…`、`strings.xml` `475da0fb…`。

### 测试（1 个文件改动）

| 文件 | R3 → R4 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/test/java/com/sayit/watch/net/DiscoverySwitchFlowR3Test.kt` | 18 条（R3 14 条中 3 条线程断言改为「等待真实退出」；新增 4 条 R4 用例） | `9e89db492be4f48aefe37a0beee8c852801baf1003d6c6542d392fb991619b23` | 30179 |

其余 4 个测试文件的哈希与 R3 完全一致（`DiscoveryRegressionTest` `4d75df20…`、`DiscoveryPureTest` `0faeb978…`、`DiscoveryRepairTest` `4806eac5…`、`DiscoveryIntegrationTest` `586f75db…`）。

### 未改动

`mdns.rs`、`watch_receiver/*`、`MainActivity.kt`、`settings/**`、`build.gradle.kts`、`AndroidManifest.xml`、Cargo 依赖与锁、权限、版本号、发布配置、录音/上传协议、前端、`HANDOFF.md`、`PROJECT_PROGRESS.md`、任务包 —— **零改动**。未改任何网络安全设置，未清数据，未安装设备。

### 对 R3 的可恢复差异材料

1. **R4 未新增文件**，所以没有「删文件即回滚」的捷径；R4 只改了 4 个产品文件 + 1 个测试文件。
2. 需要回退到 R3 时，用 R3 回传里的哈希定位 R3 版本：
   - `DiscoveryCoordinator.kt` = `6a8e7b2315b48c8c273e54ea58a7818243a5f35abd8147cedbf7d324f5feb8d4`
   - `BrowseWorker.kt` = `00dc319df6f57e679462ca80d472dc79cd467a2488edc3c75c4b05234ff86e88`
   - `AndroidNsdDiscovery.kt` = `3490414de559119931a879dff4e4dd8e98e5eca73d286d5a8d212618c1889d7b`
   - `RecordingViewModel.kt` = `89ab36aeb01412af8df06cb66b1b86d5fd7c1cd0f03fb354e3c19c6166b497c9`
   - `DiscoverySwitchFlowR3Test.kt` = `e221e3789dbeba165d1b562138ce0018e306e75b741e3f86d1fa9dd179581773`
3. R4 的改动是**局部且可逆**的：必修 1 只在一行 `keep =` 表达式与其上下注释；必修 2 只在 `stop()`/`start()`/状态枚举及三个只读 seam。**D 侧不留存 R3 副本**，还原依赖 PM 侧留存的 R3 交付物。
4. 回退后应回到 `175 tests / 0 failures`（R3 状态）。

---

## 三、验证结果

命令均在 `%USERPROFILE%\Documents\ChatGPT\Saylt\watch`，`JAVA_HOME=jdk-17.0.20.101-hotspot`、`ANDROID_HOME=%LOCAL_BUILD_ROOT%\sdk`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `gradlew.bat testDebugUnitTest --rerun-tasks --tests com.sayit.watch.net.DiscoverySwitchFlowR3Test`（先做最小定向） | 0 | `18 tests / 0 failures` |
| 同上，重复 3 次（排查线程断言抖动） | 0 / 0 / 0 | 三次均 BUILD SUCCESSFUL |
| `gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain` | **0** | BUILD SUCCESSFUL；`179 tests / 0 failures / 0 errors / 0 skipped`；lint 0 error / 38 warning（与 R2/R3 同数，均既有）；APK 产出 |

**Debug APK**：`watch/app/build/outputs/apk/debug/app-debug.apk`
SHA-256 `086e6053245fc4fca994ed402217755037aa02d5a5be77d80e0ddd5ae7d6042f`，20,593,448 bytes。

完整日志：`%LOCAL_BUILD_ROOT%\r4-verify1.log`、`%LOCAL_BUILD_ROOT%\r4-flake1..3.log`；定位过程另有 `r4-test1..5.log`。

### 测试数变化

R3 `175` → R4 `179`（+4）。测试只增不减、无跳过。**数量不代表真实场景结论**，真机仍待 PM。

### 对两处阻断的直接覆盖（新增 4 条）

| 阻断 | 覆盖测试 | 关键断言 |
|---|---|---|
| 必修 1 | `two candidates with no current target leave no hidden target after Ready re-entry` | 浏览后 `currentDestination()==null`、`canRecord==false`、`isAutomaticResolutionActiveForTest==false`；**随后调用真实 `onReadyEntered()` 后目标仍 null、仍不可录音、落盘仍 0**；取消并再次搜索仍 null、仍 0；只有显式 `onTargetPicked` 才产生目标 |
| 必修 1 | `a current target survives a two-candidate browse and is never re-picked` | 两台新机器同时认证且都不是当前目标时，旧目标保持、落盘数不变、引擎不留在 Automatic，选择面板列出三台；用户点选后才落盘 |
| 必修 2 | `a worker that ignores interrupts is not reported as stopped` | 有界 join 超时后 `stop()` 返回 **false**、`isRunning==true`、`canStart==false`、`joinTimeouts==1`、`start()` 返回 null、线程数仍为 1、迟到 `offer` 被拒；进程最终退出后才允许新会话并恢复 |
| 必修 2 | `a stop that timed out still lets the next session run exactly one worker` | 用「interrupt 打不开的 latch」模拟不响应 interrupt 的平台调用：超时后句柄保留、拒绝第二会话、释放后恰好一条线程、新会话可正常处理 |

R3 的三条线程测试也从「句柄断言」改成「等待真实退出」：`the resolve worker exists only for the browse and stop joins it`、`a stopped worker discards late work and threads are not accumulated`、`the worker stops even while it waits for a platform callback that never arrives`。这是修 R4 阻断 2 的必要配套——原来的 `assertEquals(0, workerThreads())` 在 `stop()` 返回瞬间是有竞态的。

### 既有回归

`DiscoveryPureTest` 26、`DiscoveryRepairTest` 23、`DiscoveryRegressionTest` 17、`RecordingEntryGuardTest` 7 条源级守护、`WatchUiStateMachineTest` 16、`DiscoverySwitchFlowR3Test` 18 —— **全部通过**。

---

## 四、待审问题

### A. 需要 PM 判定

1. **真机仍未验收。** 本轮只判源码与构建。台式机↔笔记本自动切换、mDNS 根因仍**单列未验证**，不得由本次返修推断为已验收。
2. **`stop()` 的真实上限（重要且诚实）。** 平台 `resolveService` 回调若永不返回且不响应 interrupt，本层**无法强杀线程**。因此语义已改为「`stop()` 有界返回，但不保证线程已死」；调用方需读 `isRunning`/`canStart`。`AndroidNsdDiscovery` 已按此更新（超时会记录一条无地址的 cat 日志）。若 PM 要求更强的硬退出保证，需要一个 R4 未授权的机制（例如放弃 `NsdManager` 单飞 `resolveService` 改用可取消的查询 API），请单独派发。
3. **超时值。** `joinTimeoutMs` 默认 2000 ms 是 R3 引入的值，R4 保留未改（UI 停止路径仍有界）。若 PM 认为应更短或可配置，请给数值。
4. **测试 seam 的接受度。** R4 新增的 seam 都是只读：`joinTimeouts` / `hasLingeringThread` / `canStart` / `resolveWorkerJoinTimeouts` / `resolveWorkerState` / `isAutomaticResolutionActiveForTest`。若 PM 不接受为测试新增产品成员，请指示，可改为仅靠已有公开面（`isRunning` + 线程枚举）断言。
5. **`onReadyEnteredForTest()` 与 `SerialResolveQueue` 两个 R3 遗留项仍未处置**：前者是我在 R3 加的 seam，后者已不在生产热路径但保留着 4 条 R2 语义测试。R4 未授权范围外改动，故原样保留。

### B. 本轮自己发现并修掉的问题（诚实记录）

6. **R3 的三条线程测试本身有竞态**：`worker.stop()` 返回后立即 `assertEquals(0, workerThreads())` 会在工作线程尚未完全退出时偶发失败（R4 定向运行时实际暴露了它）。已改为等待真实退出（`waitNoWorkerThreads`，有界 5 s），断言强度不变。
7. **`a retry of the item being processed...` 的同类竞态**同因同修。

### C. 限制与边界声明

8. **`AndroidNsdDiscovery` 适配器本身仍无 JVM 单测**（需真实 `Context`/`NsdManager`）；可测部分在 `BrowseWorker`（现 8 条线程/队列行为）与 `DiscoveryPolicy`。
9. **`addResolved` 迟到回调路径**仍只被世代检查间接覆盖（参数是 Android 的 `NsdServiceInfo`）。
10. **lint 仍为 38 warning / 0 error**，与 R2/R3 同数，非本轮引入。

未改 Windows/权限/依赖/Token/发布配置/录音上传协议/前端/网络安全设置；未清数据、未装设备、未 `git reset/clean/checkout`、未 push/merge/tag/release。

交付后 D **停止产品源码写入**。
