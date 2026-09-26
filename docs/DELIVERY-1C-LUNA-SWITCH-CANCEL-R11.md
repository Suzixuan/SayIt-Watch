# 1C-D-04@R11：取消切换后恢复连接显示

2026-09-24，用户明确指定原生 Luna 修复；上一版 R10。产品基线 91ad403，交接头 29a3099。PM 保留审核、构建部署和真机验收责任。

## 问题与验收

用户真机反馈：已连接时进入切换电脑，再返回，主界面持续显示正在连接，实际仍可连通。静态假设：browseForSwitch 设置 Searching，取消跳过后续状态恢复，而 dismissSwitchPicker 只恢复目标。必须用真实 ViewModel 链先复现再修复，不能只检查可录音布尔值。

- 搜索过程中返回及搜索完成后返回：保留当前目标，连接显示不残留 Searching。
- 旧搜索回调不得覆盖取消后的状态或新一轮状态。
- 没有已认证目标时不得假显示已连接；原自动恢复继续工作。
- 主界面、麦克风、设置布局、录音和上传行为不变。

## 执行范围

Luna 为唯一产品写入者，允许 RecordingViewModel.kt、DiscoveryCoordinator.kt（ResolverBridge）、ConnectionTaskOwner.kt 及相关 JVM 回归测试。其他路径须先返回 PM 核对。禁止 Windows、NSD 类型规则、安全门槛、Token、ASR、History、Paste、视觉冻结资源变动；不安装、不发布、不提交。PM 独立复验后收尾。

回传根因、修改路径、修复前失败及修复后通过证据、定向测试结果和限制。回滚仅恢复本轮明确产品差异，保留 PM 未提交 HANDOFF 记录。

## PM 验收进展（2026-09-25）

源码范围通过。PM 收尾增加 build.gradle.kts 的 dev.4/code 8 版本标识，并补齐 R8 测试等待 UI 投影的条件；没有删除或放宽原断言。全量 testDebugUnitTest、lintDebug、assembleDebug 退出 0，235 项零失败，lint 0 error / 37 warnings。APK 哈希、设备安装及待验状态以 HANDOFF.md 顶部为准。初次全量失败及后续修正均保留记录；此前 Luna 的修复前红灯日志本轮未取得，不声称独立复现旧版红灯。
