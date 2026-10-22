/* packcore.c — 材质包转换像素核心（JNI 实现）
 * 算法 A: flipbook 大图帧索引重排 → Java 竖条
 * 算法 B: 像素 diff 统计（往返验证）
 * 编译(宿主实测): gcc -O2 -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/linux packcore.c -o libpackcore.so
 * 编译(NDK): 同目录 CMakeLists.txt，直挂 MinewaysMobile 工程
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jintArray JNICALL
Java_com_mineways_pack_PackCore_flipbookToStrip(JNIEnv *env, jclass clazz,
                              jintArray jArgb, jint imgW, jint imgH,
                              jint tileW, jint tileH, jintArray jFrames)
{
    const jint tileCount = jFrames ? (jint)(imgH / tileH) : (jint)(imgH / tileH);
    (void)tileCount;

    /* 取大图像素 */
    const jsize srcLen = (*env)->GetArrayLength(env, jArgb);
    jint *src = (jint *)malloc((size_t)srcLen * sizeof(jint));
    if (!src) return NULL;
    (*env)->GetIntArrayRegion(env, jArgb, 0, srcLen, src);

    /* 帧序列: 显式 frames 或全帧 */
    jint nFrames = 0;
    jint *frames = NULL;
    if (jFrames) {
        nFrames = (*env)->GetArrayLength(env, jFrames);
        frames = (jint *)malloc((size_t)nFrames * sizeof(jint));
        if (!frames) { free(src); return NULL; }
        (*env)->GetIntArrayRegion(env, jFrames, 0, nFrames, frames);
    } else {
        nFrames = imgH / tileH;
        frames = (jint *)malloc((size_t)nFrames * sizeof(jint));
        if (!frames) { free(src); return NULL; }
        for (jint i = 0; i < nFrames; i++) frames[i] = i;
    }

    /* 输出竖条: tileW x (tileH*nFrames) */
    const jint outLen = tileW * tileH * nFrames;
    jint *out = (jint *)malloc((size_t)outLen * sizeof(jint));
    if (!out) { free(src); free(frames); return NULL; }

    /* 核心: 帧索引 → 行区间拷贝。源帧 f 起始行 = f*tileH；目标帧 i 起始行 = i*tileH */
    for (jint i = 0; i < nFrames; i++) {
        const jint srcRow = frames[i] * tileH;   /* 源帧首行 */
        const jint dstRow = i * tileH;           /* 目标帧首行 */
        for (jint y = 0; y < tileH; y++) {
            const jint *srow = src + (size_t)(srcRow + y) * imgW;
            jint *drow = out + (size_t)(dstRow + y) * tileW;
            memcpy(drow, srow, (size_t)tileW * sizeof(jint));
        }
    }

    jintArray jOut = (*env)->NewIntArray(env, outLen);
    if (jOut) (*env)->SetIntArrayRegion(env, jOut, 0, outLen, out);
    free(src); free(frames); free(out);
    return jOut;
}

JNIEXPORT jlong JNICALL
Java_com_mineways_pack_PackCore_pixelDiff(JNIEnv *env, jclass clazz, jintArray ja, jintArray jb)
{
    const jsize n = (*env)->GetArrayLength(env, ja);
    if (n != (*env)->GetArrayLength(env, jb)) return -1;
    jint *a = (jint *)malloc((size_t)n * sizeof(jint));
    jint *b = (jint *)malloc((size_t)n * sizeof(jint));
    if (!a || !b) { free(a); free(b); return -2; }
    (*env)->GetIntArrayRegion(env, ja, 0, n, a);
    (*env)->GetIntArrayRegion(env, jb, 0, n, b);
    jlong diff = 0;
    for (jsize i = 0; i < n; i++) if (a[i] != b[i]) diff++;
    free(a); free(b);
    return diff;
}

/* ==================== 字符串算子（核心库 C 化扩展） ==================== */

/* 目录映射表：NULL = Java 无对应（降级） */
static const struct { const char *bedrock; const char *java; } kDirMap[] = {
    {"blocks", "block"}, {"items", "item"}, {"entity", "entity"},
    {"environment", "environment"}, {"gui", "gui"}, {"particle", "particle"},
    {"particles", "particle"}, {"painting", "painting"}, {"map", "map"},
    {"colormap", "colormap"}, {"misc", "misc"}, {"font", "font"},
    {"trims", "trims"}, {"ui", NULL}, {"models", NULL},
};
#define kDirMapN (sizeof(kDirMap) / sizeof(kDirMap[0]))

