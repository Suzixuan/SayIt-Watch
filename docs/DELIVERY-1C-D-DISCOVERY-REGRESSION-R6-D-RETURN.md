# 1C-D-04@R6 — D 限域返修回传

返修包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R6.md`
上一版本：`1C-D-04@R5`（回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R5-D-RETURN.md`）
状态：真机可见的错误事实已修（UI-only）；**mDNS 双电脑端到端仍未验收**

---

## 一、真机阻断与修法

### 真机事实（PM 提供）

保留数据覆盖安装 R5 后：单电脑 Ready 可见可点「切换电脑」与独立「设置／手动地址」（R5 必修 1 通过）；点击后日志出现 `browse:started`，约 8 秒后 `browse:stopped` / `browse:no-candidate`，旧目标保留（R5 必修 2 通过）。**但同一次启动的日志只有 `probe:authenticated` / `saved-probe:accepted` / `verdict:one`、没有 `browse:*`，Ready 却写「已自动发现电脑」**——这是用户可见的错误事实，R5 必修 3 失败。

### 根因（沿用 PM 边界，未扩大）

`ResolverBridge.routeAutomatic()` 把任何已认证目标的 `lastPublishedState` 设为 `Discovered`；`RecordingViewModel.applyResolverVerdict()` 又无条件把目标成功写成 `DiscoveryState.Discovered`（手动成功、用户选择同理）。所以 UI 拿到的**永远是 `Discovered`**，靠状态分支无法区分「本次浏览」与「旧址复查」。R5 的 `readyStatusTextRes(ExistingAddress, true)` 测试只覆盖了一个生产链拿不到的状态。

### R6 修法（UI-only，最小范围）

只改 `RecordingScreen.kt` 的一个映射：**任何已认证可用目标统一中性表述「已连接电脑」**。

| 状态 | `hasVerifiedTarget` | 文案 |
|---|---|---|
| `Searching` | 任意 | 正在搜索电脑…（动作，不是结果） |
| `Discovered` | **true** | **已连接电脑**（原为「已自动发现电脑」← 真机错误事实） |
| `Discovered` | false | 等待认证结果… |
| `ExistingAddress` | true | 已连接电脑 |
| `ExistingAddress` | false | 等待认证结果… |
| `Idle` | true / false | 已连接电脑 / 尚未找到电脑 |
| `ManualFallback` | 任意 | 未找到电脑，点此搜索或手动设置地址 |

关键性质：**`readyStatusTextRes` 的任何分支都不再返回 `discovery_found`**，所以 `Discovered` 无法渲染出「已自动发现电脑」。这是**fail-safe** 的：将来即使内部来源追踪被修正（能真正区分浏览与旧址复查），这段映射也无需先改。

**未做**（R6 明确禁止）：没有重构 `ResolverBridge` / 没有引入来源追踪 / 没有改 `RecordingViewModel.kt`、`DiscoveryCoordinator.kt`、NSD、Windows、Token、设置、版本、持久化或发布路径。`strings.xml` 也**未改动**（中性文案 `discovery_saved_neutral` 在 R5 已存在）。R5 的 0/1/多电脑入口、旧目标保持、单次浏览行为全部保留。

---

## 二、变更文件

路径相对 `%USERPROFILE%\Documents\ChatGPT\Saylt`。Git 仍无提交基线，以下为**实际文件哈希**。

### 产品源码（1 个修改，无新增）

| 文件 | R6 变更 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` | `readyStatusTextRes`：`Discovered + 已认证` 由 `discovery_found` 改为 `discovery_saved_neutral`；补 KDoc 说明 UI 无法区分来源 | `622ac2a6f7e2d99f6dcdddbabfdb5d9831707e5aba20bdba0621cd92f501e4c8` | 30655 |

**未改动**（哈希与 R5/R4 一致）：

- `watch/app/src/main/res/values/strings.xml` = `a43866337ef832e6e508190c758211037bd86b1eebf452dc4486f3f10e1fef45`（R5 版本，R6 未动）
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` = `ab493e41957ad78cf1c4882229d35d443417a2fd3b02c4f3a769f6928968843f`（R4 起未动）
- `DiscoveryCoordinator.kt`、`BrowseWorker.kt`、`AndroidNsdDiscovery.kt`、`Discovery.kt`、`SerialResolveQueue.kt`、NSD、Windows receiver、录音上传、Token、权限、依赖、版本号（仍 `0.3.0-dev.1`/code 5）、发布路径、防火墙。

