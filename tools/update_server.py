#!/usr/bin/env python3
"""Beacon-DMR 远程更新推送服务端。

部署方式：
    python update_server.py --port 8080 --apk-dir ./apks

目录结构：
    apks/
    ├── beacon-dmr-2.10.0-release.apk
    ├── beacon-dmr-2.10.1-release.apk
    └── manifest.json          ← 版本清单（自动生成或手动维护）

manifest.json 格式：
{
  "latest": "2.10.1",
  "versions": {
    "2.10.1": {
      "versionCode": 53,
      "apk": "beacon-dmr-2.10.1-release.apk",
      "size": 2308065,
      "sha256": "abc123...",
      "changelog": "修：长按排序被复制菜单抢走",
      "minSdk": 26,
      "force": false,
      "published": "2026-11-09T22:30:00Z"
    },
    "2.10.0": { ... }
  }
}

客户端调用：
    GET /api/version              → 返回最新版本信息
    GET /api/version/{ver}        → 返回指定版本信息
    GET /api/download/{ver}       → 下载 APK（支持断点续传）
    GET /api/manifest             → 返回完整版本清单

部署到 Nginx 后，客户端只需配一个 URL。
"""
import argparse
import hashlib
import json
import os
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def build_manifest(apk_dir: str) -> dict:
    """扫描 apk 目录，自动生成 manifest.json。"""
    manifest_path = os.path.join(apk_dir, 'manifest.json')
    versions = {}

    # 如果已有 manifest，先读进来（保留手写的 changelog 等字段）
    if os.path.exists(manifest_path):
        try:
            with open(manifest_path, 'r', encoding='utf-8') as f:
                old = json.load(f)
                versions = old.get('versions', {})
        except Exception:
            pass

    # 扫描 APK 文件
    for fname in sorted(os.listdir(apk_dir)):
        if not fname.endswith('.apk'):
            continue
        # 从文件名提取版本号：beacon-dmr-2.10.1-release.apk → 2.10.1
        parts = fname.replace('-release.apk', '').replace('.apk', '').split('-')
        if len(parts) < 3:
            continue
        ver = parts[2]  # beacon-dmr-VERSION-release
        if ver in versions:
            continue

        fpath = os.path.join(apk_dir, fname)
        size = os.path.getsize(fpath)
        digest = sha256_file(fpath)

        # 从文件名猜 versionCode（可手动改 manifest.json 覆盖）
        # 2.10.1 → 2*10000 + 10*100 + 1 = 21001
        try:
            parts_ver = ver.split('.')
            vc = int(parts_ver[0]) * 10000 + int(parts_ver[1]) * 100 + int(parts_ver[2])
        except Exception:
            vc = 0

        versions[ver] = {
            'versionCode': vc,
            'apk': fname,
            'size': size,
            'sha256': digest,
            'changelog': f'版本 {ver}',
            'minSdk': 26,
            'force': False,
            'published': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(os.path.getmtime(fpath))),
        }

    # 找最新版本（按 versionCode 排序）
    if versions:
        latest = max(versions.keys(), key=lambda v: versions[v].get('versionCode', 0))
    else:
        latest = ''

    manifest = {'latest': latest, 'versions': versions}

    # 写回 manifest.json
    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    return manifest


