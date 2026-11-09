package nrl.pulse.net;

import android.util.Log;
import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import java.nio.charset.StandardCharsets;

/**
 * FMO MQTT 客户端（Eclipse Paho MQTTv5）。
 *
 * FMO 的实时通信全部走 MQTT，不是 WebSocket。
 * 认证用 mTLS 双向证书（BouncyCastle 证书链），不是 Bearer token。
 */
public class FmoMqtt {

    private static final String TAG = "FmoMqtt";

    private IMqttAsyncClient client;
    private String broker;
    private Listener listener;

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessage(String topic, byte[] payload);
        void onError(String msg);
    }

    public FmoMqtt(String broker, Listener listener) {
        this.broker = broker;
        this.listener = listener;
    }

    public void connect(String certPem, String keyPem, String caPem) {
        try {
            String clientId = "fmo-" + System.currentTimeMillis();
            client = new MqttAsyncClient(broker, clientId);

            client.setCallback(new MqttCallback() {
                @Override public void disconnected(MqttDisconnectResponse resp) {
                    String reason = (resp != null) ? resp.getReasonString() : "unknown";
                    Log.w(TAG, "MQTT disconnected: " + reason);
                    if (listener != null) listener.onDisconnected(reason);
                }
                @Override public void mqttErrorOccurred(MqttException ex) {
                    Log.e(TAG, "MQTT error", ex);
                    if (listener != null) listener.onError("MQTT 错误：" + ex.getMessage());
                }
                @Override public void messageArrived(String topic, MqttMessage msg) {
                    if (listener != null) listener.onMessage(topic, msg.getPayload());
                }
                @Override public void deliveryComplete(IMqttToken token) {}
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    Log.i(TAG, "connectComplete: " + serverURI);
                }
                @Override public void authPacketArrived(int reasonCode, MqttProperties props) {}
            });

            MqttConnectionOptions opts = new MqttConnectionOptions();
            opts.setCleanStart(true);
            opts.setConnectionTimeout(15);
            opts.setKeepAliveInterval(30);
            opts.setAutomaticReconnect(true);

            if (certPem != null && keyPem != null) {
                javax.net.ssl.SSLContext ctx = TlsUtils.createSslContext(certPem, keyPem, caPem);
                opts.setSocketFactory(ctx.getSocketFactory());
            }

            client.connect(opts, null, new MqttActionListener() {
                @Override public void onSuccess(IMqttToken tok) {
                    Log.i(TAG, "MQTT connected: " + broker);
                    if (listener != null) listener.onConnected();
                }
                @Override public void onFailure(IMqttToken tok, Throwable ex) {
                    Log.e(TAG, "MQTT connect failed", ex);
                    if (listener != null) listener.onError("MQTT 连接失败：" + ex.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "MQTT init error", e);
            if (listener != null) listener.onError("MQTT 初始化失败：" + e.getMessage());
        }
    }

    public void subscribe(String topic, int qos) {
        if (client == null || !client.isConnected()) return;
        try {
            MqttSubscription sub = new MqttSubscription(topic, qos);
            client.subscribe(new MqttSubscription[]{ sub }, null, null,
                new IMqttMessageListener[]{ null }, null);
            Log.i(TAG, "subscribed: " + topic);
        } catch (MqttException e) {
            Log.e(TAG, "subscribe error: " + topic, e);
        }
    }

    public void publish(String topic, byte[] payload, int qos, boolean retain) {
        if (client == null || !client.isConnected()) return;
        try {
            MqttMessage msg = new MqttMessage(payload);
            msg.setQos(qos);
            msg.setRetained(retain);
            client.publish(topic, msg);
        } catch (MqttException e) {
            Log.e(TAG, "publish error: " + topic, e);
        }
    }

    public void publishText(String topic, String text, int qos) {
        publish(topic, text.getBytes(StandardCharsets.UTF_8), qos, false);
    }

    public void disconnect() {
        try {
            if (client != null && client.isConnected()) client.disconnect();
        } catch (Exception ignored) {}
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}
