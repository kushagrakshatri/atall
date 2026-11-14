#!/usr/bin/env bash
# Build wrapper for an aarch64 Linux box without KVM (the original dev sandbox). On x86-64 / CI just run ./gradlew.
# Sets JAVA_HOME/ANDROID_HOME, points AGP at the
# aarch64 aapt2 (the Maven aapt2 AGP downloads is x86-64 only), and wires release signing from a
# properties file kept outside the repo. Machine-specific values are passed as -P flags, never committed.
# Usage: ./build.sh [gradle tasks...]   default: test assembleRelease
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME_OVERRIDE:-/workspace/work/sdk/jdk}
export ANDROID_HOME=${ANDROID_HOME_OVERRIDE:-/workspace/work/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/workspace/work/gradle-home}
export PATH="$JAVA_HOME/bin:$PATH"
# Real aarch64 aapt2 (build-tools 35.0.2, statically linked). The shim fixes AGP 9's "--opt=value" args.
export AAPT2_REAL=${AAPT2_REAL:-/workspace/work/sdk/tools/build-tools/aapt2}
AAPT2=${AAPT2:-$PWD/tools/aapt2-shim/aapt2}
# Release signing: RELEASE_SIGNING_PROPS=/path/outside/repo/signing.properties (unsigned when unset).
SIGNING=${RELEASE_SIGNING_PROPS:-}
echo "sdk.dir=$ANDROID_HOME" > local.properties   # git-ignored
# The sandbox egress proxy env makes Java CONNECT tunnelling fail with 407; direct egress works.
unset HTTPS_PROXY https_proxy HTTP_PROXY http_proxy ALL_PROXY all_proxy
[ $# -gt 0 ] || set -- test assembleRelease
./gradlew --console=plain \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  -Pandroid.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE \
  ${SIGNING:+-PreleaseSigningProps="$SIGNING"} \
  "$@"
# AGP swallows aapt2 optimize failures and ships an APK with no manifest; refuse such APKs.
for apk in app/build/outputs/apk/*/*.apk; do
  [ -f "$apk" ] || continue
  listing=$(unzip -l "$apk")
  if ! grep -q ' AndroidManifest.xml$' <<<"$listing"; then
    echo "ERROR: $apk has no AndroidManifest.xml (aapt2 step failed silently)" >&2; exit 3
  fi
done
