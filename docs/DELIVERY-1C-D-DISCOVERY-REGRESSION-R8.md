# 1C-D-04@R8 — 保持当前目标、单一调度与可用便携包说明

## 0. 决策和基线

上一版本 `1C-D-04@R7`，交付头 `89cedd04bda3609e2286c92172fafbc896d640b8`。PM 独立审核结论：**NO-GO**。Watch 210/210、lint、Debug build 通过，ZIP 与包内 28 条 SHA-256 也一致；这些不能覆盖下述生产逻辑阻断。本包是原任务范围返修，用户已授权审核失败后自动发送。

- 唯一工作树/分支不变：`C:/Users/suzix/Documents/ChatGPT/SayIt-Watch-sync` / `codex/watch-connection-r7`。
- 先读 R7 包、R7-D-RETURN、本文件、HANDOFF/PROJECT_PROGRESS。以 `89cedd0` 为 R8 基线；保留 R7 提交，不改写历史。
- D 仍是唯一产品写入者。PM 不安装 R7 APK、不替换桌面软件；D 不操作设备、真实配置、进程、防火墙、push/merge/tag/release。
- 只修本文件列出的同源状态机、测试和便携说明/重建；不扩展协议、ASR、History、Paste、录音或正式 Release。

## 1. PM 阻断证据

### P0：点击切换会在 Resolver 内清除当前目标

`RecordingViewModel` 将 `ConnectionTaskOwner.cancelForRefresh` 接到 `resolver.onTargetInvalidated()`；该函数进入 `ResolverEngine.cancelLocked()`，明确执行 `clearTargetLocked()`。因此“切换电脑”一开始，UI 私有的 `verifiedDestination` 暂时仍是旧电脑，但 Resolver 的 `verifiedTarget/hasVerifiedTarget` 已变成空。

这造成两个事实源分裂：页面还能显示/录音，调度器却判断未连接。浏览结束或用户取消后，任务所有者按未连接路径继续搜索，最终清空/重建 UI 目标。R7 测试只断言了 `currentDestination()`，没有断言 Resolver/后续调度，因此漏掉了核心回归。

**必须修复：**显式切换开始、重新搜索、无候选、取消选择时，当前已认证目标在 Resolver、ViewModel、UI 和录音门中始终保持同一个值。只有健康复查失败、上传失败或用户成功点选另一个目标才可改变。用“不清目标但取消当前自动 run”的专用操作；不得再复用 `onTargetInvalidated()`。

### P0：已连接状态每轮健康检查后仍会进入完整 SEARCH

`ConnectionTaskOwner.waitForNextRound()` 对已连接目标做 `probeCurrentTarget()`，等待 5 秒后仍固定返回 `Pending.SEARCH`。下一轮 `SEARCH` 先执行 `cancelTransport()`，清空当前目标，再跑旧地址复查/浏览。这不是“每 5 秒一次有界认证探针”，会周期性把可用连接和录音门撤销。

**必须修复：**

- 已连接空闲循环只能按 5 秒间隔做一次当前目标认证探针；成功继续保持目标并等待下一次，`ServiceDiscovery.start()` 必须为 0。
- 只有探针失败后才清目标并进入恢复 SEARCH；未连接时仍按 R7 规则 5 秒重试完整搜索。
- 启动/回前台立即验证一次可以保留，但一次成功后不能紧接着再探一次，也不能每 5 秒完整搜索。
- “连接中”只覆盖实际探针/搜索窗口；两轮之间保持已连接和可录音。

### P0：取消和后台停止没有封闭任务生命周期

- `dismissSwitchPicker()` 只关闭 picker，不取消进行中的 BROWSE，也不把调度恢复到稳定的当前目标健康循环。
- `supersede()` 对旧 Job 只调用 `cancel()`，立即启动新 Job，没有等待旧 browse/NSD stop 完成；不满足 R7“完成取消交接后才启动替代任务”，存在 listener 短暂重叠。
- 健康探针被 `NonCancellable` 包裹；Activity `onStop` 时可能继续探针并修改状态。
- `resumeConnectionTask()` 无条件把 `appInForeground = true`，上传在后台结束时会篡改真实生命周期状态。

**必须修复：**一个串行 actor/mutex 或同等可证明机制按顺序执行 cancel-and-join → transport stop → 新意图。`onBackground` 后所有未完成结果作废且不能更新 UI/目标/设置；`resumeConnectionTask()` 只能读取真实前台状态，不能自行写为 true。关闭 picker 必须取消当前 browse，并在当前目标仍有效时回到健康循环；无目标时才回恢复循环。

### P1：便携包缺配置提示不可执行，构建标识不可追溯到产品提交

