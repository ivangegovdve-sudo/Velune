#!/usr/bin/env bash
# deploy-webradio.sh
# One-shot helper that commits and pushes the Velune web-radio additions
# to origin/main so GitHub Pages can serve assets/webradio/index.html.
#
# Run from inside Git Bash:
#   cd /d/Ivan/velune-radio-project
#   bash scripts/deploy-webradio.sh
#
# Or just double-click in File Explorer (Git Bash will run it).

set -e

cd "$(dirname "$0")/.."

# Clear any stale lock left by previous failed runs.
if [ -f .git/index.lock ]; then
    echo "Removing stale .git/index.lock"
    rm -f .git/index.lock
fi

echo "==> Staging only the new/changed Velune-radio files"
git add \
    app/src/main/kotlin/com/nikhil/yt/radio \
    app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/RadioSettings.kt \
    app/src/main/res/drawable/visibility.xml \
    app/src/main/res/drawable/visibility_off.xml \
    app/src/main/res/drawable/qr_code_2.xml \
    assets/webradio \
    scripts/deploy-webradio.sh

echo
echo "==> Staged files:"
git diff --cached --stat
echo

if git diff --cached --quiet; then
    echo "Nothing staged — looks like everything is already committed."
else
    echo "==> Committing"
    git commit -m "Add web radio listener page, redesigned settings, QR sharing

- Material You status hero with live indicator + uptime + now-playing
- Listener URL card with copy/share/open/QR action chips
- Modal bottom sheet with QR for web player and direct stream URLs
- Per-field validation and accessibility (semantics, live region, IME chain)
- Pure-Kotlin QR encoder, no Gradle changes
- Static index.html web listener at assets/webradio/
- IcecastMetadataPublisher pushes track changes to /admin/metadata
- Visibility and QR-code vector drawables"
fi

echo
echo "==> Pushing to origin/main"
git push origin main

echo
echo "============================================"
echo "  Done. Now enable GitHub Pages:"
echo
echo "  1. Open: https://github.com/ivangegovdve-sudo/Velune/settings/pages"
echo "  2. Source        = Deploy from a branch"
echo "  3. Branch        = main"
echo "  4. Folder        = / (root)"
echo "  5. Save."
echo
echo "  After ~60s the listener page will be live at:"
echo "  https://ivangegovdve-sudo.github.io/Velune/assets/webradio/"
echo "============================================"
