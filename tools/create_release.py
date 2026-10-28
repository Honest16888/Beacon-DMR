#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""创建 GitHub Release 并上传 APK。

本机没有 gh CLI，凭据走 git credential helper（Windows 上由 Git Credential
Manager 缓存，主机名 github.com）。脚本只读凭据，不落盘、不回显。

用法：
    python tools/create_release.py 2.4.10
    python tools/create_release.py 2.4.10 --notes dist/release_notes_2410.md
    python tools/create_release.py 2.4.10 --dry-run

约定：
    仓库        Honest16888/Beacon-DMR
    tag         v<version>
    APK 路径    dist/beacon-dmr-<version>-release.apk
    说明文件    dist/release_notes_<version去点>.md（可用 --notes 覆盖）
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

OWNER = 'Honest16888'
REPO = 'Beacon-DMR'
API = 'https://api.github.com'
UA = 'beacon-dmr-release-script'


def repo_root():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def git_token():
    """从 git credential helper 取 token（不回显）。"""
    payload = 'protocol=https\nhost=github.com\n\n'
    try:
        out = subprocess.run(
            ['git', 'credential', 'fill'],
            input=payload.encode('utf-8'),
            capture_output=True,
            check=True,
        ).stdout.decode('utf-8', 'replace')
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        sys.exit('无法从 git credential 取凭据：%s' % e)
    for line in out.splitlines():
        if line.startswith('password='):
            tok = line[len('password='):].strip()
            if tok:
                return tok
    sys.exit('git credential 没有返回 password，请先 git push 一次让凭据缓存生效')


def api(method, path, token, body=None, raw=None, content_type='application/json'):
    url = path if path.startswith('http') else API + path
    data = None
    headers = {
        'Authorization': 'Bearer ' + token,
        'Accept': 'application/vnd.github+json',
        'User-Agent': UA,
        'X-GitHub-Api-Version': '2022-11-28',
    }
    if raw is not None:
        data = raw
        headers['Content-Type'] = content_type
        headers['Content-Length'] = str(len(raw))
    elif body is not None:
        data = json.dumps(body).encode('utf-8')
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, json.loads(r.read().decode('utf-8') or '{}')
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {'message': text}


def guess_notes(version):
    root = repo_root()
    cand = os.path.join(root, 'dist', 'release_notes_%s.md' % version.replace('.', ''))
    return cand if os.path.isfile(cand) else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('version', help='版本号，不带 v 前缀，例如 2.4.10')
    ap.add_argument('--notes', help='发布说明 markdown 路径')
    ap.add_argument('--name', help='Release 标题，默认取说明文件首个标题')
    ap.add_argument('--apk', help='APK 路径，默认 dist/beacon-dmr-<ver>-release.apk')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    ver = args.version.lstrip('v')
    tag = 'v' + ver
    root = repo_root()

    apk = args.apk or os.path.join(root, 'dist', 'beacon-dmr-%s-release.apk' % ver)
    if not os.path.isfile(apk):
        sys.exit('找不到 APK：%s' % apk)

    notes_path = args.notes or guess_notes(ver)
    if not notes_path or not os.path.isfile(notes_path):
        sys.exit('找不到发布说明，请用 --notes 指定')
    with open(notes_path, 'r', encoding='utf-8') as f:
        notes = f.read()

    name = args.name
    if not name:
        m = re.search(r'^#\s*(.+)$', notes, re.M)
        name = m.group(1).strip() if m else tag

    size = os.path.getsize(apk)
    print('版本    : %s' % ver)
    print('tag     : %s' % tag)
    print('标题    : %s' % name)
    print('APK     : %s (%d 字节)' % (apk, size))
    print('说明    : %s (%d 字符)' % (notes_path, len(notes)))

    if args.dry_run:
        print('\n--dry-run：不实际调用 API')
        return 0

    token = git_token()

    # 已存在就直接复用，避免重复建 Release 报 422
    st, rel = api('GET', '/repos/%s/%s/releases/tags/%s' % (OWNER, REPO, tag), token)
    if st == 200:
        print('\nRelease 已存在，复用 id=%s' % rel['id'])
    else:
        st, rel = api('POST', '/repos/%s/%s/releases' % (OWNER, REPO), token, body={
            'tag_name': tag,
            'name': name,
            'body': notes,
            'draft': False,
            'prerelease': False,
        })
        if st not in (200, 201):
            sys.exit('创建 Release 失败 HTTP %s：%s' % (st, rel.get('message')))
        print('\nRelease 已创建 id=%s' % rel['id'])

    # 同名资产先删掉，否则上传会 422 already_exists
    for a in rel.get('assets') or []:
        if a.get('name') == os.path.basename(apk):
            api('DELETE', '/repos/%s/%s/releases/assets/%s' % (OWNER, REPO, a['id']), token)
            print('已删除同名旧资产 %s' % a['name'])

    with open(apk, 'rb') as f:
        blob = f.read()

    up = ('https://uploads.github.com/repos/%s/%s/releases/%s/assets?name=%s'
          % (OWNER, REPO, rel['id'], os.path.basename(apk)))
    st, asset = api('POST', up, token, raw=blob, content_type='application/vnd.android.package-archive')
    if st not in (200, 201):
        sys.exit('上传 APK 失败 HTTP %s：%s' % (st, asset.get('message')))

    print('APK 已上传 %s（%s 字节）' % (asset.get('name'), asset.get('size')))
    print('下载地址 %s' % asset.get('browser_download_url'))
    print('\nRelease 页面 %s' % rel.get('html_url'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
