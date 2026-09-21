# 1C-D-04@R7 — unified portable Windows Debug test package
#
# One ZIP, one entry point, both PCs. Produces:
#
#   SayIt-Watch-0.3.0-dev.2-windows-x64-portable.zip
#
# containing a Tauri **Debug** build whose frontend is EMBEDDED (`frontendDist`), so the
# unpacked application starts on any Windows PC without this source tree, without Node,
# without npm and without a Vite/localhost frontend server. The Debug receiver and its
# DNS-SD advertisement stay debug-only exactly as before.
#
# What this script does NOT do:
#   * it does not change firewall, network profile or any user configuration;
#   * it does not bundle, copy or generate a receiver config or a token;
#   * it does not stop, replace or uninstall any installed SayIt;
#   * it does not produce a release build, a release marker or an auto-update channel.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File client/scripts/package-watch-portable.ps1
#   powershell -ExecutionPolicy Bypass -File client/scripts/package-watch-portable.ps1 -SkipBuild

[CmdletBinding()]
param(
    # Reuse the already-built debug EXE instead of running cargo again.
    [switch]$SkipBuild,
    # Output directory for the ZIP (gitignored). Defaults to <repo>/dist-portable.
    [string]$OutputDir,
    # Explicit product commit to record in BUILD-INFO. Defaults to the current HEAD, which is
    # the product commit when the worktree is clean (the normal case).
    [string]$ProductCommit
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$clientDir = Join-Path $repoRoot 'client'
$tauriDir = Join-Path $clientDir 'src-tauri'
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'dist-portable' }

$packageBase = 'SayIt-Watch-0.3.0-dev.2-windows-x64-portable'
$zipPath = Join-Path $OutputDir "$packageBase.zip"
$stagingDir = Join-Path $OutputDir $packageBase

