package nrl.pulse.fmo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.view.View;
import android.content.pm.PackageManager;
import android.Manifest;
import nrl.pulse.cert.CertManager;
import nrl.pulse.cert.CertIssuer;

/**
 * FMO 独立 App 入口。
 *
 * 界面（最小可用）：
 *   1. MQTT Broker 地址 + API 地址
 *   2. 呼号输入
 *   3. 连接 / 断开
 *   4. 信标 / 广播 / APRS 快捷按钮
 *   5. 电台列表
 *   6. 状态日志
 */
public class MainActivity extends Activity implements FmoProtocol.Listener {

    private EditText etBroker, etApi, etCallsign;
    private Button btnConnect, btnBeacon, btnBroadcast, btnStations, btnIssueCert;
    private TextView tvStatus, tvLog, tvStations;
    private FmoProtocol protocol;
    private CertManager certMgr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etBroker = findViewById(R.id.etBroker);
        etApi = findViewById(R.id.etApi);
        etCallsign = findViewById(R.id.etCallsign);
        btnConnect = findViewById(R.id.btnConnect);
        btnBeacon = findViewById(R.id.btnBeacon);
        btnBroadcast = findViewById(R.id.btnBroadcast);
        btnStations = findViewById(R.id.btnStations);
        btnIssueCert = findViewById(R.id.btnIssueCert);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvStations = findViewById(R.id.tvStations);

        certMgr = new CertManager(this);

        // 默认值
        etBroker.setText("ssl://mqtt.example.com:8883");
        etApi.setText("https://api.example.com");
        etCallsign.setText("BH6RGQ");

        btnConnect.setOnClickListener(v -> {
            if (protocol != null && protocol.isConnected()) {
                protocol.disconnect();
                btnConnect.setText("连接");
                tvStatus.setText("已断开");
                return;
            }
            String broker = etBroker.getText().toString().trim();
            String apiUrl = etApi.getText().toString().trim();
            String callsign = etCallsign.getText().toString().trim().toUpperCase();
            if (broker.isEmpty() || callsign.isEmpty()) {
                toast("请填 Broker 地址和呼号");
                return;
            }
            protocol = new FmoProtocol(broker, apiUrl, certMgr, callsign, this);
            btnConnect.setText("连接中…");
            protocol.connect();
        });

        btnBeacon.setOnClickListener(v -> {
            if (protocol != null && protocol.isConnected()) {
                protocol.getBeaconConfig().enabled = true;
                protocol.sendBeacon();
                log("信标已发送");
            }
        });

        btnBroadcast.setOnClickListener(v -> {
            if (protocol != null && protocol.isConnected()) {
                protocol.sendBroadcast("FMO 测试广播");
                log("广播已发送");
            }
        });

        btnIssueCert.setOnClickListener(v -> {
            String apiUrl = etApi.getText().toString().trim();
            String callsign = etCallsign.getText().toString().trim().toUpperCase();
            if (apiUrl.isEmpty() || callsign.isEmpty()) {
                toast("请填 API 地址和呼号");
                return;
            }
            log("=== 证书签发开始 ===");
            log("API: " + apiUrl);
            log("呼号: " + callsign);
            btnIssueCert.setEnabled(false);
            btnIssueCert.setText("签发中...");
            new Thread(() -> {
                String err = null;
                boolean ok = false;
                try {
                    ok = CertIssuer.issueCertificate(getApplicationContext(), apiUrl, callsign, "");
                } catch (Exception e) {
                    err = e.getMessage();
                }
                boolean fOk = ok;
                String fErr = err;
                runOnUiThread(() -> {
                    btnIssueCert.setEnabled(true);
                    btnIssueCert.setText("🔐 发证书（生成密钥 + CSR + 绑定）");
                    if (fOk) {
                        log("✅ 证书签发成功！");
                        toast("证书签发成功");
                    } else {
                        log("❌ 证书签发失败" + (fErr != null ? ": " + fErr : ""));
                        log("检查：1) API 地址是否正确  2) /api/cert/bind 是否存在  3) 网络是否通");
                        toast("证书签发失败" + (fErr != null ? ": " + fErr : ""));
                    }
                });
            }).start();
        });

        btnStations.setOnClickListener(v -> {
            if (protocol != null) {
                protocol.fetchStations();
                log("正在获取电台列表…");
            }
        });

        // 请求录音权限
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
        }
    }

    // ---- FmoProtocol.Listener ----
    @Override public void onConnected() {
        runOnUiThread(() -> {
            btnConnect.setText("断开");
            tvStatus.setText("已连接");
            log("MQTT 已连接");
        });
    }
    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            btnConnect.setText("连接");
            tvStatus.setText("已断开：" + reason);
        });
    }
    @Override public void onVoiceRx(byte[] pcm) {
        runOnUiThread(() -> log("收到语音: " + pcm.length + " 字节"));
    }
    @Override public void onBeacon(String from, String payload) {
        runOnUiThread(() -> log("信标: " + from));
    }
    @Override public void onBroadcast(String from, String payload) {
        runOnUiThread(() -> log("广播: " + payload));
    }
    @Override public void onAprs(String from, double lat, double lon, String comment) {
        runOnUiThread(() -> log("APRS: " + from + " " + lat + "," + lon));
    }
    @Override public void onStationList(String json) {
        runOnUiThread(() -> {
            tvStations.setText(json);
            log("收到电台列表");
        });
    }
    @Override public void onError(String msg) {
        runOnUiThread(() -> {
            tvStatus.setText("错误：" + msg);
            log("错误：" + msg);
        });
    }

    private void log(String s) {
        tvLog.append(s + "\n");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
