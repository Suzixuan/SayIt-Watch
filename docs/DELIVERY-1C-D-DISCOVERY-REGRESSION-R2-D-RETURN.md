# Delivery 1C 1C-D-04@R2 — D 交付回传

任务包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R2.md`（R1 未派发）
角色：本轮唯一产品源码写入者
状态：源码交付完成，**自动发现真机未被本轮验证**；根因仍然未证实

---

## 一、定位证据与修正假设（写代码之前先报 PM 的部分）

### 已排除的假设

| 假设 | 为什么排除 |
|---|---|
| 认证/Bearer 有问题 | 手动填地址可连，说明同一个 `GET /api/watch/discovery` 探针能过；自动与手动**用的是同一个 `DiscoveryProbe`**（本轮补了一条测试直接钉住这点） |
| Windows 侧没广播 | PM 已记录真实 `Announce`，且 `mdns.rs` 在 Repair 3 已修正为「monitor 先于 register」 |
| 网络不可达 | 历史证据里 Watch ping PC、TCP 连 18099 都通 |

### 本轮新找到的、可直接归因到代码的缺陷（已修复）

1. **`resolveService` 并发丢失（最高嫌疑，已修复）**
   `NsdManager` 的旧式 `resolveService` 是**单飞**的：上一个 resolve 未回调时再发一次会走
   `onResolveFailed(FAILURE_ALREADY_ACTIVE)`。原实现从 `onServiceFound` 里**每次都直接调用**，
   且在 `onResolveFailed` 里**只打一行日志、不重排、不重试** —— 于是：
   - 只要 found 回调不止一个（真实 mDNS 网络几乎必然如此），**除第一个以外的实例全部静默丢失**；
   - 台式机与笔记本表现**完全一致**；
   - 手动路径完全不经过 NSD，所以**手动照样能连**。
   这与用户「两台电脑、手动可连、自动失败」的描述吻合。
   现在改为 `SerialResolveQueue`（纯 Kotlin、可单测）：**任意时刻只有一个 resolve 在飞**、
   同一实例不重复入队、容量上限 8 且溢出丢最旧、`FAILURE_ALREADY_ACTIVE` 最多重试 3 次。

2. **服务类型严格相等（已放宽）**
   `NsdManager` **不会**规范化 `.local` 域与大小写。原实现用 `type == "_sayit-watch._tcp."` 全等比较，
   不匹配就**静默 `return`**（连一行日志都没有）。现在用
   `DiscoveryPolicy.isOurServiceType`：去尾点、去 `.local`、忽略大小写后比较；
   `_http._tcp.` / `_sayit-watch._udp.` / 空串仍然拒绝（原有安全性质不变）。

3. **整条链路零可观测性（已修复）**
   `onServiceFound`、resolve 成功/失败、候选被拒、探针被拒**原本全都没有日志**，
   这正是 Repair 3 那条「回调不可观测导致结论被误读」的同一个问题。
   现在有 27 个**冻结类别**，覆盖七段：旧地址探针 → 浏览启动/失败 → found →
   resolve 成功/失败/排队/去重/重试 → 候选拒绝（按原因分类）→ 认证探针结果类别 → 最终兜底。
   **每个类别都不含 IP、主机名、SSID、Token、原始 exception message、HTTP 请求或音频**，
   并有测试断言词表里不存在 `\d{1,3}\.\d{1,3}` 形状与 `.local`。

### 仍然只是假设、本轮无法证实的部分

- `serviceInfo.host?.hostAddress` 只取一个地址；若平台给的是主机名或 IPv6/link-local，会落到
  `DestinationValidator` 被拒。本轮已把它变成**可观测的固定类别**（`candidate:rejected-host`），
  但**是否真的发生，只能在真机 logcat 上判定**，不能由代码推断。
- 两台电脑的差异是否还有别的因素（DHCP、多网卡、路由器 mDNS 隔离）本轮无证据。

---

## 二、变更文件

所有路径相对 `%USERPROFILE%\Documents\ChatGPT\Saylt`。Git 仍无提交基线，以下为**实际文件哈希**（非 git diff）。

### 产品源码（7 个，含 1 个新增）

| 文件 | 变更 | SHA-256 | bytes |
|---|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt` | 串行 resolve 队列接入；类型匹配放宽；7 段固定类别诊断 | `aa1317f372f6c70d4ab3f503a17ad460a5c3fe3921e050e0940da3e050c79391` | 16588 |
| `watch/app/src/main/java/com/sayit/watch/net/SerialResolveQueue.kt` | **新增**：有界单飞 resolve 队列（4 条语义） | `458c3f970e4d49568bee197285434feb5efe0c35e78199ffe8f8294a43154b40` | 4877 |
| `watch/app/src/main/java/com/sayit/watch/net/Discovery.kt` | 新增 27 个冻结诊断类别、`CategoryLog`、`CandidateRejection`、`DiscoveryOutcome`、`isOurServiceType`/`classify` | `3c7be6b4bcf8ebedfbe23300451d320dbcaf5ee3d6afe15657e0314d242acde5` | 24270 |
| `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt` | `awaitOutcome`/`ensureResolved` 返回真实 Job；`DiscoveryOutcome` 全量结果；已认证电脑记住表；显式切换浏览（不查旧地址优先、不自行落盘）；诊断接线 | `77e33cfa17c15a2bb4955ec18d73b21f71778eda9d395a82a2487e3a2408170d` | 53868 |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` | `requestSwitch()`／`onTargetPicked()`／`dismissSwitchPicker()`；已认证目标列表；诊断共享 | `99617e11f9356230eff2f39633033443809a50c1e7961ece0d9b8cdb241a0e5c` | 37626 |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` | Ready 页「切换电脑」入口 + 已认证电脑选择弹窗（显示局域网地址仅供人工区分） | `8498de6186afe2d5c0d023e78779282cb6809158bf2fbf7e2e543336a0f593cf` | 26572 |
| `watch/app/src/main/res/values/strings.xml` | 切换相关 7 条中文字符串 | `475da0fbd1e90eda325eabc2e8f895ce6f9cf4b44b770277355510ad89a82e0a` | 3058 |

