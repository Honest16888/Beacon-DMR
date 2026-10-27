package net.beacon.dmr;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 信标 DMR —— BrandMeister Hoseline 收听台的 Android 外壳。
 *
 * 设计取舍：整个界面是一个自包含的单文件 HTML，原生层只需要补四件事：
 *   1. 允许无用户手势的自动播放（话组一有语音就得响）
 *   2. 让页面跑在真正的 https 源上（file:// 是不透明源，localStorage 会抛 SecurityError）
 *   3. 把录音 WAV 落到公共音乐目录（WebView 里 a[download] 静默失效）
 *   4. 收听期间持 Partial WakeLock，熄屏后 WebAudio 的调度不被掐断
 * 因此刻意不引入任何 androidx / 第三方依赖。
 */
public class MainActivity extends Activity {

    /**
     * 与 WebViewAssetLoader 相同的既定宿主。用 https 而非 file://，
     * 是因为只有非不透明源才能用 localStorage —— 话组订阅、主题、音量全靠它。
     */
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/index.html";
    private static final int REQ_STORAGE = 77;
    private static final int REQ_MIC = 78;

    private WebView web;
    private PowerManager.WakeLock wake;

    /** 保活服务当前是否已启动。只在目标状态变化时才去动服务。 */
    private boolean keepAliveOn = false;
    private DmrEngine dmr;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        dmr = new DmrEngine(this, this::onDmrEvent);

        web = new WebView(this);
        web.setBackgroundColor(0xFFEEF1F5);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // 关键：无需点击即可出声
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                             // 锁住系统字体缩放，紧凑布局才稳
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        web.setWebViewClient(new AssetClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        WebView.setWebContentsDebuggingEnabled(true);

        setContentView(web);
        web.loadUrl(START_URL);
    }

    @Override
    protected void onDestroy() {
        releaseWake();
        /* 引擎跟着这个 Activity 一起死，保活服务再挂着也只是一条空通知，
         * 而且它会一直持着 WakeLock 耗电 —— 必须一起停掉。 */
        keepAliveOn = false;
        DmrKeepAliveService.stop(this);
        if (dmr != null) {
            try {
                dmr.disconnect();
            } catch (Exception ignored) {
            }
            dmr = null;
        }
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ==================== 发射事件 → 页面 ==================== */

    /**
     * DmrEngine 的事件出口。已经在主线程，直接推给页面即可。
     * 页面用 window.onDmrEvent(json) 接收，JSON 用 JSONObject.quote 转义后内联，
     * 避免字符串里的引号把 JS 语法打断。
     */
    private void onDmrEvent(String json) {
        /* 连接状态可能在没有任何页面操作的情况下变化（心跳超时、被主站踢掉），
         * 所以每个事件都顺手对一次保活状态 —— 只在目标状态真的变了才动服务。 */
        syncKeepAlive();
        if (web == null || json == null) return;
        try {
            web.evaluateJavascript(
                    "window.onDmrEvent&&window.onDmrEvent(" + JSONObject.quote(json) + ");",
                    null);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code != REQ_MIC) return;
        boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
        if (web != null) {
            web.evaluateJavascript(
                    "window.onMicPermission&&window.onMicPermission(" + (ok ? "true" : "false") + ");",
                    null);
        }
        if (!ok) {
            Toast.makeText(this, "没有录音权限，无法发射", Toast.LENGTH_SHORT).show();
        }
    }

    /** lambda 里不能用限定 super，单独抽一层 */
    private void exitApp() {
        super.onBackPressed();
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            exitApp();
            return;
        }
        // 资料弹窗开着时，返回键先关弹窗，不直接退出
        web.evaluateJavascript(
                "(function(){var m=document.getElementById('stationModal');"
                        + "if(m&&m.classList.contains('show')){m.classList.remove('show');return 1;}"
                        + "return 0;})()",
                value -> {
                    if (!"1".equals(value)) exitApp();
                });
    }

