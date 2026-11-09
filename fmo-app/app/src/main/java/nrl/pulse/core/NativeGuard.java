package nrl.pulse.core;

/**
 * NativeGuard —— JNI 桥 → libnrlguard.so（934 KB, arm64-v8a）。
 *
 * 从 .dynsym 里提取的 JNI 方法：
 *   Java_com_nrl_pulse_core_NativeGuard_nativeVerify    完整性/证书校验
 *   Java_com_nrl_pulse_core_NativeGuard_nativeThreats   威胁检测（反调试/反篡改）
 */
public class NativeGuard {

    static {
        try { System.loadLibrary("nrlguard"); }
        catch (UnsatisfiedLinkError e) { /* 开发阶段允许没有 .so */ }
    }

    /** 完整性/证书校验 */
    public static native boolean nativeVerify();

    /** 威胁检测（反调试、反篡改、模拟器检测） */
    public static native int nativeThreats();
}
