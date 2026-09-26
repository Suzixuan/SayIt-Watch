# 1C-PM-R12-A@R1：前台恢复状态修复与单电脑真机验收

日期：2026-09-26。基线分支 `codex/watch-connection-r7`，起始提交 `b9b8836`。本轮只处理 R12-A 真实验收暴露的状态投影缺陷；没有重写连接状态机、mDNS、认证或上传协议，没有改 Windows 包。

## 已有成果核对

- R10 单电脑 mDNS 自动发现与认证链已经真机通过，不重复修改。
- R11 搜索中/搜索结束后返回的状态修复已经自动化和用户真机确认，不重复修改。
- dev.5/code 9 的 `screenOrientation=portrait`、构建、保留数据安装和冷启动已经完成；实物转腕仍待观察。

## R12-A 失败复现与原因

- Windows 已切回验签通过的 R9 统一便携包：ZIP SHA-256 `F3487B7385C0FA8637332C5BCCEA2A1F5EB08318A80B8B0905736022093749EE`，包内 EXE SHA-256 `A56442CF489B765355933FA7930448997F0B661C1D2ED071570496C240CB6B0B`；监听 `0.0.0.0:18099`，health=`ok`，未认证 discovery=`401`。
- dev.5/code 9 在电脑恢复后日志已经连续出现 `probe:authenticated` / `verdict:one`，但前台实屏仍停在“等待认证结果…”。失败截图：`docs/evidence/R12-20260926/r12-a-recovered.png`，SHA-256 `EE619D9E64210CBD9DD0B99CD79391AA8F819C591D2B654E4AC38A429AA80903`。
- 原因：`onForeground()` 会先撤销历史在线投影；保留目标的认证探针成功后，`probeVerifiedTarget()` 旧实现只清除 `connecting`，没有重新发布 `connected` / `transportAvailable`。因此目标和认证日志正确，但 Ready UI 与录音门槛仍停在离线投影。
- 新增真实 `RecordingViewModel → ConnectionTaskOwner → ResolverBridge → DiscoveryCoordinator → UI` 回归，修复前独立失败于 `ConnectionLifecycleR9Test.kt:370`。

## 最小修复

- 产品提交：`9ba58b79a4c8c917b81535d7240c3441c5c79143`。
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`：当前目标认证成功后，通过既有 `setVerifiedDestination(current)` 重新发布同一个已认证目标；不新增状态源、不持久化额外数据。
- `watch/app/src/test/java/com/sayit/watch/net/ConnectionLifecycleR9Test.kt`：新增前台返回后必须恢复 Ready 已连接投影和文案的回归。
- `watch/app/build.gradle.kts`：候选升为 `0.3.0-dev.6` / code 10，避免覆盖 dev.5 证据。

## 验证

- 修复前定向回归：1 test / 1 failed，确认能捕获真机缺陷。
- 修复后定向回归：1/1 通过。
- `gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain`：退出码 0；25 suites / 236 tests / 0 failures / 0 errors / 0 skipped；lint 0 errors / 39 warnings；Debug build 成功。当前 39 条 warning 中，改动路径只命中既有 `OldTargetApi`，没有本轮新增连接逻辑 warning。
- Debug APK：`watch/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `69D944256548304307D84813A2C7AE45BF5294C9DD12EFA150775FE4F1CFB7FE`。
- 以 `adb install --no-streaming -r` 保留数据覆盖安装；设备读回 dev.6/code 10，冷启动成功，约 2234 ms；没有清 Token、地址或应用数据。

## 真机结果

- 初始 Ready 显示“已连接电脑”。截图 `docs/evidence/R12-20260926/r12-a-dev6-baseline.png`，SHA-256 `C7AAE753D03FD34561FFBF0A33A94149365BFC1C539E6D34E8CBC25CFA6B9DC7`。
- 13:06:25 关闭 R9 后，18099 不再监听，手表显示“未找到电脑，请在设置中搜索或手动填地址”，未假装保持连接。
- 13:06:44 原样启动 R9；监听和 discovery 401 恢复，13:07:26 前台 UI 自动回到“已连接电脑”，没有手输 IP、重新选择电脑或重启 App。
- 切换页读回当前目标 `192.168.12.142:18099`，截图 `docs/evidence/R12-20260926/r12-current-target-awake.png`，SHA-256 `DDFB047604935A67166A55CFCE66CC186CAAC21BD45E5115C1340624533C7DED`；点击返回后首页仍为“已连接电脑”，同时覆盖 R11 单电脑回归。
- 13:11 的确认录音进入现有 Windows 处理链：一次 `Entered processing`（audioSec 5.96、runId 3）、一次 final、一次 fallback 展示；该次没有第二个 processing/final/Paste，也没有发到其他接收端的证据。它证明本机恢复后的单次上传和单机无业务重复，不替代双电脑路由验收。
- 测试侧曾把屏幕超时从 60 秒临时调到 600 秒；收尾已恢复 60 秒，`stay_on_while_plugged_in` 恢复为 15。产品代码和系统旋转设置未改。

## 仍未完成

- R12-B：台式机与笔记本同时在线的发现、选择、取消、回切。
- R12-C：A/B 两台电脑间的真实录音路由。
- R12-D：本轮只证明一条单机录音没有业务重复，双电脑与重试边界仍待验。
- R12-E：竖屏清单和安装已验证，真实转腕观察仍待用户/设备现场完成。
- 没有 push、merge、tag、Release；R9 Windows 包未重建。
