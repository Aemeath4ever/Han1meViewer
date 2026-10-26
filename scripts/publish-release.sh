#!/usr/bin/env bash
# 把构建产物发布到 GitHub Releases。
#
# 不走 actions/upload-artifact：APK 构建完直接交给 gh CLI 传到 Releases，
# 不占用 Artifacts 存储（也就没有 retention-days 可设）。
#
# 用法:
#   GH_TOKEN=xxx ./scripts/publish-release.sh \
#     --tag v1.1.0-ci+25102522 \
#     --title "1.1.0-ci+25102522" \
#     --notes-file notes.md \
#     --prerelease \
#     -- app/build/outputs/apk/release/Han1meViewer-v1.1.0-ci+25102522-arm64-v8a.apk
#
# 行为:
#   - tag 对应的 release 已存在：更新标题/说明，并用 --clobber 覆盖同名资产
#     （debug 构建的产物文件名固定，靠这一条实现“滚动更新”）。
#   - notes 文件为空：新建时用 --generate-notes；已有 release 保留原说明。
#   - --target 只在新建 release（顺带创建 tag）时生效，默认 $GITHUB_SHA / 当前 HEAD。
set -euo pipefail

TAG=""
TITLE=""
NOTES_FILE=""
PRERELEASE=0
TARGET="${GITHUB_SHA:-}"
FILES=()

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG="${2:?--tag 需要参数}"; shift 2 ;;
        --title) TITLE="${2:?--title 需要参数}"; shift 2 ;;
        --notes-file) NOTES_FILE="${2:?--notes-file 需要参数}"; shift 2 ;;
        --prerelease) PRERELEASE=1; shift ;;
        --target) TARGET="${2:?--target 需要参数}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        --) shift; while [ $# -gt 0 ]; do FILES+=("$1"); shift; done ;;
        -*) echo "[publish] 未知参数: $1" >&2; exit 2 ;;
        *) FILES+=("$1"); shift ;;
    esac
done

[ -n "$TAG" ] || { echo "[publish] 缺少 --tag" >&2; exit 2; }
[ ${#FILES[@]} -gt 0 ] || { echo "[publish] 至少需要一个产物文件" >&2; exit 2; }
for f in "${FILES[@]}"; do
    [ -f "$f" ] || { echo "[publish] 文件不存在: $f" >&2; exit 2; }
done
command -v gh >/dev/null 2>&1 || { echo "[publish] 未找到 gh CLI" >&2; exit 2; }
: "${GH_TOKEN:?请设置 GH_TOKEN（或 GH_ENTERPRISE_TOKEN）}"

NOTES_ARGS=()
if [ -n "$NOTES_FILE" ] && [ -s "$NOTES_FILE" ]; then
    NOTES_ARGS=(--notes-file "$NOTES_FILE")
fi

if gh release view "$TAG" >/dev/null 2>&1; then
    echo "[publish] release 已存在，更新: $TAG"
    EDIT_ARGS=()
    [ -n "$TITLE" ] && EDIT_ARGS+=(--title "$TITLE")
    [ ${#NOTES_ARGS[@]} -gt 0 ] && EDIT_ARGS+=("${NOTES_ARGS[@]}")
    [ "$PRERELEASE" -eq 1 ] && EDIT_ARGS+=(--prerelease)
    if [ ${#EDIT_ARGS[@]} -gt 0 ]; then
        gh release edit "$TAG" "${EDIT_ARGS[@]}"
    fi
    gh release upload "$TAG" "${FILES[@]}" --clobber
else
    echo "[publish] 新建 release: $TAG"
    CREATE_ARGS=(--title "${TITLE:-$TAG}")
    if [ ${#NOTES_ARGS[@]} -gt 0 ]; then
        CREATE_ARGS+=("${NOTES_ARGS[@]}")
    else
        CREATE_ARGS+=(--generate-notes)
    fi
    [ "$PRERELEASE" -eq 1 ] && CREATE_ARGS+=(--prerelease)
    [ -n "$TARGET" ] && CREATE_ARGS+=(--target "$TARGET")
    gh release create "$TAG" "${FILES[@]}" "${CREATE_ARGS[@]}"
fi

echo "[publish] 完成: $(gh release view "$TAG" --json url -q .url 2>/dev/null || echo "$TAG")"
