/*
  m2-3 · JNI 导出入口。
  把桌面的"读档→建立 WorldGuide/Options→SaveVolume→写 OBJ"流程收编为单次 JNI 调用。
  原则（与 MEMORY 铁律一致）：核心算法一个不改；这里只做"调用前铺状态"的 UI/适配附属：
    - 从 level.dat 探测 dataVersion、newFormat、出生点 —— 复刻桌面 loadWorld()
    - 构造 WorldGuide / Options / ExportFileData 默认值（复刻由 core/export_setup 提供）
    - 按默认 view 导出装配 exportFlags —— 复刻桌面 exportVolume() 的 flag 装配段
    - 导出前 seed 默认面剔除方案（applyCullingScheme(NULL)），使 barrier/structure_void 面剔除生效
    - 调用原版 SaveVolume()
  共享装配逻辑已抽到 core/export_setup（EFD/高度/Options），JNI 与宿主测试共用同一份。
*/
#include "stdafx.h"
#include "CullingSchemes.h"   // applyCullingScheme / isBlockCulled
#include "export_setup.h"     // initViewExportData / assembleOptions / setHeightsFromVersionId / callback
#include "export_diag.h"      // 只读体检：把 files=0/err=512 变成可读、可粘贴的报错
#include "core/ObjFileManip.h" // mwBuildCustomTerrain（自定义材质 → 合成 terrainExt 图集）
#include <jni.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <sys/stat.h>   // struct stat / stat() —— newFormat 维度目录探测

// 依维度拼出"哪个维度的 region 目录"前缀（与核心 SetDimensionDirectory 的规则逐字一致）：
//   旧格式：DIM-1/（下界）、DIM1/（末地）、无前缀（主世界）
//   新格式(25w02a+)：dimensions/minecraft/{the_nether,the_end,overworld}/
// 用于让"导出前试读"也读对维度（核心读档本身靠 opt.worldType 的 HELL/ENDER 位）。
static std::wstring dimensionDir(const std::wstring& world, int dim, bool newFormat) {
    std::wstring d = world;
    if (!d.empty() && d[d.size() - 1] != L'/') d += L'/';
    if (newFormat) {
        if (dim == 1) d += L"dimensions/minecraft/the_nether/";
        else if (dim == 2) d += L"dimensions/minecraft/the_end/";
        else d += L"dimensions/minecraft/overworld/";
    } else {
        if (dim == 1) d += L"DIM-1/";
        else if (dim == 2) d += L"DIM1/";
    }
    return d;
}

static std::string dimensionName(int dim) {
    if (dim == 1) return "下界（Nether, DIM-1）";
    if (dim == 2) return "末地（The End, DIM1）";
    return "主世界（Overworld）";
}

// 诊断报告用的数字转字符串
static std::string I(long long v) {
    char b[32];
    snprintf(b, sizeof(b), "%lld", v);
    return std::string(b);
}

// ---- 导出选项串解析：形如 "zip=1;zup=0;rotate=90;mat=4;tiledir=tex" ----
// 未知键忽略、缺省用默认值，保证不带选项调用时行为与旧版完全一致。
static std::string optRaw(const std::string& opts, const char* key) {
    if (opts.empty() || key == NULL) return std::string();
    std::string k = std::string(key) + "=";
    size_t pos = 0;
    while (pos < opts.size()) {
        size_t end = opts.find(';', pos);
        if (end == std::string::npos) end = opts.size();
        if (opts.compare(pos, k.size(), k) == 0) {
            return opts.substr(pos + k.size(), end - pos - k.size());
        }
        pos = end + 1;
    }
    return std::string();
}

// 取字符串选项（导出选项串形如 ";tiledir=tex;terrain=/path/x.png"）
static std::string optStr(const std::string& opts, const char* key, const char* def) {
    const std::string pat = std::string(";") + key + "=";
    size_t pos = opts.find(pat);
    if (pos == std::string::npos) {
        const std::string head = key + std::string("=");
        if (opts.compare(0, head.size(), head) == 0) {
            pos = 0;
        } else {
            return std::string(def);
        }
    }
    const size_t start = pos + pat.size();
    const size_t end = opts.find(';', start);
    return opts.substr(start, (end == std::string::npos) ? std::string::npos : end - start);
}

static int optInt(const std::string& opts, const char* key, int def) {
    std::string v = optRaw(opts, key);
    if (v.empty()) return def;
    return atoi(v.c_str());
}

static float optFloat(const std::string& opts, const char* key, float def) {
    std::string v = optRaw(opts, key);
    if (v.empty()) return def;
    return (float)atof(v.c_str());
}

// ---- 边界兜底（只在越界时夹紧；正常值/缺省值逐位不变，因此不影响既有导出结果）----
// 界面允许手输数字，而核心对这些值有硬要求：
//   blockSizeVal/modelHeightVal 用来算 scale（见 ObjFileManip.cpp:17712/17737），为 0 会除零产生 NaN；
//   hollowThicknessVal 有 assert(>0)（:17913）并参与除法（:17914）；costVal 参与材料预算（:17846）。
static float optFloatRange(const std::string& opts, const char* key, float def, float lo, float hi) {
    float v = optFloat(opts, key, def);
    if (!(v == v)) return def;      // NaN → 缺省值
    if (v < lo) v = lo;
    if (v > hi) v = hi;
    return v;
}

static int optIntRange(const std::string& opts, const char* key, int def, int lo, int hi) {
    int v = optInt(opts, key, def);
    if (v < lo) v = lo;
    if (v > hi) v = hi;
    return v;
}

