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
    [string]$OutputDir
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

# ── 0. Sanity: this must run from the R7 worktree ───────────────────────────────
if (-not (Test-Path (Join-Path $tauriDir 'tauri.conf.json'))) {
    Fail "client/src-tauri/tauri.conf.json not found under $repoRoot"
}
$baselineCommit = 'unknown'
try {
    $baselineCommit = (& git -C $repoRoot rev-parse HEAD 2>$null).Trim()
    if (-not $baselineCommit) { $baselineCommit = 'unknown' }
} catch { $baselineCommit = 'unknown' }

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
$buildInfo = @"
SayIt Watch Transport — unified Windows Debug test package
=========================================================

Package     : $packageBase
Built from  : this repository worktree (git HEAD $baselineCommit)
Build type  : Tauri **Debug** build with the frontend EMBEDDED in the executable.
Entry point : SayIt.exe  (same file on every PC — there is no per-PC package)
Watch app   : 0.3.0-dev.2 (versionCode 6)

Why this is one package for every PC
------------------------------------
The frontend is compiled into SayIt.exe (`frontendDist`), so the unpacked application
starts on its own. It does NOT need this source tree, Node, npm, Vite or a
localhost:1420/1421 frontend server. The Debug HTTP receiver and its DNS-SD
advertisement are unchanged and remain debug-only.

First run on a PC
-----------------
1. Unpack this folder anywhere (a path with spaces or Chinese characters is fine).
2. Run SayIt.exe.
3. If the watch receiver cannot start, the app shows a short notice explaining what to
   do (missing local connection configuration, or the receive port already in use).
   The notice never contains your token.
4. The PC needs its own local connection configuration (the watch access token) and its
   own ASR/provider settings. Nothing is copied between computers by this package.

What this package does NOT contain
----------------------------------
No configuration, no token or credential, no model file, no user history, no received
audio, no source tree, no node_modules, no installer, and no release/auto-update channel.

Unverified by the packager
--------------------------
Real-device (Galaxy Watch) discovery, two-PC switching and recording-to-text were NOT
verified by the packager of this ZIP. Those remain with the project owner.
"@
Set-Content -Path (Join-Path $stagingDir 'README-PORTABLE.txt') -Value $buildInfo -Encoding UTF8

$versionInfo = @"
package=$packageBase
entry=SayIt.exe
build=debug
frontend=embedded
watch=0.3.0-dev.2 (versionCode 6)
git_head=$baselineCommit
SayIt.exe.sha256=$exeHash
"@
Set-Content -Path (Join-Path $stagingDir 'BUILD-INFO.txt') -Value $versionInfo -Encoding UTF8

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
Set-Content -Path (Join-Path $stagingDir 'SHA256SUMS') -Value ($lines -join "`n") -Encoding UTF8

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