### 测试（1 个新增 + 1 个适配）

| 文件 | 变更 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/test/java/com/sayit/watch/ui/RecordingReadySavedProbeWordingR6Test.kt` | **新增** 6 条：真实 `onReadyEntered()`→生产链→UI 文案映射 | `a2c561b13571e3b3e5b0c1f8881cead928b890b152c4f0918b19cbfcbd07b877` | 14810 |
| `watch/app/src/test/java/com/sayit/watch/ui/RecordingReadyEntryR5Test.kt` | R5 中「`Discovered`→discovery_found」的断言按 R6 契约改为中性（该断言正是被真机证伪的那条） | `ffda9385afdc5cab59f0cae0c2b0b1d082e71a4abf1677cdad98237812220adc` | 11361 |

> **计数笔误更正**：R5 回传把 `RecordingReadyEntryR5Test` 记作 7 条，PM 核对为 **8 条**。是我的计数笔误，现已按 PM 的 8 条为准，非代码问题。

### 与 R5 的可恢复差异材料

1. **R6 新增文件只有 1 个测试**（`RecordingReadySavedProbeWordingR6Test.kt`），删除即可。
2. **R6 只改 1 个产品文件**。回退到 R5 只需恢复：
   - `RecordingScreen.kt` = R5 的 `f8dbf0b0ae4ca1ea7af8424fd171fb8c95baaaed6329630af3ae71ce1d36d323`
   - `RecordingReadyEntryR5Test.kt` = R5 的 `6408ad3920e7ad472de24a70e265b76516ae8574db33abd8fa520f32553fe38d`
   其余文件 R6 未触碰。**D 侧不留存 R5 副本**，还原依赖 PM 侧留存的 R5 交付物。
3. R6 改动**自包含**：只影响 Ready 的状态文案分支，不触及任何解析/网络/持久化逻辑，单独回退不会影响 R5 的入口与单次浏览成果。
4. 回退后应回到 `189 tests / 0 failures`（R5 状态）。

---

## 三、验证结果

命令均在 `%USERPROFILE%\Documents\ChatGPT\Saylt\watch`，`JAVA_HOME=jdk-17.0.20.101-hotspot`、`ANDROID_HOME=%LOCAL_BUILD_ROOT%\sdk`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `gradlew.bat testDebugUnitTest --rerun-tasks --tests com.sayit.watch.ui.RecordingReadySavedProbeWordingR6Test`（先做最小定向） | 0 | `6 tests / 0 failures` |
| `gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain` | **0** | BUILD SUCCESSFUL；`195 tests / 0 failures / 0 errors / 0 skipped`；lint **0 error / 36 warning**（与 R5 同数）；APK 产出 |

**Debug APK**：`watch/app/build/outputs/apk/debug/app-debug.apk`
SHA-256 `79bbf907563a4334dbba448a9de8ad9b465253b4ea4c40a3231090d3b9baa36e`，20,595,992 bytes。

完整日志：`%LOCAL_BUILD_ROOT%\r6-verify1.log`；定位过程另有 `r6-test1..2.log`。

### 测试数变化

R5 `189` → R6 `195`（+6）。测试只增不减、无跳过。**数量不代表真实场景结论**，真机仍待 PM。

### 必修 2 要求的「实际生产路径」覆盖

`RecordingReadySavedProbeWordingR6Test` 全部驱动**真实** `RecordingViewModel.onReadyEntered()` + 真实 `DiscoveryCoordinator`，不是纯函数单测：

| 断言 | 说明 |
|---|---|
| 日志含 `probe:authenticated` + `saved-probe:accepted`，**不含** `browse:started`；`browser.startCount == 0` | 复现真机输入，证明这是旧址复查路径 |
| `viewModel.discovery.value == Discovered` | **显式断言**：当前实现确实把它归一成 `Discovered`——这正是 R5 纯函数测试漏掉的那一环 |
| `readyStatusTextRes(Discovered, true) == discovery_saved_neutral` 且 `!= discovery_found` | **Ready 仍不声称自动发现** |
| `viewModel.discovery.value != ExistingAddress` | 把「R5 用了一个生产链到不了的状态」钉成事实，而不是注释 |
| 手动路径：`applySettings(...)` 真实走过 probe→采用，状态为 `Discovered`，文案同样中性 | 手动成功也不误称自动发现 |
| 遍历 5 个状态 × 2 个布尔：`readyStatusTextRes` **无一返回 `discovery_found`** | 穷尽性断言 |
| 源级：Ready 屏不硬编码 `discovery_found`，且 `readyStatusTextRes` 映射体（剥注释后）不含它 | 接线守护 |
| 资源：`discovery_saved_neutral` 存在且不含「自动发现」 | 文案守护 |

源级接线的 0/1/多目标入口测试（`RecordingReadyEntryR5Test`，8 条）与 `DiscoverySwitchFlowR3Test`（20 条）仍全部通过。

### 未覆盖（明确交回 PM 真机）

- **Compose 渲染本身**：本 JVM 环境不能渲染 Compose，所以「真机圆屏上实际显示的是『已连接电脑』」仍由 PM 复验；本轮保证的是**映射不可能产出错误文案**。
- 笔记本不在线，跨设备 mDNS / 双电脑端到端切换仍未验证。

### 既有回归

`DiscoveryPureTest` 26、`DiscoveryRepairTest` 23、`DiscoveryRegressionTest` 17、`RecordingEntryGuardTest` 7、`WatchUiStateMachineTest` 16、`DiscoverySwitchFlowR3Test` 20、`RecordingReadyEntryR5Test` 8、`RecordingReadySavedProbeWordingR6Test` 6 —— **全部通过**。

---

## 四、待审问题

### A. 需要 PM 判定

1. **真机复验只差「看一眼文案」**：请用 R6 APK 覆盖安装，确认单电脑 Ready（日志只有 `saved-probe:accepted` / `verdict:one`）显示的是「已连接电脑」而不是「已自动发现电脑」；同时确认搜索中/无目标/失败三种提示仍准确。
2. **`discovery_found` 资源仍在但 Ready 不可达**：`readyStatusTextRes` 已不返回它，只有 `discoveryLabel()`（内部文案策略函数，无生产调用者）还在用它。我没有删（R6 要求不动 `strings.xml`），也没有把映射改成条件返回它——因为 UI 目前无法证明一次真实浏览。**若 PM 希望彻底移除资源或让浏览成功重新使用该说法，需要先修内部来源追踪，那是 R6 明确排除的范围**，请单独派发。
3. **计数笔误更正**：R5 回传的 `RecordingReadyEntryR5Test` 条数应为 8（PM 计数正确），文档已更正。

### B. 限制与边界声明

4. **本轮零解析/网络改动**，所以 195 条绿灯**不能**用来推断 mDNS 或双电脑已通过；真机日志里那次缺 `browse:*` 说明当次确实没发生浏览（也不该发生）。
5. **`RecordingViewModel.kt` / `DiscoveryCoordinator.kt` / `strings.xml` 本轮未动**，`readyStatusTextRes` 是唯一产品改动点。
6. **Compose 渲染无 JVM 覆盖**（见上）。
7. **回滚基线**：R6 自身可回滚（见二、第 1–2 条）；R5 版本需 PM 侧留存副本。

未改 Token / 保存地址 / 应用数据 / 权限 / 依赖 / 版本号 / Windows / 防火墙 / 发布路径；未清缓存、未安装手表 APK、未 push/merge/tag/release。

交付后 D **停止源码写入**。