// ================= JNI：exportWorld =================
/*
  参数：
    worldDirUtf8     : 世界目录（level.dat 所在目录）
    outBaseUtf8      : 输出文件根路径（不含扩展名；SaveVolume 自动追加 .obj/.mtl/.png）
    fileType         : FILE_TYPE_WAVEFRONT_ABS_OBJ(0) 等
    minx,miny,minz,maxx,maxy,maxz : 框选坐标（miny/maxy 传世界全高度可用 -999 由内部解析）
  返回：UTF-8 结果字符串：
    "OK files=N <root0>;"  成功，N=输出文件数
    "ERR <numeric code>:<msg>"  失败
 */
// 自定义材质：把资源包贴图（texDir 下的 16×16 PNG）合成 terrainExt 图集，写出 outPng。
// 返回贴入的格子数；-1 参数不对，-2 写文件失败。（对应 ExportActivity.nativeBuildTerrain）
extern "C" JNIEXPORT jint JNICALL
Java_com_mineways_ExportActivity_nativeBuildTerrain(JNIEnv* env, jobject,
                                                    jstring jTexDir, jstring jOutPng)
{
    if (jTexDir == NULL || jOutPng == NULL)
        return -1;
    const char* texDirUtf8 = env->GetStringUTFChars(jTexDir, NULL);
    if (texDirUtf8 == NULL)
        return -1;
    const char* outUtf8 = env->GetStringUTFChars(jOutPng, NULL);
    if (outUtf8 == NULL) {
        env->ReleaseStringUTFChars(jTexDir, texDirUtf8);
        return -1;
    }
    wchar_t wTex[MAX_PATH_AND_FILE] = {0};
    wchar_t wOut[MAX_PATH_AND_FILE] = {0};
    MultiByteToWideChar(CP_UTF8, 0, texDirUtf8, -1, wTex, (int) (sizeof(wTex) / sizeof(wchar_t)));
    MultiByteToWideChar(CP_UTF8, 0, outUtf8, -1, wOut, (int) (sizeof(wOut) / sizeof(wchar_t)));
    const int rc = mwBuildCustomTerrain(wTex, wOut);
    env->ReleaseStringUTFChars(jTexDir, texDirUtf8);
    env->ReleaseStringUTFChars(jOutPng, outUtf8);
    return (jint) rc;
}

