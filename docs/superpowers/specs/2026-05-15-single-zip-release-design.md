---
title: Single-zip GitHub Release
date: 2026-05-15
status: approved
---

# Single-zip GitHub Release

## Goal

Replace the five individual release assets with one flat zip file so a technician only needs to download a single file to install QbicControl.

## Scope

- `.github/workflows/release.yml` — add zip step, replace individual asset uploads with the zip
- `INSTALL.md` — update download instruction to reference the zip

## Workflow Change

After the APK is built and renamed to `QbicControl.apk`, a new step creates the zip:

```yaml
- name: Create install zip
  run: |
    zip QbicControl-${{ github.ref_name }}.zip \
      QbicControl.apk \
      commission_panel.ps1 \
      privapp-permissions-qbiccontrol.xml \
      qbiccontrol-permissions.xml \
      INSTALL.md
```

The `gh release create` command then uploads only `QbicControl-${{ github.ref_name }}.zip` — the five individual files are removed from the upload list.

## Zip Contents (flat, no subdirectory)

| File | Purpose |
|------|---------|
| `QbicControl.apk` | Android app |
| `commission_panel.ps1` | Commissioning script |
| `privapp-permissions-qbiccontrol.xml` | Privileged app permissions |
| `qbiccontrol-permissions.xml` | Runtime permissions |
| `INSTALL.md` | Installation instructions |

## INSTALL.md Change

The **"What you need"** section changes from listing 4 individual files to:

> Download `QbicControl-vX.X.X.zip` from the release page and extract all files into a single folder.

Step 3 ("navigate to the folder containing the files and run `.\commission_panel.ps1`") is unchanged.

## Out of Scope

- No change to the build process or signing
- No change to the PowerShell commissioning script
- GitHub auto-generated "Source code" assets (zip/tar.gz) are unaffected
