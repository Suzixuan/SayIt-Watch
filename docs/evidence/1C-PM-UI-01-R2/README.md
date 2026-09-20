# 1C-PM-UI-01@R2 real-watch UI evidence

- `ready.png`: PM screenshot from the installed Debug APK on the 480×480 Galaxy Watch. Compared with `design/watch-ui/0.2.0-dev.3-final/ready.png`: title and microphone are back at the original central positions; the added neutral `已连接电脑` status and low-key `设置` are visible, with no large switch button.
- `settings-menu.png`: PM screenshot after tapping Settings. `切换电脑`, `连接设置`, and `取消` are all visible and not cut off. This is the real Compose output, superseding the illustrative candidate.1 menu preview.
- APK installed using `adb install -r` (Success; app data retained), local APK SHA-256 `9557f6c5811c8283e349cade7110d0e93cda4ae13f7024b3e27784515cbace03`.
- From this menu, PM opened the existing switch picker. `browse:started` at 00:32:41, `browse:stopped` and `browse:no-candidate` at 00:32:49; cancel returned to Ready with the prior authenticated connection intact. The laptop was not online, so no second-computer discovery/adoption was established.
- No Token, device serial, received audio, or user transcript is present in these two screenshots.
