#!/usr/bin/env bash
# 轻传 LiteSend 构建脚本 —— Termux(bionic) + 本机固定工具链
#
# 用法：
#   bash build.sh                          # 构建 debug APK
#   bash build.sh :app:compileDebugKotlin  # 只过 Kotlin 编译（最快暴露代码错误）
#   bash build.sh :app:assembleRelease     # release APK（R8 压缩，已签名）
#   bash build.sh :app:testDebugUnitTest   # JVM 单测（协议/引擎）
#   bash build.sh clean                    # 清理
#
# 日志落盘：$HOME/tmp/litesend-build.log
set -u

PREFIX="${TERMUX__PREFIX:-/data/user/0/com.dsharnessmobile.shell/files/usr}"
BUILD_HOME="${BUILD_HOME:-/data/user/0/com.dsharnessmobile.shell/files/home/build}"

export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$BUILD_HOME/gradle-home"
export ANDROID_HOME=/storage/emulated/0/WORK/02-工具链/android-sdk
export ANDROID_USER_HOME="$BUILD_HOME/android-user-home"
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Djava.io.tmpdir=$HOME/tmp -Dfile.encoding=UTF-8"
export JAVA_OPTS="-Djdk.lang.Process.launchMechanism=FORK ${JAVA_OPTS:-}"
export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_BIN="$BUILD_HOME/gradle-8.11.1/bin/gradle"
LOG="$HOME/tmp/litesend-build.log"
mkdir -p "$HOME/tmp" "$ANDROID_USER_HOME"

if [ $# -eq 0 ]; then
  set -- :app:assembleDebug
fi

cd "$PROJ_DIR" || exit 1

bash "$GRADLE_BIN" "$@" \
  --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$BUILD_HOME/tools/aapt2" \
  2>&1 | tee "$LOG"

BUILD_STATUS=${PIPESTATUS[0]}
echo "BUILD EXIT=$BUILD_STATUS"
exit "$BUILD_STATUS"
