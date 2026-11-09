# FMO Pulse —— 独立 FMO App

从 OpenRF（`com.nrl.pulse` 5.75）的 APK 逆向架构，提取 FMO 模块的独立实现。

## 四大核心发现（全部从混淆类/so 反编译）

### 1. FMO 用 APRS 协议（4 种帧类型）

| 帧类型 | 格式 | 用途 |
|---|---|---|
| `APFMO0,TCPIP*::` | 消息帧 | 文本消息 |
| `APFMO1,TCPIP*>:` | 状态帧 | 状态报告 |
| `APFMO2,TCPIP*>:` | 消息帧 | 个性化消息（如 `fmoapp`） |
| `APFMO4,TCPIP*:=` | 位置帧 | 经纬度 + 电台信息 |

**信标帧**：`>iFMO-APP,BEACON,FREQ:439.500,HEIGHT:20,RIG:FT-991A,ANT:GP-3`
**位置帧**：`= 3112.00N/12128.00E>`
**广播帧**：`>iFMO-APP,STATION,CERT:<证书信息>`

**限制**：512 字节（APRS 官方限制）

### 2. 语音用 AMBE 编解码（不是 Opus）

| 函数 | 用途 |
|---|---|
| `mbe_decodeAmbe2450Parms` | AMBE 2450 参数解码 |
| `mbe_eccAmbe3600x2450C0/Data` | AMBE 3600x2450 纠错 |
| `mbe_demodulateAmbe3600x2450Data` | AMBE 3600x2450 解调 |
| `mbe_processAmbe2450Dataf` | AMBE 2450 处理 |
| `mbe_synthesizeSpeechf` | 语音合成 |
| `mbe_floattoshort` | 浮点 → 短整型 |
| `mbe_golay2312` | Golay(23,12) 纠错 |
| `mbe_hamming1511` | Hamming(15,11) 纠错 |

FFT：pffft（快速傅里叶变换）

### 3. 证书签发流程（ECDSA P-256 + PKCS#10）

```
1. 本地生成 ECDSA P-256 密钥对（seed → KeyPair）
2. 生成 PKCS#10 CSR（Certificate Signing Request）
3. 调用 /api/cert/bind 提交 CSR
4. 服务端用 SHA256 签名，返回 cert_user + cert_int + cert_root
```

证书文件结构：
```
certs/
├── index.json              证书索引
├── cert_user.json          用户证书（subject.callsign, uid, exp, publicKey, signature）
├── cert_devicekey.json     设备私钥（seed = 私钥种子）
├── cert_int.json           中级证书
└── cert_root.json          根证书
```

校验链：`cert_root` + `cert_int` → 验证 `cert_user` 签名 → `cert_devicekey` 私钥匹配

### 4. MQTT 认证（PKCS12 + PEM）

- KeyStore 格式：PKCS12
- 证书格式：PEM
- 私钥管理：`seed` 字段（51 处引用）
- SSL：`SSLSocketFactory` + `KeyManagerFactory` + `TrustManagerFactory`

## 项目结构

```
app/src/main/java/nrl/pulse/
├── fmo/     MainActivity + FmoProtocol + BeaconConfig + BroadcastConfig
├── net/     FmoMqtt + FmoApi + TlsUtils
├── cert/    CertManager（4 个 PEM + 校验 + 签发流程）
├── core/    NativeGuard（JNI → libnrlguard.so）
├── dmr/     OpenDmr（JNI → libopendmr.so，AMBE 编解码）
└── app/     GuardService + AppSettings + DmrCredential
```

## JNI 方法

| 库 | 方法 | 用途 |
|---|---|---|
| libnrlguard.so | `nativeVerify` | 完整性/证书校验 |
| libnrlguard.so | `nativeThreats` | 威胁检测 |
| libopendmr.so | `nativeDecode` | AMBE 解码 |
| libopendmr.so | `nativeEncode` | AMBE 编码 |
| libopendmr.so | `nativeReset` | 重置解码器 |
| libopendmr.so | `nativeResetEncoder` | 重置编码器 |

## 构建

```bash
cd fmo-app
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 6.9 MB）
```

## 状态

- ✅ MQTT v5 客户端（mTLS）编译通过
- ✅ APRS 帧格式生成/解析
- ✅ 证书管理（4 个 PEM + 校验）
- ✅ JNI 桥（6 个 native 方法）
- ✅ 12 个 .so 全部打入
- ✅ BUILD SUCCESSFUL

## 待补充（需要实测或更多逆向）

1. **AMBE 帧精确长度** —— `AMBE_FRAME_BYTES` 常量值
2. **APRS 位置帧完整字段顺序** —— 精确格式
3. **MQTT broker 认证细节** —— 客户端证书传递方式
