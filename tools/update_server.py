#!/usr/bin/env python3
"""Beacon-DMR 远程更新推送服务端（带图形管理后台）。

部署：
    python3 update_server.py --port 8080 --apk-dir ./apks

功能：
    - 后台管理：上传 APK、编辑更新说明、强制更新、删除版本
    - 客户端 API：检查更新、下载 APK（断点续传）
    - 下载统计：每个版本的下载次数
"""

import argparse
import hashlib
import json
import os
import time
import cgi
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

APP_DIR = os.path.dirname(os.path.abspath(__file__))

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

def load_manifest(apk_dir):
    path = os.path.join(apk_dir, 'manifest.json')
    if os.path.exists(path):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            pass
    return {'latest': '', 'versions': {}, 'downloads': {}}

def save_manifest(apk_dir, manifest):
    path = os.path.join(apk_dir, 'manifest.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

def scan_apks(apk_dir, manifest):
    """扫描 APK 目录，把新文件加进 manifest。"""
    versions = manifest.setdefault('versions', {})
    for fname in sorted(os.listdir(apk_dir)):
        if not fname.endswith('.apk'):
            continue
        # 已收录的跳过
        if any(v.get('apk') == fname for v in versions.values()):
            continue
        # 从文件名提取版本号
        base = fname.replace('-release.apk', '').replace('.apk', '')
        parts = base.split('-')
        if len(parts) < 3:
            continue
        ver = parts[2]
        if ver in versions:
            continue

        fpath = os.path.join(apk_dir, fname)
        try:
            parts_ver = ver.split('.')
            vc = int(parts_ver[0]) * 10000 + int(parts_ver[1]) * 100 + int(parts_ver[2])
        except Exception:
            vc = 0

        versions[ver] = {
            'versionCode': vc,
            'apk': fname,
            'size': os.path.getsize(fpath),
            'sha256': sha256_file(fpath),
            'changelog': '',
            'minSdk': 26,
            'force': False,
            'published': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(os.path.getmtime(fpath))),
        }

    # 更新 latest
    if versions:
        manifest['latest'] = max(versions.keys(), key=lambda v: versions[v].get('versionCode', 0))
    save_manifest(apk_dir, manifest)
    return manifest


# ===================== HTML 管理后台 =====================

PAGE_HEADER = """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Beacon-DMR 更新管理</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:#0f172a;color:#e2e8f0;min-height:100vh}
.top{background:#1e293b;padding:16px 24px;display:flex;align-items:center;gap:16px;border-bottom:1px solid #334155}
.top h1{font-size:20px;font-weight:700}
.top .badge{background:#3b82f6;color:#fff;padding:2px 10px;border-radius:12px;font-size:12px}
.main{max-width:1100px;margin:0 auto;padding:24px}
.card{background:#1e293b;border-radius:12px;padding:20px;margin-bottom:20px;border:1px solid #334155}
.card h2{font-size:16px;margin-bottom:16px;color:#94a3b8;text-transform:uppercase;letter-spacing:1px}
table{width:100%;border-collapse:collapse}
th{padding:10px 12px;text-align:left;background:#0f172a;color:#64748b;font-size:12px;text-transform:uppercase;border-bottom:1px solid #334155}
td{padding:12px;border-bottom:1px solid #1e293b;font-size:14px}
tr:hover td{background:#263348}
.btn{padding:6px 16px;border-radius:8px;border:none;cursor:pointer;font-size:13px;font-weight:600;transition:all .15s}
.btn-sm{padding:4px 10px;font-size:12px}
.btn-blue{background:#3b82f6;color:#fff}.btn-blue:hover{background:#2563eb}
.btn-green{background:#22c55e;color:#fff}.btn-green:hover{background:#16a34a}
.btn-red{background:#ef4444;color:#fff}.btn-red:hover{background:#dc2626}
.btn-gray{background:#475569;color:#fff}.btn-gray:hover{background:#334155}
.tag{display:inline-block;padding:2px 8px;border-radius:6px;font-size:11px;font-weight:700}
.tag-latest{background:#3b82f6;color:#fff}
.tag-force{background:#ef4444;color:#fff}
.tag-ok{background:#22c55e;color:#fff}
.input{width:100%;padding:8px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:14px}
.input:focus{outline:none;border-color:#3b82f6}
textarea.input{min-height:60px;resize:vertical}
.form-row{margin-bottom:12px}
.form-row label{display:block;font-size:13px;color:#94a3b8;margin-bottom:4px}
.upload-zone{border:2px dashed #334155;border-radius:12px;padding:40px;text-align:center;cursor:pointer;transition:all .2s}
.upload-zone:hover{border-color:#3b82f6;background:#1e293b}
.upload-zone.dragover{border-color:#22c55e;background:#1e293b}
.upload-zone p{color:#64748b;font-size:14px}
.upload-zone .big{font-size:24px;margin-bottom:8px}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin-bottom:20px}
.stat-card{background:#1e293b;border-radius:12px;padding:20px;border:1px solid #334155}
.stat-card .num{font-size:32px;font-weight:700;color:#3b82f6}
.stat-card .label{font-size:13px;color:#64748b;margin-top:4px}
.toast{position:fixed;top:20px;right:20px;padding:12px 20px;border-radius:8px;color:#fff;font-size:14px;z-index:999;animation:slideIn .3s}
@keyframes slideIn{from{transform:translateX(100%);opacity:0}to{transform:translateX(0);opacity:1}}
.toast-ok{background:#22c55e}.toast-err{background:#ef4444}
.modal-bg{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.6);z-index:100;display:flex;align-items:center;justify-content:center}
.modal{background:#1e293b;border-radius:16px;padding:24px;width:90%;max-width:500px;border:1px solid #334155}
.modal h3{margin-bottom:16px}
</style></head><body>"""

