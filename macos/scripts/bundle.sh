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
	<key>CFBundleShortVersionString</key><string>0.1.1</string>
	<key>CFBundleVersion</key><string>2</string>
	<key>LSMinimumSystemVersion</key><string>14.0</string>
	<key>NSHighResolutionCapable</key><true/>
	<key>NSAppTransportSecurity</key>
	<dict>
		<key>NSAllowsArbitraryLoads</key><true/>
	</dict>
	<key>CFBundleURLTypes</key>
	<array>
		<dict>
			<key>CFBundleURLName</key><string>com.idroid.mac</string>
			<key>CFBundleURLSchemes</key><array><string>idroid</string></array>
		</dict>
	</array>
</dict>
</plist>
EOF

# Share menu extension ("Idroid" next to AirDrop). SwiftPM can't build an
# .appex, so it's compiled directly; NSExtensionMain is the entry point.
# Extensions must be sandboxed, hence its own entitlements.
APPEX="$APP/Contents/PlugIns/IdroidShare.appex"
mkdir -p "$APPEX/Contents/MacOS" "$APPEX/Contents/Resources"
swiftc -O -parse-as-library -module-name IdroidShare \
	-sdk "$SDKROOT" -target "$(uname -m)-apple-macos14.0" \
	-Xlinker -e -Xlinker _NSExtensionMain \
	ShareExtension/*.swift -o "$APPEX/Contents/MacOS/IdroidShare"
cp "$APP/Contents/Resources/Idroid.icns" "$APPEX/Contents/Resources/"
cat > "$APPEX/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleIdentifier</key><string>com.idroid.mac.share</string>
	<key>CFBundleName</key><string>Idroid</string>
	<key>CFBundleDisplayName</key><string>Idroid</string>
	<key>CFBundleExecutable</key><string>IdroidShare</string>
	<key>CFBundleIconFile</key><string>Idroid</string>
	<key>CFBundlePackageType</key><string>XPC!</string>
	<key>CFBundleShortVersionString</key><string>0.1.1</string>
	<key>CFBundleVersion</key><string>2</string>
	<key>LSMinimumSystemVersion</key><string>14.0</string>
	<key>NSExtension</key>
	<dict>
		<key>NSExtensionPointIdentifier</key><string>com.apple.share-services</string>
		<key>NSExtensionPrincipalClass</key><string>IdroidShareViewController</string>
		<key>NSExtensionAttributes</key>
		<dict>
			<key>NSExtensionActivationRule</key>
			<dict>
				<key>NSExtensionActivationSupportsFileWithMaxCount</key><integer>100</integer>
				<key>NSExtensionActivationSupportsImageWithMaxCount</key><integer>100</integer>
				<key>NSExtensionActivationSupportsMovieWithMaxCount</key><integer>100</integer>
				<key>NSExtensionActivationSupportsWebURLWithMaxCount</key><integer>1</integer>
				<key>NSExtensionActivationSupportsText</key><true/>
			</dict>
		</dict>
	</dict>
</dict>
</plist>
EOF
cat > .build/IdroidShare.entitlements <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.security.app-sandbox</key><true/>
</dict>
</plist>
EOF
# Stable local identity (if present) keeps macOS permissions such as
# Accessibility (ADR-0008) across rebuilds; ad hoc ("-") resets them each time.
SIGN_ID=$(security find-identity -p codesigning 2>/dev/null | awk '/"Idroid Local Signing"/ {print $2; exit}')
SIGN_ID=${SIGN_ID:--}
codesign --force --sign "$SIGN_ID" --entitlements .build/IdroidShare.entitlements "$APPEX"

codesign --force --sign "$SIGN_ID" "$APP"
echo "Built $APP"

if [ "${1:-}" = "--install" ]; then
	osascript -e 'quit app "Idroid"' 2>/dev/null || true
	sleep 1
	rm -rf /Applications/Idroid.app
	ditto "$APP" /Applications/Idroid.app
	# Make the Share menu notice the (re)installed extension right away.
	pluginkit -a /Applications/Idroid.app/Contents/PlugIns/IdroidShare.appex 2>/dev/null || true
	open /Applications/Idroid.app
	echo "Installed /Applications/Idroid.app"
fi
