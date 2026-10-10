#!/bin/zsh
# Builds WiFiBar and installs it in ~/Applications, then launches it.
# macOS only loads third-party desktop widgets from apps in an Applications folder; from the build
# folder the widget service discards the extension ("shouldn't cache it").
set -euo pipefail

cd "${0:A:h}/.."
scripts/bundle.sh "${1:-release}"

lsregister=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
installed="$HOME/Applications/WiFiBar.app"

pkill -x WiFiBar || true
mkdir -p "$HOME/Applications"
rm -rf "$installed"
ditto build/WiFiBar.app "$installed"
# Keep a single registered copy so the widget gallery doesn't list the build folder's widget too.
"$lsregister" -u "$PWD/build/WiFiBar.app" 2>/dev/null || true
open "$installed"
echo "Installed $installed"
