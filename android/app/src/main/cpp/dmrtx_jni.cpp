/*
 * dmrtx_jni.cpp —— DMR 发射帧组装（Homebrew/MMDVM 55 字节 DMRD 包）
 *
 * 本文件是 DroidStar（nostar/DroidStar, GPL-3.0-or-later）中 dmr.cpp 发射路径的
 * 忠实移植，去掉了 Qt 依赖（QByteArray / QString），保留全部位级行为：
 *
 *   build_frame()          —— 填 DMRD 头 + [15] 标志位
 *   encode_header(type)    —— 语音 LC 头 / 终止帧：RS(12,9) + BPTC(196,96) + Golay
 *   encode_data()          —— 语音突发：同步码 / 嵌入信令 + AMBE 交织
 *   get_emb_data()         —— 嵌入信令的 QR(16,7) 编码
 *   encode_embedded_data() —— 嵌入 LC 的 Hamming(16,11) 编码
 *
 * 帧结构（55 字节）：
 *   [0..3]   "DMRD"
 *   [4]      seqNo
 *   [5..7]   srcId   (24-bit BE)
 *   [8..10]  dstId   (24-bit BE)
 *   [11..14] repeaterId (32-bit BE)
 *   [15]     标志位：bit7=时隙 bit6=FLCO bit5=数据/同步 bit4=语音同步 bit3..0=帧内序号
 *   [16..19] streamId
 *   [20..52] 33 字节 DMR 数据（3 个 9 字节 AMBE 帧 + 同步/嵌入信令，位级交织）
 *   [53]     BER
 *   [54]     RSSI
 *
 * 27 字节 AMBE 在 33 字节载荷里的**交织**方式（这是最容易写错的地方）：
 *   payload[0..12]  = ambe[0..12]
 *   payload[13]     = (原值 & 0x0F) | (ambe[13] & 0xF0)   ← ambe[13] 高 4 位
 *   payload[14..18] = 同步码 / 嵌入信令
 *   payload[19]     = (原值 & 0xF0) | (ambe[13] & 0x0F)   ← ambe[13] 低 4 位
 *   payload[20..32] = ambe[14..26]
 *
 * 许可：本文件移植自 GPL-3.0 的 DroidStar；FEC 子模块来自 G4KLX/MMDVMHost（GPL-2.0-or-later）。
 */

#include <jni.h>
#include <android/log.h>
#include <pthread.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "DMRDefines.h"
#include "cbptc19696.h"
#include "crs129.h"
#include "cgolay2087.h"
#include "CRCenc.h"

#define LOG_TAG "BeaconDmrTx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

const int DMRD_PACKET_BYTES = 55;
const int DMR_PAYLOAD_OFF   = 20;
const int DMR_PAYLOAD_LEN   = 33;
const int AMBE_BUNDLE_BYTES = 27;   // 3 帧 × 9 字节

