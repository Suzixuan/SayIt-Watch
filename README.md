<div align="center">

<img src="docs/images/readme/watch/watch-logo.svg" width="88" height="88" alt="SayIt Watch 图标：麦克风与蓝色指示点">

# SayIt Watch

**抬腕说话，电脑把文字写进当前输入框。**

Galaxy Watch 负责录音和选择电脑；Windows SayIt 继续负责语音识别、AI 整理、History 与文字插入。

当前开发快照：**Watch 0.3.0-dev.13 / code 17**

</div>

<div align="center">

<img src="docs/images/readme/watch/ready-connected-r4.png" width="220" alt="Galaxy Watch 7 真机：SayIt 已连接电脑">
&nbsp;
<img src="docs/images/readme/watch/picker-dev13.png" width="220" alt="Galaxy Watch 7 真机：两台电脑选择器">
&nbsp;
<img src="docs/images/readme/watch/customize-dev13.png" width="220" alt="Galaxy Watch 7 真机：电脑自定义界面">

<p><em>Galaxy Watch 7 真机画面：Ready、双电脑选择与本地自定义。</em></p>

</div>

## 这个项目是什么

Windows SayIt 来自开源项目 [crosswk/SayIt](https://github.com/crosswk/SayIt)，使用 [AGPL-3.0](LICENSE) 许可证。原项目提供 Windows 客户端、语音识别、AI 整理、History 和向当前输入框插入文字等核心能力；本仓库在其基础上保留既有安全修复，并增加 Galaxy Watch 7 录音入口、局域网发现和多电脑选择。

它不是上游官方发行版，也不是另一个手腕 AI 助手。它只把离手最近的麦克风变成 SayIt 的一个入口：

```text
Galaxy Watch → 同一可信 Wi-Fi → Windows SayIt
             → 现有 ASR / AI 整理 → History → 当前输入框
```

手表只确认录音已交给电脑，不会把“上传成功”伪装成“文字已经插入”。**目标输入框里真正出现文字，完整链路才算成功。**

## 从最初版本到 dev.13

### 1. 手表录音与完整音频传输

- Galaxy Watch 上点麦克风开始录音，结束后发送一段完整 WAV。
- 音频保持 16 kHz、16-bit、单声道；显示时长按实际采样数计算。
- 支持录音取消、上传失败后的保留/重试边界和前台防熄屏。
- Windows 继续使用既有 Provider、ASR、AI、History、Paste 和目标窗口跟踪链路。

### 2. Debug-only 安全接收链

- Watch 使用 Bearer Token 和 requestId 发送音频。
- Token 只保存在用户自己的设备中，不进入 mDNS、截图、日志或 Git。
- 接收端只用于 Debug，绑定明确的 RFC1918 局域网 IPv4；正式 Release 不启用当前明文 HTTP 入口。
- 请求先经过 WAV、认证和接收状态校验，再进入现有桌面处理链；不会因地址重找自动重发同一段音频。

### 3. 自动发现与前台恢复

- Windows Debug 接收端通过标准 DNS-SD/mDNS 广播 `_sayit-watch._tcp`。
- Watch 启动时先短探针检查上次电脑；地址失效时执行最多 8 秒的认证发现。
- 电脑晚启动、应用回到前台或上次 IP 因 DHCP 改变时，可以重新发现并恢复 Ready。
- 路由器隔离 mDNS 或发现失败时，仍保留 Token、手动 IP 和端口兜底。

### 4. 双电脑即时选择

- 「设置 → 切换电脑」同时显示当前电脑和其他已认证电脑。
- 候选电脑完成认证后立即出现在列表中，不必等整个搜索窗口结束。
- 搜索仍继续收集其他电脑；只有用户明确点选后才切换，不按发现顺序猜测。
- 主名称使用可读标签，短 IP 和在线状态作为次要信息；当前电脑用颜色和勾选双重标记。

### 5. 打开页面期间的实时在线列表

- 选择器可见且应用在前台时，连续运行已有的有界认证浏览。
- 新启动的电脑端通过认证后自动加入。
- 非当前电脑若既未被发现、又无法通过确认探针，会在一轮刷新后移除。
- 当前电脑离线时不会突然消失，而是保留并显示「未连接」；恢复后原位回到在线。
- 关闭页面、退到后台、选择电脑、进入录音或销毁 ViewModel 时立即停止刷新，不做常驻高频扫描。

### 6. 电脑本地昵称与圆屏交互

- 每台电脑左侧有铅笔角标；点图标或长按卡片正文进入「自定义电脑」。
- 普通点卡片正文仍然只负责切换电脑，编辑和切换不会抢同一个触控事件。
- 可以保存 1–16 字符本地昵称或恢复 `电脑 · <IP 末段>`。
- 昵称只存于 Watch 私有设置，并按已认证的 `IP:port` 隔离；当前还没有稳定设备 ID，因此 DHCP 改址后不会自动迁移旧昵称。
- 列表和自定义页使用固定的底部居中关闭键，滚动区域在它上方结束，不再被圆屏边缘或按钮遮挡。

## 当前界面

<div align="center">

<img src="docs/images/readme/watch/recording.png" width="220" alt="Galaxy Watch 7 真机：录音中">
&nbsp;
<img src="docs/images/readme/watch/name-edit-dev13.png" width="220" alt="Galaxy Watch 7 真机：编辑电脑名称">
&nbsp;
<img src="docs/images/readme/watch/settings-r3.png" width="220" alt="Galaxy Watch 7 真机：设置菜单">

<p><em>录音页、名称编辑页和设置入口。</em></p>

</div>

## 怎么使用

1. 在可信的同一 Wi-Fi 下运行本仓库的 Windows **Debug** 接收端。
2. 首次使用时，在 Watch「连接设置」中输入电脑端生成的 64 位十六进制 Token。
3. Watch 自动发现并认证电脑，进入 Ready。
4. 在 Windows 上先点好准备接收文字的输入框。
5. 点 Watch 中央麦克风开始录音，说完结束；等待 Windows 完成识别并写入。
6. 有多台电脑时进入「设置 → 切换电脑」。点卡片切换；点铅笔或长按卡片自定义名称。
7. 自动发现不可用时，再在「连接设置」里填写手动地址。

## 最新验证状态

已验证：

- Galaxy Watch 7 单电脑自动发现、认证、前台恢复和 Ready 显示。
- 一段真实 Watch WAV 进入 Windows 现有识别链并写入目标输入框。
- 两台电脑同时在线时即时出现、显式 `.142 → .153 → .142` 切换。
- dev.13 真机图标点按进入当前电脑自定义页。
- dev.13 真机长按另一台电脑进入正确自定义页，且不会误切当前目标。
- 名称字段能打开编辑界面与三星输入法；列表和自定义页的底部关闭键不遮挡内容。
- 自动化：27 suites / 246 tests / 0 failures / 0 errors；lint 0 error。

仍待完成：

- 用三星 T9 手动输入真实昵称后，确认保存、列表即时更新和恢复默认的完整手动体验。
- 真实关闭/重开第二台 Windows 软件，确认选择器中的自动消失/重新出现时序。
- 两台电脑各完成一条真实录音路由，以及后续重复/连续运行验收。
- 正式发布链路；当前 Watch HTTP 接收/发送能力仍是 Debug-only。

详细证据和未完成项见 [HANDOFF.md](HANDOFF.md)、[PROJECT_PROGRESS.md](PROJECT_PROGRESS.md) 与 [dev.13 真机证据](docs/evidence/1C-PM-UI-02-R5/README.md)。

## 从源码继续

```powershell
git clone https://github.com/Suzixuan/SayIt-Watch.git
cd SayIt-Watch
git switch codex/watch-connection-r7
```

开始前先读：

- [AGENTS.md](AGENTS.md)：项目硬边界与验证要求
- [HANDOFF.md](HANDOFF.md)：当前实现、设备证据和下一步
- [PROJECT_PROGRESS.md](PROJECT_PROGRESS.md)：唯一权威进度表

Watch 构建需要 JDK 17 和 Android SDK：

```powershell
cd watch
.\gradlew.bat testDebugUnitTest --rerun-tasks lintDebug assembleDebug
```

Debug APK 会生成在 `watch/app/build/outputs/apk/debug/`，但 `*.apk` 已被忽略，不进入 Git 历史。凭证、`local.properties`、设备序列号、录音、模型、安装包和构建缓存都必须留在本机。

本开发快照没有对应的正式 Windows 安装包或 GitHub Release；仓库中的旧便携产物不代表当前 dev.13 源码。

## 许可证与来源

- 上游：[crosswk/SayIt](https://github.com/crosswk/SayIt)
- 许可证：[GNU Affero General Public License v3.0](LICENSE)
- 本仓库不是上游官方发行版

更新记录见 [CHANGELOG.md](CHANGELOG.md)。在另一台电脑继续前使用 `git pull --ff-only`；Token 和本机配置需要重新设置，不随 Git 同步。
