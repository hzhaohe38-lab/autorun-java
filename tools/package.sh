#!/usr/bin/env bash
# 打包正式 APK。
#
#   ./tools/package.sh 2.2      → 氢氦跑-autorun-H2.2.apk
#   ./tools/package.sh H3.0     → 氢氦跑-autorun-H3.0.apk
#
# 做的事：
#   1. 把版本号写进 app/src/utils/config.js（"我的"页面会显示）
#   2. 重建前端并同步到 assets
#   3. 构建 APK
#   4. 复制并按版本号命名到旧的 APK 目录
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -z "$1" ]; then
  echo "用法: ./tools/package.sh <版本号>"
  echo "例如: ./tools/package.sh 2.2    → 氢氦跑-autorun-H2.2.apk"
  echo
  echo "当前版本:"
  grep -o "version: '[^']*'" app/src/utils/config.js | head -1
  exit 1
fi

VERSION="${1#H}"
VERSION="${VERSION#h}"
if ! echo "$VERSION" | grep -qE '^[0-9]+(\.[0-9]+)*$'; then
  echo "版本号格式不对: $1（应该是 2.2 这样的数字）" >&2
  exit 1
fi

APK_DIR="D:/StudyProjects/byerun-web/APK"
APK_NAME="氢氦跑-autorun-H${VERSION}.apk"

echo "==> 版本号: 氢氦跑-autorun-H${VERSION}"
node -e "
const fs=require('fs');
const p='app/src/utils/config.js';
let s=fs.readFileSync(p,'utf8');
const before=s;
s=s.replace(/version:\s*'[^']*'/, \"version: '$VERSION'\");
if(s===before){ console.error('未找到 releaseInfo.version，放弃'); process.exit(1); }
fs.writeFileSync(p,s);
"

echo "==> 重建前端"
cd "$ROOT/app"
[ -d node_modules ] || npm install
npm run build

echo "==> 同步到 assets"
DEST="$ROOT/app/android/app/src/main/assets/web"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r "$ROOT/app/dist/." "$DEST/"
rm -f "$DEST"/assets/remixicon-*.svg
rm -f "$DEST"/assets/remixicon-*.eot
rm -f "$DEST"/assets/remixicon-*.ttf
rm -f "$DEST"/assets/remixicon-*.woff

echo "==> 构建 APK"
cd "$ROOT/app/android"
./gradlew assembleDebug

SRC="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$SRC" ]; then
  echo "构建失败，未找到 $SRC" >&2
  exit 1
fi

mkdir -p "$APK_DIR"
cp "$SRC" "$APK_DIR/$APK_NAME"

SIZE=$(du -h "$APK_DIR/$APK_NAME" | cut -f1)
echo
echo "完成: $APK_DIR/$APK_NAME ($SIZE)"
echo "安装: adb install -r \"$APK_DIR/$APK_NAME\""
