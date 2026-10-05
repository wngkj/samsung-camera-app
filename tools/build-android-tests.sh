#!/usr/bin/env bash
# A separate test-only APK, never the app delivered for installation.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

: "${WB800F_STORE_PASSWORD:?Set WB800F_STORE_PASSWORD privately before signing}"
export WB800F_STORE_PASSWORD
export WB800F_KEY_PASSWORD="${WB800F_KEY_PASSWORD:-$WB800F_STORE_PASSWORD}"
WB800F_KEYSTORE_FILE="${WB800F_KEYSTORE_FILE:-$PROJECT_DIR/signing/wb800f-release.p12}"
WB800F_KEY_ALIAS="${WB800F_KEY_ALIAS:-wb800f}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
JDK_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
PLATFORM_DIR="${ANDROID_PLATFORM:-$ANDROID_SDK_ROOT/platforms/android-37.0}"
if [[ ! -f "$PLATFORM_DIR/android.jar" ]]; then PLATFORM_DIR="$ANDROID_SDK_ROOT/platforms/android-37"; fi
TOOLS_DIR="${ANDROID_BUILD_TOOLS:-$ANDROID_SDK_ROOT/build-tools/37.0.0}"
OUT_DIR="$PROJECT_DIR/build/android-tests"
mkdir -p "$OUT_DIR/classes" "$OUT_DIR/dex"
if [[ ! -f "$PROJECT_DIR/build/manual/classes.jar" ]]; then "$PROJECT_DIR/tools/build-apk.sh"; fi
"$TOOLS_DIR/aapt2" link -o "$OUT_DIR/base.apk" -I "$PLATFORM_DIR/android.jar" --manifest "$PROJECT_DIR/tests/android/AndroidManifest.xml" \
    --min-sdk-version 29 --target-sdk-version 37 -A "$PROJECT_DIR/tests/fixtures"
find "$PROJECT_DIR/tests/android" -name '*.java' -print > "$OUT_DIR/sources.txt"
"${JDK_BIN}javac" --release 17 -encoding UTF-8 -classpath "$PLATFORM_DIR/android.jar:$PROJECT_DIR/build/manual/classes.jar" -d "$OUT_DIR/classes" @"$OUT_DIR/sources.txt"
"${JDK_BIN}jar" cf "$OUT_DIR/classes.jar" -C "$OUT_DIR/classes" .
"${JDK_BIN}java" -cp "$TOOLS_DIR/lib/d8.jar" com.android.tools.r8.D8 --min-api 29 --lib "$PLATFORM_DIR/android.jar" \
    --classpath "$PROJECT_DIR/build/manual/classes.jar" --output "$OUT_DIR/dex" "$OUT_DIR/classes.jar"
(cd "$OUT_DIR/dex" && zip -q -j "$OUT_DIR/base.apk" classes*.dex)
"$TOOLS_DIR/zipalign" -f -p 4 "$OUT_DIR/base.apk" "$OUT_DIR/aligned.apk"
"${JDK_BIN}java" -jar "$TOOLS_DIR/lib/apksigner.jar" sign --ks "$WB800F_KEYSTORE_FILE" \
    --ks-key-alias "$WB800F_KEY_ALIAS" --ks-pass env:WB800F_STORE_PASSWORD --key-pass env:WB800F_KEY_PASSWORD \
    --out "$OUT_DIR/tests.apk" "$OUT_DIR/aligned.apk"
echo "Built test-only APK: $OUT_DIR/tests.apk"
