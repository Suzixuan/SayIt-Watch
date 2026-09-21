# 1C-D-04@R7 — D 回传（变更文件 / 验证结果 / 待审问题）

- 任务包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R7.md`
- 唯一产品工作树：`C:/Users/suzix/Documents/ChatGPT/SayIt-Watch-sync`
- 分支：`codex/watch-connection-r7`
- 交付基线（PM 文档提交）：`d32821c1250c66e586c3cd394d45ee57fa8a9a44`
- 本轮回传时的分支头：`codex/watch-connection-r7` 的当前提示提交（即本回传文档所在的文档提交；本文件不自我引用哈希）
- 交付状态：**工作树干净**（`git status --porcelain` 为空）；未 push、未 merge、未 tag、未 release。
- 未测场景与设备项全部在第 3 栏声明；本文件不把未测项写成通过。

### 提交顺序

| 提交 | 内容 |
|---|---|
| `d32821c1250c66e586c3cd394d45ee57fa8a9a44` | 任务包派发基线（PM 文档，非 D 提交） |
| `1716022b44e58f56b0eebeece9f752bd2760a0d9` | `feat(watch): R7 connection recovery, explicit switching and unified portable package` |
| `3e036ee58bc5d7fa6d3f2677b93129a0ddba907d` | `docs(design): replace the duplicate R7 preview with the not-found state` |
| 文档提交（分支头） | `docs: R7 D return (changed files / verification / open questions)`（无自我引用哈希） |


---

## 1. 变更文件

三个 D 提交，均在 `d32821c` 之后（`1716022`、`3e036ee`、`76dc21f`，见上文提交顺序）。
下表列出除本回传文档以外的**全部 31 个变更路径**；本回传文档自身为第 32 个路径。

### 1.1 Watch 产品源码

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `watch/app/src/main/java/com/sayit/watch/net/ConnectionTaskOwner.kt` | **新增**：唯一前台任务所有者（见 §1.5） | `0404d3b438f674763d3cdb316f6e756b6f045af9c48748d917cd6a2b0f5a0fa7` |
| `watch/app/src/main/java/com/sayit/watch/net/DiscoveryCoordinator.kt` | `ResolverBridge.handleSwitchBrowse()` 不再自动采用；点选前独立重新认证（`executePickProbe`）；新增 `pick()`/`PickResult`/`pickerCandidates`/`currentTarget`；`ResolverEngine.releaseAutomaticRun()` | `53f3fd004e0fecd2a2d62c6602d7c9d8ed0dfd9e3c7596b8e681ef99eb6fadbd` |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt` | 接入任务所有者；`onForeground()`/`onBackground()`；`openConfig()` 不再丢目标；`applySettings()` 仅在“真的改了”时才失效；切换页条目投影 | `688776f1ee8876df129ebfcb93fc078a392772edec1b15933b65850c227f8b28` |
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` | 切换页显示真实当前 `IP:端口`、精简标识、删长说明；状态文案映射新增“正在连接/未连接”分支 | `c972f8c8dd3f173e51e67dc38fec3a3481e427258c6de0349d4c6d538a332d45` |
| `watch/app/src/main/java/com/sayit/watch/MainActivity.kt` | 生命周期接线：`onStart→onForeground()`、`onStop→onBackground()` | `9257b1f6811b5672cfdb561463236b081885b4380801938a2eb67b1e93260dd5` |
| `watch/app/src/main/res/values/strings.xml` | 新增 `switch_current_section`/`discovery_current_missing`/`action_back`；`switch_dialog_hint` 缩短；`switch_other_computer`→「可选电脑」；`discovery_searching`/`discovery_none_yet`→「正在连接…」；**删除无生产调用点的 `discovery_found`** | `e5c178426abc118b901c86fc398508f026f4e2f5397da62b7188269d4d320c07` |
| `watch/app/build.gradle.kts` | 版本 `0.3.0-dev.2` / versionCode 6 | `b9afe21dc048f9971341c46028d4ab1d8a7deb85fea22c150f6a79f9c9edafff` |

### 1.2 Watch 测试

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `watch/app/src/test/java/com/sayit/watch/net/ConnectionRecoveryR7Test.kt` | **新增 12 条**生产链回归：真实 `RecordingViewModel`→`ResolverBridge`→`DiscoveryCoordinator`→fake discovery/probe→UI 投影 | `80cfe5796354960621ea4a13ba266c626da644c4ac591d6c4163c5742d557b82` |
| `.../net/DiscoverySwitchFlowR3Test.kt` | 按 R7 契约改写“显式切换必须点选/取消保留/二次点击重启” | `3435b565b0a5998b9b08d7b595665553f5d864a0cdc9ba360206522d7a1d3a4b` |
| `.../net/DiscoveryRegressionTest.kt` | `onSwitchBrowse()` 现在返回候选列表并断言当前目标保留 | `e7e4810a1ee5ca788f28fedf6abdb9629ad1fb3f3f1f7c3e4edc949d2704d0b7` |
| `.../ui/RecordingReadyEntryR5Test.kt` | 状态映射新签名 + 声明 R7 新资源 + 断言长说明与 `discovery_found` 已删除 | `c31f8b867e41d528496f2743b49ef05e9a191316875aff253aa21b98e0e1b5c7` |
| `.../ui/RecordingReadySavedProbeWordingR6Test.kt` | 适配新签名；失败态不再声称“已连接” | `c604f7779f6ab0277f974a1e912256071e39e80e66100a4ad43e822e0c546f8c` |
| `.../ui/RecordingEntryGuardTest.kt` | 入口守卫改为“委托给任务所有者 + 未改动表单不得拆链” | `6e03e231a4b39e8e0b8202d53446465ab7f42b0d00308a07f6582630439d3705` |

### 1.3 Windows 接收端

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `client/src-tauri/src/watch_receiver/mod.rs` | `start()` 改为**在调用线程完成绑定**并返回类型化 `ReceiverStartError`（`MissingConfig` / `BindFailed`），两类各有简短、可行动、无敏感值的中文提示与固定类别；新增 4 条测试 | `b4a96a57fc9eac9a8b25ecdf78ee2abf49c0deb175374981b0812181d1bef8d1` |
| `client/src-tauri/src/main.rs` | 启动失败时改为**可见原生提示**（`MessageBoxW`，独立线程，不阻塞 `.setup()`），日志只写固定类别 | `f1079c5094a82b8afa27daf2f7237ef24f7d010ce729f4ba171845a692085a80` |

### 1.4 打包、设计候选与忽略规则

| 文件 | 变更 | SHA-256 |
|---|---|---|
| `client/scripts/package-watch-portable.ps1` | **新增**：统一便携包可复现构建脚本（见 §2.4） | `4ab2539f11500469cb9f51b95312985103f2f2c5fad9f3eaad2f86471fb0bf28` |
| `.gitignore` | 放行该脚本；忽略 `dist-portable/` 产物 | `49352f4bc86e5f74d729ffa51696fdaf301a0d9e5047f39b2d78145e630ddd2e` |
| `design/watch-ui/0.3.0-dev.2-candidate.1/` | **新增**视觉候选（6 张 480×480 预览、`RecordingScreen.R7.kt`、`strings.R7.xml`、`ConnectionRecoveryR7Test.kt`、`render.py`、`parent-ready.png`、`README.md`、`SHA256SUMS`）；父候选 `.1-candidate.4` 未改动 | 见该目录 `SHA256SUMS` |

### 1.5 §2A 的机制（复现根因 → 修复）

**复现的根因。** `RecordingScreen` 只在 `ui.screen` **变化**时问一次搜索（`LaunchedEffect(ui.screen)`），
所以首轮失败之后屏幕上没有任何调度者；用户必须先开电脑就必须重启应用。R7 用一个所有者接管全部搜索：

- **单任务**：新意图取消在飞的那一轮（其结果由 transport 自身的 generation 门丢弃），并立刻启动替代轮。
  连点“重新搜索”因此是最新意图生效，既不会被静默拒绝，也不会叠加。
- **前台恢复**：前台 + 有有效 Token + 无可用电脑时，每轮失败后等 **5 s** 再搜一轮，**无上限**持续，
  直到连上或被替代。后开电脑会被其中一轮发现。
- **闲置有界复查**：已连接且空闲时，每 **5 s** 做一次有界认证探针（在轮与轮之间，不在轮内），
  失败即清除可用状态并转入恢复。
- **前台限制**：`MainActivity` 的 `onStart`/`onStop` 驱动，退后台即取消并停止全部调度，不后台轮询。
- **不抢占传输**：录音/上传开始时暂停调度；本轮结束后恢复。
- 任务轮运行在**自己的调度器**上（不是 `viewModelScope` 的 `Dispatchers.Main`，也不是测试的虚拟时钟），
  因此多秒等待既不阻塞 UI 线程，也不会在虚拟时钟下卡死。

---

## 2. 验证结果

### 2.1 Watch 全量（退出码 0）

命令（`watch/` 下，`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`、`ANDROID_HOME=I:\Deepseek\sdk`）：

```
gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug --console=plain
```

- 日志 `I:\Deepseek\r7-verify2.log`：`BUILD SUCCESSFUL in 2m 45s`，退出码 **0**
- 单测：**210 tests / 0 failures / 0 errors / 0 skipped**（`--rerun-tasks`，按 XML 汇总独立重算）
- lint：**0 error** / 37 warning（全部为依赖版本、未使用资源、SDK 目标等既有类别，无新增代码类告警）
- 新增计数：`ConnectionRecoveryR7Test` = 12；R6 时的 195 → **210**

定向复现证据（本轮先复现、后修复）：修复前 `ConnectionRecoveryR7Test` 里“先开手表后开电脑”的用例确实
失败（首轮失败后不再有任何一轮搜索），根因是 `ConnectionTaskOwner` 曾把 `delay` 放在调用者的
dispatcher 上，导致循环停摆；改为自有调度器 + 代际守卫后该用例与其余 11 条一起转绿。

### 2.2 Windows / 前端

| 命令 | 退出码 | 结果 |
|---|---|---|
| `cargo test watch_receiver` | 101 | `60 passed; 1 failed`（唯一失败见第 3 栏 §3.1） |
| `cargo test`（全量） | 101 | `182 passed; 1 failed; 4 ignored`，失败项同上、同一个既有 live-multicast 用例 |
| `npm test -- --run`（client） | 0 | **31 files / 370 passed** |
| `npm run build`（client） | 0 | 由打包脚本的 `beforeBuildCommand` 执行，产出 `client/dist` |
| `powershell -File client/scripts/package-watch-portable.ps1` | 0 | 见 §2.4 |

`client/src-tauri/src/watch_receiver/mdns.rs` 本轮**未改动**（`git status` 对该路径为空，PM 记录的
`AA87FE64…` 仍成立）。

### 2.3 统一便携包

- 命令：`powershell -ExecutionPolicy Bypass -File client/scripts/package-watch-portable.ps1`
- 日志 `I:\Deepseek\r7-package3.log`，退出码 **0**
- 产物：`dist-portable/SayIt-Watch-0.3.0-dev.2-windows-x64-portable.zip`
  - ZIP SHA-256 `86a37bf63f1bdf8cec542cc82de5278454f97bfa43c5de060e1ce47d8a4294f5`
  - 字节数 `18,084,966`；包内 29 个文件
  - `SayIt.exe` SHA-256 `f12d28c90a8da62bb03cfe53127186b20ab3f87295f1e404a7c3d889f334ab92`（38,419,968 字节）
- 解压后独立核对（另一个空目录 `I:\Deepseek\r7-smoke2`）：
  - 包内 `SHA256SUMS` 的 **28 条全部重新计算一致，0 mismatch**
  - 包内 `BUILD-INFO.txt` 记录基线 `git_head=d32821c1…`（即打包脚本运行时的分支头）
- 自包含证据（**关键**）：
  - `frontend=embedded`：脚本在打包前用 `tauri build --debug --no-bundle` 构建，并**在 EXE 内逐字符
    校验前端资源名**（本轮命中 **10** 个 `assets/*` 资源名）；包内不含 `node_modules`、`package.json`、
    Vite 配置或任何 dev-server 启动脚本（独立枚举确认）。
  - 运行时 DLL 已随包：`transcribe.dll`、13 个 `ggml*`（含 CPU 后端模块）、MSVC 运行库
    `msvcp140*`、`vcruntime140*`、`concrt140`、`vcomp140`、`vccorlib140` 等，共 23 个 DLL。
  - 包内只有 `resources/test_en.wav`、`resources/test_zh.wav` 两个既有测试夹具；**无**配置、Token、模型、
    用户记录或音频。
- **一处必须记录的自我纠错**：最初的脚本用 `cargo build` 构建，Tauri 在 dev profile 下走 `devUrl`
  路径、**完全不嵌入前端**，EXE 里找不到任何前端资源。脚本现已改为 `tauri build --debug --no-bundle`，
  并把“前端确实在 EXE 内”做成**构建后硬校验**（找不到资源即失败）。第二次尝试即被该断言拦下。

### 2.4 交付产物哈希汇总

| 产物 | SHA-256 | 大小 |
|---|---|---|
| Debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` | `94833ae084eb29845b5554acd1e79d922f59381d4c490bc78fdda1d0f141e717` | 20,663,012 |
| 便携 ZIP `dist-portable/SayIt-Watch-0.3.0-dev.2-windows-x64-portable.zip` | `86a37bf63f1bdf8cec542cc82de5278454f97bfa43c5de060e1ce47d8a4294f5` | 18,084,966 |
| 包内 `SayIt.exe` | `f12d28c90a8da62bb03cfe53127186b20ab3f87295f1e404a7c3d889f334ab92` | 38,419,968 |
| 分支头提交 | 分支 `codex/watch-connection-r7` 的当前 tip（本回传文档提交） | — |

三个二进制产物都**未提交**（在 `.gitignore` 覆盖范围内）。日志：`I:\Deepseek\r7-verify2.log`、
`r7-package3.log`、`r7-rust-full.log`、`r7-vitest.log`。

---

## 3. 待审问题（未测场景、范围偏差、风险）

### 3.1 未通过 / 未验证（不得当作通过）

1. **Rust live-multicast 用例失败（既有、环境相关）**：
   `watch_receiver::mdns::tests::live_daemon_announces_or_reports_a_daemon_error` 在 5 s 有界窗口内
   “既没观察到注册 Announce 也没观察到 daemon 错误”，单独重跑 **稳定失败**；`mdns.rs` 本轮零改动，
   其余 182 条 Rust 用例通过。D 判为**本机网络环境相关**（当前 Wi-Fi 已 Up，但该 live 用例需要真实接口
   在窗口内发出/收回报文），**不是 R7 引入的回归**，但 D 未证明 PM 主机同样失败，请以 PM 复跑为准。
   若要把它变成确定性门禁，需要单独的诊断切片，本轮不擅自改 `mdns.rs`。
2. **统一便携包的独立启动冒烟未做**：本机 **18099 端口正被用户正在运行的
   `C:\Users\suzix\OneDrive\Desktop\SayIt-Watch-Debug-20260917\sayit.exe`（PID 44500）占用**；
   任务包明确禁止停止用户现用程序，因此 **D 没有**在这个会话里从解压目录直接启动新 EXE 做闭环冒烟。
   已替代为**零侵入证据**：前端资源确实内嵌于 EXE、运行库齐全、ZIP 内 `SHA256SUMS` 全部自校验通过、
   无 dev-server 依赖。**“解压到任意空目录直接双击即可启动”仍需 PM 在不再占用该端口的条件下确认。**
3. **真机全部未验**：R7 的 A–H 里所有设备/双电脑部分（晚启动恢复、双电脑显式选择、换机录音→文本框、
   后台/前台失效恢复的真机表现、缺配置/端口冲突提示在真实桌面上的可见性）**均未由 D 验证**。
   D 未连接/安装手表、未改任何真实 Token 或配置、未动防火墙。**210 条绿灯不能推断 mDNS 或双电脑通过。**
4. **Compose 渲染未验**：JVM 无法渲染 Wear Compose。视觉候选里的 6 张图是**按代码 dp 值确定性绘制的
   度量图，不是运行时截图**；圆屏安全区、文本是否截断仍需真机 480×480 截图确认（`design/watch-ui/0.3.0-dev.2-candidate.1/README.md` 已声明）。
5. **前台/后台真机行为**：`onStart`/`onStop` 的接线在 JVM 里由测试直接调用 `onForeground()`/`onBackground()`
   覆盖，`MainActivity` 生命周期本身未在设备上观察。

### 3.2 范围与实现偏差（请 PM 裁定）

6. **测试构造器新增两个参数**：`RecordingViewModel` 增加 `retryDelayMs` / `healthCheckIntervalMs`
   （默认即冻结的 5 s / 5 s）。加它是因为“5 s 重试”需要在测试里被**真实计时**验证而不是靠一个 5 秒的睡眠；
   生产默认值与契约一致。任务包允许 `RecordingViewModel.kt` 与“必要的 UI 验证测试”，但**未逐字列出**
   该构造参数，属 D 主动扩展，请确认。
7. **新增 3 个 `internal` 测试接缝**：`pauseSchedulingForTransfer()`（生产路径 `startRecording` 真的调用它）、
   `resumeConnectionTaskForTest()`、`pauseConnectionTaskForTest()`。后两个仅测试使用。
8. **删除 `discovery_found` 资源**：该资源在 R6 已被证明无生产调用点（只有不被生产调用的 `discoveryLabel()` 用它）。
   R7 把它连同 `discoveryLabel()` 一起变成“只测试引用”，因此 lint 的 `UnusedResources` 类别仍会看到它；
   D 选择**删除**以便“已自动发现电脑”不可能再被渲染。若 PM 希望保留该资源以待将来修正内部来源追踪，请指示。
9. **`tauri.conf.json` 的 Windows 版本仍为 `0.1.9`**：本轮**没有**改它。理由：任务包 §2C 要求
   “本机已有应用标识、数据路径、Token、ASR 配置、历史保持兼容”且“不改现有正式 Windows 版本/自动更新渠道”，
   改配置版本会同时改动标识/兼容面。统一包的身份改由**产物名 + 包内 `BUILD-INFO.txt`** 承载
   （`package=SayIt-Watch-0.3.0-dev.2-windows-x64-portable`、`watch=0.3.0-dev.2 (versionCode 6)`）。
   若 PM 要求 EXE 属性中也体现 dev.2，请单独派发（会触及兼容面）。
10. **未新增 `client/src-tauri/gen/schemas/*` 之外的构建配置**：脚本复用既有 `tauri.conf.json`
    （`frontendDist: ../dist`），只新增脚本本身与 `.gitignore` 放行。`Cargo.toml` / `Cargo.lock` **未改**。
11. **`local.properties` / Token / APK / EXE / ZIP / 缓存均未提交**；`dist-portable/` 已加入 `.gitignore`。
    `client/src-tauri/gen/schemas/` 因构建产生纯 CRLF 差异，已在提交前还原，交付工作树**干净**。

### 3.3 风险提示

12. **前端资源嵌入是本包最容易静默退化的点**：`cargo build`（dev profile）与
    `tauri build --debug` 的产物完全不同（前者无前端）。脚本已把该检查变成硬失败，但**请 PM 在验收时
    也独立确认**包内 EXE 不含 dev-server 依赖，而不是只看进程能起来。
13. **5 s 节奏的双电脑冲突**：两台电脑同一 Token 同时在线时，旧电脑会被每 5 s 的有界探针复查，
    新电脑只会出现在显式切换列表里（不再自动跳转）。这正是 R7 的契约，但意味着一台电脑刚关机、
    另一台刚开机时，最多需要一轮（≤ 3 s 探针 + 5 s 间隔 + 8 s 浏览）才会稳定下来。
14. **回滚**：恢复 `d32821c` 即可完全回到 R7 之前（本轮两个提交只新增/修改上述文件，未删任何既有文件；
    `design/watch-ui/0.3.0-dev.1-candidate.4` 与既有 `mdns.rs` 保持原样）。回滚**不需要**删配置或重装。

---

## 4. 交付提交范围（一句话）

`d32821c` → `1716022b44e58f56b0eebeece9f752bd2760a0d9` → `3e036ee58bc5d7fa6d3f2677b93129a0ddba907d`
→ 分支头（本回传文档提交），共 **32 个路径**：
Watch 产品源码 6、Watch 测试 6、Windows 产品源码 2、打包脚本 1、忽略规则 1、设计候选 15、本回传文档 1，
无未提交修改。

D 已停止源码写入，等待 PM 独立核对范围/测试并进行设备与双电脑验收。