static void json_escape_into_n(const char *s, size_t len, char *out, size_t outCap) {
    size_t o = 0;
    for (size_t i = 0; i < len && o + 8 < outCap; i++) {
        unsigned char c = (unsigned char)s[i];
        switch (c) {
            case '"':  out[o++] = '\\'; out[o++] = '"';  break;
            case '\\': out[o++] = '\\'; out[o++] = '\\'; break;
            case '\n': out[o++] = '\\'; out[o++] = 'n';  break;
            case '\r': out[o++] = '\\'; out[o++] = 'r';  break;
            case '\t': out[o++] = '\\'; out[o++] = 't';  break;
            default:   out[o++] = (char)c;
        }
    }
    out[o] = 0;
}

static void json_escape_into(const char *s, char *out, size_t outCap) {
    json_escape_into_n(s, strlen(s), out, outCap);
}

/* 算子 1：贴图路径映射 textures/blocks/x.png → assets/minecraft/textures/block/x.png；降级返回 NULL(空串) */
JNIEXPORT jstring JNICALL
Java_com_mineways_pack_PackCore_mapTexturePath(JNIEnv *env, jclass clazz, jstring jRel)
{
    if (!jRel) return NULL;
    const char *rel = (*env)->GetStringUTFChars(env, jRel, NULL);
    if (!rel) return NULL;

    /* 解析 textures/<dir>/<rest> */
    char out[1024]; out[0] = 0;
    if (strncmp(rel, "textures/", 9) == 0) {
        const char *dir = rel + 9;
        const char *slash = strchr(dir, '/');
        if (slash) {
            size_t dlen = (size_t)(slash - dir);
            const char *javaDir = NULL;
            for (size_t i = 0; i < kDirMapN; i++) {
                if (strlen(kDirMap[i].bedrock) == dlen &&
                    strncmp(kDirMap[i].bedrock, dir, dlen) == 0) {
                    javaDir = kDirMap[i].java;   /* NULL = 降级 */
                    break;
                }
            }
            if (javaDir) {
                snprintf(out, sizeof(out), "assets/minecraft/textures/%s%s", javaDir, slash);
            }
        }
    }
    (*env)->ReleaseStringUTFChars(env, jRel, rel);
    return (*env)->NewStringUTF(env, out);   /* 降级时为空串 */
}

/* 算子 2：命名规范化 token 重排（planks_oak → oak_planks 同一规范形） */
JNIEXPORT jstring JNICALL
Java_com_mineways_pack_PackCore_canonicalName(JNIEnv *env, jclass clazz, jstring jName)
{
    if (!jName) return NULL;
    const char *name = (*env)->GetStringUTFChars(env, jName, NULL);
    if (!name) return NULL;

    char buf[512]; strncpy(buf, name, sizeof(buf) - 1); buf[sizeof(buf) - 1] = 0;
    (*env)->ReleaseStringUTFChars(env, jName, name);

    /* 分 token（下划线分割，最多 64 段） */
    char *tokens[64]; int n = 0;
    char *p = buf;
    while (n < 64) {
        tokens[n++] = p;
        char *u = strchr(p, '_');
        if (!u) break;
        *u = 0; p = u + 1;
    }
    /* 插入排序（token 少，稳定且简单） */
    for (int i = 1; i < n; i++) {
        char *key = tokens[i];
        int j = i - 1;
        while (j >= 0 && strcmp(tokens[j], key) > 0) { tokens[j + 1] = tokens[j]; j--; }
        tokens[j + 1] = key;
    }
    /* join('_') */
    char out[1024]; size_t o = 0;
    for (int i = 0; i < n; i++) {
        size_t l = strlen(tokens[i]);
        if (o + l + 2 < sizeof(out)) {
            if (i) out[o++] = '_';
            memcpy(out + o, tokens[i], l); o += l;
        }
    }
    out[o] = 0;
    return (*env)->NewStringUTF(env, out);
}

