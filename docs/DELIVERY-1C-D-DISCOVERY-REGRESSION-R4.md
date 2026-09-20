# 1C-D-04@R4 — PM 架构核查后的限域返修包（已派发）

上一版本：`1C-D-04@R3`；D 回传：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R3-D-RETURN.md`。R3 的 APK/关键源码哈希与回传一致；PM 独立重跑 `DiscoverySwitchFlowR3Test` 14/14、退出码 0，但源码核心验收 **NO-GO**。真机未连接，端到端自动发现/切换仍未验收。用户已确认发送；PM 于 2026-09-19 在右侧本地 D `Saylt V2` 原对话点击“发送消息”，核对 `1C-D-04@R4` 消息已进入对话、D 显示运行中。本版现为唯一有效返修要求。

## PM 对重复返修的契约核查

核心契约仍是：仅认证成功的候选能被选；用户显式切换时，旧目标在确认前保持；没有旧目标、同时认证两台时，不得按发现顺序隐式选任何一台；取消/离开 Ready 后不得留存或累积解析 worker。R2→R3 的首次切换列表顺序和重复等待已有行为测试覆盖；以下两处是 R3 的新阻断，不是要求扩功能。问题根源是将“搜索结束/恢复 Idle”与“用户已经选择目标”混成一个状态转移，以及将 worker 句柄提前清空并把有超时的 `join` 称为保证退出。请只围绕这两个契约收口，不做第四轮架构重写。

### 必修 1：两个候选时内部已暗选第一台

`DiscoveryCoordinator.kt` 的 `ResolverBridge.handleSwitchBrowse()` 在无当前目标、两个已认证候选时用 `engine.verifiedTarget ?: only ?: collected.firstOrNull()` 取到第一台，然后调用 `stateAdoptedByUser(keep)`。ViewModel 此刻 `_canRecord=false`，所以 R3 的测试只检查当下的 `currentDestination()==null` 会绿；但 `RecordingViewModel.onReadyEntered()` 在 resolver 不接受自动解析时执行 `resolver.verifiedTarget?.let { setVerifiedDestination(it) }`，重新进入 Ready 即可能把第一台变为可录音/上传目标，完全绕过用户选择。要求：无旧目标且非唯一可自动采用结果时，内部 verified target 仍为 null，结束搜索后 Idle、选择面板可选两台；返回 Ready、取消面板、再次搜索都不得隐式选第一台或持久化。旧目标在线时保持旧目标，新候选唯一时沿实际 `requestSwitch()`→已认证列表→采用→持久化路径运行。补生产 ViewModel 入口行为测试：两候选浏览后调用真实 `onReadyEntered()`、断言目标/可录音/落盘仍为空（不能仅断言浏览刚结束），并覆盖取消后再进入。

### 必修 2：`stop()` 的线程退出保证不成立

`BrowseWorker.kt` 中 `stop()` 在 join 之前 `thread=null`，只等待 `joinTimeoutMs=2000`；若 `process` 无法在两秒内响应 interrupt，`isStopped()` 会因空句柄错误返回 true，下一次 `start()` 还能起第二条线程。R3 测试只模拟可中断的 `sleep`，没覆盖这个边界。要求：取消/关闭后停止接收、发送和回调；保留未退出线程的真实句柄，不能谎报已停止，不能在旧线程存活时启动第二条；它最终退出时才允许新会话。保持 UI 停止路径有界，不能为等待系统平台回调而无界卡住。补不响应 interrupt 的有限期假 `process`，核查 stop 超时后句柄状态、不能叠线程、迟到结果不落地、最终退出后的可恢复性。若平台调用本身无法给出硬退出保证，报告真实上限，不再宣称 `stop()` 返回即证明线程已死。

## 允许范围与交付

- 仅允许修改 `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt`、`BrowseWorker.kt`，必要时 `AndroidNsdDiscovery.kt`、`ui/RecordingViewModel.kt`，以及对应 `watch/app/src/test/**`。R3 未获预先批准新增 `BrowseWorker.kt`，PM 本版仅为收敛该已存在的 worker 明确纳入；这不追认 R3 的范围违规。再需别的路径先说明，停手等待 PM。
- 不改 Windows、权限、依赖、Token、发布配置、录音与上传协议、前端、网络安全设置；不清数据、不装设备、不 push/merge/tag/release。Debug-only 和既有手动兜底不变。
- 先跑新增最小用例，再跑 Watch 单测、lint、Debug build；列命令退出码/用例数量、变更路径和 SHA-256、APK 哈希、对 R3 的可恢复差异材料及待审风险。不能用测试数量代替真实场景结论。交付后停止产品源码写入，PM 再独立复审；真机台式机↔笔记本自动切换单列未验证。