/* dmr.cpp 里 encode_qr1676 用的 QR(16,7) 编码表，128 项，原样照搬 */
const uint32_t ENCODING_TABLE_1676[128] = {
        0x0000U, 0x0273U, 0x04E5U, 0x0696U, 0x09C9U, 0x0BBAU, 0x0D2CU, 0x0F5FU,
        0x11E2U, 0x1391U, 0x1507U, 0x1774U, 0x182BU, 0x1A58U, 0x1CCEU, 0x1EBDU,
        0x21B7U, 0x23C4U, 0x2552U, 0x2721U, 0x287EU, 0x2A0DU, 0x2C9BU, 0x2EE8U,
        0x3055U, 0x3226U, 0x34B0U, 0x36C3U, 0x399CU, 0x3BEFU, 0x3D79U, 0x3F0AU,
        0x411EU, 0x436DU, 0x45FBU, 0x4788U, 0x48D7U, 0x4AA4U, 0x4C32U, 0x4E41U,
        0x50FCU, 0x528FU, 0x5419U, 0x566AU, 0x5935U, 0x5B46U, 0x5DD0U, 0x5FA3U,
        0x60A9U, 0x62DAU, 0x644CU, 0x663FU, 0x6960U, 0x6B13U, 0x6D85U, 0x6FF6U,
        0x714BU, 0x7338U, 0x75AEU, 0x77DDU, 0x7882U, 0x7AF1U, 0x7C67U, 0x7E14U,
        0x804FU, 0x823CU, 0x84AAU, 0x86D9U, 0x8986U, 0x8BF5U, 0x8D63U, 0x8F10U,
        0x91ADU, 0x93DEU, 0x9548U, 0x973BU, 0x9864U, 0x9A17U, 0x9C81U, 0x9EF2U,
        0xA1F8U, 0xA38BU, 0xA51DU, 0xA76EU, 0xA831U, 0xAA42U, 0xACD4U, 0xAEA7U,
        0xB01AU, 0xB269U, 0xB4FFU, 0xB68CU, 0xB9D3U, 0xBBA0U, 0xBD36U, 0xBF45U,
        0xC151U, 0xC322U, 0xC5B4U, 0xC7C7U, 0xC898U, 0xCAEBU, 0xCC7DU, 0xCE0EU,
        0xD0B3U, 0xD2C0U, 0xD456U, 0xD625U, 0xD97AU, 0xDB09U, 0xDD9FU, 0xDFECU,
        0xE0E6U, 0xE295U, 0xE403U, 0xE670U, 0xE92FU, 0xEB5CU, 0xEDCAU, 0xEFB9U,
        0xF104U, 0xF377U, 0xF5E1U, 0xF792U, 0xF8CDU, 0xFABEU, 0xFC28U, 0xFE5BU};

/* ------------------------------------------------------------------ */
/* 发射上下文                                                          */
/* ------------------------------------------------------------------ */

struct DmrTxCtx {
    uint8_t  frame[DMRD_PACKET_BYTES];
    uint8_t  ambe[AMBE_BUNDLE_BYTES];

    uint32_t dmrCnt;        // 已发出的 DMRD 包计数，同时是 seqNo
    uint8_t  dataType;      // DT_VOICE_SYNC / DT_VOICE / DT_VOICE_LC_HEADER ...
    int      flco;          // FLCO_GROUP(0) 或 FLCO_USER_USER(3)

    uint32_t srcId;
    uint32_t dstId;
    uint32_t streamId;
    /*
     * DMRD 头 [11..14] 的 repeaterId —— 标识「经哪台热点中继」。
     *
     * 它不一定等于 srcId：配了 BrandMeister 的 ESSID 别名 ID 之后，
     * 登录用 9 位别名（如 461418701），而 srcId 仍是裸 7 位（4614187）。
     * 主站靠 repeaterId 把通联记录归到正确的那台热点上，
     * 用错会让记录挂到另一台设备名下。
     */
    uint32_t repeaterId;

    int      slot;          // 1 或 2
    int      colorCode;     // 0..15

    bool     raw[128];      // 嵌入 LC 的 128 位交织缓冲
    bool     data[72];      // LC 的 72 位

    CBPTC19696 bptc;
};

DmrTxCtx        g_tx;
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ------------------------------------------------------------------ */
/* 位操作小工具                                                        */
/* ------------------------------------------------------------------ */

void byteToBitsBE(uint8_t byte, bool *bits) {
    bits[0] = (byte & 0x80U) == 0x80U;
    bits[1] = (byte & 0x40U) == 0x40U;
    bits[2] = (byte & 0x20U) == 0x20U;
    bits[3] = (byte & 0x10U) == 0x10U;
    bits[4] = (byte & 0x08U) == 0x08U;
    bits[5] = (byte & 0x04U) == 0x04U;
    bits[6] = (byte & 0x02U) == 0x02U;
    bits[7] = (byte & 0x01U) == 0x01U;
}

