# SayIt Watch Transport handoff

## R7 PM 验收 NO-GO；当前返修 1C-D-04@R8（2026-09-20，待发送）

- D 的 R7 交付头 `89cedd04bda3609e2286c92172fafbc896d640b8`，工作树干净。PM 独立复跑 Watch：210/210、lint、Debug build 退出码 0；APK SHA-256 `94833AE084EB29845B5554ACD1E79D922F59381D4C490BC78FDDA1D0F141E717`。统一 ZIP SHA-256 `86A37BF63F1BDF8CEC542CC82DE5278454F97BFA43C5DE060E1CE47D8A4294F5`，从 ZIP 独立解压得到 EXE `F12D28C90A8DA62BB03CFE53127186B20AB3F87295F1E404A7C3D889F334AB92`，28 条 SHA256SUMS 全部一致。Rust 新编译受本机既有 transcribe CMake 缓存失败阻断；D 报告的 live-multicast 用例亦未通过。
- 源码阻断：切换开始使用 `resolver.onTargetInvalidated()`，在 Resolver 内清掉当前目标，造成 Resolver 与 UI 目标分裂；已连接健康探针结束后固定进入完整 SEARCH，每周期再次清目标；取消 picker、cancel-and-join、后台探针和上传后前台状态亦未封闭。便携包 MissingConfig 提示指向不存在的 Watch Token 设置，README 还有控制字符，BUILD-INFO 记录的是产品提交前的 `d32821c`。因此不安装 R7 APK、不替换桌面软件，R7 核心验收失败。
- 同范围 R8 包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R8.md`。只修保持当前目标、已连接只健康探针、取消/后台/任务串行，以及便携提示和可追溯重建；晚启动与 R7 视觉成果须保留。当前待发送，阶段 10/12 均未通过。

## 当前有效返修：1C-D-04@R7（2026-09-20，已发送，D 执行中）

- 用户批准统一便携包与连接全流程返修。唯一任务包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R7.md`；上一 D 版本 R6，继承 PM UI R4。产品基线 main `17e59afde86ccf971b3d6ef1e777f841dece7224`，开发分支 `codex/watch-connection-r7`，工作树 `C:/Users/suzix/Documents/ChatGPT/SayIt-Watch-sync`；旧 `../Saylt` 不再写产品源码。
- 本轮范围：前台晚启动/重试/失效恢复，搜索单任务与显式点选，当前 IP 和简洁设置显示，同一份自包含 Windows Debug 测试便携包。任务包明确范围、A–H 场景、证据和回滚；D 开发，PM 独立装机/双电脑验收。
- PM 任务包提交 `371ddc3351449a45044eb6d1879442ec99c11f19`。已在本地 D 原对话「Saylt V2」点击可见“发送消息”按钮；消息 16:12 入列，界面显示“深度求索中…”和“停止生成”。已交接产品写入给 D；本条为派发事实，不是 D 交付或验收通过。阶段 12 与统一包均未完成，既有 UI R4 局部证据保留。

## 原仓库 main 已同步（2026-09-20）