    /* ==================== 资产以 https 源提供 ==================== */

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private class AssetClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null || !ASSET_HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
            String name = path.startsWith("/") ? path.substring(1) : path;
            if (name.contains("..")) return notFound();
            try {
                InputStream in = getAssets().open(name);
                Map<String, String> h = new HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                h.put("Cache-Control", "no-cache");
                return new WebResourceResponse(mimeOf(name), "utf-8", 200, "OK", h, in);
            } catch (IOException e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null) return false;
            if (ASSET_HOST.equals(u.getHost())) return false;
            // 站台资料里的地图等外链交给系统浏览器
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
            }
            return true;
        }
    }

    /* ==================== 录音落盘 / 主题 / 唤醒 ==================== */

    private static String sanitize(String name) {
        String s = (name == null || name.trim().isEmpty()) ? "rec.wav" : name.trim();
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            b.append(ok ? c : '_');
        }
        String r = b.toString();
        if (!r.toLowerCase().endsWith(".wav")) r = r + ".wav";
        return r;
    }

    private boolean storageGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 9 及以下的落点：有权限就进公共音乐目录，否则退到应用私有目录 */
    private File legacyDir() {
        File base;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        } else {
            base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        }
        return base == null ? null : new File(base, "DMR");
    }

    private void acquireWake() {
        if (wake != null && wake.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beacon:dmr-listen");
        wake.setReferenceCounted(false);
        wake.acquire();
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) {
            try {
                wake.release();
            } catch (Exception ignored) {
            }
        }
        wake = null;
    }

    /* ==================== 保活服务 ==================== */

    /**
     * 根据当前状态决定要不要把进程钉在前台。
     *
     * <p>「已连接主站」和「正在守听」两者任一成立就需要保活 ——
     * 前者靠每 5 秒一次的心跳维持 UDP 会话，后者靠一条长连 WebSocket，
     * 两种链路在 cached process 里都会被系统冻结。
     *
     * <p>只在<b>目标状态发生变化</b>时才动服务，避免每个事件都去
     * startForegroundService 刷一遍通知。
     */
    private void syncKeepAlive() {
        boolean connected = dmr != null && dmr.isConnected();
        boolean rxOn = dmr != null && dmr.isRxOn();
        boolean want = connected || rxOn;
        if (want == keepAliveOn) return;
        keepAliveOn = want;
        if (want) {
            DmrKeepAliveService.start(this, connected ? "已连接主站 · 切后台不断" : "守听中 · 切后台不断");
        } else {
            DmrKeepAliveService.stop(this);
        }
    }

    /** 暴露给页面的 window.Android */
    public class Bridge {

        private OutputStream out;
        private Uri mediaUri;
        private String savedName = "";
        private boolean needPermission;

        /**
         * 开一个 WAV 输出流。页面按 192KB 一块调用 wavChunk 灌数据，
         * 避免把十几 MB 的 base64 一次性塞过 JNI 边界。
         */
        @JavascriptInterface
        public boolean wavBegin(String name) {
            wavAbort();
            needPermission = false;
            try {
                String safe = sanitize(name);
                savedName = safe;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_MUSIC + "/DMR");
                    mediaUri = getContentResolver()
                            .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
                    if (mediaUri == null) return false;
                    out = getContentResolver().openOutputStream(mediaUri);
                } else {
                    if (!storageGranted()) {
                        needPermission = true;
                        return false;
                    }
                    File dir = legacyDir();
                    if (dir == null) return false;
                    if (!dir.exists() && !dir.mkdirs()) return false;
                    out = new FileOutputStream(new File(dir, safe));
                }
                return out != null;
            } catch (Exception e) {
                wavAbort();
                return false;
            }
        }

        @JavascriptInterface
        public boolean wavChunk(String b64) {
            if (out == null || b64 == null) return false;
            try {
                out.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 返回落点描述（给用户看），失败返回空串 */
        @JavascriptInterface
        public String wavEnd() {
            if (out == null) return "";
            try {
                out.flush();
                out.close();
            } catch (Exception ignored) {
            }
            out = null;
            mediaUri = null;
            boolean legacyPublic = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && storageGranted();
            return (legacyPublic ? "音乐/DMR/" : "Android/data/net.beacon.dmr/files/Music/DMR/")
                    + savedName;
        }

        @JavascriptInterface
        public void wavAbort() {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
                out = null;
            }
            if (mediaUri != null) {
                try {
                    getContentResolver().delete(mediaUri, null, null);
                } catch (Exception ignored) {
                }
                mediaUri = null;
            }
        }

        /** 上一次 wavBegin 失败是不是因为缺存储权限 */
        @JavascriptInterface
        public boolean needsStoragePermission() {
            return needPermission;
        }

        @JavascriptInterface
        public void requestStorage() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_STORAGE));
            }
        }

        /** 页面切主题时同步状态栏 / 导航栏 / WebView 底色，避免深色下白边 */
        @JavascriptInterface
        public void setTheme(final boolean dark) {
            runOnUiThread(() -> {
                if (web == null) return;
                web.setBackgroundColor(dark ? 0xFF0F1216 : 0xFFEEF1F5);
                getWindow().setStatusBarColor(dark ? 0xFF171B21 : 0xFFFFFFFF);
                getWindow().setNavigationBarColor(dark ? 0xFF0F1216 : 0xFFEEF1F5);
                View dv = getWindow().getDecorView();
                int f = dv.getSystemUiVisibility();
                if (dark) {
                    f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                dv.setSystemUiVisibility(f);
            });
        }

        /** 收听中：持 Partial WakeLock，熄屏也保住 WebAudio 的调度线程 */
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) acquireWake();
                else releaseWake();
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String version() {
            /* 以前这里硬编码 "1.1"，和 build.gradle 的 versionName 各写一份，
             * 迟早对不上（曾经就是 gradle 1.0 / 这里 1.1）。改成从包信息读，
             * 版本号只在 build.gradle 一处维护。 */
            try {
                return getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }

        /* ==================== 发射（MMDVM / Homebrew） ==================== */

        /** 加载声码器插件并自检，返回多行报告 */
        @JavascriptInterface
        public String dmrLoadVocoder() {
            try {
                return dmr == null ? "引擎未初始化" : dmr.loadVocoder();
            } catch (Throwable t) {
                return "加载失败：" + t;
            }
        }

        /**
         * 同步查询声码器是否真的可用。
         *
         * <p>状态事件是异步回传的，页面在拿到报告后可能还没收到事件，
         * 会出现「自检全过但显示未加载」。这个方法让页面能同步确认。
         */
        @JavascriptInterface
        public boolean dmrVocoderReady() {
            return dmr != null && dmr.isVocoderReady();
        }

        /** 只验 FEC 链（不需要声码器），返回三帧的十六进制 dump */
        @JavascriptInterface
        public String dmrSelfTest() {
            try {
                return dmr == null ? "引擎未初始化" : dmr.selfTestFec();
            } catch (Throwable t) {
                return "自检失败：" + t;
            }
        }

        /**
         * 登录主站。
         *
         * @param password SelfCare 里的「热点安全密码」，不是账号密码
         * @param essid    热点别名 ID（ESSID），1~99；0 表示不用别名。
         *                 同一个 DMR ID 已有一台热点在线时必须靠它才能并存。
         * @return 空串表示已发起连接（结果通过 onDmrEvent 异步回来），否则为错误原因
         */
        @JavascriptInterface
        public String dmrConnect(String host, int port, int dmrId, int essid, String password,
                                 String callsign, String location, String desc) {
            if (dmr == null) return "引擎未初始化";
            try {
                dmr.connect(host, port, dmrId, essid, password, callsign, location, desc);
                /* 一旦开始连就把保活拉起来 —— 握手本身也要好几秒，
                 * 这期间用户很可能已经切走了。 */
                syncKeepAlive();
                return "";
            } catch (Throwable t) {
                return String.valueOf(t.getMessage());
            }
        }

        @JavascriptInterface
        public void dmrDisconnect() {
            if (dmr != null) dmr.disconnect();
            syncKeepAlive();
        }

        /**
         * 按下 / 松开 PTT。
         *
         * @return 空串表示成功，否则为失败原因
         */
        @JavascriptInterface
        public String dmrPtt(boolean down, int tgid, int slot, int cc, boolean privateCall) {
            if (dmr == null) return "引擎未初始化";
            try {
                String err = dmr.ptt(down, tgid, slot, cc, privateCall);
                return err == null ? "" : err;
            } catch (Throwable t) {
                return String.valueOf(t.getMessage());
            }
        }

        /**
         * 麦克风增益，单位 dB，−12 ~ +12，0 为不改动。
         *
         * <p>作用在自动电平之后。超范围的值由引擎自己夹住，这里不校验 ——
         * 前端滑块已经限位，多一道判断只会让「值没生效」更难查。
         */
        @JavascriptInterface
        public void dmrSetMicGain(float db) {
            if (dmr != null) dmr.setMicGainDb(db);
        }

        @JavascriptInterface
        public String dmrStatus() {            if (dmr == null) return "{}";
            try {
                JSONObject o = new JSONObject();
                o.put("connected", dmr.isConnected());
                o.put("vocoder", dmr.isVocoderReady());
                o.put("txing", dmr.isTransmitting());
                /* 实际登录用的 ID（含 ESSID 别名）。界面拿它显示
                 * 「正在用 461418701 登录」，避免用户分不清填了哪个 ID。 */
                o.put("loginId", dmr.getLoginId());
                o.put("essid", dmr.getEssid());
                /* 排障用：pong 为 0 说明主站不回 MSTPONG（不做心跳超时判断），
                 * lateNak 是「会话建立之后才到的 MSTNAK」条数 —— 都被忽略了，
                 * 但数值能说明重连是不是在反复制造重复 RPTL。 */
                o.put("pong", dmr.getPongCount());
                o.put("lateNak", dmr.getLateNakCount());
                /* 接收侧状态 —— 对讲页靠它决定按钮亮不亮、要不要走 Hoseline 兜底 */
                o.put("rxOn", dmr.isRxOn());
                o.put("rxPlaying", dmr.isRxPlaying());
                o.put("rxVol", dmr.getRxVolume());
                o.put("rxJitter", dmr.getRxJitter());
                /* 接收音频电平（dBFS）。DMRD 包不带 RSSI，所以这是这条链路上
                 * 唯一可量化的电平指标 —— 界面上标成「音频电平」而不是「信号强度」 */
                o.put("rxDb", Math.round(dmr.getRxDb() * 10f) / 10.0);
                /* 发射侧的用户增益（dB）。页面重进时要回填滑块，
                 * 否则显示 0 dB 而实际还在 +6 dB，用户会以为设置丢了 */
                o.put("micGain", Math.round(dmr.getMicGainDb() * 10f) / 10.0);
                /* 丢包统计：靠 DMRD 包的 8 位序号跳变算出来的。
                 * rxStats 是给界面直接显示的一句话，rxLoss 给进度条/告警用。 */
                o.put("rxStats", dmr.getRxStats());
                o.put("rxPackets", dmr.getRxPackets());
                o.put("rxLoss", dmr.getRxLoss());
                /* 接收过滤的状态 —— 界面靠它显示「已屏蔽 N 人 · 挡掉 N 包」 */
                o.put("rxBlockSize", dmr.getRxBlockSize());
                o.put("rxBlocked", dmr.getRxBlockedCount());
                o.put("rxOnlyTg", dmr.isRxOnlyWatchTg());
                o.put("rxMuteSize", dmr.getRxMuteTgSize());
                o.put("rxMuted", dmr.getRxMutedCount());
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        /* ---------------- 接收（通联的收听半边） ---------------- */

        /** 开关接收播放。关掉就只发不收 */
        @JavascriptInterface
        public void dmrSetRxOn(boolean on) {
            if (dmr != null) dmr.setRxOn(on);
            syncKeepAlive();
        }

        /** 接收增益，线性 0~2 */
        @JavascriptInterface
        public void dmrSetRxVolume(float g) {
            if (dmr != null) dmr.setRxVolume(g);
        }

        /** 抖动缓冲厚度（毫秒）。网络不稳时加长 */
        @JavascriptInterface
        public void dmrSetRxJitter(int ms) {
            if (dmr != null) dmr.setRxJitter(ms);
        }

        /* ---------------- 接收过滤（屏蔽名单 / 单流锁定） ---------------- */

        /**
         * 设置屏蔽名单。参数是逗号分隔的 DMR ID，空串表示清空。
         *
         * <p>用字符串而不是数组，是因为 JS↔Java 桥对数组的支持在各版本 WebView 上
         * 不一致，字符串最稳。
         */
        @JavascriptInterface
        public void dmrSetRxBlock(String csv) {
            if (dmr == null) return;
            dmr.setRxBlock(parseIdCsv(csv));
        }

        /**
         * 设置静音的话组。参数是逗号分隔的话组号，空串表示清空。
         *
         * <p>和 {@link #dmrSetRxBlock} 是两个维度：那个按「谁在说」，
         * 这个按「在哪个组说」。
         */
        @JavascriptInterface
        public void dmrSetRxMuteTg(String csv) {
            if (dmr == null) return;
            dmr.setRxMuteTg(parseIdCsv(csv));
        }

        /** "4614187,46001" → int[]，非法项直接跳过 */
        private int[] parseIdCsv(String csv) {
            java.util.List<Integer> list = new java.util.ArrayList<>();
            if (csv != null) {
                for (String p : csv.split(",")) {
                    String t = p.trim();
                    if (t.isEmpty()) continue;
                    try {
                        int v = Integer.parseInt(t);
                        if (v > 0) list.add(v);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            return arr;
        }

        /** 只收当前话组（主站同时推多路时不混音） */
        @JavascriptInterface
        public void dmrSetRxOnlyWatchTg(boolean on) {
            if (dmr != null) dmr.setRxOnlyWatchTg(on);
        }

        /** 界面当前关注的话组。0 = 还没选，「只收当前话组」暂不生效 */
        @JavascriptInterface
        public void dmrSetWatchTg(int tgid) {
            if (dmr != null) dmr.setWatchTg(tgid);
        }

        /**
         * 呼号 ⇄ DMR ID 查询。**异步**，结果通过 {@code window.__dmrLookup(res)} 回抛。
         *
         * <p>不能同步返回：这个桥接方法跑在 WebView 的 JavaBridge 线程上，同步阻塞会
         * 把 JS 一起卡住（页面在等待返回值），弱网下就是几秒白屏。
         * 所以这里起一个线程查，查完 evaluateJavascript 回去。
         *
         * @param q 呼号（BH6RGQ）或纯数字 ID（4614187）
         */
        @JavascriptInterface
        public void dmrLookupDmrIdAsync(final String q) {
            final String key = q == null ? "" : q.trim();
            if (key.isEmpty()) {
                deliverLookup("");
                return;
            }
            new Thread(() -> {
                String res = "";
                try {
                    res = DmrDirectory.lookup(key);
                } catch (Throwable ignored) {
                }
                deliverLookup(res);
            }, "dmr-lookup").start();
        }

        private void deliverLookup(String res) {
            final String js = "window.__dmrLookup&&window.__dmrLookup("
                    + JSONObject.quote(res == null ? "" : res) + ");";
            runOnUiThread(() -> {
                try {
                    if (web != null) web.evaluateJavascript(js, null);
                } catch (Exception ignored) {
                }
            });
        }

        /** 接收链路自检（解交织 + 解码），不需要网络 */
        @JavascriptInterface
        public String dmrRxSelfTest() {
            if (dmr == null) return "引擎未初始化";
            try {
                return dmr.rxSelfTest();
            } catch (Throwable t) {
                return "自检失败：" + t;
            }
        }

        @JavascriptInterface
        public boolean hasMicPermission() {
            return dmr != null && dmr.hasAudioPermission();
        }

        @JavascriptInterface
        public void requestMicPermission() {
            runOnUiThread(() -> requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        }
    }
}
