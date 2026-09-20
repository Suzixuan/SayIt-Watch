# 1C-PM-UI-01@R2 — 原版 Ready 对齐与真机验收

上一版 `1C-PM-UI-01@R1` 已把切换放进设置且真机搜索可达，但 Ready 标题/麦克风比冻结的 `0.2.0-dev.3-final` 明显偏上。用户要求不打乱原本 UI；R2 仅修此视觉偏差及搬迁后不准确的无目标文案。设计候选 `design/watch-ui/0.3.0-dev.1-candidate.2` 冻结了 R1 正式源码、资源与真机截图；未覆盖候选.1或原版。

## 实际变更与回退

- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` SHA-256 `94d1533269471dd9755543e603595a689aa9dec7524f551948e31bd8324fd891`：Ready 用圆屏相对位置，恢复 MIC READY 和麦克风的原版中心；保留中性连接状态与小号设置。设置菜单、选择器、录音点击及网络代码未改。
- `watch/app/src/main/res/values/strings.xml` SHA-256 `0fcb21a71aacf737ac5e790760fc590f6ce1021f2ca69b3a1319601637c03408`：无目标文案指向设置，移除搬迁后不再使用的冗余提示资源。
- `watch/app/src/test/java/com/sayit/watch/ui/RecordingReadyEntryR5Test.kt` SHA-256 `beae39433fdc5cdfc6f0ece6b72f5e1937ff3e04a1ab46eeabf46bc6e3d98a98`：仅把布局守护更新为原版居中坐标；`RecordingSettingsMenuR7Test.kt` 保持 R1 SHA-256 `e80b302bcaebdddec88d84def3f4c2c0c025bd46038ff018e1609bc4d3c91aa9`。
- 按近期修改时间，`watch/app/src` 仅上述三个文件在 R2 改动范围内；整个迁移仓库无 Git 提交基线，不声称全树洁净。回退产品文件用候选.2中的 `RecordingScreen.R1.kt`（`c733c1ce1241c274333cb55ce50fd503bb7ebdb99e2a885cc6c9e3d34d7be8af`）和 `strings.R1.xml`（`f493437ac384433b805294b588bad5e40667028b7a8d0722a12275f8244cd25e`），再将 R5 测试恢复成 R1 的布局断言；本轮未回退。

## 验证与边界

- 定向 R5/R6/R7 单测 `--rerun-tasks` 退出码 0；完整 `gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain` 退出码 0，21 suites / 198 tests / 0 failures / 0 errors / 0 skipped，lint 0 error / 36 warnings。Debug APK SHA-256 `9557f6c5811c8283e349cade7110d0e93cda4ae13f7024b3e27784515cbace03`。
- PM 用 `adb install -r` 保留数据装表，返回 Success；设备读回 `com.sayit.watch.debug` versionCode 5 / `0.3.0-dev.1`，更新于 00:32:01。真实 480×480 截图及 SHA 在 `docs/evidence/1C-PM-UI-01-R2/`：Ready 的标题/麦克风位置与原版视觉一致，连接状态及设置可见；设置菜单三项无截断。PM 点击设置→切换：原选择器打开，8 秒浏览后无其他候选，取消回 Ready，旧目标仍认证可用。日志 `browse:started` 00:32:41、`browse:stopped`/`browse:no-candidate`/`verdict:none` 00:32:49。
- **局部通过**：本次 UI 放置及单电脑旧目标保持已完成。未清应用数据、未改 Token、未录音。笔记本当前不可达，0 目标/两电脑发现与选择、换机录音到文本框尚未真机验收；项目阶段 12 不关闭。
