#!/usr/bin/env bash
# 重新编译声码器 / FEC 的 native 库，并拷进纯 Java 版的 jniLibs。
#
#   bash tools/build_vocoder.sh
#
# 什么时候需要跑这个：
#   android/app/src/main/cpp/ 下任何 .cpp / .h 改了 —— 也就是
#   vocoder_jni.cpp（声码器插件加载、编解码、自检）、dmrtx_jni.cpp
#   （DMRD 帧组装 + FEC）、fec/*.cpp（RS / BPTC / Golay / Hamming / CRC）。
#
# 平时**不需要**跑：native-java/app/src/main/jniLibs/ 里已经放了
# 与 v2.5.5 正式包**逐字节相同**的六份 .so（两个 ABI ×
# libbeaconvoc / libdsvocoder / libc++_shared），APK 直接打包它们。
#
# 为什么不让 native-java 自己挂 externalNativeBuild：
#   本仓库路径含中文（新建文件夹），CMake 在 configure 阶段会直接以
#   -1073740791 退出，报错里只有一串乱码路径 —— 看起来像 NDK 崩了。
#   所以原生编译只在纯 ASCII 的镜像目录里做，产物拷回来。
#   这样 native-java 保持「纯 Java + 预编译 .so」的简单形态，
#   也就不用每次出包都过一遍 CMake。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_PROJ="$ROOT/android"
DST_LIBS="$ROOT/native-java/app/src/main/jniLibs"
MIRROR="${BUILD_MIRROR:-$HOME/beacon-dmr-android}"

[ -d "$SRC_PROJ/app/src/main/cpp" ] || {
  echo "!! 找不到 $SRC_PROJ/app/src/main/cpp" >&2; exit 1; }

if printf '%s' "$ROOT" | LC_ALL=C grep -q '[^ -~]'; then
  echo "==> 工程路径含非 ASCII，改在镜像目录编译：$MIRROR"
else
  echo "==> 路径是纯 ASCII，仍在镜像目录编译（保持一致，省得两套流程）"
fi
mkdir -p "$MIRROR"

# ---- 1. 同步到镜像 ----------------------------------------------------
# 只带编译需要的东西：cpp、jniLibs、gradle 工程文件。
# build/ 与 .cxx/ 是产物，不带走 —— 带着反而会让 Gradle 以为不用重编。
echo "==> 同步源码到镜像"
for d in app/src/main/cpp app/src/main/jniLibs; do
  mkdir -p "$MIRROR/$d"
  ( cd "$SRC_PROJ/$d" && tar -cf - --exclude=.cxx . ) | ( cd "$MIRROR/$d" && tar -xf - )
done
for f in settings.gradle build.gradle gradle.properties gradlew gradlew.bat; do
  [ -f "$SRC_PROJ/$f" ] && cp "$SRC_PROJ/$f" "$MIRROR/$f" || true
done
[ -d "$SRC_PROJ/gradle" ] && rm -rf "$MIRROR/gradle" && cp -r "$SRC_PROJ/gradle" "$MIRROR/gradle"
[ -f "$SRC_PROJ/app/build.gradle" ] && cp "$SRC_PROJ/app/build.gradle" "$MIRROR/app/build.gradle"

# sdk.dir 必须有，否则 Gradle 直接报「SDK location not found」
if [ ! -f "$MIRROR/local.properties" ]; then
  if [ -f "$SRC_PROJ/local.properties" ]; then
    cp "$SRC_PROJ/local.properties" "$MIRROR/local.properties"
  elif [ -n "${ANDROID_HOME:-}" ]; then
    esc="$(printf '%s' "$ANDROID_HOME" | sed 's/\\/\\\\/g; s/:/\\:/g')"
    printf 'sdk.dir=%s\n' "$esc" > "$MIRROR/local.properties"
  else
    echo "!! 缺 local.properties（sdk.dir），也没设 ANDROID_HOME" >&2; exit 1
  fi
fi

# ---- 2. 只编 native，不打整包 ----------------------------------------
# externalNativeBuildRelease 只跑 CMake/ndk-build，不 dex、不签名，快很多。
echo "==> 编译 native（这一步要几十秒）"
( cd "$MIRROR" && ./gradlew --no-daemon :app:externalNativeBuildRelease )

# ---- 3. 拷回 jniLibs --------------------------------------------------
echo "==> 拷回 jniLibs"
copied=0
for abi in arm64-v8a armeabi-v7a; do
  # 优先取 strip 过的（和正式包一致，体积小），没有就用 .cxx 里那份未 strip 的
  cand=""
  for c in \
    "$MIRROR/app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/$abi/libbeaconvoc.so" \
    $(ls -1 "$MIRROR"/app/build/intermediates/cxx/RelWithDebInfo/*/obj/"$abi"/libbeaconvoc.so 2>/dev/null | tail -1); do
    if [ -f "$c" ]; then cand="$c"; break; fi
  done
  if [ -z "$cand" ]; then
    echo "!! 没找到 $abi 的 libbeaconvoc.so —— 编译可能没成功" >&2
    exit 1
  fi
  mkdir -p "$DST_LIBS/$abi"
  cp "$cand" "$DST_LIBS/$abi/libbeaconvoc.so"
  printf '   %-14s %8d 字节  (%s)\n' "$abi" \
    "$(wc -c < "$DST_LIBS/$abi/libbeaconvoc.so")" "$(basename "$(dirname "$(dirname "$cand")")")"
  copied=$((copied + 1))
done

# ---- 4. 提醒确认 ------------------------------------------------------
cat <<'EOF'

==> 完成。接下来：
    1) 确认 libdsvocoder.so / libc++_shared.so 还在 jniLibs 里 —— 它们来自
       上游插件，不由这个脚本产出，别顺手删了。
    2) 出包：bash tools/build_java.sh
    3) 上机后进「设置 → 收发 → 自检」，把三份报告都看一遍：
         声码器：dlopen / create / 编码产出 三项都 ✓
         FEC 链：三帧十六进制不该是全 0
         接收链：Q1 交织往返「位级一致」
EOF
echo "   已更新 $copied 个 ABI。"
