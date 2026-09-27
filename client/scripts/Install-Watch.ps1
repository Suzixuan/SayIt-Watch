[CmdletBinding()]
param(
    [string]$Device,
    [string]$AdbPath,
    [string]$ConfigPath = (Join-Path $env:LOCALAPPDATA 'com.sayit.app\watch-receiver.config.json')
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.sayit.watch.debug'
$apkPath = Join-Path $PSScriptRoot 'SayIt-Watch.apk'

if (-not $AdbPath) {
    $bundledAdb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $bundledAdb) {
        $AdbPath = $bundledAdb
    } else {
        $command = Get-Command adb.exe -ErrorAction SilentlyContinue
        if ($command) { $AdbPath = $command.Source }
    }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath)) {
    Write-Host '未找到 adb。请先下载并解压 Android SDK Platform-Tools：' -ForegroundColor Yellow
    Write-Host 'https://developer.android.com/tools/releases/platform-tools'
    Write-Host '然后把 platform-tools 文件夹放到本安装包目录，再重新双击 2-Install-Watch.cmd。'
    exit 2
}
if (-not (Test-Path -LiteralPath $apkPath)) { throw "安装包中缺少 $apkPath" }
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw '电脑端配置不存在。请先双击 1-Setup-PC.cmd。'
}

$config = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
$token = "$($config.devToken)".Trim().ToLowerInvariant()
if ($token -notmatch '^[0-9a-f]{64}$') { throw '电脑端 Token 无效，请先重新运行 1-Setup-PC.cmd。' }

function Get-ConnectedDevices {
    return @(& $AdbPath devices |
        Select-Object -Skip 1 |
        ForEach-Object {
            if ($_.Trim() -match '^(\S+)\s+device$') { $Matches[1] }
        })
}

$devices = @(Get-ConnectedDevices)
if (-not $Device -and $devices.Count -eq 1) { $Device = $devices[0] }
if (-not $Device -and $devices.Count -gt 1) {
    Write-Host '检测到多个 ADB 设备：' -ForegroundColor Cyan
    for ($index = 0; $index -lt $devices.Count; $index++) { Write-Host "  $($index + 1). $($devices[$index])" }
    $choice = [int](Read-Host '请输入手表序号')
    if ($choice -lt 1 -or $choice -gt $devices.Count) { throw '设备序号无效。' }
    $Device = $devices[$choice - 1]
}

if (-not $Device) {
    Write-Host '手表：设置 → 开发者选项 → 无线调试 → 使用配对码配对设备。' -ForegroundColor Cyan
    $pairEndpoint = (Read-Host '输入“配对”页面显示的 IP:端口（直接回车表示已配对）').Trim()
    if ($pairEndpoint) {
        if ($pairEndpoint -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d+$') { throw '配对地址格式无效。' }
        $pairCode = (Read-Host '输入手表显示的六位配对码').Trim()
        & $AdbPath pair $pairEndpoint $pairCode
        if ($LASTEXITCODE -ne 0) { throw 'ADB 配对失败。' }
    }
    Write-Host '返回“无线调试”上一页；连接端口通常与配对端口不同。'
    $Device = (Read-Host '输入该页显示的连接 IP:端口').Trim()
    if ($Device -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d+$') { throw '连接地址格式无效。' }
    & $AdbPath connect $Device
    if ($LASTEXITCODE -ne 0) { throw 'ADB 连接失败。' }
}

& $AdbPath -s $Device get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw "手表未连接：$Device" }

Write-Host '正在安装 SayIt Watch（保留已有应用数据）……' -ForegroundColor Cyan
& $AdbPath -s $Device install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK 安装失败。' }
& $AdbPath -s $Device shell am force-stop $packageName | Out-Null

$existingPrefs = (& $AdbPath -s $Device exec-out run-as $packageName cat shared_prefs/sayit_watch_debug.xml 2>$null | Out-String)
if ($existingPrefs -notmatch 'name="dev_token"') {
    $xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>`n<map>`n    <string name=`"dev_token`">$token</string>`n</map>`n"
    $oldOutputEncoding = $OutputEncoding
    try {
        $OutputEncoding = New-Object Text.UTF8Encoding($false)
        $xml | & $AdbPath -s $Device exec-in run-as $packageName sh -c 'mkdir -p shared_prefs; cat > shared_prefs/sayit_watch_debug.xml; chmod 600 shared_prefs/sayit_watch_debug.xml'
    } finally {
        $OutputEncoding = $oldOutputEncoding
    }
    if ($LASTEXITCODE -ne 0) { throw '首次 Token 写入失败。' }
    Write-Host '首次连接 Token 已安全写入手表应用私有配置。' -ForegroundColor Green
} else {
    Write-Host '检测到手表已有连接配置，已保留原 Token、电脑名称和历史设置。' -ForegroundColor Green
}

& $AdbPath -s $Device shell pm grant $packageName android.permission.RECORD_AUDIO 2>$null | Out-Null
& $AdbPath -s $Device shell am start -n "$packageName/.MainActivity" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'SayIt Watch 启动失败。' }

Write-Host ''
Write-Host '手表端安装完成。保持电脑和手表在同一 Wi-Fi，数秒后应显示“已连接电脑”。' -ForegroundColor Green
Write-Host '若一直未连接：先确认 Windows 防火墙只允许专用网络，再在手表“连接设置”里检查 Token。'
