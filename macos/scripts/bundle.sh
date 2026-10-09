#!/bin/sh
# Builds the app and wraps it in a minimal, ad-hoc signed Idroid.app so
# UNUserNotificationCenter works (a bare `swift run` binary has no bundle id).
#
#   scripts/bundle.sh            build .build/Idroid.app
#   scripts/bundle.sh --install  also replace /Applications/Idroid.app and relaunch it
#
# Uses the newest SDK that still ships the SwiftUI macro plugin with the
# Command Line Tools; override with SDKROOT=... if needed.
set -eu
cd "$(dirname "$0")/.."

: "${SDKROOT:=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk}"
export SDKROOT
swift build -c release

APP=.build/Idroid.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/IdroidApp "$APP/Contents/MacOS/Idroid"

# App icon, drawn by make-icon.swift (same mark as the Android app).
ICONSET=.build/Idroid.iconset
rm -rf "$ICONSET" && mkdir -p "$ICONSET"
swift scripts/make-icon.swift .build/icon-1024.png
for px in 16 32 128 256 512; do
	sips -z $px $px .build/icon-1024.png --out "$ICONSET/icon_${px}x${px}.png" >/dev/null
	sips -z $((px * 2)) $((px * 2)) .build/icon-1024.png --out "$ICONSET/icon_${px}x${px}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/Idroid.icns"

# NSAllowsArbitraryLoads: the relay speaks plain HTTP because it is only
# reachable over the Tailscale tailnet, which already encrypts traffic
# (ADR-0001). Tailnet IPs (100.x) don't count as "local" for
# NSAllowsLocalNetworking, so that narrower key isn't enough.
cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleIdentifier</key><string>com.idroid.mac</string>
	<key>CFBundleName</key><string>Idroid</string>
	<key>CFBundleExecutable</key><string>Idroid</string>
	<key>CFBundleIconFile</key><string>Idroid</string>
	<key>CFBundlePackageType</key><string>APPL</string>
	<key>CFBundleShortVersionString</key><string>0.1.0</string>
	<key>CFBundleVersion</key><string>1</string>
	<key>LSMinimumSystemVersion</key><string>14.0</string>
	<key>NSHighResolutionCapable</key><true/>
	<key>NSAppTransportSecurity</key>
	<dict>
		<key>NSAllowsArbitraryLoads</key><true/>
	</dict>
</dict>
</plist>
EOF

codesign --force --sign - "$APP"
echo "Built $APP"

if [ "${1:-}" = "--install" ]; then
	osascript -e 'quit app "Idroid"' 2>/dev/null || true
	sleep 1
	rm -rf /Applications/Idroid.app
	ditto "$APP" /Applications/Idroid.app
	open /Applications/Idroid.app
	echo "Installed /Applications/Idroid.app"
fi
