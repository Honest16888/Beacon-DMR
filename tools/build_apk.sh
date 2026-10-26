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
# 步骤：
#   1. 把 web/dmr-listener.html 同步进 app/src/main/assets/index.html，
#      并校验字节一致 —— 两者一旦不同步，装到手机上的就是旧界面。
#   2. 调 gradlew 构建。
#   3. 把产物拷到 dist/，命名 beacon-dmr-<variant>.apk。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/android"
SRC="$ROOT/web/dmr-listener.html"
DST="$PROJ/app/src/main/assets/index.html"
VARIANT="${1:-release}"

[ -f "$SRC" ] || { echo "!! 找不到前端源文件 $SRC" >&2; exit 1; }

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
