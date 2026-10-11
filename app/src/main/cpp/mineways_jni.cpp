/*
  M1 最小 JNI 层：加载并调用复用的 C++ 核心。
    - coreInfo()   : 返回核心构建信息（证明 .so 已链接核心文件）
    - probeWorld() : 打开 world/ 目录下的 level.dat，解析世界名、数据版本与出生点
                     （顺带端到端验证 region->nbt->zlib 读取链已通）
*/
#include <jni.h>
#include <string>
#include <cstdio>
#include "stdafx.h"

static std::string jstringToUtf8(JNIEnv* env, jstring js) {
    if (!js) return std::string();
    const char* raw = env->GetStringUTFChars(js, nullptr);
    std::string out = raw ? raw : "";
    env->ReleaseStringUTFChars(js, raw);
    return out;
}

// 符号名按「声明所在类」命名：native 声明在 com.mineways.ExportActivity（以前写成 MainActivity → 运行时找不到）
extern "C" JNIEXPORT jstring JNICALL
Java_com_mineways_ExportActivity_coreInfo(JNIEnv* env, jobject) {
    char buf[256];
    snprintf(buf, sizeof(buf),
             "Mineways C++ core OK\n"
             "NUM_BLOCKS=%d\n"
             "NUM_TRANS=%d\n"
             "MAX_WORLD_HEIGHT=%d",
             NUM_BLOCKS_DEFINED, NUM_TRANS, MAX_WORLD_HEIGHT(4099, 19));
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mineways_ExportActivity_probeWorld(JNIEnv* env, jobject, jstring jdir) {
    std::string dir = jstringToUtf8(env, jdir);

    // 桌面核心文件接口是宽字符；把 UTF-8 目录转成 UTF-32(wchar_t) 再拼 level.dat
    wchar_t levelFile[MAX_PATH_AND_FILE] = {0};
    androidMultiByteToWideChar(CP_UTF8, 0, dir.c_str(), (int)dir.size(),
                               levelFile, MAX_PATH_AND_FILE);
    if (wcslen(levelFile) == 0) return env->NewStringUTF("ERR:empty world dir");
    if (levelFile[wcslen(levelFile) - 1] != L'/') wcscat_s(levelFile, MAX_PATH_AND_FILE, L"/");
    wcscat_s(levelFile, MAX_PATH_AND_FILE, L"level.dat");

    int fopenErr = 0;
    bfFile bf = newNBT(levelFile, &fopenErr);

    char out[512];
    if (bf.fptr == NULL || fopenErr != 0) {
        snprintf(out, sizeof(out), "ERR:open level.dat failed (not a world dir?) err=%d", fopenErr);
        return env->NewStringUTF(out);
    }

    int version = 0;
    char name[256] = {0};
    int sx = 0, sy = 0, sz = 0;
    int rv = nbtGetFileVersion(&bf, &version);
    nbtGetLevelName(&bf, name, sizeof(name));
    nbtGetSpawn(&bf, &sx, &sy, &sz);
    nbtClose(&bf);

    if (rv < 0) {
        snprintf(out, sizeof(out), "ERR:nbt version parse rc=%d", rv);
    } else {
        snprintf(out, sizeof(out), "name=%s | dataVersion=%d | spawn=%d,%d,%d",
                 name, version, sx, sy, sz);
    }
    return env->NewStringUTF(out);
}