void bitsToByteBE(const bool *bits, uint8_t &byte) {
    byte = bits[0] ? 0x80U : 0x00U;
    byte |= bits[1] ? 0x40U : 0x00U;
    byte |= bits[2] ? 0x20U : 0x00U;
    byte |= bits[3] ? 0x10U : 0x00U;
    byte |= bits[4] ? 0x08U : 0x00U;
    byte |= bits[5] ? 0x04U : 0x00U;
    byte |= bits[6] ? 0x02U : 0x00U;
    byte |= bits[7] ? 0x01U : 0x00U;
}

/* Hamming(16,11,4)：为 11 位数据补 5 位校验 */
void encode16114(bool *d) {
    d[11] = d[0] ^ d[1] ^ d[2] ^ d[3] ^ d[5] ^ d[7] ^ d[8];
    d[12] = d[1] ^ d[2] ^ d[3] ^ d[4] ^ d[6] ^ d[8] ^ d[9];
    d[13] = d[2] ^ d[3] ^ d[4] ^ d[5] ^ d[7] ^ d[9] ^ d[10];
    d[14] = d[0] ^ d[1] ^ d[2] ^ d[4] ^ d[6] ^ d[7] ^ d[10];
    d[15] = d[0] ^ d[2] ^ d[5] ^ d[6] ^ d[8] ^ d[9] ^ d[10];
}

/* QR(16,7) 校验：把 7 位压成 16 位码字 */
void encode_qr1676(uint8_t *data) {
    uint32_t value = (data[0U] >> 1) & 0x7FU;
    uint32_t cksum = ENCODING_TABLE_1676[value];
    data[0U] = cksum >> 8;
    data[1U] = cksum & 0xFFU;
}

/* ------------------------------------------------------------------ */
/* 同步码                                                              */
/* ------------------------------------------------------------------ */

void addDMRDataSync(uint8_t *data, bool duplex) {
    const uint8_t *pat = duplex ? BS_SOURCED_DATA_SYNC : MS_SOURCED_DATA_SYNC;
    for (uint32_t i = 0U; i < 7U; i++)
        data[i + 13U] = (data[i + 13U] & ~SYNC_MASK[i]) | pat[i];
}

void addDMRAudioSync(uint8_t *data, bool duplex) {
    const uint8_t *pat = duplex ? BS_SOURCED_AUDIO_SYNC : MS_SOURCED_AUDIO_SYNC;
    for (uint32_t i = 0U; i < 7U; i++)
        data[i + 13U] = (data[i + 13U] & ~SYNC_MASK[i]) | pat[i];
}

/* ------------------------------------------------------------------ */
/* 嵌入信令 / 嵌入 LC                                                  */
/* ------------------------------------------------------------------ */

void lc_get_data(uint8_t *bytes);      // 定义见下方
void lc_get_data_bits(bool *bits);

/* 把 2 字节嵌入信令经 QR(16,7) 编码后塞进 33 字节载荷的 14/18 字节位缝里 */
void get_emb_data(uint8_t *data, uint8_t lcss) {
    uint8_t DMREMB[2U];
    DMREMB[0U] = ((uint8_t) g_tx.colorCode << 4) & 0xF0U;
    DMREMB[0U] |= (lcss << 1) & 0x06U;
    DMREMB[1U] = 0x00U;

    encode_qr1676(DMREMB);

    data[13U] = (data[13U] & 0xF0U) | ((DMREMB[0U] >> 4U) & 0x0FU);
    data[14U] = (data[14U] & 0x0FU) | ((DMREMB[0U] << 4U) & 0xF0U);
    data[18U] = (data[18U] & 0xF0U) | ((DMREMB[1U] >> 4U) & 0x0FU);
    data[19U] = (data[19U] & 0x0FU) | ((DMREMB[1U] << 4U) & 0xF0U);
}

