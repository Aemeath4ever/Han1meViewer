#!/usr/bin/env bash
# 预装 Android SDK 组件（自动检测网络：官方可达走官方，不可达走腾讯镜像）
#
# 用法:
#   ./scripts/setup-android-sdk.sh
#
# 环境变量（可选）:
#   ANDROID_HOME  SDK 安装目录（默认 ~/android-sdk）
#   HA1_MIRROR    on=强制镜像 / off=强制官方（默认自动探测 dl.google.com:443）
#   安装: build-tools 36.0.0 / platforms;android-37 / platform-tools
#   装完后 Gradle 不会再尝试联网补装组件（已接受 license、组件齐全）。
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail() { echo "[setup-sdk] ERROR: $*" >&2; exit 1; }

probe_google() {
    if [ -n "${HA1_MIRROR:-}" ]; then
        case "$HA1_MIRROR" in on|true) return 1 ;; off|false) return 0 ;; esac
    fi
    (exec 3<>/dev/tcp/dl.google.com/443) 2>/dev/null
}

if probe_google; then
    BASE="https://dl.google.com/android/repository"
    echo "[setup-sdk] dl.google.com 可达，使用官方源"
else
    BASE="https://mirrors.cloud.tencent.com/AndroidSDK"
    echo "[setup-sdk] dl.google.com 不可达，使用腾讯镜像"
fi

mkdir -p "$SDK/licenses"

# ---------- download + unzip helper ----------
fetch() { # fetch <官方zip名> <镜像zip名> <dest-dir>
    local zip_official="$1" zip_mirror="$2" dest="$3"
    local zip="$zip_official"
    if [ "$BASE" != "https://dl.google.com/android/repository" ]; then
        zip="$zip_mirror"
    fi
    echo "[setup-sdk] 下载 $zip ..."
    curl -fL --retry 3 --retry-delay 2 -o "$TMP/$zip" "$BASE/$zip" \
        || fail "下载 $zip 失败（检查网络或手动下载后重试）"
    echo "[setup-sdk] 解压 $zip -> $dest"
    mkdir -p "$dest"
    unzip -q -o "$TMP/$zip" -d "$dest"
}

# ---------- licenses（与 sdkmanager 自动接受的内容一致）----------
cat > "$SDK/licenses/android-sdk-license" <<'EOF'
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
EOF
cat > "$SDK/licenses/android-sdk-preview-license" <<'EOF'
84831b9409646a918e30573bab4c9c9131d51d38
EOF

# ---------- packages ----------
fetch "build-tools_r36_linux.zip" "build-tools_r36_linux.zip" "$TMP/bt"
rm -rf "$SDK/build-tools/36.0.0"
mkdir -p "$SDK/build-tools/36.0.0"
mv "$TMP/bt"/*/* "$SDK/build-tools/36.0.0/" 2>/dev/null || mv "$TMP/bt"/* "$SDK/build-tools/36.0.0/"

fetch "platform-37.0_r02.zip" "platform-37.0_r02.zip" "$TMP/pf"
rm -rf "$SDK/platforms/android-37"
mkdir -p "$SDK/platforms/android-37"
mv "$TMP/pf"/*/* "$SDK/platforms/android-37/" 2>/dev/null || mv "$TMP/pf"/* "$SDK/platforms/android-37/"

fetch "platform-tools_r37.0.1-linux.zip" "platform-tools_r37.0.1-linux.zip" "$TMP/pt"
rm -rf "$SDK/platform-tools"
mkdir -p "$SDK/platform-tools"
mv "$TMP/pt"/*/* "$SDK/platform-tools/" 2>/dev/null || mv "$TMP/pt"/* "$SDK/platform-tools/"

# ---------- verify ----------
echo "[setup-sdk] 校验安装结果:"
[ -x "$SDK/build-tools/36.0.0/aapt2" ] || fail "build-tools 36.0.0 缺少 aapt2"
[ -f "$SDK/platforms/android-37/android.jar" ] || fail "platforms;android-37 缺少 android.jar"
[ -x "$SDK/platform-tools/adb" ] || fail "platform-tools 缺少 adb"

echo "[setup-sdk] 完成: $SDK"
echo "[setup-sdk] 编译时确保 ANDROID_HOME=$SDK（build-release.sh / build-debug.sh 会自动探测该路径）"
