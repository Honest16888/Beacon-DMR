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
# ⚠ 这一份**不挂 NDK / CMake**：声码器与 FEC 的 native 库是**预编译产物**，
#   放在 native-java/app/src/main/jniLibs/ 下直接打包（六份：两个 ABI ×
#   libbeaconvoc / libdsvocoder / libc++_shared，与 v2.5.5 正式包逐字节相同）。
#   所以这里不存在「中文路径把 CMake 搞崩」那个坑，可以直接在工程目录里编。
#   改了 android/app/src/main/cpp/ 下的 .cpp/.h 之后，先跑
#       bash tools/build_vocoder.sh
#   在纯 ASCII 镜像目录里重编并把 .so 拷回来，再跑本脚本出包。
#
#   ★ jniLibs 里的 .so 一律不许 strip（见 app/build.gradle 的 packaging 段）——
#     libdsvocoder.so 是第三方预编译插件，改它的二进制等于赌 dlsym 的符号还在。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/native-java"
TOOLS="$ROOT/tools"
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

# ★ JNI 符号一致性：Java 的 native 声明必须和 .so 的 .dynsym 对得上。
# 这一步放在编译之前 —— 对不上的话编出来的包一按 PTT 就 UnsatisfiedLinkError，
# 而那个报错信息（"is the library loaded?"）会把人往「.so 没打进包」上带，
# 实际是包名改过、符号名还是旧的。查一次比在真机上猜一轮便宜得多。
echo "==> JNI 符号一致性"
python3 "$TOOLS/check_jni.py" --root "$PROJ/../native-java/app/src/main" --quiet 2>/dev/null \
  || python "$TOOLS/check_jni.py" --root "$PROJ/../native-java/app/src/main" --quiet

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
