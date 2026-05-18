# Commission a QBIC TD-1070 panel for zero-intervention camera streaming.
#
# What this script does:
#   1. Installs the APK as a system priv-app (required for SYSTEM_ALERT_WINDOW, INJECT_EVENTS)
#   2. Writes /system/etc/permissions/privapp-permissions-qbiccontrol.xml so
#      SYSTEM_ALERT_WINDOW and INJECT_EVENTS survive APK updates and reboots
#   3. Writes /system/etc/sysconfig/qbiccontrol-permissions.xml to exempt CAMERA
#      from Android's automatic permission revocation
#   4. Removes any user-installed copy before rebooting (prevents an Android 12 boot loop
#      caused by PackageManagerService crashing on signature mismatch)
#   5. Reboots and waits for the device to come back online
#   6. Removes any remaining user-installed copy that would shadow the system version (safety net)
#   7. Grants CAMERA, WRITE_SECURE_SETTINGS, SYSTEM_ALERT_WINDOW, GET_USAGE_STATS, and device admin
#
# Usage:
#   .\commission_panel.ps1                                          # USB, auto-generated token
#   .\commission_panel.ps1 -Token "mysecret"                        # USB, specific token
#   .\commission_panel.ps1 -DeviceIp 192.168.1.50 -Token "secret"  # Wi-Fi ADB
#   .\commission_panel.ps1 -Apk "C:\path\to\app-debug.apk" -Token "secret"
#
# Token:
#   If -Token is omitted the app generates a random UUID on first run.
#   Pass -Token to set a known value so it can be entered in Q-SYS Designer.
#   The token is stored in the app's EncryptedSharedPreferences and survives reboots.
#
# Prerequisites:
#   - adb on PATH
#   - Panel in developer mode with USB debugging (or Wi-Fi ADB already enabled)
#   - APK already built (run build_android_app.ps1 first)

param(
    [switch]$Help,
    [string]$DeviceIp = "",
    [string]$Apk      = "$PSScriptRoot\QbicControl.apk",
    [string]$Token    = ""
)

if ($Help) {
    Write-Host @"
QBIC Panel Commission — Quick Reference

WHAT YOU NEED
  - A Windows PC with adb installed (winget install Google.PlatformTools)
  - A USB cable connecting your PC to the panel
  - All 4 files in the same folder:
      QbicControl.apk, commission_panel.ps1,
      privapp-permissions-qbiccontrol.xml, qbiccontrol-permissions.xml

FIRST-TIME PANEL SETUP (on the panel touchscreen)
  1. Settings → About device → Build number → tap 7 times
  2. Settings → Developer options → USB debugging → ON

USAGE
  .\commission_panel.ps1                                  # USB, auto token
  .\commission_panel.ps1 -Token "mysecret"                # USB, known token
  .\commission_panel.ps1 -DeviceIp 192.168.1.50           # Wi-Fi ADB

VERIFICATION
  http://<panel-ip>:9091/snapshot   single camera frame
  http://<panel-ip>:9091/screen     live panel display

For full documentation see README.md or the GitHub repo.
"@
    exit 0
}

$ErrorActionPreference = 'Stop'

# Load .env file if present and -Token was not supplied
if (-not $Token) {
    $EnvFile = Join-Path $PSScriptRoot ".env"
    if (Test-Path $EnvFile) {
        Get-Content $EnvFile | ForEach-Object {
            if ($_ -match '^\s*TOKEN\s*=\s*(.+)$') {
                $script:Token = $Matches[1].Trim().Trim('"').Trim("'")
            }
        }
    }
}

$Package   = "au.com.theavitgroup.qbiccontrol"
$AdminComp = "$Package/.DeviceAdminReceiver"
$PrivAppDst     = "/system/priv-app/QbicControl/QbicControl.apk"
$PrivPermDst    = "/system/etc/permissions/privapp-permissions-qbiccontrol.xml"
$PrivPermLocal  = Join-Path $PSScriptRoot "privapp-permissions-qbiccontrol.xml"
$SysConfigDst   = "/system/etc/sysconfig/qbiccontrol-permissions.xml"
$SysConfigLocal = Join-Path $PSScriptRoot "qbiccontrol-permissions.xml"

function Adb {
    param([string[]]$AdbArgs)
    & adb.exe @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($AdbArgs -join ' ') failed (exit $LASTEXITCODE)"
    }
}

function Step {
    param([string]$Msg)
    Write-Host ""
    Write-Host "==> $Msg" -ForegroundColor Cyan
}


# ── Connect ──────────────────────────────────────────────────────────────────

if ($DeviceIp) {
    Step "Connecting to $DeviceIp via Wi-Fi ADB"
    & adb connect "${DeviceIp}:5555"
    Start-Sleep -Seconds 2
}

