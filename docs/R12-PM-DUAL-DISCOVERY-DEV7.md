# 1C-PM-R12-B@R1：双电脑候选即时显示与真机切换验收

日期：2026-09-26。分支 `codex/watch-connection-r7`，产品提交 `2daf71594778dade9f49dc1146131287d426ec4b`。本轮只修复显式“切换电脑”搜索中已认证候选迟迟不显示的问题；Windows R9 包、mDNS 协议、Bearer 认证、录音和上传路由均未修改。

## 失败边界与原因

- 两台 Windows 接收端同时在线：当前电脑 `192.168.12.142:18099`，第二台 `192.168.12.153:18099`。第二台 `/api/health` 返回 200，未认证 `/api/watch/discovery` 返回 401，证明接收服务和鉴权入口存在。
- 修复前真机日志同一轮已经出现两次 `found:type-accepted → resolve:succeeded → candidate:accepted → probe:authenticated`，最终 `verdict:ambiguous`。因此故障不在 Windows 广播、Watch NSD 解析、候选过滤或 Token。
- `browseAndAuthenticate()` 会在候选探测成功时只写入内部集合，`currentCollected`、Resolver 目标列表和 UI 都要等完整 8 秒窗口结束才一次性更新。用户在窗口内再次点“重新搜索”会取消旧轮并重开 8 秒，界面持续只显示旧电脑，看起来就是第二台始终搜不到。

## 最小修复

- `DiscoveryCoordinator` 在每个端点通过既有 Bearer 探测后发布累计的 authenticated-only 进度，同时继续剩余窗口寻找更多电脑；普通自动发现仍只在完整窗口后应用原有 0/1/多台判定。
- `ResolverBridge` 和 `RecordingViewModel` 用现有搜索代际门控增量结果；被取消或被新一轮替代的搜索不能刷新新界面。候选仍不会自动采用或提前持久化。
- 搜索中已经验证的行立即可见，但在完整收集窗口结束前不可点选，避免关闭选择器后留下隐藏 NSD 浏览；窗口结束后按原流程再次探测并显式采用。
- 新增真实 ViewModel→Owner→Resolver→Coordinator 回归，要求第二台在搜索仍运行时已经进入列表、当前电脑保持选中。候选版本为 `0.3.0-dev.7` / code 11。

## 自动验证与制品

- 定向 `DiscoverySwitchFlowR3Test`：退出码 0。
- `gradlew.bat testDebugUnitTest lintDebug assembleDebug`：退出码 0；25 suites / 237 tests / 0 failures / 0 errors / 0 skipped；lint 0 errors / 39 warnings；Debug build 成功。
- Debug APK：`watch/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `6A2DB354AA137D695FFEFA3B817DC2D3A742CF720B9EE5E74C8505D01A14D907`。
- `adb install -r` 保留数据安装成功；设备读回 versionName `0.3.0-dev.7`、versionCode 11。未清 Token、地址或应用数据。

## 双电脑真机结果

- dev.7 搜索开始后日志在约 1.7 秒内完成两台真实认证；PC 侧采样在 2.228 秒确认两次 `probe:authenticated`。5.432 秒截图时完整 8 秒窗口仍显示“正在搜索…”，列表已经同时显示当前 `.142` 和可选 `.153`。证据：`docs/evidence/R12-20260926/r12-dev7-streaming.png`，SHA-256 `819EDA426A3DEFCFF1FA65D21D330132C76B81C84913CED91D6D9A31A6DC2378`；对应 UI XML SHA-256 `3213305503D138A4A0F1BAF90954A8620EC0D976493F778FEAF6B4C444A628F8`。
- 窗口结束后显式点选 `.153`，再次鉴权成功并回到 Ready“已连接电脑”。重新打开选择器后页首和“当前使用”均为 `.153`，`.142` 为可选电脑；证据截图 SHA-256 `53F646A98876D477733D23C99DD5D2C0554CB6478AD7A749C634F84CE5957A52`，XML SHA-256 `C8AF2C0065F65E744FE728801B3D70045917DD1F6EC0677F9D80BB8C58D98EAA`。
- 随后显式切回 `.142` 并恢复 Ready；重新打开选择器读回 `.142` 为当前电脑。收尾证据截图 SHA-256 `F92228D806967A8FD99097F01F51A4DC8D1B371AD662630C88BBB88812F21D7F`，XML SHA-256 `28E87CA7E99428E3231621483EFFCCCBE11B1445B53B55EECC52C40C3AC0F6AD`。
- ADB 注入触摸不会像真实手指一样延长亮屏，第一次点选被设备 Doze 生命周期正确取消。为完成自动化点选，测试时临时模拟充电保持亮屏；结束后已执行 battery reset，并把 `screen_off_timeout` 恢复为 60000、`stay_on_while_plugged_in` 恢复为 15。该测试条件不计作产品缺陷或成功证据。

## 旋转状态跟进

- 用户在本轮验收后报告界面再次旋转。现场 `dumpsys` 证明不是应用传感器逻辑：设备全局为 `USER_ROTATION_LOCKED / ROTATION_90`，系统界面和 SayIt 共用同一 90°显示；SayIt Activity 仍正确请求 `SCREEN_ORIENTATION_PORTRAIT`。WindowManager 历史把该锁定变更归因到本轮使用的 `UiAutomationConnection#restoreRotationStateLocked`，属于验收工具留下的设备状态。
- PM 执行 `wm user-rotation lock 0` 精确恢复全局 0°锁定；复验为 `mRotation=0`、`mCurrentRotation=ROTATION_0`、`USER_ROTATION_LOCKED / ROTATION_0`，SayIt 为前台且继续请求 Portrait。恢复截图 `docs/evidence/R12-20260926/r12-rotation-restored.png`，SHA-256 `C7AAE753D03FD34561FFBF0A33A94149365BFC1C539E6D34E8CBC25CFA6B9DC7`。
- 本项没有新增产品源码修改。后续真机验收不得把 `uiautomator dump` 当作无副作用读取；如必须使用，前后都要核对并恢复 `wm user-rotation`。真实手腕旋转是否保持 0°仍由用户现场观察确认。

## 状态边界

- R12-B 的“双电脑发现、即时显示、显式选择、回切”已通过；R11 的取消/返回门槛保持既有通过证据。
- R12-C 仍未完成：尚未分别向 A、B 各录一条并以两端接收/处理记录证明选中目标的音频路由。
- R12-D 仍未完成：双电脑环境和重试边界的业务重复检查未完成。
- R12-E 仍未完成：真实手腕旋转观察待用户完成。
- 阶段 12 保持开放；无 push、merge、tag、Release，Windows R9 包未重建。
