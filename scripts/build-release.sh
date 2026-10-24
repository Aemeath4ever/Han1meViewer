#!/usr/bin/env bash
# 服务器编译 release APK（与 CI 流程一致，仅 release 变体）
#
# 用法:
#   ./scripts/build-release.sh
#
# 环境变量（必填）:
#   KEYSTORE_PASSWORD   keystore 密码（CI 上的 YOUR_KEYSTORE_PASSWORD）
#   KEY_ALIAS           key 别名（CI 上的 KEY_ALIAS）
#   HA_GITHUB_TOKEN     上游仓库 token（与 CI 一致，可留空）
#
# 可选环境变量:
#   ANDROID_HOME        Android SDK 路径（默认探测 ANDROID_HOME/ANDROID_SDK_ROOT/~/android-sdk）
#   JAVA_HOME           JDK 21 路径（默认探测 PATH 中的 java，需为 JDK 21）
#   KEYSTORE_SRC        keystore 文件路径（默认 $HOME/.android/keystore.jks 已存在则直接用）
#
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

fail() { echo "[build-release] ERROR: $*" >&2; exit 1; }

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
echo "[build-release] JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

# ---------- Android SDK ----------
detect_sdk() {
    for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "$HOME/Android/Sdk" /opt/android-sdk /usr/local/android-sdk; do
        if [ -n "$c" ] && [ -d "$c" ] && [ -d "$c/platforms" ]; then
            echo "$c"; return 0
        fi
    done
    return 1
}

SDK=$(detect_sdk) || fail "未找到 Android SDK，请用 ANDROID_HOME= 指定（需要 platforms;android-$(grep -oP 'compile\.sdk=\K\d+' gradle.properties 2>/dev/null || echo 35) 和 build-tools）"
export ANDROID_HOME="$SDK"
echo "[build-release] ANDROID_HOME=$ANDROID_HOME"

local_props="$REPO_DIR/local.properties"
if [ ! -f "$local_props" ]; then
    echo "sdk.dir=$SDK" > "$local_props"
    echo "[build-release] 已生成 local.properties"
fi

# ---------- Keystore ----------
KEYSTORE_DST="$HOME/.android/keystore.jks"
if [ -n "${KEYSTORE_SRC:-}" ]; then
    mkdir -p "$(dirname "$KEYSTORE_DST")"
    cp "$KEYSTORE_SRC" "$KEYSTORE_DST"
    echo "[build-release] 已从 $KEYSTORE_SRC 复制 keystore"
fi
[ -f "$KEYSTORE_DST" ] || fail "缺少 keystore: $KEYSTORE_DST（可用 KEYSTORE_SRC= 指定路径）"
: "${KEYSTORE_PASSWORD:?请设置 KEYSTORE_PASSWORD}"
: "${KEY_ALIAS:?请设置 KEY_ALIAS}"

# ---------- Build ----------
chmod +x gradlew
echo "[build-release] 开始 assembleRelease ..."
./gradlew assembleRelease --no-daemon "$@"

APK=$(ls -t app/build/outputs/apk/release/Han1meViewer-v*.apk 2>/dev/null | head -1) \
    || fail "构建完成但未找到 APK"
echo "[build-release] 完成: $APK"
ls -lh "$APK"