Step "Checking ADB device"
$devices = & adb devices | Select-String -Pattern "^\S+\s+device$"
if (-not $devices) {
    Write-Host "No device found. Check USB/Wi-Fi ADB connection." -ForegroundColor Red
    exit 1
}
Write-Host $devices

# ── APK ──────────────────────────────────────────────────────────────────────

if (-not (Test-Path $Apk)) {
    Write-Host "APK not found: $Apk" -ForegroundColor Red
    Write-Host "Run build_android_app.ps1 first."
    exit 1
}
Write-Host "APK: $Apk"

# ── Root + remount ────────────────────────────────────────────────────────────

Step "Gaining root access"
$rootOut = & adb root 2>&1
$IsRooted = ($rootOut -notmatch "cannot run as root in production builds")
if ($IsRooted) {
    Write-Host "ADB running as root."
    Start-Sleep -Seconds 3
} else {
    # adb remount uses overlayfs and disables dm-verity; plain su mount bypasses
    # that protection and risks a dm-verity boot failure. Only use priv-app
    # installation when adb root succeeded.
    Write-Host "ADB root unavailable — installing as user app (home button uses accessibility service)." -ForegroundColor Yellow
}

if ($IsRooted) {

    Step "Remounting /system as writable"
    Adb "remount"
    Start-Sleep -Seconds 2

    # ── Preflight: remove any user-installed copy before pushing to priv-app ──
    # Android 12's PackageManagerService crashes on boot when it finds a signature
    # mismatch between the incoming priv-app and an existing /data/app entry — the
    # post-reboot uninstall step below can never run if the device boot-loops first.
    # Uninstall now, while PackageManager is healthy, to prevent this.

    Step "Preflight: checking for existing user-installed copy"
    $existingPath = & adb shell pm path $Package 2>$null
    if ($existingPath -match "/data/app") {
        Write-Host "User-installed copy found — uninstalling before priv-app install to prevent boot loop..." -ForegroundColor Yellow
        # Clear device admin first; uninstall fails with INSTALL_FAILED_DEVICE_POLICY_MANAGER if active.
        Adb "shell","rm","-f","/data/system/device_policies.xml"
        Adb "uninstall",$Package
        Write-Host "Uninstalled."
    } else {
        Write-Host "No user-installed copy — proceeding."
    }

    # ── Install to priv-app ───────────────────────────────────────────────────

    Step "Installing APK to /system/priv-app/"
    # Remove existing APK first — overlayfs needs free space for the new file;
    # leaving the old file in place can cause "No space left on device".
    Adb "shell","rm","-rf","/system/priv-app/QbicControl"
    Adb "shell","mkdir","-p","/system/priv-app/QbicControl"
    Adb "push",$Apk,$PrivAppDst
    Adb "shell","chmod","644",$PrivAppDst
    Adb "shell","chown","root:root",$PrivAppDst
    Write-Host "Installed: $PrivAppDst"

    # ── privapp-permissions XML ───────────────────────────────────────────────

    Step "Writing privapp-permissions XML (SYSTEM_ALERT_WINDOW)"
    if (-not (Test-Path $PrivPermLocal)) {
        Write-Host "privapp-permissions-qbiccontrol.xml not found: $PrivPermLocal" -ForegroundColor Red
        exit 1
    }
    Adb "push",$PrivPermLocal,$PrivPermDst
    Adb "shell","chmod","644",$PrivPermDst
    Adb "shell","chown","root:root",$PrivPermDst
    Write-Host "Written: $PrivPermDst"

    # ── sysconfig exceptions XML ──────────────────────────────────────────────

    Step "Writing sysconfig exceptions XML (CAMERA auto-revoke exemption)"
    if (-not (Test-Path $SysConfigLocal)) {
        Write-Host "qbiccontrol-permissions.xml not found: $SysConfigLocal" -ForegroundColor Red
        exit 1
    }
    Adb "push",$SysConfigLocal,$SysConfigDst
    Adb "shell","chmod","644",$SysConfigDst
    Adb "shell","chown","root:root",$SysConfigDst
    Write-Host "Written: $SysConfigDst"

    # ── Clear device admin before reboot ─────────────────────────────────────
    # DevicePolicyManager stores admin state in device_policies.xml; deleting it
    # before reboot clears admin so the user-installed copy can be uninstalled after
    # reboot without hitting DELETE_FAILED_DEVICE_POLICY_MANAGER.

    Step "Clearing device admin state (device_policies.xml)"
    Adb "shell","rm","-f","/data/system/device_policies.xml"
    Write-Host "Device policies cleared — admin will be reset on reboot."

    # ── Reboot ────────────────────────────────────────────────────────────────

    Step "Rebooting device"
    & adb reboot
    Write-Host "Waiting for device to come back online..."
    & adb wait-for-device

    Write-Host "Waiting for boot to complete..."
    $booted = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 3
        $val = & adb shell getprop sys.boot_completed 2>$null
        if ($val -match "1") { $booted = $true; break }
    }
    if (-not $booted) {
        Write-Host "Boot timed out — continuing anyway, some steps may fail." -ForegroundColor Yellow
    }
    Start-Sleep -Seconds 5
    & adb root | Out-Null
    Start-Sleep -Seconds 2

    # ── Remove user-installed copy if it shadows the system version ───────────

    Step "Checking for user-installed copy that would shadow system priv-app"
    $pmPath = & adb shell pm path $Package 2>$null
    if ($pmPath -match "/data/app") {
        Write-Host "User-installed copy found — uninstalling to expose system version..." -ForegroundColor Yellow
        Adb "uninstall",$Package
        Write-Host "Uninstalled user copy."

        # Reboot so PackageManager scans priv-app cleanly and grants privapp permissions.
        Step "Rebooting to apply priv-app permission grants"
        & adb reboot
        Write-Host "Waiting for device to come back online..."
        & adb wait-for-device
        Write-Host "Waiting for boot to complete..."
        $booted = $false
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 3
            $val = & adb shell getprop sys.boot_completed 2>$null
            if ($val -match "1") { $booted = $true; break }
        }
        if (-not $booted) {
            Write-Host "Boot timed out — continuing anyway." -ForegroundColor Yellow
        }
        Start-Sleep -Seconds 5
        & adb root | Out-Null
        Start-Sleep -Seconds 2
    } else {
        Write-Host "No user-installed copy found — system version is active."
    }

} else {

    # ── No root / no su: install as user app ─────────────────────────────────
    # SYSTEM_ALERT_WINDOW is granted via appops below (persists across reboots).
    # Home button uses the accessibility service (no INJECT_EVENTS needed).

    Step "Installing APK as user app"
    Adb "install","-r",$Apk
    Write-Host "Installed as user app."

}

