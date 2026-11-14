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

APK_DIR="$ROOT/output"
APK_NAME="氢氦跑-autorun-H${VERSION}.apk"

echo "==> 版本号: 氢氦跑-autorun-H${VERSION}"
node -e "
const fs=require('fs');
const p='app/src/utils/config.js';
let s=fs.readFileSync(p,'utf8');
const re=/version:\s*'[^']*'/;
if(!re.test(s)){ console.error('未找到 releaseInfo.version，放弃'); process.exit(1); }
// 先判断再替换：版本号本来就等于目标值时，替换前后内容相同，
// 用'内容是否变化'来判断会误报成找不到。
s=s.replace(re, \"version: '$VERSION'\");
fs.writeFileSync(p,s);
"

echo "==> 重建前端"
cd "$ROOT/app"
[ -d node_modules ] || npm install
npm run build

echo "==> 同步到 assets"
node "$ROOT/tools/sync-web.mjs"

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

SIZE=$(node -e "console.log((require('fs').statSync(process.argv[1]).size/1048576).toFixed(2)+' MB')" "$APK_DIR/$APK_NAME")
echo
echo "完成: $APK_DIR/$APK_NAME ($SIZE)"
echo "安装: adb install -r \"$APK_DIR/$APK_NAME\""