> 注：产品源码实际为 7 个文件（含一个新增文件），上表逐行列出。

### 测试源码（5 个）

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRegressionTest.kt` | **新增**：17 条 R2 回归测试 | `240c2b5f270242cae1479a1182f6dc5c731c892408e76fd305b04184630c5e97` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRepairTest.kt` | 适配 `ResolverBridge` 新的 `DiscoveryOutcome` 入参 | `a8946ec253ebd037dcf6b2c8802fabe33f2030a4d595a74290d635e9aae4bdad` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryIntegrationTest.kt` | 同上 | `3156fd71c6f423204526eb0c56b152b437ff170e80dcbfea09e00fe2598abbe1` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt` | 类型容错后修正 2 条断言（`_sayit-watch._tcp` 无尾点、工厂签名） | `ee6afb5c1fe02fd9b6c74f90e3a4aef150f1c34955a15a5684db8d87349f3661` |

### 未改动（明确声明）

`mdns.rs`、`watch_receiver/mod.rs`、`watch_receiver/server.rs`、`MainActivity.kt`、
`settings/**`、`build.gradle.kts`、`AndroidManifest.xml`、`Cargo.toml`、`Cargo.lock`、
依赖、权限、版本号、`HANDOFF.md`、`PROJECT_PROGRESS.md`、任务包本身 —— **全部零改动**。

### 可执行回滚办法

无 Git 提交基线，回滚依靠文件级替换：

1. 本轮**新增**文件只有 `watch/app/src/main/java/com/sayit/watch/net/SerialResolveQueue.kt`
   与 `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRegressionTest.kt`，直接删除即可。
2. 被修改的 9 个文件需按字节还原。当前不存在 D 侧改动前副本，**回滚基线不可用（诚实声明）**；
   若要回滚，可从 PM 侧留存的上一版交付物或 Repair 2 §8 清单中的哈希对应文件恢复。
3. 恢复后运行 `gradlew.bat testDebugUnitTest lintDebug assembleDebug`，确认回到 `144 tests / 0 failures`。

---

## 三、验证结果