class UpdateHandler(BaseHTTPRequestHandler):
    apk_dir = './apks'

    def log_message(self, fmt, *args):
        print(f'[{time.strftime("%H:%M:%S")}] {fmt % args}')

    def send_json(self, data: dict, status: int = 200):
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path.rstrip('/')
        params = parse_qs(url.query)

        try:
            if path == '/api/version':
                self.handle_version(params)
            elif path.startswith('/api/version/'):
                ver = path.split('/api/version/')[1]
                self.handle_version_detail(ver)
            elif path.startswith('/api/download/'):
                ver = path.split('/api/download/')[1]
                self.handle_download(ver)
            elif path == '/api/manifest':
                self.handle_manifest()
            elif path == '/':
                self.handle_index()
            else:
                self.send_json({'error': 'not found'}, 404)
        except Exception as e:
            self.send_json({'error': str(e)}, 500)

    def handle_version(self, params):
        """GET /api/version?current=2.10.0 → 返回是否有更新"""
        manifest = build_manifest(self.apk_dir)
        latest = manifest['latest']
        current = params.get('current', [''])[0]

        if not latest:
            self.send_json({'error': 'no versions available'}, 404)
            return

        ver_info = manifest['versions'][latest]
        has_update = False
        if current:
            # 比较版本号
            try:
                cur_parts = [int(x) for x in current.split('.')]
                lat_parts = [int(x) for x in latest.split('.')]
                has_update = lat_parts > cur_parts
            except Exception:
                has_update = True

        self.send_json({
            'ok': True,
            'latest': latest,
            'has_update': has_update,
            'current': current,
            'info': {
                'version': latest,
                'versionCode': ver_info.get('versionCode', 0),
                'size': ver_info.get('size', 0),
                'sha256': ver_info.get('sha256', ''),
                'changelog': ver_info.get('changelog', ''),
                'force': ver_info.get('force', False),
                'minSdk': ver_info.get('minSdk', 26),
                'published': ver_info.get('published', ''),
                'download': f'/api/download/{latest}',
            },
        })

    def handle_version_detail(self, ver):
        """GET /api/version/{ver} → 返回指定版本信息"""
        manifest = build_manifest(self.apk_dir)
        if ver not in manifest['versions']:
            self.send_json({'error': f'version {ver} not found'}, 404)
            return
        self.send_json({'ok': True, 'info': manifest['versions'][ver]})

    def handle_manifest(self):
        """GET /api/manifest → 完整版本清单"""
        manifest = build_manifest(self.apk_dir)
        self.send_json(manifest)

    def handle_download(self, ver):
        """GET /api/download/{ver} → 下载 APK（支持断点续传）"""
        manifest = build_manifest(self.apk_dir)
        if ver not in manifest['versions']:
            self.send_json({'error': f'version {ver} not found'}, 404)
            return

        fname = manifest['versions'][ver]['apk']
        fpath = os.path.join(self.apk_dir, fname)
        if not os.path.exists(fpath):
            self.send_json({'error': 'APK file not found'}, 404)
            return

        size = os.path.getsize(fpath)

        # 支持 Range 断点续传
        range_header = self.headers.get('Range')
        start = 0
        if range_header:
            try:
                range_val = range_header.replace('bytes=', '').split('-')[0]
                start = int(range_val)
            except Exception:
                pass

        self.send_response(206 if start > 0 else 200)
        self.send_header('Content-Type', 'application/vnd.android.package-archive')
        self.send_header('Content-Length', str(size - start))
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Disposition', f'attachment; filename="{fname}"')
        self.send_header('Access-Control-Allow-Origin', '*')
        if start > 0:
            self.send_header('Content-Range', f'bytes {start}-{size-1}/{size}')
        self.end_headers()

        with open(fpath, 'rb') as f:
            f.seek(start)
            while True:
                chunk = f.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def handle_index(self):
        """GET / → 简单的管理页面"""
        manifest = build_manifest(self.apk_dir)
        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Beacon-DMR Update Server</title>
<style>body{{font-family:system-ui;padding:20px;background:#1a1a2e;color:#eee}}
table{{border-collapse:collapse;width:100%}}
td,th{{padding:8px 12px;border:1px solid #333;text-align:left}}
th{{background:#16213e}} a{{color:#4da3ff}}</style></head>
<body>
<h1>Beacon-DMR Update Server</h1>
<p>最新版本: <b>{manifest['latest']}</b> | 共 {len(manifest['versions'])} 个版本</p>
<table><tr><th>版本</th><th>版本号</th><th>大小</th><th>SHA256</th><th>更新说明</th><th>操作</th></tr>
"""
        for ver in sorted(manifest['versions'].keys(),
                          key=lambda v: manifest['versions'][v].get('versionCode', 0), reverse=True):
            v = manifest['versions'][ver]
            size_kb = v.get('size', 0) / 1024
            html += f"""<tr><td><b>{ver}</b></td><td>{v.get('versionCode','')}</td>
<td>{size_kb:.0f} KB</td><td><code>{v.get('sha256','')[:16]}…</code></td>
<td>{v.get('changelog','')}</td>
<td><a href="/api/download/{ver}">下载</a></td></tr>"""
        html += """</table>
<h2>客户端配置</h2>
<pre>GET /api/version?current=当前版本   → 检查更新
GET /api/download/{version}        → 下载 APK</pre>
</body></html>"""
        body = html.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    ap = argparse.ArgumentParser(description='Beacon-DMR 更新推送服务')
    ap.add_argument('--port', type=int, default=8080)
    ap.add_argument('--host', default='0.0.0.0')
    ap.add_argument('--apk-dir', default='./apks', help='APK 文件目录')
    args = ap.parse_args()

    os.makedirs(args.apk_dir, exist_ok=True)
    UpdateHandler.apk_dir = args.apk_dir

    # 启动时先建一次 manifest
    manifest = build_manifest(args.apk_dir)
    print(f'APK 目录: {os.path.abspath(args.apk_dir)}')
    print(f'最新版本: {manifest["latest"]}（共 {len(manifest["versions"])} 个）')
    print(f'监听: http://{args.host}:{args.port}')
    print(f'管理页: http://localhost:{args.port}/')
    print()

    server = HTTPServer((args.host, args.port), UpdateHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n已停止')
        server.server_close()


if __name__ == '__main__':
    main()
