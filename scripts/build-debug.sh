#!/usr/bin/env bash
# 服务器编译 debug APK（带 crashx 崩溃弹窗，用于本地调试）
#
# 用法:
#   ./scripts/build-debug.sh
#
# 环境变量（均可选）:
#   ANDROID_HOME        Android SDK 路径（默认探测 ANDROID_HOME/ANDROID_SDK_ROOT/~/android-sdk）
#   JAVA_HOME           JDK 21 路径（默认探测 PATH 中的 java，需为 JDK 21）
#   HA1_VERSION_SOURCE  版本来源标记（默认 debug）
#
# debug 变体使用 Android 默认 debug keystore（~/.android/debug.keystore，不存在时
# 构建会自动生成），无需提供 release keystore。包名带 .debug 后缀，可与 release 共存。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

fail() { echo "[build-debug] ERROR: $*" >&2; exit 1; }

# ---------- Java ----------
find_java() {
    local cands=(
        "${JAVA_HOME:-}"
        /usr/lib/jvm/*21*
        /opt/java/*21*
        "$HOME"/.sdkman/candidates/java/*21*
    )
    for c in "${cands[@]}"; do
        if [ -n "$c" ] && [ -x "$c/bin/java" ]; then
            echo "$c"; return 0
        fi
    done
    if command -v java >/dev/null 2>&1; then
        local home
        home=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
        echo "$home"; return 0
    fi
    return 1
}

JAVA_HOME_DETECTED=$(find_java) || fail "未找到 JDK 21，请用 JAVA_HOME= 指定，或先安装（apt install openjdk-21-jdk-headless）"
export JAVA_HOME="$JAVA_HOME_DETECTED"
echo "[build-debug] JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

# ---------- Android SDK ----------
detect_sdk() {
    for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "$HOME/Android/Sdk" /opt/android-sdk /usr/local/android-sdk; do
        if [ -n "$c" ] && [ -d "$c" ] && [ -d "$c/platforms" ]; then
            echo "$c"; return 0
        fi
    done
    return 1
}

SDK=$(detect_sdk) || true
if [ -z "$SDK" ]; then
    echo "[build-debug] 未找到 Android SDK，自动安装（探测网络选择官方/腾讯镜像）..."
    "$REPO_DIR/scripts/setup-android-sdk.sh"
    SDK="$HOME/android-sdk"
elif [ ! -d "$SDK/build-tools/36.0.0" ] || [ ! -d "$SDK/platforms/android-37" ]; then
    echo "[build-debug] SDK 缺少 build-tools 36.0.0 / android-37，自动补装..."
    ANDROID_HOME="$SDK" "$REPO_DIR/scripts/setup-android-sdk.sh"
fi
export ANDROID_HOME="$SDK"
echo "[build-debug] ANDROID_HOME=$ANDROID_HOME"

local_props="$REPO_DIR/local.properties"
if [ ! -f "$local_props" ]; then
    echo "sdk.dir=$SDK" > "$local_props"
    echo "[build-debug] 已生成 local.properties"
fi

# ---------- Build ----------
chmod +x gradlew
echo "[build-debug] 开始 assembleDebug ..."
HA1_VERSION_SOURCE="${HA1_VERSION_SOURCE:-debug}" ./gradlew assembleDebug --no-daemon "$@"

APK=$(ls -t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1) \
    || fail "构建完成但未找到 APK"
echo "[build-debug] 完成: $APK"
ls -lh "$APK"