- 用户确认同步到原有公开仓库 `Suzixuan/SayIt-Watch`（原名 `SayIt-watch-local`）。分支 `codex/watch-sync-20260920` 从原 `main` 的 `4b5bf7e1be7b75c7074ba105ae12099de8ece813` 建立，按文件导入已审核的 Delivery 1C、R4 UI、README 与证据；保留原历史和原仓库已有文件。此前误建的私有仓库不再作为同步目标。
- PR [#4](https://github.com/Suzixuan/SayIt-Watch/pull/4) 已合入 `main`，合并提交 `b561dc6d64f28199e1ba2105a2b407ac0ccc57cb`。远端默认分支与 README、手表 Logo、Ready 截图均已读回确认。其他电脑直接克隆 `https://github.com/Suzixuan/SayIt-Watch.git` 即可取得新版代码与文档。
- 本次同步不改变产品源码内容，复用下文 R4 的 198 项测试、lint/build 和真机截图证据。两电脑同时在线的发现、选择、换机后录音仍未完成端到端验收，也未发布对应的新安装包。历史记录中旧仓库名称、私有可见性和空 Git 基线均为当时状态，以本节为准。

## Ready 状态居中与 GitHub 交接：1C-PM-UI-01@R4（2026-09-20）

- 用户要求「已连接电脑」更居中、README 以手表功能为主并上传 GitHub。PM 冻结 `design/watch-ui/0.3.0-dev.1-candidate.4/`（父版 candidate.3 的 R3 真机 Ready，含预览、完整前态源码/测试与 SHA-256），只对已连接文案应用 -2dp 水平光学偏移；其他状态、麦克风、设置菜单、连接与录音逻辑不变。变更与回退说明见 `docs/DELIVERY-1C-PM-UI-SETTINGS-R4.md`。
- Watch 21 suites / 198 tests / 0 failures，lint 0 error / 36 warnings，Debug build 退出码 0；APK SHA-256 `180c4812fcf58edad0a5af9eaa4b714434e4f36f9a0682e472b33bde0311edc0`。保留数据 `adb install -r` 成功，真机 R4 截图相对 R3 只有状态行 925 像素改变、其余 0；文字像素重心 x≈244→240，与候选预览逐像素一致。证据 `docs/evidence/1C-PM-UI-01-R4/`。**R4 视觉局部验收通过；两电脑切换仍未验，阶段 12 未关闭。**
- 根 README 已改为手表为主，复用手表 Logo 和真实 Ready/Recording/Settings 截图；旧 README/中文 README 完整前态仅留本机 `docs/archive/` 作为回退，不进入 GitHub。`.gitignore` 排除了本地配置、构建产物、视觉 staging，以及可见账户标识/部分 API Key 的旧上游说明截图。PM 曾误将初始提交 `69a194da35e15c10a42e1eab8dcc88575aebdd31` 推到新建的 **私有** [Suzixuan/Saylt](https://github.com/Suzixuan/Saylt)；该仓库暂留作备份，不作为目标。原有公开仓库已更名为 [Suzixuan/SayIt-Watch](https://github.com/Suzixuan/SayIt-Watch)，后续同步和合并结果见本文件顶部。未创建 Release，未推送 `crosswk/SayIt`。

## Ready 状态文案视觉候选：1C-PM-UI-01@R4（2026-09-20，实施前）

- 用户要求把「已连接电脑」水平居中，并在完成后将项目上传 GitHub、将 README 改为手表主角。PM 冻结 `design/watch-ui/0.3.0-dev.1-candidate.4/`，父版为 R3 真机 Ready；仅将状态文案光学左移约 2dp 的预览、完整前态源码/测试、已锁定的设置菜单和哈希保存在候选目录。此处为冻结时历史状态；后续实施与真机证据见本文件顶部。
- 旧 Delivery 1C 切片禁止 push 是针对当时的开发验收。用户本轮明确要求上传；最终目标改为原有 `Suzixuan/SayIt-Watch`，结果见本文件顶部。双电脑真机验收仍独立未完成。

## 设置菜单实施与真机验收：1C-PM-UI-01@R3（2026-09-20）

- 用户确认 `design/watch-ui/0.3.0-dev.1-candidate.3/` 预览后，PM 仅替换设置菜单为圆屏双行布局和关闭 X；R2 Ready 主界面、切换回调/当前目标、发现与上传逻辑不变。冻结目录保留父版 `candidate.2`、R2 全量前态快照及预览；正式变更路径和哈希在 `docs/DELIVERY-1C-PM-UI-SETTINGS-R3.md`。
- 最终 Watch 21 suites / 198 tests / 0 failures，lint 0 error / 36 warnings，Debug build 退出码 0；APK SHA-256 `55338b04e65ceb2f6ba49f67509f904d9730b9e762dcb12289c082a74795388e`。保留应用数据 `adb install -r` 成功，Galaxy Watch 7 读回 `0.3.0-dev.1`/code 5。真机设置菜单与预览相符，关闭 X、进入原选择器、进入连接设置均可用；未修改设置。真实 Ready 截图与 R2 冻结图 SHA-256 **完全相同**。截图索引 `docs/evidence/1C-PM-UI-01-R3/`。
- **R3 UI 局部验收通过**，不等于阶段 12 整体通过。笔记本离线，双电脑发现/显式选择/换机录音链路仍未验。用户的正式 Release/桌面安装包未改；无源代码提交或推送。

## 设置菜单视觉候选：1C-PM-UI-01@R3（2026-09-20，待视觉确认）

- 用户认为 R2 的设置菜单不够精致，仍要求保留原本 Ready 主界面。PM 只针对设置菜单冻结 `design/watch-ui/0.3.0-dev.1-candidate.3/`，父版 `0.3.0-dev.1-candidate.2`；其中有 480×480 圆屏预览、R2 真实设置与已通过 Ready 截图、完整替换前源码/资源/测试快照、渲染脚本及 SHA-256 清单。
- 候选把矩形大弹窗改为圆屏双行入口（切换电脑、连接设置）和关闭控件；不改 Ready、发现、录音、Token 或目标保留语义。此处是预览冻结时的历史状态，后续用户已确认并由 PM 完成 R3 实装，最新状态见本文件顶部；阶段 12 双电脑验收仍未完成。

## 当前 UI 微调：1C-PM-UI-01@R2（2026-09-20）

- R1 保留数据装表后，真机 Ready 已无大号切换按钮，设置菜单两项在圆屏完整可见；从菜单点「切换电脑」进入原选择器，8 秒浏览无新候选后取消，原目标仍显示已连接。但真机 Ready 的 MIC READY 与麦克风比已批准的 `design/watch-ui/0.2.0-dev.3-final/ready.png` 明显偏上，未达到用户“别把原本 UI 弄乱”的视觉要求。
- 已冻结唯一新候选 `design/watch-ui/0.3.0-dev.1-candidate.2`：含原批准 Ready、R1 真机 Ready/设置菜单截图、R1 正式源码/资源完整快照、对齐预览、渲染脚本和 SHA-256 清单。候选.1 因真机标题/麦克风偏上而未放行；候选.2 经 PM 真机视觉比对局部通过。R2 只微调 Ready 布局及受影响的无目标文案，不动已通过的菜单、切换/录音/网络逻辑。
- R2 完整证据见 `docs/DELIVERY-1C-PM-UI-SETTINGS-R2.md` 和 `docs/evidence/1C-PM-UI-01-R2/`。PM 全量 Watch 198/198 单测通过、lint 0 error / 36 warnings、Debug build 退出码 0；APK SHA-256 `9557f6c5811c8283e349cade7110d0e93cda4ae13f7024b3e27784515cbace03`，保留数据 `adb install -r` 成功。真实 480×480 Ready 截图与原版标题/麦克风位置视觉一致，状态/设置无遮挡；菜单三项完整，设置→切换走原选择器，8 秒无新候选后取消，旧电脑保持可用。**R2 UI 与单目标切换入口局部验收通过；两电脑自动发现/切换仍未验，阶段 12 不关闭。**

## 当前 UI 调整：1C-PM-UI-01@R1（2026-09-20）

- 用户要求不要打乱原本简洁的 Watch UI，将切换电脑功能收进设置，并把具体放法交给 PM。PM 发现当前 `openConfig()` 会清空已验证目标；因此冻结的布局是 Ready 只留原有低调「设置」，由一个独立设置菜单提供「搜索／切换电脑」和「连接设置」。前者沿用 `requestSwitch()` 与现有选择器，不先调用 `openConfig()`；后者才进入 Token/手动地址页。核心场景与限域见 `docs/DELIVERY-1C-PM-UI-SETTINGS.md`。
- 设计候选 `design/watch-ui/0.3.0-dev.1-candidate.1` 已冻结：父版为 `0.2.0-dev.3-final` 的真实 Ready 截图；包含 R6 运行界面、改动前源码/资源快照、两个 480×480 布局预览、确定性渲染脚本与 SHA-256 清单。候选是示意图，不能代替新版本真机截图。
- PM 已按用户委托直接限域实现（未派发 D）：`RecordingScreen.kt` 取消 Ready 大号切换按钮，改为低调「设置」→ 独立菜单的「搜索／切换电脑」/「连接设置」；前者调用原 `requestSwitch()`，仅后者调用 `openConfig()`。仅另改 `strings.xml` 的两句导航文案、适配 R5 测试和新增 R7 菜单测试；`RecordingViewModel.kt`、发现/上传协议、Token/配置均未改。定向 R7/R5/R6 测试通过；Watch 全量 `testDebugUnitTest lintDebug assembleDebug` 退出码 0，198 tests / 0 failures / 0 errors / 0 skipped，lint 0 error / 36 warnings。新 Debug APK SHA-256 `e58dbb994c1ed496f250394d4799f20665d22e86aeb699133339dfde0fbc811a`。手表本轮不在 ADB 列表、mDNS 无调试端点，**新 APK 未安装，真机排版/交互未验**；手表上仍是上一轮的 R6。阶段 12 双电脑验收也仍待笔记本上线。实际改动及回退哈希见 `docs/DELIVERY-1C-PM-UI-SETTINGS.md`。

## 当前状态：1C-D-04@R6 真机文案与单目标入口通过，双电脑待验（2026-09-19）

- 用户重新开启手表无线连接后，PM 在在线 Galaxy Watch 上核对 R6 Debug APK SHA-256 `79bbf907563a4334dbba448a9de8ad9b465253b4ea4c40a3231090d3b9baa36e`，以 `adb install -r` 保留数据覆盖安装，返回 `Success`；设备读回 `com.sayit.watch.debug` versionCode 5 / `0.3.0-dev.1`、更新时间 20:32:47。应用启动的真机日志为 `probe:authenticated`、`saved-probe:accepted`、`verdict:one`，无该次启动的 `browse:started`；圆屏 Ready 实际显示「已连接电脑」而非「已自动发现电脑」，「切换电脑」及「设置／手动地址」仍可见。R6 的真机文案门槛通过。
- PM 点击「切换电脑」，真实选择面板保留当前目标并显示搜索状态；日志在 20:35:20 启动浏览，20:35:28 停止，随后 `browse:no-candidate` / `verdict:none`，选择面板仍显示旧目标。笔记本 `192.168.12.153` 当前不可达，所以此次单目标无候选不证明双电脑 mDNS 成败；未选择新目标、未改 Token/地址或网络设置。阶段 12 仍未完成，须笔记本上线后验证发现、显式选择和录音→文本框。无线 ADB 曾短暂掉线并通过现有调试端点无损重连；不影响已取得的安装/截图/日志证据。

- R6 已发送且 D 于 18:59 回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R6-D-RETURN.md` 并声明停写。PM 独立核对 R6 1 个产品源码修改、1 个新增测试及 1 个适配测试的实际路径/哈希与回传一致；`strings.xml`、`RecordingViewModel.kt`、`DiscoveryCoordinator.kt` 保持 R5/R4 哈希。Debug APK SHA-256 为 `79bbf907563a4334dbba448a9de8ad9b465253b4ea4c40a3231090d3b9baa36e`，与回传一致。独立重跑 R6 文案 6 条、R5 入口 8 条、切换 20 条，34/34、退出码 0；D 全量单测 195/195、lint 0 error / 36 warning、Debug build 退出码 0（日志 `%LOCAL_BUILD_ROOT%/r6-verify1.log`）。源码路径 `ReadyScreen` 使用的 `readyStatusTextRes` 已对认证成功统一映射「已连接电脑」；R5 错误文案不再由该生产入口渲染。
- 上一轮手表离线导致 R6 未能安装；本轮用户开启无线连接后已按本节顶部完成保留数据覆盖安装与真机文案/单目标入口复验。两电脑 mDNS/切换仍为 **未验证**，阶段 12 保持未完成。笔记本上线后再做第二台的真实搜索/选择/录音链路，不因 D 回传、自动测试或单电脑结果宣称整体 VERIFIED。

- R5 D 回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R5-D-RETURN.md`，五个产品/测试文件及 Debug APK SHA-256 与现场一致；PM 独立重跑 `RecordingReadyEntryR5Test` 8 条、`DiscoverySwitchFlowR3Test` 20 条，28/28，退出码 0。R5 保留数据覆盖安装到 Galaxy Watch：单电脑 Ready 圆屏显示可点击「切换电脑」及独立设置入口；点按后真实选择面板打开、`browse:started`，约 8 秒后 `browse:stopped` / `browse:no-candidate`，旧目标保持。笔记本不在线，尚无第二目标可验。
- 同次 R5 真机启动日志仅有 `saved-probe:accepted` / `verdict:one`，却显示「已自动发现电脑」。`ResolverBridge.routeAutomatic()` 和 `RecordingViewModel.applyResolverVerdict()` 均把目标认证成功映射成 `Discovered`，R5 的 `ExistingAddress` 文案测试未覆盖该真实生产链。R5 必修 3 验收失败。限域 `1C-D-04@R6` 包 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R6.md` 已发送 D 原对话并确认入列；此处为派发时历史，当前交付/验收状态见本节顶部。

- PM 在 Galaxy Watch 真机用 `adb install -r` 覆盖安装 R4 Debug APK，未卸载/清数据；手表原有保存地址和 Token 条目仍在。手表最初显示发现失败时，本机端口 18099 实际被旧 `C:/SayItApp/SayIt-0.1.9.exe` 占用且 discovery 返回 404。确认旧进程无活动连接后仅停止该进程，启动桌面 `SayIt-Watch-Debug-20260917/sayit.exe`；现在 `0.0.0.0:18099` 由 Debug 版监听，未带 Bearer 的 discovery 返回预期 401。旧程序没有卸载/删除，Debug 版保持运行。
- 手表重启后显示电脑可用，logcat 固定类别为 `probe:authenticated`、`saved-probe:accepted`、`verdict:one`；**这证明保存地址复查，不证明 mDNS 新发现**。Ready 真机界面没有「切换电脑」；源码 `RecordingScreen.kt:268` 将入口限制在 `ui.targets.size > 1`，而保存旧址成功会立即返回且列表仅当前一台。无目标失败界面也只让手动填 IP。核心「单台旧电脑在线时主动切换到笔记本」因此不可达；PM 撤回前述 R4 源码放行，整体 NO-GO。限域 R5 返修包见 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R5.md`，已通过 D 原对话可见「发送消息」按钮发送并核对消息入列，D 显示运行中。
- 笔记本当前不可达，未做两台电脑间的端到端切换；没有改手表 Token/地址或网络安全设置。R4 自动发现不能称 VERIFIED。PM 上轮只核对 ViewModel 入口、未核对真实 UI 入口可达性，已记录为验收漏检。

## 上一增量验收：1C-D-04@R4 源码/构建局部通过，后被真机入口证据推翻（2026-09-19）

- D 回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R4-D-RETURN.md` 并停止源码写入。PM 按 R3 哈希基线核对本次 4 个产品源码 + 1 个测试文件的 R4 SHA-256 及 Debug APK SHA-256，均与回传一致；近期 `watch/app/src` 修改时间也只落在这五个文件。Git 仓库无提交基线，不能以 `git diff` 证明整个项目无其它历史未跟踪改动，R3 原文件副本未保留，按哈希定位旧文件不是完整回滚材料。
- 核心需求—实际行为—证据：无旧目标且认证 2 台，`handleSwitchBrowse()` 不再用 `collected.firstOrNull()` 内部暗选；真实 `onReadyEntered()` 后仍无目标/不可录音/未落盘，旧目标保持并只在显式点选后改变。`BrowseWorker.stop()` 有界等待但超时保留存活句柄、拒绝第二线程，且将 `CLOSING` 与超时计数明确暴露。PM 独立运行 `gradlew.bat testDebugUnitTest --rerun-tasks --tests com.sayit.watch.net.DiscoverySwitchFlowR3Test --console=plain` 退出码 0，18/18 通过；D 的 `%LOCAL_BUILD_ROOT%/r4-verify1.log` 为完整 179 单测、lint、Debug build 成功，本轮 PM 未重复完整构建。
- R4 源码/构建门槛接受，但 **自动发现、台式机↔笔记本切换和录音到文本框的 R4 真机链路未验收**：`adb devices` 空列表，未安装 R4 APK。平台 resolve 若长期不响应，下一次浏览会安全地拒绝创建第二解析线程，可能暂时无法发现；只能待真实设备复测，不能称为 VERIFIED。Debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `086e6053245fc4fca994ed402217755037aa02d5a5be77d80e0ddd5ae7d6042f`。请勿把 R4 作为发布版，不改用户现有 Token/地址/防火墙。

## 上一验收：1C-D-04@R3 源码 NO-GO（2026-09-19）

- D 已回传 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R3-D-RETURN.md` 并声明停写源码。PM 核对 `BrowseWorker.kt`、`AndroidNsdDiscovery.kt`、`DiscoveryCoordinator.kt`、`RecordingViewModel.kt`、R3 定向测试和 Debug APK 的 SHA-256 与回传一致，独立重跑 `DiscoverySwitchFlowR3Test`：14/14、退出码 0（`--rerun-tasks`）。原完整构建日志 `%LOCAL_BUILD_ROOT%/r3-verify4.log` 显示 BUILD SUCCESSFUL；PM 本轮没有重跑完整 lint/build。ADB `devices` 列表为空，真机链路未验证。
- 核心阻断：无旧目标且同时认证两台时，`ResolverBridge.handleSwitchBrowse()` 用 `collected.firstOrNull()` 内部设第一台为 verified，下一次 `RecordingViewModel.onReadyEntered()` 可把它变成录音/上传目标，违反不得暗选；R3 的断言仅检查浏览刚结束。`BrowseWorker.stop()` 在限时 join 前清空线程句柄，超时后可能误报已停并叠加线程；R3 测试只覆盖可中断等待。R3 还新增了任务包未预先允许的 `BrowseWorker.kt`，范围须在下一版明确。
- 同一核心需求反复返修，PM 已定向核查状态契约和 worker 生命周期，暂停盲目补丁。用户确认后，限域 `1C-D-04@R4` 已通过右侧本地 D `Saylt V2` 原对话的“发送消息”按钮派发，消息已进入对话、D 显示运行中；唯一有效返修包为 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R4.md`。R3 源码 NO-GO，不安装 R3 APK，不声称自动发现 VERIFIED。旧手动地址可连接的历史证据不变。

## 上一有效派单：1C-D-04@R3（2026-09-19）

- PM 对 R2 实际文件与 APK 哈希独立核对，均与 D 回传一致，最终构建日志 BUILD SUCCESSFUL；但核心“切换电脑”链路有确定性源码阻断，解析 worker 也在浏览结束后永久 25 ms 轮询，另无目标切换可留下 Automatic 状态并重复等待。R2 源码验收 NO-GO，真机未装未测。定向返修任务 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R3.md`；当前版本 R3 仅修这些原任务范围内偏差，D 仍为唯一产品源码写入者，PM 保留真机验收。

## 上一版本：1C-D-04@R2（2026-09-19）

- 自动发现出现新回归：新版台式机 Debug EXE 与接收端正常，真实 mDNS Announce 已记录；用户手动填写当前台式机地址可连接，但 Watch 报“自动发现失败／未发现电脑”。Watch ADB 目前无在线设备，Windows 日志也没有这次失败可归因于 Watch 的认证探针；根因尚未证实。
- D 任务包：`docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R2.md`。R1 未派发，R2 增加笔记本同症状与不手动改 IP 的换电脑目标；聚焦 Watch 端 found→resolve→候选→认证的可观测性、证据支持的最小修复和显式切换。D 为唯一产品源码写入者，PM 负责真机复现与验收；任务版本不代表软件发布版本。
- Repair 3 的单次核心链路成功仍有效，但台式机／笔记本切换和当前自动发现失败使多电脑边界验收继续不通过；不得写成整体 VERIFIED。

## Windows laptop test kit prepared (2026-09-17)

- User wants the Watch to send recordings to a Windows laptop too. Prepared `%USERPROFILE%/OneDrive/Desktop/SayIt-Watch-Laptop-Test-20260917.zip` (SHA-256 `1790AB010B17FEB53EFE6CFB00907261B7CEB46364D300C19B8312840B0AE4BF`, 18,843,237 bytes, 42 entries). Its unpacked sibling folder is the source for the archive. Includes the accepted Debug EXE and runtime DLLs/resources plus a freshly built frontend (`npm.cmd run build` exit 0), a path-independent localhost Node server/launcher and README. EXE SHA-256 remains `D3AA7F8941E04B614D3037E7BB87DD4F205AAA5C40996D0484309A0D54B2D3DE`.
- The package has no receiver config, Token, provider credential, user model, received recording, history, cache, source tree or node_modules. The two `app/resources/test_*.wav` files are the pre-existing bundled test fixtures. Receiver config on this PC was checked presence/format only: `0.0.0.0:18099`, valid 64-hex token; its value was not printed or packaged. User must transfer only `%LOCALAPPDATA%/com.sayit.app/watch-receiver.config.json` privately to the same path on the laptop, install Node there, and configure the laptop ASR/provider locally. Do not copy DPAPI protected provider credentials as if portable.
- Local checks: built frontend 13 files, independent static server bound `127.0.0.1:1421` returned 200 for health, main/overlay/tray HTML and the main JS asset with correct MIME/marker; stopped the isolated test server afterwards. Archive contents/hash verified. The laptop itself is not yet available for execution, so laptop install, Watch discovery and recording→text remain unverified. Desktop SayIt runtime was not replaced or stopped for this packaging work. Multiple same-token PCs should not be simultaneously active: Watch first probes its saved destination and may stay on the old PC.
- The test kit uses Debug HTTP discovery and a local static frontend. It does not close formal Release/security packaging row 10.

## Desktop runtime relocation (2026-09-17)

- User explicitly requested deleting the old desktop version and putting the tested version on the desktop. The exact old folder `%USERPROFILE%/OneDrive/Desktop/SayIt-0.1.9-standalone-20260904` was moved to the Windows Recycle Bin after checking it was not running; original path absence verified. No application-data, credentials, history, models or other installation directories were deleted.
- Tested runtime copied to `%USERPROFILE%/OneDrive/Desktop/SayIt-Watch-Debug-20260917`: EXE plus 23 DLLs, bundled sample resources, README and `Start-SayIt.cmd`. All 24 executable/library hashes matched the build output; EXE remains `D3AA7F8941E04B614D3037E7BB87DD4F205AAA5C40996D0484309A0D54B2D3DE`. Build output retained, not relocated/cleaned.
- Launcher uses the existing project frontend at `%USERPROFILE%/Documents/ChatGPT/Saylt/client`, starts it hidden on loopback port 1420 when unavailable, waits boundedly, then launches the adjacent EXE. This is a local Debug convenience entry, not an independent portable Release. The existing-server launch path was tested; cold frontend-start branch was not separately exercised.
- PM stopped the prior build-directory process and launched the desktop entry successfully; process path is now the new desktop folder and unauthenticated discovery endpoint returned expected 401. No new recording/device acceptance was claimed for relocation; the executable is unchanged from the user-tested version. Previous runtime-path instructions below are historical. No packaging/security gate, release, push or auto-start installation was completed.

## Delivery 1C Repair 3 — core device chain passed; boundary acceptance partial (2026-09-17)

- User confirmed `测试成功` after the requested real discovery→recording→ASR→Notepad paste test. This closes the core end-to-end scenario by user-observed evidence, in addition to PM's discovery/offline/recovery observations below. PM did not independently capture the new transcript or measure latency; no such evidence is claimed.

- D has stopped source writing. PM verified delivered mdns.rs SHA-256 `AA87FE641587CCC3339B109AEA98F7FD8ED113DE3395431868DF502861EC0423`, unchanged mod.rs SHA-256 `D534CD6A642882D4AA1309E411C2FA60343542C6B5938A338C796FDD7F229B4C`, and Debug EXE SHA-256 `D3AA7F8941E04B614D3037E7BB87DD4F205AAA5C40996D0484309A0D54B2D3DE`. Focused D test/build log independently read: `%LOCAL_BUILD_ROOT%/r3-verify2.log`, mDNS 14/14, Watch receiver 57/57, Debug build exit 0 (19.73 s). No redundant unchanged-suite rerun.
- PM actual application run at 20:40 recorded queued then real announcement sent. Watch UI subsequently displayed `已自动发现电脑`; screenshot `%TEMP%/saylt-r3-discovered.png` visually checked. With the Debug receiver stopped, a fresh Watch app run correctly displayed manual fallback. After restarting receiver and Watch app, `已自动发现电脑` returned at 20:42. Device UI dumps: `/sdcard/saylt-ui-r3.xml`, `/sdcard/saylt-r3-offline.xml`, `/sdcard/saylt-r3-recovered.xml`. This supersedes the earlier basic-device NO-GO, but does not establish the original failure's root cause or DHCP/multi-PC stability.
- Core automatic-discovery chain passed: discovery/authenticated Ready, offline/recovery and user-confirmed real recording→ASR→Paste. Full boundary acceptance remains PARTIAL: IP-change/multiple-PC cases and broader network conditions remain unverified. No firewall/security setting, token, Watch APK, installed desktop binary or release was changed.
- Runtime handoff (last PM observation): the separate repaired Debug EXE and Vite at `127.0.0.1:1420` were left running for the now-successful user recording test; installed `C:/SayItApp/SayIt-0.1.9.exe` was temporarily stopped, not replaced. No runtime change was made on receipt of the user's success report. Stop the test EXE/Vite and relaunch that installed path to restore the prior runtime. Do not present this as a packaged upgrade.
- D report corrections (nonblocking): `LOG_MONITOR_STOPPED` is already used in the production Disconnected branch and the final log has no corresponding dead-code warning; final build time is 19.73 s, not 29.55 s. DaemonEvent::Error logs a fixed generic category, not the typed error_category mapping used by synchronous library failures. The reported backup is a final-file copy, not a pre-edit rollback baseline. No speculative code cleanup requested.

### Repair 3 dispatch and diagnostic history

- PM's additional isolated round-trip probe registered a non-authenticated diagnostic service on port 18101 and a second daemon on the same PC observed both ServiceFound and ServiceResolved (correct port, one address); both daemons shut down normally, exit 0. Evidence: `%TEMP%/saylt-mdns-probe-roundtrip.log`. This does not exercise the product's authentication or prove cross-device multicast. D is completing the bounded log/lifecycle correction; no root-cause resolution or device pass claimed.
- User requested shared bug-fix work. PM sent `docs/DELIVERY-1C-D-REPAIR-3.md` to the original Saylt V2 conversation and verified receipt/generation. D owns mdns.rs plus necessary mod.rs changes and the task return; PM owns an isolated diagnostic probe and device acceptance. No simultaneous product-source writers.
- The device NO-GO below remains the baseline. Root cause must be evidenced; queued registration must not be described as successful announcement. No firewall changes, dependency changes or Watch changes authorized in this slice.
- PM isolated probe using the exact compiled mdns-sd 0.21.3 rlib received a real Announce for the Wi-Fi interface (`192.168.12.142`) with no daemon error in a 30-second run; default route also selects that address. Evidence: `%TEMP%/saylt-mdns-probe-detailed.log`. This proves local announcement is possible, not delivery to the Watch. Correction to the older device report: AndroidNsdDiscovery does not log successful found/resolved callbacks, so their absence from logcat cannot prove they never occurred. The UI fallback is the observed failure. Wireless ADB subsequently became offline; PM requested the current connection port to resume device checks while D continues source work.

## Delivery 1C real-device acceptance — NO-GO (2026-09-17)

- User approved running the locally built Debug Windows EXE and temporarily switching from the installed desktop. PM launched `%LOCAL_BUILD_ROOT%\saylt-device-test-target\debug\sayit.exe` (SHA-256 `0D465EE579A97C7B83F489110A3D29377BA32AF06A51E8CD0DC347B38063D1F7`) through the Windows app control surface. The Debug receiver owned `0.0.0.0:18099`; an unauthenticated `GET http://192.168.12.142:18099/api/watch/discovery` returned 401. The PC log recorded `watch discovery advertised` for `_sayit-watch._tcp.local.`. This proves receiver startup and an enqueued registration, not successful multicast delivery.
- Galaxy Watch 7 SM-L310 at `192.168.12.125`, with Debug APK `0.3.0-dev.1`/code 5 installed, entered Ready and displayed `尚未找到电脑`. Watch log: `SayItNsd` discovery started for `_sayit-watch._tcp.` at 20:14:40 and stopped at 20:14:48. Successful found/resolved callbacks are not logged by this adapter, so callback absence is unknown. Watch ping to PC `192.168.12.142` succeeded (0% loss, ~2.1 ms); TCP connect to 18099 from Watch succeeded. Basic LAN reachability is established; discovery, candidate validation and authentication still need to be distinguished.
- PC Wi-Fi is Public profile. Existing inbound mDNS allow rules are scoped to `svchost.exe`, not the Debug EXE. During the test, the Debug EXE's UDP 5353 endpoint was briefly observed but absent on a later snapshot while the process and HTTP receiver were still alive. These observations do **not** yet prove whether firewall, socket lifetime/bind, or multicast routing is the root cause.
- Source/dep behavior clarifies the misleading success log: `watch_receiver/mdns.rs::register()` calls `ServiceDaemon::new()` and `register()` but never reads `monitor()` events. `mdns-sd 0.21.3` explicitly documents that multicast sockets open lazily in the daemon thread; `register()` queues a command and bind/platform failures surface later through `DaemonEvent`. Therefore the logged `advertised` line cannot be accepted as broadcast proof. Next narrow repair/diagnostic should observe daemon events and retain actionable errors, then rerun on the same PC/Watch. Do not change Windows firewall without separate authorization or claim automatic discovery VERIFIED.
- The test Debug process was stopped, Vite test server was stopped, and installed `C:\SayItApp\SayIt-0.1.9.exe` plus its 18099 listener were verified restored. No source, token, security setting, installed binary, or release was changed. Watch test APK remains installed. The previous `test binary launch blocked` note below is superseded by this approved launch and device result.

## Delivery 1C device attempt — test binary launch blocked (2026-09-17)

- Galaxy Watch 7 (SM-L310) was paired over ADB and the accepted Watch Debug APK (SHA-256 `CC79402608FEB54F5B18566C54B0FCB78B5A4943A715B16592039DD3BE705315`, version `0.3.0-dev.1`/code 5) installed successfully. This is installation evidence only, not discovery acceptance.
- Current PC IP `192.168.12.142`; installed desktop `C:\SayItApp\SayIt-0.1.9.exe` answered 404 at `/api/watch/discovery`, so it cannot prove Delivery 1C. PM built a separate source-matched Debug binary at `%LOCAL_BUILD_ROOT%\saylt-device-test-target\debug\sayit.exe` (SHA-256 `0D465EE579A97C7B83F489110A3D29377BA32AF06A51E8CD0DC347B38063D1F7`, `cargo build` exit 0). First build in the existing cache failed FTK1011; two fresh junction builds failed because the junction silently did not create child directories. A direct short target plus Ninja and fallback from the broken junction passed in 10m55s; no source changes or cache deletion.
- Vite dev server was started at `127.0.0.1:1420`. The prior installed desktop was gracefully asked to close, remained resident/occupied 18099, then was temporarily stopped under user approval. Launching the separately built Debug EXE was rejected by the execution environment. PM immediately restarted the installed desktop; process and listener on 18099 were independently confirmed restored. The test binary never ran. No token value was printed or copied; the existing local persisted receiver config remains in place.
- **Gate: NOT VERIFIED / blocked before PC discovery broadcast.** Need action-time approval or user launch of the local test binary, with the installed desktop temporarily closed. Only then test authenticated discovery, watch Ready/recording/upload, DHCP/IP change and negative paths. Do not infer success from APK install, source tests, or build.

## Delivery 1C Repair 2 — PM SOURCE/BUILD ACCEPTED (2026-09-17)

- Follow-up acceptance recheck: all 8 source/test SHA-256 entries and the Debug APK match the final §8 manifest; existing XML reports remain 144/0/0 and the full-build log ends successfully. D's latest reply explicitly confirms closure and no further file edits/builds/new tasks. No new delivery found; reuse unchanged verification evidence, with device acceptance still NOT VERIFIED.

- Repair package was sent to the original Saylt V2 conversation. D completed the bounded repair, then stopped source writing; PM took over the asynchronous production adapter, stale manual persistence and cancellation/listener ordering.
- PM final verification: Watch **144 tests / 0 failures / 0 errors**, lintDebug, assembleDebug and assembleRelease passed. Six new integration regressions exercise the actual adapter factory used by RecordingViewModel, not an independently reimplemented adapter.
- Canonical final evidence and changed-file SHA-256 manifest: `docs/DELIVERY-1C-D-REPAIR-2.md` §8. Its §7 is D's historical intermediate report, not the final artifact manifest.
- This repair/source stage is complete. **Automatic discovery remains NOT VERIFIED on Galaxy Watch**; multicast/firewall/multi-NIC behavior and actual recording/upload after discovery still need separate device acceptance. No install, packaging, release, merge or push was performed.
- D remains stopped; no new implementation task. `PROJECT_PROGRESS.md` remains the only authoritative progress table.

## Latest PM review (2026-09-15, Delivery 1C Repair 1 re-review)

- Verdict: **NO-GO; Repair 2 required.** Repair 1 correctly fixed the real NSD service-type mapping, pre-auth upload target, 0/1/≥2 endpoint verdict, duplicate probing and Windows discovery response body. Scope and reported hashes match.
- Remaining blocker 1: `HttpDiscoveryProbe` sets `connectTimeout = min(budget, 1000)` and `readTimeout = budget`. These serial phases can sum beyond the caller's remaining deadline, so the claimed 3 s/8 s end-to-end bound is not true for the real HTTP implementation; existing slow-probe tests use a cooperative fake and do not exercise this split.
- Remaining blocker 2: `RecordingScreen` unconditionally starts automatic discovery whenever Ready is entered, while `applySettings()` moves to Ready and starts a manual probe. Opening Config also leaves an existing run alive. Manual and automatic results can race, clear or overwrite `verifiedDestination`, and a late automatic result can defeat the user's authenticated manual choice.
- Independent evidence: Watch unit tests 113/113 passed (exit 0, forced rerun, 2m42s); Debug APK hash matched `4E7976884D5BD2EE84EC0BD4A8F3A7F6B7CE2BDD30C837255A1E14CF4D2EE44A`; current Rust Watch test artifact passed 48/48 from `client/src-tauri` (the first root-CWD attempt failed six source-path checks only and was corrected). Green tests do not cover the two blockers above.
- Narrow next contract: `docs/DELIVERY-1C-D-REPAIR-2.md`. Because the same core requirement has already had one repair, PM paused automatic consecutive dispatch, rechecked the contract/architecture, and reduced the next slice to only the two remaining issues. Automatic discovery remains **NOT VERIFIED**; do not install the candidate or begin device acceptance.

## Delivery 1C Repair 1 submission (2026-09-15, by D)

- Contract: `docs/DELIVERY-1C-D-REPAIR-1.md`; D's three-heading return is filled into its §9. Scope followed exactly: the three-line `MainActivity.kt` Context wiring is the only previously-outside path, now formally allowed; no other expansion.
- All four blocking items are repaired in the Watch discovery path plus one Windows response body:
  1. `ResolvedService` separates `instanceName` from `serviceType` and the candidate policy checks the **type**, so a real `SayIt` instance is accepted; `AndroidNsdDiscovery` passes `NsdServiceInfo.serviceType`, folds concurrent resolve completions through the pure `DiscoveryPolicy.foldResolved`, and discards callbacks from a browse session that already stopped.
  2. No unauthenticated address can become an upload target: `applySettings()` never persists a manual address (only `adopt()` after a successful probe), `currentDestination()` returns only the probe-authenticated in-memory target (no format-valid fallback), and both record entry points are gated on `canRecord` / `verifiedDestination`, so re-probe, browse and manual-probe windows cannot record. A failed upload only lets the NEXT recording re-discover; the WAV is never re-sent.
  3. The frozen 0/1/≥2 rule is implemented: every distinct endpoint is authenticated exactly once inside the window, and only exactly one authenticated endpoint is saved; 0 or ≥2 gives `ManualFallback` with nothing saved or selected, independent of arrival order.
  4. The 3 s / 8 s windows are true end-to-end budgets (`DiscoveryProbe.probe()` receives the caller's remaining budget), and Windows `GET /api/watch/discovery` returns exactly `{"service":…,"protocol":…}` — the extra `path` is gone while the TXT `path` stays.
- Self-found defect fixed in the same pass: rejected candidates were re-probed ~160 times inside the 8 s window; now each endpoint gets exactly one probe.
- Fresh evidence: Watch `testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease` exit 0, **113 tests / 0 failed**; `cargo test` 170 passed / 0 failed / 4 ignored; `cargo build --release` exit 0 with **all 19 release markers absent**; client Vitest 370/370 and build exit 0. Debug APK SHA-256 `4e7976884d5bd2ee84ec0bd4a8f3a7f6b7ce2bdd30c837255a1e14cf4d2ee44a`.
- Watch stays `0.3.0-dev.1` / versionCode 5; Windows stays `0.1.9`, not repackaged. Dependencies, manifest and version number unchanged from the first 1C submission.
- Automatic discovery remains **NOT VERIFIED**: no device this round; PM source review and the Galaxy Watch acceptance stay with the PM.

## Latest PM review (2026-09-15, Delivery 1C Repair 1 required)

- PM verdict: **NO-GO for source/device acceptance**. Repair package: `docs/DELIVERY-1C-D-REPAIR-1.md`; it was sent to the existing D conversation on 2026-09-15 and D is generating.
- Blocking findings: the Android adapter passes `NsdServiceInfo.serviceName` (instance name) into a policy that expects the service type, so real services are rejected; manual and saved addresses can be used for upload before their Bearer probe succeeds; the coordinator selects the first authenticated PC instead of falling back when multiple endpoints authenticate; blocking probes can exceed the frozen 3 s/8 s total windows, and the Windows success body includes an unauthorized extra `path` field.
- Scope decision: the three-line `MainActivity.kt` application-context wiring was outside the original allowlist but is a necessary minimal NSD connection. Repair 1 formally allows that exact wiring; no other scope expansion is accepted.
- Independent evidence: Watch unit tests reran successfully (100/100; exit 0) and the current Debug APK SHA-256 matches D's `653D98DCDF6E8E443AC4D5A1089DD766CDA5EF21A6044E5B574A2EFD1FFFD00C`. These tests do not prove the core behavior because their service fake encodes a full type inside `serviceName` and omits the pre-auth upload race. PM `cargo test watch_receiver` was blocked before project tests by the existing external `transcribe-cpp-sys` CMake/MSBuild `FTK1011` cache error (exit 101); D's Rust results remain worker evidence only.
- Automatic discovery remains **NOT VERIFIED**. Do not install this APK or begin Galaxy Watch acceptance until Repair 1 passes PM source review.

## Latest handoff (2026-09-15, Delivery 1C implementation by D)

- Delivery 1C task package: `docs/DELIVERY-1C-D-AUTO-DISCOVERY-HANDOFF.md`. This entry is D's implementation record; the package's own "D 回传" section is filled in at the end of that document.
- **Not VERIFIED end-to-end.** No Watch was installed and no PC was modified this round; stage 8 (ten consecutive real runs) and the PM device acceptance of "DHCP address change + automatic discovery + one send per recording" remain locked.
- Windows side (debug-only, unchanged freeze): `client/src-tauri/Cargo.toml` adds `mdns-sd = "0.21"` (resolved `0.21.3`), `Cargo.lock` gains only the mDNS graph (`mdns-sd`, `if-addrs`, `flume`, `mio`, `socket-pktinfo`, `fastrand`); new `client/src-tauri/src/watch_receiver/mdns.rs` registers `_sayit-watch._tcp.local.` with TXT `protocol=1` + `path=/api/watch/discovery` and no token, token digest, user name, audio, window or path metadata; `watch_receiver/mod.rs` declares the module and calls `mdns::spawn_registration(&cfg)` ONLY after `ReceiverServer::start` bound the port; `watch_receiver/server.rs` adds `GET /api/watch/discovery` behind the existing constant-time Bearer check (200 = fixed service id + protocol, 401 otherwise, token never echoed or logged). No version change: Windows stays `0.1.9` and no installer is produced.
- Watch side (candidate `0.3.0-dev.1` / versionCode 5): new `net/Discovery.kt` (frozen constants + pure candidate/response rules), `net/DiscoveryCoordinator.kt` (single-flight 3 s saved-address re-probe → 8 s browse window → bounded manual fallback; NSD isolated behind the `ServiceDiscovery` interface; settings behind `DiscoverySettings`), new `net/AndroidNsdDiscovery.kt` (official `NsdManager`, `discoverServices(type, PROTOCOL_DNS_SD, listener)` — the API-34 form; no third-party mDNS library, no new permission); `ui/RecordingViewModel.kt` + `ui/RecordingScreen.kt` move the IP/port fields into an optional "手动设置地址" section inside Config, leave the 64-hex token as the only mandatory field, run discovery on every entry into Ready and stop browsing on the way out (`onCleared` + screen effect), and invalidate the known address after a failed upload so only the NEXT recording re-discovers.
- Boundary that must not regress: the advertised values are exactly `protocol=1` and `path=/api/watch/discovery`; only a probe answering 200 with the frozen service id + protocol version may be saved; hostnames, IPv6, public, link-local, loopback and `0.0.0.0` are rejected before any request; a failed upload never re-sends the same audio (the WAV is discarded, so a lost response cannot duplicate a transcription); release builds contain neither the discovery endpoint, the mDNS registration entry point nor a usable browser/probe.

## Latest handoff (2026-09-15)

- The project was migrated into `%USERPROFILE%\Documents\ChatGPT\Saylt` on 2026-09-14. The destination keeps its own empty Git repository; all project files are currently untracked, so no clean commit baseline exists here.
- The user has explicitly opened a new Delivery 1C slice for automatic Windows discovery from the Watch. This supersedes the earlier no-discovery rule only for this bounded slice; all existing audio, Provider/ASR/History/Paste, token, debug-only transport, release, secret and frozen-design boundaries remain.
- Authoritative D task package: `docs/DELIVERY-1C-D-AUTO-DISCOVERY-HANDOFF.md`.
- Until D delivery is independently reviewed, the accepted Windows desktop remains `0.1.9`, Watch remains installed as `0.2.0-dev.4`, the ten-run gate remains deferred, and automatic discovery is not VERIFIED.

## Goal

Verify the smallest real transport path first:

`Galaxy Watch 7 -> 16 kHz PCM/WAV -> debug HTTP over Wi-Fi -> Windows SayIt -> received_watch.wav`

Only after PM manually accepts the received audio may the project unlock Delivery 1B and reuse the existing SayIt Provider/History/Paste pipeline.

## Source authority

- Baseline archive: `SayIt-local-src-2026-08-29.zip`
- Baseline SHA-256: `E492F71CF073E2012E0CA61AFD865F9A773B4E321A8C9853D29C4FCD62E889B3`
- Inherited project handoff: `HANDOVER.md`
- Upstream public repository: `crosswk/SayIt` at inherited commit `bad38da`
- Private working repository: `https://github.com/Suzixuan/SayIt-watch-local`
- Accepted Delivery 1A branch: `codex/review-watch-transport`
- Accepted PM commit: `fe096d7c41c335155db8c8300a89f64b47f75fe1`
- Delivery 1B review branch for colleague Z: `codex/review-watch-pipeline`
- Baseline import commit: `18400ad82d7f5a7009a47622248643022472650f`
- Large installer and build caches were deliberately excluded from Git history.

## Confirmed decisions

- Delivery 1A and 1B are strictly sequential.
- Delivery 1A uses a manually supplied random development token; formal six-digit pairing is deferred.
- Cleartext HTTP is permitted only in Android debug builds. Release builds must deny it, and the Windows HTTP receiver must not start in release builds.
- The Windows receiver binds exactly one configured RFC1918 LAN IPv4, never `0.0.0.0`.
- Galaxy Watch 7 must prove native 16,000 Hz initialization. Delivery 1A must fail visibly rather than resample or fall back to 44.1/48 kHz.
- A successful upload means only durable transport. The receiver returns `201 Created`; it must not claim transcription success.

## Current state

- The user reports the existing AudioRelay -> SayIt -> ASR -> text-output chain as VERIFIED. It is out of scope for re-test or refactor.
- The isolated source baseline and PM documents are prepared and pushed to the private `main` branch.
- Delivery 1A was repaired, independently verified, and PM-accepted on `codex/review-watch-transport`.
- The user confirmed the real received WAV by human playback.
- Delivery 1B Provider contract is PM-accepted as **MATCH**. Repair 1's seven architecture corrections, Repair 2's exact final-chunk accounting, and the Z2 packaged-Windows raw binary-IPC proof are frozen.
- Z2 (stage 6A) is independently PM-accepted. The next and only unlocked slice is the PC-only external WAV ingress task in `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`; the real Watch closed loop and ten-run acceptance remain locked.
- The PM-reviewed Watch UI direction is frozen in `docs/WATCH-UI-Z-HANDOFF.md` and is now explicitly unlocked only as Part B of `docs/DELIVERY-1B-Z3-REPAIR-1.md`, after Part A Repair passes.
- Updated user sequencing decision (2026-08-29): Z3 Repair 1 and the frozen Watch UI candidate are one colleague-Z package, strictly ordered Repair first and UI second. Real-device closure is still a later PM gate.

## Colleague Z quick start

- Repository: `https://github.com/Suzixuan/SayIt-watch-local` (private; obtain access from the user, never exchange credentials in project files or chat evidence).
- Read first: `AGENTS.md`, `HANDOFF.md`, `PROJECT_PROGRESS.md`, then `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`.
- Working branch: `codex/review-watch-pipeline`, based on accepted Delivery 1A.
- Z3 is PC-only external ingress. Change only the task's allowed files; do not modify Watch UI, Provider implementations, History, Paste, AI, target/focus behavior, release HTTP, dependencies, or accepted locks.
- Z must push the requested evidence and stop; no merge, tag, release, installer, or public-upstream push.

## Delivery 1B Z1 contract evidence (Colleague Z, 2026-08-29) — revision 1, superseded by Repair 1

> Superseded: this entry describes the original submission (`525f0b2`), whose MATCH verdict, `connect → isReady → start` order claim, and single-side release design were corrected by PM Repair 1. Retained for audit; the authoritative record is the revision-2 entry below and the PM review in between.

- Deliverable: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` (documentation only; base commit `5cc14b4c8658920cc9ca9b21ab1dcaf878f1a136` on `codex/review-watch-pipeline`). No product code, tests, dependencies, or build configuration changed.
- Conclusion: **MATCH**. All six proposed audio-contract clauses are confirmed from source evidence on both sides — capture (`client/src/services/audio.ts`: 16 kHz target, mono, Int16 conversion, `bytes/2/16000` duration) and receiver validation (`client/src-tauri/src/watch_receiver/wav.rs`: PCM format 1, mono, 16,000 Hz, 16-bit, block-align/byte-rate checks).
- Contract freeze: `TranscriptionProvider` real call order is `connect → isReady → start → sendAudio* → stop → (onASR? → onFinal → onDone?) | onError` with runId generation guards (`RecorderOrchestrator.ts:126-135, 258-275, 900-1154`); a normal final reaches History first (`addHistory` in `processFinalResult`) then Paste with the probe snapshot captured exactly once at run start; `aiEnabled` is the AI-cleanup setting and an external run can freeze `disableAi: true` per-run via existing `StartOptions`/`StopOptions` fields without deleting AI features.
- `409` admission boundary design: a Rust `AdmissionGate` (`Idle`/`Reserved{requestId}`) shared between the blocking receiver thread and three new debug-only Tauri commands; the WebView performs a fully synchronous check-and-reserve (`tryReserveExternalRun`) inside one JS macrotask, so a PTT run cannot start between the busy/ready check and the reservation (and vice versa via one added guard clause in `startRecording`). Admission precedes the durable save, so a `409` never overwrites `received_watch.wav`; `201` is still returned only after durable save plus acceptance; PCM reaches the Orchestrator as raw header-stripped bytes via binary IPC (no base64); every terminal path releases the gate through the existing `finishRun` hook.
- All five task stop-conditions evaluated as not triggered; minimal implementation file list, four ordered event sequences, and nine risks/open questions are recorded in the contract document for the PM's decision.

## Delivery 1B Z1 PM review (2026-08-29) — NO-GO

Scope control passed: Z changed only `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`, `HANDOFF.md`, and `PROJECT_PROGRESS.md`; no product code changed.

The contract is not frozen yet:

- It states `connect -> isReady -> start`, while the real run first checks readiness and then reconnects the existing guarded callbacks before `start`. The external sequence omits explicit successful `provider.connect(buildProviderCallbacks())` and `provider.start(...)` before PCM.
- It labels `tryReserveExternalRun` fully synchronous while also claiming it completes `bridge.getRecordingContext(false)`, which is asynchronous.
- Durable-save and other Rust-side aborts release only the Rust gate, leaving the JS reservation/run allocated.
- The proposed 30-second JS watchdog is shorter than valid processing plus late-final lifetimes and cannot execute after a WebView reload.
- The design explicitly permits a lost post-201 handoff, leaving the Watch showing success when no ASR run exists.
- It does not freeze `recordedChunks`, `audioSentSamples`, sample-derived duration, context fields, or a non-mic stop path required by existing History and timeout behavior.
- Raw binary Tauri IPC remains an unverified dependency despite the unconditional `MATCH` verdict.

Decision: Delivery 1B implementation remains locked. Colleague Z receives the documentation-only repair package `docs/DELIVERY-1B-Z-CONTRACT-REPAIR-1.md` and must stop after resubmission.

## Delivery 1B Z1 contract revision 2 (Colleague Z, 2026-08-29) — resubmitted after Repair 1

- Deliverable: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` revision 2 (documentation only; review base `8240d4a`). No product code, tests, dependencies, or build configuration changed. A §G checklist maps all seven repair items to exact contract sections.
- Verdict: **CONDITIONAL MATCH**. The single condition is the Tauri v2 raw binary-IPC spike for the PCM handoff, frozen as the first stop/go gate of the implementation slice (§B.4, §E row 0). Pinned-version source evidence is recorded (`tauri 2.10.3`: `InvokeResponseBody::Raw`, `Response::new`, octet-stream dispatch at `src/ipc/protocol.rs:344`; injected `scripts/ipc-protocol.js:50-53` consumes non-JSON responses via `response.arrayBuffer()`; `@tauri-apps/api 2.10.1` `core.js:201-203` passthrough), including the postMessage-fallback hazard that motivates the spike. A base64 fallback for the full 10 MiB body is explicitly not authorized.
- Repair 1.1: the frozen per-run lifecycle is now pre-flight `isReady()` → per-run `connect(buildProviderCallbacks())` → checked `start()` → `sendAudio*` → `stop()` (§A.2); the external run performs both indispensable steps, re-verifies reservation/run currency after each await, and no PCM moves before `start` succeeds, with per-stage rollback (§B.5).
- Repair 1.2: truthful two-phase API (§B.2) — Phase A fully synchronous (checks + runId + reservation + the genuinely synchronous `captureActiveInsertionTarget`, `textInsertion.ts:248-276, 316-321`); Phase B is the bounded (3 s) asynchronous native capture (`bridge.getRecordingContext`, a Promise, `bridge.ts:127-132`) whose success is required before the admission response is accepted; capture failure → `500 context_capture_failed|context_capture_timeout`, no silent live-probe fallback.
- Repair 1.3: one correlated abort operation, two idempotent requestId(+runId)-conditional halves, clearing the Rust gate, JS reservation/runId, pending promises, overlay/Esc state and the provider session across all nine trigger cases (§B.6 matrix), including durable-save failure and WebView reload.
- Repair 1.4: the 30 s JS watchdog is removed; a Rust-owned lazy lease (`LEASE_MS = 300 s` = frozen `MAX_RECORDING_SEC`, against a derived ≈164 s legitimate worst case) plus boot-time `watch_gate_state()` reconciliation recover a gate orphaned by WebView reload without any JS timer (§B.7).
- Repair 1.5: `201` is returned only after a bounded (10 s) post-save acknowledgement (`watch_run_started`) proving the saved PCM was obtained and validated, callbacks connected, and `provider.start` succeeded; failures answer non-2xx and abort both sides; `201` means transport/admission/session success, never transcription completion (§B.3 step 8).
- Repair 1.6: the external run explicitly populates `recordedChunks`, `audioSentSamples`, sample-derived `wallTimeAtStopSec`, the processing-timeout input and the prompt/app/probe context fields; it never calls mic-only `stopCapture()`; the smallest shared finalize helper (`finalizeRecording`) is extracted from `stopRecording`'s tail for both paths (§B.5).
- All five task stop-conditions remain not triggered (§D); revised sequences cover accepted, busy, abort matrix, reload recovery and successful final (§C); nine risks recorded (§F).

## Delivery 1B Z1 PM re-review (2026-08-29) — Repair 2 required

Scope control passed: compared with PM review base `8240d4a`, Z changed only `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`, `HANDOFF.md`, and `PROJECT_PROGRESS.md`; `git diff --check` is clean. Revision 2 correctly addresses all seven Repair 1 architecture items.

One blocking defect remains in §B.5 step 7: the feed loop increments `audioSentSamples += 4096` for every chunk, although the final slice can contain fewer than 4096 i16 samples. That overcounts sample-derived duration and can affect minimum-duration handling, processing timeouts, History audio metadata, and latency evidence.

Decision: revision 2 is **not yet frozen**. Colleague Z receives the narrow documentation-only package `docs/DELIVERY-1B-Z-CONTRACT-REPAIR-2.md`. It requires exact `chunk.byteLength / 2` accounting, end-of-feed equality checks against admitted `sampleCount`/PCM bytes, correlated abort on mismatch, and one non-divisible-by-4096 test contract. All other Repair 1 decisions remain accepted and must not be redesigned. Product implementation and the binary-IPC spike remain locked until PM accepts Repair 2.

## Delivery 1B Z1 contract revision 3 (Colleague Z, 2026-08-29) — resubmitted after Repair 2

- Deliverable: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` revision 3 (documentation only; review base `dd7efb2`). No product source, tests, dependencies, generated files, UI files, WAVs, APKs, tokens, or logs changed. `docs/WATCH-UI-Z-HANDOFF.md` was treated strictly as PM reference for a later slice — no UI work performed.
- Scope: exactly the Repair 2 correction — exact final-chunk sample accounting. Changed contract sections: §B.4 (chunk target is not an invariant; the final slice carries the exact remainder, guaranteed even), §B.5 step 7 (per-chunk `chunkSamples = chunk.byteLength / 2` on the exact slice; exact copied chunk into `recordedChunks`; `audioSentSamples += chunkSamples`, never the configured maximum; post-feed assertions `audioSentSamples === admission.sampleCount` and `Σ chunk bytes === pcm.byteLength` **before** `provider.stop`, mismatch → correlated abort with fixed reason `sample_accounting_mismatch`), §B.5 step 9 (`wallTimeAtStopSec`, `pttHoldMs`, timeout input, History duration derived **only** from the exact final `audioSentSamples`), §C.1 (sequence updated with the assertions and abort-before-stop), §E row 7 (explicit non-divisible-by-chunk-target test case plus a mismatch-abort case), §G (new R2 mapping row). Header and revision notes updated; verdict stays **CONDITIONAL MATCH**.
- Confirmation: no other Repair 1 decision was weakened. The per-run lifecycle (§A.2), two-phase reservation (§B.2), correlated abort operation and nine-case matrix (§B.6), Rust-owned 300 s lease and reload recovery (§B.7), post-save acknowledgement before `201` (§B.3 step 8), shared `finalizeRecording` / no-`stopCapture` rule (§B.5 step 9), probe-once rule, reuse guarantees, and the binary-IPC spike gate (§B.4) all carry over from revision 2 verbatim.

## Delivery 1B Z1 PM acceptance (2026-08-29) — CONDITIONAL MATCH accepted

PM compared revision 3 commit `c89c0bc36bccc32281ca3158e1f111511467d39f` against review base `dd7efb233f3ab85a85c740cecbfe644078cc7827`.

- Scope passed: only `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`, `HANDOFF.md`, and `PROJECT_PROGRESS.md` changed; no product source, tests, dependencies, UI, generated files, or artifacts changed. `git diff --check` is clean.
- Repair 2 passed: chunk size is a target rather than an invariant; each exact even-length slice uses `chunk.byteLength / 2`; the copied chunks and `audioSentSamples` totals are asserted against the admitted PCM before `provider.stop`; mismatch takes the correlated `sample_accounting_mismatch` abort; duration/timeout/History inputs derive only from the exact final sample total; the implementation test contract includes a non-divisible final chunk and mismatch-before-stop case.
- Regression review passed: the diff does not weaken the accepted Repair 1 lifecycle, two-phase reservation, two-sided cleanup, Rust lease/reload recovery, pre-`201` acknowledgement, no-`stopCapture` external finalization, probe-once rule, or AI-off/reuse boundaries.

Decision: the Provider contract is frozen and stage 6 is accepted as **CONDITIONAL MATCH**. This is a documentation/architecture acceptance, not Delivery 1B product completion. Only the isolated ≥9 MiB binary-IPC spike is unlocked; task package: `docs/DELIVERY-1B-Z-BINARY-IPC-SPIKE-TASK.md`.

## Delivery 1B Z2 binary-IPC spike (Colleague Z, 2026-08-29) — PASS, awaiting PM review

- Deliverable: `spikes/watch-binary-ipc/**` only (isolated harness; plus this file and `PROJECT_PROGRESS.md`). No SayIt product file — `client/**`, `watch/**`, receiver, Provider, Orchestrator, History, Paste, AI, target/focus, release configuration, or the accepted dependency locks — was touched. `docs/WATCH-UI-Z-HANDOFF.md` was not implemented.
- **Result: PASS** in the packaged Windows debug WebView2 app (not a browser/dev-server page), reproduced across two runs (invoke elapsed 126 ms / 113 ms). Runtime panel (PASS/FAIL, versions, payload length, JS type, SHA-256 match, sentinels, fallback-warning count) was captured as a screenshot retained session-locally outside the repository; the payload is never logged or committed.
- Resolved stack (as pinned and as reported by the harness panel): Rust `tauri =2.10.3` (`Cargo.lock`), `@tauri-apps/api 2.10.1`, `@tauri-apps/cli 2.10.1`, toolchain `stable-x86_64-pc-windows-msvc` (rustc 1.98.0) — the same MSVC toolchain the accepted SayIt stack pins.
- Numeric result: payload 9,438,418 bytes (9 MiB + 1234; even length; deliberately not an 8192-byte/4096-sample chunk multiple) returned by `tauri::ipc::Response::new(Vec<u8>)`; JS received `[object ArrayBuffer]` with `byteLength` exactly 9,438,418; SHA-256 expected === actual: `ebf783be13a56bd212a474803c6b8d6da391b10bc0cfc5721f9422c90c045750` (WebCrypto subtle over the raw buffer, no base64/number-array conversion); sentinels first/middle/last 7/190/86 (Rust) === (JS) with independent formula re-derivation; **fallback-warning count 0** (`console.warn` wrapped programmatically across the whole invoke window and restored on all paths; total warnings 0).
- Explicit statement: `customProtocolIpcFailed` / the postMessage fallback message **did not appear**. The custom-protocol IPC path delivered the raw bytes.
- Commands and exit codes: `npm install` (0), `npm test` — vitest 8/8 passed (0), `npm run build` — versions.json + vite (0), `cargo test` — 4/4 payload/hash unit tests passed (0), `npx tauri build --debug --no-bundle` (0). Packaged debug executable: `spikes/watch-binary-ipc/src-tauri/target/debug/spike-binary-ipc.exe`, SHA-256 `940a58476ac390998b352a73aa4a741f97c08866a799002685d2020e02c0abf7` (not committed).
- Environment findings recorded for the implementation slice: (1) the machine's rustup default is the **GNU** host, under which the `windows-*` crates fail with `dlltool.exe: program not found` — the harness pins `stable-x86_64-pc-windows-msvc` exactly like `client/src-tauri/rust-toolchain.toml`; any new Rust workspace in this repository needs the same pin. (2) A fresh lock resolution drifts past the accepted stack (e.g. `tauri-runtime-wry 2.11.4` breaks `tauri 2.10.3` compilation with an E0308 `new_window_handler` signature change); the harness lock pins the Tauri family to the accepted versions (`tauri-runtime`/`tauri-runtime-wry 2.10.1`, `tauri-utils 2.8.3`, `tauri-build 2.5.6`, `tauri-codegen`/`tauri-macros 2.5.5`, `wry 0.54.4`, `muda 0.17.1`, `tao 0.34.8`).

## Delivery 1B Z2 PM acceptance (2026-08-29) — PASS, contract MATCH

PM reviewed Z commit `d1f7c1fa6943e556d0957ec1bf7c73823193ba81` against accepted base `db988d43a70c6371dade1857c4d379b68737a5fc`.

- Scope passed: only the isolated `spikes/watch-binary-ipc/**` harness and the two submitted authority-document updates changed; no `client/**`, `watch/**`, Provider, Orchestrator, History, Paste, UI, accepted dependency lock, received WAV, token, executable, or build cache was committed. `git diff --check` was clean.
- Independent automation passed: `npm ci` (exit 0, zero vulnerabilities); `npm test` (Vitest 8/8, exit 0); `npm run build` (exit 0); `cargo test` (4/4, exit 0); `npm run spike:build` (exit 0). The two Rust dead-code warnings are confined to test constants and are non-blocking.
- Independent payload check passed: length 9,438,418; sentinels 7/190/86; SHA-256 `ebf783be13a56bd212a474803c6b8d6da391b10bc0cfc5721f9422c90c045750`.
- Independent packaged-native run passed: the freshly rebuilt Windows WebView2 panel reported a 115 ms invoke, `[object ArrayBuffer]`, exact byte length and hash, all sentinels/checks green, and zero total/fallback warnings. PM rebuild executable SHA-256 was `0fdf6bb7789885affca9257c91668a1dfb38f130c9d023c66d2b52706852219f` (not committed; a debug executable is not expected to reproduce Z's build hash bit-for-bit).
- Dependency evidence matched the frozen stack: `tauri 2.10.3`, `tauri-runtime-wry 2.10.1`, `tauri-utils 2.8.3`, `@tauri-apps/api 2.10.1`, and `@tauri-apps/cli 2.10.1`.

Decision: Z2 is **accepted** and the Provider contract upgrades from CONDITIONAL MATCH to **MATCH**. This proves only the PC runtime transport primitive; it does not complete Delivery 1B or the Galaxy Watch closed loop. The next and only unlocked slice is `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`. Watch UI remains a later slice.

## Delivery 1B Z3 external WAV ingress (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Scope: exactly the allowed surfaces — new `client/src-tauri/src/watch_receiver/admission.rs`, modified `watch_receiver/server.rs`, debug-only wiring in `main.rs`, new `client/src/services/watchIngress.ts`, additive `recorder/RecorderOrchestrator.ts`, thin delegation in `recorder.ts`, focused Rust + Vitest tests, and these two authority documents. No dependency or lockfile drift; Watch app/UI untouched; Z2 harness untouched.
- Rust (`admission.rs`): `AdmissionGate` with `Idle | Reserved{request_id, reserved_at}`, request-correlated bounded oneshots (admission 5 s, run-start ack 10 s), Rust-owned lazy 300 s lease with reclamation logging, stale-ID no-ops, idempotent request-conditional `abort`, `watch_gate_state()` snapshot, and `watch_read_reserved_pcm` returning the header-stripped data chunk over the Z2-proven raw binary IPC (re-validated with the same strict WAV parser; gated by the exact live request ID).
- Rust (`server.rs`): admission step inserted after WAV validation and **before** the durable save — `409` for `orchestrator_not_idle` / `provider_not_ready` / `already_reserved` / `bridge_timeout` (never touches disk; previous `received_watch.wav` preserved), `500` for capture/preparation failures; save failure emits `watch://run-abort` (`save_failed`) and aborts the gate; `watch://audio-ready` then a bounded `watch_run_started` acknowledgement precede `201` — which still means transport/run-start success only, never transcription status.
- Rust (`main.rs`): debug-only event-sink registration at the top of `.setup` (a request arriving before registration fails closed via the admission timeout) plus five `#[cfg(debug_assertions)]` entries in the existing `generate_handler!` list, which compile the whole match arms out of release builds. Necessity note: the admission module is mounted from `main.rs` via `#[path = "../watch_receiver/admission.rs"]` inside a `pub mod watch_admission`, so `watch_receiver/mod.rs` keeps its exact Delivery 1A shape (its release-guard tests pin `watch_receiver::start` and pass unchanged); the gate is a `OnceLock` shared between `ReceiverServer` and the commands, so no `.manage()` wiring was needed.
- TS (`watchIngress.ts`): thin fail-closed adapter — `watch://admission-request` → Phase A → Phase B (3 s budget) → `watch_admission_resolve`; `watch://audio-ready` → `watch_read_reserved_pcm` (ArrayBuffer type check) → `beginExternalRun`; `watch://run-abort` → correlated abort; boot `watch_gate_state()` reconciliation releases an orphaned gate (`orphaned_after_reload`). Handlers are injected by `RecorderOrchestrator.init()` (no import cycle).
- TS (`RecorderOrchestrator.ts`, additive): `externalAdmissionBlocker` / `tryReserveExternalRun` (Phase A: synchronous reserve + one-shot in-process capture) / `prepareExternalRun` (Phase B: bounded native capture into the same probe/app-context fields the mic path uses) / `beginExternalRun` (validate → reset run fields → per-run `connect(buildProviderCallbacks())` → re-verify ready → checked `start({runId, disableAi: true, no systemPrompt, …})` → `transition('recording')` → `watch_run_started` ack → exact-accounting feed (per-slice `byteLength / 2`, exact copied slices into `recordedChunks`, short final chunk, post-feed equality assertions **before** `provider.stop`) → sample-derived durations → shared finalize; <0.5 s discards like the mic path) / `abortExternalRun` (request-conditional, idempotent, clears both halves). Guards: PTT down ignores while a reservation exists; PTT up cannot hijack the external `recording` phase. `finishRun` releases the gate on every terminal path. `finalizeRecording` was extracted verbatim from the mic `stopRecording` tail (mic-only teardown stays behind; the external path never calls `stopCapture`).
- Evidence (commands, exit codes): `cargo test watch_receiver` **34/34** (0) — 12 new gate unit tests (accept/reject/correlation/double-resolve/stale-IDs/lease-reclamation/reload-state/abort-completes-pending-ack), 7 new receiver integration tests (busy 409 preserves the previous file; prepare-failure 500 writes nothing; bridge-timeout 409; full accept flow returns 201 only after ack; ack-timeout 500 + release; save-failure 500 + release + run-abort) plus the preserved malformed-WAV/truncation regressions and legacy 201 flows updated to the frozen contract (they now simulate the WebView decision + ack); full `cargo test` **156 passed / 0 failed / 4 ignored** (0); focused Vitest **28/28** (0) — reservation atomicity vs PTT, lifecycle order with no PCM before a successful start, exact short final chunk with byte-for-byte reconstruction, `sample_accounting_mismatch` abort before `stop`, `pcm_length_mismatch` entry validation, probe/context captured exactly once, AI forced off per run, History and Paste reached exactly once via `onFinal`, error cleanup with single gate release, stale-abort conditionality, and the mic-path regression after the extraction; full client Vitest **31 files, 0 failures** (0); `tsc && vite build` (0); `cargo build --release` (0) and the release-marker scan found **all** markers absent — the five Delivery 1A markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`) plus `watch_admission_resolve`, `watch_run_started`, `watch_run_aborted`, `watch_read_reserved_pcm`, `watch_gate_state`, `AdmissionGate`. `git diff --check` clean. No token, WAV, executable, build cache, model, APK, or local path was committed.
- Boundary: not VERIFIED end-to-end. The real Galaxy Watch → ASR → History → Paste ten-run acceptance (stage 8) stays locked until PM independently reviews this slice.

## Delivery 1B Z3 PM review (2026-08-29) — NO-GO, Repair 1 required

PM reviewed Z3 commit `dc1af55137c6048b2f170a7ffa167f83364daef9` against `07ffdb214d7a5da6e935ced19bc453f55124aeaf`. Scope and diff hygiene passed. Fresh PM evidence before the user paused the review: client Vitest 367/367 passed; TypeScript/Vite build passed; Rust passed 156/0/4 after adding the already-installed `C:\Program Files\CMake\bin` to that command process's PATH. The Release build was deliberately interrupted at the user's request and is not PM-verified.

Decision is **NO-GO** despite the green unit tests. Three Important runtime-contract defects remain: (1) Rust emits preformatted JSON as a `String`, so real Tauri delivers `event.payload` as a string while `watchIngress.ts` reads an object, causing the event to be ignored and the upload to end in `409 bridge_timeout`; tests use an object-only fake and miss this. (2) `server.rs` emits `watch://admission-request` before the gate registers `pending_admission`, so an immediate WebView response is stale and the receiver can time out; tests poll for the waiter and therefore mask the ordering race. (3) the shared Provider `onError` path calls mic-only `stopCapture()` whenever state is `recording`; an external Watch run also uses that state, so a feed-time Provider error violates the no-mic-capture contract and lacks the required dedicated regression test.

Only `docs/DELIVERY-1B-Z3-REPAIR-1.md` is unlocked. It authorizes the three Repair items first, then the versioned Watch UI candidate. Real-device closure remains locked until PM independently accepts both parts.

## Delivery 1B Z3 Repair 1 + Watch UI candidate (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Review base: `5da1a32279b372810d83504aca2021b0c8146763` (task `docs/DELIVERY-1B-Z3-REPAIR-1.md`). Part A (three blocking repairs) was completed and fully green before Part B (Watch UI) started, per the strict serial rule. NOT VERIFIED end-to-end: no real device this round; stage 8 stays locked.
- **Repair 1 (object payloads)**: `EventSink` now carries `serde_json::Value`; `server.rs` builds `watch://admission-request` / `watch://audio-ready` with `serde_json::json!` objects (`requestId`/`bytes`/`sampleCount`/`durationMs`) and `watch://run-abort` with `requestId`/`reason`; `main.rs`'s sink forwards the object so Tauri delivers a structured object — never a JSON string. Rust regression `event_payloads_are_json_objects_with_frozen_fields` asserts every sink payload is an object (serialized form starts with `{`, never `"}`) with the frozen field types; TS regression asserts a legacy string payload is ignored fail-closed (no reserve, no resolve).
- **Repair 2 (register-before-emit)**: gate API split into `begin_admission` (mutex checks + lease reclamation + waiter registration) -> emit -> `wait_admission` (bounded wait; fail-closed cleanup of exactly the current request). The same race closed symmetrically for the handoff: `begin_run_ack` registers the ack waiter BEFORE the `audio-ready` emit. Regression `sync_resolve_inside_emit_stack_is_accepted` resolves both decisions synchronously inside the emit call stacks — accepted, no stale, no timeout, no polling helper. Admission still precedes the durable save; `409` never overwrites `received_watch.wav`.
- **Repair 3 (no mic teardown for external errors)**: `onError` branches — when the failing generation is the external Watch run (request/run correlated), the mic-only teardown (`stopCapture`, listening ticker, system-mute restore) is skipped; the Provider session is cancelled (server reconnected), the shared error-history tail runs once, and `finishRun` releases the Rust gate exactly once. Mic behavior unchanged. Regressions: external mid-feed `onError` -> `stopCapture` 0 calls, gate-release invoke exactly once, no Paste, single History entry, orchestrator reusable; stale second `onError` duplicates nothing; mic-run `onError` still calls `stopCapture`.
- Allowed-list note: no change outside the allowed list was needed. The production emitter lives in `main.rs`'s setup (registered through `watch_receiver::server::set_event_sink`), so `watch_receiver/mod.rs` stayed byte-identical to its Delivery 1A shape; `watchIngress.ts` also needed no code change for Repair 1 — its adapter already ignores non-object payloads fail-closed (a regression test now pins that).
- **Part B (Watch UI, 0.2.0-dev.2, versionCode 2)**: three screens (developer config with masked token + Show/Hide, Ready with explicit health check and transport-only wording, Recording with sample-derived duration + Stop + Cancel-discard) and inline states (Uploading with app-driven keep-screen-on, Upload failed with retained WAV + Retry/Later, Pending-upload badge, explicit discard prompt before any new recording overwrites a retained WAV, brief "Uploaded to PC" that never claims transcription). Haptics: start/stop/success/failure only. No carousel dots; 480x480 round safe area; no new pages.
- Watch implementation only in the allowed files (`MainActivity.kt`, `ui/RecordingScreen.kt`, `ui/RecordingViewModel.kt`, `strings.xml`, `build.gradle.kts` version bump). A pure `WatchUiStateMachine` (no Android imports) lives in `RecordingViewModel.kt`, unit-tested by `WatchUiStateMachineTest` (9 tests) ; `RecordingSessionRetryTest` (3 tests) pins byte-for-byte retention. Existing 1A tests untouched and green.
- Design evidence: `design/watch-ui/0.2.0-dev.1-baseline/` (status `accepted-parent`, parent Git SHA `5da1a322...`, per-file SHA-256 of the pre-modification sources) and `design/watch-ui/0.2.0-dev.2-candidate.1/` (status `candidate`, README with changes/locks/deviations, seven 480x480 state previews, `SHA256SUMS`). Declared deviation: the previews are hand-authored layout renders — this environment has no emulator or attached watch, so photographic screenshots (baseline and candidate) are deferred to the PM/user device session, exactly like the real-device interaction acceptance.
- Verification (commands, exit codes): client focused Repair tests (0); full client `npm test` — 31 files / 370 passed (0); `npm run build` (0); `cargo test watch_receiver` 36/36 (0); full `cargo test` 158 passed / 0 failed / 4 ignored (0); `cargo build --release` (0); release-marker scan — all eleven markers absent (five Delivery 1A markers, five `watch_*` command names, `AdmissionGate`); `watch`: `gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` all successful (0), debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `77e614cf2ce8accdda61a8221c0501d9210bd1a976b1425985835c7ae1625cb5` (not committed; release APK also produced unsigned). `git diff --check` clean. No token, WAV, executable, build cache, model, APK, device info, or local path committed.

## Delivery 1B Z3 Repair 2 — Watch UI behavior & evidence (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Review base: `d3f17c5b3f990d0ef1e6e9f40b58d086a166a613` (task `docs/DELIVERY-1B-Z3-REPAIR-2.md`). PC-side Rust/TS product code untouched (the three accepted repairs stand as-is); all changes are in the allowed Watch files, tests, and the two design-evidence directories. Not VERIFIED end-to-end; no device this round.
- **必修 1 (Cancel race)**: new pure `RecordingRequestLatch` in `RecordingViewModel.kt` — `startRecording` opens a generation, `cancelRecording` invalidates it BEFORE `session.reset()/toReady()`, and the capture coroutine drops a late completion when `isCurrent(gen)` is false (no `recordingCompleted`, no auto-upload, no FAILURE write). Cancel deterministically ends READY with `wavBytes == null`. Tests: `RecordingRequestLatchTest` (4 — live-until-cancel, the exact late-completion scenario on a real `RecordingSession`, re-begin after cancel, generation 0 never current).
- **必修 2 (Retry reachable after Later)**: `retryPressed()` now accepts Pending-ready (`NONE` overlay + `pendingUpload` + READY screen) in addition to the failure overlay; the Pending badge is tappable and a wide `Retry upload` chip sits next to it on Ready. End-to-end regression `failure then later then retry from pending-ready reaches uploading with the same bytes` drives the machine and a real `RecordingSession` in lockstep (Failure → Later → Ready/Pending → Retry → Uploading) and asserts the retried upload consumes the exact original WAV; a no-pending retry is a verified no-op; the discard prompt is unchanged.
- **必修 3 (runtime controls = previews)**: all text actions are now Wear `Chip`s sized from a single `WatchUiMetrics` source — wide pills 340×52 dp (`Save & Apply`, `Check / Refresh`, `Retry upload`), Retry/Later as two 160×52 dp chips, Record/Stop as 52 dp circular buttons with ●/■ glyphs and captions; config rows and the token Show/Hide have ≥48 dp touch heights (`heightIn`/`height`). `WatchUiMetricsTest` (4) pins the invariants automatically (≥48 dp touch heights, pill widths and the 160+20+160 pair inside the 480×480 circular safe area). The candidate.2 previews are generated from these same numbers, so preview and runtime agree by construction; real round-screen touch/clip checks stay with the device gate.
- **必修 4 (copy + versioned evidence)**: the Uploading copy now states "The screen stays awake automatically." (no user responsibility). `candidate.1` is untouched; the new freeze is `design/watch-ui/0.2.0-dev.2-candidate.2/` (status `candidate`, version still 0.2.0-dev.2 / versionCode 2, README declaring all four fixes and the same non-photographic-preview deviation, seven regenerated 480×480 previews, `SHA256SUMS`). The invalid parent manifest is preserved and superseded by `design/watch-ui/0.2.0-dev.1-baseline-recovery.1/` — its `SHA256SUMS` was regenerated from the parent commit's Git blobs (`git show 5da1a322…:<path>`): the corrected `build.gradle.kts` hash is `8eee9944f1e9aa0464774fe183ae4e0e3d1861ff1381f9ae11bd55066fc38254` (the invalid manifest had recorded the dev.2 value `656dca86…`); the recovery README states the original baseline was ruled invalid/rejected evidence.
- **Fresh Cargo rerun (PM's FTK1011/MSB3491 blockage cleared)**: `cargo test` — 158 passed / 0 failed / 4 ignored (exit 0); `cargo build --release` (exit 0); release-marker scan — all eleven markers absent.
- Full verification (commands, exit codes): `watch` `gradlew testDebugUnitTest --rerun-tasks` — **67 tests / 0 failed** (exit 0; 57 prior + 10 new); `gradlew lintDebug assembleDebug assembleRelease` (exit 0); debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `a540941c771b89daafff73122ac2bef4bf4b231e768db40177757909d77c8f3e` (not committed; changed from the previous build because the sources changed); `client` `npm test -- --run` — 31 files / 370 passed (exit 0); `npm run build` (exit 0); `git diff --check` clean. SHA256SUMS recomputed for both design directories. No token, WAV, executable, build cache, model, APK, device info, or local path committed.
- Boundary: no real device, no ten-run closure; stage 8 stays locked pending PM re-review.

## Delivery 1B Z3 Repair 3 — Cancel concurrency, responsive UI, faithful previews (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Review base: `edcfcef9b460a4f107449d6bc38adf008616d814` (task `docs/DELIVERY-1B-Z3-REPAIR-3.md`). PC Rust/TS product code untouched; changes only in `ui/RecordingScreen.kt`, `ui/RecordingViewModel.kt`, the corresponding tests, `design/watch-ui/0.2.0-dev.2-candidate.3/**`, and these two authority documents. Not VERIFIED end-to-end; no device this round.
- **必修 1 (Cancel fail-closed for both late outcomes + visibility)**: `RecordingRequestLatch` is now fully atomic (`AtomicInteger` generation/settled, `AtomicBoolean` cancelled) and exposes a SINGLE completion coordinator — `settle(generation)` — which returns true exactly once per live generation and never for cancelled/superseded/already-settled/generation-0 IDs. Both ViewModel completion paths (normal return AND the catch branch) are gated on `settle`, so a Cancel drops a late exception exactly like a late success: session stays READY, no WAV, no upload, no stop vibration. `recordingActive` is now `@Volatile`. Tests: `RecordingRequestLatchTest` (7) — exactly-once, Cancel+late-normal, Cancel+late-EXCEPTION, re-begin, generation 0 (which caught a real settle bug on a fresh latch, now guarded), cross-thread cancel visibility via latch-join, and concurrent settles from two threads claiming exactly once. The tests drive `settle` itself — the same coordinator the ViewModel calls — with no duplicated decision logic.
- **必修 2 (no px/dp conflation, responsive)**: `WatchUiMetrics` no longer contains any 480 dp width assumption. Widths are parent-relative: wide chips `fillMaxWidth(1f)` of the padded parent, Retry/Later are two `weight(1f)` chips with a dp gap, heights/padding/circle remain dp. The Ready column scrolls (`verticalScroll`) when content exceeds the logical height. Long-copy actions left the circular buttons: Grant Mic, dialog OK/Cancel, and Keep-it/Discard-&-record are proper chips now. `WatchUiMetricsTest` (5) pins the responsive policy (min touch heights, fraction/weight values, no absolute screen-width arithmetic).
- **必修 3 (candidate.3 faithful)**: new `design/watch-ui/0.2.0-dev.2-candidate.3/` (candidate.2 untouched): every preview is an element-for-element render of its Composable — Ready (and Pending) now include the transport line, `Check / Refresh` chip, Settings link, Pending badge + `Retry upload` chip, Record circle with caption, and the bottom `StatusLine`, in runtime order; overlays draw the dimmed Ready content underneath exactly like the Compose overlay stack. The generator asserts programmatically that no text exceeds its container (the candidate.2 badge overflow class is now impossible to regenerate). README records candidate.2's rejection, the round's differences, parent/locks, the non-photographic deviation, and a dp→px mapping note (previews draw the logical dp layout on a nominal 1 dp = 1 px reference canvas; widths are fractions, so they scale with real density). `SHA256SUMS` covers all files.
- **baseline-recovery.1 re-verified**: all six entries recomputed from the parent commit's raw Git blob bytes (`git show 5da1a322…:<path>`) and matched — including `build.gradle.kts` `8eee9944…`. Not modified.
- Verification (commands, exit codes): `watch` `gradlew testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease` — **71 tests / 0 failed**, all tasks successful (exit 0; 67 prior + 4 new latch tests); debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `b1c73093900b56e044fcb77634bcb13513607c7ea4848a131876dca5c0aa8d11` (not committed); `client` `npm test -- --run` — 31 files / 370 passed (exit 0); `npm run build` (exit 0); `cargo test` — 158 passed / 0 failed / 4 ignored (exit 0); `cargo build --release` (exit 0); release-marker scan — **11 absent / 0 present**. `git diff --check` clean. No token, WAV, executable, build cache, model, APK, device info, or local path committed.
- Boundary: no real device, no ten-run closure; stage 8 stays locked pending PM re-review.

## Delivery 1A source/build evidence (Colleague D, 2026-08-29)

Toolchain used for the delivery-1A verification runs:

- JDK: Eclipse Temurin 17.0.20.1 (x64)
- Gradle: 8.10.2 (watch project wrapper)
- Android SDK: platform-tools 37.0.1, platforms;android-34, build-tools;34.0.0 (command-line tools installed outside the repository; no local paths committed)
- Kotlin 2.0.21, AGP 8.7.3, Wear Compose 1.4.1, compileSdk 34 / targetSdk 34 / minSdk 30
- Rust: repo toolchain pinned to stable x86_64-pc-windows-msvc (rustc 1.98.0 via rustup); Cargo.lock updated for `tiny_http = "0.12"`
- Node.js v24.16.0 (npm test / build unchanged)

Fresh verification runs (exit codes):

1. `cd watch; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0). 35 unit tests, 0 failures. Lint clean. Debug APK produced.
2. `cd client; npm test` — 29 files / 339 tests passed (exit 0).
3. `cd client; npm run build` — tsc + vite build OK (exit 0).
4. `cd src-tauri; cargo test` — 142 tests, 138 passed, 0 failed, 4 ignored (exit 0). Includes watch_receiver module tests (config, WAV parsing, HTTP server, atomic save).
5. Release checks: `cargo build --release` succeeds; the release binary contains none of the receiver markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `tiny_http`, `api/watch/audio`). Watch release APK manifest has no `usesCleartextTraffic`; debug APK manifest has it.

Debug APK: `watch/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `81B14FECBC5119300FE98433526CF848E28178878B925F0E85A15DB6FBF06944` (not committed to Git).

Receiver contract implemented in `client/src-tauri/src/watch_receiver/` (debug-only, `#[cfg(debug_assertions)]` gated in `main.rs`): env config fail-closed, `GET /api/health`, `POST /api/watch/audio` with exact Bearer auth, audio/wav content type, 10 MiB cap, RIFF validation, atomic `MoveFileExW` write-through replacement under `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.

Watch app implemented under `watch/` (Kotlin + Compose for Wear OS): 16 kHz native verification, dedicated I/O recording, canonical WAV writer, RFC1918-only destination validation, cleartext debug-only, `201` = transport success only.

## Delivery 1A real-device evidence (Colleague D, 2026-08-29) — D evidence pending PM acceptance

Device: Galaxy Watch 7 (SM-L310, Wear OS Android 16 / API 36, 480x480 round), connected via Wi-Fi ADB wireless debugging (no serial recorded; no tokens/APKs committed).

Watch app debug APK installed on device with RECORD_AUDIO granted; destination configured to the PC's LAN RFC1918 IPv4 `192.168.12.142:18099` with a 48-hex-char dev token (supplied via environment, not committed).

Receiver run: debug SayIt started with `SAYIT_WATCH_BIND_IP=192.168.12.142`, `SAYIT_WATCH_PORT=18099`, `SAYIT_WATCH_DEV_TOKEN=<48-char token>`; log line: `watch receiver listening on 192.168.12.142:18099 (dev token present: true)`. Windows firewall rule for TCP 18099 on the Private profile was created.

Observed on the watch (UI state machine): `Ready — 16 kHz verified` (AudioRecord min buffer > 0, STATE_INITIALIZED, actual sampleRate 16000) -> `Recording` -> `Recorded (N samples)` -> `Uploaded / transport verified`. `201 Created` mapped to transport success only; UI showed "Uploaded / transport verified", never "Transcribed".

Two real recordings were transported and durably saved:

| Run | Captured samples | WAV bytes | Duration | Received SHA-256 | Note |
|---|---:|---:|---:|---:|---|
| 1 | 223,360 | 44,764 header+PCM = 446,764 | 13.96 s | `700f4daa17357506167a35d6e84f68d473c21f56c0b7dd576ed202636665f6c9` | Quiet environment audio |
| 2 | 238,080 | 476,204 | 14.88 s | `39a80c7cfa210cbfe4bcac1aca8117e1096e5d3b3f6b9ca8195cd7f4b2b57077` | Speech (0 dBFS peaks; per-second RMS -56.8..-66.3 dBFS) |

Both files validated as PCM format 1, 1 channel, 16,000 Hz, 16-bit, block align 2, byte rate 32,000, even non-empty data, no truncation. `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav` is the durable accepted sample; no temp files remain after each upload. Desktop copies for PM playback: `received_watch_D-evidence.wav` (run 1) and `received_watch_D-evidence-voice.wav` (run 2).

Manual playback acceptance: on 2026-08-29 the user explicitly confirmed the human playback gate. Together with the independently checked file parameters and hash, Delivery 1A stage 5 is accepted. This acceptance covers the received audio only; it does not waive the source findings below or unlock Delivery 1B.

Upload HTTP response (from run-1-style PC self-test and run 2 device upload): `201 Created` with `requestId`, `bytes`, `sampleCount`, `audioDurationMs`, and lowercase hex `sha256` matching the saved file.

## PM review (2026-08-29) — NO-GO

Fresh PM checks confirmed the submitted APK and received WAV hashes match the handoff. The received file independently parses as PCM s16le, 16,000 Hz, mono, 14.88 seconds, 238,080 samples, 476,204 bytes; an automated scan detected no silence interval of 0.5 seconds or longer. The user subsequently confirmed the required human playback acceptance.

The source cannot be accepted yet:

- Watch destination and token rows are plain `Text`; the supplied `onChange` callback is never called, so the required settings cannot be entered through the Watch UI.
- Live duration is not sample-derived in the UI: `elapsedSec` is unused and `_sampleCount` is synchronized only after recording completes.
- A 48-hex-character token represents only 24 decoded bytes; current validators merely check string length and do not enforce the required 256-bit representation.
- Watch sends `X-Request-Id`, but the receiver discards it and creates another UUID, preventing reliable Watch-to-PC trace correlation.
- WAV chunk parsing walks to the physical file end even when the declared RIFF extent is shorter.
- `client/src-tauri/rust-toolchain.toml` changed outside the authorized path without necessity evidence and must return to the baseline declaration.

PM independently reran client Vitest (339 passed) and the client production build successfully. Android verification could not be independently reproduced in the PM shell because Java/JAVA_HOME is absent; Rust tests could not be reproduced there because CMake is absent. These environment blockers do not invalidate D's logs, but those logs are not independent PM proof.

Decision: do not merge, do not mark Delivery 1A accepted, and do not begin Delivery 1B. Colleague D receives only `docs/DELIVERY-1A-D-REPAIR-1.md`.

## Delivery 1A Repair 1 (Colleague D, 2026-08-29) — PM accepted

All six frozen discrepancies were repaired on `codex/review-watch-transport` (commit pushed; see `PROJECT_PROGRESS.md`). Focused diff per item:

1. **On-Watch editable settings**: `RecordingScreen.SettingsField` now opens a Wear dialog with a `BasicTextField` (IME `Done` action); values are committed on confirm. Proven on-device without ADB-pre-seeded prefs: PC IP `192.168.12.142`, port `18099`, and a 64-hex dev token were all typed through the real Wear keyboard and persisted (`watch-ev-*.png` screenshots).
2. **Live sample-derived duration**: `AudioCapture.record` now invokes `onProgress(cumulativeSamples)` after every read; `RecordingViewModel` publishes it to `_sampleCount`, and the status line renders `Recording <ms> ms` while recording (observed on-device increasing through ~3520 ms during a run that finished at 238,080 samples). Wall-clock time is never used as the source of truth.
3. **Frozen 64-hex Dev Token**: new `DevTokenValidator` (Watch) and `watch_receiver::config::validate_token` (Windows) both require exactly 64 hexadecimal chars after trimming (32 decoded bytes / 256 bits). Positive and negative tests cover 63/65 chars, non-hex, surrounding whitespace, and valid 64-char tokens on both sides.
4. **X-Request-Id end to end**: receiver validates `X-Request-Id` as a UUID (missing/invalid -> 400), echoes the same UUID in the 201 JSON, and includes it in the non-secret success log. Watch `TransportClient.verifySuccessResponse` accepts 201 only when the echoed `requestId` equals the sent UUID (mismatch/missing -> failure). Tests for mismatch/missing/invalid headers added.
5. **Strict RIFF bounds**: `wav::parse` now requires the declared RIFF extent to match the file extent (one valid container-pad byte tolerated) and bounds all chunk walking to the declared extent; a valid `fmt`/`data` beyond a shortened declaration is rejected (test added).
6. **Toolchain declaration restored**: `client/src-tauri/rust-toolchain.toml` reverted to `channel = "stable-x86_64-pc-windows-msvc"`. Reproducible evidence the baseline works: with `stable-x86_64-pc-windows-msvc` installed via rustup, `cargo --version` resolves 1.98.0 and `cargo check --tests`/`cargo test`/`cargo build --release` all succeed under the baseline declaration (the earlier failure was caused by the MSVC toolchain not being installed yet, not by the declaration).

Fresh verification for Repair 1:

- `watch`: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0), 45 unit tests / 0 failures, lint clean.
- `client`: `npm test` — 339 passed (exit 0); `npm run build` — exit 0.
- `client/src-tauri`: `cargo test` — 145 tests, 141 passed / 0 failed / 4 ignored (exit 0); receiver module tests 28 passed.
- Release checks: `cargo build --release` binary contains none of `watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`; Watch release APK has no `usesCleartextTraffic`, debug APK has it.
- Debug APK (rebuilt): `watch/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `1275C49D508A8E03AFD7E9D84F7EC668BE12332DE3E479152DDA38C20873E8D6` (not committed).

Repair-1 real-device evidence (D evidence): a new speech/ambient recording was captured on the Watch (238,080 samples, 14.88 s) and uploaded with a valid `X-Request-Id`; receiver logged `requestId=bb327d88-96a3-4af5-b700-c7eeeaeee942 bytes=476204 samples=238080 durationMs=14880` and durably saved `received_watch.wav` (SHA-256 `2929585a641a1f6d55d3aa715d8f21d153f67941742c5eb299fa135cd3de02d2`); desktop copy `received_watch_D-repair-voice.wav` for PM playback. PC-side self-tests confirmed 201 echoes the sent UUID and missing/invalid `X-Request-Id` return 400.

## Next executable step

The user personally transfers `docs/DELIVERY-1B-Z-CONTRACT-TASK.md` to colleague Z. Z freezes the real Provider/Orchestrator/History/Paste contract on `codex/review-watch-pipeline` and stops. PM reviews that document before issuing any implementation slice.

## PM acceptance (2026-08-29)

PM reviewed the Repair 1 diff against all six frozen findings and independently ran the available verification:

- Watch: unit tests forced to rerun, 45 passed / 0 failed / 0 skipped; lint, Debug APK and Release APK builds succeeded.
- Client: Vitest 29 files / 339 tests passed; TypeScript and Vite production build succeeded.
- Rust: 145 tests total, 141 passed / 0 failed / 4 pre-existing ignored; release build succeeded.
- Release safety: none of the five receiver markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`) occur in the release executable. Debug merged manifest enables cleartext; Release merged manifest does not enable it.
- Artifact evidence: the submitted repair APK hash was matched before the PM rebuild. A clean PM rebuild produced a new debug-artifact hash, as expected for a regenerated debug APK; it does not replace D's installed artifact identity.
- Device transport: the locally retained Repair 1 WAV hash matches D's report and independently parses as PCM s16le, 16,000 Hz, mono, 14.88 seconds, 238,080 samples, with no detected silence interval of 0.5 seconds or longer. The earlier human playback gate remains accepted.

D reported that IP, port and token were typed with the real Wear keyboard and that live sample-derived duration increased on device. The referenced screenshot files were not included in Git, so this portion remains explicitly classified as D production evidence rather than a PM-replayed attachment. It is consistent with the inspected source and the new device upload.

Decision: Delivery 1A **ACCEPTED**. Delivery 1B is unlocked but not started. No merge, release, installer, or Delivery 1B work was performed by this acceptance.

## Delivery 1A acceptance

- Debug Watch APK builds successfully and release configuration rejects cleartext transport.
- On a real Galaxy Watch 7, `AudioRecord` initializes at exactly 16 kHz, PCM 16-bit, mono.
- Authenticated upload reaches the explicitly configured Windows LAN IPv4.
- The receiver validates the WAV and atomically saves `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.
- Response is `201 Created` with request ID, bytes, sample count, duration, and SHA-256.
- PM manually plays the file and records: no truncation, no abnormal gaps, correct speed/pitch, correct duration, and usable speech.

## Delivery 1B guardrail

Delivery 1B may modify the external-audio ingress only after the Delivery 1A acceptance gate is recorded here. It must reuse `RecorderOrchestrator`, the active `TranscriptionProvider`, existing callbacks, History, and Paste; it must not create a second ASR path.

## PM status sync (2026-09-02) — security/package NO-GO

D reported security hardening, GitHub security settings, a silent Windows build and a refreshed portable Release. PM independently fetched and reviewed the current state:

- Before this PM documentation update, local and remote `main` matched at `032de349f0a8e929dfde995ae65b017741c8c047`; commits `83056ab` and `a7ddcac` contain the security changes, and D's submitted product tree was clean.
- GitHub Secret Scanning, Push Protection and Dependabot security updates are enabled. `main` rejects force-push and deletion. CodeQL completed successfully for `032de34`. One Dependabot PR is open for `python-multipart`; several Rust Dependabot jobs fail because the repository pins the Windows-only toolchain string `stable-x86_64-pc-windows-msvc`, which the Linux updater cannot resolve.
- Fresh PM client verification passes: Vitest 370/370, TypeScript/Vite production build, and `npm audit --omit=dev` with 0 vulnerabilities. Fresh Rust compilation remains blocked by the known external `transcribe-cpp-sys`/MSBuild `FTK1011` cache failure. Server pytest could not be collected in the PM shell because FastAPI is not installed; D supplied only `py_compile` evidence for the server edits.

The security/package claims are not accepted yet:

1. The backend now rejects protected HTTP and WebSocket routes whenever `SAYIT_API_TOKEN` is empty, even on loopback. This conflicts with the client defaults and UI copy that explicitly support `http://127.0.0.1:8000` with an empty token, so zero-config local-server behavior may be broken. The intended invariant is: loopback may be tokenless; non-loopback must fail closed without a token.
2. The diagnostics ZIP no longer includes raw `sayit.log`, but it still calls `read_and_parse_logs()` and serializes the resulting titles into `timeline.json`. A persisted `[WS] ... final {...asr_text/llm_text...}` line can therefore still carry transcript content into the ZIP. The claimed transcript redaction is incomplete.
3. `032de34` changes Windows builds to the GUI subsystem, but the published Release and desktop copy contain the earlier executable: Release ZIP SHA-256 `D0E483650651DB598068F40D81576C1E04529815F8AA3D8C6A24539D0488F98D`; packaged/desktop `sayit.exe` timestamp `2026-09-01 13:02:50`, SHA-256 `123FF8CB1144152119ECC4267459614C41040ABDAF1003CC4DCFC1302C4EE9F8`. The newly built target after the source change is timestamped `13:20:58`, SHA-256 `E064AE0CC0B6B69A7B46653CDFA16D08C5D1EBEBD31928066408EB2970E69A9B`. Therefore silent startup is present in source but not in the distributed/desktop artifact.
4. Backup import now has finite entry/single-file/total bounds, but no focused regression tests were added; the accepted limits also remain large (10,000 entries, 256 MiB single, 512 MiB total). This is retained as a non-blocking hardening follow-up once the two Important defects are repaired.
5. Receiver fallback still binds `0.0.0.0:18099`. This contradicts the older project hard boundary requiring one explicit RFC1918 address. D attributes the change to DHCP/IP-drift resilience; PM records it as an unresolved product/security exception rather than silently accepting it.

The optional Wear OS Tile attempt did not compile against the tried Tiles APIs and was reverted to a clean tree. No Tile implementation is currently delivered. At status-read time, Vite `1420` and the debug Receiver `18099` were listening; Vite is development infrastructure and is not a stable packaged runtime.

Decision: keep the already-pushed `main` as the factual remote state, but mark security and portable packaging **NO-GO / Repair 2 required**. Do not describe the current Release as containing `032de34`, and do not mark the optional Tile as implemented.

## Security

- Development tokens are generated outside the repository and supplied through environment configuration.
- Tokens, received audio, local SDK paths, signing material, and device identifiers must not enter Git, logs, screenshots, or handoff documents.

## Delivery 1B Z3 Repair 1 PM review (2026-08-30) — split decision / UI Repair 2 required

- Review target: `b8daef527acbdced401e73ec9be6eaee39373389` against base `5da1a32279b372810d83504aca2021b0c8146763`; branch `codex/review-watch-pipeline`; submitted tree was clean and `git diff --check` passed.
- PC Part A retained: the real Tauri sink now emits `serde_json::Value` objects; admission and run-start ack waiters are registered before their events; external Provider errors skip mic-only `stopCapture` and use the correlated shared cleanup. Fresh client evidence: 370/370 Vitest passed and production TypeScript/Vite build passed. The current Rust test executable ran 158 passed / 0 failed / 4 ignored; the existing Release exe was independently scanned and all eleven Watch/admission markers were absent.
- Rust build limitation: a fresh `cargo test`/Release rebuild did not complete in this PM run because the external `transcribe-cpp-sys` CMake/MSBuild cache failed at generated tracking logs (`FTK1011` / `MSB3491`). This is not presented as a product-code failure, but fresh Cargo compile evidence is required in the next Z return.
- Watch build evidence: unit tests forced to rerun, 57 passed / 0 failed / 0 skipped; lint, Debug APK, and Release APK builds passed. Debug APK SHA-256: `77E614CF2CE8ACCDDA61A8221C0501D9210BD1A976B1425985835C7AE1625CB5` (not committed).
- Watch UI decision: **NO-GO**. Cancel can race its late capture completion and end in Failure; Later leaves a Pending state with no reachable Retry; long text actions use Wear Compose Material 1.4.1 default circular 52 dp `Button` while candidate SVGs show wide pills; `Keep the watch on` conflicts with app-driven awake behavior; and the parent baseline hashes are not trustworthy (`build.gradle.kts` records the dev.2 working-file hash rather than the dev.1 parent blob). Candidate.1 and the invalid baseline remain untouched as audit evidence.
- Next task for colleague Z: `docs/DELIVERY-1B-Z3-REPAIR-2.md`. It fixes only Watch UI behavior and versioned evidence, freezes `candidate.2`, reruns all required checks, pushes, and stops. No real-device closure, merge, tag, release, or ten-run acceptance is authorized.

## Delivery 1B Z3 Repair 2 PM review (2026-08-30) — NO-GO / Repair 3 required

- Review target `d04b7c3b3fb93cb7ea01e742556fb0ae302dc9b0` against PM task base `d3f17c5b3f990d0ef1e6e9f40b58d086a166a613`; scope stayed within the allowed Watch/UI/test/design/docs paths, PC Rust/TS product code and frozen candidate.1 were untouched, and the submitted tree was clean.
- Retained evidence: Pending-ready Retry is reachable and retains the same WAV; Uploading copy is app-driven; candidate.2 manifest is internally consistent; `baseline-recovery.1` exactly matches SHA-256 of raw blobs from parent `5da1a322...`. Fresh PM runs: Watch 67/67 tests passed with `--rerun-tasks`; lint and Debug/Release APK builds passed; Debug APK SHA-256 `A540941C771B89DAAFFF73122AC2BEF4BF4B231E768DB40177757909D77C8F3E`; client Vitest 370/370 and production build passed.
- Rust limitation: fresh PM Cargo compilation still fails in the external `transcribe-cpp-sys` CMake/MSBuild cache with `FTK1011`; Z's reported fresh Rust run is retained as worker evidence only, not PM-reproduced evidence. PC code was unchanged in this slice.
- Decision: Watch UI remains **NO-GO**. Cancel's normal completion is gated, but the exception catch can still overwrite READY with FAILURE after Cancel; the latch and `recordingActive` have no explicit cross-thread visibility, and tests do not exercise the ViewModel completion/upload decision. Runtime layout incorrectly equates a 480 px watch with `480.dp`, using fixed 340/160 dp chips; default circular Buttons still hold long Grant/Discard/dialog labels. Candidate.2 visibly clips the Pending badge and omits runtime Check/Refresh and StatusLine, contradicting its runtime-parity claim.
- Next task: `docs/DELIVERY-1B-Z3-REPAIR-3.md`. Preserve PC code, baseline-recovery.1, candidate.1 and candidate.2; repair only Cancel concurrency, responsive Wear layout, and new immutable candidate.3. No merge, tag, release, device installation, or ten-run closure is authorized.

## Delivery 1B Z3 Repair 3 PM review (2026-08-30) — NO-GO / Repair 4 required

- Review target `332089a510cb06040f4863561301b9b5d5eaef51` against PM task base `edcfcef9b460a4f107449d6bc38adf008616d814`; allowed scope and frozen-version preservation passed.
- Retained: responsive fraction/weight widths replace fixed 340/160 dp; Ready scrolls; long Grant/Discard/input actions use Chips; both success and error call the same atomic settle gate; cross-thread stop flag is volatile; candidate.3 manifest passes. Fresh PM runs: Watch 71/71, lint and Debug/Release builds pass, Debug APK SHA-256 `B1C73093900B56E044FCB77634BCB13513607C7EA4848A131876DCA5C0AA8D11`; client 370/370 and production build pass.
- Rust limitation: fresh Cargo still fails at the external `transcribe-cpp-sys` MSBuild cache (`FTK1011`); PC source was unchanged.
- Decision: **NO-GO**. `settledGeneration` is not reset per begin, so only the first successful recording generation can settle. Cancel and settle remain separate multi-Atomic checks rather than one state transition, and Session mutation remains on the I/O context. Candidate.3 also fails full-size visual QA: clipped Pending status, missing Recording TimeText, and severe text collisions on translucent overlays.
- Next task: `docs/DELIVERY-1B-Z3-REPAIR-4.md`. Preserve all accepted PC/responsive/baseline work; repair only the multi-generation coordinator and candidate.4 readability. Real-device and ten-run stages remain locked.

## Delivery 1B Z3 Repair 4 PM acceptance (2026-08-30) — stage 7 accepted

- Review target `5de4d989e3af215fa77ae9abebbf64facf2e0307` against PM base `3de3aaa04bd1e5d0dbade3c53a6effabe0d62bde`. The user identifies the completing colleague as D; the commit retains the historical Z3 label. Allowed product scope passed; the extra `.gitignore` entry only excludes generated `watch/.kotlin/` cache and is accepted as benign hygiene.
- Runtime decision: accepted. One AtomicReference state now serializes begin/cancel/settle across generations; each begin supersedes old state, stale completion cannot claim a new generation, and recording I/O only produces an outcome. Session/UI/vibration/upload writes resume on the main coroutine after settle. Source inspection confirms the second and later generations are no longer blocked.
- UI decision: candidate.4 accepted for device validation. Responsive fractions, scrolling and long-action Chips remain. Pending uses one compact Retry action; Recording includes TimeText; upload overlays use opaque panels. PM rendered and inspected all seven 480×480 SVGs with no remaining overlap/cutoff; candidate.4 SHA256SUMS matches and candidate.1/2/3/baselines are preserved.
- Fresh PM evidence: Watch 75/75 tests, lint and Debug/Release APK builds passed; Debug APK SHA-256 `8F58722B018890AEF3BD0DF428AC31AB6F793952DC4515586D655062E21CB8EF`. Client 370/370 and production build passed. Current Rust artifact ran 158/0/4; fresh compile remains blocked locally by external `transcribe-cpp-sys` MSBuild `FTK1011`, with PC source unchanged.
- Non-blocking cleanup: tests use a test-only `RecordingOutcomeCoordinator` while the ViewModel applies the same latch/outcome sequence directly, so README wording overstates literal reuse; `isCurrent` performs two AtomicReference reads but is unused in production. These do not block the device gate and must not trigger another UI repair cycle before real-world evidence.
- Next and only unlocked task: `docs/DELIVERY-1B-D-DEVICE-ACCEPTANCE.md`. Delivery 1B remains NOT VERIFIED until real Galaxy Watch visual/interaction smoke plus 10 consecutive Watch→ASR→Paste runs with Stop-to-Paste median/P95 pass.

## Delivery 1B Z3 Repair 4 (Colleague Z, 2026-08-30) — awaiting PM re-review

Base: PM task commit `3de3aaa04bd1e5d0dbade3c53a6effabe0d62bde`; branch `codex/review-watch-pipeline`. Repaired exactly the two frozen blockers; PC Rust/TS, dependencies, lockfile, MainActivity, and every frozen design/baseline directory are untouched.

### 必修 1 — completion gate supports consecutive generations, atomic with Cancel

- `RecordingRequestLatch` rewritten as ONE atomic state machine (`AtomicReference<Phase>`: `Idle -> Active(gen) -> Settled | Cancelled`). `begin`, `cancel`, `settle` all CAS the same state — Cancel and completion are a single atomic transition; the old three-Atomic `settledGeneration` bug (never reset per begin, so gen2 could never settle) is gone.
- Every `begin()` creates a fresh independently-settlable generation; a late older generation can never claim the newer one (settle matches phase AND generation id).
- I/O only produces a `RecordingOutcome` (Completed/Failed) inside `Dispatchers.IO`; the session, UI, vibration, and upload writes happen on the main coroutine only after the `settle` gate — no cross-thread session mutation.
- New shared `RecordingOutcomeCoordinator.applyOutcome(gen, outcome, session)` is the single application path used by both the ViewModel and the coordinator tests.
- Tests (13 in `RecordingRequestLatchTest`) drive `applyOutcome`, not bare Atomics: gen1→gen2 consecutive settle; gen1 late can't occupy gen2; cancel-vs-completion single terminal state (cancel wins → READY, no WAV/Failure/upload); two consecutive rounds each complete+upload exactly once; real cross-thread cancel visibility and concurrent settle exactly-once; generation-zero rejected.

### 必修 2 — candidate.4 readable at full size

- `OverlayScaffold` now uses a FULLY OPAQUE background plus a solid rounded content panel: Uploading / Upload failed / Uploaded never show underlying Ready text, controls, or TimeText — no overlapping glyphs; "Upload failed" fully readable.
- The former `Pending upload` badge + duplicate `Retry upload` chip are merged into one obvious, clickable, ≥48 dp `Pending upload — Retry` chip; the retained WAV and explicit discard protection are unchanged.
- Every non-overlay screen (Config/Ready/Recording) renders its own runtime `TimeText` (the root-Box global was removed); overlays hide everything with the opaque panel — previews and runtime agree element for element.
- Ready (incl. Pending) and Recording scroll with the same first-screen semantics as the previews; Record / Retry / status stay fully inside the round safe area (geometry-checked).
- New immutable freeze `design/watch-ui/0.2.0-dev.2-candidate.4/` (README marks candidate.3 rejected and records parent/differences/inherited locks/non-photographic deviation/status; seven full-size 480x480 previews; SHA256SUMS). candidate.1/2/3 and baseline-recovery.1 untouched.

### Verification (commands, exit codes)

- `git diff --check` — clean (exit 0).
- `watch`: `gradlew testDebugUnitTest --rerun-tasks` — 75 tests / 0 failed (exit 0); `lintDebug assembleDebug assembleRelease` — all successful (exit 0). Debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `8F58722B018890AEF3BD0DF428AC31AB6F793952DC4515586D655062E21CB8EF` (not committed; release APK produced unsigned).
- `client`: `npm test -- --run` — 31 files / 370 passed (exit 0); `npm run build` (exit 0).
- `client/src-tauri`: `cargo test` — 162 tests, 158 passed / 0 failed / 4 ignored (exit 0); `cargo build --release` (exit 0); release-marker scan — all eleven markers absent (five Delivery 1A markers + five `watch_*` commands + `AdmissionGate`).
- candidate.4 preview geometry self-check: all 7 previews pass (no clipped text, no control outside the round safe area); overlay previews verified to contain no underlying Ready text; SHA256SUMS re-verified (all OK).

Remaining risks: previews remain hand-authored layout renders (non-photographic, as declared in README); real-device visual/interaction closure and the ten-run acceptance stay locked; PM Cargo re-run may still hit the external `transcribe-cpp-sys` FTK1011 cache issue on the PM host.

## Delivery 1B Galaxy Watch 7 device closure — NOT VERIFIED (2026-08-31)

Attempted per `docs/DELIVERY-1B-D-DEVICE-ACCEPTANCE.md`; **result NOT VERIFIED**. Full report: `docs/DELIVERY-1B-D-DEVICE-ACCEPTANCE-RESULT.md`.

Verified ready on device:
- Watch Galaxy Watch 7 SM-L310, Android 16 / API 36 (480×480, 226dp). Config page (IP `192.168.12.144`, port `18099`, masked 64-hex token), Ready page (transport line, Check / Refresh chip, Settings, Record circle, status line), and the opaque Upload-failed overlay all render correctly; recording → network send → receiver request → 409 WAV retention → Retry-without-re-record verified on-device. Candidate.4 UI has no overlap/clipping as inspected.
- PC: SayIt frontend renders (OCR-verified after removing a stale WebView2 data directory that had shown residual browser content); vite 1420 and receiver `192.168.12.144:18099` running; SenseVoice GGUF ASR ready (`GGUF ASR ready in ~340ms`).
- PC local POST of a valid WAV → receiver answered `409 bridge_timeout` — reproduced identically from the Watch and from a PC self-test, so the failure is independent of the Watch path.

Blocker (PC product runtime defect):
- Receiver emits `watch://admission-request` via `app_handle.emit`; the frontend `initWatchIngress` listener (`listen('watch://admission-request')`, registered unconditionally in `RecorderOrchestrator.init()` via `App.tsx` `useEffect` → `initRecorder()`) never receives it within the 5 s admission window → `bridge_timeout` → 409. The frontend renders and the dist bundle contains the admission code; the call chain is unconditional and unprotected. Tauri event delivery itself is the suspect (event name contains `://`, emit from a background receiver thread, or WebView event-channel timing) — requires PM/dev localization. PM's Z2/Z3 evidence came from an isolated harness, never from a full real SayIt run.
- Per the task hard boundary ("if a source change is required, stop and report only"), no product source was modified. Delivery 1B remains NOT VERIFIED; the ten-run acceptance and real-device closure stay locked.

## Delivery 1B dev.3 minimal Watch UI (D, 2026-08-31) — per DELIVERY-1B-D-WATCH-UI-MINIMAL-HANDOFF.md

Completed from the intentionally-dirty working tree on `codex/review-watch-pipeline` (baseline `178ab81e5da735d2284205f6ce4ca7e0a5819c05`); no reset/clean/re-generation. The pre-existing dev.3 minimal implementation (three screens only: Config/Ready/Recording, silent single upload) was reviewed and completed with the missing **Config startup rule**.

- Startup rule: a valid saved IP/port/token starts directly on Ready; first install or missing/invalid configuration starts on Config. Implemented in `WatchUiStateMachine.startupWith(configValid)` + ViewModel constructor init block (no MainActivity change — outside allowed scope).
- Config: only IP/Port/Token/Save & Apply; valid save → Ready immediately. Ready keeps a small Settings entry; health-check/upload failure never jumps to Config (silent, returns to Ready with `transportAvailable=false`).
- Silent upload: Stop → one whole-WAV upload, visible screen returns to Ready immediately; Record is a no-op while uploading; success or failure clears the WAV/session silently (no panel, no vibration, no retention) and restores recordability. Only start/stop haptics.
- Screens: Ready = large blue Mic; Recording = real-time sample-derived `mm:ss`, red Stop pill, compact Cancel; no `●/■` debug buttons, no upload/success/failure/pending/retry pages.

Verification (commands, exit codes):
- `gradlew testDebugUnitTest --rerun-tasks` — 75 tests / 0 failed (exit 0); added 3 startup-rule tests (valid→Ready, invalid/missing→Config, idempotent).
- `lintDebug assembleDebug assembleRelease` — all successful (exit 0). Debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `C0F3E1FDB59C4F9CB33B5BFAFF7C6705A066BBF454B448B00B06C11D111541AA` (versionCode 3 / 0.2.0-dev.3, not committed).
- `git diff --check` clean; candidate.2 SHA256SUMS recomputed and verified.
- Real-device screenshots (Galaxy Watch 7 SM-L310, Android 16/API 36): Config (fresh install, missing config), Ready (large blue Mic), Recording (live `mm:ss`, red Stop, Cancel) — session paths `%USERPROFILE%\toolchain\dev3-config.png`, `dev3-ready.png`, `dev3-recording.png`; OCR-verified with no `●/■` buttons and no upload/success/failure/pending/retry screens. PC `bridge_timeout` is intentionally left to a later independent task per the handoff; not re-attempted here.
- Allowed-scope check: only `RecordingScreen.kt`, `RecordingViewModel.kt`, `strings.xml`, directly-corresponding tests, `build.gradle.kts` (versionCode 3), candidate.1/2 design dirs, and this doc updated; TransportClient/Receiver/PC bridge/ASR/Provider/History/Paste untouched; the previously committed NOT-VERIFIED report is untouched.

## Delivery 1B single-flow closure and post-flow hardening (2026-08-31)

- Commit `412f1da` fixes the real `bridge_timeout` root cause: `ReceiverServer` snapshots `EVENT_SINK` at construction, so Receiver startup now occurs inside Tauri `.setup()` strictly after the real WebView sink is registered. PC local POST returned 201 and a real Galaxy Watch recording reached existing ASR and inserted text into the selected input target.
- Post-flow hardening marks Watch transport unavailable after any silent upload failure instead of retaining a stale available status, and adds a direct source-order regression that matches the concrete `set_event_sink(...)` and `watch_receiver::start()` calls.
- PM light review: client 370/370 and production build passed; Watch 75/75, lint and Debug build passed on the then-current tree; the current Rust test artifact ran 158/0/4. PM fresh Rust compilation still hit the external `transcribe-cpp-sys` MSBuild FTK1011 cache issue, so D's fresh Cargo build remains worker evidence.
- User will gather the formal ten consecutive runs and latency statistics during normal use rather than a dedicated session. Core flow is accepted for observation, not release/tag/deploy.
- The later white-dial UI experiment remains unaccepted with known visual/stop-interaction risks and is isolated on `codex/wip-watch-dial-ui`.

## Watch launcher icon 0.1.1-candidate.1 (2026-09-04)

- Recreated the user-selected reference as native Android vector resources: dark circular background, white microphone outline, and cyan-blue status dot. Screenshot whitespace was intentionally excluded for Wear OS circular-mask safety.
- Wired `android:icon` and `android:roundIcon` to adaptive icons; no Watch screens, transport, recording, ASR, or paste behavior changed.
- Immutable review package: `design/watch-icon/0.1.1-candidate.1/` (reference, SVG source, rendered PNG preview, README, and SHA-256 manifest).
- Verification: `gradlew testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0). Debug APK SHA-256: `E8F939E1835D00A2F6AF20D7727633E708087E24D4506ACC8664E96213CD7B77`.
- Status: candidate. Source/render/build verified; physical Galaxy Watch 7 launcher appearance is still pending and must not be reported as device-verified.

## Watch recording duration extension (2026-09-07)

- User reports recording ends while thinking aloud. Source evidence: `RecordingViewModel.startRecording` default was 15 seconds; `AudioCapture` stops on elapsed duration, with no silence-based stop in that loop.
- Raised the default to 180 seconds; manual Stop/Cancel unchanged. Three minutes at 16 kHz / 16-bit mono produces 5,760,044 WAV bytes, below the existing 10 MiB receiver cap.
- App version: `0.2.0-dev.4`, versionCode 4. Only default duration and version changed.
- `gradlew testDebugUnitTest assembleDebug --console=plain` passed (exit 0); targeted `git diff --check` passed. APK: `watch/app/build/outputs/apk/debug/app-debug.apk`; SHA-256 `791AEA6A8FF934B23C3B869BFDB4C6A6037F36E12D9AC53C086DF742EE65ADE8`.
- Installed on Galaxy Watch 7 SM-L310 via `adb install -r` (Success, existing app data retained). Device package readback confirms versionCode 4 / `0.2.0-dev.4`; MainActivity launch succeeded. Long-audio recording/ASR/Paste acceptance remains pending user use.