# ── Grant permissions ─────────────────────────────────────────────────────────

Step "Granting CAMERA permission"
Adb "shell","pm","grant",$Package,"android.permission.CAMERA"

Step "Granting WRITE_SECURE_SETTINGS permission"
Adb "shell","pm","grant",$Package,"android.permission.WRITE_SECURE_SETTINGS"

Step "Registering device admin"
Adb "shell","dpm","set-active-admin",$AdminComp

Step "Setting SYSTEM_ALERT_WINDOW appops to allow"
Adb "shell","appops","set",$Package,"SYSTEM_ALERT_WINDOW","allow"

Step "Granting GET_USAGE_STATS appops (required for browser foreground detection)"
Adb "shell","appops","set",$Package,"GET_USAGE_STATS","allow"

Step "Enabling ScreenCaptureService (accessibility service for /screen endpoint)"
Adb "shell","settings","put","secure","enabled_accessibility_services",
    "$Package/.ScreenCaptureService"
Adb "shell","settings","put","secure","accessibility_enabled","1"

# ── Token ─────────────────────────────────────────────────────────────────────

if ($Token) {
    Step "Setting auth token"
    # Deliver token into the running service so it is written to EncryptedSharedPreferences.
    # The service must be running (BootReceiver starts it on boot).
    Adb "shell","am","startservice",
        "-n","$Package/.QbicControlService",
        "-a","$Package.SET_TOKEN",
        "--es","token",$Token
    Write-Host "Token set: $Token"
} else {
    Write-Host ""
    Write-Host "No -Token supplied — app will use its auto-generated token." -ForegroundColor Yellow
    Write-Host "To retrieve it: tap the QbicControl app icon on the panel (token is shown in MainActivity)." -ForegroundColor Yellow
    Write-Host "Or re-run this script with -Token to set a known value." -ForegroundColor Yellow
}

# ── Verify ────────────────────────────────────────────────────────────────────

Step "Verifying"
Write-Host ""
Write-Host "Package path:"
& adb shell pm path $Package

Write-Host ""
Write-Host "SYSTEM_ALERT_WINDOW appops:"
& adb shell appops get $Package SYSTEM_ALERT_WINDOW

Write-Host ""
Write-Host "CAMERA appops (UID mode should be foreground; BootActivity works around it):"
& adb shell appops get $Package CAMERA

Write-Host ""
Write-Host "================================================" -ForegroundColor Green
Write-Host " Commission complete." -ForegroundColor Green
Write-Host " Camera will open automatically ~1 s after boot." -ForegroundColor Green
if ($Token) {
Write-Host " Auth token: $Token" -ForegroundColor Green
}
Write-Host "================================================" -ForegroundColor Green
