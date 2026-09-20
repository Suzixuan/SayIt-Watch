# 1C-PM-UI-01@R1 — 将电脑切换入口收进设置

用户要求保留原本简洁的 Watch Ready UI，由 PM 决定入口放置。上一外包版本 `1C-D-04@R6` 已通过真机文案与单目标入口局部验收；本次是 PM 直接处理的兼容 UI 调整，不是发给 D 的新任务，也不改变 R6 的 mDNS/选择协议或阶段 12 的双电脑待验状态。

## 核心验收场景

1. 已验证一台电脑进入 Ready：主界面仍以 MIC READY、麦克风和中性连接状态为主，仅显示低调「设置」入口；不显示大号「切换电脑」按钮。点击设置后明确可找到「切换电脑」，点按发起已有一次有界浏览，旧目标在无候选/取消时保持。
2. 无已验证目标：Ready 的设置入口仍可达，菜单显示「搜索电脑」，点击后能打开现有选择器并启动浏览；手动地址仍可通过「连接设置」进入。
3. 设置菜单只打开/关闭时不得调用 `openConfig()`，因为现有实现会清空当前已验证目标。只有进入原配置页才调用它；菜单中切换仍走生产 `requestSwitch()` 和原选择器，不引入暗选或未认证目标。
4. 真实圆屏不重叠、不截断、触控区域不低于原 48 dp 规则；录音/上传、Token、手动地址、权限和网络行为不回退。定向测试、Watch 全量单测、lint、Debug build 后保留数据覆盖安装真机检查；双电脑链路仍需笔记本在线单独验收。

## 范围与回退

允许改 `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`、`watch/app/src/main/res/values/strings.xml` 和必要的 `watch/app/src/test/**`。设计冻结在 `design/watch-ui/0.3.0-dev.1-candidate.1`，其中有 R6 正式源码与资源的完整快照和截图；不覆盖更早的设计版本。禁止改 ViewModel、发现协议、Windows、Token、权限、存储、版本号和发布路径。软件版本保持 `0.3.0-dev.1`，任务编号与软件版本分开。

## PM 实施与验证（2026-09-20）

- 正式源码改动仅 `RecordingScreen.kt`（SHA-256 `c733c1ce1241c274333cb55ce50fd503bb7ebdb99e2a885cc6c9e3d34d7be8af`）和 `strings.xml`（`f493437ac384433b805294b588bad5e40667028b7a8d0722a12275f8244cd25e`）。适配 `RecordingReadyEntryR5Test.kt`（`b7b9122a452600d0e0b843c75e60cb007fc6cdb6a94f874c4ee08d26a7b01633`），新增 `RecordingSettingsMenuR7Test.kt`（`e80b302bcaebdddec88d84def3f4c2c0c025bd46038ff018e1609bc4d3c91aa9`）。按修改时间核查，`watch/app/src` 本轮仅这四个文件；无 Git 提交基线，因此不能用 `git diff` 证明整个历史工作树洁净。
- 定向 R7/R5/R6 单测 `--rerun-tasks` 退出码 0；全量 `gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain` 退出码 0，21 suites / 198 tests / 0 failures / 0 errors / 0 skipped，lint 0 error / 36 warnings。Debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `e58dbb994c1ed496f250394d4799f20665d22e86aeb699133339dfde0fbc811a`。
- 回退源：候选目录中的 `RecordingScreen.R6.kt`（SHA-256 `622ac2a6f7e2d99f6dcdddbabfdb5d9831707e5aba20bdba0621cd92f501e4c8`）和 `strings.R6.xml`（`a43866337ef832e6e508190c758211037bd86b1eebf452dc4486f3f10e1fef45`）。本轮未执行回退或覆盖候选；R5 测试适配及新增 R7 测试也需要随回退同步处理。
- 真机限制：`adb devices -l` 为空、`adb mdns services` 无端点，新 APK **未装手表**；预览不是 Compose 实拍。必须保留数据安装后核对 Ready 简洁排版、设置菜单、0/1 目标下可达的切换与当前目标保持，才能放行 UI。笔记本不在线，双电脑发现/选择/录音仍未验证。