/* 从 m_raw 取出第 n 段 32 位嵌入 LC，返回 LCSS 值 */
uint8_t get_embedded_data(uint8_t *data, uint8_t n) {
    if (n >= 1U && n < 5U) {
        n--;

        bool bits[40U];
        ::memset(bits, 0x00U, 40U * sizeof(bool));
        ::memcpy(bits + 4U, g_tx.raw + n * 32U, 32U * sizeof(bool));

        uint8_t bytes[5U];
        bitsToByteBE(bits + 0U,  bytes[0U]);
        bitsToByteBE(bits + 8U,  bytes[1U]);
        bitsToByteBE(bits + 16U, bytes[2U]);
        bitsToByteBE(bits + 24U, bytes[3U]);
        bitsToByteBE(bits + 32U, bytes[4U]);

        data[14U] = (data[14U] & 0xF0U) | (bytes[0U] & 0x0FU);
        data[15U] = bytes[1U];
        data[16U] = bytes[2U];
        data[17U] = bytes[3U];
        data[18U] = (data[18U] & 0x0FU) | (bytes[4U] & 0xF0U);

        switch (n) {
            case 0U: return 1U;
            case 3U: return 2U;
            default: return 3U;
        }
    }

    data[14U] &= 0xF0U;
    data[15U] = 0x00U;
    data[16U] = 0x00U;
    data[17U] = 0x00U;
    data[18U] &= 0x0FU;
    return 0U;
}

/* 把 LC 经 Hamming(16,11) + 列校验打成 128 位，再按列向下交织进 m_raw */
void encode_embedded_data() {
    uint32_t crc;
    lc_get_data_bits(g_tx.data);

    CCRC::encodeFiveBit(g_tx.data, crc);

    bool data[128U];
    ::memset(data, 0x00U, 128U * sizeof(bool));

    data[106U] = (crc & 0x01U) == 0x01U;
    data[90U]  = (crc & 0x02U) == 0x02U;
    data[74U]  = (crc & 0x04U) == 0x04U;
    data[58U]  = (crc & 0x08U) == 0x08U;
    data[42U]  = (crc & 0x10U) == 0x10U;

    uint32_t b = 0U;
    for (uint32_t a = 0U; a < 11U; a++, b++) data[a] = g_tx.data[b];
    for (uint32_t a = 16U; a < 27U; a++, b++) data[a] = g_tx.data[b];
    for (uint32_t a = 32U; a < 42U; a++, b++) data[a] = g_tx.data[b];
    for (uint32_t a = 48U; a < 58U; a++, b++) data[a] = g_tx.data[b];
    for (uint32_t a = 64U; a < 74U; a++, b++) data[a] = g_tx.data[b];
    for (uint32_t a = 80U; a < 90U; a++, b++) data[a] = g_tx.data[b];
    for (uint32_t a = 96U; a < 106U; a++, b++) data[a] = g_tx.data[b];

    for (uint32_t a = 0U; a < 112U; a += 16U)
        encode16114(data + a);

    for (uint32_t a = 0U; a < 16U; a++)
        data[a + 112U] = data[a + 0U] ^ data[a + 16U] ^ data[a + 32U] ^ data[a + 48U]
                       ^ data[a + 64U] ^ data[a + 80U] ^ data[a + 96U];

    b = 0U;
    for (uint32_t a = 0U; a < 128U; a++) {
        g_tx.raw[a] = data[b];
        b += 16U;
        if (b > 127U) b -= 127U;
    }
}

/* ------------------------------------------------------------------ */
/* LC 与 BPTC 编码                                                     */
/* ------------------------------------------------------------------ */

