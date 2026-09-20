# 1C-D-04@R5 — 真机发现的「切换电脑」入口不可达（已派发）

上一有效版本 `1C-D-04@R4`，回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R4-D-RETURN.md`。PM 曾按 R4 两项定向修复接受源码/构建，但 2026-09-19 在 Galaxy Watch 真机覆盖安装 R4 后发现，用户最常见的切换路径仍不可达，故当前整体验收 **NO-GO**。本包是同一自动发现/切换任务的限域返修，已于 2026-09-19 发送到 D 原对话并核对消息入列；当前有效版本为 R5。

## 需求—实际行为—证据

- 用户目标：台式机和笔记本之间切换不反复手填 IP；当前电脑仍在线时也能主动找另一台，旧电脑在用户确认前继续可用。
- 真实输入：手表保留原 Token/地址，R4 APK 用 `adb install -r` 覆盖安装成功、数据未清；正确的桌面 Debug SayIt 接收端监听 `0.0.0.0:18099`，无 Bearer 的 discovery 返回预期 HTTP 401。手表重启后进入 Ready，屏幕显示电脑可用。
- 真实处理：手表日志只有 `probe:authenticated`、`saved-probe:accepted`、`verdict:one`，没有这次 Ready 的 `browse:*`。也就是说这是**已保存地址复查成功**，不是一次 mDNS 新发现。`runDiscovery()` 在旧址成功时立即返回，`DiscoveryOutcome.authenticated` 只有当前电脑。
- 真实输出：`RecordingScreen.kt` ReadyScreen 只在 `ui.targets.size > 1` 时渲染「切换电脑」。在上述正常单电脑 Ready 场景它被隐藏，用户无从触发已实现的 `requestSwitch()`；发现失败时界面也只提示填写地址，缺少直接重试入口。这与任务包「不手填 IP 切换」的核心链路冲突。PM 此前只测了 ViewModel 入口，没有核对它在真实 UI 中是否可点，属于验收漏检。
- 本机还有独立环境障碍：最初一个旧 `SayIt-0.1.9.exe` 后台进程占用 18099 并返回 404；PM 确认它无活动连接后只停止该进程，未卸载/删除，随后启动桌面 Debug 版并见到预期 401。不要把最初的 404 归因于手表 mDNS 代码。笔记本当前不在线，双电脑端到端切换仍不能声称已验证。

## 必修与验收场景

1. Ready 中 **0/1/≥2 台**已认证电脑时，都能看见并点击明确的「搜索电脑／切换电脑」入口；尤其一台旧电脑可录音时不能隐藏。无电脑时点击可发起一次有界重新发现，不强制打开手动 IP 设置。保留单独的手动设置入口和原配置，不清数据、不重填 Token。
2. 点击该入口必须走生产 `RecordingViewModel.requestSwitch()`：只做一次最多 8 秒浏览，不先用 3 秒复查旧址；一台新目标按既有已认证选择路径采用，两台新目标绝不暗选，0 台可重试；旧目标在确认新目标前继续可用。不得将 `ui.targets.size > 1` 当作入口可见性的前提。
3. Ready 的状态文案不得把 `saved-probe:accepted` 说成「已自动发现电脑」。可用中性的「已连接电脑／电脑已验证」；不要据此宣称 mDNS 已通过。确保 480×480 圆屏能完整显示操作、触控区域符合既有至少 48 dp 规则，不遮挡录音与设置。
4. 测试不能只调用 `requestSwitch()`：至少覆盖 0/1/≥2 个目标时实际 Ready UI 入口的可达性，以及入口点击后调用真实 ViewModel、旧目标保持、首次新目标采用、失败后重试。若 JVM 无法渲染 Compose，可提取极小的纯决策测试并加源级接线守护；明确未覆盖的渲染部分由 PM 真机复验。

## 范围、禁止与交付

- 仅允许改 `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`、必要时 `ui/RecordingViewModel.kt`、`watch/app/src/main/res/values/strings.xml` 及对应 `watch/app/src/test/**`。R4 的 ResolverBridge、NSD worker、Windows receiver、录音上传与安全边界均保持；确需其它路径先说明、停手等待 PM。
- 不改 Token、保存地址、应用数据、权限、依赖、版本号、Windows、防火墙、发布路径；不清缓存、安装手表 APK、push/merge/tag/release。D 为唯一产品源码写入者，PM 保留真机复验。
- 先最小行为测试，再 Watch 单测、lint、Debug build。回传 `变更文件／验证结果／待审问题`，列实际路径与 SHA-256、命令退出码、测试数、APK SHA-256、与 R4 的可恢复差异材料；交付后停止源码写入。源码绿灯不等于 mDNS 或两电脑真机已验收。
