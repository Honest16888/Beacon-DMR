#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
布局 ⇄ 代码 的一致性检查。

背景
----
这个工程没有 emulator（`adb devices` 是空的），所有界面改动都只能靠真机验收。
而 `findViewById(R.id.x)` 拿不到控件时**不会编译报错**，只会在运行时返回 null，
再往下走一步就是 NPE —— 用户看到的是一句「应用已停止运行」，堆栈里那条
`MainActivity.onCreate` 完全不指向真正写错的那一行。

所以把能在桌面上查的全查掉：

1. Java 里 `R.id.foo` 引用的 id，在某个 layout 里必须真的存在。
2. 同一个 layout 里不能有重复 id。
3. `@drawable/` `@style/` `@color/` `@string/` `@attr/` 引用必须解析得到。
4. `@+id` 定义了但代码里从没用过 —— 只提示，不算错（布局里有些节点就是纯装饰）。

用法
----
    python tools/check_layout.py                  # 默认查 native-java
    python tools/check_layout.py --root <工程目录>
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

RE_JAVA_ID = re.compile(r"\bR\.id\.(\w+)")
RE_REF = re.compile(r"@(drawable|style|color|string|attr|layout|dimen|mipmap)/(\w+)")


def walk_xml(root_dir, sub):
    out = []
    d = os.path.join(root_dir, sub)
    for dirpath, _dn, files in os.walk(d):
        for f in files:
            if f.endswith(".xml"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def collect_ids(layout_files):
    """
    返回 (per_file, all_ids)：
      per_file = {相对路径: [id...]}（用于查重复）
      all_ids  = 全部 @+id 名字集合
    """
    per_file = {}
    all_ids = set()
    for p in layout_files:
        ids = []
        try:
            tree = ET.parse(p)
        except ET.ParseError as e:
            print("✗ XML 解析失败 {}: {}".format(p, e))
            continue
        for el in tree.iter():
            v = el.get(ANDROID_NS + "id")
            if v and v.startswith("@+id/"):
                ids.append(v[len("@+id/"):])
        per_file[p] = ids
        all_ids.update(ids)
    return per_file, all_ids


def collect_java_ids(java_files):
    """返回 {id: [(文件, 行号)]}"""
    out = {}
    for p in java_files:
        with open(p, encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f, 1):
                for m in RE_JAVA_ID.finditer(line):
                    out.setdefault(m.group(1), []).append((p, i))
    return out


def defined_resources(res_dir):
    """扫描 values/ 与各种 res 子目录，收集已定义的资源名。"""
    have = {"drawable": set(), "color": set(), "string": set(),
            "style": set(), "attr": set(), "layout": set(),
            "dimen": set(), "mipmap": set()}

    for sub in ("values", "drawable", "layout", "mipmap", "color", "drawable-night"):
        d = os.path.join(res_dir, sub)
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if sub == "values" and f.endswith(".xml"):
                try:
                    tree = ET.parse(os.path.join(d, f))
                except ET.ParseError:
                    continue
                for el in tree.iter():
                    tag, name = el.tag, el.get("name")
                    if not name:
                        continue
                    if tag == "string":
                        have["string"].add(name)
                    elif tag == "color":
                        have["color"].add(name)
                    elif tag == "style":
                        have["style"].add(name)
                    elif tag == "attr":
                        have["attr"].add(name)
                    elif tag == "dimen":
                        have["dimen"].add(name)
            elif f.endswith(".xml"):
                key = "drawable" if sub.startswith("drawable") else sub
                have[key].add(os.path.splitext(f)[0])
    return have


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default = os.path.join(os.path.dirname(here), "native-java", "app", "src", "main")

    ap = argparse.ArgumentParser(description="布局与代码一致性检查")
    ap.add_argument("--root", default=default, help="含 java/ 与 res/ 的 main 目录")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    main_dir = args.root
    res_dir = os.path.join(main_dir, "res")
    java_dir = os.path.join(main_dir, "java")
    if not os.path.isdir(res_dir):
        print("✗ 找不到 res 目录：{}".format(res_dir))
        return 2

    layouts = walk_xml(res_dir, "layout")
    per_file, layout_ids = collect_ids(layouts)

    javas = []
    for dirpath, _dn, files in os.walk(java_dir):
        if os.sep + "build" + os.sep in dirpath + os.sep:
            continue
        for f in files:
            if f.endswith(".java"):
                javas.append(os.path.join(dirpath, f))

    java_ids = collect_java_ids(javas)
    have = defined_resources(res_dir)

    errors, warns = [], []

    # ---- 1. 代码引用了不存在的 id
    for rid in sorted(java_ids):
        if rid not in layout_ids:
            for p, ln in java_ids[rid]:
                errors.append("{}:{}  R.id.{} 在任何 layout 里都不存在".format(
                    os.path.basename(p), ln, rid))

    # ---- 2. 同一个 layout 内重复 id
    for p, ids in per_file.items():
        seen = set()
        for i in ids:
            if i in seen:
                errors.append("{} 里 id `{}` 出现了两次（findViewById 只会拿到第一个）".format(
                    os.path.relpath(p, res_dir), i))
            seen.add(i)

    # ---- 3. 资源引用解析
    # 先把 XML 注释剥掉再扫 —— 注释里会写「不要用 @color/bg 直引」这类说明文字，
    # 不剥的话会被当成真的引用，报一堆假错。
    strip_comment = re.compile(r"<!--((?:(?!-->).)*)-->", re.S)
    for p in layouts + walk_xml(res_dir, "values"):
        with open(p, encoding="utf-8", errors="replace") as f:
            text = strip_comment.sub("", f.read())
        for m in RE_REF.finditer(text):
            kind, name = m.group(1), m.group(2)
            if name not in have.get(kind, set()):
                errors.append("{} 引用了不存在的 @{}/{}".format(
                    os.path.relpath(p, res_dir), kind, name))

    # ---- 4. 定义了没人用（提示）
    for rid in sorted(layout_ids - set(java_ids)):
        warns.append("layout 里有 id `{}`，代码里没用到（纯装饰可以忽略）".format(rid))

    if not args.quiet:
        print("=== 布局 ⇄ 代码 一致性 ===")
        print("  layout 文件   {} 个".format(len(layouts)))
        print("  @+id 定义     {} 个".format(len(layout_ids)))
        print("  代码引用 id   {} 个".format(len(java_ids)))
        print()

    for w in warns:
        print("  ⚠ {}".format(w))
    for e in errors:
        print("  ✗ {}".format(e))

    if errors:
        print("\n{} 处不一致 —— 跑起来会 NPE 或资源找不到。".format(len(errors)))
        return 1
    print("\n✓ 布局与代码对得上（{} 个 id / {} 个 layout）".format(len(layout_ids), len(layouts)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
