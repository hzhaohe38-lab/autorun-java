#!/usr/bin/env bash
# 构建 APK。
#
#   ./tools/build.sh          直接用已提交的前端产物出包
#   ./tools/build.sh --web    先重建前端（需要 node + npm install），再出包
#
# 产物：app/android/app/build/outputs/apk/debug/app-debug.apk
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ "$1" = "--web" ]; then
  echo "==> 重建前端"
  cd "$ROOT/app"
  [ -d node_modules ] || npm install
  npm run build

  echo "==> 同步到 assets"
  node "$ROOT/tools/sync-web.mjs"
fi

echo "==> 构建 APK"
cd "$ROOT/app/android"
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  SIZE=$(du -h "$APK" | cut -f1)
  echo
  echo "完成: app/android/$APK ($SIZE)"
else
  echo "构建失败，未找到 app/android/$APK" >&2
  exit 1
fi
