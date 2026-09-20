# 1C-D-04@R3 — R2 增量验收未通过的定向返修

状态：PM 2026-09-19 源码验收 NO-GO，原任务范围返修。上一有效版本为 `1C-D-04@R2`，交付说明在 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R2-D-RETURN.md`。R2 实际文件与 APK SHA-256 和 D 回传一致；`gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug` 的最终日志为 BUILD SUCCESSFUL，161 个单测。这些绿灯没有覆盖 ViewModel 的真实“切换电脑”入口或解析线程生命周期。本轮只修下述核心偏差，沿用 R2 的安全/发布/凭证边界，D 唯一写产品源码，PM 独立审核和真机验收。

## 必修 1：第一次发现另一台电脑却无法采用

`RecordingViewModel.requestSwitch()` 在 `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt:588-604` 得到一个新的已认证目标后立即调用 `onTargetPicked(found)`；后者在 622-627 行只接受 `_targets.value` 已有项。新目标此时仅经 `ResolverBridge.onSwitchBrowse()` 合入 `knownTargets`；`publishTargets()` 只在 `found == null` 分支第 612 行执行，所以新目标不在 `_targets.value`，被拒绝并关闭弹窗。这是任务包核心场景“旧电脑在线，点切换电脑，发现一台新电脑后无需输 IP 即可采用”的确定性失败。R2 新测试只调用 bridge 的 `onTargetChosen()`，绕过真实 ViewModel 检查。

要求：新增贯通实际 `requestSwitch()` → 收集 → 选择/采用 → 保存/Ready 的可执行行为测试；修正目标列表与成员校验的顺序，保留“只能采用本轮已认证目标”、两个新目标不静默选择以及旧目标在未确认前继续可用。不能仅调宽成员检查让未经认证地址可选。

## 必修 2：浏览结束后解析线程仍永久轮询

`AndroidNsdDiscovery.kt:84-86` 构造对象即启动单线程 executor；`resolveLoop()` 在 247-266 行以 25 ms 轮询；`stop()` 在 163-182 行只清队列、停 listener，不终止 worker；全文件没有 shutdown。离开 Ready、ViewModel `onCleared()` 后仍每秒约 40 次唤醒，且每次构造新的浏览器对象会留一条线程，违反原 1C“不常驻高频扫描/生命周期结束停止”的硬约束。

要求：把 worker 的启动/终止绑定到实际 browse 生命周期，取消/销毁能确定退出，无待办时不高频空轮询；保持同时最多一个 resolve 在飞、有界排队和 8 秒发现窗口。加能证明 stop 后不再轮询/无存活工作线程的行为检查或可复现的运行证据。不要为此重构整个发现架构。

## 必修 3：无当前目标时的切换状态与重复等待

`ResolverBridge.onSwitchBrowse()` 在 `DiscoveryCoordinator.kt:1078-1091` 对 `engine.verifiedTarget == null` 直接 `return only`，跳过“回到 Idle”转移；当浏览返回 0 或多个目标时，engine 仍可能停在 Automatic。`RecordingViewModel.requestSwitch()` 在 575-589 行对无目标先运行一次完整自动解析（可达 3 秒旧址 + 8 秒浏览），随后无条件再浏览 8 秒，不符合用户点击切换后的一次有界发现体验。

要求：无当前目标时，零个/多个目标都可结束搜索、关闭弹窗或重试，不留 Automatic 锁死后续 Ready/手动路径；显式切换只进行一次有界浏览，不先重复旧地址复查。补 0/1/2 认证目标、取消与再次进入的行为测试。

## 验证与交付

- 仅允许改 R2 范围内实际受影响的 `watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt`、`DiscoveryCoordinator.kt`、`ui/RecordingViewModel.kt` 和对应测试；若确有必要改其它路径，先给 PM 明确原因，不自行扩大。
- 先最小定向测试，再按变更影响运行 Watch 单测、lint、Debug build；报告命令退出码、测试数、改动文件新 SHA-256、APK 新 SHA-256，以及有用的回滚材料。不要安装到手表、改 Windows/Token/防火墙、清缓存、reset/clean/push/merge/tag/release。
- 本轮验收只判定源码和构建。真实自动发现根因及台式机↔笔记本切换仍待 PM 真机验证，不因这次返修自动标 VERIFIED。
- 回传固定 `变更文件`、`验证结果`、`待审问题` 三栏。说明 R2 与 R3 变化；交付后停止源码写入。
