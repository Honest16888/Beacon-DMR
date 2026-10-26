cmake_minimum_required(VERSION 3.22.1)
project(beaconvoc CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# 只构建与预编译声码器插件匹配的 ABI。
# jniLibs/ 下只有 arm64-v8a 与 armeabi-v7a 两份 libdsvocoder.so，
# 多编一个 ABI 只会白白增大 APK。
# ---------------------------------------------------------------------------

add_library(beaconvoc SHARED
        vocoder_jni.cpp                 # 声码器插件加载 + 编解码 + 自检
        dmrtx_jni.cpp                   # DMR 发射帧组装（LC + BPTC + RS129 + Golay）
        fec/crs129.cpp                  # RS(12,9) 里德-所罗门
        fec/cbptc19696.cpp              # BPTC(196,96) 块分组码
        fec/cgolay2087.cpp              # Golay(20,8,7)
        fec/chamming.cpp                # CACH 的 Hamming(17,12,5)
        fec/CRCenc.cpp                  # CRC / Hamming(16,11)
)

target_include_directories(beaconvoc PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/fec
)

find_library(log-lib log)

# dl 用于 dlopen 声码器插件。
# 注意：libdsvocoder.so 是 dlopen 动态加载的，**不要**在这里 target_link_libraries，
# 否则链接期就会去解析它的符号，与运行时 dlopen 的意图冲突。
target_link_libraries(beaconvoc PRIVATE ${log-lib} dl)

target_compile_options(beaconvoc PRIVATE
        -O2
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -Wall
        -Wno-unused-parameter
        -Wno-unused-variable
)

# 16 KB 页对齐：Android 15+ 在部分设备上要求 16 KB 内存页，
# 不满足会导致安装/加载失败。
target_link_options(beaconvoc PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
)

# ---------------------------------------------------------------------------
# 关键：与插件共享同一个 libc++_shared.so
#
# 插件 DT_NEEDED 里写着 libc++_shared.so（NDK r21 时代的 clang 9 产物）。
# 实测它的 C++ 未定义符号只有 4 个：operator new / operator delete /
# 两个 RTTI vtable —— 零个 std:: 符号，所以新旧 libc++ 都能满足，
# 但必须让两边**加载同一份** libc++，否则会出现两套 operator new
# 与两套 type_info，跨边界传对象会直接崩。
# ---------------------------------------------------------------------------
set(ANDROID_STL c++_shared)