PAGE_HEADER_STATS = """
<div class="top">
  <h1>📡 Beacon-DMR 更新管理</h1>
  <span class="badge">{version_count} 个版本</span>
  <span class="badge" style="background:#22c55e">最新 {latest}</span>
</div>
<div class="main">
"""

PAGE_FOOTER = """</div>
<script>
function toast(msg, ok=true) {
  const t = document.createElement('div');
  t.className = 'toast ' + (ok ? 'toast-ok' : 'toast-err');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}

async function api(method, url, body=null) {
  const opts = {method, headers: {'Content-Type': 'application/json'}};
  if (body) opts.body = JSON.stringify(body);
  const r = await fetch(url, opts);
  return r.json();
}

// 上传 APK
function initUpload() {
  const zone = document.getElementById('uploadZone');
  const input = document.getElementById('fileInput');
  if (!zone) return;

  zone.addEventListener('click', () => input.click());
  zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('dragover'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
  zone.addEventListener('drop', e => {
    e.preventDefault();
    zone.classList.remove('dragover');
    if (e.dataTransfer.files.length) uploadFile(e.dataTransfer.files[0]);
  });
  input.addEventListener('change', () => {
    if (input.files.length) uploadFile(input.files[0]);
  });
}

async function uploadFile(file) {
  const zone = document.getElementById('uploadZone');
  zone.innerHTML = '<p>⏳ 正在上传 ' + file.name + '（' + (file.size/1024/1024).toFixed(1) + ' MB）...</p>';

  const form = new FormData();
  form.append('apk', file);

  try {
    const r = await fetch('/api/upload', {method: 'POST', body: form});
    const data = await r.json();
    if (data.ok) {
      toast('✅ 上传成功：' + data.version);
      setTimeout(() => location.reload(), 800);
    } else {
      toast('❌ 上传失败：' + data.error, false);
      zone.innerHTML = '<div class="big">📦</div><p>点击或拖拽 APK 文件到这里上传</p><input type="file" id="fileInput" accept=".apk" hidden>';
      initUpload();
    }
  } catch(e) {
    toast('❌ 上传失败：' + e.message, false);
    zone.innerHTML = '<div class="big">📦</div><p>点击或拖拽 APK 文件到这里上传</p><input type="file" id="fileInput" accept=".apk" hidden>';
    initUpload();
  }
}

// 编辑版本
function editVersion(ver) {
  const modal = document.getElementById('modal');
  modal.style.display = 'flex';
  document.getElementById('editVer').value = ver;
  document.getElementById('editChangelog').value = document.getElementById('cl-' + ver)?.textContent || '';
  document.getElementById('editForce').checked = document.getElementById('force-' + ver)?.textContent === '强制';
}

function closeModal() {
  document.getElementById('modal').style.display = 'none';
}

async function saveVersion() {
  const ver = document.getElementById('editVer').value;
  const changelog = document.getElementById('editChangelog').value;
  const force = document.getElementById('editForce').checked;

  const r = await api('POST', '/api/update-version', {version: ver, changelog, force});
  if (r.ok) {
    toast('✅ 已保存');
    setTimeout(() => location.reload(), 500);
  } else {
    toast('❌ ' + r.error, false);
  }
}

// 删除版本
async function deleteVersion(ver) {
  if (!confirm('确定删除版本 ' + ver + ' 吗？')) return;
  const r = await api('POST', '/api/delete-version', {version: ver});
  if (r.ok) {
    toast('✅ 已删除');
    setTimeout(() => location.reload(), 500);
  } else {
    toast('❌ ' + r.error, false);
  }
}

// 设置为最新版本
async function setLatest(ver) {
  const r = await api('POST', '/api/set-latest', {version: ver});
  if (r.ok) {
    toast('✅ 已设为最新版本');
    setTimeout(() => location.reload(), 500);
  } else {
    toast('❌ ' + r.error, false);
  }
}

initUpload();
</script>

<!-- 编辑弹窗 -->
<div class="modal-bg" id="modal" style="display:none" onclick="if(event.target===this)closeModal()">
  <div class="modal">
    <h3>编辑版本 <span id="editVer"></span></h3>
    <input type="hidden" id="editVer">
    <div class="form-row">
      <label>更新说明</label>
      <textarea class="input" id="editChangelog" placeholder="这个版本改了什么..."></textarea>
    </div>
    <div class="form-row">
      <label>
        <input type="checkbox" id="editForce"> 强制更新（用户不能跳过）
      </label>
    </div>
    <div style="display:flex;gap:8px;justify-content:flex-end">
      <button class="btn btn-gray" onclick="closeModal()">取消</button>
      <button class="btn btn-blue" onclick="saveVersion()">保存</button>
    </div>
  </div>
</div>
</body></html>"""


