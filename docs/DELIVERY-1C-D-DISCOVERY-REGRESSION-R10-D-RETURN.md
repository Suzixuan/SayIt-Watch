# 1C-D-04@R10 — D 回传（变更文件 / 验证结果 / 待审问题）

- 任务包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R10.md`
- 唯一产品工作树：`C:/Users/suzix/Documents/ChatGPT/SayIt-Watch-sync`
- 分支：`codex/watch-connection-r7`（保留 R7/R8/R9 历史，未 amend、未 force）
- R10 基线（任务包指定的 R9 产品头）：`e33b5361c3517f35d75bf210bfc1077d4f730658`
- **最终产品提交（全量命令与 APK 均来自此 SHA）**：`91ad403c21c5e554edeb28fa205e982f74ac4a72`
- 交付状态：**工作树干净**；未 push、未 merge、未 tag、未 release。
- **明确声明：本轮未做任何真机验收；未重建、未修改、未替换 Windows 便携包；未发布任何正式 Release。** 未安装 APK、未改真实 Token/配置/防火墙/进程。

### 提交顺序（`f8da9a1` 之后）

| # | 提交 | 内容 |
|---|---|---|
| 1 | `f8da9a1b1aebb461905f8b8a44d7892b641c174d` | PM 的 R10 任务包（**不是 D 的改动**） |
| 2 | `91ad403c21c5e554edeb28fa205e982f74ac4a72` | `fix(watch): R10 accept the service-type spelling the NSD resolve callback really reports`（**最终产品头**） |
| 3 | 本回传文档 | `docs: R10 D return` |

---

## 1. 变更文件

`f8da9a1` 之后 D 改动 **4 个路径**（外加本回传文档），全部落在任务包 §3 的允许清单内。

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/net/Discovery.kt` | `DiscoveryPolicy.isOurServiceType()`：在原有大小写/首尾空白/尾点/`.local` 容忍之上，再容忍**恰好一个可选前导点**；更新 KDoc 记录真机形态与 R10 根因 | `ac87aea9cd1fcc2ee396072484928f53bf3a7e750b52e240236d361637d1f0b0` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryNsdTypeR10Test.kt` | **新增 5 条**真实生产链回归（见 §2.1） | `15fbc24c79e234d3fb28b4d88bc0c0bfc60984dbcd680c7e946c5f30258d6bc7` |
| `watch/app/src/test/java/com/sayit/watch/net/DiscoveryRegressionTest.kt` | 既有 “platform variants” 列表**补上真机形态**（接受侧）并补强拒绝侧（`..`、`.`, `._http._tcp`、`._sayit-watch._udp`、`._sayit-watch._tcp.example.`） | `ff84f9c5b8ad5183b201a836267ca439b514cb815e4452b36a3274f227e36f7f` |
| `watch/app/build.gradle.kts` | 仅版本号：`versionCode 6 → 7`，`versionName "0.3.0-dev.2" → "0.3.0-dev.3"` | `bcd99cd5ed6001f42a34680a915a873f9a507321dbf6a2adbf037a15203e7641` |

**`watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt` 未改动。** 任务包允许"仅当需要在 found 校验与 resolved 对象之间安全保留平台类型语义"时修改它；本轮不需要——`addResolved()` 已经把原始的 `serviceInfo.serviceType` 原样交给 `DiscoveryPolicy.classify()`，所以缺陷完全在规则一侧，修规则即可，适配层没有第二套判断。

### 1.1 根因（与 PM 真机日志逐条对应）

PM 在 Galaxy Watch 7（Android 16 / API 36）上看到的序列是
`saved-probe:rejected → browse:started → found:type-accepted → resolve:queued → resolve:started → resolve:succeeded → candidate:rejected-type → browse:no-candidate → verdict:none`。

这条日志本身就说明广播到达、Android 发现了、解析也成功了，失败只发生在"解析结果 → 候选校验"这一步。原因是 `NsdManager` 的两个回调**对点的处理不一致**：

| 回调 | 真实上报的 serviceType |
|---|---|
| `onServiceFound` | `_sayit-watch._tcp.`（尾点） |
| `onServiceResolved` | `._sayit-watch._tcp`（**一个前导点**） |

R2 起的 `isOurServiceType()` 先 `removeSuffix(".")` 再 `removeSuffix(".local")`，能容忍尾点却**不能容忍前导点**，于是 found 检查通过、resolve 成功、结果被 `classify()` 判为 `SERVICE_TYPE` 而丢弃。这就是"接收端完全正常、手表却报未发现电脑"的完整解释。

**为什么 227/227 没抓到：** 既有的 “platform variants” 列表里根本没有 `._sayit-watch._tcp` 这一形态——缺少的正是真机唯一会给出的那个字符串。本轮把该形态补进同一列表，并新增真实链路测试。

### 1.2 修法与 fail-closed 边界（可逐项核查）

规范化顺序：`trim()` → `lowercase()` → 去**至多一个**前导点 → 去一个尾点 → 去一个 `.local` → 与 `_sayit-watch._tcp` 精确比较。两个"去前缀"之后若仍以点开头、或已成空串，立即拒绝。

**现在接受（同一冻结类型的不同拼写）：**

`_sayit-watch._tcp.`、`_sayit-watch._tcp`、`_sayit-watch._tcp.local.`、`_sayit-watch._tcp.local`、`._sayit-watch._tcp`、`._sayit-watch._tcp.`、`._sayit-watch._tcp.local`、`._sayit-watch._tcp.local.`，以及以上任意形态的大小写变体（`._SAYIT-WATCH._TCP.`）与首尾空白变体（`" ._sayit-watch._tcp. "`、含制表/换行）。

**仍然拒绝（未放宽一毫米）：**

| 输入 | 原因 |
|---|---|
| `null`、`""`、`"   "`、`"."`、`".."` | 空值 / 只有点 |
| `.._sayit-watch._tcp`、`.._sayit-watch._tcp.local.` | 两个前导点：是另一个（畸形）名字，不是平台变体 |
| `._sayit-watch._tcp.example.`、`._sayit-watch._tcp.local.example` | 额外标签 / 其它域 |
| `._sayit-watch._tcp..` | 双尾点 |
| `._sayit-watch._udp`、`_sayit-watch._udp.local.` | 错误协议 |
| `._http._tcp`、`_other._tcp.`、`._sayit._tcp.`、`sayit-watch._tcp.`、`._sayit-watch.tcp`、`._sayit watch._tcp` | 相似子串 / 其它服务 / 非法分隔 |

同时**未改动**：实例名不参与判定（仍不拿 `SayIt` 顶替类型校验）；`classify()`/`candidate()` 的协议版本、RFC1918 主机、端口 `1..65535` 规则；Bearer 认证门槛；诊断仍只记录固定类别，不输出原始类型、IP、Token 或响应体。

---

## 2. 验证结果

命令均在最终产品头 `91ad403c21c5e554edeb28fa205e982f74ac4a72` 上、工作树干净时执行。

### 2.1 定向 R10（退出码 0）

```
gradlew.bat testDebugUnitTest --tests com.sayit.watch.net.DiscoveryNsdTypeR10Test \
  --tests com.sayit.watch.net.DiscoveryRegressionTest \
  --tests com.sayit.watch.net.DiscoveryPureTest \
  --tests com.sayit.watch.net.DiscoveryIntegrationTest --console=plain
