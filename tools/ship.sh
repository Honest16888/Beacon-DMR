#!/usr/bin/env bash
# 一条命令完成交付：构建 → 核验 → push → 发 Release（带 APK）。
#
#   bash tools/ship.sh                # 完整交付（默认）
#   bash tools/ship.sh --no-release   # 只 push，不发版（改文档、改注释时用）
#   bash tools/ship.sh --dry-run      # 只打印将要做什么，不执行任何写操作
#
# ★ 前置：代码已经 commit 过。
#   脚本**不替你写提交信息** —— 提交信息要说清「改了什么、为什么」，
#   那是写代码的人才知道的事，机器生成的一定是废话。
#   工作区不干净时脚本会停下来告诉你，而不是把半成品推上去。
#
# 为什么要有这个脚本
# ------------------
# 手动交付是五步，每一步都可能漏：
#   1. export ANDROID_HOME + BUILD_MIRROR 再构建（漏了就报 SDK location not found /
#      中文路径构建失败）
#   2. 核验包里的 versionName 和 build.gradle 对得上
#   3. 把 dist/beacon-dmr-release.apk **复制**成 dist/beacon-dmr-<版本>-release.apk
#      （create_release.py 要的名字带版本号，而 build_apk.sh 产出的不带 ——
#       这一步最容易忘，忘了就是「发版失败：找不到 APK」）
#   4. git push（国内直连偶尔 408，要重试）
#   5. tools/create_release.py <版本>
# 只要漏第 3 步或者第 1 步的环境变量，就得从头再来一遍。固化下来一劳永逸。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/android"
DIST="$ROOT/dist"
GIT="git -C $ROOT"

# SDK 路径：环境里没有就用本机默认位置（这台机器的实际路径，换机器要改）
: "${ANDROID_HOME:=C:/Users/ThinkPink/AppData/Local/Android/Sdk}"
export ANDROID_HOME
# 构建镜像：工程路径含中文，CMake 会崩，必须在一份纯 ASCII 目录里构建
: "${BUILD_MIRROR:=/c/Users/ThinkPink/beacon-dmr-android}"
export BUILD_MIRROR

DO_RELEASE=1
DRY=0
for a in "$@"; do
  case "$a" in
    --no-release) DO_RELEASE=0 ;;
    --dry-run)    DRY=1 ;;
    -h|--help)    sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "!! 不认识的参数：$a" >&2; exit 2 ;;
  esac
done

say() { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }
run() { if [ "$DRY" = 1 ]; then echo "    [dry-run] $*"; else "$@"; fi; }

# ---------- 0. 环境检查 ----------