class UpdateHandler(BaseHTTPRequestHandler):
    apk_dir = './apks'

    def log_message(self, fmt, *args):
        print(f'[{time.strftime("%H:%M:%S")}] {fmt % args}')

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)

    def send_html(self, html, status=200):
        body = html.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path.rstrip('/')
        params = parse_qs(url.query)

        if path == '' or path == '/' or path == '/index.html':
            self.render_dashboard()
        elif path == '/api/version':
            self.api_version(params)
        elif path.startswith('/api/version/'):
            self.api_version_detail(path.split('/api/version/')[1])
        elif path.startswith('/api/download/'):
            self.api_download(path.split('/api/download/')[1])
        elif path == '/api/manifest':
            self.api_manifest()
        elif path == '/style.css':
            self.send_html('/* inline */', 200)
        else:
            self.send_json({'error': 'not found'}, 404)

    def do_POST(self):
        url = urlparse(self.path)
        path = url.path.rstrip('/')

        if path == '/api/upload':
            self.handle_upload()
        elif path == '/api/update-version':
            self.handle_update_version()
        elif path == '/api/delete-version':
            self.handle_delete_version()
        elif path == '/api/set-latest':
            self.handle_set_latest()
        else:
            self.send_json({'error': 'not found'}, 404)

    # ==================== 管理后台 ====================

    def render_dashboard(self):
        manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
        versions = manifest.get('versions', {})
        latest = manifest.get('latest', '')
        downloads = manifest.get('downloads', {})

        total_downloads = sum(downloads.values())

        html = PAGE_HEADER
        html += PAGE_HEADER_STATS.format(
            version_count=len(versions),
            latest=latest or '无'
        )

        # 统计卡片
        html += f"""
        <div class="stats">
          <div class="stat-card">
            <div class="num">{len(versions)}</div>
            <div class="label">版本总数</div>
          </div>
          <div class="stat-card">
            <div class="num" style="color:#22c55e">{latest or '无'}</div>
            <div class="label">最新版本</div>
          </div>
          <div class="stat-card">
            <div class="num" style="color:#f59e0b">{total_downloads}</div>
            <div class="label">总下载次数</div>
          </div>
        </div>
        """

        # 上传区
        html += """
        <div class="card">
          <h2>📦 上传新版 APK</h2>
          <div class="upload-zone" id="uploadZone">
            <div class="big">📦</div>
            <p>点击或拖拽 APK 文件到这里上传</p>
            <input type="file" id="fileInput" accept=".apk" hidden>
          </div>
        </div>
        """

        # 版本列表
        html += """
        <div class="card">
          <h2>📋 版本列表</h2>
          <table>
            <tr><th>版本</th><th>版本号</th><th>大小</th><th>更新说明</th><th>状态</th><th>下载</th><th>操作</th></tr>
        """

        for ver in sorted(versions.keys(),
                          key=lambda v: versions[v].get('versionCode', 0), reverse=True):
            v = versions[ver]
            size_mb = v.get('size', 0) / 1024 / 1024
            dl = downloads.get(ver, 0)

            tags = ''
            if ver == latest:
                tags += '<span class="tag tag-latest">最新</span> '
            if v.get('force'):
                tags += '<span class="tag tag-force">强制</span> '

            changelog_display = (v.get('changelog', '') or '')[:80]
            if len(v.get('changelog', '')) > 80:
                changelog_display += '…'

            html += f"""
            <tr>
              <td><b>{ver}</b></td>
              <td>{v.get('versionCode','')}</td>
              <td>{size_mb:.2f} MB</td>
              <td id="cl-{ver}">{changelog_display or '<span style="color:#475569">（未填写）</span>'}</td>
              <td>{tags}</td>
              <td>{dl}</td>
              <td>
                <button class="btn btn-sm btn-blue" onclick="editVersion('{ver}')">编辑</button>
                <button class="btn btn-sm btn-gray" onclick="setLatest('{ver}')">设为最新</button>
                <button class="btn btn-sm btn-red" onclick="deleteVersion('{ver}')">删除</button>
              </td>
            </tr>
            """

        html += "</table></div>"

        # API 文档
        html += """
        <div class="card">
          <h2>🔌 API 接口</h2>
          <table>
            <tr><th>接口</th><th>说明</th></tr>
            <tr><td><code>GET /api/version?current=x.y.z</code></td><td>检查是否有更新</td></tr>
            <tr><td><code>GET /api/download/{version}</code></td><td>下载 APK（支持断点续传）</td></tr>
            <tr><td><code>GET /api/manifest</code></td><td>完整版本清单</td></tr>
          </table>
          <p style="margin-top:12px;color:#64748b;font-size:13px">
            App 配置：<b>设置 → 更新服务器</b> → <code>http://服务器IP:端口</code>
          </p>
        </div>
        """

        html += PAGE_FOOTER
        self.send_html(html)

    # ==================== API ====================

    def api_version(self, params):
        manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
        latest = manifest.get('latest', '')
        current = params.get('current', [''])[0]

        if not latest:
            self.send_json({'error': 'no versions'}, 404)
            return

        v = manifest['versions'][latest]
        has_update = False
        if current:
            try:
                cur = [int(x) for x in current.split('.')]
                lat = [int(x) for x in latest.split('.')]
                has_update = lat > cur
            except Exception:
                has_update = True

        self.send_json({
            'ok': True, 'latest': latest, 'has_update': has_update, 'current': current,
            'info': {
                'version': latest,
                'versionCode': v.get('versionCode', 0),
                'size': v.get('size', 0),
                'sha256': v.get('sha256', ''),
                'changelog': v.get('changelog', ''),
                'force': v.get('force', False),
                'minSdk': v.get('minSdk', 26),
                'published': v.get('published', ''),
                'download': f'/api/download/{latest}',
            },
        })

    def api_version_detail(self, ver):
        manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
        if ver not in manifest.get('versions', {}):
            self.send_json({'error': f'version {ver} not found'}, 404)
            return
        self.send_json({'ok': True, 'info': manifest['versions'][ver]})

    def api_manifest(self):
        manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
        self.send_json(manifest)

    def api_download(self, ver):
        manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
        versions = manifest.get('versions', {})
        if ver not in versions:
            self.send_json({'error': f'version {ver} not found'}, 404)
            return

        fname = versions[ver]['apk']
        fpath = os.path.join(self.apk_dir, fname)
        if not os.path.exists(fpath):
            self.send_json({'error': 'file not found'}, 404)
            return

        # 记录下载
        manifest.setdefault('downloads', {})[ver] = manifest['downloads'].get(ver, 0) + 1
        save_manifest(self.apk_dir, manifest)

        size = os.path.getsize(fpath)
        start = 0
        range_header = self.headers.get('Range')
        if range_header:
            try:
                start = int(range_header.replace('bytes=', '').split('-')[0])
            except Exception:
                pass

        self.send_response(206 if start > 0 else 200)
        self.send_header('Content-Type', 'application/vnd.android.package-archive')
        self.send_header('Content-Length', str(size - start))
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Disposition', f'attachment; filename="{fname}"')
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

    # ==================== 管理操作 ====================

    def handle_upload(self):
        """上传 APK 文件。"""
        try:
            form = cgi.FieldStorage(fp=self.rfile, headers=self.headers,
                environ={'REQUEST_METHOD': 'POST',
                         'CONTENT_TYPE': self.headers.get('Content-Type')})
            file_item = form['apk']
            if not file_item.filename.endswith('.apk'):
                self.send_json({'error': '只支持 .apk 文件'}, 400)
                return

            fname = os.path.basename(file_item.filename)
            fpath = os.path.join(self.apk_dir, fname)
            with open(fpath, 'wb') as f:
                f.write(file_item.file.read())

            # 重新扫描 manifest
            manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))

            # 提取版本号
            base = fname.replace('-release.apk', '').replace('.apk', '')
            parts = base.split('-')
            ver = parts[2] if len(parts) >= 3 else fname

            self.send_json({'ok': True, 'version': ver, 'file': fname})
        except Exception as e:
            self.send_json({'error': str(e)}, 500)

    def handle_update_version(self):
        """更新版本的 changelog 和 force 设置。"""
        try:
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            ver = body.get('version')
            manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
            if ver not in manifest.get('versions', {}):
                self.send_json({'error': f'version {ver} not found'}, 404)
                return
            manifest['versions'][ver]['changelog'] = body.get('changelog', '')
            manifest['versions'][ver]['force'] = bool(body.get('force', False))
            save_manifest(self.apk_dir, manifest)
            self.send_json({'ok': True})
        except Exception as e:
            self.send_json({'error': str(e)}, 500)

    def handle_delete_version(self):
        """删除一个版本。"""
        try:
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            ver = body.get('version')
            manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
            if ver not in manifest.get('versions', {}):
                self.send_json({'error': f'version {ver} not found'}, 404)
                return

            # 删除 APK 文件
            fname = manifest['versions'][ver]['apk']
            fpath = os.path.join(self.apk_dir, fname)
            if os.path.exists(fpath):
                os.remove(fpath)

            del manifest['versions'][ver]
            manifest.get('downloads', {}).pop(ver, None)

            # 更新 latest
            if manifest['versions']:
                manifest['latest'] = max(manifest['versions'].keys(),
                    key=lambda v: manifest['versions'][v].get('versionCode', 0))
            else:
                manifest['latest'] = ''

            save_manifest(self.apk_dir, manifest)
            self.send_json({'ok': True})
        except Exception as e:
            self.send_json({'error': str(e)}, 500)

    def handle_set_latest(self):
        """设置最新版本。"""
        try:
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            ver = body.get('version')
            manifest = scan_apks(self.apk_dir, load_manifest(self.apk_dir))
            if ver not in manifest.get('versions', {}):
                self.send_json({'error': f'version {ver} not found'}, 404)
                return
            manifest['latest'] = ver
            save_manifest(self.apk_dir, manifest)
            self.send_json({'ok': True})
        except Exception as e:
            self.send_json({'error': str(e)}, 500)


def main():
    ap = argparse.ArgumentParser(description='Beacon-DMR 更新推送服务')
    ap.add_argument('--port', type=int, default=8080)
    ap.add_argument('--host', default='0.0.0.0')
    ap.add_argument('--apk-dir', default=os.path.join(APP_DIR, 'apks'))
    args = ap.parse_args()

    os.makedirs(args.apk_dir, exist_ok=True)
    UpdateHandler.apk_dir = args.apk_dir

    manifest = scan_apks(args.apk_dir, load_manifest(args.apk_dir))
    print(f'APK 目录: {os.path.abspath(args.apk_dir)}')
    print(f'最新版本: {manifest.get("latest", "无")}（共 {len(manifest.get("versions", {}))} 个）')
    print(f'管理后台: http://localhost:{args.port}/')
    print(f'API 地址: http://localhost:{args.port}/api/version')
    print()

    server = HTTPServer((args.host, args.port), UpdateHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n已停止')
        server.server_close()


if __name__ == '__main__':
    main()
