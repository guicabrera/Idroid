#!/usr/bin/env bash
# Release do app Android: APK (chave de debug, sideload) → GitHub Release. Uso: scripts/release-android.sh 0.3.0
set -euo pipefail
VERSION="${1:?uso: scripts/release-android.sh <versão, ex. 0.3.0>}"
TAG="android-v$VERSION"
cd "$(dirname "$0")/.."

export PATH="/opt/homebrew/bin:$PATH"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)}"
GRADLE_FILE=android/app/build.gradle.kts

step() { printf '\n▶ %s\n' "$*"; }

step "Conferindo estado"
[ -z "$(git status --porcelain)" ] || { echo "✖ árvore suja — commite antes"; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "✖ tag $TAG já existe"; exit 1; }

step "Versão $VERSION"
CODE=$(( $(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE") + 1 ))
sed -i '' -E "s/versionCode = [0-9]+/versionCode = $CODE/; s/versionName = \"[^\"]+\"/versionName = \"$VERSION\"/" "$GRADLE_FILE"
echo "versionName=$VERSION versionCode=$CODE"

step "Build e lint"
(cd android && ./gradlew :app:assembleDebug :app:lintDebug -q) || { git checkout "$GRADLE_FILE"; exit 1; }
OUT="/tmp/Idroid-$TAG.apk"
cp android/app/build/outputs/apk/debug/app-debug.apk "$OUT"
echo "ok — $(du -h "$OUT" | cut -f1)"

step "Commit, tag e push"
git commit -qam "release: $TAG"
git tag -a "$TAG" -m "Idroid Android $VERSION"
git push -q && git push -q origin "$TAG"

step "GitHub Release"
PREV=$(git describe --tags --abbrev=0 --match 'android-v*' "$TAG^" 2>/dev/null || true)
NOTES=$(git log --pretty='- %s' ${PREV:+$PREV..}"$TAG^" | grep -v '^- release:' || true)
gh release create "$TAG" "$OUT" --title "Idroid Android v$VERSION" \
  --notes "$(printf 'APK para instalação manual (sideload), assinado com a chave de debug — atualiza por cima das versões anteriores.\n\n## Mudanças\n%s\n' "$NOTES")"
echo "✅ $(gh release view "$TAG" --json url -q .url)"