```

新增 `DiscoveryNsdTypeR10Test` **5 条全过**，覆盖任务包 §2.B 的五项：

1. `the spelling the resolve callback reported is recognized` —— `isOurServiceType("._sayit-watch._tcp") == true`，并覆盖前导点与尾点/`.local`/大小写/空白的各种组合；R2 已容忍的形态回归验证不退化。
2. `only one leading dot is tolerated and everything else is still refused` —— 上表全部拒绝项被逐一断言（含双前导点、额外域、错误 TCP/UDP、空类型、相似子串）。
3. `a resolved service in that spelling passes classify and candidate` —— 真实 `DiscoveryPolicy.classify/candidate` 接受 `ResolvedService(serviceType = "._sayit-watch._tcp", host = RFC1918, port = 18099, protocol = "1")`；同对象的错误类型/协议/公网地址/非法端口仍分别落到 `SERVICE_TYPE` / `PROTOCOL` / `HOST` / `PORT`。
4. `the real coordinator authenticates the device-reported spelling into one target` —— 真实 `DiscoveryCoordinator` + 真实 `DiscoveryPolicy` 链路：该形态进入浏览 → 认证探针成功 → `verdict:one` → 唯一目标、唯一持久化写入，且轨迹中**不出现** `candidate:rejected-type`。
5. `a stale saved address browses and connects through the resolve spelling` —— 完整真机故事：旧地址 `saved-probe:rejected` → `browse:started` → 该真实解析形态 → `probe:authenticated` → `verdict:one` → 目标为浏览到的电脑。

测试调用的是生产 `DiscoveryPolicy` 与生产 `DiscoveryCoordinator`，没有复制任何测试专用的规范化逻辑。

**稳定性：** 上述四个 suite 连续 **3 次** `--rerun-tasks` 重跑，**3/3 全绿、退出码 0**（日志 `r10-focused1..4.log`）。

### 2.2 全量（退出码 0）

```
gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain
```

- 日志 `I:\Deepseek\r10-verify1.log`：**BUILD SUCCESSFUL in 2m 46s**，`53 actionable tasks: 53 executed`，**退出码 0**
- 单测**按 XML 独立重算**（`watch/app/build/test-results/testDebugUnitTest/TEST-*.xml`，25 个 suite）：
  **232 tests / 0 failures / 0 errors / 0 skipped**
- 计数变化：R9 的 227 → **232**（新增 R10 5 条；既有用例无删除、无降断言）
- lint：**0 error / 37 warning**（`lint-results-debug.xml` 统计，全部为既有类别）

### 2.3 Debug APK

| 项 | 值 |
|---|---|
| 路径 | `watch/app/build/outputs/apk/debug/app-debug.apk` |
| SHA-256 | `677e29156382cdec6ec4ae107e8e1c17fca694e93bd6a68b4dea412e1e7b5ad0` |
| 大小 | 20,679,396 字节 |
| `versionName` | **`0.3.0-dev.3`** |
| `versionCode` | **`7`** |
| 读回方式 | `aapt2 dump badging`：`package: name='com.sayit.watch.debug' versionCode='7' versionName='0.3.0-dev.3'` |

APK **未提交**（在 `.gitignore` 范围内），**未安装到手表**。

### 2.4 Windows 便携包：未重建、未改动

- 任务包 §2.C 要求复用 R9 包。本轮**没有运行打包脚本**，`client/scripts/package-watch-portable.ps1` 与 `client/src-tauri/**` 均在本次 diff 之外（`git diff f8da9a1..HEAD` 只含 4 个 Watch 路径）。
- 现存产物实测：`dist-portable/SayIt-Watch-0.3.0-dev.2-windows-x64-portable.zip`
  - SHA-256 **`f3487b7385c0fa8637332c5bccea2a1f5eb08318a80b8b0905736022093749ee`**（18,085,445 字节），与 R9 交付时**完全一致**
  - 包内 `BUILD-INFO.git_head` 仍为 R9 产品头 `e33b5361c3517f35d75bf210bfc1077d4f730658`——**这是刻意保留的**：PM 已用该包在同一现场证明"被 Android 发现并解析"，本轮只改手表侧规则，包不需要变。
- 未启动、未替换 PC 上任何正在运行的程序。

### 2.5 哈希汇总

| 产物 | SHA-256 |
|---|---|
| 最终产品头 | `91ad403c21c5e554edeb28fa205e982f74ac4a72` |
| Debug APK | `677e29156382cdec6ec4ae107e8e1c17fca694e93bd6a68b4dea412e1e7b5ad0` |
| 复用的 R9 便携 ZIP | `f3487b7385c0fa8637332c5bccea2a1f5eb08318a80b8b0905736022093749ee` |

---

## 3. 待审问题（未测场景、范围、风险）

### 3.1 未验证（不得当作通过）

1. **真机链路未验，这是本轮唯一的放行门槛，且只能由 PM 完成。** 本轮证据全部来自 JVM 单元/集成测试；**没有任何一条在手表或电脑上跑过**。任务包要求 PM 观察 `found:type-accepted → resolve:succeeded → candidate:accepted → probe:authenticated → verdict:one`，并确认不退出重开手表即可进入 Ready——D 未执行、未声称。
2. **APK 未安装。** `0.3.0-dev.3 / code 7` 只由 `aapt2 dump badging` 从构建产物读回，不是从设备读回。
3. **双电脑 A–H 仍未验**（与 R9 相同，未因本轮改变）。
4. **R9 便携包未重新做启动冒烟**（与 R9 相同；本轮未触碰该包）。
5. **PM 主机的 Rust 复验状态未知**：本轮未运行 `cargo test`（任务包未要求，且未触碰 `client/src-tauri`）。R9 记录的差异（D 本机 62/62 通过、PM 主机 FTK1011 阻断）在本轮**未被重新检验**，请仍以 PM 主机为准。

### 3.2 范围与实现偏差（请 PM 裁定）

6. **只改了规则一侧，没有动 `AndroidNsdDiscovery.kt`**（理由见 §1）。若 PM 认为适配层也应保留平台原始类型语义（例如为了日志或跨回调比对），请指示，D 再单独限域处理。
7. **接受集合是"拼写"而不是"服务集合"**：新增的只有"恰好一个前导点"这一族。若后续在别的 OEM 上观察到其它畸形形态（例如两个前导点），D **不建议**继续逐个放宽——那属于平台返回了不同名字，应单独取证后再定，而不是让类型过滤器越来越松。
8. **版本号只动了 `watch/app/build.gradle.kts` 的两行**（dev.3 / code 7），未改 `applicationId`、`minSdk`/`targetSdk`、签名或渠道。
9. **`DiscoveryRegressionTest` 的 variants 列表被扩充**（接受 +7 项、拒绝 +7 项）。这是补齐真机形态，不是放宽断言：原有条目一条未删。
10. **未触碰**：UI、设置/切换布局、录音/上传、Token、Windows receiver/mDNS、便携脚本、ASR、History、Paste、正式 Release、既有视觉冻结目录（`0.3.0-dev.2-candidate.1` 保持不变；本轮无显示变化，未新建候选）。
11. **回滚**：恢复 `e33b536` 即完全回到 R9 产品头；R10 只改上述 4 个路径，不需要删配置或重装。

---

## 4. 一句话范围

`f8da9a1`（PM 的 R10 包）→ `91ad403`（产品 1 + 测试 2 + 版本号 1，**最终产品头**）→ 本回传文档；
共 4 个路径，外加本回传文档，**无未提交修改**。Windows 便携包按任务包要求原样复用 R9 产物。

D 已停止源码写入，等待 PM 独立核对范围与测试，并进行保留数据安装与真机链路验收。
