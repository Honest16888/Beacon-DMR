#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 radioid.net 全量用户库中抽取中国（含中国香港等地区）DMR 台站数据，
直接注入 web/index.html 的 <script id="cnData"> 块，产出真正的单文件应用。

为什么要内嵌而不是外挂：
  - 外挂 dmr-cn.js 需要两个文件放一起，一旦只拷走 HTML 就会静默失去中国数据；
  - file:// 下 fetch 本地文件会被跨域拦截（不透明源），只有 <script> 能加载外挂数据，
    而内嵌则完全没有这个顾虑。

为什么不在运行时直接调接口：
  国内没有开放 CORS 的公开呼号接口（qrz.cn / qrzworld / hamcq 均为网页表单），
  radioid.net 数据最全但同样不返回 CORS 头。因此只能构建期抽取。

用法（在仓库根目录执行）：
    python tools/gen_cn_db.py [--csv _tmp/user.csv] [--html web/index.html]
若本地没有 CSV，脚本会自动下载（约 17MB）。
"""
import argparse
import csv
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import urllib.request

CSV_URL = "https://database.radioid.net/static/user.csv"
COUNTRY = "China"          # radioid.net 的 COUNTRY 字段值
SCRIPT_RE = re.compile(
    r'(<script id="cnData" type="text/plain"[^>]*>)(.*?)(</script>)', re.S)


def download(path):
    print(f"下载 {CSV_URL} …")
    req = urllib.request.Request(CSV_URL, headers={"User-Agent": "Mozilla/5.0"})
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with urllib.request.urlopen(req, timeout=300) as r, open(path, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    print(f"  已保存 {path}  ({os.path.getsize(path)/1048576:.1f} MB)")


def clean(s):
    """去掉分隔符与尖括号：分隔符会破坏打包格式，< 会破坏 <script> 原始文本块。"""
    s = (s or "").strip()
    for ch in ("|", ":", "\n", "\r", "<", ">"):
        s = s.replace(ch, " ")
    return " ".join(s.split())


def build_payload(csv_path):
    rows = []
    with open(csv_path, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            if (row.get("COUNTRY") or "").strip() != COUNTRY:
                continue
            rid = (row.get("RADIO_ID") or "").strip()
            call = (row.get("CALLSIGN") or "").strip().upper()
            if not rid.isdigit() or not call or call == "NOT_FOUND":
                continue
            name = clean(" ".join(x for x in [(row.get("FIRST_NAME") or ""),
                                              (row.get("LAST_NAME") or "")] if x))
            rows.append((int(rid), call, name, clean(row.get("CITY"))))

    # 同一呼号可能多人/换号登记：按呼号去重，保留 radio_id 最小的
    best = {}
    for rid, call, name, city in rows:
        cur = best.get(call)
        if cur is None or rid < cur[0]:
            best[call] = (rid, call, name, city)
    uniq = sorted(best.values())
    payload = "\n".join(f"{r}:{c}:{n}:{t}" for r, c, n, t in uniq)
    return uniq, payload, len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=os.path.join(ROOT, "_tmp", "user.csv"))
    ap.add_argument("--html", default=os.path.join(ROOT, "web", "index.html"))
    a = ap.parse_args()

    if not os.path.exists(a.csv):
        download(a.csv)

    uniq, payload, raw_n = build_payload(a.csv)
    print(f"中国台站 {len(uniq)} 条（原始 {raw_n} 条，按呼号去重）")

    with open(a.html, encoding="utf-8") as f:
        html = f.read()
    if not SCRIPT_RE.search(html):
        print(f"错误：在 {a.html} 中找不到 <script id=\"cnData\" type=\"text/plain\"> 注入点",
              file=sys.stderr)
        return 1

    before = os.path.getsize(a.html)
    today = __import__("datetime").date.today().isoformat()

    def repl(m):
        open_tag = m.group(1)
        # 把生成日期写进 data-date，界面据此提示数据时效
        if "data-date" in open_tag:
            open_tag = re.sub(r'data-date="[^"]*"', 'data-date="%s"' % today, open_tag)
        else:
            open_tag = open_tag[:-1] + ' data-date="%s">' % today
        return open_tag + "\n" + payload + "\n" + m.group(3)

    html2 = SCRIPT_RE.sub(repl, html, count=1)
    with open(a.html, "w", encoding="utf-8", newline="") as f:
        f.write(html2)
    after = os.path.getsize(a.html)

    print(f"已注入 {a.html}  (data-date={today})")
    print(f"  {before/1024:.0f} KB → {after/1024:.0f} KB（数据 {len(payload.encode())/1024:.0f} KB）")
    if uniq:
        print(f"  样例 {uniq[0]}")
        print(f"        {uniq[len(uniq)//2]}")
        print(f"        {uniq[-1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