/* 算子 3：.lang → JSON（行解析 key=value + JSON 转义，跳过空行/注释） */
JNIEXPORT jstring JNICALL
Java_com_mineways_pack_PackCore_langToJson(JNIEnv *env, jclass clazz, jstring jLang)
{
    if (!jLang) return NULL;
    const char *lang = (*env)->GetStringUTFChars(env, jLang, NULL);
    if (!lang) return NULL;
    const size_t cap = strlen(lang) * 4 + 64;
    char *out = (char *)malloc(cap);
    if (!out) { (*env)->ReleaseStringUTFChars(env, jLang, lang); return NULL; }
    size_t o = 0; out[o++] = '{';
    const char *p = lang;
    int first = 1;
    while (*p) {
        const char *eol = strchr(p, '\n');
        size_t lineLen = eol ? (size_t)(eol - p) : strlen(p);
        /* 跳过 \r */
        while (lineLen > 0 && (p[lineLen - 1] == '\r' || p[lineLen - 1] == ' ')) lineLen--;
        /* 前导空白 */
        size_t s = 0;
        while (s < lineLen && (p[s] == ' ' || p[s] == '\t')) s++;
        if (lineLen > s && p[s] != '#') {
            const char *eq = memchr(p + s, '=', lineLen - s);
            if (eq) {
                size_t klen = (size_t)(eq - (p + s));
                while (klen > 0 && (p[s + klen - 1] == ' ' || p[s + klen - 1] == '\t')) klen--;
                if (klen > 0) {
                    char kesc[1024], vesc[4096];
                    size_t vlen = lineLen - s - klen - 1;
                    char *vtmp = (char *)malloc(vlen + 1);
                    if (vtmp) {
                        memcpy(vtmp, eq + 1, vlen); vtmp[vlen] = 0;
                        /* value 首尾 trim（与 Java 版 trim() 语义对齐） */
                        char *vb = vtmp; char *ve = vtmp + vlen;
                        while (*vb == ' ' || *vb == '\t' || *vb == '\r') vb++;
                        while (ve > vb && (ve[-1] == ' ' || ve[-1] == '\t' || ve[-1] == '\r')) ve--;
                        *ve = 0; vlen = (size_t)(ve - vb);
                        memmove(vtmp, vb, vlen + 1);
                        json_escape_into_n(p + s, klen, kesc, sizeof(kesc));
                        json_escape_into(vtmp, vesc, sizeof(vesc));
                        int w = snprintf(out + o, cap - o, "%s\"%s\":\"%s\"",
                                         first ? "" : ",", kesc, vesc);
                        if (w > 0 && (size_t)w < cap - o) o += (size_t)w;
                        first = 0;
                        free(vtmp);
                    }
                }
            }
        }
        if (!eol) break;
        p = eol + 1;
    }
    (*env)->ReleaseStringUTFChars(env, jLang, lang);
    if (o < cap) out[o++] = '}'; else out[cap - 2] = '}'; out[o] = 0;
    jstring jr = (*env)->NewStringUTF(env, out);
    free(out);
    return jr;
}

/* 算子 4：pack.mcmeta 生成 */
JNIEXPORT jstring JNICALL
Java_com_mineways_pack_PackCore_buildPackMcmeta(JNIEnv *env, jclass clazz, jstring jDesc, jint fmt)
{
    if (!jDesc) return NULL;
    const char *desc = (*env)->GetStringUTFChars(env, jDesc, NULL);
    if (!desc) return NULL;
    char esc[1024]; json_escape_into(desc, esc, sizeof(esc));
    (*env)->ReleaseStringUTFChars(env, jDesc, desc);
    char out[1280];
    snprintf(out, sizeof(out), "{\"pack\":{\"pack_format\":%d,\"description\":\"%s\"}}", (int)fmt, esc);
    return (*env)->NewStringUTF(env, out);
}

/* ==================== v3: 四级解析链全 C 内置（不依赖外部数据） ==================== */
#include "name_data.h"

static int ends_with(const char *s, const char *suffix) {
    size_t ls = strlen(s), lf = strlen(suffix);
    return ls >= lf && strcmp(s + ls - lf, suffix) == 0;
}

