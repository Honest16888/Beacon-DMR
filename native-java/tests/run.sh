#!/usr/bin/env bash
# 纯逻辑自检 —— 不需要 Android 设备，桌面 JDK 直接跑。
#
#   bash native-java/tests/run.sh
#
# 分两大组：
#   1) 完全不依赖 Android 的类（直接编、直接跑）
#        CoreSelfTest    MsgPack / μ-law
#        GeoTest         话组号 → 国家/地区
#        AudioChainTest  增益 / 窄带增强 / 软削波
#        StatsTest       看板统计口径
#   2) 话组表合并（要靠 android.jar 才编得过，因为 TalkgroupRepo 里有 Context）。
#      找不到 android.jar 就跳过这一组，不算失败。
#
# 这些错了的表现是「连上了但没声音」「列表里全是英文名」「看板数字对不上」，
# 在手机上排查极贵 —— 所以全部钉在桌面上。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_DIR="$ROOT/app/src/main/java/net/beacon/dmr"
CORE="$JAVA_DIR/core"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# ★ classpath 分隔符必须是 ';' —— Windows 上的 java.exe 只认分号。
# 写成 "$OUT:$ANDROID_JAR" 的话它会把整串当成**一个**路径，于是报
# "找不到或无法加载主类"（ClassNotFoundException），看起来像编译没产出
# class，其实编译一直是好的。用 cygpath 转成纯 Windows 路径再拼。
cp_join() {
  if command -v cygpath >/dev/null 2>&1; then
    local out=""
    for p in "$@"; do
      [ -n "$out" ] && out="$out;"
      out="$out$(cygpath -w "$p")"
    done
    printf '%s' "$out"
  else
    local IFS=';'
    printf '%s' "$*"
  fi
}

echo "==> 1/2 不依赖 Android 的纯逻辑"
javac -encoding UTF-8 -d "$OUT" \
  "$CORE/MsgPack.java" \
  "$CORE/MuLaw.java" \
  "$CORE/Geo.java" \
  "$CORE/HostCheck.java" \
  "$JAVA_DIR/audio/AudioChain.java" \
  "$JAVA_DIR/live/LiveStore.java" \
  "$JAVA_DIR/vocoder/Vocoder.java" \
  "$JAVA_DIR/DmrTx.java" \
  "$JAVA_DIR/mmdvm/DmrRx.java" \
  "$ROOT/tests/CoreSelfTest.java" \
  "$ROOT/tests/GeoTest.java" \
  "$ROOT/tests/AudioChainTest.java" \
  "$ROOT/tests/StatsTest.java" \
  "$ROOT/tests/DmrRxTest.java" \
  "$ROOT/tests/HostCheckTest.java"

for t in CoreSelfTest GeoTest AudioChainTest StatsTest DmrRxTest HostCheckTest; do
  java -Dfile.encoding=UTF-8 -cp "$(cp_join "$OUT")" "$t"
done

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

echo
echo "==> 2/2 话组表合并（借 android.jar 编译）"
OUT2="$(mktemp -d)"
trap 'rm -rf "$OUT" "$OUT2"' EXIT
javac -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT2" \
  "$CORE/Talkgroups.java" \
  "$CORE/TalkgroupRepo.java" \
  "$ROOT/tests/TgMergeTest.java"

java -Dfile.encoding=UTF-8 -cp "$(cp_join "$OUT2" "$ANDROID_JAR")" TgMergeTest