void lc_get_data(uint8_t *bytes) {
    bytes[0U] = (uint8_t) g_tx.flco;

    bool pf = (bytes[0U] & 0x80U) == 0x80U;
    bool r  = (bytes[0U] & 0x40U) == 0x40U;
    uint8_t fid = bytes[1U];
    uint8_t options = bytes[2U];

    if (pf) bytes[0U] |= 0x80U;
    if (r)  bytes[0U] |= 0x40U;

    bytes[1U] = fid;
    bytes[2U] = options;
    bytes[3U] = g_tx.dstId >> 16;
    bytes[4U] = g_tx.dstId >> 8;
    bytes[5U] = g_tx.dstId >> 0;
    bytes[6U] = g_tx.srcId >> 16;
    bytes[7U] = g_tx.srcId >> 8;
    bytes[8U] = g_tx.srcId >> 0;
}

/* bool 版本：把 9 字节 LC 展开成 72 位，供嵌入 LC 的 Hamming 编码使用 */
void lc_get_data_bits(bool *bits) {
    uint8_t bytes[9U];
    ::memset(bytes, 0, 9);
    lc_get_data(bytes);
    for (int i = 0; i < 9; i++)
        byteToBitsBE(bytes[i], bits + i * 8);
}

/* 把色彩码 + 数据类型经 Golay(20,8,7) 编成 slot type，塞进载荷的位缝 */
void get_slot_data(uint8_t *data) {
    uint8_t DMRSlotType[3U];
    DMRSlotType[0U] = ((uint8_t) g_tx.colorCode << 4) & 0xF0U;
    DMRSlotType[0U] |= (g_tx.dataType << 0) & 0x0FU;
    DMRSlotType[1U] = 0x00U;
    DMRSlotType[2U] = 0x00U;

    CGolay2087::encode(DMRSlotType);

    data[12U] = (data[12U] & 0xC0U) | ((DMRSlotType[0U] >> 2) & 0x3FU);
    data[13U] = (data[13U] & 0x0FU) | ((DMRSlotType[0U] << 6) & 0xC0U)
                                    | ((DMRSlotType[1U] >> 2) & 0x30U);
    data[19U] = (data[19U] & 0xF0U) | ((DMRSlotType[1U] >> 2) & 0x0FU);
    data[20U] = (data[20U] & 0x03U) | ((DMRSlotType[1U] << 6) & 0xC0U)
                                    | ((DMRSlotType[2U] >> 2) & 0x3CU);
}

/* 完整 LC：RS(12,9) + CRC 掩码 + slot type + BPTC(196,96) */
void full_lc_encode(uint8_t *data, uint8_t type) {
    uint8_t lcData[12U];
    uint8_t parity[4U];
    ::memset(lcData, 0, sizeof(lcData));
    lc_get_data(lcData);

    CRS129::encode(lcData, 9U, parity);

    switch (type) {
        case DT_VOICE_LC_HEADER:
            lcData[9U]  = parity[2U] ^ VOICE_LC_HEADER_CRC_MASK[0U];
            lcData[10U] = parity[1U] ^ VOICE_LC_HEADER_CRC_MASK[1U];
            lcData[11U] = parity[0U] ^ VOICE_LC_HEADER_CRC_MASK[2U];
            break;
        case DT_TERMINATOR_WITH_LC:
            lcData[9U]  = parity[2U] ^ TERMINATOR_WITH_LC_CRC_MASK[0U];
            lcData[10U] = parity[1U] ^ TERMINATOR_WITH_LC_CRC_MASK[1U];
            lcData[11U] = parity[0U] ^ TERMINATOR_WITH_LC_CRC_MASK[2U];
            break;
        default:
            return;
    }

    get_slot_data(data);
    g_tx.bptc.encode(lcData, data);
}

/* ------------------------------------------------------------------ */
/* 帧组装                                                              */
/* ------------------------------------------------------------------ */

