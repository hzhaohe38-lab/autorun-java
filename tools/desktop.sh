#!/usr/bin/env bash
# 在电脑上跑引擎 + 前端，用浏览器验证。
#
#   ./tools/desktop.sh          编译并启动，然后打开 http://127.0.0.1:3000
#   ./tools/desktop.sh --build  只编译不启动
#
# 和 APK 的区别只有调度方式：这里靠 5 秒轮询，手机上靠 AlarmManager。
# 其余（签名、排程、轨迹生成、落盘）是同一份代码。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
  *) SEP=":" ;;
esac

OUT="build/desktop"
JSON="libs/json.jar"
mkdir -p "$OUT"

if [ ! -f "$JSON" ]; then
  echo "缺少 $JSON，正在下载 org.json（桌面端没有 Android 自带的那个）"
  mkdir -p libs
  curl -sL -o "$JSON" "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
fi

echo "==> 编译（排除 Android 专属类）"
find app/android/app/src/main/java -name "*.java" \
  ! -name "MainActivity.java" \
  ! -name "AutorunService.java" \
  ! -name "WakeReceiver.java" \
  ! -name "BootReceiver.java" \
  ! -name "AndroidAssets.java" \
  > "$OUT/sources.txt"

javac -encoding UTF-8 -d "$OUT" -cp "$JSON" @"$OUT/sources.txt" \
  tools/desktop/DesktopMain.java tools/desktop/FileAssets.java

if [ "$1" = "--build" ]; then
  echo "编译完成: $OUT"
  exit 0
fi

echo "==> 启动"
exec java -cp "$OUT$SEP$JSON" com.qinghaipao.autorun.desktop.DesktopMain "$ROOT"
