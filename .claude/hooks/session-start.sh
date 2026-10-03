#!/bin/bash
# Cloud sessions: install the Android SDK (needs dl.google.com allowed) and warm Gradle.
set -euo pipefail
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
cd "$CLAUDE_PROJECT_DIR"

SDK=/opt/android-sdk
if [ ! -d "$SDK/platforms/android-35" ]; then
  if curl -sSfLo /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip; then
    mkdir -p "$SDK/cmdline-tools" && rm -rf /tmp/cmdline-tools
    unzip -q -o /tmp/clt.zip -d /tmp && rm -rf "$SDK/cmdline-tools/latest" && mv /tmp/cmdline-tools "$SDK/cmdline-tools/latest"
    rm -f /tmp/clt.zip
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools" > /dev/null
  else
    echo "Android SDK download failed; only :core will build" >&2
  fi
fi
[ -d "$SDK/platforms/android-35" ] && echo "sdk.dir=$SDK" > local.properties

./gradlew -q :core:testClasses > /dev/null 2>&1 || true
