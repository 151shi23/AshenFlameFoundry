cmake_minimum_required(VERSION 3.22.1)
project(mineways_core C CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 桌面源码把 and/or/not 等当作普通标识符（MSVC 下非关键字）；clang 需关闭替代运算符关键字
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-operator-names")

# 屏蔽大量历史告警，聚焦功能性编译通过
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -w")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -w")

# Android 15+ 16KB page-size 设备要求 native 库按 16KB 对齐（LOAD 段 p_align=0x4000）。
# Google 的要求覆盖 arm64-v8a 与 x86_64；armeabi-v7a 仍按 4KB 常规对齐（32 位设备无 16KB 页要求）。
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
endif()

add_library(mineways_core SHARED
    mineways_jni.cpp
    jni_export.cpp
    export_diag.cpp
    android_port.cpp
    core/region.cpp
    core/nbt.cpp
    core/blockInfo.cpp
    core/biomes.cpp
    core/cache.cpp
    core/lodepng.cpp
    core/rwpng.cpp
    core/terrainExtData.cpp
    core/culling.cpp
    core/ObjFileManip.cpp
    core/mw_loadblock.cpp
    core/export_setup.cpp
)

target_include_directories(mineways_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/core
)

# NDK 自带 zlib
find_library(ZLIB_LIB z)
target_link_libraries(mineways_core PRIVATE ${ZLIB_LIB} log)

# ---------------------------------------------------------------------------
# 离线种子地图内核：cubiomes（MIT License, © 2020 Cubitect）
#   源码并入 app/src/main/cpp/cubiomes/（原始文件未改），JNI 封装见 seedmap_jni.c
#   用途：种子 → 群系地图 / 结构定位 / 要塞 / 出生点 / 史莱姆区块，全部离线计算
# ---------------------------------------------------------------------------
add_library(seedmap SHARED
    seedmap_jni.c
    cubiomes/biomes.c
    cubiomes/biomenoise.c
    cubiomes/finders.c
    cubiomes/generator.c
    cubiomes/layers.c
    cubiomes/noise.c
    cubiomes/quadbase.c
    cubiomes/util.c
)

target_include_directories(seedmap PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/cubiomes)
target_link_libraries(seedmap PRIVATE log m)

# ---------------------------------------------------------------------------
# 格式转换内核 fmtconv（新工具「格式转换」用）
#   模型：Assimp（BSD-3-Clause）—— FetchContent 拉取；只编需要的导入/导出器控体积
#   .blend：zstd（BSD-3-Clause）解压 + NDK 自带 zlib（兼容老版 gzip 压缩）
#   图片：stb_image / stb_image_write（公有领域，已并入 third_party/stb）
# ---------------------------------------------------------------------------
include(FetchContent)

# Assimp 用【静态库】链进 fmtconv.so：这样只出一个 .so、打包时能被 strip、体积可控
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# 导入/导出器裁剪：只留常用互换格式。
# ⚠ MD2 与 MD3 必须成对保留 —— Assimp 6.0.5 的 MD3Loader 会调用 MD2::LookupNormalIndex，
#   只留 MD3 会链接期 undefined symbol（踩过）。
set(ASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ALL_EXPORTERS_BY_DEFAULT OFF CACHE BOOL "" FORCE)
foreach(_imp BLEND OBJ GLTF GLTF2 FBX STL PLY DAE 3DS MD2 MD3)
    set(ASSIMP_BUILD_${_imp}_IMPORTER ON CACHE BOOL "" FORCE)
endforeach()
foreach(_exp OBJ GLTF GLTF2 STL PLY DAE 3DS FBX ASSBIN ASSXML)
    set(ASSIMP_BUILD_${_exp}_EXPORTER ON CACHE BOOL "" FORCE)
endforeach()

set(ASSIMP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ASSIMP_TOOLS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(ASSIMP_INSTALL OFF CACHE BOOL "" FORCE)
set(ASSIMP_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ZLIB ON CACHE BOOL "" FORCE)
FetchContent_Declare(assimp
    GIT_REPOSITORY https://github.com/assimp/assimp.git
    GIT_TAG v6.0.5
    GIT_SHALLOW TRUE)

set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(zstd
    GIT_REPOSITORY https://github.com/facebook/zstd.git
    GIT_TAG v1.5.7
    GIT_SHALLOW TRUE
    SOURCE_SUBDIR build/cmake)

FetchContent_MakeAvailable(assimp zstd)

add_library(fmtconv SHARED
    fmtconv/fmt_jni.cpp
    fmtconv/fmt_core.cpp)

target_include_directories(fmtconv PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/stb)

target_compile_features(fmtconv PRIVATE cxx_std_17)

# 16KB 对齐（Android 15+ 要求，与 mineways_core 保持一致）
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    target_link_options(fmtconv PRIVATE
        "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endif()

target_link_libraries(fmtconv PRIVATE assimp::assimp libzstd_static z log m)
