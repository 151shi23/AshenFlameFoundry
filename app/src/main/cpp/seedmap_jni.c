/*
 * 离线种子地图 · JNI 封装（cubiomes，MIT License，© 2020 Cubitect）
 *
 * 对外只暴露「够用就好」的几件事：
 *   nativeCreate / nativeFree          生成器生命周期
 *   nativeBiomeAt                      单点群系查询
 *   nativeMap                          一块区域的群系 id 表（Java 侧上色成图）
 *   nativeStructures                   结构定位（村庄 / 要塞以外的各类）
 *   nativeStrongholds                  要塞（含生物群系校验）
 *   nativeSpawn                        出生点（估算）
 *   nativeSlimeChunk                   史莱姆区块判定
 *
 * 说明：cubiomes 里 scale 表示「一个采样点 = scale 个方块」，
 * 因此传进来的 x/z/y 都是「方块坐标 / scale」，出参也是同一套坐标。
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "generator.h"
#include "biomes.h"
#include "finders.h"

/* 版本字符串 → cubiomes 版本枚举（避免 Java 侧硬编码枚举值） */
static int mcFromKey(const char *key)
{
    if (!key) return MC_1_21;
    if (strcmp(key, "1.21") == 0) return MC_1_21;
    if (strcmp(key, "1.20") == 0) return MC_1_20;
    if (strcmp(key, "1.19") == 0) return MC_1_19;
    if (strcmp(key, "1.18") == 0) return MC_1_18;
    if (strcmp(key, "1.17") == 0) return MC_1_17;
    if (strcmp(key, "1.16") == 0) return MC_1_16;
    if (strcmp(key, "1.15") == 0) return MC_1_15;
    if (strcmp(key, "1.14") == 0) return MC_1_14;
    if (strcmp(key, "1.13") == 0) return MC_1_13;
    if (strcmp(key, "1.12") == 0) return MC_1_12;
    if (strcmp(key, "1.11") == 0) return MC_1_11;
    if (strcmp(key, "1.10") == 0) return MC_1_10;
    if (strcmp(key, "1.9") == 0) return MC_1_9;
    if (strcmp(key, "1.8") == 0) return MC_1_8;
    if (strcmp(key, "1.7") == 0) return MC_1_7;
    return MC_1_21;
}

static Generator *genNew(const char *mcKey, int dim, uint64_t seed)
{
    Generator *g = (Generator *) calloc(1, sizeof(Generator));
    if (!g) return NULL;
    setupGenerator(g, mcFromKey(mcKey), 0);
    applySeed(g, dim, seed);
    return g;
}

/* --- 生命周期 ---------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_com_mineways_SeedMapNative_nativeCreate(JNIEnv *env, jclass cls,
        jstring mcKey, jint dim, jlong seed)
{
    (void) cls;
    const char *key = (*env)->GetStringUTFChars(env, mcKey, 0);
    Generator *g = genNew(key, (int) dim, (uint64_t) seed);
    if (key) (*env)->ReleaseStringUTFChars(env, mcKey, key);
    return (jlong) (intptr_t) g;
}

JNIEXPORT void JNICALL
Java_com_mineways_SeedMapNative_nativeFree(JNIEnv *env, jclass cls, jlong ptr)
{
    (void) env; (void) cls;
    if (ptr) free((void *) (intptr_t) ptr);
}

/* --- 单点群系 ---------------------------------------------------------- */

JNIEXPORT jint JNICALL
Java_com_mineways_SeedMapNative_nativeBiomeAt(JNIEnv *env, jclass cls,
        jlong ptr, jint scale, jint x, jint y, jint z)
{
    (void) env; (void) cls;
    Generator *g = (Generator *) (intptr_t) ptr;
    if (!g || scale <= 0) return -1;
    return (jint) getBiomeAt(g, (int) scale, (int) x, (int) y, (int) z);
}

/* --- 区域群系表（Java 侧上色）------------------------------------------ */

JNIEXPORT jintArray JNICALL
Java_com_mineways_SeedMapNative_nativeMap(JNIEnv *env, jclass cls, jlong ptr,
        jint scale, jint x0, jint z0, jint w, jint h, jint y)
{
    (void) cls;
    Generator *g = (Generator *) (intptr_t) ptr;
    if (!g || w <= 0 || h <= 0 || scale <= 0) return NULL;

    jint *buf = (jint *) malloc(sizeof(jint) * (size_t) w * (size_t) h);
    if (!buf) return NULL;

    for (int j = 0; j < h; j++) {
        for (int i = 0; i < w; i++) {
            buf[(size_t) j * w + i] = (jint) getBiomeAt(g, (int) scale,
                    (int) x0 + i, (int) y, (int) z0 + j);
        }
    }

    jintArray arr = (*env)->NewIntArray(env, (jsize) ((size_t) w * h));
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, (jsize) ((size_t) w * h), buf);
    free(buf);
    return arr;
}

/* --- 结构定位 ---------------------------------------------------------- */