VER="$(grep -oE 'versionName "[^"]+"' "$PROJ/app/build.gradle" | head -1 | sed 's/.*"\(.*\)"/\1/')"
VC="$(grep -oE 'versionCode [0-9]+' "$PROJ/app/build.gradle" | head -1 | awk '{print $2}')"
[ -n "$VER" ] || die "读不到 versionName（看 android/app/build.gradle）"
say "版本      $VER (versionCode $VC)"

if ! $GIT diff --quiet || ! $GIT diff --cached --quiet; then
  $GIT status --short | head -10
  die "工作区不干净 —— 先 commit 再 ship。脚本不替你写提交信息。"
fi
say "工作区    干净"

BRANCH="$($GIT rev-parse --abbrev-ref HEAD)"
AHEAD="$($GIT rev-list --count "origin/$BRANCH..HEAD" 2>/dev/null || echo '?')"
say "分支      $BRANCH（领先 origin $AHEAD 个提交）"

# ---------- 1. 构建 ----------

step "构建 $VER"

if [ ! -d "$PROJ/app/src/main/java" ] && [ ! -f "$PROJ/app/src/main/assets/index.html" ]; then
  die "找不到 Android 工程：$PROJ"
fi

run env ANDROID_HOME="$ANDROID_HOME" BUILD_MIRROR="$BUILD_MIRROR" bash "$ROOT/tools/build_apk.sh" \
  || die "构建失败"

APK="$DIST/beacon-dmr-release.apk"
if [ "$DRY" = 0 ]; then
  [ -f "$APK" ] || die "没找到产物 $APK"
fi

# ---------- 2. 核验 ----------

step "核验"

PY=""
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
fi

if [ "$DRY" = 0 ]; then
  # 用 build-tools 自带的 aapt2 读清单 —— 别自己手写二进制 AndroidManifest 解析：
  # 资源字符串池的 UTF-8/UTF-16 标志、chunk 遍历都容易写错，而 aapt2 是官方工具，
  # 一行就出来。手写版第一版就返回了 `?`，静默失效比报错更糟。
  AAPT=""
  for d in $(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V -r); do
    for cand in "${d}aapt2.exe" "${d}aapt2"; do
      [ -f "$cand" ] && AAPT="$cand" && break 2
    done
  done

  if [ -n "$AAPT" ]; then
    BADGING="$("$AAPT" dump badging "$APK" 2>/dev/null | head -1)"
    GOT_NAME="$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
    GOT_CODE="$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
    # ★ 必须锚定 `^package: name=`。
    #   写成 `s/.*name='\([^']*\)'.*/` 会抓到行尾的 platformBuildVersionName='14' ——
    #   sed 的 .* 是贪婪的，而这一行里有三个 name='...'。第一版就是这样把包名读成 14 的。
    GOT_PKG="$(printf '%s' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
    say "  包名       ${GOT_PKG:-?}"
    say "  包内版本   ${GOT_NAME:-?} (versionCode ${GOT_CODE:-?})"
    [ "$GOT_NAME" = "$VER" ] || die "包内 versionName 是 $GOT_NAME，和 build.gradle 的 $VER 不一致（清单没刷新？）"
    [ "$GOT_CODE" = "$VC" ]  || die "包内 versionCode 是 $GOT_CODE，和 build.gradle 的 $VC 不一致"
    [ "$GOT_PKG" = "net.beacon.dmr" ] || die "包名是 $GOT_PKG，不是 net.beacon.dmr —— 老用户覆盖安装会装成两个 App"
  else
    say "  !! 找不到 aapt2，跳过清单核验（$ANDROID_HOME/build-tools）"
  fi

  # 签名：必须还是同一把 keystore，否则老用户没法覆盖安装
  APKSIGNER=""
  for d in $(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V -r); do
    for cand in "${d}apksigner.bat" "${d}apksigner"; do
      [ -f "$cand" ] && APKSIGNER="$cand" && break 2
    done
  done
  if [ -n "$APKSIGNER" ]; then
    if CERT="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | head -1)"; then
      say "  签名       ${CERT#*certificate DN: }"
    else
      die "签名校验失败"
    fi
  fi

  # 包内 HTML 必须和源文件逐字节一致 —— 这是最容易「改了但没进包」的地方，
  # build_apk.sh 里虽然已经 cmp 过一次，但那是构建前，这里查的是**成品**。
  if [ -n "$PY" ]; then
    "$PY" - "$APK" "$ROOT/web/index.html" <<'PYEOF' || die "包内 HTML 与 web/index.html 不一致"
import sys, zipfile, io, hashlib
z = zipfile.ZipFile(sys.argv[1])
a = z.read('assets/index.html'); b = io.open(sys.argv[2], 'rb').read()
print('  HTML       %d 字节 sha256 %s %s' % (len(a), hashlib.sha256(a).hexdigest()[:12],
      '一致' if a == b else '不一致'))
sys.exit(0 if a == b else 1)
PYEOF
  fi
fi

# ---------- 3. 复制成带版本号的名字 ----------

# create_release.py 认的是 dist/beacon-dmr-<版本>-release.apk，
# 而 build_apk.sh 产出的是不带版本号的 —— 必须复制一份。
NAMED="$DIST/beacon-dmr-$VER-release.apk"
step "准备发布文件"
run cp "$APK" "$NAMED"
say "  $NAMED"

if [ "$DO_RELEASE" = 1 ]; then
  NOTES="$DIST/release_notes_$(printf '%s' "$VER" | tr -d '.').md"
  if [ ! -f "$NOTES" ]; then
    # 没有手写说明就用最近的提交生成一份 —— 不完美，但比「发版失败」强
    step "没找到 $NOTES，用最近提交生成"
    if [ "$DRY" = 0 ]; then
      {
        printf '# %s\n\n' "$VER"
        $GIT log --pretty='## %s%n%n%b' -1
      } > "$NOTES"
    fi
  fi
  say "  说明文件   $(basename "$NOTES")"
fi

# ---------- 4. push ----------

step "push"
if [ "$DRY" = 1 ]; then
  echo "    [dry-run] git push origin $BRANCH"
else
  # 国内直连 GitHub 偶尔 408 —— 重试两次，第二次带上更宽松的传输参数
  if ! $GIT push origin "$BRANCH" 2>&1 | tail -3; then
    say "   第一次失败，放宽传输参数重试…"
    $GIT config http.postBuffer 524288000
    $GIT config http.version HTTP/1.1
    $GIT push origin "$BRANCH" || die "push 失败（网络问题的话过一会儿再跑一次 ship.sh）"
  fi
fi

# ---------- 5. 发 Release ----------

if [ "$DO_RELEASE" = 1 ]; then
  step "发 Release v$VER"
  if $GIT rev-parse "v$VER" >/dev/null 2>&1; then
    say "  tag v$VER 已存在 —— 跳过发版（同一个版本号发第二次会 422）"
  elif [ -z "$PY" ]; then
    say "  !! 没有 python，跳过发版"
  else
    if [ "$DRY" = 1 ]; then
      echo "    [dry-run] python tools/create_release.py $VER --notes $NOTES"
    else
      "$PY" "$ROOT/tools/create_release.py" "$VER" --notes "$NOTES" || die "发版失败"
    fi
  fi
else
  step "跳过发版（--no-release）"
fi

step "完成"
say "  版本   $VER"
say "  远程   $($GIT remote get-url origin)"
say "  提交   $($GIT rev-parse --short HEAD)"
if [ "$DO_RELEASE" = 1 ] && [ "$DRY" = 0 ]; then
  say "  下载   https://github.com/Honest16888/Beacon-DMR/releases/tag/v$VER"
fi
