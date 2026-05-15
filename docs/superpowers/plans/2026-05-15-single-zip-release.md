# Single-zip GitHub Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace five individual GitHub release assets with one flat zip so a technician downloads a single file.

**Architecture:** The GitHub Actions release workflow gains one `zip` step after the APK is renamed; the `gh release create` command is updated to upload only the zip. `INSTALL.md` is updated to tell the technician to download and extract the zip instead of four individual files.

**Tech Stack:** GitHub Actions, `zip` (pre-installed on `ubuntu-latest`), `gh` CLI, Markdown

---

### Task 1: Add zip step to release workflow

**Files:**
- Modify: `.github/workflows/release.yml`

Current `release.yml` for reference:
```yaml
- name: Rename APK
  run: mv app/build/outputs/apk/debug/app-debug.apk QbicControl.apk

- name: Create GitHub release
  env:
    GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  run: |
    gh release create "${{ github.ref_name }}" \
      QbicControl.apk \
      commission_panel.ps1 \
      privapp-permissions-qbiccontrol.xml \
      qbiccontrol-permissions.xml \
      INSTALL.md \
      --title "${{ github.ref_name }}" \
      --notes-from-tag
```

- [ ] **Step 1: Insert the zip step between "Rename APK" and "Create GitHub release"**

Replace the two steps above with:

```yaml
      - name: Rename APK
        run: mv app/build/outputs/apk/debug/app-debug.apk QbicControl.apk

      - name: Create install zip
        run: |
          zip QbicControl-${{ github.ref_name }}.zip \
            QbicControl.apk \
            commission_panel.ps1 \
            privapp-permissions-qbiccontrol.xml \
            qbiccontrol-permissions.xml \
            INSTALL.md

      - name: Create GitHub release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh release create "${{ github.ref_name }}" \
            "QbicControl-${{ github.ref_name }}.zip" \
            --title "${{ github.ref_name }}" \
            --notes-from-tag
```

- [ ] **Step 2: Validate YAML syntax**

Run from the repo root (Python is available on all platforms):

```bash
python -c "import yaml, sys; yaml.safe_load(open('.github/workflows/release.yml')); print('YAML OK')"
```

Expected output: `YAML OK`

If you get a `ScannerError`, fix the indentation in the edited file and re-run.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "feat(ci): bundle release assets into single zip"
```

---

### Task 2: Update INSTALL.md download instruction

**Files:**
- Modify: `INSTALL.md`

Current "What you need" section:
```markdown
## What you need

- A Windows PC
- A USB cable connecting your PC to the panel
- The panel's IP address (for verification after install)
- All 4 files from this release in the same folder:
  - `QbicControl.apk`
  - `commission_panel.ps1`
  - `privapp-permissions-qbiccontrol.xml`
  - `qbiccontrol-permissions.xml`
```

- [ ] **Step 1: Replace the "What you need" section**

Replace the bullet block above with:

```markdown
## What you need

- A Windows PC
- A USB cable connecting your PC to the panel
- The panel's IP address (for verification after install)
- `QbicControl-vX.X.X.zip` downloaded from the release page and extracted into a single folder
```

> Note: `vX.X.X` is illustrative prose for the technician — leave it exactly as written. The technician will see the real version number in the release page filename.

- [ ] **Step 2: Verify the rest of INSTALL.md is unchanged**

Confirm Step 3 still reads:
```
navigate to the folder containing the 4 files and run:
.\commission_panel.ps1
```

Update "4 files" → "extracted files" if present, otherwise leave as-is.

- [ ] **Step 3: Commit**

```bash
git add INSTALL.md
git commit -m "docs: update install instructions for single-zip download"
```

---

### Task 3: End-to-end verification

No automated test is possible without pushing a tag. Perform these manual checks before merging.

- [ ] **Step 1: Confirm both commits are on the branch**

```bash
git log --oneline -5
```

Expected: two new commits — `feat(ci): bundle release assets into single zip` and `docs: update install instructions for single-zip download`.

- [ ] **Step 2: Dry-run the zip command locally (optional but recommended)**

On any machine with `zip` installed (Git Bash, WSL, or macOS/Linux):

```bash
# From the repo root — uses placeholder version tag
zip QbicControl-v0.0.0-test.zip \
  QbicControl.apk \
  commission_panel.ps1 \
  privapp-permissions-qbiccontrol.xml \
  qbiccontrol-permissions.xml \
  INSTALL.md
```

Then verify contents:
```bash
unzip -l QbicControl-v0.0.0-test.zip
```

Expected: exactly 5 files listed at the root (no subdirectory prefix). Delete the test zip afterwards.

> If `QbicControl.apk` does not exist locally yet (it's built in CI), substitute any file (e.g. `README.md`) just to verify the zip command syntax works.

- [ ] **Step 3: Push a test tag to trigger CI (when ready for a real release)**

```bash
git tag v1.0.1
git push origin v1.0.1
```

Then on the GitHub releases page confirm:
- Exactly one custom asset: `QbicControl-v1.0.1.zip`
- The zip extracts to 5 files flat (no subfolder)
- The individual `.apk`, `.ps1`, and `.xml` files are **not** listed as separate assets