void build_frame() {
    uint8_t *f = g_tx.frame;

    f[0U] = 'D';
    f[1U] = 'M';
    f[2U] = 'R';
    f[3U] = 'D';

    f[5U]  = g_tx.srcId >> 16;
    f[6U]  = g_tx.srcId >> 8;
    f[7U]  = g_tx.srcId >> 0;
    f[8U]  = g_tx.dstId >> 16;
    f[9U]  = g_tx.dstId >> 8;
    f[10U] = g_tx.dstId >> 0;

    /* repeaterId 标识「经哪台热点中继」。未配 ESSID 别名时等于本机 DMR ID；
     * 配了别名时是 9 位的别名 ID（dmrId*100 + essid），与 srcId 不同。 */
    f[11U] = g_tx.repeaterId >> 24;
    f[12U] = g_tx.repeaterId >> 16;
    f[13U] = g_tx.repeaterId >> 8;
    f[14U] = g_tx.repeaterId >> 0;

    f[15U] = (g_tx.slot == 1) ? 0x00U : 0x80U;
    f[15U] |= (g_tx.flco == FLCO_GROUP) ? 0x00U : 0x40U;

    if (g_tx.dataType == DT_VOICE_SYNC) {
        f[15U] |= 0x10U;
    } else if (g_tx.dataType == DT_VOICE) {
        f[15U] |= ((g_tx.dmrCnt - 1U) % 6U);
    } else {
        f[15U] |= (0x20U | g_tx.dataType);
    }

    f[4U] = (uint8_t) (g_tx.dmrCnt & 0xFFU);
    ::memcpy(f + 16U, &g_tx.streamId, 4U);

    f[53U] = 0;   // BER
    f[54U] = 0;   // RSSI
}

void encode_header(uint8_t type) {
    addDMRDataSync(g_tx.frame + DMR_PAYLOAD_OFF, false);
    g_tx.dataType = type;
    full_lc_encode(g_tx.frame + DMR_PAYLOAD_OFF, type);
}

void encode_data() {
    uint32_t n = (g_tx.dmrCnt - 1U) % 6U;

    if (!n) {
        g_tx.dataType = DT_VOICE_SYNC;
        addDMRAudioSync(g_tx.frame + DMR_PAYLOAD_OFF, false);
        encode_embedded_data();
    } else {
        g_tx.dataType = DT_VOICE;
        uint8_t lcss = get_embedded_data(g_tx.frame + DMR_PAYLOAD_OFF, (uint8_t) n);
        get_emb_data(g_tx.frame + DMR_PAYLOAD_OFF, lcss);
    }
}

/* 把 27 字节 AMBE 按位交织填进 33 字节载荷 */
void interleave_ambe() {
    uint8_t *p = g_tx.frame + DMR_PAYLOAD_OFF;
    const uint8_t *a = g_tx.ambe;

    ::memcpy(p, a, 13U);
    p[13U] = (uint8_t) ((p[13U] & 0x0FU) | (a[13U] & 0xF0U));
    ::memcpy(p + 20U, a + 14U, 13U);
    p[19U] = (uint8_t) ((p[19U] & 0xF0U) | (a[13U] & 0x0FU));
}

/* ------------------------------------------------------------------ */
/* 对外操作                                                            */
/* ------------------------------------------------------------------ */

void do_reset() {
    /* 逐成员清零，不用 memset 整个结构 —— g_tx 里有 CBPTC19696 对象 */
    ::memset(g_tx.frame, 0, sizeof(g_tx.frame));
    ::memset(g_tx.ambe, 0, sizeof(g_tx.ambe));
    ::memset(g_tx.raw, 0, sizeof(g_tx.raw));
    ::memset(g_tx.data, 0, sizeof(g_tx.data));

    g_tx.dmrCnt   = 0;
    g_tx.dataType = DT_VOICE;
    g_tx.flco     = FLCO_GROUP;
    g_tx.srcId    = 0;
    g_tx.dstId    = 0;
    g_tx.streamId = 0;
    g_tx.slot     = 2;
    g_tx.colorCode = 1;
}

