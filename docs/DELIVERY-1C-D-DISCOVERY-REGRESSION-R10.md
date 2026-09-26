# Delivery 1C — 自动发现真实 NSD 类型返修 `1C-D-04@R10`

状态：PM 真机验收 R9 后 **NO-GO，返修待发送**（2026-09-22）  
上一任务版本：`1C-D-04@R9`  
产品基线：`e33b5361c3517f35d75bf210bfc1077d4f730658`  
开发分支：`codex/watch-connection-r7`

## 1. 本轮结论与已证实根因

R9 的连接任务串行、晚启动重试和统一 Windows 便携包静态门槛已通过；本轮不得重做这些部分。

PM 在 Galaxy Watch 7（Android 16 / API 36）保留数据安装 R9，并启动经哈希核对的 R9 便携 EXE。电脑端监听 `0.0.0.0:18099`，从 WLAN 地址访问 `/api/watch/discovery` 得到预期 401。真机日志连续出现：

```text
saved-probe:rejected
browse:started
found:type-accepted
resolve:queued
resolve:started
resolve:succeeded
candidate:rejected-type
browse:no-candidate
verdict:none
```

这证明 Windows 广播已到达手表、Android 已发现并解析服务，失败发生在解析结果进入候选校验时。

Android 官方 `NsdService` 当前实现对两类回调使用不同历史格式：发现结果的类型末尾带点；解析成功结果的类型前面带点。也就是本设备的真实链路为：

- `onServiceFound`: `_sayit-watch._tcp.`
- `onServiceResolved`: `._sayit-watch._tcp`

当前 `DiscoveryPolicy.isOurServiceType()` 仅容忍大小写、尾点和 `.local`，不容忍官方解析格式的单个前导点，所以 `AndroidNsdDiscovery.addResolved()` 把真实服务映射成 `ResolvedService` 后立刻报 `candidate:rejected-type`。现有测试的“platform variants”列表也漏掉了 `._sayit-watch._tcp`，因此 227/227 没有覆盖本次真机形态。

## 2. 必修内容

### A. 接受官方解析回调形态，仍然 fail-closed

- 让服务类型规范化同时接受 `_sayit-watch._tcp.`、`_sayit-watch._tcp`、`_sayit-watch._tcp.local.`、`_sayit-watch._tcp.local`、`._sayit-watch._tcp`，以及官方形态的大小写/首尾空白等价形式。
- 只允许**一个可选前导点**作为 Android 解析回调的历史格式；空值、双前导点、额外标签、错误协议、相似子串和其他服务类型继续拒绝。
- 不得用实例名 `SayIt` 替代服务类型校验，也不得因本次修复放宽 RFC1918、端口、协议版本或 Bearer 认证门槛。
- 日志继续只记录固定类别，不输出原始类型、IP、Token 或响应体。

### B. 补真实生产链回归

至少覆盖：

1. `DiscoveryPolicy.isOurServiceType("._sayit-watch._tcp") == true`；
2. `ResolvedService(serviceType = "._sayit-watch._tcp", host = RFC1918, port = 18099, protocol = "1")` 被 `classify/candidate` 接受；
3. 上述解析结果进入真实 `DiscoveryCoordinator` 后执行一次认证探针，并在唯一认证成功时产出唯一目标；
4. 双前导点、额外域、错误 TCP/UDP 及空类型仍被拒绝；
5. 旧地址失败 → 浏览 → 该真实解析形态 → 认证成功的完整回归，避免只测一个字符串辅助函数。

测试名称或文件可调整，但必须调用生产 `DiscoveryPolicy`、`DiscoveryCoordinator`，不能复制一套测试专用规范化逻辑。

### C. 可识别版本

- Watch 版本改为 `0.3.0-dev.3`，`versionCode = 7`，便于 PM 读回确认本轮 APK。
- Windows R9 便携包不重建、不改协议、不改启动器；PM 已在同一现场证明它被 Android 发现并解析。回传时明确复用 R9 ZIP 哈希 `F3487B7385C0FA8637332C5BCCEA2A1F5EB08318A80B8B0905736022093749EE`。

## 3. 允许路径

产品代码只允许：

- `watch/app/src/main/java/com/sayit/watch/net/Discovery.kt`
- `watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt`（仅当需要在已通过 found 校验与 resolved 对象之间安全保留平台类型语义；若纯规范化即可，不要改）
- `watch/app/build.gradle.kts`（仅版本号）

测试只允许：

- `watch/app/src/test/java/com/sayit/watch/net/DiscoveryPureTest.kt`
- `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRegressionTest.kt`
- 必要时新增一个同目录的 R10 定向测试文件。

文档只允许本任务回传文件。不得修改 UI、设置/切换布局、录音/上传、Token、Windows receiver/mDNS、便携脚本、ASR、History、Paste、正式 Release 或既有视觉冻结目录。

## 4. 验收与回传

D 必须返回：

- 最终产品提交 SHA、工作树状态和逐文件变更清单；
- 定向 R10 测试以及 `testDebugUnitTest --rerun-tasks lintDebug assembleDebug` 的命令、退出码和计数；
- Debug APK 路径、SHA-256、`versionName` / `versionCode`；
- R9 Windows ZIP 未改变及其复用哈希；
- 明确声明未做真机验收、未改 Windows 包、未发布。

PM 独立验收：保留数据覆盖安装并读回 dev.3/code 7；保持当前 R9 Windows 进程和端口，观察 `found:type-accepted → resolve:succeeded → candidate:accepted → probe:authenticated → verdict:one`，确认无需退出重开手表即可进入 Ready。之后才继续双电脑 A–H。自动测试或构建通过不能替代该真机链路。
