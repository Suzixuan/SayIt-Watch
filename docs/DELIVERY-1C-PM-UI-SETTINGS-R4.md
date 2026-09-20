# 1C-PM-UI-01@R4 — Ready 状态文字居中与 README 交付

用户要求将「已连接电脑」水平居中、完成后将项目上传 GitHub，并将 README 改为以手表为主角。父版 `@R3` 的菜单及录音表盘不动。改动前已冻结 `design/watch-ui/0.3.0-dev.1-candidate.4/`：R3 真机图、设置菜单、完整前态源码/测试、4 px 左移预览、渲染脚本和 SHA-256 清单。

## 实际改动

- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` SHA-256 `a382b9e6a54e8f0380a075c9e62268f588701f1ebf2bb67d11f6024a14dbc167`：仅对资源 `discovery_saved_neutral` 的 Ready 文案应用 `x=-2dp`；其他发现状态仍使用原居中坐标，录音、切换、Token、网络代码未动。
- `watch/app/src/test/java/com/sayit/watch/ui/RecordingReadyEntryR5Test.kt` SHA-256 `817415a800aa5f7a7b53c6b3e36c3c737b8d6a7188572589079299065d053bf4`：守护只有中性的已连接文案取得该光学偏移。
- 首页 `README.md` 改为手表链路、真实截图与明确的未验边界；`README.zh-CN.md` 保留旧入口但指向首页；`CHANGELOG.md` 新增未发布 Watch 开发快照。原两个 README 完整前态仅保存在本机 `docs/archive/`，不进入 GitHub。
- README 新图是 `docs/images/readme/watch/ready-connected-r4.png`（真机 R4）、`settings-r3.png`（真机 R3）和原有已验收的 `recording.png`；Logo 复用冻结手表图标 `design/watch-icon/0.1.1-candidate.1/icon-preview.svg`，复制成 `watch-logo.svg`。不使用上游桌面首页图或凭证截图。

## 验证与边界

- `gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain` 退出码 0，21 suites / 198 tests / 0 failures；lint 0 error / 36 warnings。Debug APK SHA-256 `180c4812fcf58edad0a5af9eaa4b714434e4f36f9a0682e472b33bde0311edc0`，`adb install -r` 返回 Success，未清应用数据。
- 真机 Ready 图见 `docs/evidence/1C-PM-UI-01-R4/`：相较父版只有状态行 925 个像素变化，范围外变化 0；文字像素重心 x≈244→x=240，和候选预览逐像素相同。**R4 局部 UI 验收通过**。仍未重跑录音或完成两电脑端到端切换；项目阶段 12 不关闭。
- GitHub 安全发布与远端读回单独记录在 `HANDOFF.md`；此处不把源码构建当作正式 APK/Release，也不推送到上游 `crosswk/SayIt`。
