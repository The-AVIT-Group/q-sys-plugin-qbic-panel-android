# PowerShell script to build the Android app using Gradle
# Usage: .\build_android_app.ps1

$ErrorActionPreference = 'Stop'

Write-Host "Building Android app..."

# Navigate to script directory
Push-Location $PSScriptRoot

# Run Gradle assembleDebug
./gradlew assembleDebug

Pop-Location

$src = "C:\Temp\qbic-android-build\app\outputs\apk\debug\app-debug.apk"
$dst = Join-Path $PSScriptRoot "QbicControl.apk"
Copy-Item -Path $src -Destination $dst -Force
Write-Host "Build complete. APK copied to: $dst"