// 注意：native 声明在 com.mineways.ExportActivity 里，按 JNI 规范符号名必须用「声明类」的名字。
// 这里以前写成 MainActivity → 运行时 UnsatisfiedLinkError（存档导出整个不可用）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_mineways_ExportActivity_exportWorld(JNIEnv* env, jobject,
    jstring worldDirUtf8, jstring outBaseUtf8,
    jint fileType, jint minx, jint miny, jint minz,
    jint maxx, jint maxy, jint maxz,
    jint cullMode, jint decimateOn, jstring optionsUtf8)
{
    // cullMode : 0=全显示(不剔除), 1=标准(仅隐藏永不可见), 2=强剔除(隐藏不透明面)
    // decimateOn : 0=不合并面(保留最多面), 1=合并共面(大幅减少面数)
    // optionsUtf8 : "k=v;k=v" 形式的高级导出选项（桌面 Mineways 导出对话框的对应子集）
    const char* cWorldUtf8 = worldDirUtf8 ? env->GetStringUTFChars(worldDirUtf8, nullptr) : nullptr;
    const char* cOutUtf8   = outBaseUtf8  ? env->GetStringUTFChars(outBaseUtf8,  nullptr) : nullptr;
    const char* cOptsUtf8  = optionsUtf8  ? env->GetStringUTFChars(optionsUtf8,  nullptr) : nullptr;
    std::string opts = cOptsUtf8 ? std::string(cOptsUtf8) : std::string();
    if (cOptsUtf8) env->ReleaseStringUTFChars(optionsUtf8, cOptsUtf8);

    wchar_t worldDir[MAX_PATH_AND_FILE] = {0};
    wchar_t outBase[MAX_PATH_AND_FILE]  = {0};
    if (cWorldUtf8) androidMultiByteToWideChar(CP_UTF8, 0, cWorldUtf8, (int)strlen(cWorldUtf8), worldDir, MAX_PATH_AND_FILE);
    if (cOutUtf8)   androidMultiByteToWideChar(CP_UTF8, 0, cOutUtf8,   (int)strlen(cOutUtf8),   outBase,   MAX_PATH_AND_FILE);
    if (cWorldUtf8) env->ReleaseStringUTFChars(worldDirUtf8, cWorldUtf8);
    if (cOutUtf8)  env->ReleaseStringUTFChars(outBaseUtf8,  cOutUtf8);

    // 基本校验
    std::string result;
    if (wcslen(worldDir) == 0) { result = "ERR 1:empty world dir"; return env->NewStringUTF(result.c_str()); }
    if (wcslen(outBase) == 0)  { result = "ERR 2:empty output base"; return env->NewStringUTF(result.c_str()); }
    if (fileType < 0 || fileType >= FILE_TYPE_TOTAL) { result = "ERR 3:bad fileType"; return env->NewStringUTF(result.c_str()); }

    // ---- 读 level.dat：dataVersion + 出生点（复刻 loadWorld 前半段） ----
    wchar_t levelFile[MAX_PATH_AND_FILE];
    wcscpy(levelFile, worldDir);
    size_t lfLen = wcslen(levelFile);
    if (levelFile[lfLen-1] != L'/') wcscat(levelFile, L"/");
    wcscat(levelFile, L"level.dat");

    int fopenErr = 0;
    bfFile bf = newNBT(levelFile, &fopenErr);
    if (bf.fptr == NULL || fopenErr != 0) {
        char er[256]; snprintf(er, sizeof(er), "ERR 4:open level.dat failed err=%d", fopenErr);
        return env->NewStringUTF(er);
    }
    int versionId = 0;
    nbtGetFileVersionId(&bf, &versionId);
    if (versionId < 1000) {
        nbtClose(&bf);
        // 这里以前只回一句 "world too old"，是最误导人的一种：基岩版存档的 level.dat 本来就没有
        // Java 的 DataVersion。补上体检 + 人话结论，一次就能看出该去「转换」页。
        ExportDiag::WorldScan early;
        ExportDiag::scanWorld(worldDir, versionId, 0, 0, 0, 0, early);
        std::string msg = "ERR 5:world too old (<Anvil)";
        msg += "\n—— 导出诊断报告（请整段复制反馈）——\n";
        msg += std::string("[核心] 原生构建 ") + __DATE__ + " " + __TIME__ + "\n";
        msg += "[世界] " + early.worldDir + "\n";
        msg += "[level.dat] 读到 DataVersion=" + I(versionId) + "（Java 版存档这里应为 1000+）\n";
        msg += "[region] " + I(early.regionFiles) + " 个文件 / " + I(early.totalChunks) + " 个区块";
        if (early.hasDbDir) msg += " ；检测到 db/ 目录（基岩版特征）";
        if (!early.hasRegionDir) msg += " ；没有 region/ 目录";
        msg += "\n";
        for (size_t i = 0; i < early.problems.size(); i++) {
            msg += "    - " + early.problems[i] + "\n";
        }
        msg += "[结论] ";
        if (early.hasDbDir || !early.hasRegionDir) {
            msg += "这是基岩版（Bedrock）存档：请先到「转换」页把它转成 Java 版，再用转出的 Java 世界导出 OBJ。\n";
        } else {
            msg += "level.dat 里没有可用的 Java 版本号：确认选中的是 Java 版存档根目录（同时含 level.dat 与 region/）。\n";
        }
        return env->NewStringUTF(msg.c_str());
    }
    int sx=0, sy=0, sz=0;
    nbtGetSpawn(&bf, &sx, &sy, &sz);
    nbtClose(&bf);

    int mcVersion = DATA_VERSION_TO_RELEASE_NUMBER(versionId);

    // 世界高度
    int mapMinHeight, mapMaxHeight;
    setHeightsFromVersionId(versionId, mcVersion, mapMinHeight, mapMaxHeight);

    // 若调用方未给 Y 范围（用 -999 标记），则用世界整体高度
    int y0 = (miny == -999 || maxy == -999)? mapMinHeight : miny;
    int y1 = (miny == -999 || maxy == -999)? mapMaxHeight : maxy;
    if (y1 < y0) { int t=y0; y0=y1; y1=t; }

    // ---- WorldGuide（复刻 loadWorld 的设置） ----
    WorldGuide wg;
    memset(&wg, 0, sizeof(WorldGuide));
    wg.type = WORLD_LEVEL_TYPE;
    wcscpy(wg.world, worldDir);
    // newFormat：检测 dimensions/ 子目录（快照 25w02a+）
    wg.newFormat = false;
    {
        wchar_t testDir[MAX_PATH_AND_FILE];
        wcscpy(testDir, worldDir);
        size_t tl = wcslen(testDir);
        if (testDir[tl-1] != L'/') wcscat(testDir, L"/");
        wcscat(testDir, L"dimensions");
        // 简易宽→窄（仅 ASCII 世界目录可直接判定；非 ASCII 目录在此按非 newFormat 处理，路径读写走 UTF-8 shim）
        char tmp[MAX_PATH_AND_FILE];
        bool ascii = true;
        const wchar_t* p = testDir;
        int k = 0;
        for (k=0; p[k]!=0 && k<MAX_PATH_AND_FILE-1; k++){ if(p[k]>127){ ascii=false; break; } tmp[k]=(char)p[k]; }
        tmp[k]=0;
        if (ascii) {
            struct stat st;
            if (stat(tmp, &st)==0 && (st.st_mode & S_IFDIR)) wg.newFormat = true;
        }
    }

    // 关键：WorldGuide 必须带上真实高度范围，LoadBlock/block_alloc 依此分配、并把 block->minHeight/maxHeight 传给 regionGetBlocks。
    // 若不设置，block_alloc 得到 0..0，regionGetBlocks 只读 1 层，SaveVolume 报 MW_NO_BLOCKS_FOUND(512)。
    wg.minHeight = mapMinHeight;
    wg.maxHeight = mapMaxHeight;

    // 维度：0=主世界 1=下界 2=末地（默认主世界＝旧行为不变）。
    // 桌面版是从地图视图切维度；安卓端此前**没有这个开关**，于是构建在下界/末地时
    // 导出来的永远是主世界地形 —— 用户看到的就是"地图转 OBJ 货不对版"。
    const int dim = optInt(opts, "dim", 0);

    // ---- 导出前世界体检（只读扫描，不碰核心状态；失败也有报告可粘贴）----
    ExportDiag::WorldScan scan;
    ExportDiag::scanWorld(worldDir, versionId, mcVersion, sx, sy, sz, scan);

    // 再用核心自己的读档函数试读**选区内**的区块：把"导出 0 方块"拆成三层
    //   —— 核心解析失败 / 选区内确实没有方块（多半 Y 填错）/ 渲染筛选层问题
    // 先应用一次剔除方案：让"导出前试读"里的"[被过滤]"判定与真正的导出**完全同一套状态**
    // （剔除只影响 barrier/structure_void/structure_block 等技术块；这里幂等，不影响后面的正式装配）
    if (cullMode == 0) {
        unsigned char showAllEarly[NUM_CULL_ENTRIES];
        memset(showAllEarly, 0, sizeof(showAllEarly));
        applyCullingScheme(showAllEarly);
    } else if (cullMode == 2) {
        applyCullingScheme(NULL);
        seedExtraCulled();
    } else {
        applyCullingScheme(NULL);
    }

    std::string coreProbe = ExportDiag::probeCoreRead(
            dimensionDir(std::wstring(worldDir), dim, wg.newFormat), mcVersion,
            mapMinHeight, mapMaxHeight,
            minx, y0, minz, maxx, y1, maxz);

    // ---- Options + ExportFileData（共享装配）----
    ExportFileData efd;
    initViewExportData(efd);
    efd.fileType = fileType;
    efd.minxVal = minx; efd.minyVal = y0; efd.minzVal = minz;
    efd.maxxVal = maxx; efd.maxyVal = y1; efd.maxzVal = maxz;

    // ---- 高级导出选项（Android「导出选项」页 → 对应桌面导出对话框的子集）----
    // 全部给默认值，且默认值与旧行为逐项一致：不传选项时导出结果与以前完全相同。
    {
        // 材质/纹理模式：0=不导出材质 1=实体材质颜色 2=带颜色噪点纹理 3=整幅大图 4=单独纹理(旧默认)
        const int mat = optInt(opts, "mat", 4);
        for (int f = 0; f < FILE_TYPE_TOTAL; f++) {
            efd.radioExportNoMaterials[f]   = (mat == 0) ? 1 : 0;
            efd.radioExportMtlColors[f]     = (mat == 1) ? 1 : 0;
            efd.radioExportSolidTexture[f]  = (mat == 2) ? 1 : 0;
            efd.radioExportFullTexture[f]   = (mat == 3) ? 1 : 0;
            efd.radioExportTileTextures[f]  = (mat == 4) ? 1 : 0;
        }
        efd.chkTextureRGB  = optInt(opts, "texrgb", 1);
        efd.chkTextureA    = optInt(opts, "texa", 1);
        efd.chkTextureRGBA = optInt(opts, "texrgba", 1);
        std::string tileDir = optRaw(opts, "tiledir");
        if (!tileDir.empty()) {
            strncpy(efd.tileDirString, tileDir.c_str(), sizeof(efd.tileDirString) - 1);
            efd.tileDirString[sizeof(efd.tileDirString) - 1] = 0;
        }

        // 朝向与坐标
        efd.chkMakeZUp[fileType] = optInt(opts, "zup", 0);   // chkMakeZUp 是 per-fileType 数组
        efd.chkCenterModel = optInt(opts, "center", 0);
        const int rot = optInt(opts, "rotate", 0);
        efd.radioRotate0   = (rot == 0) ? 1 : 0;
        efd.radioRotate90  = (rot == 90) ? 1 : 0;
        efd.radioRotate180 = (rot == 180) ? 1 : 0;
        efd.radioRotate270 = (rot == 270) ? 1 : 0;

        // 网格与分块（默认值与 initViewExportData 相同）
        efd.chkDecimate = (optInt(opts, "decimate", (decimateOn != 0) ? 1 : 0) != 0) ? 1 : 0;
        efd.chkBlockFacesAtBorders = optInt(opts, "borderfaces", 1);
        efd.chkSeparateTypes = optInt(opts, "septypes", 1);
        efd.chkIndividualBlocks[fileType] = optInt(opts, "indiv", 0);
        efd.chkMaterialPerFamily = optInt(opts, "fam", 1);
        efd.chkSplitByBlockType = optInt(opts, "split", 1);
        efd.chkMakeGroupsObjects = optInt(opts, "groups", 1);
        efd.chkCustomMaterial[fileType] = optInt(opts, "custommtl", efd.chkCustomMaterial[fileType]);
        efd.chkLeavesSolid = optInt(opts, "leaves", 0);

        // ---- 第二批：3D 打印相关（对应桌面对话框右半部分）----
        // 尺寸模式：0=按高度(cm，radioScaleToHeight) 1=按材料壁厚(radioScaleToMaterial)
        //          2=每个区块多少毫米(radioScaleByBlock，旧默认) 3=按目标成本(radioScaleByCost)
        const int scale = optInt(opts, "scale", 2);
        efd.radioScaleToHeight   = (scale == 0) ? 1 : 0;
        efd.radioScaleToMaterial = (scale == 1) ? 1 : 0;
        efd.radioScaleByBlock    = (scale == 2) ? 1 : 0;
        efd.radioScaleByCost     = (scale == 3) ? 1 : 0;
        efd.modelHeightVal = optFloatRange(opts, "modelheight", 5.0f, 0.5f, 1000.0f);          // 厘米
        // 兜底值必须和 Java 侧 DEFAULT_BLOCK_MM 一致：1000 毫米 = 1 方块 1 个模型单位。
        // 以前这里是 2（桌面 3D 打印档），一旦上层没带 blocksize 就会导出小 500 倍的模型。
        efd.blockSizeVal[fileType] = optFloatRange(opts, "blocksize", 1000.0f, 0.1f, 10000.0f);   // 每个区块毫米
        efd.costVal = optFloatRange(opts, "cost", 25.0f, 1.0f, 1000000.0f);

        // 单位与物理材料（值是核心里的枚举：UNITS_METER/CENTIMETER/MILLIMETER/INCHES、PRINT_MATERIAL_*）
        efd.comboModelUnits[fileType] = optInt(opts, "units", efd.comboModelUnits[fileType]);
        efd.comboPhysicalMaterial[fileType] = optInt(opts, "physmat", efd.comboPhysicalMaterial[fileType]);

        // 掏空 / 中空 / 壁厚
        efd.chkHollow[fileType] = optInt(opts, "hollow", efd.chkHollow[fileType]);
        efd.chkSuperHollow[fileType] = optInt(opts, "superhollow", efd.chkSuperHollow[fileType]);
        efd.hollowThicknessVal[fileType] = optFloatRange(opts, "hollowthick", efd.hollowThicknessVal[fileType], 0.1f, 1000.0f);

        // 密封 / 连接 / 浮动对象 / 融雪 / 复合叠加
        efd.chkSealEntrances   = optInt(opts, "sealentrances", 0);
        efd.chkSealSideTunnels = optInt(opts, "sealtunnels", 0);
        efd.chkFillBubbles     = optInt(opts, "fillbubbles", 0);
        efd.chkConnectParts    = optInt(opts, "connectparts", 0);
        efd.chkConnectCornerTips = optInt(opts, "connectcorners", 0);
        efd.chkConnectAllEdges = optInt(opts, "connectalledges", 0);
        efd.chkDeleteFloaters  = optInt(opts, "deletefloaters", 0);
        efd.floaterCountVal    = optIntRange(opts, "floatercount", 16, 0, 1000000);
        efd.chkMeltSnow        = optInt(opts, "meltsnow", 0);
        efd.chkCompositeOverlay = optInt(opts, "compositeoverlay", 0);

        // ---- 第三批：块数/翻倍/平面合并/调试显示（核心都是直接读 efd，无需 EXPT 标志）----
        efd.chkFatten            = optInt(opts, "fatten", 0);      // 肥大块：相邻同类方块合成大块→块数/面数更少
        efd.chkDoubledBillboards = optInt(opts, "doubled", 0);    // 平面物件（花草/告示牌）正反各画一遍
        efd.chkMergeFlattop      = optInt(opts, "mergeflat", 0);  // 默认关＝与 1.0/1.1 的既有导出结果一致；桌面默认开，需要时可勾
        efd.chkShowParts         = optInt(opts, "showparts", 0);  // 用颜色标出零件（调试）
        efd.chkShowWelds         = optInt(opts, "showwelds", 0);  // 用颜色标出焊接块（调试）

        // ---- 第四批：桌面对话框里剩下这几项（默认值＝此前固定行为，不影响既有导出结果）----
        efd.chkExportMDL = optInt(opts, "mdl", 0);              // 导出 MDL（额外 .mdl 文件）
        efd.chkBiome     = optInt(opts, "biome", 0);            // 使用生物群系
        efd.chkExportAll = optInt(opts, "exportall", 1);        // 1=导出较少、详细的块（小装饰），此前固定为 1
        for (int f = 0; f < FILE_TYPE_TOTAL; f++) {
            efd.chkCreateModelFiles[f] = optInt(opts, "cremodel", 1);   // 1=导出后保留模型文件，此前固定为 1
        }
    }

    Options opt;
    assembleOptions(opt, efd, fileType);

    // 维度：核心用 worldType 里的 HELL/ENDER 位决定读哪个维度的 region 文件
    // （ObjFileManip 里 SetDimensionDirectory(pWorldGuide, gModel.options->worldType)）。
    // 之前恒为主世界 → 构建在下界/末地时导出的就不是那张图。
    if (dim == 1)      opt.worldType |= HELL;
    else if (dim == 2) opt.worldType |= ENDER;

    // 适配 Prisma3D（P3D）：只改导出材质参数，不动几何。参照 Prisma3D 2.0.8 自带的 OBJ 材质模板
    // （illum 4 / Ka 0 0 0 / Kd 1 1 1 / 无 map_Ka / 无自发光 Ke），专治"模型导入 P3D 后过曝"。
    // 默认开启（安卓端此前没有这个开关），面板上可关。
    if (optInt(opts, "p3d", 1) != 0) opt.exportFlags |= EXPT_ADAPT_P3D;

    // 调试开关（属于 exportFlags，不进 efd）：用不同颜色显示浮动部件 / 焊接块
    if (optInt(opts, "dbggroups", 0)) opt.exportFlags |= EXPT_DEBUG_SHOW_GROUPS;
    if (optInt(opts, "dbgwelds", 0)) opt.exportFlags |= EXPT_DEBUG_SHOW_WELDS;


    // ---- 面剔除方案（Culling）----
    // 剔除的本质在 LoadBlock/mesh 生成里已经逐方块做（只有暴露的面才生成三角形）。
    // applyCullingScheme 控制的是"额外隐藏某些方块"（技术方块 / 你想眼不见的块）：
    //   0 = 全显示：连 barrier/structure_void 也保留（最完整、面最多）
    //   1 = 标准（默认）：隐藏永不可见的技术方块 barrier/structure_void
    //   2 = 强：在标准基础上，额外隐藏只作为结构支撑的常见工程方块（barrier/结构空位/隐形基岩）
    if (cullMode == 0) {
        // 全显示：culled 全 0。applyCullingScheme(ptr) 走 memcpy 分支，不再 seed 默认隐藏项。
        unsigned char showAll[NUM_CULL_ENTRIES];
        memset(showAll, 0, sizeof(showAll));
        applyCullingScheme(showAll);
    } else if (cullMode == 2) {
        // 强：Standard 打底（barrier/void 藏掉），再额外隐藏 structure_block/void 相关工程块。
        // 用一块含"默认隐藏+额外项"的方案：seed 后补 structure_block。
        applyCullingScheme(NULL);
        seedExtraCulled();
    } else {
        // 1 = 标准（默认）
        applyCullingScheme(NULL);
    }

    // 统计当前方案隐藏了多少种方块：让「全显示 / 标准 / 强剔除」的差别可见
    // （这些方案只隐藏 barrier/structure_void 等技术方块，世界里没有这些方块时肉眼看着"没变化"）。
    int culledTypes = 0;
    for (int t = 0; t < NUM_CULL_ENTRIES; t++) {
        if (isBlockCulled(t, 0)) culledTypes++;
    }

    // ---- 路径分隔符（桌面 GUI 设 '\\'；安卓固定 '/'），SetDimensionDirectory 依赖它拼接维度目录 ----
    SetSeparatorMap(L"/");
    SetDimensionDirectory(&wg, opt.worldType);

    // ---- 输出目录（curDir = 输出根所在目录） ----
    wchar_t outDir[MAX_PATH_AND_FILE];
    wcscpy(outDir, outBase);
    wchar_t* slash = wcsrchr(outDir, L'/');
    if (slash) { *slash = 0; }
    else {
        wchar_t* bs = wcsrchr(outDir, L'\\');
        if (bs) *bs = 0;
        else    wcscpy(outDir, L".");
    }

    // 地形纹理（图集）：
    //   默认传一个不存在的、无 .png 后缀的路径 → SaveVolume 内 RGBA 用内置 gTerrainExt fallback，
    //   其余类别自动跳过/读同名前缀；带 terrain=<路径> 时用自定义图集（自定义材质包合成的那张），
    //   「整幅大图」与「单独纹理」两种模式都会用它。
    wchar_t terrainFileName[MAX_PATH_AND_FILE];
    std::string terrainOpt = optStr(opts, "terrain", "");
    if (!terrainOpt.empty()) {
        MultiByteToWideChar(CP_UTF8, 0, terrainOpt.c_str(), -1, terrainFileName,
                            (int) (sizeof(terrainFileName) / sizeof(wchar_t)));
    } else {
        wcscpy(terrainFileName, outBase);
        wcscat(terrainFileName, L"_terrain_placeholder");
    }

    // cull 方案名：核心只把它写进 OBJ 注释头做记录（writeStatistics → "# Culling scheme: ..."），
    // 不参与实际剔除计算（剔除由上面的 applyCullingScheme 完成）。
    // 之前留空 → 无论选哪种方案，回执永远写 "Standard"，看起来像"面剔除选项没用"。
    wchar_t cullSchemeSelected[MAX_PATH_AND_FILE];
    if (cullMode == 0) {
        wcscpy(cullSchemeSelected, L"Android: show all blocks");
    } else if (cullMode == 2) {
        wcscpy(cullSchemeSelected, L"Android: aggressive (hide engineering blocks)");
    } else {
        wcscpy(cullSchemeSelected, L"Android: standard (hide technical blocks)");
    }

    FileList outputFileList;
    initOutputFileList(outputFileList);

    // groupCount 输出参数
    int userSelectedBiome = -1;
    int biomeIndex = 0;
    int groupCount = 0;
    const int groupCountSize = 0;
    int* groupCountArray = NULL;

    int errCode = SaveVolume(outBase, fileType, &opt, &wg, outDir,
        minx, y0, minz, maxx, y1, maxz,
        mapMinHeight, mapMaxHeight,
        androidProgressCallback, terrainFileName, cullSchemeSelected, &outputFileList,
        17, 3, versionId, NULL, 16,
        userSelectedBiome, biomeIndex, groupCount, groupCountSize, groupCountArray);

    // ---- 自动重试：掏空/删除浮动对象/焊接等 3D 打印结构选项**按设计会删内容** ----
    // 「删除浮动对象」会删掉小于 floaterCount(默认16) 且不接地的方块组 —— 小模型/悬浮建筑会被整体删光，
    // 核心就如实返回 MW_NO_BLOCKS_FOUND(512)。用户"清空应用数据后能导出"（偏好被重置）正是这个机理。
    // 与其让用户猜是哪个开关干的：第一次 512 且没有产出任何文件时，自动关掉这组选项重试一次。
    bool retriedWithoutPrintOptions = false;
    if (errCode == MW_NO_BLOCKS_FOUND && outputFileList.count <= 0) {
        for (int f = 0; f < FILE_TYPE_TOTAL; f++) {
            efd.chkHollow[f] = 0;
            efd.chkSuperHollow[f] = 0;
        }
        efd.chkSealEntrances = 0;
        efd.chkSealSideTunnels = 0;
        efd.chkFillBubbles = 0;
        efd.chkConnectParts = 0;
        efd.chkConnectCornerTips = 0;
        efd.chkConnectAllEdges = 0;
        efd.chkDeleteFloaters = 0;
        efd.chkMeltSnow = 0;
        Options optRetry;
        assembleOptions(optRetry, efd, fileType);
        if (dim == 1)      optRetry.worldType |= HELL;
        else if (dim == 2) optRetry.worldType |= ENDER;
        optRetry.exportFlags &= ~EXPT_3DPRINT;   // 结构处理整体撤掉，保证不再删内容

        initOutputFileList(outputFileList);
        userSelectedBiome = -1; biomeIndex = 0; groupCount = 0;
        SetDimensionDirectory(&wg, optRetry.worldType);
        int retryCode = SaveVolume(outBase, fileType, &optRetry, &wg, outDir,
            minx, y0, minz, maxx, y1, maxz,
            mapMinHeight, mapMaxHeight,
            androidProgressCallback, terrainFileName, cullSchemeSelected, &outputFileList,
            17, 3, versionId, NULL, 16,
            userSelectedBiome, biomeIndex, groupCount, groupCountSize, groupCountArray);
        if (outputFileList.count > 0 && retryCode < MW_BEGIN_ERRORS) {
            retriedWithoutPrintOptions = true;
            errCode = retryCode;   // 采用重试结果
            opt = optRetry;        // 后续报告按重试后的装配输出
        }
    }

    // ---- 导出后：选区命中统计 + 组装"可直接粘贴反馈"的诊断报告 ----
    ExportDiag::scanSelection(worldDir, minx, minz, maxx, maxz, scan);

    const int files = outputFileList.count;
    const bool fatal = (errCode >= MW_BEGIN_ERRORS);
    const bool nothing = (files <= 0);
    // 一个文件都没产出时，错误码 0 也要按"没有找到方块"上报，不能冒充成功
    const int shownCode = fatal ? errCode
                                : (errCode != 0 ? errCode : (int)MW_NO_BLOCKS_FOUND);

    result.clear();   // 复用函数开头的 result（早退分支已用过同名变量）
    if (nothing || fatal) {
        result = "ERR " + I(shownCode) + " " + ExportDiag::codeName(shownCode);
    } else if (errCode >= MW_BEGIN_NOTHING_TO_DO) {
        result = "WARN files=" + I(files) + " err=" + I(errCode) + " " + ExportDiag::codeName(errCode);
    } else {
        result = "OK files=" + I(files) + " err=" + I(errCode);
    }

    // 压缩类型汇总
    std::string compText;
    long long nonZlib = 0;
    for (int t = 1; t < 8; t++) {
        if (scan.compression[t] > 0) {
            const char* nm = (t == 1) ? "gzip" : (t == 2) ? "zlib"
                           : (t == 3) ? "未压缩" : (t == 4) ? "LZ4" : "其它";
            if (!compText.empty()) compText += " / ";
            compText += std::string(nm) + "×" + I(scan.compression[t]);
            if (t != 2) nonZlib += scan.compression[t];
        }
    }
    if (compText.empty()) compText = "无";

    const char* cullDesc = (cullMode == 0) ? "全显示" : ((cullMode == 2) ? "强剔除（隐藏工程方块）" : "标准");

    std::string report;
    report += "\n—— 导出诊断报告（请整段复制反馈）——\n";
    // 原生构建时间戳：用于确认手机里跑的到底是不是刚出的这版（增量构建时该文件也会重编）
    report += std::string("[核心] 原生构建 ") + __DATE__ + " " + __TIME__ + "\n";
    report += "[世界] " + scan.worldDir + "\n";
    report += "[level.dat] " + std::string(scan.levelDatOk ? "OK" : "异常")
            + "  dataVersion=" + I(scan.dataVersion)
            + (scan.releaseNumber > 0 ? ("（约 MC 1." + I(scan.releaseNumber) + ".x）") : "")
            + "  出生点 " + I(scan.spawnX) + "," + I(scan.spawnY) + "," + I(scan.spawnZ);
    if (!scan.levelDatOk && !scan.levelDatNote.empty()) report += "  " + scan.levelDatNote;
    report += "\n";
    report += "[高度] 世界 " + I(mapMinHeight) + ".." + I(mapMaxHeight)
            + " ；本次导出 Y " + I(y0) + ".." + I(y1) + "\n";
    report += "[选区] 方块 X " + I(minx) + ".." + I(maxx) + ", Z " + I(minz) + ".." + I(maxz)
            + " （对应区块 x " + I(minx >> 4) + ".." + I(maxx >> 4)
            + ", z " + I(minz >> 4) + ".." + I(maxz >> 4) + "）\n";
    report += "[面剔除] " + std::string(cullDesc) + " → 本方案隐藏 " + I(culledTypes)
            + " 种技术方块（世界里没有 barrier/structure_void 这类方块时，肉眼看不出差别；要更激进请用「强剔除」）\n";
    report += "[简化] 合并共面 " + std::string(efd.chkDecimate ? "开" : "关") + "（此项以导出页开关为准）\n";
    report += "[region] " + I(scan.regionFiles) + " 个文件 / " + I(scan.totalChunks)
            + " 个区块  压缩: " + compText;
    if (scan.extraRegionDirs > 0) {
        report += " ；另有 " + I(scan.extraRegionDirs) + " 个维度 region 目录（本页只导出主世界）";
    }
    report += "\n";
    if (!scan.fileLines.empty()) report += scan.fileLines;
    else report += "    （没有可读的 region 文件）\n";
    if (scan.haveChunkBounds) {
        report += "    已存区块范围: 区块 x " + I(scan.minChunkX) + ".." + I(scan.maxChunkX)
                + ", z " + I(scan.minChunkZ) + ".." + I(scan.maxChunkZ)
                + "  →  方块 X " + I((long long)scan.minChunkX * 16) + ".." + I((long long)scan.maxChunkX * 16 + 15)
                + ", Z " + I((long long)scan.minChunkZ * 16) + ".." + I((long long)scan.maxChunkZ * 16 + 15) + "\n";
    }
    report += "[选维度] " + dimensionName(dim) + "（导出与试读都按这个维度读 region）\n";
    {
        int matMode = optInt(opts, "mat", 4);
        char fx[32];
        snprintf(fx, sizeof(fx), "0x%X", (unsigned int)opt.saveFilterFlags);
        report += std::string("[导出装配] 材质模式 mat=") + I(matMode) + "（0=不导出材质）"
                + "  saveFilterFlags=" + fx
                + "  3D打印结构位=" + I((opt.exportFlags & EXPT_3DPRINT) ? 1 : 0)
                + "  面剔除隐藏 " + I(culledTypes) + " 种\n";
        report += "    （核心放行一个方块要同时满足：类别位命中 saveFilterFlags + alpha>0 + 未被剔除；"
                  "下面 [选区实测] 里每种方块都标了具体数值与原因）\n";
    }
    if (retriedWithoutPrintOptions) {
        report += "[自动重试] 第一次导出时，3D 打印结构选项（如「删除浮动对象」会删除小于 floaterCount 且"
                  "不接地的方块组）把选区内容删光了，核心如实返回 512。已自动关闭这组选项并重试成功 —— "
                  "本次导出不含掏空/焊接等结构处理；如需这些效果，请确保模型接地且足够大后再勾选。\n";
    }
    report += "[选区命中] 覆盖 " + I(scan.coveredChunks) + " 个区块坐标，其中磁盘上存在 "
            + I(scan.presentChunks) + " 个" + (scan.selectionCapped ? "（选区过大，统计已截断）" : "") + "\n";
    if (dim != 0) {
        report += "    （注：「选区命中」统计的是主世界 region/ 的文件数，仅供参考）\n";
    }
    if (!coreProbe.empty()) {
        report += "[核心试读] 调用核心自己的读档函数（regionGetBlocks）的结果：\n" + coreProbe;
    }
    if (dim != 0 && files <= 0) {
        report += "    **维度提醒**：现在选的是" + dimensionName(dim)
                + "。如果你的建筑在别的维度，导出的就会是「另一张地图」——请到"
                  "【导出选项 → 维度】改对再导。\n";
    }
    report += "[输出] files=" + I(files) + "  err=" + I(errCode)
            + "  输出目录 " + ExportDiag::toUtf8(outDir) + "\n";
    if (files > 0) {
        report += "    写出文件:";
        for (int i = 0; i < files && i < 12; i++) {
            if (outputFileList.name[i] != NULL) {
                report += " " + ExportDiag::toUtf8(std::wstring(outputFileList.name[i]));
            }
        }
        if (files > 12) report += " …（共 " + I(files) + " 个）";
        report += "\n";
    } else {
        report += "    写出文件: 无（核心没有创建任何文件）\n";
    }
    if (errCode != 0) {
        report += "[错误码] " + I(errCode) + " " + ExportDiag::codeName(errCode) + "\n";
        report += ExportDiag::describeErrorCode(errCode) + "\n";
    }
    if (!scan.problems.empty()) {
        report += "[发现的问题]\n";
        for (size_t i = 0; i < scan.problems.size(); i++) {
            report += "    - " + scan.problems[i] + "\n";
        }
    }

    // ---- 核心自己的"回执"：写进 OBJ 注释头的生效选项与统计，用来核对界面开关是否真的到达核心 ----
    {
        std::wstring objPath(outBase);
        objPath += L".obj";
        std::string receipt = ExportDiag::readObjReceipt(ExportDiag::toUtf8(objPath), 30);
        if (!receipt.empty()) {
            report += "[核心回执 · 摘自导出的 OBJ 注释头（核心自己记录的生效状态）]\n";
            report += receipt;
        }
        // ---- 直接看"最终写进模型的到底是什么方块"：名字在＝真的导出了 ----
        if (files > 0) {
            report += "[导出内容 · 实际写进 OBJ 的材质/对象（名字在这里＝真的导出了）]\n";
            report += ExportDiag::summarizeObjContent(ExportDiag::toUtf8(objPath), 40);
            report += "    （若某个方块不在此列表：它被过滤/剔除，或压根不在选区内；在此列表却看不到，"
                      "则是查看器的材质透明度/显示设置问题）\n";

            // ---- 对等性自检：尺寸 / 缩放 / 方向（用导出的 OBJ 实测，不依赖对核心的假设）----
            report += "[对等性自检] 顶点包围盒 vs 核心声明尺寸（验证缩放 / 越界 / 方向）\n";
            report += ExportDiag::verifyObjGeometry(ExportDiag::toUtf8(objPath),
                    minx, y0, minz, maxx, y1, maxz,
                    optInt(opts, "rotate", 0),
                    optInt(opts, "zup", 0) != 0,
                    optInt(opts, "center", 0) != 0);
        }
    }

    report += "[结论] ";
    if (files > 0) {
        report += "导出成功：写出 " + I(files) + " 个文件"
                + (errCode != 0 ? ("（另有提示码 " + I(errCode) + "：" + ExportDiag::codeName(errCode) + "）") : "")
                + "。\n";
    } else if (!scan.levelDatOk) {
        report += "世界目录里没有可读的 level.dat：请选到含 level.dat 的那一层目录。\n";
    } else if (!scan.hasRegionDir && scan.extraRegionDirs == 0) {
        report += "这不是 Java 版存档结构（没有 region/ 目录）：基岩版/网易版存档需先在「转换」页转成 Java 版，再来导出。\n";
    } else if (scan.regionFiles == 0) {
        report += "region/ 目录里没有任何 .mca 文件：存档可能没复制完整。\n";
    } else if (scan.totalChunks == 0) {
        report += "所有 region 文件的偏移表都是空的：磁盘上确实没有区块数据（复制中断或存档为空）。\n";
    } else if (nonZlib > 0) {
        report += "有 " + I(nonZlib) + " 个区块不是 zlib 压缩，核心只认 zlib(=2) 会跳过它们；请用 1.20.4 或更早版本另存/转换该世界。\n";
    } else if (scan.coveredChunks > 0 && scan.presentChunks == 0) {
        report += "选区没有覆盖到任何一个已存区块：已存区块位于 区块 x " + I(scan.minChunkX) + ".." + I(scan.maxChunkX)
                + ", z " + I(scan.minChunkZ) + ".." + I(scan.maxChunkZ)
                + "（方块 X " + I((long long)scan.minChunkX * 16) + ".." + I((long long)scan.maxChunkX * 16 + 15)
                + ", Z " + I((long long)scan.minChunkZ * 16) + ".." + I((long long)scan.maxChunkZ * 16 + 15)
                + "），请把 X/Z 选区改成覆盖它。\n";
    } else if (scan.presentChunks > 0) {
        report += "选区覆盖了 " + I(scan.presentChunks) + " 个已存区块，但核心没有产出任何面。"
                  "请看上方 [核心试读] 的「落在选区内」与「方块 Y 实际范围」："
                  "若选区内方块为 0，把 Y 范围改成覆盖地形即可；若选区内确有方块却仍无输出，"
                  "则是渲染/筛选层问题，请把本报告发我。\n";
    } else {
        report += "见上方错误码与问题列表。\n";
    }

    // ---- 短提示放最前面：常见错误直接给"照做就行"的几行，超长报告仍保留在下面（并写进报错文件）----
    {
        std::string hints = ExportDiag::buildHints(scan, errCode, files,
                                                   minx, y0, minz, maxx, y1, maxz, coreProbe);
        if (!hints.empty()) {
            result += "\n[原因与处理]\n";
            result += hints;
        }
    }

    result += report;
    freeOutputFileList(outputFileList);
    return env->NewStringUTF(result.c_str());
}