- MissingConfig 文案说“请在 SayIt 设置里填写本机的访问令牌”，但仓库里没有 Watch Token 的桌面设置入口；现有“服务器访问令牌”是另一套功能。不得引导用户填写错误位置。
- `README-PORTABLE.txt` 只说需要本机配置，没有给出配置文件路径/安全迁移步骤；双击新电脑仍不知道如何完成首次连接。保持“不打包 Token、不自动生成 Token”的边界，但说明必须可执行。
- README 的双引号 here-string 中 `` `frontendDist` `` 被 PowerShell 解释为控制字符 `\f`，实包中出现不可见字符。
- R7 包内 `BUILD-INFO.txt` 记录 `git_head=d32821c`，早于产品提交 `1716022`；虽然本次哈希可核对，最终候选必须从干净、已提交产品头构建并记录该产品提交。

**必须修复：**提示与 README 使用准确措辞：连接配置路径为 `%LOCALAPPDATA%\com.sayit.app\watch-receiver.config.json`，Token 只通过用户自己的安全本地迁移保留；不要指向不存在的 UI，不显示内容。修复控制字符，加入构建后字节/文本检查。先提交产品修复，再从干净工作树打包；`BUILD-INFO` 记录该产品提交，R8 回传文档可后置提交。

## 2. 必须新增的回归

测试必须走真实 `RecordingViewModel → ConnectionTaskOwner → ResolverBridge → DiscoveryCoordinator`，不能用源码字符串断言代替：

1. 已连接后进入切换、浏览无候选、按返回；立即及等待超过两个 5 秒周期后，Resolver 当前目标、`currentDestination`、`canRecord`、UI 当前 IP 均保持，浏览只发生用户要求的那一次。
2. 已连接空闲跨两个健康周期：当前目标不变、可录音在探针间保持、认证探针按周期增加，`ServiceDiscovery.startCount` 不增加；探针失败后才进入一次恢复搜索。
3. 浏览中连点重新搜索：记录最大同时 active listener/worker 数必须为 1；第二轮只能在第一轮 stop/完成取消后 start；旧回调不能更新列表或目标。
4. 浏览中按返回：当前 browse 停止且不再发布候选；当前目标保持。无当前目标时返回后仍允许前台恢复，但不在隐藏 picker 中继续 BROWSE。
5. 健康探针或 browse 期间退后台：操作取消/结果失效，之后至少两个间隔无新 probe/browse、无 UI/持久化变更；后台上传完成不得把 `appInForeground` 改回 true。回前台才立即重新验证。
6. 缺配置提示不含“在 SayIt 设置填写 Watch Token”等不存在入口；便携 README 包含准确配置路径、安全迁移提示，且无控制字符。包内不含配置/Token。

测试可增加必要的只读 internal seam，但不得维护第二套连接状态。清除 R7 中无法发现上述事实源分裂的测试假阳性；保留晚启动电脑、显式点选、设置未改不丢目标等已通过场景。

## 3. 实施和验证范围

- 允许修改 R7 已涉及的连接调度、Resolver/ViewModel/Activity 接线和对应测试；UI 仅在状态投影需要时调整。新视觉不变则保留 candidate.1，不覆盖；若显示变化则创建 `0.3.0-dev.2-candidate.2`，继承 candidate.1。
- Windows 产品代码只改 MissingConfig 固定文案及必要测试；打包脚本/README 生成、构建后检查和 `.gitignore` 可改。不要新增 Token UI 或改变配置格式。
- Watch：先跑新增定向场景，再一次 `testDebugUnitTest --rerun-tasks lintDebug assembleDebug`，记录 XML 计数。
- Windows：独立运行受影响 receiver 测试。PM 本轮首次 `cargo test` 受既有 transcribe CMake 缓存 FTK1011/MSB3491 类环境失败影响；D 要保留实际命令和完整结果，不把无法编译写成通过。live mDNS 门禁若仍失败须如实保留，R8 不改 mdns.rs 来掩盖。
- 便携包：从干净产品提交构建；重新解压到隔离目录，核对所有 SHA256SUMS、EXE 内嵌前端、无 Node/源码/Vite/配置/Token。不要覆盖用户桌面或停止当前 SayIt。独立启动仍留 PM，但包内说明必须静态可验。

## 4. 交付格式与停止点

- 提交顺序建议：产品修复+测试 →（干净头构建包）→ 视觉/文档 → R8-D-RETURN。不得 amend/force。
- 回传固定三栏：**变更文件、验证结果、待审问题**。列 R8 基线 `89cedd0` 之后全部提交、范围、命令/退出码/测试计数、APK/ZIP/EXE 哈希、包内 `git_head`、未测真机场景。
- 工作树干净后停止写入。D 不安装 APK、不替换电脑软件、不操作真实配置。PM 复核通过后才进入手表和双电脑 A–H 验收。
