# 1C-PM-UI-01@R3 — 圆屏设置菜单实施与真机核对

上一版本 `@R2` 已恢复用户确认的 Ready 表盘。用户针对 R2 大矩形设置弹窗要求改进 UI，明确确认 `design/watch-ui/0.3.0-dev.1-candidate.3/01-settings-menu.png` 后，PM 限域实现；该目录保留了 R2 源码、资源、测试和真实截图完整快照与哈希。候选在冻结时为 `candidate`；本文件记录其后独立的运行实施与验收，不改写冻结目录。

## 范围与回退

- `RecordingScreen.kt` SHA-256 `c68a2fd644af0950429a4b401fb39e13ecd13a86ebe6199938cfa2047e514add`：只替换 `ReadySettingsMenu` 为全屏圆表盘布局，添加菜单专用图标与 52dp 两行操作；保留 Ready 布局、`onSwitch → requestSwitch()` 和 `onConnectionSettings → openConfig()` 接线。
- `strings.xml` SHA-256 `d4565a0a5b5e648c7b848849e7f2aa437f3d5ea9c17e33d1b482ec83f5d9bd0f`：仅新增三条菜单标题/辅助说明。
- `RecordingSettingsMenuR7Test.kt` SHA-256 `aebf72449cdb9347bc5f0be72da92aef41756ee1c5f3672368d2e9b6e2d081db` 和 `RecordingReadyEntryR5Test.kt` SHA-256 `295d45335e24abc2ed3b431d13e04489f962bb75f035ad5a7acf022d45b5836f`：适配旧 PillAction 源码守护为新 52dp 行动区，保留原连接路径守护。
- R2 产品源码、资源、R7 测试完整快照见候选.3；R5 测试的本次前态为 `docs/DELIVERY-1C-PM-UI-SETTINGS-R2.md` 中哈希 `beae39433fdc5cdfc6f0ece6b72f5e1937ff3e04a1ab46eeabf46bc6e3d98a98`，变化仅为上述两项源码断言。迁移仓库无 Git 提交基线，不能以 `git diff` 声称整个工作树洁净；此次没有重置、清理或提交。

## 验证与界限

- 初次定向 R5/R7 运行时，R5 旧源码断言要求 `PillAction`，11 条中 1 条失败；按新布局修正断言后定向测试退出码 0。最终完整 `gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain` 退出码 0，21 suites / 198 tests / 0 failures / 0 errors / 0 skipped，lint 0 error / 36 warnings；APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `55338b04e65ceb2f6ba49f67509f904d9730b9e762dcb12289c082a74795388e`。
- Galaxy Watch 7 上 `adb install -r` 返回 `Success`，未清数据；读回 `com.sayit.watch.debug` versionCode 5、versionName `0.3.0-dev.1`、更新时间 00:56:03。480×480 实际菜单与用户批准的预览在层级、位置、图标和文案上相符；关闭 X 返回 Ready。真实 Ready 截图 SHA-256 `fae27dfdf54a9548ba85eee8a2f97a6fa8eabfd77695dd2f91363b5e07236c5c`，与候选.3 冻结的 R2 Ready **逐字节一致**。
- 菜单顶部入口进入原选择器、显示当前已认证电脑和搜索进度；按系统返回可回到 Ready。底部入口进入原连接设置，未更改 Token/IP；诊断截图因包含部分遮蔽 Token 而删除，不作公开证据。完整截图索引与哈希见 `docs/evidence/1C-PM-UI-01-R3/`。
- **R3 设置视觉及两个入口的真机局部验收通过。** 笔记本尚未在线，不证明两台电脑发现/选择或换机后录音到文本框；阶段 12 保持未完成。无 Windows 端、发现协议、权限、设置存储或上传逻辑修改。没有 Release 包或正式发布。