function Write-Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail([string]$text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

# ── 0. Sanity: this must run from the R7/R8 worktree ───────────────────────────
if (-not (Test-Path (Join-Path $tauriDir 'tauri.conf.json'))) {
    Fail "client/src-tauri/tauri.conf.json not found under $repoRoot"
}

# The recorded product commit must be traceable. 1C-D-04@R8 P1: the package must be built from a
# CLEAN, COMMITTED product head, so `git rev-parse HEAD` really is the product commit and a dirty
# tree (uncommitted product source) can never be shipped under a hash that does not describe it.
function Get-GitValue([string[]]$arguments) {
    try {
        $value = (& git -C $repoRoot @arguments 2>$null | Out-String).Trim()
        if ($value) { return $value }
    } catch { }
    return $null
}

$gitStatus = Get-GitValue @('status', '--porcelain')
if ($null -eq $gitStatus) {
    Write-Host '   note: git is unavailable; the product commit cannot be verified' -ForegroundColor Yellow
    $dirty = $false
} else {
    $dirty = $gitStatus.Length -gt 0
}
$baselineCommit = if ($ProductCommit) { $ProductCommit } else { Get-GitValue @('rev-parse', 'HEAD') }
if (-not $baselineCommit) { $baselineCommit = 'unknown' }

if ($dirty -and -not $SkipBuild) {
    Fail ("the worktree has uncommitted changes, so the package cannot record a product commit. " +
          "Commit the product work first, then build from the clean tree. (Dirty entries:`n$gitStatus)")
}
if ($dirty -and $SkipBuild) {
    Write-Host '   note: -SkipBuild was used with a dirty tree; BUILD-INFO still names the committed head' -ForegroundColor Yellow
}
if ($dirty -and $ProductCommit) {
    Write-Host "   note: worktree is dirty; BUILD-INFO names the explicitly supplied commit $ProductCommit" -ForegroundColor Yellow
}

$frontendDist = Join-Path $clientDir 'dist'

# ── 1. Frontend: BUILD-TIME ONLY. The result is embedded in the EXE ────────────
if (-not $SkipBuild) {
    Write-Step 'Building the frontend (build-time only; the result is embedded in the EXE)'
    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue) -and -not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Fail 'npm is required to BUILD the package. It is not needed to RUN the packaged app.'
    }
    Push-Location $clientDir
    try {
        if (-not (Test-Path (Join-Path $clientDir 'node_modules'))) {
            Write-Host '   installing client dependencies (npm ci)'
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { Fail "npm ci failed with exit code $LASTEXITCODE" }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { Fail "frontend build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path (Join-Path $frontendDist 'index.html'))) {
    Fail "frontend dist is missing ($frontendDist). Run without -SkipBuild."
}
# The embedded frontend must contain the three windows' entries, otherwise the packaged
# app would start with a blank overlay/tray.
$requiredAssets = @('index.html', 'overlay.html', 'tray-menu.html')
foreach ($asset in $requiredAssets) {
    if (-not (Test-Path (Join-Path $frontendDist $asset))) {
        Fail "frontend dist is incomplete: $asset is missing"
    }
}

# ── 2. Debug binary (frontend embedded, Debug receiver retained) ───────────────
#
# The build command matters. A plain `cargo build` in a dev profile takes Tauri's DEV
# path (`devUrl` + the Vite dev server) and embeds NO frontend at all, so the packaged app
# would start blank. `tauri build --debug --no-bundle` produces a DEBUG binary with the
# production asset-embedding path and without packaging an installer.
$exePath = Join-Path $tauriDir 'target\debug\sayit.exe'
if (-not $SkipBuild) {
    Write-Step 'Building the Tauri Debug binary with the frontend embedded'

    # tauri-build does not declare the frontend dist as a rebuild input, so drop the
    # crate's own artifacts to guarantee a fresh embed.
    foreach ($artifact in @($exePath, (Join-Path $tauriDir 'target\debug\sayit.pdb'))) {
        if (Test-Path $artifact) { Remove-Item $artifact -Force }
    }

    $npx = Get-Command npx.cmd -ErrorAction SilentlyContinue
    if (-not $npx) { $npx = Get-Command npx -ErrorAction SilentlyContinue }
    if (-not $npx) { Fail 'npx is required to BUILD the package (it runs the Tauri CLI). It is not needed to RUN the packaged app.' }

    Push-Location $clientDir
    try {
        # `beforeBuildCommand` (npm run build) runs as part of this command, so the
        # frontend is regenerated and then embedded in the same step.
        & $npx.Source tauri build --debug --no-bundle
        if ($LASTEXITCODE -ne 0) { Fail "tauri build --debug failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path $exePath)) { Fail "debug EXE not found at $exePath" }

# The embedded frontend must really be inside the binary, otherwise the packaged app
# starts with no UI at all while looking perfectly packaged.
Write-Step 'Verifying the frontend is embedded in the EXE'
$exeBytes = [System.IO.File]::ReadAllBytes($exePath)
$exeText = [System.Text.Encoding]::UTF8.GetString($exeBytes)
$embeddedCount = 0
foreach ($asset in (Get-ChildItem (Join-Path $frontendDist 'assets') -File -ErrorAction SilentlyContinue)) {
    if ($exeText.Contains($asset.Name)) { $embeddedCount++ }
}
if ($embeddedCount -eq 0) {
    Fail 'the built EXE embeds no frontend asset: it was produced by a dev-mode build (devUrl/Vite), not by `tauri build --debug`'
}
Write-Host "   embedded frontend assets found in the EXE: $embeddedCount"

# A release binary must never be shipped through this path.
$releaseExe = Join-Path $tauriDir 'target\release\sayit.exe'
if ((Test-Path $releaseExe) -and ((Get-Item $releaseExe).LastWriteTimeUtc -gt (Get-Item $exePath).LastWriteTimeUtc)) {
    Write-Host '   note: a newer release EXE exists; this package still uses the Debug one' -ForegroundColor Yellow
}

# ── 3. Stage the package ──────────────────────────────────────────────────────
Write-Step "Staging $stagingDir"
if (Test-Path $stagingDir) { Remove-Item $stagingDir -Recurse -Force }
New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null

Copy-Item $exePath (Join-Path $stagingDir 'SayIt.exe') -Force

# Runtime libraries the app loads next to itself: the transcribe/ggml backend DLLs the
# build staged, plus any MSVC runtime DLL the toolchain copied beside the EXE.
$dllSources = @(
    (Join-Path $tauriDir 'transcribe-libs'),
    (Join-Path $tauriDir 'target\debug')
)
$dllCount = 0
foreach ($dir in $dllSources) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem $dir -Filter *.dll -File | ForEach-Object {
        $target = Join-Path $stagingDir $_.Name
        if (-not (Test-Path $target)) { Copy-Item $_.FullName $target -Force; $dllCount++ }
    }
}

# Bundled app resources (Tauri `bundle.resources`: resources/* and transcribe-libs/*).
$resourceDir = Join-Path $tauriDir 'resources'
if (Test-Path $resourceDir) {
    $stagedResources = Join-Path $stagingDir 'resources'
    New-Item -ItemType Directory -Path $stagedResources -Force | Out-Null
    Copy-Item (Join-Path $resourceDir '*') $stagedResources -Force -Recurse
}

# ── 4. Version / baseline / usage notes ───────────────────────────────────────
$exeHash = (Get-FileHash (Join-Path $stagingDir 'SayIt.exe') -Algorithm SHA256).Hash.ToLowerInvariant()

# The notes are written from a SINGLE-QUOTED here-string so nothing in them is interpreted:
# a backtick inside a double-quoted here-string is an escape character, and the previous
# version silently shipped a form-feed where "frontendDist" should have been.
$receiverConfigRelative = '%LOCALAPPDATA%\com.sayit.app\watch-receiver.config.json'
# Notes are written as explicit UTF-8 (no BOM) so the bytes are deterministic on any Windows.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$buildInfoTemplate = @'
SayIt Watch Transport — unified Windows Debug test package
=========================================================

Package     : @PACKAGE@
Built from  : committed product head @GIT_HEAD@
Build type  : Tauri Debug build with the frontend EMBEDDED in the executable.
Entry point : SayIt.exe  (same file on every PC — there is no per-PC package)
Watch app   : 0.3.0-dev.2 (versionCode 6)

Why this is one package for every PC
------------------------------------
The frontend is compiled into SayIt.exe (the Tauri "frontendDist" setting), so the unpacked
application starts on its own. It does NOT need this source tree, Node, npm, Vite or a
localhost:1420/1421 frontend server. The Debug HTTP receiver and its DNS-SD advertisement are
unchanged and remain debug-only.

First run on a PC (read this once per computer)
-----------------------------------------------
1. Unpack this folder anywhere. A path with spaces or Chinese characters is fine.
2. Run SayIt.exe.
3. The watch receiver needs THIS computer's own connection configuration:
       @RECEIVER_CONFIG@
   Example (64-hex token; the field name is fixed):
       {"bindIp":"0.0.0.0","port":18099,"devToken":"<this computer's watch token>"}
   If that file is missing, the app shows a short notice naming this exact path — it will
   not tell you to use the desktop Settings page, because that page configures a different
   feature and cannot set this token.
4. Bring the token over yourself, by a channel you trust (for example from the computer that
   already works, using your own encrypted or otherwise private transfer). This package
   never copies, generates or transmits a token, and no token is included in it.
5. If the notice instead says the receive port could not be bound, some other program is
   already using it. Close that program and start SayIt again; this app never ends another
   process for you.
6. The PC also needs its own ASR/provider configuration. None of it is copied between
   computers by this package.

What this package does NOT contain
----------------------------------
No configuration, no token or credential, no model file, no user history, no received audio,
no source tree, no node_modules, no installer, and no release/auto-update channel.

Unverified by the packager
--------------------------
Real-device (Galaxy Watch) discovery, two-PC switching and recording-to-text were NOT
verified by the packager of this ZIP. Those remain with the project owner.
'@
$buildInfo = $buildInfoTemplate.
    Replace('@PACKAGE@', $packageBase).
    Replace('@GIT_HEAD@', $baselineCommit).
    Replace('@RECEIVER_CONFIG@', $receiverConfigRelative)
[System.IO.File]::WriteAllText((Join-Path $stagingDir 'README-PORTABLE.txt'), $buildInfo, $utf8NoBom)

$versionInfo = @"
package=$packageBase
entry=SayIt.exe
build=debug
frontend=embedded
watch=0.3.0-dev.2 (versionCode 6)
git_head=$baselineCommit
receiver_config=$receiverConfigRelative
SayIt.exe.sha256=$exeHash
"@
[System.IO.File]::WriteAllText((Join-Path $stagingDir 'BUILD-INFO.txt'), $versionInfo, $utf8NoBom)

# ── 5. SHA256SUMS over the payload (not over itself) ──────────────────────────
Write-Step 'Computing SHA256SUMS'
$payload = Get-ChildItem $stagingDir -Recurse -File |
    Where-Object { $_.Name -ne 'SHA256SUMS' } |
    Sort-Object FullName
$lines = foreach ($file in $payload) {
    $relative = $file.FullName.Substring($stagingDir.Length + 1).Replace('\', '/')
    $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $relative"
}
[System.IO.File]::WriteAllText((Join-Path $stagingDir 'SHA256SUMS'), ($lines -join "`n"), $utf8NoBom)

# ── 5b. Static checks on the generated notes ──────────────────────────────────
# The notes are read by a human on a machine that has nothing else, so they must be plain text,
# name the real configuration path, and never contain an interpreted escape that turned into a
# control character while the package was generated.
Write-Step 'Checking the generated notes (text, path, no control characters)'
foreach ($noteName in @('README-PORTABLE.txt', 'BUILD-INFO.txt')) {
    $notePath = Join-Path $stagingDir $noteName
    $noteBytes = [System.IO.File]::ReadAllBytes($notePath)
    foreach ($b in $noteBytes) {
        # Tab, LF and CR are the only control characters a text note may contain.
        if ($b -lt 0x20 -and $b -ne 0x09 -and $b -ne 0x0A -and $b -ne 0x0D) {
            Fail "$noteName contains a control character (0x$('{0:X2}' -f $b)); an escape was interpreted while generating it"
        }
    }
}
$readmeText = [System.IO.File]::ReadAllText((Join-Path $stagingDir 'README-PORTABLE.txt'), [System.Text.Encoding]::UTF8)
# ASCII-only requirements: Windows PowerShell 5.1 cannot be trusted to round-trip a non-ASCII
# literal through a BOM-less read, and a wrong encoding must not be able to pass this gate.
foreach ($required in @(
    $receiverConfigRelative,
    'frontendDist',
    'SayIt.exe',
    'watch-receiver.config.json',
    'devToken'
)) {
    if (-not $readmeText.Contains($required)) {
        Fail "README-PORTABLE.txt must mention $required"
    }
}
# The file must be valid UTF-8 (a garbled encoding would show up as a replacement character).
if ($readmeText.Contains([char]0xFFFD)) {
    Fail 'README-PORTABLE.txt was not written as clean UTF-8'
}
foreach ($forbidden in @('SayIt 设置', '服务器访问令牌')) {
    if ($readmeText.Contains($forbidden)) {
        Fail "README-PORTABLE.txt must not point at the non-existent destination $forbidden"
    }
}
$buildInfoText = [System.IO.File]::ReadAllText((Join-Path $stagingDir 'BUILD-INFO.txt'), [System.Text.Encoding]::UTF8)
if (-not $buildInfoText.Contains("git_head=$baselineCommit")) {
    Fail 'BUILD-INFO.txt must record the product commit it was built from'
}
if (-not $buildInfoText.Contains('frontend=embedded')) {
    Fail 'BUILD-INFO.txt must record that the frontend is embedded'
}

# ── 6. ZIP ────────────────────────────────────────────────────────────────────
Write-Step "Creating $zipPath"
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null }
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stagingDir,
    $zipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $true # include the package folder as the archive's single root
)

$zipHash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$zipSize = (Get-Item $zipPath).Length

Write-Host ''
Write-Host 'Package ready' -ForegroundColor Green
Write-Host "  ZIP         : $zipPath"
Write-Host "  ZIP SHA-256 : $zipHash"
Write-Host "  ZIP bytes   : $zipSize"
Write-Host "  EXE SHA-256 : $exeHash"
Write-Host "  DLLs staged : $dllCount"
Write-Host "  git HEAD    : $baselineCommit"
Write-Host ''
Write-Host 'Independent startup smoke check (no source tree, no Node):'
Write-Host "  1. Expand-Archive '$zipPath' -DestinationPath <empty-temp-dir>"
Write-Host "  2. run <empty-temp-dir>\$packageBase\SayIt.exe from that directory"
Write-Host '  3. confirm the app window/overlay/tray work and no localhost frontend is needed'