命令均在 `%USERPROFILE%\Documents\ChatGPT\Saylt\watch`，`JAVA_HOME=jdk-17.0.20.101-hotspot`、
`ANDROID_HOME=%LOCAL_BUILD_ROOT%\sdk`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain` | **0** | BUILD SUCCESSFUL；`161 tests / 0 failures / 0 errors / 0 skipped` |
| `lintDebug` | 0（同一命令内） | 0 error / 38 warning（无新增 error） |
| `assembleDebug` | 0（同一命令内） | `watch/app/build/outputs/apk/debug/app-debug.apk` |

**Debug APK**：`watch/app/build/outputs/apk/debug/app-debug.apk`
SHA-256 `e771d808db9f0ad1d520364c78e38d684eb1529185499edea5efb2e8876a12c5`，20,593,448 bytes。

完整日志：`%LOCAL_BUILD_ROOT%\r2-verify.log`（第一次）、`%LOCAL_BUILD_ROOT%\r2-verify2.log`（最终源码状态）。
两次均为 `161 tests / 0 failures / 0 errors / 0 skipped`，APK 哈希一致。

### 测试覆盖（对任务包「验证与回传」逐条对上）

| 任务包要求 | 覆盖测试 |
|---|---|
| 旧地址失效 → 发现另一台电脑 | `a stale saved address is forgotten and the current computer is adopted` |
| 旧地址可用 → 保持当前 | `a live saved address is kept and an explicit switch bypasses it`（前半段） |
| 显式切换时绕过旧地址优先 | 同上（后半段，断言 `discovery.startCount == 1`、旧地址未被重新查询） |
| 两个已认证目标必须显式选择 | `two authenticated computers are never switched between silently` |
| 候选因类型/地址过滤 | `rejected candidates are never probed and are reported by category`、`a rejection is reported as a fixed category for each reason` |
| 浏览失败/超时 | `a browse that resolves nothing reports the browse stage, not a probe` |
| 取消后迟到回调 | `a cancelled browse still stops the browser exactly once and persists nothing`、`a stale generation neither publishes nor persists after a stop` |
| 同一段音频仍不会重发 | `an invalidated target is re-discovered but the failed endpoint is not reconsidered`（叠加原有 `RecordingEntryGuardTest.a failed upload drops the target and never re-sends the wav`） |
| 生产 adapter 可接入的 fake | 全部走 `ResolverBridge.forCoordinator(coordinator)` 真实工厂；probe/browser 为 fake，判定逻辑为产品代码 |
| 诊断词表无地址 | `the diagnostic vocabulary is a frozen set of address free categories` |
| resolve 单飞/去重/容量/重试 | 4 条 `SerialResolveQueue` 语义测试 |

### 既有回归

`DiscoveryPureTest` 的 3 s / 8 s 真实时钟预算测试、Repair 2 的 23 条、`RecordingEntryGuardTest`
的 7 条源级守护、`WatchUiStateMachineTest` 16 条**全部保持通过**。
没有任何测试被删除或跳过。

---

## 四、待审问题

### A. 需要 PM 判定或真机确认

1. **真机链路未验证。** 本轮没有设备、没有装 APK。请用 Debug APK 复测：
   日志标签 `SayItDiscovery`（与 `SayItNsd`）现在会逐段打印固定类别。判定方法：
   - 若看到 `browse:started` 但**没有** `found:*` → 平台没回调，属收取阶段；
   - 若看到 `found:other-type` → 类型仍不匹配（请把该实例的 type 形态反馈，不要贴 IP）；
   - 若看到 `resolve:failed` / `resolve:retried-busy` → 单飞冲突仍在；
   - 若看到 `candidate:rejected-host` → 平台给的不是可用的 RFC1918 字面量（这一条**最需要真机证据**）；
   - 若看到 `probe:rejected` → 认证探针阶段，与手动路径不一致时再排查。
2. **根因仍未证实。** 本轮修的是「证据支持的最小修复 + 可观测性」。单飞丢失与类型严格比较是
   **代码级已证实缺陷**，但它们是否就是用户这次失败的唯一/主要原因，**必须由真机日志判定**。
   请勿把本交付读成「已修好自动发现」。
3. **显式切换的交互口径。** 用户点「切换电脑」后，若恰好只有**一台新的**已认证电脑，本轮实现是
   **直接采用它**（不再要求二次点选）。理由：这本就是用户的显式动作，且旧的「显式选择入口」已存在。
   两个**新**目标同时出现时仍然必须点选、绝不静默。若 PM 要求任何切换都必须落到选择界面点一下，
   请指示，改动局限在 `RecordingViewModel.requestSwitch()` 一处。
4. **选择界面显示地址。** 已按任务包允许范围在弹窗里显示 `IP:port` 用于人工区分两台电脑；
   日志、广播、回传中均不出现地址。若 PM 认为界面也不能显示，请指示改为序号标签。
5. **浏览器单飞窗口与 8 s 的关系。** resolve 队列容量 8、重试 3 次、重试间隔 60 ms 是**我选的**边界值，
   任务包未冻结。若 PM 有偏好数值请给出。

### B. 诚实声明的限制

6. **`AndroidNsdDiscovery` 本身没有 JVM 单测。** 它需要真实 `Context`/`NsdManager`，本环境构造不了
   （这与既有 `RecordingEntryGuardTest` 注释里记录的限制一致）。可测的部分已被抽到
   `SerialResolveQueue`（4 条）与 `DiscoveryPolicy`（纯函数），但**适配器内部的重试循环、会话过期、
   `host` 三态**只有源码级守护 + 真机日志，没有行为测试。
7. **`addResolved` 的迟到回调路径没有直接单测。** 它只接受 `NsdServiceInfo`（Android 类），
   无法在 JVM 测试里构造。该保证目前由会话/世代检查的源码结构与协调器层测试间接覆盖。
8. **回滚基线不可用**（见一、可执行回滚办法第 2 条）。
9. **lint 仍有 38 条 warning**（预先存在，非本轮引入；本轮未新增 error，也未改动产生它们的代码）。

### C. 边界保持声明

未改动 Windows receiver、Provider/ASR/History/Paste、依赖、权限、版本号、发布代码；
未安装 APK、未 `git reset/clean/checkout`、未 push/merge/tag/release；
未改防火墙或网络类别；未启动/停止任何桌面程序；未触碰手表。

交付后 D **停止源码写入**。
