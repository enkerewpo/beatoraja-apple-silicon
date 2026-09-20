#!/bin/bash
# Wrap the SwiftPM executable into a macOS .app bundle.
# SwiftPM builds a plain binary; the bundle gives it an icon, a Dock presence and a name.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/build/BeatorajaLauncher.app"
BIN="$HERE/.build/release/BeatorajaLauncher"

swift build -c release

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/BeatorajaLauncher"
cp "$HERE/../desktop/packaging/beatoraja.icns" "$APP/Contents/Resources/launcher.icns"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>beatoraja Launcher</string>
  <key>CFBundleDisplayName</key><string>beatoraja Launcher</string>
  <key>CFBundleIdentifier</key><string>dev.wheatfox.beatoraja.launcher</string>
  <key>CFBundleVersion</key><string>1.0.0</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleExecutable</key><string>BeatorajaLauncher</string>
  <key>CFBundleIconFile</key><string>launcher.icns</string>
  <key>LSMinimumSystemVersion</key><string>26.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true
echo "built: $APP"
