# Delivery 1C 自动发现回归：1C-D-04@R1

状态：未派发，已由 `docs/DELIVERY-1C-D-DISCOVERY-REGRESSION-R2.md` 取代。上一相关交付为 Delivery 1C Repair 3（`docs/DELIVERY-1C-D-REPAIR-3.md`）；本轮变化是台式机和笔记本切换后的真实失败证据，不能把 Repair 3 的本机成功等同于此场景通过。

## 目标与当前证据

目标：定位并修复 Galaxy Watch 在同网段、手动地址可连接时仍显示“自动发现失败／未发现电脑”的问题。D 是本轮唯一产品源码写入者；PM 负责真机复现与最终验收。

2026-09-19 PM 已核对：桌面运行的 Debug `sayit.exe` 哈希为 `D3AA7F8941E04B614D3037E7BB87DD4F205AAA5C40996D0484309A0D54B2D3DE`；接收端监听 `0.0.0.0:18099`，本机经 WLAN 地址访问 `/api/watch/discovery` 返回预期 401；同一次运行日志出现真实 `watch discovery announcement sent`。用户确认在手表手动填写当前台式机地址后可连接，但自动发现失败。当前桌面日志未观察到这次失败对应的 Watch 认证探针；日志里有 PM 自己的未授权 HTTP 检查，不能将它算作 Watch 请求。手表 ADB 当前无在线设备，无法读取本轮 NSD 回调。

因此仅能定位到发现／候选／探针边界；尚不能定性为 Windows 防火墙、mDNS 组播、Android NSD、Token 或具体代码缺陷。`AndroidNsdDiscovery.kt` 当前只记录浏览开始／停止及失败，不记录 found/resolved 成功或候选拒绝原因；`onServiceResolved` 只取 `serviceInfo.host?.hostAddress`，以及服务类型的严格比较，都是待验证假设，不是已确认根因。已保存的旧地址若仍能认证，会按既有契约直接使用而不触发浏览；双 PC 同时运行时不要把这个预期行为误报为 mDNS 故障。

## 允许范围与安全边界

优先只改 `watch/app/src/main/java/com/sayit/watch/net/AndroidNsdDiscovery.kt`、`Discovery.kt`、`DiscoveryCoordinator.kt` 及其对应 `watch/app/src/test/**`。如证据表明需要 UI 状态呈现，先向 PM 报告具体路径及原因；不得自行扩大到录音、上传、Provider/ASR、History、Paste、token 存储、Windows receiver、依赖或发布路径。保持 Debug-only、DNS-SD 类型、TXT、Bearer 认证、0/1/多个认证目标策略、3 秒旧地址探针＋8 秒浏览窗口、一段音频最多发送一次的原契约。不得改防火墙、网络类别、手表应用数据、Token 或安装运行中的桌面软件；不得把 Token、原始服务主机名、用户信息、音频、精确地址写入日志或交接。不要 reset/clean/覆盖未跟踪项目文件、push、merge、tag 或 release。

## 工作与验收

1. 先给 Watch 发现链路加入最小、限量且不含敏感值的阶段诊断：是否进入旧地址探针、是否开始浏览、服务 found/resolve 成功或失败的数量、候选为何被拒绝（固定类别）、认证探针是否发起及其结果类别、最终为什么兜底。不要输出原始 exception message、Token、IP、主机名或整条 HTTP 请求。正常路径避免每轮大量日志。
2. 用纯 Kotlin/fake 覆盖已保存地址失效→浏览→一个认证目标、服务已 found 但候选因地址／类型被过滤、多个目标、浏览启动失败与超时、取消后迟到回调。若能在 Android 侧证明单一 `host` 丢弃可用 IPv4 等具体问题，给出最小修复与针对性回归测试；若不能证明，只交付诊断候选，不猜测修复。
3. 编译 Debug APK 并运行相关 Watch 单测／lint。回传命令、退出码、测试结果、APK SHA-256、改动文件前后 SHA-256 与回滚办法。不得安装到手表；PM 会在同一 Wi-Fi 上复现并核对 Watch 日志与 Windows 探针日志后独立验收。
4. PM 核心验收：手动地址继续可连接；保存旧地址失效而桌面在线时，8 秒窗口内自动发现并认证进入 Ready；桌面离线时按时兜底；两个同 Token 电脑并存时不得静默选错。若证据显示路由器隔离、Watch 未收到广播等外部因素，报告边界，不得将其写成代码修复完成。

回传固定三栏：`变更文件`、`验证结果`、`待审问题`。清楚区分已证实根因、已修复行为、只增加可观测性和未通过的真机条件。交付后停止源码写入。