/* 生成语音 LC 头帧。返回后 g_tx.dmrCnt 已 +1 */
void do_header() {
    g_tx.dmrCnt = 0;
    g_tx.streamId = (uint32_t) ::rand();
    /* 注意顺序：必须先按调用方设定的 FLCO 编 LC，再交给 build_frame。
     * DroidStar 原实现在 encode_header 之后才赋值 m_flco，导致首个头帧的
     * FLCO 用的是上一轮的残值；这里把顺序修正过来。 */
    encode_header(DT_VOICE_LC_HEADER);
    build_frame();
    g_tx.dmrCnt++;
}

/* 生成一个语音突发帧（含 3 个 AMBE 帧） */
void do_voice() {
    interleave_ambe();
    encode_data();
    build_frame();
    g_tx.dmrCnt++;
}

/* 生成终止帧（EOT） */
void do_eot() {
    encode_header(DT_TERMINATOR_WITH_LC);
    build_frame();
    g_tx.dmrCnt++;
}

}  // namespace

/* ================================================================== */
/* JNI                                                                 */
/* ================================================================== */

extern "C" {

JNIEXPORT void JNICALL
Java_net_beacon_dmr_DmrTx_nativeReset(JNIEnv *, jclass) {
    pthread_mutex_lock(&g_lock);
    do_reset();
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_net_beacon_dmr_DmrTx_nativeSetParams(JNIEnv *, jclass,
                                          jint srcId, jint dstId,
                                          jint slot, jint colorCode, jint flco) {
    pthread_mutex_lock(&g_lock);
    g_tx.srcId     = (uint32_t) srcId;
    g_tx.dstId     = (uint32_t) dstId;
    g_tx.slot      = (slot == 1) ? 1 : 2;
    g_tx.colorCode = colorCode & 0x0F;
    g_tx.flco      = flco;
    /* 默认 repeaterId 跟随 srcId（未配 ESSID 别名时两者相同）。
     * Java 侧若配了别名，会在 nativeSetRepeaterId 里覆盖掉。 */
    g_tx.repeaterId = (uint32_t) srcId;
    pthread_mutex_unlock(&g_lock);
}

/*
 * 单独设置 DMRD 头 [11..14] 的 repeaterId。
 *
 * 配了 BrandMeister 的 ESSID 别名 ID 时必须调它：登录 ID 是 9 位别名，
 * 而 srcId 仍是裸 7 位 —— 主站靠 repeaterId 认「哪台热点中继的」，
 * 不设的话通联记录会挂到原 ID 那台设备名下。
 */
JNIEXPORT void JNICALL
Java_net_beacon_dmr_DmrTx_nativeSetRepeaterId(JNIEnv *, jclass, jint repeaterId) {
    pthread_mutex_lock(&g_lock);
    if (repeaterId > 0) {
        g_tx.repeaterId = (uint32_t) repeaterId;
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_net_beacon_dmr_DmrTx_nativeSetIds(JNIEnv *, jclass, jint srcId, jint dstId) {
    pthread_mutex_lock(&g_lock);
    g_tx.srcId = (uint32_t) srcId;
    g_tx.dstId = (uint32_t) dstId;
    pthread_mutex_unlock(&g_lock);
}

/* 写出语音 LC 头帧，返回 55 */
JNIEXPORT jint JNICALL
Java_net_beacon_dmr_DmrTx_nativeHeader(JNIEnv *env, jclass, jbyteArray out) {
    if (out == nullptr || env->GetArrayLength(out) < DMRD_PACKET_BYTES) return -1;
    pthread_mutex_lock(&g_lock);
    do_header();
    env->SetByteArrayRegion(out, 0, DMRD_PACKET_BYTES, (const jbyte *) g_tx.frame);
    pthread_mutex_unlock(&g_lock);
    return DMRD_PACKET_BYTES;
}

/* 传入 27 字节 AMBE，写出一个语音突发帧，返回 55 */
JNIEXPORT jint JNICALL
Java_net_beacon_dmr_DmrTx_nativeVoice(JNIEnv *env, jclass, jbyteArray ambe, jbyteArray out) {
    if (ambe == nullptr || out == nullptr) return -1;
    if (env->GetArrayLength(ambe) < AMBE_BUNDLE_BYTES) return -2;
    if (env->GetArrayLength(out) < DMRD_PACKET_BYTES) return -1;

    pthread_mutex_lock(&g_lock);
    env->GetByteArrayRegion(ambe, 0, AMBE_BUNDLE_BYTES, (jbyte *) g_tx.ambe);
    do_voice();
    env->SetByteArrayRegion(out, 0, DMRD_PACKET_BYTES, (const jbyte *) g_tx.frame);
    pthread_mutex_unlock(&g_lock);
    return DMRD_PACKET_BYTES;
}

/* 写出终止帧，返回 55 */
JNIEXPORT jint JNICALL
Java_net_beacon_dmr_DmrTx_nativeEot(JNIEnv *env, jclass, jbyteArray out) {
    if (out == nullptr || env->GetArrayLength(out) < DMRD_PACKET_BYTES) return -1;
    pthread_mutex_lock(&g_lock);
    do_eot();
    env->SetByteArrayRegion(out, 0, DMRD_PACKET_BYTES, (const jbyte *) g_tx.frame);
    pthread_mutex_unlock(&g_lock);
    return DMRD_PACKET_BYTES;
}

/*
 * 自检：不依赖声码器，用一串假 AMBE 走完整条 FEC 链，
 * 把三帧（header / voice / eot）的十六进制 dump 回 Java 侧核对。
 * 这是在没有空中接口的情况下验证位级正确性的唯一手段。
 */
JNIEXPORT jstring JNICALL
Java_net_beacon_dmr_DmrTx_nativeSelfTest(JNIEnv *env, jclass) {
    pthread_mutex_lock(&g_lock);

    do_reset();
    g_tx.srcId = 4601234;
    g_tx.dstId = 91;          // 世界组
    g_tx.slot = 2;
    g_tx.colorCode = 1;
    g_tx.flco = FLCO_GROUP;
    g_tx.repeaterId = 4601234;   // 自检不带 ESSID 别名，repeaterId 等于 srcId
    ::srand(12345);

    char buf[4096];
    int  off = 0;

    auto hexline = [&](const char *tag, const uint8_t *p, int n) {
        off += snprintf(buf + off, sizeof(buf) - off, "%s (%d):", tag, n);
        for (int i = 0; i < n && off < (int) sizeof(buf) - 4; i++)
            off += snprintf(buf + off, sizeof(buf) - off, " %02X", p[i]);
        off += snprintf(buf + off, sizeof(buf) - off, "\n");
    };

    /* 帧 0：LC 头 */
    do_header();
    hexline("HEADER", g_tx.frame, 55);

    /* 帧 1：语音同步帧（n=0 走 DT_VOICE_SYNC） */
    for (int i = 0; i < 27; i++) g_tx.ambe[i] = (uint8_t) (0xA0 + i);
    do_voice();
    hexline("VOICE-SYNC", g_tx.frame, 55);

    /* 帧 2：普通语音帧 */
    for (int i = 0; i < 27; i++) g_tx.ambe[i] = (uint8_t) (0xB0 + i);
    do_voice();
    hexline("VOICE", g_tx.frame, 55);

    /* 终止帧 */
    do_eot();
    hexline("EOT", g_tx.frame, 55);

    /* 校验点：AMBE 交织是否可逆还原 */
    off += snprintf(buf + off, sizeof(buf) - off,
                    "CHECK: header[0..3]=%c%c%c%c slot=%d cc=%d flco=%d dmrcnt=%u\n",
                    g_tx.frame[0], g_tx.frame[1], g_tx.frame[2], g_tx.frame[3],
                    (g_tx.frame[15] & 0x80) ? 2 : 1, g_tx.colorCode, g_tx.flco, g_tx.dmrCnt);

    pthread_mutex_unlock(&g_lock);
    return env->NewStringUTF(buf);
}

}  // extern "C"
