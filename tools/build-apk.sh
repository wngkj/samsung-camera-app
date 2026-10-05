#!/usr/bin/env bash
# Builds without Gradle or third-party dependencies, using official Android SDK tools.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

: "${WB800F_STORE_PASSWORD:?Set WB800F_STORE_PASSWORD privately before signing}"
export WB800F_STORE_PASSWORD
export WB800F_KEY_PASSWORD="${WB800F_KEY_PASSWORD:-$WB800F_STORE_PASSWORD}"
WB800F_KEYSTORE_FILE="${WB800F_KEYSTORE_FILE:-$PROJECT_DIR/signing/wb800f-release.p12}"
WB800F_KEY_ALIAS="${WB800F_KEY_ALIAS:-wb800f}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
source "$PROJECT_DIR/version.properties"
JDK_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
PLATFORM_DIR="${ANDROID_PLATFORM:-$ANDROID_SDK_ROOT/platforms/android-37.0}"
if [[ ! -f "$PLATFORM_DIR/android.jar" ]]; then PLATFORM_DIR="$ANDROID_SDK_ROOT/platforms/android-37"; fi
TOOLS_DIR="${ANDROID_BUILD_TOOLS:-$ANDROID_SDK_ROOT/build-tools/37.0.0}"
if [[ ! -f "$TOOLS_DIR/lib/d8.jar" ]]; then TOOLS_DIR="$ANDROID_SDK_ROOT/build-tools/android-37.0"; fi
OUT_DIR="$PROJECT_DIR/build/manual"
mkdir -p "$OUT_DIR/classes" "$OUT_DIR/generated" "$OUT_DIR/dex"
"$TOOLS_DIR/aapt2" compile --dir "$PROJECT_DIR/app/src/main/res" -o "$OUT_DIR/resources.zip"
sed 's/<manifest xmlns:android=/<manifest package="cn.cameralink.wb800f" xmlns:android=/' "$PROJECT_DIR/app/src/main/AndroidManifest.xml" > "$OUT_DIR/AndroidManifest.xml"
"$TOOLS_DIR/aapt2" link -o "$OUT_DIR/base.apk" -I "$PLATFORM_DIR/android.jar" --manifest "$OUT_DIR/AndroidManifest.xml" \
    --java "$OUT_DIR/generated" --min-sdk-version 29 --target-sdk-version 37 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT_DIR/resources.zip"
find "$PROJECT_DIR/app/src/main/java" "$OUT_DIR/generated" -name '*.java' -print > "$OUT_DIR/sources.txt"
"${JDK_BIN}javac" --release 17 -encoding UTF-8 -classpath "$PLATFORM_DIR/android.jar" -d "$OUT_DIR/classes" @"$OUT_DIR/sources.txt"
"${JDK_BIN}jar" cf "$OUT_DIR/classes.jar" -C "$OUT_DIR/classes" .
"${JDK_BIN}java" -cp "$TOOLS_DIR/lib/d8.jar" com.android.tools.r8.D8 --min-api 29 --lib "$PLATFORM_DIR/android.jar" --output "$OUT_DIR/dex" "$OUT_DIR/classes.jar"
cp "$OUT_DIR/base.apk" "$OUT_DIR/unsigned.apk"
(cd "$OUT_DIR/dex" && zip -q -j "$OUT_DIR/unsigned.apk" classes*.dex)
"$TOOLS_DIR/zipalign" -f -p 4 "$OUT_DIR/unsigned.apk" "$OUT_DIR/aligned.apk"
"${JDK_BIN}java" -jar "$TOOLS_DIR/lib/apksigner.jar" sign --ks "$WB800F_KEYSTORE_FILE" \
    --ks-key-alias "$WB800F_KEY_ALIAS" --ks-pass env:WB800F_STORE_PASSWORD --key-pass env:WB800F_KEY_PASSWORD \
    --out "$PROJECT_DIR/build/WB800F-Transfer-$VERSION_NAME.apk" "$OUT_DIR/aligned.apk"
"${JDK_BIN}java" -jar "$TOOLS_DIR/lib/apksigner.jar" verify --verbose "$PROJECT_DIR/build/WB800F-Transfer-$VERSION_NAME.apk"
echo "Built: $PROJECT_DIR/build/WB800F-Transfer-$VERSION_NAME.apk"
