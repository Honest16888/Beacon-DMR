# FMO Pulse ProGuard 规则

# 保留 native 方法
-keep class nrl.pulse.core.NativeGuard { *; }

# 保留证书类
-keep class nrl.pulse.cert.** { *; }

# 保留 MQTT 回调
-keep class nrl.pulse.net.** { *; }

# 保留 FMO 配置类（Kotlinx 序列化）
-keep class nrl.pulse.fmo.BeaconConfig { *; }
-keep class nrl.pulse.fmo.BroadcastConfig { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }

# Paho MQTT
-keep class org.eclipse.paho.** { *; }
