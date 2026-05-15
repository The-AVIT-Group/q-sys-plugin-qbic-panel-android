# PowerShell script to deploy the debug APK to a connected Android device
# Usage: .\deploy_android_app.ps1

$ErrorActionPreference = 'Stop'

#$apkPath = "app/build/outputs/apk/debug/app-debug.apk"
$apkPath = "C:\Temp\qbic-android-build\app\outputs\apk\debug\app-debug.apk"

if (-Not (Test-Path $apkPath)) {
    Write-Host "APK not found at $apkPath. Run build_android_app.ps1 first."
    exit 1
}

Write-Host "Deploying APK to connected device..."

# Install APK using adb
adb install -r $apkPath
#adb install -r "C:\Temp\qbic-android-build\app\outputs\apk\debug\app-debug.apk"
Write-Host "Deployment complete."

adb shell am force-stop au.com.theavitgroup.qbiccontrol
Write-Host "Stopped QbicControlService on device."

adb shell am start-foreground-service -n au.com.theavitgroup.qbiccontrol/.QbicControlService
Write-Host "Started QbicControlService on device."
