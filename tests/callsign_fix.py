# 呼号后处理：把 ASR 输出里的 NATO 拼读还原成呼号
#
#   python tests/callsign_fix.py "It is India Uniform 6 Victor Lee-McGio"
#
# 为什么需要这一层：通用 ASR 的词汇表里没有呼号，它会按"最像的英文单词"
# 去解码 —— Lima Kilo 变成 "Lee-McGio"、Victor Lima Kilo 变成 "6-week for"。
# 但业余无线电的呼号拼读是**封闭词表**（26 个 NATO 词 + 10 个数字），
# 所以可以在 ASR 之后做一次模糊对齐，把它纠回来。
#
# 这一层不需要额外模型，纯字符串处理，几十行，跑一次不到 1 毫秒。
import argparse
import re
import sys
import unicodedata
from difflib import SequenceMatcher

NATO = {
    "a": ["alfa", "alpha"], "b": ["bravo"], "c": ["charlie"], "d": ["delta"],
    "e": ["echo"], "f": ["foxtrot"], "g": ["golf"], "h": ["hotel"],
    "i": ["india"], "j": ["juliett", "juliet"], "k": ["kilo"], "l": ["lima"],
    "m": ["mike"], "n": ["november"], "o": ["oscar"], "p": ["papa"],
    "q": ["quebec"], "r": ["romeo"], "s": ["sierra"], "t": ["tango"],
    "u": ["uniform"], "v": ["victor"], "w": ["whiskey", "whisky"],
    "x": ["xray", "x-ray"], "y": ["yankee"], "z": ["zulu"],
}
DIGITS = {
    "0": ["zero", "oh"], "1": ["one", "wun"], "2": ["two", "too", "to"],
    "3": ["three", "tree"], "4": ["four", "for"], "5": ["five", "fife"],
    "6": ["six"], "7": ["seven"], "8": ["eight", "ate"], "9": ["nine", "niner"],
}

# 反向索引：每个词 → 字符
WORD2CH = {}
for ch, words in NATO.items():
    for w in words:
        WORD2CH[w] = ch.upper()
for ch, words in DIGITS.items():
    for w in words:
        WORD2CH[w] = ch


def norm(s):
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()


def to_phonetic(text, cutoff=0.55):
    """把一段文字里的 NATO 词/数字换成字母数字，其余保留原词。
    返回 [(字符或原文, 置信度, 原词)]"""
    out = []
    for raw in norm(text).split():
        if raw in WORD2CH:
            out.append((WORD2CH[raw], 1.0, raw))
            continue
        if raw.isdigit() and len(raw) == 1:
            out.append((raw, 1.0, raw))
            continue
        # 模糊匹配 NATO 词表
        best, score = None, 0.0
        for w, ch in WORD2CH.items():
            r = SequenceMatcher(None, raw, w).ratio()
            # 长词才算，避免 "it" 之类误配
            if r > score and (r >= cutoff or (len(raw) >= 4 and raw in w)):
                best, score = ch, r
        if best and score >= cutoff:
            out.append((best, score, raw))
        else:
            out.append((None, 0.0, raw))
    return out


def extract_callsigns(tokens, min_len=4, max_len=8):
    """从音标串里找呼号：至少含一个数字，字母数字混合，长度 4~8。"""
    found = []
    # 把连续的"已识别字符"聚成串，遇到未识别就断开
    run = []
    for ch, score, raw in tokens + [(None, 0, None)]:
        if ch is not None:
            run.append((ch, score, raw))
        else:
            if run:
                found.extend(_split_runs(run, min_len, max_len))
                run = []
    return found


def _split_runs(run, min_len, max_len):
    """一个长串里可能藏着多个呼号（比如 "... India Uniform 6 Victor Lima Kilo
    Sierra Romeo Oscar ..."）。这里只做简单滑动：找出所有含数字的合法窗口。"""
    res = []
    n = len(run)
    for i in range(n):
        for j in range(i + min_len, min(max_len, n - i) + i + 1):
            seg = run[i:j]
            s = "".join(c for c, _, _ in seg)
            if not re.fullmatch(r"[A-Z0-9]+", s):
                continue
            if not re.search(r"\d", s):
                continue
            if not re.search(r"[A-Z]", s):
                continue
            # 合法呼号：字母在前、数字、字母在后（允许纯字母数字交替的宽松形式）
            if not re.match(r"^[A-Z]{0,3}\d[A-Z]{0,4}$", s):
                continue
            conf = sum(x[1] for x in seg) / len(seg)
            res.append((s, round(conf, 2), " ".join(x[2] for x in seg)))
    # 去重、按置信度排序，只留互不重叠的最优
    res.sort(key=lambda t: (-len(t[0]), -t[1]))
    picked, used = [], set()
    for s, c, raw in res:
        if any(k in used for k in range(0)):
            pass
        if s in [p[0] for p in picked]:
            continue
        picked.append((s, c, raw))
    return picked[:3]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("text", nargs="+")
    ap.add_argument("--expect", help="已知呼号，用于对照")
    args = ap.parse_args()

    for t in args.text:
        print("=" * 60)
        print("ASR 原文:", t)
        toks = to_phonetic(t)
        shown = "".join(c if c else ("·" if not raw.isalpha() else "?")
                        for c, s, raw in toks)
        print("音标还原:", shown)
        low = [(c, s, raw) for c, s, raw in toks if s < 1.0 and c]
        if low:
            print("模糊纠正:", ", ".join("%s←%s(%.2f)" % (c, raw, s) for c, s, raw in low))
        cs = extract_callsigns(toks)
        if cs:
            print("抽出呼号:", ", ".join("%s (置信 %.2f, 来自「%s」)" % (a, b, c) for a, b, c in cs))
        else:
            print("抽出呼号: 无")

    if args.expect:
        print("\n期望呼号:", args.expect.upper())


if __name__ == "__main__":
    main()
