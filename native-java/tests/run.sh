#!/usr/bin/env bash
# 纯逻辑自检 —— 不需要 Android 设备，桌面 JDK 直接跑。
#
#   bash native-java/tests/run.sh
#
# 分两组：
#   1) MsgPack / μ-law —— 完全无 Android 依赖，直接编
#   2) 话组表合并 —— 只测纯函数 merge()，但要靠 android.jar 才编得过
#      （TalkgroupRepo 里有 Context 参数）。找不到 android.jar 就跳过这一组。
#
# 这些错了的表现是「连上了但没声音」「列表里全是英文名」，在手机上排查极贵。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/app/src/main/java/net/beacon/dmr/core"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -d "$OUT" \
  "$CORE/MsgPack.java" \
  "$CORE/MuLaw.java" \
  "$ROOT/tests/CoreSelfTest.java"
java -Dfile.encoding=UTF-8 -cp "$OUT" CoreSelfTest

# ---- android.jar（只为编译，不执行任何需要 Context 的代码）----
ANDROID_JAR=""
for cand in \
  "${ANDROID_HOME:-}/platforms/android-34/android.jar" \
  "C:/Users/ThinkPink/AppData/Local/Android/Sdk/platforms/android-34/android.jar" \
  "$HOME/AppData/Local/Android/Sdk/platforms/android-34/android.jar"; do
  if [ -f "$cand" ]; then ANDROID_JAR="$cand"; break; fi
done

if [ -z "$ANDROID_JAR" ]; then
  echo
  echo "（跳过话组表合并测试：没找到 android.jar，设 ANDROID_HOME 可启用）"
  exit 0
fi

OUT2="$(mktemp -d)"
trap 'rm -rf "$OUT" "$OUT2"' EXIT
javac -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT2" \
  "$CORE/Talkgroups.java" \
  "$CORE/TalkgroupRepo.java" \
  "$ROOT/tests/TgMergeTest.java"

# ★ classpath 分隔符必须是 ';' —— Windows 上的 java.exe 只认分号。
# 写成 "$OUT2:$ANDROID_JAR" 的话它会把整串当成**一个**路径，
# 于是报 "找不到或无法加载主类 TgMergeTest"（ClassNotFoundException），
# 看起来像编译没产出 class，其实编译是好的。用 cygpath 转成纯 Windows 路径，
# 免得 Git Bash 再把 C:/... 和 \ 混着塞进去。
if command -v cygpath >/dev/null 2>&1; then
  CP2="$(cygpath -w "$OUT2");$(cygpath -w "$ANDROID_JAR")"
else
  CP2="$OUT2;$ANDROID_JAR"
fi
java -Dfile.encoding=UTF-8 -cp "$CP2" TgMergeTest