JNIEXPORT jintArray JNICALL
Java_com_mineways_SeedMapNative_nativeStructures(JNIEnv *env, jclass cls, jlong ptr,
        jstring mcKey, jlong seed, jint dim, jint cx, jint cz, jint radius, jint maxOut)
{
    (void) cls; (void) dim;
    Generator *g0 = (Generator *) (intptr_t) ptr;
    if (!g0) return NULL;

    /* 结构类型（枚举值，与 finders.h 的 StructureType 一致，Java 侧同名解析） */
    static const int types[] = {
        Village, Desert_Pyramid, Jungle_Temple, Swamp_Hut, Igloo,
        Monument, Mansion, Outpost, Ruined_Portal,
        Ancient_City, Trail_Ruins, Trial_Chambers,
        Treasure, Ocean_Ruin, Fortress, Bastion, End_City
    };
    const int nTypes = (int) (sizeof(types) / sizeof(types[0]));

    const char *key = (*env)->GetStringUTFChars(env, mcKey, 0);
    int mc = mcFromKey(key);

    int cap = (maxOut > 0 && maxOut <= 2000) ? maxOut : 400;
    jint *out = (jint *) malloc(sizeof(jint) * 3 * (size_t) cap);
    if (!out) {
        if (key) (*env)->ReleaseStringUTFChars(env, mcKey, key);
        return NULL;
    }

    Generator *gc[3] = { NULL, NULL, NULL };
    gc[0] = g0;                       /* dim -1 → 索引 0，0 → 1，+1 → 2 */
    int n = 0;

    for (int t = 0; t < nTypes && n < cap; t++) {
        StructureConfig sc;
        if (!getStructureConfig(types[t], mc, &sc)) continue;   /* 该版本没有这种结构 */
        int di = sc.dim + 1;
        if (di < 0 || di > 2) continue;
        if (!gc[di]) gc[di] = genNew(key, sc.dim, (uint64_t) seed);
        Generator *g = gc[di];
        if (!g) continue;

        int rsBlocks = sc.regionSize * 16;
        if (rsBlocks < 16) rsBlocks = 16;

        int rx0 = (cx - radius) / rsBlocks - 1;
        int rx1 = (cx + radius) / rsBlocks + 1;
        int rz0 = (cz - radius) / rsBlocks - 1;
        int rz1 = (cz + radius) / rsBlocks + 1;

        for (int rz = rz0; rz <= rz1 && n < cap; rz++) {
            for (int rx = rx0; rx <= rx1 && n < cap; rx++) {
                Pos p;
                if (!getStructurePos(types[t], mc, (uint64_t) seed, rx, rz, &p)) continue;
                double dx = (double) p.x - cx, dz = (double) p.z - cz;
                if (dx * dx + dz * dz > (double) radius * radius) continue;
                if (!isViableStructurePos(types[t], g, p.x, p.z, 0)) continue;
                if (mc >= MC_1_18 && (types[t] == Desert_Pyramid
                        || types[t] == Jungle_Temple || types[t] == Mansion)) {
                    if (!isViableStructureTerrain(types[t], g, p.x, p.z)) continue;
                }
                out[3 * n] = types[t];
                out[3 * n + 1] = p.x;
                out[3 * n + 2] = p.z;
                n++;
            }
        }
    }

    for (int i = 1; i < 3; i++) {
        if (gc[i]) free(gc[i]);
    }

    jintArray arr = NULL;
    if (n > 0) {
        arr = (*env)->NewIntArray(env, n * 3);
        if (arr) (*env)->SetIntArrayRegion(env, arr, 0, n * 3, out);
    } else {
        arr = (*env)->NewIntArray(env, 0);
    }
    free(out);
    if (key) (*env)->ReleaseStringUTFChars(env, mcKey, key);
    return arr;
}

/* --- 要塞 -------------------------------------------------------------- */

JNIEXPORT jintArray JNICALL
Java_com_mineways_SeedMapNative_nativeStrongholds(JNIEnv *env, jclass cls, jlong ptr,
        jstring mcKey, jlong seed, jint cx, jint cz, jint radius, jint maxOut)
{
    (void) cls;
    Generator *g = (Generator *) (intptr_t) ptr;
    if (!g) return NULL;

    const char *key = (*env)->GetStringUTFChars(env, mcKey, 0);
    int mc = mcFromKey(key);

    StrongholdIter sh;
    Pos p = initFirstStronghold(&sh, mc, ((uint64_t) seed) & MASK48);

    int cap = (maxOut > 0 && maxOut <= 64) ? maxOut : 24;
    jint *out = (jint *) malloc(sizeof(jint) * 2 * (size_t) cap);
    if (!out) {
        if (key) (*env)->ReleaseStringUTFChars(env, mcKey, key);
        return NULL;
    }

    int n = 0;
    while (n < cap) {
        double dx = (double) p.x - cx, dz = (double) p.z - cz;
        if (dx * dx + dz * dz <= (double) radius * radius) {
            out[2 * n] = p.x;
            out[2 * n + 1] = p.z;
            n++;
        }
        if (nextStronghold(&sh, g) <= 0) break;
        p = sh.pos;
    }

    jintArray arr = (*env)->NewIntArray(env, n * 2);
    if (arr && n > 0) (*env)->SetIntArrayRegion(env, arr, 0, n * 2, out);
    free(out);
    if (key) (*env)->ReleaseStringUTFChars(env, mcKey, key);
    return arr;
}

/* --- 出生点（估算）----------------------------------------------------- */

JNIEXPORT jintArray JNICALL
Java_com_mineways_SeedMapNative_nativeSpawn(JNIEnv *env, jclass cls, jlong ptr)
{
    (void) cls;
    Generator *g = (Generator *) (intptr_t) ptr;
    if (!g) return NULL;
    Pos p = estimateSpawn(g, NULL);
    jint tmp[2];
    tmp[0] = p.x;
    tmp[1] = p.z;
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 2, tmp);
    return arr;
}

/* --- 史莱姆区块 -------------------------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_com_mineways_SeedMapNative_nativeSlimeChunk(JNIEnv *env, jclass cls,
        jlong seed, jint chunkX, jint chunkZ)
{
    (void) env; (void) cls;
    return isSlimeChunk((uint64_t) seed, (int) chunkX, (int) chunkZ) ? JNI_TRUE : JNI_FALSE;
}