/* token 规范形比较: a 与 b 下划线 token 重排后是否等价 */
static int token_equal(const char *a, const char *b) {
    char ca[128], cb[128];
    strncpy(ca, a, sizeof(ca) - 1); ca[sizeof(ca) - 1] = 0;
    strncpy(cb, b, sizeof(cb) - 1); cb[sizeof(cb) - 1] = 0;
    /* 去掉 .png 后缀 */
    char *dot = strstr(ca, ".png"); if (dot) *dot = 0;
    dot = strstr(cb, ".png"); if (dot) *dot = 0;
    char *ta[32], *tb[32]; int na = 0, nb = 0;
    char *p = ca;
    while (na < 32) { ta[na++] = p; char *u = strchr(p, '_'); if (!u) break; *u = 0; p = u + 1; }
    p = cb;
    while (nb < 32) { tb[nb++] = p; char *u = strchr(p, '_'); if (!u) break; *u = 0; p = u + 1; }
    if (na != nb) return 0;
    /* 插入排序 ca */
    for (int i = 1; i < na; i++) { char *k = ta[i]; int j = i - 1;
        while (j >= 0 && strcmp(ta[j], k) > 0) { ta[j + 1] = ta[j]; j--; } ta[j + 1] = k; }
    for (int i = 1; i < nb; i++) { char *k = tb[i]; int j = i - 1;
        while (j >= 0 && strcmp(tb[j], k) > 0) { tb[j + 1] = tb[j]; j--; } tb[j + 1] = k; }
    for (int i = 0; i < na; i++) if (strcmp(ta[i], tb[i]) != 0) return 0;
    return 1;
}

/* 在名单中找 token 等价项 */
static const char *find_token_in(const char **list, int n, const char *base) {
    for (int i = 0; i < n; i++)
        if (token_equal(list[i], base)) return list[i];
    return NULL;
}

static const char *dir_of(const char *jdir) {
    if (strstr(jdir, "/block")) return "block";
    if (strstr(jdir, "/item")) return "item";
    return NULL;
}

/**
 * resolveTexturePath — 四级解析链（与 Java 版 Bedrock2JavaCore 语义一致）:
 *   L1 权威映射表(内置957条) → L2 同名直转 → L3a 同目录 token 重排 → L3b 跨目录
 * HUD 语义子表同样内置（13 条核心）。
 * 返回: Java 相对路径（"assets/minecraft/..."）或降级时为空串。
 */
