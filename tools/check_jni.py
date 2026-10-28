#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
JNI 符号一致性检查：Java 的 native 声明  ⇄  .so 的 .dynsym 导出。

为什么需要这个
--------------
JNI 的函数符号名由**全限定类名**拼出来：

    Java_net_beacon_dmr_vocoder_Vocoder_nativeInit
         └─ package ─────┘ └─ class ─┘ └ method ┘
    （包名里的 . 变成 _，类名里的 $ 也变成 _）

所以「只改一行 package」就能让所有 native 调用静默全断。而且**只在第一次真正
调用时才暴露**，抛的是英文 UnsatisfiedLinkError：

    No implementation found for boolean net.beacon.dmr.mmdvm.vocoder.Vocoder.nativeInit
    (tried Java_..._nativeInit and Java_..._nativeInit__Ljava_lang_String_2
     - is the library loaded?)

这句话极具误导性 —— 它读起来像「.so 没打进包」，实际 .so 好好地在 lib/ 里，
只是里面导出的名字还带着旧包名。查这个坑要翻 dex、解 .so、看 .dynsym，
所以不如让脚本在出包前查一遍。

检查三件事
----------
1. Java 侧每个 native 方法，在某个 .so 的 .dynsym 里都能找到对应符号
   （缺失 = 运行时必崩）。
2. 每个 ABI 都要齐（arm64 有、armeabi-v7a 没有的话，老机型才崩）。
3. System.loadLibrary("x") 里的 x，在 jniLibs 里真的有 libx.so。

用法
----
    python tools/check_jni.py                # 默认查 native-java
    python tools/check_jni.py --root android # 查 WebView 版
