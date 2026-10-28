#!/usr/bin/env bash
# 信标 DMR · 纯 Java 版 —— 一键构建 APK
#
#   bash tools/build_java.sh            # release（默认）
#   bash tools/build_java.sh debug
#
# 和 tools/build_apk.sh（WebView 版）互不影响，两个 APK 可以同时装在手机上：
#   WebView 版 applicationId = net.beacon.dmr
#   Java  版 applicationId = net.beacon.dmr.java
#
# ⚠ 这一份**没有 NDK / CMake**（Phase 0 只做收听，不需要声码器），
#   所以不存在「中文路径把 CMake 搞崩」那个坑，可以直接在工程目录里编。
#   等做到收发、要把 vocoder 的 .so 引进来时，再照 build_apk.sh 那套镜像逻辑办。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/native-java"
VARIANT="${1:-release}"
DIST="$ROOT/dist"

[ -d "$PROJ/app/src/main/java" ] || { echo "!! 找不到 Java 工程 $PROJ" >&2; exit 1; }

# local.properties 只在第一次需要；SDK 路径从环境变量或已有文件里来。
if [ ! -f "$PROJ/local.properties" ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then
    # 冒号与反斜杠都要转义，这是 .properties 的语法要求
    esc="$(printf '%s' "$ANDROID_HOME" | sed 's/\\/\\\\/g; s/:/\\:/g')"
    printf 'sdk.dir=%s\n' "$esc" > "$PROJ/local.properties"
    echo "==> 已按 ANDROID_HOME 写入 local.properties"
  else
    echo "!! 缺 native-java/local.properties（sdk.dir）且没有 ANDROID_HOME" >&2
    exit 1
  fi
fi

echo "==> 纯逻辑自检"
bash "$PROJ/tests/run.sh" | tail -3

echo "==> 构建 $VARIANT"
( cd "$PROJ" && ./gradlew --no-daemon ":app:assemble${VARIANT^}" )

APK_SRC="$PROJ/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$APK_SRC" ] || { echo "!! 没找到产物 $APK_SRC" >&2; exit 1; }

mkdir -p "$DIST"
OUT="$DIST/beacon-dmr-java-$VARIANT.apk"
cp "$APK_SRC" "$OUT"

echo "==> 完成"
ls -l "$OUT" | awk '{print "    " $5 " 字节  " $9}'
python - "$OUT" <<'PY'
import hashlib, io, sys
p = sys.argv[1]
print('    sha256', hashlib.sha256(io.open(p, 'rb').read()).hexdigest())
PY
