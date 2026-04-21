#requires -Version 5.1
<#
.SYNOPSIS
    Removes the Korean-Japanese IME (KorJpnIme.dll).

.DESCRIPTION
    1. Self-elevates to Administrator.
    2. Imports uninstall_tip.reg to drop the TSF TIP profile.
    3. regsvr32 /u to drop the COM CLSID.
    4. Removes the user's HKCU TIP entry if present (so the input method
       disappears from the language bar immediately).
    5. Optionally deletes the install directory (-RemoveFiles).

.PARAMETER InstallDir
    Where the IME was installed.  Default: C:\Program Files\KorJpnIme

.PARAMETER RemoveFiles
    Also delete the install directory after unregistering.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File uninstall.ps1
    powershell -ExecutionPolicy Bypass -File uninstall.ps1 -RemoveFiles
#>

param(
    [string] $InstallDir   = "C:\Program Files\KorJpnIme",
    [switch] $RemoveFiles
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
              '-InstallDir', $InstallDir)
    if ($RemoveFiles) { $args += '-RemoveFiles' }
    Start-Process powershell -Verb RunAs -ArgumentList $args -Wait
    return
}

Write-Host ""
Write-Step "Korean-Japanese IME uninstaller"
Write-Host "    Install dir: $InstallDir"
Write-Host ""

# ---------- Remove user-level TIP entry (best effort) ----------------------
Write-Step "Removing per-user TIP entry (HKCU)..."
$tipKey  = 'HKCU:\Control Panel\International\User Profile\ko'
$tipName = '0412:{CADA0001-1234-5678-90AB-CDEF00000001}{CADA0002-1234-5678-90AB-CDEF00000002}'
try {
    Remove-ItemProperty -Path $tipKey -Name $tipName -ErrorAction SilentlyContinue
    Write-Ok "  HKCU TIP entry removed (if it existed)"
} catch {
    Write-Warn "  HKCU TIP entry not present"
}

# ---------- Remove HKLM CTF/TIP (uses uninstall_tip.reg if available) ------
Write-Step "Removing TSF TIP profile (HKLM)..."
$uninstallReg = Join-Path $InstallDir 'uninstall_tip.reg'
if (Test-Path $uninstallReg) {
    $proc = Start-Process reg -ArgumentList "import `"$uninstallReg`"" -Wait -PassThru -NoNewWindow
    if ($proc.ExitCode -ne 0) {
        Write-Warn "  reg import returned $($proc.ExitCode), falling back to manual delete"
        & reg delete 'HKLM\SOFTWARE\Microsoft\CTF\TIP\{CADA0001-1234-5678-90AB-CDEF00000001}' /f 2>$null | Out-Null
    } else {
        Write-Ok "  TIP profile removed"
    }
} else {
    Write-Warn "  uninstall_tip.reg not found, deleting registry keys directly"
    & reg delete 'HKLM\SOFTWARE\Microsoft\CTF\TIP\{CADA0001-1234-5678-90AB-CDEF00000001}' /f 2>$null | Out-Null
}

# ---------- Unregister COM CLSID -------------------------------------------
Write-Step "Unregistering COM server (regsvr32 /u)..."
$dllPath = Join-Path $InstallDir 'KorJpnIme.dll'
if (Test-Path $dllPath) {
    $proc = Start-Process regsvr32 -ArgumentList "/u /s `"$dllPath`"" -Wait -PassThru -NoNewWindow
    Write-Ok "  regsvr32 /u exit=$($proc.ExitCode)"
} else {
    Write-Warn "  DLL not found at $dllPath, deleting CLSID directly"
    & reg delete 'HKLM\SOFTWARE\Classes\CLSID\{CADA0001-1234-5678-90AB-CDEF00000001}' /f 2>$null | Out-Null
}

# ---------- Optionally remove files ----------------------------------------
if ($RemoveFiles) {
    Write-Step "Deleting install directory $InstallDir ..."
    if (Test-Path $InstallDir) {
        Remove-Item -Path $InstallDir -Recurse -Force
        Write-Ok "  removed"
    } else {
        Write-Warn "  already gone"
    }
}

# ---------- Done -----------------------------------------------------------
Write-Host ""
Write-Step "Uninstall complete."
Write-Host ""
Write-Host "Log out and log back in to fully remove the IME from running processes." -ForegroundColor White
Write-Host ""
Read-Host "Press Enter to close"
