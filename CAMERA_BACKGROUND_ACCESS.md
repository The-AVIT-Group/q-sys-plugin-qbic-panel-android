# Camera Background Access — QBIC TD-1070 (Android 12)

## Problem

After a panel reboot, the MJPEG camera stream (port 9091) fails to open.
The Android service starts and the WebSocket (port 9090) works immediately, but
`CameraManager.openCamera()` throws `CameraAccessException` (reason=1, CAMERA_DISABLED)
or fires `onError(ERROR_CAMERA_DISABLED)` on every attempt.

The failure is caused by Android 12's `Uid mode: CAMERA: foreground` restriction, which
requires the app's process to have a visible Activity (importance ≤ 100) when
`openCamera()` is called. A foreground service alone has importance 125 — not sufficient.

---

## Root Cause

`adb shell pm grant` sets `Uid mode: CAMERA: foreground` for the app's UID. On every
boot, `PermissionManagerService` re-reads the runtime-permissions database and re-applies
this mode, making it immune to direct edits of `/data/system/appops.xml`.

QBIC's camera daemon enforces this at `connectHelper`. QBIC's own apps face the same
restriction if they used `pm grant` — `com.qbic.smilplayer` also has `m="4"` (foreground)
in the appops XML. Only apps provisioned at manufacture time with the platform signing key
receive `Uid mode: CAMERA: allow` by default.

Key finding: once a camera session is **successfully opened**, it remains open even when
the process drops back to foreground-service importance (125). The policy is only enforced
at `openCamera()` time, not during an active session.

---

## Solution (implemented and verified)

Use `SYSTEM_ALERT_WINDOW` permission to let the foreground service launch a transparent
`BootActivity` as soon as the camera fails. `BootActivity` raises the process importance
to 100 (visible Activity), signals the retry loop via `ACTION_RETRY_CAMERA`, then finishes
after 1.5 s. The camera retry fires while the Activity is still visible and succeeds.
The camera session then stays open for the duration of the service's lifetime.

### Components

**`CameraStreamServer`** — on first `CAMERA_DISABLED` failure (and every 10th attempt
thereafter), launches `BootActivity` with `FLAG_ACTIVITY_NEW_TASK`. Uses
`retrySignal.wait()` instead of `Thread.sleep` so `retryNow()` can interrupt the wait
immediately when `BootActivity` signals back.

**`BootActivity`** — transparent, zero-UI activity. Sends `ACTION_RETRY_CAMERA` to
`QbicControlService` via `startForegroundService`, then finishes after 1500 ms.

**`QbicControlService`** — handles `ACTION_RETRY_CAMERA` in `onStartCommand` by calling
`cameraStream?.retryNow()`, which wakes the waiting retry thread.

### Boot sequence (verified)

```
20:31:26.239  Camera error: 3 — attempt 1, retrying in 10s
20:31:26.240  Launching BootActivity to satisfy foreground camera requirement
20:31:26.471  BootActivity: onCreate — satisfying camera foreground check
20:31:26.966  MJPEG server listening on :9091
20:31:27.358  Camera capture started
```
Camera is open ~1.1 s after boot. No manual intervention required.

---

## Device Setup (one-time, per panel)

Install the APK as a **system app** via overlayfs (required — user-installed APKs
cannot get SYSTEM_ALERT_WINDOW reliably, and `pm grant` still forces `foreground` mode):

```bash
adb root
adb remount
# Copy files BEFORE rebooting — overlayfs is transient
adb shell mkdir -p /system/priv-app/QbicControl
adb push app-debug.apk /system/priv-app/QbicControl/QbicControl.apk
adb shell chmod 644 /system/priv-app/QbicControl/QbicControl.apk
adb shell chown root:root /system/priv-app/QbicControl/QbicControl.apk
adb reboot
```

After reboot, grant permissions and register device admin:

```bash
adb shell pm grant au.com.theavitgroup.qbiccontrol android.permission.CAMERA
adb shell pm grant au.com.theavitgroup.qbiccontrol android.permission.WRITE_SECURE_SETTINGS
adb shell dpm set-active-admin au.com.theavitgroup.qbiccontrol/.DeviceAdminReceiver
adb shell appops set au.com.theavitgroup.qbiccontrol SYSTEM_ALERT_WINDOW allow
```

`SYSTEM_ALERT_WINDOW` is what enables the service to launch `BootActivity` from the
background. It must be set via `appops set` (not `pm grant`) and survives reboots.

> **If the user-installed version takes precedence over the system version:**
> Run `adb uninstall au.com.theavitgroup.qbiccontrol` to remove the user copy, then
> re-grant the permissions above.

> **Note on `adb remount`:** The `remount` command sets up overlayfs and makes `/system`
> writable immediately — do NOT reboot between `remount` and the file copy. Reboot only after the files are in place.

---

## What Was Tried (history)

| Approach | Result |
| --- | --- |
| Retry loop with `wantStreaming` flag | Works — service recovers when camera becomes accessible |
| `foregroundServiceType="camera"` in manifest | No effect — QBIC daemon ignores Android service type flags |
| `cmd appops set --uid <uid> CAMERA allow` | Sets package-level mode only; UID-level stays `foreground` |
| `cmd appops set-uid-mode` | Not available on this Android version |
| `BootActivity` launched from `BootReceiver` | Blocked: Android 12 `allowBackgroundActivityStart: false` |
| Direct edit of `/data/system/appops.xml` (via `abx2xml`/`xml2abx`) | Overwritten on boot by `PermissionManagerService` |
| `cmd appops reset <package>` | Resets package-level ops only; UID-level persists |
| Install to `/system/priv-app/` + `pm grant` | Still sets `Uid mode: foreground` — `pm grant` code path always does this |
| `default-permissions` XML in `/system/etc/default-permissions/` | Same `grantRuntimePermission()` code path; still sets `foreground` |
| `SYSTEM_ALERT_WINDOW` + `BootActivity` from foreground service | **WORKS** — implemented solution |
