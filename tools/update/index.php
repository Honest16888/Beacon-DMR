<?php
/**
 * Beacon-DMR 更新推送系统（PHP 版）
 *
 * 部署：把整个 update/ 目录上传到网站根目录即可，无需任何命令。
 * 访问：http://你的网站/update/
 *
 * 目录结构：
 *   update/
 *   ├── index.php          ← 管理后台 + API
 *   └── apks/              ← APK 文件放这里
 *       └── *.apk
 */

$APK_DIR = __DIR__ . '/apks';
$MANIFEST = $APK_DIR . '/manifest.json';

// ===================== 工具函数 =====================

function load_manifest($path) {
    if (file_exists($path)) {
        $data = json_decode(file_get_contents($path), true);
        if ($data) return $data;
    }
    return ['latest' => '', 'versions' => [], 'downloads' => []];
}

function save_manifest($path, $data) {
    file_put_contents($path, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
}

function sha256_file($path) {
    return hash_file('sha256', $path);
}

function scan_apks($apk_dir, $manifest_path) {
    $manifest = load_manifest($manifest_path);
    $versions = $manifest['versions'] ?? [];

    foreach (glob($apk_dir . '/*.apk') as $fpath) {
        $fname = basename($fpath);
        // 已收录的跳过
        foreach ($versions as $v) {
            if ($v['apk'] === $fname) continue 2;
        }
        // 提取版本号
        $base = str_replace(['-release.apk', '.apk'], '', $fname);
        $parts = explode('-', $base);
        if (count($parts) < 3) continue;
        $ver = $parts[2];

        // versionCode: 2.10.1 → 21001
        $vp = explode('.', $ver);
        $vc = count($vp) === 3 ? ($vp[0] * 10000 + $vp[1] * 100 + $vp[2]) : 0;

        $versions[$ver] = [
            'versionCode' => $vc,
            'apk' => $fname,
            'size' => filesize($fpath),
            'sha256' => sha256_file($fpath),
            'changelog' => '',
            'minSdk' => 26,
            'force' => false,
            'published' => date('Y-m-d\TH:i:s\Z', filemtime($fpath)),
        ];
    }

    // 更新 latest
    $latest = '';
    foreach ($versions as $ver => $v) {
        if ($v['versionCode'] > ($versions[$latest]['versionCode'] ?? 0)) {
            $latest = $ver;
        }
    }
    $manifest['versions'] = $versions;
    $manifest['latest'] = $latest;

    save_manifest($manifest_path, $manifest);
    return $manifest;
}

function json_response($data, $status = 200) {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Access-Control-Allow-Origin: *');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

// ===================== API 路由 =====================

$action = $_GET['action'] ?? '';
$current = $_GET['current'] ?? '';

// 检查更新
if ($action === 'version') {
    $manifest = scan_apks($APK_DIR, $MANIFEST);
    $latest = $manifest['latest'] ?? '';
    if (!$latest) json_response(['error' => 'no versions'], 404);

    $v = $manifest['versions'][$latest];
    $has_update = false;
    if ($current) {
        $cur = array_map('intval', explode('.', $current));
        $lat = array_map('intval', explode('.', $latest));
        $has_update = $lat > $cur;
    }

    json_response([
        'ok' => true, 'latest' => $latest, 'has_update' => $has_update, 'current' => $current,
        'info' => [
            'version' => $latest,
            'versionCode' => $v['versionCode'],
            'size' => $v['size'],
            'sha256' => $v['sha256'],
            'changelog' => $v['changelog'],
            'force' => $v['force'],
            'minSdk' => $v['minSdk'],
            'published' => $v['published'],
            'download' => '?action=download&ver=' . $latest,
        ],
    ]);
}

// 下载 APK
if ($action === 'download') {
    $ver = $_GET['ver'] ?? '';
    $manifest = scan_apks($APK_DIR, $MANIFEST);
    if (!isset($manifest['versions'][$ver])) json_response(['error' => 'not found'], 404);

    $fname = $manifest['versions'][$ver]['apk'];
    $fpath = $APK_DIR . '/' . $fname;
    if (!file_exists($fpath)) json_response(['error' => 'file not found'], 404);

    // 记录下载
    $manifest['downloads'][$ver] = ($manifest['downloads'][$ver] ?? 0) + 1;
    save_manifest($MANIFEST, $manifest);

    // 输出 APK
    header('Content-Type: application/vnd.android.package-archive');
    header('Content-Length: ' . filesize($fpath));
    header('Content-Disposition: attachment; filename="' . $fname . '"');
    header('Accept-Ranges: bytes');
    readfile($fpath);
    exit;
}

// 完整版本清单
if ($action === 'manifest') {
    json_response(scan_apks($APK_DIR, $MANIFEST));
}

// 上传 APK
if ($action === 'upload' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    if (empty($_FILES['apk'])) json_response(['error' => '没有收到文件'], 400);

    $file = $_FILES['apk'];
    if ($file['error'] !== UPLOAD_ERR_OK) json_response(['error' => '上传失败: ' . $file['error']], 500);

    $fname = basename($file['name']);
    if (!str_ends_with($fname, '.apk')) json_response(['error' => '只支持 .apk 文件'], 400);

    $dest = $APK_DIR . '/' . $fname;
    if (!move_uploaded_file($file['tmp_name'], $dest)) {
        json_response(['error' => '保存失败'], 500);
    }

    $manifest = scan_apks($APK_DIR, $MANIFEST);
    $base = str_replace(['-release.apk', '.apk'], '', $fname);
    $parts = explode('-', $base);
    $ver = count($parts) >= 3 ? $parts[2] : $fname;

    json_response(['ok' => true, 'version' => $ver, 'file' => $fname]);
}

// 更新版本信息（changelog / force）
if ($action === 'update-version' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $body = json_decode(file_get_contents('php://input'), true);
    $ver = $body['version'] ?? '';
    $manifest = scan_apks($APK_DIR, $MANIFEST);
    if (!isset($manifest['versions'][$ver])) json_response(['error' => 'not found'], 404);

    $manifest['versions'][$ver]['changelog'] = $body['changelog'] ?? '';
    $manifest['versions'][$ver]['force'] = !empty($body['force']);
    save_manifest($MANIFEST, $manifest);
    json_response(['ok' => true]);
}

// 删除版本
if ($action === 'delete-version' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $body = json_decode(file_get_contents('php://input'), true);
    $ver = $body['version'] ?? '';
    $manifest = scan_apks($APK_DIR, $MANIFEST);
    if (!isset($manifest['versions'][$ver])) json_response(['error' => 'not found'], 404);

    $fname = $manifest['versions'][$ver]['apk'];
    if (file_exists($APK_DIR . '/' . $fname)) unlink($APK_DIR . '/' . $fname);

    unset($manifest['versions'][$ver]);
    unset($manifest['downloads'][$ver]);

    // 更新 latest
    $latest = '';
    foreach ($manifest['versions'] as $v2 => $v) {
        if ($v['versionCode'] > ($manifest['versions'][$latest]['versionCode'] ?? 0)) {
            $latest = $v2;
        }
    }
    $manifest['latest'] = $latest;
    save_manifest($MANIFEST, $manifest);
    json_response(['ok' => true]);
}

// 设为最新版本
if ($action === 'set-latest' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $body = json_decode(file_get_contents('php://input'), true);
    $ver = $body['version'] ?? '';
    $manifest = scan_apks($APK_DIR, $MANIFEST);
    if (!isset($manifest['versions'][$ver])) json_response(['error' => 'not found'], 404);

    $manifest['latest'] = $ver;
    save_manifest($MANIFEST, $manifest);
    json_response(['ok' => true]);
}

// ===================== 管理后台 HTML =====================

$manifest = scan_apks($APK_DIR, $MANIFEST);
$versions = $manifest['versions'] ?? [];
$latest = $manifest['latest'] ?? '';
$downloads = $manifest['downloads'] ?? [];
$total_dl = array_sum($downloads);

?>
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Beacon-DMR 更新管理</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:#0f172a;color:#e2e8f0;min-height:100vh}
.top{background:#1e293b;padding:16px 24px;display:flex;align-items:center;gap:16px;border-bottom:1px solid #334155}
.top h1{font-size:20px;font-weight:700}
.badge{background:#3b82f6;color:#fff;padding:2px 10px;border-radius:12px;font-size:12px}
.badge-green{background:#22c55e}
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
.btn-red{background:#ef4444;color:#fff}.btn-red:hover{background:#dc2626}
.btn-gray{background:#475569;color:#fff}.btn-gray:hover{background:#334155}
.tag{display:inline-block;padding:2px 8px;border-radius:6px;font-size:11px;font-weight:700}
.tag-latest{background:#3b82f6;color:#fff}
.tag-force{background:#ef4444;color:#fff}
.input{width:100%;padding:8px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:14px}
.input:focus{outline:none;border-color:#3b82f6}
textarea.input{min-height:60px;resize:vertical}
.form-row{margin-bottom:12px}
.form-row label{display:block;font-size:13px;color:#94a3b8;margin-bottom:4px}
.upload-zone{border:2px dashed #334155;border-radius:12px;padding:40px;text-align:center;cursor:pointer;transition:all .2s}
.upload-zone:hover{border-color:#3b82f6}
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
code{background:#0f172a;padding:2px 6px;border-radius:4px;font-size:13px;color:#3b82f6}
</style></head><body>

<div class="top">
  <h1>📡 Beacon-DMR 更新管理</h1>
  <span class="badge"><?= count($versions) ?> 个版本</span>
  <span class="badge badge-green">最新 <?= htmlspecialchars($latest ?: '无') ?></span>
</div>

<div class="main">

  <!-- 统计 -->
  <div class="stats">
    <div class="stat-card"><div class="num"><?= count($versions) ?></div><div class="label">版本总数</div></div>
    <div class="stat-card"><div class="num" style="color:#22c55e"><?= htmlspecialchars($latest ?: '无') ?></div><div class="label">最新版本</div></div>
    <div class="stat-card"><div class="num" style="color:#f59e0b"><?= $total_dl ?></div><div class="label">总下载次数</div></div>
  </div>

  <!-- 上传 -->
  <div class="card">
    <h2>📦 上传新版 APK</h2>
    <div class="upload-zone" id="uploadZone">
      <div class="big">📦</div>
      <p>点击或拖拽 APK 文件到这里上传</p>
      <input type="file" id="fileInput" accept=".apk" hidden>
    </div>
  </div>

  <!-- 版本列表 -->
  <div class="card">
    <h2>📋 版本列表</h2>
    <table>
      <tr><th>版本</th><th>版本号</th><th>大小</th><th>更新说明</th><th>状态</th><th>下载</th><th>操作</th></tr>
      <?php foreach (array_reverse(array_keys($versions)) as $ver):
          $v = $versions[$ver];
          $size_mb = $v['size'] / 1024 / 1024;
          $dl = $downloads[$ver] ?? 0;
          $tags = '';
          if ($ver === $latest) $tags .= '<span class="tag tag-latest">最新</span> ';
          if ($v['force']) $tags .= '<span class="tag tag-force">强制</span> ';
      ?>
      <tr>
        <td><b><?= htmlspecialchars($ver) ?></b></td>
        <td><?= $v['versionCode'] ?></td>
        <td><?= sprintf('%.2f', $size_mb) ?> MB</td>
        <td><?= htmlspecialchars(mb_substr($v['changelog'] ?: '（未填写）', 0, 80)) ?></td>
        <td><?= $tags ?></td>
        <td><?= $dl ?></td>
        <td>
          <button class="btn btn-sm btn-blue" onclick="editVersion('<?= htmlspecialchars($ver) ?>')">编辑</button>
          <button class="btn btn-sm btn-gray" onclick="setLatest('<?= htmlspecialchars($ver) ?>')">设为最新</button>
          <button class="btn btn-sm btn-red" onclick="deleteVersion('<?= htmlspecialchars($ver) ?>')">删除</button>
        </td>
      </tr>
      <?php endforeach; ?>
    </table>
  </div>

  <!-- API 文档 -->
  <div class="card">
    <h2>🔌 API 接口</h2>
    <table>
      <tr><th>接口</th><th>说明</th></tr>
      <tr><td><code>?action=version&current=x.y.z</code></td><td>检查是否有更新</td></tr>
      <tr><td><code>?action=download&ver=x.y.z</code></td><td>下载 APK</td></tr>
      <tr><td><code>?action=manifest</code></td><td>完整版本清单</td></tr>
    </table>
    <p style="margin-top:12px;color:#64748b;font-size:13px">
      App 配置：<b>设置 → 更新服务器</b> → <code><?= (isset($_SERVER['HTTPS']) ? 'https' : 'http') ?>://<?= $_SERVER['HTTP_HOST'] ?><?= rtrim(dirname($_SERVER['REQUEST_URI']), '/') ?>/</code>
    </p>
  </div>

</div>

<!-- 编辑弹窗 -->
<div class="modal-bg" id="modal" style="display:none" onclick="if(event.target===this)closeModal()">
  <div class="modal">
    <h3>编辑版本 <span id="modalVer"></span></h3>
    <input type="hidden" id="editVer">
    <div class="form-row">
      <label>更新说明</label>
      <textarea class="input" id="editChangelog" placeholder="这个版本改了什么..."></textarea>
    </div>
    <div class="form-row">
      <label><input type="checkbox" id="editForce"> 强制更新（用户不能跳过）</label>
    </div>
    <div style="display:flex;gap:8px;justify-content:flex-end">
      <button class="btn btn-gray" onclick="closeModal()">取消</button>
      <button class="btn btn-blue" onclick="saveVersion()">保存</button>
    </div>
  </div>
</div>

<script>
function toast(msg, ok=true) {
  const t = document.createElement('div');
  t.className = 'toast ' + (ok ? 'toast-ok' : 'toast-err');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}

async function api(action, body=null) {
  const opts = body ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : {method:'POST'};
  const r = await fetch('?action=' + action, opts);
  return r.json();
}

// 上传
function initUpload() {
  const zone = document.getElementById('uploadZone');
  const input = document.getElementById('fileInput');
  zone.addEventListener('click', () => input.click());
  zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('dragover'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
  zone.addEventListener('drop', e => {
    e.preventDefault(); zone.classList.remove('dragover');
    if (e.dataTransfer.files.length) uploadFile(e.dataTransfer.files[0]);
  });
  input.addEventListener('change', () => { if (input.files.length) uploadFile(input.files[0]); });
}

async function uploadFile(file) {
  const zone = document.getElementById('uploadZone');
  zone.innerHTML = '<p>⏳ 正在上传 ' + file.name + '（' + (file.size/1024/1024).toFixed(1) + ' MB）...</p>';
  const form = new FormData();
  form.append('apk', file);
  try {
    const r = await fetch('?action=upload', {method:'POST', body:form});
    const data = await r.json();
    if (data.ok) { toast('✅ 上传成功：' + data.version); setTimeout(()=>location.reload(),800); }
    else { toast('❌ ' + data.error, false); location.reload(); }
  } catch(e) { toast('❌ 上传失败', false); location.reload(); }
}

function editVersion(ver) {
  document.getElementById('modal').style.display = 'flex';
  document.getElementById('editVer').value = ver;
  document.getElementById('modalVer').textContent = ver;
  document.getElementById('editChangelog').value = '';
  document.getElementById('editForce').checked = false;
  // 读取当前值
  fetch('?action=version&current=0').then(r=>r.json()).then(d=>{
    if (d.info && d.info.version === ver) {
      document.getElementById('editChangelog').value = d.info.changelog || '';
      document.getElementById('editForce').checked = !!d.info.force;
    }
  });
}
function closeModal() { document.getElementById('modal').style.display = 'none'; }

async function saveVersion() {
  const r = await api('update-version', {
    version: document.getElementById('editVer').value,
    changelog: document.getElementById('editChangelog').value,
    force: document.getElementById('editForce').checked
  });
  if (r.ok) { toast('✅ 已保存'); setTimeout(()=>location.reload(),500); }
  else toast('❌ ' + r.error, false);
}

async function deleteVersion(ver) {
  if (!confirm('确定删除版本 ' + ver + ' 吗？')) return;
  const r = await api('delete-version', {version: ver});
  if (r.ok) { toast('✅ 已删除'); setTimeout(()=>location.reload(),500); }
  else toast('❌ ' + r.error, false);
}

async function setLatest(ver) {
  const r = await api('set-latest', {version: ver});
  if (r.ok) { toast('✅ 已设为最新'); setTimeout(()=>location.reload(),500); }
  else toast('❌ ' + r.error, false);
}

initUpload();
</script>
</body></html>
