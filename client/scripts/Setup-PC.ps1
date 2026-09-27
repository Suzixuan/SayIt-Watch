[CmdletBinding()]
param(
    [switch]$StartSayIt,
    [switch]$GenerateNewToken,
    [switch]$NoClipboard,
    [string]$BindIp,
    [string]$ConfigPath = (Join-Path $env:LOCALAPPDATA 'com.sayit.app\watch-receiver.config.json')
)

$ErrorActionPreference = 'Stop'

function Test-PrivateIpv4([string]$value) {
    $parsed = $null
    if (-not [Net.IPAddress]::TryParse($value, [ref]$parsed)) { return $false }
    if ($parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { return $false }
    $bytes = $parsed.GetAddressBytes()
    return $bytes[0] -eq 10 -or
        ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
        ($bytes[0] -eq 192 -and $bytes[1] -eq 168)
}

function New-DevToken {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Read-PrivateIpv4 {
    $candidates = @(Get-NetIPConfiguration -ErrorAction SilentlyContinue |
        Where-Object { $_.NetAdapter.Status -eq 'Up' -and $_.IPv4DefaultGateway } |
        ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { Test-PrivateIpv4 $_ } |
        Sort-Object -Unique)

    if ($candidates.Count -eq 1) { return $candidates[0] }
    if ($candidates.Count -gt 1) {
        Write-Host '检测到多个局域网地址：' -ForegroundColor Cyan
        for ($index = 0; $index -lt $candidates.Count; $index++) {
            Write-Host "  $($index + 1). $($candidates[$index])"
        }
        $choice = Read-Host '请输入序号'
        $number = 0
        if ([int]::TryParse($choice, [ref]$number) -and $number -ge 1 -and $number -le $candidates.Count) {
            return $candidates[$number - 1]
        }
    }

    while ($true) {
        $manual = Read-Host '请输入电脑在当前 Wi-Fi 下的 IPv4（例如 192.168.1.20）'
        if (Test-PrivateIpv4 $manual) { return $manual.Trim() }
        Write-Host '必须是 10.x、172.16–31.x 或 192.168.x 的局域网 IPv4。' -ForegroundColor Yellow
    }
}

$existing = $null
if (Test-Path -LiteralPath $ConfigPath) {
    try {
        $existing = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
    } catch {
        throw "现有配置无法读取，已停止且未覆盖：$ConfigPath"
    }
}

$existingToken = if ($existing) {
    if ("$($existing.dev_token)" -match '^[0-9A-Fa-f]{64}$') { "$($existing.dev_token)" }
    elseif ("$($existing.devToken)" -match '^[0-9A-Fa-f]{64}$') { "$($existing.devToken)" }
}
$token = if ($existingToken) {
    $existingToken.ToLowerInvariant()
} else {
    $providedToken = ''
    if (-not $GenerateNewToken) {
        Write-Host '第一台电脑直接回车会生成新 Token；第二台电脑要同时被手表发现，请粘贴第一台的同一个 Token。' -ForegroundColor Cyan
        $secureToken = Read-Host '已有 Token（输入会隐藏，可直接回车）' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
        try { $providedToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
    if ([string]::IsNullOrWhiteSpace($providedToken)) {
        New-DevToken
    } elseif ($providedToken.Trim() -match '^[0-9A-Fa-f]{64}$') {
        $providedToken.Trim().ToLowerInvariant()
    } else {
        throw '输入的 Token 必须正好是 64 位十六进制字符。'
    }
}

if (-not $BindIp) {
    $existingBindIp = if ($existing -and (Test-PrivateIpv4 "$($existing.bind_ip)")) {
        "$($existing.bind_ip)"
    } elseif ($existing -and (Test-PrivateIpv4 "$($existing.bindIp)")) {
        "$($existing.bindIp)"
    }
    if ($existingBindIp) {
        $BindIp = $existingBindIp
    } else {
        $BindIp = Read-PrivateIpv4
    }
}
if (-not (Test-PrivateIpv4 $BindIp)) {
    throw "拒绝非局域网地址：$BindIp"
}

$port = 18099
if ($existing -and $existing.port -as [int] -and [int]$existing.port -ge 1 -and [int]$existing.port -le 65535) {
    $port = [int]$existing.port
}

$configDirectory = Split-Path -Parent $ConfigPath
[IO.Directory]::CreateDirectory($configDirectory) | Out-Null
$json = [ordered]@{ bind_ip = $BindIp; port = $port; dev_token = $token } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($ConfigPath, $json, (New-Object Text.UTF8Encoding($false)))

$clipboardMessage = '测试模式未写入剪贴板。'
if (-not $NoClipboard) {
    try {
        Set-Clipboard -Value $token
        $clipboardMessage = 'Token 已复制到剪贴板。'
    } catch {
        $clipboardMessage = '无法写入剪贴板；可重新运行本脚本再试。'
    }
}

Write-Host ''
Write-Host '电脑端连接配置已就绪' -ForegroundColor Green
Write-Host "  IP   : $BindIp"
Write-Host "  Port : $port"
Write-Host "  Token: $($token.Substring(0, 4))••••••••$($token.Substring(60, 4))"
Write-Host "  $clipboardMessage"
Write-Host '只在可信的私人局域网使用；不要把 Token 发给别人。' -ForegroundColor Yellow

if ($StartSayIt) {
    $exe = Join-Path $PSScriptRoot 'SayIt.exe'
    if (-not (Test-Path -LiteralPath $exe)) { throw "找不到 $exe" }
    Start-Process -FilePath $exe -WorkingDirectory $PSScriptRoot
    Write-Host 'SayIt 已启动。Windows 防火墙询问时只允许“专用网络”。' -ForegroundColor Cyan
}
