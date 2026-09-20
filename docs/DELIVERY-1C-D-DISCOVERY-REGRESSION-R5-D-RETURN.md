# 1C-D-04@R5 — D 限域返修回传

返修包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R5.md`
上一版本：`1C-D-04@R4`（回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R4-D-RETURN.md`）
状态：真机暴露的入口不可达已修；源码与构建验证完成。**mDNS 与两电脑真机切换仍未验收**

---

## 一、真机问题与修法

### 真机事实（PM 提供，本轮不重新推断）

手表保留原 Token/地址覆盖安装 R4 后进入 Ready，日志只有 `probe:authenticated` / `saved-probe:accepted` / `verdict:one`，**没有本次 `browse:*`**：这是**已保存地址复查成功**，不是一次 mDNS 新发现。此时 `DiscoveryOutcome.authenticated` 只含当前电脑 → `ui.targets.size == 1` → R4 的 `ReadyScreen` 把「切换电脑」隐藏了。于是用户最常见的路径（旧电脑在线时主动找另一台）**在真机上完全不可达**；失败时界面也只有「点此填写地址」，没有直接重试入口。

### 必修 1：入口在 0/1/≥2 台时都可见可点

`RecordingScreen.kt` 的 Ready 屏重写为**居中 Column**（顺手消掉了旧的 `offset(y = 106.dp)` / `offset(y = 88.dp)` 重叠）：

- 「搜索电脑 / 切换电脑」改为 `PillAction`（既有 52 dp chip），**无条件渲染**，不再以 `ui.targets.size > 1` 为前提；
- 文案随状态走：搜索中→`正在搜索电脑…`（同时禁用按钮，避免重复点击堆叠浏览）、有已知电脑→`切换电脑`、没有→`搜索电脑`；
- 0 台时下方给一行中性提示（`未找到电脑：点上方按钮重新搜索，或进入「设置 / 手动地址」`），**不强制弹出**手动 IP 页；
- 「设置 / 手动地址」保留为**独立、始终存在**的入口，且不再只在失败态出现；
- 右侧硬性守卫：Ready 屏不再出现 `if (discovery is DiscoveryState.ManualFallback)` 这种「只有失败才能进设置」的结构。

### 必修 2：点击走真实 `requestSwitch()`，一次有界浏览

按钮 `onClick = { viewModel.requestSwitch() }`，未新增任何旁路。R4 的 `ResolverBridge` / `handleSwitchBrowse` 语义原样保留：

- 只做一次 8 秒浏览，**不先做 3 秒旧址复查**（`handleSwitchBrowse` 不走 `runDiscovery` 的 saved 阶段）；
- 一台新目标 → 沿既有已认证选择路径采用（`onTargetPicked` → `resolver.pick` → 落盘）；
- 两台新目标 → 绝不暗选，列表交给用户点选；
- 0 台 → 可重试；
- 旧目标在确认前继续可用（列在面板里、可录音）。

### 必修 3：文案不再把 saved-probe 说成新发现

新增纯决策 `readyStatusTextRes(state, hasVerifiedTarget)`，Ready 屏只渲染它的资源：

| 状态 | 文案 | 说明 |
|---|---|---|
| `Searching` | 正在搜索电脑… | 动作，不是结果 |
| `Discovered` + 已认证 | 已自动发现电脑 | **只有真正的浏览判定**才用这个说法 |
| `Discovered` 未认证 | 等待认证结果… | 不谎报成功 |
| `ExistingAddress` + 已认证 | **已连接电脑** | saved-probe 复查，中性措辞 |
| `Idle` + 已认证 | 已连接电脑 | 中性 |
| `Idle` / `ExistingAddress` 未认证 | 尚未找到电脑 / 等待认证结果… | |
| `ManualFallback` | 未找到电脑，点此搜索或手动设置地址 | 指向新的重试入口 |

`discoveryLabel()`（既有内部函数）同步改为一致措辞，`WatchUiStateMachineTest` 里那条「状态文案」测试也升级为**断言 `ExistingAddress` 文案不含「自动发现」**。

---

## 二、变更文件

路径相对 `%USERPROFILE%\Documents\ChatGPT\Saylt`。Git 仍无提交基线，以下为**实际文件哈希**。

### 产品源码（2 个，均为修改，无新增）

