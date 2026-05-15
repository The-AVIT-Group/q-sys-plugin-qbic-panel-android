# Installing QbicControl on a QBIC TD-1070 Panel

## What you need

- A Windows PC
- A USB cable connecting your PC to the panel
- The panel's IP address (for verification after install)
- `QbicControl-vX.X.X.zip` downloaded from the release page and extracted into a single folder

## Step 1 — Install ADB (one-time setup, ~2 minutes)

Open PowerShell and run:

```powershell
winget install Google.PlatformTools
```

Close and reopen PowerShell when it finishes.

## Step 2 — Enable USB debugging on the panel (one-time per panel)

On the panel touchscreen:

1. Settings → About device → **Build number** → tap 7 times
2. Settings → **Developer options** → USB debugging → **ON**

## Step 3 — Connect and commission

Plug the USB cable from your PC into the panel, then in PowerShell navigate to the
folder containing the extracted files and run:

```powershell
.\commission_panel.ps1
```

The script takes approximately 3 minutes and reboots the panel once. When it prints
**"Commission complete"** the app is installed and running.

## Verification

Open a browser on your PC and go to:

- `http://<panel-ip>:9091/snapshot` — single camera frame
- `http://<panel-ip>:9091/screen` — live panel display

If both pages load, the app is running correctly.

## Troubleshooting

Run the commission script with `-Help` for a quick reference:

```powershell
.\commission_panel.ps1 -Help
```

For detailed troubleshooting, see the [README](README.md) in this repo.
