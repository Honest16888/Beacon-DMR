#!/usr/bin/env bash
# 信标 DMR —— 一键构建 Android APK
#
#   bash tools/build_apk.sh          # 只出 release（默认）
#   bash tools/build_apk.sh debug    # 只出 debug
#   bash tools/build_apk.sh all      # 两个都出
#
# 前置条件：
#   - JDK 17
#   - Android SDK（compileSdk 34）+ NDK 26.1.10909125 + CMake 3.22.1
#   - 在 android/local.properties 里写好 sdk.dir，或设置 ANDROID_HOME 环境变量
#
# ⚠ 工程路径不能含非 ASCII 字符（中文、日文、带空格的目录）。
#   CMake 在 configure 阶段会直接以 -1073740791（STATUS_STACK_BUFFER_OVERRUN）
#   退出，报错信息里只有一串乱码路径，看起来像 NDK 崩了，其实是路径问题。
#   路径非 ASCII 时请用 BUILD_MIRROR 指一个纯 ASCII 目录，脚本会先同步过去再编：
#       BUILD_MIRROR=/f/Users/me/beacon-dmr-android bash tools/build_apk.sh
#
# 步骤：
#   1. 把 web/index.html 同步进 app/src/main/assets/index.html，
#      并校验字节一致 —— 两者一旦不同步，装到手机上的就是旧界面。
#   2. 调 gradlew 构建（设了 BUILD_MIRROR 就先同步到镜像目录）。
#   3. 把产物拷到 dist/，命名 beacon-dmr-<variant>.apk。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/android"
SRC="$ROOT/web/index.html"
DST="$PROJ/app/src/main/assets/index.html"
VARIANT="${1:-release}"

[ -f "$SRC" ] || { echo "!! 找不到前端源文件 $SRC" >&2; exit 1; }

# 路径里出现非 ASCII → 原生构建必崩。有镜像就走镜像，没有就明确报错，
# 别让用户对着一串乱码栈去猜。
if printf '%s' "$ROOT" | LC_ALL=C grep -q '[^ -~]'; then
  if [ -z "${BUILD_MIRROR:-}" ]; then
    cat >&2 <<'EOF'
!! 工程路径含非 ASCII 字符，CMake 无法在此构建。
   两种办法：
     1) 设 BUILD_MIRROR 指一个纯 ASCII 目录（推荐，脚本自动同步）：
          BUILD_MIRROR=/f/Users/me/beacon-dmr-android bash tools/build_apk.sh
     2) 把整个工程移到纯 ASCII 路径下再构建。
EOF
    exit 1
  fi
  echo "==> 路径含非 ASCII，改在镜像目录构建：$BUILD_MIRROR"
  mkdir -p "$BUILD_MIRROR"
  # app/ 里的源码、资源、jniLibs 都要带过去；build/ 与 .cxx/ 是产物，跳过。
  ( cd "$PROJ" && tar -cf - --exclude=app/build --exclude=app/.cxx \
      --exclude=.gradle --exclude=app/src/main/cpp/.cxx . ) \
    | ( cd "$BUILD_MIRROR" && tar -xf - )
  # 签名材料不进 git，但构建需要 —— 有就一起带过去
  for f in keystore.properties beacon-dmr.jks local.properties; do
    [ -f "$PROJ/$f" ] && cp "$PROJ/$f" "$BUILD_MIRROR/$f" || true
  done
  PROJ="$BUILD_MIRROR"
  DST="$PROJ/app/src/main/assets/index.html"
fi

# Windows（Git Bash / MSYS / Cygwin）走 .bat，其余走 sh
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) GRADLE="./gradlew.bat" ;;
  *)                    GRADLE="./gradlew" ;;
esac

echo "==> 同步前端资源"
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
if ! cmp -s "$SRC" "$DST"; then
  echo "!! 同步后字节不一致，中止" >&2
  exit 1
fi
echo "    $(wc -c < "$DST") 字节  sha256 $(sha256sum "$DST" | cut -c1-16)…"

mkdir -p "$ROOT/dist"

build_one() {
  local v="$1" cap apk
  cap="$(echo "${v:0:1}" | tr '[:lower:]' '[:upper:]')${v:1}"
  echo "==> 构建 $cap"
  ( cd "$PROJ" && "$GRADLE" "assemble$cap" --console=plain -q )

  # 配了 keystore.properties 时产出 app-release.apk，
  # 没配时 AGP 给的是 app-release-unsigned.apk —— 两种都要认。
  apk="$PROJ/app/build/outputs/apk/$v/app-$v.apk"
  if [ ! -f "$apk" ]; then
    apk="$PROJ/app/build/outputs/apk/$v/app-$v-unsigned.apk"
    [ -f "$apk" ] && echo "    注意：没有 keystore.properties，产出的是未签名包"
  fi
  [ -f "$apk" ] || { echo "!! 没找到产物（$PROJ/app/build/outputs/apk/$v/）" >&2; exit 1; }

  cp "$apk" "$ROOT/dist/beacon-dmr-$v.apk"
  echo "    -> dist/beacon-dmr-$v.apk  $(wc -c < "$ROOT/dist/beacon-dmr-$v.apk") 字节"
}

case "$VARIANT" in
  all)   build_one debug; build_one release ;;
  debug) build_one debug ;;
  *)     build_one release ;;
esac

echo "==> 完成"
ls -l "$ROOT"/dist/beacon-dmr-*.apk
