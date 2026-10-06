#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JDK_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
mkdir -p "$PROJECT_DIR/build/tests"
"${JDK_BIN}javac" --release 17 -encoding UTF-8 -d "$PROJECT_DIR/build/tests" \
    "$PROJECT_DIR/app/src/main/java/cn/cameralink/wb800f/CameraProtocol.java" \
    "$PROJECT_DIR/app/src/main/java/cn/cameralink/wb800f/CameraClient.java" \
    "$PROJECT_DIR/app/src/main/java/cn/cameralink/wb800f/SsdpDiscovery.java" \
    "$PROJECT_DIR/app/src/main/java/cn/cameralink/wb800f/PushHttp.java" \
    "$PROJECT_DIR/app/src/main/java/cn/cameralink/wb800f/SamsungPushReceiver.java" \
    "$PROJECT_DIR/app/src/main/java/cn/cameralink/wb800f/SamsungPushClient.java" \
    "$PROJECT_DIR/tests/ProtocolTest.java" "$PROJECT_DIR/tests/DiscoveryTest.java" "$PROJECT_DIR/tests/PushTest.java"
"${JDK_BIN}java" -cp "$PROJECT_DIR/build/tests" ProtocolTest
"${JDK_BIN}java" -cp "$PROJECT_DIR/build/tests" DiscoveryTest
"${JDK_BIN}java" -cp "$PROJECT_DIR/build/tests" PushTest
