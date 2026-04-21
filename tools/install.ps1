#requires -Version 5.1
<#
.SYNOPSIS
    Installs the Korean-Japanese IME (KorJpnIme.dll) as a Windows TSF text service.

.DESCRIPTION
    1. Self-elevates to Administrator (required for HKLM writes and regsvr32).
    2. Copies KorJpnIme.dll and jpn_dict.txt into the install directory
       (default: C:\Program Files\KorJpnIme).
    3. Registers the DLL via regsvr32 (writes HKLM\SOFTWARE\Classes\CLSID\...).
    4. Imports install_tip.reg (writes HKLM CTF\TIP profile, with Enable=0
       so ctfmon does NOT auto-activate the IME on registration — Korean
       input is preserved).
    5. Tells the user to log out / log back in, then add the IME via
       Settings > Time & Language > Language > Korean > Language options.

.PARAMETER InstallDir
    Where to put the DLL.  Default: C:\Program Files\KorJpnIme

.PARAMETER SourceDir
    Where to look for the input files.  Default: parent of this script.
    Looks for: KorJpnIme.dll, jpn_dict.txt, install_tip.reg, uninstall_tip.reg.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install.ps1
#>

param(
    [string] $InstallDir = "C:\Program Files\KorJpnIme",
    [string] $SourceDir  = (Split-Path -Parent $PSCommandPath)
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok  ($msg) { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "    $msg" -ForegroundColor Yellow }

# ---------- Self-elevate ---------------------------------------------------
$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)) {
    Write-Step "Re-launching as Administrator (UAC prompt)..."
    $args = @('-NoProfile', '-ExecutionPolicy', 'Bypass',
              '-File', $PSCommandPath,
              '-InstallDir', $InstallDir,
              '-SourceDir',  $SourceDir)
    Start-Process powershell -Verb RunAs -ArgumentList $args -Wait
    return
}

Write-Host ""
Write-Step "Korean-Japanese IME installer"
Write-Host "    Source dir : $SourceDir"
Write-Host "    Install dir: $InstallDir"
Write-Host ""

# ---------- Sanity-check source files --------------------------------------
# LICENSES.txt is the third-party attribution file (Mozc / NAIST IPAdic /
# Okinawa Dictionary).  IPAdic terms require it to ship alongside the data.
# kj_dict.bin / kj_conn.bin are the optional viterbi engine inputs -- the
# IME runs without them, just with the legacy longest-prefix lookup.
$required = @('KorJpnIme.dll', 'jpn_dict.txt', 'LICENSES.txt',
              'install_tip.reg', 'uninstall_tip.reg')
$optional = @('kj_dict.bin', 'kj_conn.bin')
foreach ($f in $required) {
    $path = Join-Path $SourceDir $f
    if (-not (Test-Path $path)) {
        # Try the typical project subfolders (tsf/, dict/) as a convenience
        $alt = $null
        foreach ($sub in @('..', '..\tsf', '..\dict')) {
            $candidate = Join-Path $SourceDir $sub
            $candidate = Join-Path $candidate $f
            if (Test-Path $candidate) { $alt = (Resolve-Path $candidate).Path; break }
        }
        if ($alt) {
            Write-Warn "Found $f at $alt"
        } else {
            throw "Required file not found: $f (searched $SourceDir and ..\tsf, ..\dict)"
        }
    }
}

function Resolve-Source($name) {
    $p = Join-Path $SourceDir $name
    if (Test-Path $p) { return (Resolve-Path $p).Path }
    foreach ($sub in @('..', '..\tsf', '..\dict')) {
        $candidate = Join-Path $SourceDir $sub
        $candidate = Join-Path $candidate $name
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }
    throw "Cannot locate $name"
}

# ---------- Copy files -----------------------------------------------------
Write-Step "Copying files into $InstallDir ..."
if (-not (Test-Path $InstallDir)) {
    New-Item -Path $InstallDir -ItemType Directory -Force | Out-Null
}
foreach ($f in $required) {
    $src = Resolve-Source $f
    $dst = Join-Path $InstallDir $f
    Copy-Item -Path $src -Destination $dst -Force
    Write-Ok "  copied $f"
}
# Optional files: copy when present, skip silently when missing.  Lets us
# ship a slim build (without viterbi binaries) on environments where the
# user has no need for the better engine.
foreach ($f in $optional) {
    try {
        $src = Resolve-Source $f
    } catch {
        Write-Host "  (skipped $f -- not found)" -ForegroundColor Gray
        continue
    }
    $dst = Join-Path $InstallDir $f
    Copy-Item -Path $src -Destination $dst -Force
    Write-Ok "  copied $f"
}

# ---------- regsvr32 -------------------------------------------------------
Write-Step "Registering COM server (regsvr32)..."
$dllPath = Join-Path $InstallDir 'KorJpnIme.dll'
$proc = Start-Process regsvr32 -ArgumentList "/s `"$dllPath`"" -Wait -PassThru -NoNewWindow
if ($proc.ExitCode -ne 0) {
    throw "regsvr32 failed (exit $($proc.ExitCode))"
}
Write-Ok "  CLSID registered"

# ---------- Import TIP .reg ------------------------------------------------
Write-Step "Importing TSF TIP profile (Enable=0, will not auto-activate)..."
$regPath = Join-Path $InstallDir 'install_tip.reg'
$proc = Start-Process reg -ArgumentList "import `"$regPath`"" -Wait -PassThru -NoNewWindow
if ($proc.ExitCode -ne 0) {
    throw "reg import failed (exit $($proc.ExitCode))"
}
Write-Ok "  TIP profile imported"

# ---------- Done -----------------------------------------------------------
Write-Host ""
Write-Step "Install complete."
Write-Host ""
Write-Host "Next steps (manual):" -ForegroundColor White
Write-Host "  1. LOG OUT and log back in (so ctfmon picks up the new TIP cleanly)."
Write-Host "  2. Open Settings > Time and Language > Language and Region."
Write-Host "  3. Click Korean > Language options > Add a keyboard."
Write-Host "  4. Pick 'Korean-Japanese IME' from the list."
Write-Host "  5. Use Win+Space to switch to it whenever you want to type Japanese."
Write-Host ""
Write-Host "To uninstall later: run uninstall.ps1 from the install directory" -ForegroundColor Gray
Write-Host "    ($InstallDir\uninstall.ps1)" -ForegroundColor Gray
Write-Host ""
Read-Host "Press Enter to close"
