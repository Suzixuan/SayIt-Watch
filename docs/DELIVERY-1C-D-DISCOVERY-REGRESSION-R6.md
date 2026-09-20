# 1C-D-04@R6 — 已保存地址复查被误报为自动发现（已派发）

上一版本 `1C-D-04@R5`，回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R5-D-RETURN.md`。R5 真机入口与一次浏览行为局部通过，但 R5 必修 3 的结果文案仍失败；本包只修同一任务的误报，不改发现协议。R5 的 0/1/多电脑入口、旧目标保持、单次浏览成果必须保留。2026-09-19 已通过 D 原对话发送并核对消息入列，当前有效版本为 R6。

## PM 独立验收证据与契约核查

- R5 五个交付源码/测试文件及 Debug APK 的 SHA-256 与 D 回传一致；PM 独立运行 `RecordingReadyEntryR5Test`（8 条）和 `DiscoverySwitchFlowR3Test`（20 条），28/28、退出码 0。D 文档将前者记作 7 条是非阻断计数笔误。
- 保留数据 `adb install -r` 覆盖安装 R5 后，真机单电脑 Ready 显示可点的「切换电脑」与独立「设置／手动地址」，圆屏上均可见；点击后进入选择电脑，日志出现 `browse:started`，约 8 秒后 `browse:stopped` / `browse:no-candidate`，旧目标仍在。笔记本不在线，因此没有第二台可选择，也未证明跨设备 mDNS 成功。
- 同一次应用重启的日志明确是 `probe:authenticated` / `saved-probe:accepted` / `verdict:one`，没有启动时 `browse:started`，但 Ready 截图却写「已自动发现电脑」。这是用户可见的错误事实，违反 R5 必修 3，不是仅凭单测推测。
- 根因边界：`DiscoveryCoordinator` 的保存地址结果原本是 `ExistingAddress`，但 `ResolverBridge.routeAutomatic()` 把任何已验证目标的 `lastPublishedState` 设成 `Discovered`，`RecordingViewModel.applyResolverVerdict()` 又无条件把目标成功写入 `DiscoveryState.Discovered`；手动成功/用户选择也会写 `Discovered`。R5 的 `readyStatusTextRes(ExistingAddress, true)` 测试只传入一个真实 UI 在此路径拿不到的状态，无法证明所需链路。当前状态契约已丢失成功来源，不能靠 UI 的状态分支声称“新发现”。

## 必修（最小范围）

1. Ready 对任何已认证可用目标统一使用中性「已连接电脑」或同义措辞，**不得**仅凭 `DiscoveryState.Discovered` 显示「已自动发现电脑」。不要在此返修中扩大到重构 ResolverBridge/网络来源追踪。搜索中、无目标、失败的提示保持准确；录音、手动设置、切换入口与尺寸不回退。
2. 测试覆盖实际生产路径：从保存地址认证成功经真实 `RecordingViewModel.onReadyEntered()` / ResolverBridge 到 UI 文案映射；至少显式断言 ViewModel 在当前实现会发布 `Discovered`，但 Ready 仍不声称自动发现。手动目标也不应误称自动发现。原 `ExistingAddress` 纯函数测试可保留，但不得再将它当作 saved-probe→UI 的唯一证明。源级接线守护及 0/1/多目标入口测试仍有效。
3. 只允许修改 `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` 及必要的 `watch/app/src/test/**`；若单纯把已验证状态文案改为中性即可，无需改 `strings.xml`。如测试需要最小 seam，可在改动前向 PM 说明；不得改 `RecordingViewModel.kt`、`DiscoveryCoordinator.kt`、NSD、Windows、Token、权限、配置、版本、持久化或发布路径。不要安装手表 APK，PM 保留真机复验。
4. 先定向测试，再 Watch 全部单测、lint、Debug build；回传变更路径及 SHA-256、命令退出码与测试数、APK SHA-256、与 R5 可恢复差异、待审风险。交付后停止源码写入。R6 源码绿灯不等于两电脑 mDNS 端到端验收。
