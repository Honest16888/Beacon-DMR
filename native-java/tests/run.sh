#!/usr/bin/env bash
# 纯逻辑自检 —— 不需要 Android 设备，桌面 JDK 直接跑。
#
#   bash native-java/tests/run.sh
#
# 只覆盖不依赖 Android 的那两个类（MessagePack / μ-law）。
# 它们错了的表现是「连上了但没声音」，在手机上排查极贵，所以钉在桌面上。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/java/net/beacon/dmr/core"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -d "$OUT" \
  "$SRC/MsgPack.java" \
  "$SRC/MuLaw.java" \
  "$ROOT/tests/CoreSelfTest.java"

java -Dfile.encoding=UTF-8 -cp "$OUT" CoreSelfTest