| 文件 | R5 变更 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` | Ready 屏改居中 Column；入口无条件渲染 + 状态化文案 + 禁用态；`readyStatusTextRes` / `switchEntryLabelRes` / `searchEntryAvailable` 纯决策；`discoveryLabel` 措辞对齐 | `f8dbf0b0ae4ca1ea7af8424fd171fb8c95baaaed6329630af3ae71ce1d36d323` | 29708 |
| `watch/app/src/main/res/values/strings.xml` | 新增 `ready_search_computer` / `ready_entry_searching` / `ready_search_hint` / `discovery_saved_neutral` / `discovery_awaiting` / `discovery_none_yet`；`discovery_searching`、`discovery_manual`、`ready_open_config` 改中性措辞 | `a43866337ef832e6e508190c758211037bd86b1eebf452dc4486f3f10e1fef45` | 3560 |

**`ui/RecordingViewModel.kt` 未改动**（R5 允可但不需要）：哈希仍为 R4 的 `ab493e41957ad78cf1c4882229d35d443417a2fd3b02c4f3a769f6928968843f`。

**R4 → R5 未改动**（哈希与 R4 一致）：`DiscoveryCoordinator.kt` `4d84ba76…`、`BrowseWorker.kt` `161d4ff2…`、`AndroidNsdDiscovery.kt` `cf99fc02…`、`Discovery.kt` `3c7be6b4…`、`SerialResolveQueue.kt` `458c3f97…`。

### 测试（3 个文件改动，1 个新增）

| 文件 | 变更 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/test/java/com/sayit/watch/ui/RecordingReadyEntryR5Test.kt` | **新增** 7 条：入口可达性（0/1/≥2）、标签决策、saved≠discovery 文案、资源声明、Ready 屏源级接线与 48 dp 规则、状态机不破坏录音 | `6408ad3920e7ad472de24a70e265b76516ae8574db33abd8fa520f32553fe38d` | 10830 |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoverySwitchFlowR3Test.kt` | 18 → 20 条：新增「入口一次有界浏览、无隐藏旧址复查」与「搜索中二次点击不叠加」；`FakeDiscovery` 支持按批次投递 | `254d0a64338eac6c565a4a02f2d7d494502cfda620bb7657771cdd99f95d37c9` | 35964 |
| `watch/app/src/test/java/com/sayit/watch/ui/WatchUiStateMachineTest.kt` | 状态文案测试升级为断言 saved 中性措辞 | `d9c1c4245866ab9659ad90c9f6cbffd737b7e2869cad8f57c12b69e20f460268` | 10161 |

其余测试文件哈希与 R4 一致。

### 未改动

`ResolverBridge`、NSD worker、Windows receiver、录音上传协议、Token、保存地址、应用数据、权限、依赖、版本号（仍 `0.3.0-dev.1`/code 5）、发布路径、防火墙 —— **零改动**；未清缓存、未安装手表 APK、未 push/merge/tag/release。

### 与 R4 的可恢复差异材料

1. **R5 新增文件只有 1 个测试**：`RecordingReadyEntryR5Test.kt`，删除即可。
2. **R5 只改了 2 个产品文件**。回退到 R4 只需恢复：
   - `RecordingScreen.kt` = R4 的 `8498de6186afe2d5c0d023e78779282cb6809158bf2fbf7e2e543336a0f593cf`
   - `strings.xml` = R4 的 `475da0fbd1e90eda325eabc2e8f895ce6f9cf4b44b770277355510ad89a82e0a`
   以及 3 个测试文件（R4 哈希：`DiscoverySwitchFlowR3Test` `9e89db49…`、`WatchUiStateMachineTest` `e221e378…` 为 R3 版本，R4 未改；请以 R4 回传清单为准）。**D 侧不留存 R4 副本**，还原依赖 PM 侧留存的 R4 交付物。
3. R5 改动是**自包含**的：只涉及 Ready 屏布局、入口可见性与文案，不触碰任何解析/网络/持久化逻辑，所以单独回退 UI 不会影响 R4 的两项修复。
4. 回退后应回到 `179 tests / 0 failures`（R4 状态）。

---

## 三、验证结果

命令均在 `%USERPROFILE%\Documents\ChatGPT\Saylt\watch`，`JAVA_HOME=jdk-17.0.20.101-hotspot`、`ANDROID_HOME=%LOCAL_BUILD_ROOT%\sdk`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `gradlew.bat testDebugUnitTest --rerun-tasks --tests com.sayit.watch.ui.RecordingReadyEntryR5Test --tests com.sayit.watch.net.DiscoverySwitchFlowR3Test`（先做最小定向） | 0 | `28 tests / 0 failures` |
| `gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain` | **0** | BUILD SUCCESSFUL；`189 tests / 0 failures / 0 errors / 0 skipped`；lint **0 error / 36 warning**；APK 产出 |

**Debug APK**：`watch/app/build/outputs/apk/debug/app-debug.apk`
SHA-256 `64a8a098da6b8bec8833ec1c5e8dc8aebeebf9fb28d6485eb4e5246b8c40a50a`，20,595,992 bytes。

> lint warning 由 R2–R4 的 38 降到 36，是因为我删掉了一条只被 UI 文本引用的旧字符串与相应死代码，**没有新增**任何 error；剩余 36 条均为既有。

完整日志：`%LOCAL_BUILD_ROOT%\r5-verify2.log`；定位过程另有 `r5-test1..4.log`。

### 测试数变化

R4 `179` → R5 `189`（+10）。测试只增不减、无跳过。**数量不代表真实场景结论**，真机仍待 PM。

### 对验收场景的覆盖

| 场景 | 覆盖 | 方式 |
|---|---|---|
| 0/1/≥2 台已认证时入口可达 | `the search entry is available with zero one and two authenticated computers` | 真实生产决策函数 `searchEntryAvailable`（非镜像实现） |
| 入口标签随状态 | `the entry label is neutral while searching and names the action otherwise` | `switchEntryLabelRes` |
| saved-probe 不算「已自动发现」 | `a saved address verdict is never worded as a new automatic discovery` + `WatchUiStateMachineTest` 升级断言 | `readyStatusTextRes` / `discoveryLabel` |
| 所有状态都有资源文案 | `every status state maps to a distinct, resource-backed label` | 同上 |
| 入口无条件渲染且接真实 `requestSwitch()` | `the Ready screen renders the entry unconditionally and wires it to requestSwitch` | 源级守卫（剥注释后匹配，不受说明性注释干扰） |
| 48 dp 触控 + 不再重叠 | `the entry meets the existing 48 dp touch rule and avoids the old overlaps` | `PillAction` / `RowMinHeightDp` / 禁止旧 `offset(y=106.dp)`、`offset(y=88.dp)` |
| 所需字符串都存在 | `the UI strings the entry needs are all declared` | `strings.xml` |
| 点击 → 真实 ViewModel、一次浏览、旧目标保持、首次新目标采用、落盘、Token 不变 | `the entry point runs one bounded browse without a saved-address re-probe` | 真实 `onReadyEntered()` + 真实 `requestSwitch()` + 真实 `DiscoveryCoordinator`，断言 `startCount==1`、`BROWSE_STARTED` 出现、认证探针恰 2 次（证明**没有**隐藏的 3 秒旧址复查）、`tokenWrites==0` |
| 失败后重试 / 搜索中不叠加 | `a switch that finds nothing ends in the bounded fallback and can be retried`（R4 起）+ `a second press while the bounded browse is running is refused` | 真实入口 |
| 搜索不破坏录音门 | `the state machine can carry a searching entry without breaking recording` | `WatchUiStateMachine` |

### 未覆盖（明确交回 PM 真机）

- **Compose 渲染本身**：本 JVM 环境无法渲染 Compose，所以「入口在 480×480 圆屏上实际完整可见、不遮挡录音与设置、触控区域真实命中」**只由源级守卫 + 上述决策函数间接保证**，必须由 PM 真机复验。这是本包明确接受的分工，不是已验结论。
- mDNS 双电脑端到端切换、笔记本在线场景仍未验证。

### 既有回归

`DiscoveryPureTest` 26、`DiscoveryRepairTest` 23、`DiscoveryRegressionTest` 17、`RecordingEntryGuardTest` 7、`WatchUiStateMachineTest` 16、`DiscoverySwitchFlowR3Test` 20、`RecordingReadyEntryR5Test` 7 —— **全部通过**。

---

## 四、待审问题

### A. 需要 PM 判定

1. **真机复验是必需的，且只差这一步**：本轮证明的是「代码里入口不再被 `targets.size > 1` 关掉、点击确实进 `requestSwitch()`、文案中性」。请用 R5 APK 覆盖安装后确认：正常单电脑 Ready 上能看到并按到入口；按下后日志出现 `browse:started`（而不是只有 saved-probe）；笔记本在线时能找到并点选第二台。
2. **布局改动幅度**：Ready 屏从「手写 offset」改为「居中 Column」。我按圆屏内接方约束算过（纵向合计约 233 dp < 约 261 dp 内接方，横向 chip 宽 0.72×226≈163 dp），但**真实圆屏是否被裁切只能真机看**。若 PM 认为风险偏高，我可以改为更保守的双行布局。
3. **`discoveryLabel` 的去留**：R5 后 Ready 屏已改用 `readyStatusTextRes(...)`，`discoveryLabel` 只剩措辞策略与测试在用（无生产调用者）。删或留请指示（删除会影响 `WatchUiStateMachineTest` 的一条）。
4. **`ready_discovery_failed` 已无引用**：失败态的重试入口取代了它。若 PM 希望保留该字符串做兜底提示，我可以加回。

### B. 限制与边界声明

5. **未改任何解析/网络逻辑**，所以本轮的绿灯**不能**用来推断 mDNS 或双电脑切换已通过——真机日志里那次的 `browse:*` 缺失仍说明当次没发生浏览（当时也确实不需要，旧址可用）。
6. **`RecordingViewModel.kt` 本轮零改动**：R5 允许但不需要，入口本来就已接好；问题在 UI 可见性。
7. **Compose 渲染无 JVM 覆盖**（见上）。
8. **回滚基线**：R5 自身可回滚（见二、可恢复差异材料第 1–2 条）；R4 版本需 PM 侧留存副本。

未改 Token / 保存地址 / 应用数据 / 权限 / 依赖 / 版本号 / Windows / 防火墙 / 发布路径；未清缓存、未安装手表 APK、未 push/merge/tag/release。

交付后 D **停止源码写入**。