"""

import argparse
import os
import re
import struct
import sys

# ---------------------------------------------------------------- ELF 解析

# ELF 里 sh_type == SHT_SYMTAB(2) / SHT_DYNSYM(11)；我们要的是 DYNSYM ——
# 那才是动态链接器能看见的部分。只导到 SYMTAB、没进 DYNSYM 的符号，
# dlsym 和 JVM 都找不到（需要加 __attribute__((visibility("default")))）。
SHT_DYNSYM = 11


def elf_dynsym_names(path):
    """取 .so 的 .dynsym 里的所有符号名。不是 ELF 就返回 None。"""
    with open(path, "rb") as f:
        d = f.read()
    if len(d) < 64 or d[:4] != b"\x7fELF":
        return None

    is64 = d[4] == 2
    end = "<" if d[5] == 1 else ">"          # 小端 / 大端

    if is64:
        e_shoff, = struct.unpack_from(end + "Q", d, 0x28)
        e_shentsize, = struct.unpack_from(end + "H", d, 0x3A)
        e_shnum, = struct.unpack_from(end + "H", d, 0x3C)
    else:
        e_shoff, = struct.unpack_from(end + "I", d, 0x20)
        e_shentsize, = struct.unpack_from(end + "H", d, 0x2E)
        e_shnum, = struct.unpack_from(end + "H", d, 0x30)

    def section(i):
        o = e_shoff + i * e_shentsize
        if is64:
            name, typ, _flags, _addr, off, size, link, _info, _align, entsz = \
                struct.unpack_from(end + "IIQQQQIIQQ", d, o)
        else:
            name, typ, _flags, _addr, off, size, link, _info, _align, entsz = \
                struct.unpack_from(end + "IIIIIIIIII", d, o)
        return dict(typ=typ, off=off, size=size, link=link, entsz=entsz)

    names = set()
    for i in range(e_shnum):
        s = section(i)
        if s["typ"] != SHT_DYNSYM:
            continue
        strtab = section(s["link"])
        entsz = s["entsz"] or (24 if is64 else 16)
        for j in range(s["size"] // entsz):
            o = s["off"] + j * entsz
            (st_name,) = struct.unpack_from(end + "I", d, o)
            if st_name == 0:
                continue
            base = strtab["off"] + st_name
            names.add(d[base:d.index(b"\0", base)].decode("latin-1"))
    return names


# ------------------------------------------------------- Java 侧声明扫描

# package com.foo.bar;
RE_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)

# class Foo / final class Foo / public final class Foo extends X
RE_CLASS = re.compile(
    r"^\s*(?:public\s+|final\s+|abstract\s+|static\s+|strictfp\s+)*"
    r"class\s+(\w+)", re.M)

# public static native int foo(int a, byte[] b);
RE_NATIVE = re.compile(
    r"^[^/\n]*\bnative\s+[\w.<>\[\]]+\s+(\w+)\s*\(", re.M)

# System.loadLibrary("beaconvoc");
RE_LOADLIB = re.compile(r'System\.loadLibrary\(\s*"([^"]+)"\s*\)')

# Java 标识符里的 5 个字符要转义成 _0.._5，顺序不能反：
# 先处理 _ 自己，再处理其余（. 由 package 处理，不进这里）
_JNI_ESC = [("_", "_1"), (";", "_2"), ("[", "_3"), ("/", "_4")]


def mangle(ident):
    """按 JNI 规范转义一个标识符（类名或方法名）。"""
    out = ident
    for src, dst in _JNI_ESC:
        out = out.replace(src, dst)
    return out


def scan_java(root):
    """
    返回 (decls, libs)：
      decls = [(符号名, 源文件相对路径, 行号, 全限定方法名)]
      libs  = {库名: [(相对路径, 行号)]}
    """
    decls, libs = [], {}

    for dirpath, _dirnames, filenames in os.walk(root):
        # build/ 下是 AGP 生成的 R.java / BuildConfig.java，没有 native 方法
        if os.sep + "build" + os.sep in dirpath + os.sep:
            continue
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                text = open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue

            pkg_m = RE_PACKAGE.search(text)
            pkg = pkg_m.group(1).replace(".", "_") if pkg_m else ""

            rel = os.path.relpath(path, os.path.dirname(root))

            for m in RE_LOADLIB.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                libs.setdefault(m.group(1), []).append((rel, line))

            for m in RE_NATIVE.finditer(text):
                # native 声明可能出现在多个类里？一个文件一个顶层类（本项目约定），
                # 取「出现在该 native 声明之前、离它最近的那个 class」，比盲取第一个稳。
                cls = None
                for cm in RE_CLASS.finditer(text, 0, m.start()):
                    cls = cm.group(1)
                if not cls:
                    continue
                line = text.count("\n", 0, m.start()) + 1

                # Java_ + package + _ + class + _ + method
                parts = []
                if pkg:
                    parts.append(pkg)
                parts.append(mangle(cls))
                parts.append(mangle(m.group(1)))
                sym = "Java_" + "_".join(parts)

                fq = "{}.{}.{}".format(pkg.replace("_", "."), cls, m.group(1))
                decls.append((sym, rel, line, fq))

    return decls, libs


# ------------------------------------------------------------------- 主流程

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default_root = os.path.join(os.path.dirname(here),
                                "native-java", "app", "src", "main")

    ap = argparse.ArgumentParser(description="检查 JNI 符号一致性")
    ap.add_argument("--root", default=default_root,
                    help="包含 java/ 和 jniLibs/ 的 main 目录")
    ap.add_argument("--quiet", action="store_true", help="只报错误")
    args = ap.parse_args()

    java_root = os.path.join(args.root, "java")
    if not os.path.isdir(java_root):
        # 允许直接传 .../src/main/java
        if os.path.basename(args.root) == "java":
            java_root = args.root
        else:
            print("✗ 找不到 java 目录：{}".format(java_root))
            return 2

    main_dir = os.path.dirname(java_root)
    jni_root = os.path.join(main_dir, "jniLibs")

    decls, libs = scan_java(java_root)

    # 收集每个 ABI 的 .so 导出符号
    abi_syms = {}      # abi -> 文件名 -> set(符号)
    so_index = {}      # 库名(不含 lib/.so) -> [(abi, 路径)]
    if os.path.isdir(jni_root):
        for abi in sorted(os.listdir(jni_root)):
            abi_dir = os.path.join(jni_root, abi)
            if not os.path.isdir(abi_dir):
                continue
            abi_syms[abi] = {}
            for fn in sorted(os.listdir(abi_dir)):
                if not fn.endswith(".so"):
                    continue
                path = os.path.join(abi_dir, fn)
                names = elf_dynsym_names(path)
                if names is None:
                    print("✗ {} 不是 ELF，跳过".format(path))
                    continue
                abi_syms[abi][fn] = names
                short = fn[3:-3] if fn.startswith("lib") else fn[:-3]
                so_index.setdefault(short, []).append((abi, path))

    errors, warns = [], []

    if not decls:
        print("（没有找到 native 方法声明）")

    # ---- 1/2. 每个声明在每个 ABI 都要有符号
    for sym, rel, line, fq in decls:
        if not abi_syms:
            errors.append("{}:{} 有 native 声明但 jniLibs 里没有 .so".format(rel, line))
            continue
        for abi, sos in abi_syms.items():
            hit = [fn for fn, names in sos.items() if sym in names]
            if not hit:
                errors.append(
                    "{}:{}  {}  →  在 {} 的任何 .so 里都没有 {}".format(
                        rel, line, fq, abi, sym))

    # ---- 3. loadLibrary 的库确实存在
    for lib, where in sorted(libs.items()):
        if lib not in so_index:
            for rel, line in where:
                errors.append("{}:{}  loadLibrary(\"{}\") 但 jniLibs 里没有 "
                              "lib{}.so".format(rel, line, lib, lib))

    # ---- 反向：.so 里导出但 Java 侧已没有（提示，不算错）
    declared = {sym for sym, _r, _l, _f in decls}
    orphan = set()
    for abi, sos in abi_syms.items():
        for fn, names in sos.items():
            for n in names:
                if n.startswith("Java_") and n not in declared:
                    orphan.add((n, fn))

    # ---- 输出
    if not args.quiet:
        print("=== JNI 符号一致性 ===")
        print("  Java native 声明  {} 条".format(len(decls)))
        for sym, rel, line, fq in sorted(decls, key=lambda x: x[3]):
            print("    {:<58} {}".format(sym, fq))
        print("  ABI               {}".format(
            " · ".join("{} ({} 个 .so)".format(a, len(s))
                       for a, s in sorted(abi_syms.items())) or "（无）"))
        print("  loadLibrary       {}".format(
            " · ".join(sorted(libs)) or "（无）"))
        print()

        for abi, sos in sorted(abi_syms.items()):
            got = sum(1 for n in set().union(*sos.values()) if n.startswith("Java_"))
            print("  {} 导出 Java_* 符号 {} 个".format(abi, got))
        print()

    for o in sorted(orphan):
        warns.append(".so 里还有 {} （{}），Java 侧已无对应声明 —— "
                     "多半是 native 代码留着旧类，无害".format(o[0], o[1]))

    for w in warns:
        print("  ⚠ {}".format(w))
    for e in errors:
        print("  ✗ {}".format(e))

    if errors:
        print("\n{} 处 JNI 符号不匹配 —— 出包后必崩（UnsatisfiedLinkError）。".format(
            len(errors)))
        print("修法：把类移回 .so 编译时用的那个包，或者在 C++ 侧加 JNI_OnLoad "
              "+ RegisterNatives 重新绑定。")
        return 1
    print("\n✓ JNI 符号全部对得上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