JNIEXPORT jstring JNICALL
Java_com_mineways_pack_PackCore_resolveTexturePath(JNIEnv *env, jclass clazz, jstring jRel)
{
    if (!jRel) return NULL;
    const char *rel = (*env)->GetStringUTFChars(env, jRel, NULL);
    if (!rel) return NULL;

    char out[1024]; out[0] = 0;

    /* L-HUD: ui 语义子表 */
    if (strncmp(rel, "textures/ui/", 12) == 0) {
        const char *base = rel + 12;
        static const struct { const char *b; const char *j; } kHud[] = {
            {"armor_full.png", "hud/armor_full.png"},
            {"armor_half.png", "hud/armor_half.png"},
            {"armor_empty.png", "hud/armor_empty.png"},
            {"absorption_heart.png", "hud/heart/absorbing_full.png"},
            {"absorption_heart_half.png", "hud/heart/absorbing_half.png"},
            {"freeze_heart.png", "hud/heart/frozen_full.png"},
            {"freeze_heart_half.png", "hud/heart/frozen_half.png"},
            {"heart.png", "hud/heart/full.png"},
            {"heart_half.png", "hud/heart/half.png"},
            {"heart_flash.png", "hud/heart/container.png"},
            {"heart_background.png", "hud/heart/container.png"},
            {"poison_heart.png", "hud/heart/poisoned_full.png"},
            {"poison_heart_half.png", "hud/heart/poisoned_half.png"},
            {"horse_heart.png", "hud/heart/vehicle_full.png"},
            {"horse_heart_half.png", "hud/heart/vehicle_half.png"},
            {"horse_heart_flash.png", "hud/heart/vehicle_container.png"},
            {"experiencebarfull.png", "hud/experience_bar_progress.png"},
            {"experiencebarempty.png", "hud/experience_bar_background.png"},
            {"hunger_full.png", "hud/food_full.png"},
            {"hunger_half.png", "hud/food_half.png"},
            {"hunger_background.png", "hud/food_empty.png"},
            {"hunger_effect_full.png", "hud/food_full_hunger.png"},
            {"hunger_effect_half.png", "hud/food_half_hunger.png"},
            {"hunger_effect_background.png", "hud/food_empty_hunger.png"},
        };
        for (size_t i = 0; i < sizeof(kHud) / sizeof(kHud[0]); i++) {
            if (strcmp(base, kHud[i].b) == 0) {
                snprintf(out, sizeof(out), "assets/minecraft/textures/gui/sprites/%s", kHud[i].j);
                goto done;
            }
        }
        goto done;  /* ui 未命中 = 降级 */
    }

    if (strncmp(rel, "textures/", 9) == 0) {
        const char *dir = rel + 9;
        const char *slash = strchr(dir, '/');
        const char *javaDir = NULL;
        if (slash) {
            size_t dlen = (size_t)(slash - dir);
            for (size_t i = 0; i < kDirMapN; i++)
                if (strlen(kDirMap[i].bedrock) == dlen && strncmp(kDirMap[i].bedrock, dir, dlen) == 0) {
                    javaDir = kDirMap[i].java; break;
                }
        }
        if (!javaDir) goto done;   /* 降级 */

        /* L1: 权威映射表（基岩全路径） */
        for (int i = 0; i < NAME_MAP_N; i++) {
            if (strcmp(kNameMap[i].bedrock, rel) == 0) {
                const char *j = kNameMap[i].java;
                /* java 中间态复数目录 → 单数 */
                char fixed[1024];
                const char *repl = NULL;
                if (strstr(j, "textures/blocks/") == j) { repl = "textures/block/"; snprintf(fixed, sizeof(fixed), "%s%s", repl, j + 16); }
                else if (strstr(j, "textures/items/") == j) { repl = "textures/item/"; snprintf(fixed, sizeof(fixed), "%s%s", repl, j + 15); }
                else snprintf(fixed, sizeof(fixed), "%s", j);
                snprintf(out, sizeof(out), "assets/minecraft/%s", fixed);
                goto done;
            }
        }
        /* L2: 同名直转 */
        {
            char jdir[256];
            snprintf(jdir, sizeof(jdir), "assets/minecraft/textures/%s", javaDir);
            snprintf(out, sizeof(out), "%s/%s", jdir, slash + 1);
            const char *vk = dir_of(jdir);
            if (vk) {
                const char **list = (strcmp(vk, "block") == 0) ? kJavaBlock : kJavaItem;
                const int ln = (strcmp(vk, "block") == 0) ? JAVA_BLOCK_N : JAVA_ITEM_N;
                int inList = 0;
                for (int i = 0; i < ln; i++) if (strcmp(list[i], slash + 1) == 0) { inList = 1; break; }
                if (inList) goto done;
                /* L3a: 同目录 token 重排 */
                const char *tok = find_token_in(list, ln, slash + 1);
                if (tok) { snprintf(out, sizeof(out), "%s/%s", jdir, tok); goto done; }
                /* L3b: 跨目录同名/重排 */
                {
                    const char **ol = (strcmp(vk, "block") == 0) ? kJavaItem : kJavaBlock;
                    const int on = (strcmp(vk, "block") == 0) ? JAVA_ITEM_N : JAVA_BLOCK_N;
                    const char *odir = (strcmp(vk, "block") == 0)
                        ? "assets/minecraft/textures/item" : "assets/minecraft/textures/block";
                    for (int i = 0; i < on; i++)
                        if (strcmp(ol[i], slash + 1) == 0) { snprintf(out, sizeof(out), "%s/%s", odir, slash + 1); goto done; }
                    tok = find_token_in(ol, on, slash + 1);
                    if (tok) { snprintf(out, sizeof(out), "%s/%s", odir, tok); goto done; }
                }
            }
        }
    }
done:
    (*env)->ReleaseStringUTFChars(env, jRel, rel);
    return (*env)->NewStringUTF(env, out);
}

/**
 * buildItemDefinition — 为未命中贴图生成 1.21.4+ 物品引用定义（贴图直挂模板）:
 *   items/<name>.json 与 models/item/<name>.json 的模板文本。
 * 返回 model JSON（layer0 引用贴图路径）；调用方再拼 items 定义。
 */
JNIEXPORT jstring JNICALL
Java_com_mineways_pack_PackCore_buildItemModel(JNIEnv *env, jclass clazz, jstring jModel, jstring jTex)
{
    if (!jModel || !jTex) return NULL;
    const char *model = (*env)->GetStringUTFChars(env, jModel, NULL);
    const char *tex = (*env)->GetStringUTFChars(env, jTex, NULL);
    char out[2048];
    snprintf(out, sizeof(out),
        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"%s\"}}", tex);
    (void)model;
    (*env)->ReleaseStringUTFChars(env, jModel, model);
    (*env)->ReleaseStringUTFChars(env, jTex, tex);
    return (*env)->NewStringUTF(env, out);
}
