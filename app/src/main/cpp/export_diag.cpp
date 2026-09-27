/*
  M2-5 · 导出诊断模块实现（移植适配层，不属于核心算法）。
  全部为只读扫描：只打开 region 文件读头部/区块头、抽样解压，不写任何文件、不改核心状态。
  另外照抄 core/region.cpp 里"核心会拒绝哪些区块"的判定条件（仅用于报告，不改变核心行为）：
    - 偏移表槽位为 0            → 核心视为"该区块不存在"
    - 压缩类型 != 2（zlib）     → 核心直接跳过（region.cpp: RERROR(buf[4] != 2)）
    - chunkLength 超过扇区大小   → 核心跳过（长度越界）
    - zlib 解压失败              → 核心返回 ERROR_INFLATE
*/
#include "stdafx.h"
#include "export_diag.h"
#include "nbt.h"       // BlockEntity / NBT_* 返回码
#include "region.h"    // regionGetBlocks —— 核心自己的读档入口
#include "blockInfo.h" // gBlockDefinitions[] —— 内置ID → 方块名（仅用于报告）
#include "CullingSchemes.h" // isBlockCulled —— 让"被过滤"判定与导出完全一致

#include <dirent.h>
#include <sys/stat.h>
#include <zlib.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <utility>
#include <vector>

namespace ExportDiag {
namespace {

const int MAX_CHUNK_SLOTS = 1024;          // 每个 region 文件 32x32 个槽位
const int REGION_HEADER = 4096;            // 偏移表字节数
const int MAX_LEVEL_PER_FILE = 4096;       // 每个文件最多逐槽检查多少个区块（超出只数头部）
const int MAX_SAMPLES_PER_FILE = 64;       // 每个文件最多抽样解压多少个区块
const int MAX_FILE_LINES = 24;             // 报告里最多列多少个 region 文件
const long long MAX_LOAD_BYTES = 32LL * 1024 * 1024;  // 超过此大小改为逐块读取

std::vector<unsigned long long> gPresentChunks;  // 排序后的 (cx,cz) 打包键

unsigned long long chunkKey(int cx, int cz) {
    return ((unsigned long long)(uint32_t)cx << 32) | (uint32_t)cz;
}

bool statUtf8(const std::string& path, bool& isDir, long long& size) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return false;
    isDir = (st.st_mode & S_IFDIR) != 0;
    size = (long long)st.st_size;
    return true;
}

std::string fmtSize(long long bytes) {
    char buf[64];
    if (bytes < 1024) snprintf(buf, sizeof(buf), "%lld B", bytes);
    else if (bytes < 1024 * 1024) snprintf(buf, sizeof(buf), "%.1f KB", bytes / 1024.0);
    else snprintf(buf, sizeof(buf), "%.1f MB", bytes / (1024.0 * 1024.0));
    return std::string(buf);
}

std::string fmtInt(long long v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%lld", v);
    return std::string(buf);
}

// 列出目录下的文件名（不含 . / ..），失败返回空
std::vector<std::string> listDir(const std::string& dir) {
    std::vector<std::string> out;
    DIR* d = opendir(dir.c_str());
    if (d == NULL) return out;
    struct dirent* e;
    while ((e = readdir(d)) != NULL) {
        const char* n = e->d_name;
        if (n == NULL) continue;
        if (n[0] == '.' && (n[1] == 0 || (n[1] == '.' && n[2] == 0))) continue;
        out.push_back(std::string(n));
    }
    closedir(d);
    std::sort(out.begin(), out.end());
    return out;
}

bool endsWith(const std::string& s, const char* suffix) {
    size_t n = strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

// 从 "r.-1.2.mca" 解出 region 坐标
bool parseRegionCoords(const std::string& name, int& rx, int& rz) {
    if (name.size() < 8 || name[0] != 'r' || name[1] != '.') return false;
    size_t dot = name.find('.', 2);
    if (dot == std::string::npos) return false;
    size_t dot2 = name.find('.', dot + 1);
    if (dot2 == std::string::npos) return false;
    char* end = NULL;
    long a = strtol(name.c_str() + 2, &end, 10);
    if (end == NULL || *end != '.') return false;
    long b = strtol(name.c_str() + dot + 1, &end, 10);
    if (end == NULL || *end != '.') return false;
    rx = (int)a;
    rz = (int)b;
    return true;
}

// 体检单个 region 文件（relPath 用于报告；baseChunkX/Z 用于建索引）
void scanRegionFile(const std::string& fullPath, const std::string& relPath,
                    int baseChunkX, int baseChunkZ, WorldScan& out,
                    RegionFileInfo& info, std::string* lineOut) {
    info.path = relPath;

    bool isDir = false;
    long long size = 0;
    if (!statUtf8(fullPath, isDir, size) || isDir) {
        info.note = "无法读取文件";
        if (out.problems.size() < 8) out.problems.push_back(relPath + "：无法读取（文件缺失或权限不足）");
        return;
    }
    info.size = size;

    // 直接以 UTF-8 路径 fopen（android_port 的宽路径入口最终也是转 UTF-8 后 fopen）
    FILE* f = fopen(fullPath.c_str(), "rb");
    if (f == NULL) {
        info.note = "打开失败";
        return;
    }

    unsigned char header[REGION_HEADER];
    if (fread(header, REGION_HEADER, 1, f) != 1) {
        info.note = "文件头不完整（不是有效的 .mca）";
        if (out.problems.size() < 8) out.problems.push_back(relPath + "：文件头不完整，不是有效的 region 文件");
        fclose(f);
        return;
    }

    // 是否整文件读入内存（小文件更快）
    std::vector<unsigned char> whole;
    if (size <= MAX_LOAD_BYTES) {
        whole.resize((size_t)size);
        fseek(f, 0, SEEK_SET);
        size_t got = fread(whole.data(), 1, (size_t)size, f);
        if (got != (size_t)size) whole.clear();
    }

    int levelChecked = 0;
    int samples = 0;
    std::vector<unsigned char> payload;

    for (int slot = 0; slot < MAX_CHUNK_SLOTS; slot++) {
        int p = slot * 4;
        int offset = (header[p] << 16) | (header[p + 1] << 8) | header[p + 2];
        int sectors = header[p + 3];
        if (offset == 0) continue;   // 空槽位：核心视为不存在

        int cx = baseChunkX + (slot & 31);
        int cz = baseChunkZ + (slot >> 5);
        info.chunks++;
        out.totalChunks++;
        gPresentChunks.push_back(chunkKey(cx, cz));
        if (!out.haveChunkBounds) {
            out.haveChunkBounds = true;
            out.minChunkX = out.maxChunkX = cx;
            out.minChunkZ = out.maxChunkZ = cz;
        } else {
            if (cx < out.minChunkX) out.minChunkX = cx;
            if (cx > out.maxChunkX) out.maxChunkX = cx;
            if (cz < out.minChunkZ) out.minChunkZ = cz;
            if (cz > out.maxChunkZ) out.maxChunkZ = cz;
        }

        if (levelChecked >= MAX_LEVEL_PER_FILE) continue;  // 超大文件只数头部
        levelChecked++;

        unsigned char head[5];
        bool haveHead = false;
        if (!whole.empty()) {
            long long at = 4096LL * offset;
            if (at + 5 <= (long long)whole.size()) {
                memcpy(head, whole.data() + at, 5);
                haveHead = true;
            }
        } else {
            if (fseek(f, (long)(4096LL * offset), SEEK_SET) == 0 && fread(head, 5, 1, f) == 1) {
                haveHead = true;
            }
        }
        if (!haveHead) {
            info.badEntry++;
            out.badEntries++;
            if (info.note.empty()) info.note = "区块数据超出文件末尾";
            continue;
        }

        long long chunkLength = ((long long)head[0] << 24) | ((long long)head[1] << 16)
                              | ((long long)head[2] << 8) | (long long)head[3];
        int comp = head[4];
        if (comp < 0) comp = 0;
        if (comp > 7) comp = 7;
        info.compression[comp]++;
        out.compression[comp]++;

        // 核心的结构校验（region.cpp 109-122）
        if (sectors <= 0 || chunkLength > (long long)sectors * 4096 || chunkLength > 1024 * 1024) {
            info.badEntry++;
            out.badEntries++;
            if (info.note.empty()) info.note = "区块长度与扇区数不符（核心会跳过）";
            continue;
        }
        if (comp != 2) {
            if (info.note.empty()) {
                info.note = "压缩类型 " + fmtInt(comp) + "（核心只支持 zlib=2，会跳过该区块）";
            }
            continue;   // 非 zlib：核心读不了，不再解压
        }

        // 抽样解压：验证 zlib 流本身是否完好
        if (samples >= MAX_SAMPLES_PER_FILE) continue;
        if (info.chunks <= 8 || samples < MAX_SAMPLES_PER_FILE) {
            long long dataLen = chunkLength - 1;
            if (dataLen <= 0) continue;
            payload.resize((size_t)dataLen);
            bool haveData = false;
            if (!whole.empty()) {
                long long at = 4096LL * offset + 5;
                if (at + dataLen <= (long long)whole.size()) {
                    memcpy(payload.data(), whole.data() + at, (size_t)dataLen);
                    haveData = true;
                }
            } else {
                if (fseek(f, (long)(4096LL * offset + 5), SEEK_SET) == 0
                        && fread(payload.data(), 1, (size_t)dataLen, f) == (size_t)dataLen) {
                    haveData = true;
                }
            }
            if (haveData) {
                // 缓冲区大小与核心一致（core/region.cpp: CHUNK_INFLATE_MAX = 20MB）：
                // 若核心会因体积解压失败，这里也同样判失败，避免"诊断说没问题、导出却失败"。
                static std::vector<unsigned char> dest;
                dest.resize(20 * 1024 * 1024);
                uLongf destLen = (uLongf)dest.size();
                int zr = uncompress(dest.data(), &destLen, payload.data(), (uLong)dataLen);
                samples++;
                info.tested++;
                out.testedChunks++;
                if (zr == Z_OK) {
                    info.inflateOk++;
                    out.inflateOk++;
                } else if (zr == Z_BUF_ERROR) {
                    info.inflateFail++;
                    out.inflateFail++;
                    if (info.note.empty()) info.note = "区块解压后超过 20MB 上限（核心同样会读取失败）";
                } else {
                    info.inflateFail++;
                    out.inflateFail++;
                    if (info.note.empty()) info.note = "zlib 解压失败（区块数据损坏或并非 zlib 封装）";
                }
            }
        }
    }

    fclose(f);

    if (lineOut != NULL) {
        std::string line = "  " + relPath + "  " + fmtSize(info.size)
                         + "  区块 " + fmtInt(info.chunks) + "  压缩:";
        bool anyComp = false;
        for (int t = 0; t < 8; t++) {
            if (info.compression[t] > 0) {
                anyComp = true;
                const char* name = (t == 1) ? "gzip" : (t == 2) ? "zlib" : (t == 3) ? "未压缩" : (t == 4) ? "LZ4" : "?";
                line += std::string(" ") + name + "×" + fmtInt(info.compression[t]);
            }
        }
        if (!anyComp) line += " 无";
        if (info.tested > 0) {
            line += "  解压抽样 " + fmtInt(info.inflateOk) + " OK/" + fmtInt(info.inflateFail) + " 失败";
        }
        if (info.badEntry > 0) line += "  结构异常 " + fmtInt(info.badEntry) + " 个";
        if (!info.note.empty()) line += "\n       <- " + info.note;
        *lineOut = line;
    }
}

void scanRegionDirectory(const std::string& dirUtf8, const std::string& label, WorldScan& out) {
    bool isDir = false;
    long long sz = 0;
    if (!statUtf8(dirUtf8, isDir, sz) || !isDir) return;

    std::vector<std::string> names = listDir(dirUtf8);
    int found = 0;
    for (size_t i = 0; i < names.size(); i++) {
        const std::string& n = names[i];
        if (!endsWith(n, ".mca")) continue;
        found++;
        out.regionFiles++;

        std::string rel = label.empty() ? n : (label + "/" + n);
        RegionFileInfo info;
        int rx = 0, rz = 0;
        if (!parseRegionCoords(n, rx, rz)) {
            // 名称异常：仍尝试扫，但区块坐标无法定位
            rx = rz = 0;
        }
        std::string line;
        scanRegionFile(dirUtf8 + "/" + n, rel, rx * 32, rz * 32, out, info,
                       out.regionFiles <= MAX_FILE_LINES ? &line : NULL);
        if (!line.empty()) {
            out.fileLines += line + "\n";
        }
    }
    if (found == 0 && out.problems.size() < 8) {
        out.problems.push_back((label.empty() ? std::string("region/") : label + "/region/")
                               + "：目录存在但没有任何 .mca 文件");
    }
}

// dimensions/<namespace>/<world>/region
void scanDimensions(const std::string& worldUtf8, WorldScan& out) {
    std::string dims = worldUtf8 + "/dimensions";
    bool isDir = false;
    long long sz = 0;
    if (!statUtf8(dims, isDir, sz) || !isDir) return;
    out.hasDimensionsDir = true;

    std::vector<std::string> ns = listDir(dims);
    for (size_t i = 0; i < ns.size(); i++) {
        std::string nsPath = dims + "/" + ns[i];
        bool d1 = false; long long s1 = 0;
        if (!statUtf8(nsPath, d1, s1) || !d1) continue;
        std::vector<std::string> worlds = listDir(nsPath);
        for (size_t j = 0; j < worlds.size(); j++) {
            std::string reg = nsPath + "/" + worlds[j] + "/region";
            bool d2 = false; long long s2 = 0;
            if (!statUtf8(reg, d2, s2) || !d2) continue;
            out.extraRegionDirs++;
            scanRegionDirectory(reg, "dimensions/" + ns[i] + "/" + worlds[j], out);
        }
    }
}

}  // namespace

std::string toUtf8(const std::wstring& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); i++) {
        unsigned int c = (unsigned int)s[i];
        if (c < 0x80) {
            out += (char)c;
        } else if (c < 0x800) {
            out += (char)(0xC0 | (c >> 6));
            out += (char)(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += (char)(0xE0 | (c >> 12));
            out += (char)(0x80 | ((c >> 6) & 0x3F));
            out += (char)(0x80 | (c & 0x3F));
        } else {
            out += (char)(0xF0 | (c >> 18));
            out += (char)(0x80 | ((c >> 12) & 0x3F));
            out += (char)(0x80 | ((c >> 6) & 0x3F));
            out += (char)(0x80 | (c & 0x3F));
        }
    }
    return out;
}

void scanWorld(const wchar_t* worldDir, int dataVersion, int releaseNumber,
               int spawnX, int spawnY, int spawnZ, WorldScan& out) {
    out = WorldScan();
    gPresentChunks.clear();

    std::wstring wdir = (worldDir != NULL) ? std::wstring(worldDir) : std::wstring();
    out.worldDir = toUtf8(wdir);
    out.dataVersion = dataVersion;
    out.releaseNumber = releaseNumber;
    out.spawnX = spawnX;
    out.spawnY = spawnY;
    out.spawnZ = spawnZ;

    if (wdir.empty()) {
        out.problems.push_back("世界目录为空");
        return;
    }

    // level.dat
    std::string levelDat = out.worldDir + "/level.dat";
    bool isDir = false;
    long long size = 0;
    if (statUtf8(levelDat, isDir, size) && !isDir) {
        out.levelDatOk = true;
    } else {
        out.levelDatOk = false;
        out.levelDatNote = "找不到 level.dat";
        out.problems.push_back("世界目录下没有 level.dat");
    }

    // region/
    std::string regionDir = out.worldDir + "/region";
    if (statUtf8(regionDir, isDir, size) && isDir) {
        out.hasRegionDir = true;
    } else {
        out.problems.push_back("世界目录下没有 region/ 目录（可能是基岩版/网易版存档，或只复制了 level.dat）");
    }

    // db/（LevelDB）—— 基岩版存档特征：能凭它把"太老/读不到"直接说成"这是基岩版，请先转换"
    std::string dbDir = out.worldDir + "/db";
    if (statUtf8(dbDir, isDir, size) && isDir) out.hasDbDir = true;

    scanRegionDirectory(regionDir, std::string(), out);
    scanRegionDirectory(out.worldDir + "/DIM1/region", "DIM1", out);
    scanRegionDirectory(out.worldDir + "/DIM-1/region", "DIM-1", out);
    scanDimensions(out.worldDir, out);

    std::sort(gPresentChunks.begin(), gPresentChunks.end());

    if (out.regionFiles > 0 && out.totalChunks == 0) {
        out.problems.push_back("所有 region 文件的偏移表都是空的：磁盘上确实没有任何区块数据");
    }
    if (out.inflateFail > 0) {
        out.problems.push_back("有 " + fmtInt(out.inflateFail) + " 个区块无法解压（数据损坏或非 zlib 封装）");
    }
    if (out.badEntries > 0) {
        out.problems.push_back("有 " + fmtInt(out.badEntries) + " 个区块的长度/扇区结构异常，核心会跳过");
    }
}

void scanSelection(const wchar_t* worldDir, int minx, int minz, int maxx, int maxz,
                   WorldScan& out) {
    (void)worldDir;
    out.coveredChunks = 0;
    out.presentChunks = 0;
    out.selectionCapped = false;

    if (maxx < minx || maxz < minz) return;

    int cx0 = minx >> 4;      // 算术右移 = 向下取整，负数同样正确
    int cx1 = maxx >> 4;
    int cz0 = minz >> 4;
    int cz1 = maxz >> 4;

    const int CAP = 200000;   // 防止超大选区把手机算死
    for (int cx = cx0; cx <= cx1; cx++) {
        for (int cz = cz0; cz <= cz1; cz++) {
            if (out.coveredChunks >= CAP) {
                out.selectionCapped = true;
                return;
            }
            out.coveredChunks++;
            unsigned long long key = chunkKey(cx, cz);
            if (std::binary_search(gPresentChunks.begin(), gPresentChunks.end(), key)) {
                out.presentChunks++;
            }
        }
    }
}

std::string codeName(int code) {
    switch (code) {
        case 0: return "MW_NO_ERROR";
        case (1 << 9): return "MW_NO_BLOCKS_FOUND";
        case (1 << 10): return "MW_ALL_BLOCKS_DELETED";
        case (1 << 11): return "MW_CANNOT_CREATE_FILE";
        case (1 << 12): return "MW_CANNOT_WRITE_TO_FILE";
        case (1 << 13): return "MW_IMAGE_WRONG_WIDTH";
        case (1 << 14): return "MW_NEED_16_ROWS";
        case (1 << 15): return "MW_DIMENSION_TOO_LARGE";
        case (1 << 16): return "MW_CANNOT_READ_IMPORT_FILE";
        case (1 << 17): return "MW_CANNOT_PARSE_IMPORT_FILE";
        case (1 << 18): return "MW_TEXTURE_TOO_LARGE";
        case (1 << 19): return "MW_WORLD_EXPORT_TOO_LARGE";
        case (1 << 20): return "MW_CANNOT_CREATE_DIRECTORY";
        case (1 << 21): return "MW_INTERNAL_ERROR";
        default: return "MW_" + fmtInt(code);
    }
}

std::string describeErrorCode(int code) {
    if (code == 0) return std::string();
    std::string out;
    struct Entry { int bit; const char* text; };
    const Entry entries[] = {
        {1 << 0,  "模型侧壁很薄，可能漏面"},
        {1 << 1,  "模型整体尺寸过小"},
        {1 << 2,  "面数过多，模型可能过大"},
        {1 << 3,  "检测到多个分组"},
        {1 << 4,  "至少一个方向尺寸过大"},
        {1 << 5,  "出现未知方块类型（该版本方块未收录，按默认处理）"},
        {1 << 6,  "纹理行数不足"},
        {1 << 7,  "方块替换命令超出范围"},
        {1 << 8,  "纹理分辨率偏高"},
        {1 << 9,  "没有找到任何方块（世界为空，或选区没有覆盖到任何已存方块）"},
        {1 << 10, "所有方块都被删除/过滤掉了"},
        {1 << 11, "无法创建输出文件"},
        {1 << 12, "无法写入输出文件"},
        {1 << 13, "纹理图片宽度不正确"},
        {1 << 14, "需要 16 行纹理"},
        {1 << 15, "模型尺寸过大"},
        {1 << 16, "无法读取导入文件"},
        {1 << 17, "无法解析导入文件"},
        {1 << 18, "纹理过大"},
        {1 << 19, "世界导出体积过大"},
        {1 << 20, "无法创建输出目录"},
        {1 << 21, "核心内部错误"},
        {1 << 22, "无法读取指定的地形纹理文件"},
        {1 << 23, "无法创建 PNG 文件"},
        {1 << 24, "某个索引过大"},
    };
    const int count = (int)(sizeof(entries) / sizeof(entries[0]));
    for (int i = 0; i < count; i++) {
        if ((code & entries[i].bit) != 0) {
            if (!out.empty()) out += "\n";
            out += "    - ";
            out += entries[i].text;
        }
    }
    if (out.empty()) {
        out = "    - 未知错误码 " + fmtInt(code);
    }
    return out;
}

std::string probeCoreRead(const std::wstring& worldDir, int mcVersion, int minHeight, int maxHeight,
                          int selMinX, int selMinY, int selMinZ,
                          int selMaxX, int selMaxY, int selMaxZ) {
    std::string out;
    if (gPresentChunks.empty()) {
        return std::string("  （世界里没有任何区块，跳过试读）\n");
    }

    std::wstring dir = worldDir;
    if (dir.empty() || dir[dir.size() - 1] != L'/') dir += L'/';

    int heightAlloc = maxHeight - minHeight + 1;
    if (heightAlloc <= 0 || heightAlloc > 4096) heightAlloc = 384;
    const size_t cells = (size_t)16 * 16 * (size_t)heightAlloc;

    std::vector<unsigned char> grid(cells);
    std::vector<unsigned short> data(cells);
    std::vector<unsigned char> light(cells / 2);
    unsigned char biome[256];

    // 优先试读"落在选区内"的区块：以前随便取最前面的三个，结果和用户选区无关，诊断不到点子上
    std::vector<unsigned long long> pick;
    for (size_t i = 0; i < gPresentChunks.size() && pick.size() < 3; i++) {
        int cx = (int)(gPresentChunks[i] >> 32);
        int cz = (int)(unsigned int)(gPresentChunks[i] & 0xFFFFFFFFu);
        int bx0 = cx * 16, bx1 = cx * 16 + 15;
        int bz0 = cz * 16, bz1 = cz * 16 + 15;
        if (bx1 >= selMinX && bx0 <= selMaxX && bz1 >= selMinZ && bz0 <= selMaxZ) {
            pick.push_back(gPresentChunks[i]);
        }
    }
    bool usingSelection = !pick.empty();
    if (!usingSelection) {
        for (size_t i = 0; i < gPresentChunks.size() && pick.size() < 3; i++) {
            pick.push_back(gPresentChunks[i]);
        }
    }

    long long totalInSel = 0;
    int probedInSel = 0;
    bool haveY = false;
    int worldMinY = 0, worldMaxY = 0;

    // ---- 读档实测汇总：用"核心自己读到的方块"回答"导出的 OBJ 跟地图不一样"到底出在哪一层 ----
    // 统计口径 = 选区内（selMinX..selMaxX / selMinY..selMaxY / selMinZ..selMaxZ）的非空气方块。
    long long typeCounts[256];
    for (int i = 0; i < 256; i++) typeCounts[i] = 0;
    long long yBand[24];                       // 每 16 格一层（最多 24 层 = 384 格）
    for (int i = 0; i < 24; i++) yBand[i] = 0;
    const int MAPW = 48, MAPH = 48;            // 俯视高度图（把选区缩到 48x48 个字符）
    int topY[MAPW * MAPH];
    for (int i = 0; i < MAPW * MAPH; i++) topY[i] = -100000;
    long long selTotal = 0;

    for (size_t i = 0; i < pick.size(); i++) {
        int cx = (int)(pick[i] >> 32);
        int cz = (int)(unsigned int)(pick[i] & 0xFFFFFFFFu);

        std::fill(grid.begin(), grid.end(), (unsigned char)0);
        std::fill(data.begin(), data.end(), (unsigned short)0);
        std::fill(light.begin(), light.end(), (unsigned char)0);
        memset(biome, 0, sizeof(biome));

        BlockEntity entities[400];
        int numEntities = 0;
        int mfsHeight = 0;
        char unknownBlock[256];
        unknownBlock[0] = 0;

        int code = regionGetBlocks(&dir[0], cx, cz, grid.data(), data.data(), light.data(),
                                   biome, entities, &numEntities, mcVersion,
                                   minHeight, maxHeight, mfsHeight, unknownBlock, 7);

        long long nonAir = 0, inSel = 0;
        int firstIdx = -1;
        bool chunkY = false;
        int chunkMinY = 0, chunkMaxY = 0;
        for (size_t k = 0; k < cells; k++) {
            if (grid[k] == 0) continue;
            nonAir++;
            if (firstIdx < 0) firstIdx = (int)k;
            int x = (int)(k % 16);
            int z = (int)((k / 16) % 16);
            int y = minHeight + (int)(k / 256);
            if (!chunkY) {
                chunkMinY = chunkMaxY = y;
                chunkY = true;
            } else {
                if (y < chunkMinY) chunkMinY = y;
                if (y > chunkMaxY) chunkMaxY = y;
            }
            int wx = cx * 16 + x;
            int wz = cz * 16 + z;
            if (wx >= selMinX && wx <= selMaxX && y >= selMinY && y <= selMaxY &&
                wz >= selMinZ && wz <= selMaxZ) {
                inSel++;
                // 实测汇总（只统计选区内，对应"用户真正想导的那块地"）
                typeCounts[grid[k]]++;
                int band = (y - minHeight) / 16;
                if (band >= 0 && band < 24) yBand[band]++;
                selTotal++;
                long long spanX = (long long)selMaxX - selMinX + 1;
                long long spanZ = (long long)selMaxZ - selMinZ + 1;
                int gx = (int)(((long long)(wx - selMinX) * MAPW) / (spanX > 0 ? spanX : 1));
                int gz = (int)(((long long)(wz - selMinZ) * MAPH) / (spanZ > 0 ? spanZ : 1));
                if (gx >= 0 && gx < MAPW && gz >= 0 && gz < MAPH) {
                    int& slot = topY[gz * MAPW + gx];
                    if (y > slot) slot = y;
                }
            }
        }
        if (chunkY) {
            if (!haveY) {
                worldMinY = chunkMinY;
                worldMaxY = chunkMaxY;
                haveY = true;
            } else {
                if (chunkMinY < worldMinY) worldMinY = chunkMinY;
                if (chunkMaxY > worldMaxY) worldMaxY = chunkMaxY;
            }
        }
        if (usingSelection) {
            totalInSel += inSel;
            probedInSel++;
        }

        out += "  试读 区块(" + fmtInt(cx) + "," + fmtInt(cz) + ")"
             + (usingSelection ? "（在选区内）" : "（不在选区内，仅供参考）")
             + " → 返回码 " + fmtInt(code);
        // nbt.h：低 2 位是基础结果（0=有效但空 / 1=有效且有方块 / 2=没有 sections），高位是警告位
        const int base = code & 0x3;
        if (code == ERROR_INFLATE) out += "（ERROR_INFLATE：解压失败）";
        else if (base == NBT_NO_SECTIONS) out += "（NBT_NO_SECTIONS：区块里没有 sections 数据）";
        else if (base == NBT_VALID_BLOCK) out += "（NBT_VALID_BLOCK：读到了方块数据）";
        else if (base == NBT_VALID_BUT_EMPTY) out += "（有效但为空）";
        else if (code <= 0) out += "（核心判定读取失败）";
        if ((code & NBT_WARNING_NAME_NOT_FOUND) != 0) out += " 含未知方块名警告";
        if ((code & NBT_WARNING_DIRECTORY_NOT_FOUND) != 0) out += " 含目录未找到警告";
        out += "\n     非空气方块(全高)=" + fmtInt(nonAir);
        if (chunkY) out += "  方块 Y 实际范围=" + fmtInt(chunkMinY) + ".." + fmtInt(chunkMaxY);
        if (usingSelection) out += "  落在选区内=" + fmtInt(inSel);
        if (firstIdx >= 0) {
            int x = firstIdx % 16;
            int z = (firstIdx / 16) % 16;
            int y = minHeight + firstIdx / 256;
            out += "  首个非空气: 方块(" + fmtInt(cx * 16 + x) + "," + fmtInt(y) + "," + fmtInt(cz * 16 + z) + ")"
                 + " 内部ID=" + fmtInt(grid[firstIdx]);
        }
        if (mfsHeight != 0) out += "  mfsHeight=" + fmtInt(mfsHeight);
        if (unknownBlock[0] != 0) out += std::string("  未知方块=") + unknownBlock;
        out += "\n";
    }

    if (usingSelection && selTotal > 0) {
        out += "\n  [选区实测] 以下是**核心实际读到的内容**（把它跟游戏里的地图对照，就能判断差异出在哪一层）：\n";

        // 1) 方块类型 Top 8（用内置的 ID→名字表，读到的到底是什么方块一眼可见）
        out += "    方块类型 Top 8：";
        {
            bool used[256];
            for (int i = 0; i < 256; i++) used[i] = false;
            std::string list;
            for (int n = 0; n < 8; n++) {
                int best = -1;
                long long bestCount = 0;
                for (int i = 0; i < 256; i++) {
                    if (!used[i] && typeCounts[i] > bestCount) { best = i; bestCount = typeCounts[i]; }
                }
                if (best < 0 || bestCount <= 0) break;
                used[best] = true;
                const char* nm = "?";
                if (best < NUM_BLOCKS_DEFINED && gBlockDefinitions[best].name) nm = gBlockDefinitions[best].name;
                // 逐条核对该方块会不会被留下：与核心导出时**完全同一套**判定
                //   (flags & saveFilterFlags) && alpha > 0 && !isBlockCulled(type, dataVal)
                // 报告里给出具体数值，出错时一眼看出是哪个条件挡的。
                unsigned int fl = 0;
                float al = 0.0f;
                bool keptByFilter = true, keptByAlpha = true, culled = false;
                if (best < NUM_BLOCKS_DEFINED) {
                    fl = (unsigned int)gBlockDefinitions[best].flags;
                    al = gBlockDefinitions[best].alpha;
                    const unsigned int CLASS_MASK = BLF_WHOLE | BLF_ALMOST_WHOLE | BLF_STAIRS | BLF_HALF |
                            BLF_MIDDLER | BLF_BILLBOARD | BLF_PANE | BLF_FLATTEN | BLF_FLATTEN_SMALL |
                            BLF_SMALL_MIDDLER | BLF_SMALL_BILLBOARD;
                    keptByFilter = (fl & CLASS_MASK) != 0;
                    keptByAlpha = al > 0.0f;
                    culled = isBlockCulled(best, 0);
                }
                char hexbuf[32];
                snprintf(hexbuf, sizeof(hexbuf), "0x%X", fl);
                char alphaBuf[32];
                snprintf(alphaBuf, sizeof(alphaBuf), "%.3f", al);
                std::string verdict;
                if (keptByFilter && keptByAlpha && !culled) {
                    verdict = "[会导出]";
                } else {
                    verdict = "[被过滤：";
                    if (!keptByFilter) verdict += "类别位不在保存过滤内 ";
                    if (!keptByAlpha) verdict += "alpha<=0 ";
                    if (culled) verdict += "被剔除方案隐藏 ";
                    verdict += "]";
                }
                if (!list.empty()) list += " ；";
                list += std::string(nm) + " ×" + fmtInt(bestCount) + verdict
                        + "(flags=" + hexbuf + " alpha=" + alphaBuf + ")";
            }
            out += list.empty() ? std::string("（无）\n") : (list + "\n");
        }

        // 2) Y 分层：能立刻看出地形地面在第几层、建筑在哪几层
        out += "    Y 分层（每 16 格）：";
        {
            std::string bands;
            for (int b = 0; b < 24; b++) {
                if (yBand[b] <= 0) continue;
                int lo = minHeight + b * 16;
                if (!bands.empty()) bands += "  ";
                bands += "Y " + fmtInt(lo) + ".." + fmtInt(lo + 15) + " = " + fmtInt(yBand[b]);
            }
            out += bands.empty() ? std::string("（无）\n") : (bands + "\n");
        }

        // 3) 俯视高度图：形状对了，说明读档没问题，差异在导出映射/材质一侧
        out += "    俯视高度图（每格=该列最高方块；' '=无  '.'<0  ':'0-63  'o'64-127  '#'128-191  '@'>=192）：\n";
        for (int gz = 0; gz < MAPH; gz++) {
            std::string row = "      ";
            for (int gx = 0; gx < MAPW; gx++) {
                int h = topY[gz * MAPW + gx];
                char c = ' ';
                if (h > -100000) {
                    if (h < 0) c = '.';
                    else if (h < 64) c = ':';
                    else if (h < 128) c = 'o';
                    else if (h < 192) c = '#';
                    else c = '@';
                }
                row += c;
            }
            out += row + "\n";
        }
        out += "    （形状与游戏内一致 → 读档正确，问题在导出映射/材质；不一致 → 就是读档这一层）\n";
    }

    if (usingSelection) {
        out += "  → 选区内试读 " + fmtInt(probedInSel) + " 个区块，选区内非空气方块合计 "
             + fmtInt(totalInSel) + " 个\n";
        if (totalInSel == 0) {
            out += "     **选区内一个方块都没有**：几乎可以肯定是 Y 范围没盖住地形";
            if (haveY) {
                out += "（这些区块的方块只在 Y " + fmtInt(worldMinY) + ".." + fmtInt(worldMaxY) + " 出现）";
            }
            out += "，把 Y 范围改成覆盖它即可。\n";
        }
    }
    return out;
}

// ---- 短提示：把常见失败原因压成"照做就行"的几行 ----
namespace {
// 从任意文本里取 "标记=N" 之后的整数（找不到返回 -1）
long long pickNumber(const std::string& text, const char* marker) {
    size_t p = text.find(marker);
    if (p == std::string::npos) return -1;
    p += strlen(marker);
    long long v = 0;
    bool any = false;
    while (p < text.size() && text[p] >= '0' && text[p] <= '9') {
        v = v * 10 + (text[p] - '0');
        p++;
        any = true;
    }
    return any ? v : -1;
}
// 从 "方块 Y 实际范围=a..b" 里取 a 与 b
void pickYRange(const std::string& text, long long& lo, long long& hi) {
    lo = hi = -1;
    size_t p = text.find("方块 Y 实际范围=");
    if (p == std::string::npos) return;
    p += strlen("方块 Y 实际范围=");
    std::string a, b;
    while (p < text.size() && text[p] != '.' && text[p] != ' ') { a += text[p]; p++; }
    if (p < text.size() && text[p] == '.') p++;
    if (p < text.size() && text[p] == '.') p++;
    while (p < text.size() && text[p] != ' ' && text[p] != '\n' && text[p] != '(') { b += text[p]; p++; }
    if (!a.empty()) lo = atoll(a.c_str());
    if (!b.empty()) hi = atoll(b.c_str());
}
std::string L(long long v) {
    char b[32];
    snprintf(b, sizeof(b), "%lld", v);
    return std::string(b);
}
}  // namespace

std::string buildHints(const WorldScan& scan, int errCode, int files,
                       int selMinX, int selMinY, int selMinZ,
                       int selMaxX, int selMaxY, int selMaxZ,
                       const std::string& probeText) {
    if (files > 0) return std::string();   // 成功就不需要提示

    std::string out;
    const char* B = "  • ";

    // 1) 世界本身的问题（按"最可能挡住导出"的顺序）
    if (!scan.levelDatOk) {
        out += std::string(B) + "世界目录里没有可读的 level.dat：请选到**含 level.dat 的那一层**文件夹（不是它的上级目录）。\n";
        out += std::string(B) + "当前目录：" + scan.worldDir + "\n";
        return out;
    }
    if (!scan.hasRegionDir && scan.extraRegionDirs == 0) {
        out += scan.hasDbDir
            ? std::string(B) + "检测到 db/ 目录：这是**基岩版存档**，请先到「转换」页转成 Java 版，再来导出。\n"
            : std::string(B) + "没有 region/ 目录：这不是 Java 版存档结构（或存档没复制完整）。\n";
        out += std::string(B) + "当前目录：" + scan.worldDir + "\n";
        return out;
    }
    if (scan.regionFiles == 0) {
        out += std::string(B) + "region/ 里没有任何 .mca 文件：存档没复制完整，请重新复制整个存档文件夹。\n";
        return out;
    }
    if (scan.totalChunks == 0) {
        out += std::string(B) + "所有 region 的区块表都是空的：这个存档磁盘上确实没有区块数据（复制中断或存档本身为空）。\n";
        return out;
    }
    if (scan.inflateFail > 0) {
        out += std::string(B) + "有 " + L(scan.inflateFail) + " 个区块解压失败：存档文件损坏，请重新复制/重新转换存档。\n";
    }
    {
        long long nonZlib = 0;
        for (int t = 1; t < 8; t++) {
            if (t != 2) nonZlib += scan.compression[t];
        }
        if (nonZlib > 0) {
            out += std::string(B) + "有 " + L(nonZlib) + " 个区块不是 zlib 压缩：本核心只认 zlib，"
                   "请用 1.20.4 或更早版本另存（或先用「转换」页转一次）再导出。\n";
        }
    }

    // 2) 选区问题（决定性的两项：选区内方块数、方块真实 Y 范围）
    const long long inSel = pickNumber(probeText, "选区内非空气方块合计 ");
    long long yLo = -1, yHi = -1;
    pickYRange(probeText, yLo, yHi);

    if (scan.coveredChunks > 0 && scan.presentChunks == 0) {
        out += std::string(B) + "选区没有覆盖到任何已存区块：已存区块在 "
             + "方块 X " + L((long long)scan.minChunkX * 16) + ".." + L((long long)scan.maxChunkX * 16 + 15)
             + "，Z " + L((long long)scan.minChunkZ * 16) + ".." + L((long long)scan.maxChunkZ * 16 + 15)
             + "；请把 X/Z 改成覆盖它。\n";
        return out;
    }
    if (inSel == 0) {
        out += std::string(B) + "选区里一个方块都没有：这是本次导出 0 面的直接原因。\n";
        if (yLo >= 0 && yHi >= 0) {
            if (yLo == yHi) {
                out += std::string(B) + "这个存档的方块只在 **Y=" + L(yLo) + "** 这一层（像测试世界/只有基岩层）；"
                       "请把 Y 改成 " + L(yLo) + ".." + L(yLo + 4) + " 再试。\n";
            } else {
                out += std::string(B) + "该存档的方块分布在 **Y " + L(yLo) + ".." + L(yHi) + "**；"
                       "你填的是 Y " + L(selMinY) + ".." + L(selMaxY) + "，请改成与它重叠的范围。\n";
            }
        } else {
            out += std::string(B) + "请把 Y 范围改成覆盖地形的高度（例如 Y -64..80），X/Z 也要落在已存区块内。\n";
        }
        return out;
    }
    if (inSel > 0) {
        out += std::string(B) + "选区内有 " + L(inSel) + " 个方块，但没有生成任何面：\n";
        out += std::string(B) + "① 若开了「掏空/超中空/删除浮动对象/焊接」等 3D 打印选项，可能把内容整体删掉了 —— 先全部关掉再试；\n";
        out += std::string(B) + "② 若仍为 0，这些方块多为本核心未收录的类型（报告里搜「未知方块」看具体名字）。\n";
        return out;
    }

    // 3) 其他致命码：给出中文名 + 通用处理
    if (errCode != 0 && errCode != (1 << 9)) {
        out += std::string(B) + "错误码 " + L(errCode) + " " + codeName(errCode) + "：详情见下方完整报告的错误码解读。\n";
        out += std::string(B) + "若是内存/体积类错误，请缩小选区（尤其降低 Y 范围）后再试。\n";
    }
    return out;
}

std::string readObjReceipt(const std::string& objPathUtf8, int maxLines) {
    std::string out;
    FILE* f = fopen(objPathUtf8.c_str(), "rb");
    if (f == NULL) return out;
    char line[512];
    int kept = 0;
    bool started = false;
    while (fgets(line, sizeof(line), f) != NULL) {
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
        if (line[0] == '#') {
            started = true;
            out += "    ";
            out += line;
            out += "\n";
            if (maxLines > 0 && ++kept >= maxLines) break;
        } else if (n > 0) {
            // 遇到第一条非注释的非空行：注释头结束（头部注释之间的空行会被跳过）
            break;
        }
    }
    fclose(f);
    return out;
}

std::string summarizeObjContent(const std::string& objPathUtf8, int maxNames) {
    // 只读扫描导出的 OBJ：统计 v/f 行数，并列出所有 usemtl（材质）与 o/g（对象/组）名字及出现次数。
    // 用途：直接回答"某个方块到底有没有写进模型"——
    //   名字在 → 已经导出了（看不到就是显示/材质/透明度一侧的问题）；
    //   名字不在 → 它被过滤/剔除，或者压根不在选区内。
    std::string out;
    FILE* f = fopen(objPathUtf8.c_str(), "rb");
    if (f == NULL) {
        return std::string("  （读不到 OBJ 文件：") + objPathUtf8 + "）\n";
    }

    std::vector<std::pair<std::string, long long> > matNames, objNames;
    long long verts = 0, faces = 0, matLines = 0, objLines = 0, bytes = 0;
    // 超大 OBJ 只扫前 24MB：这份统计只是给报告看的"抽样"，扫全文件在大导出（几百 MB）时
    // 会白白多花数秒到数十秒（逐行 fgets + 字符串比较），24MB 已足够反映材质/对象清单。
    const long long SCAN_CAP = 24LL * 1024 * 1024;
    bool truncated = false;

    char line[1024];
    while (fgets(line, sizeof(line), f) != NULL) {
        bytes += (long long)strlen(line);
        if (bytes > SCAN_CAP) { truncated = true; break; }
        if (line[0] == 'v' && line[1] == ' ') { verts++; continue; }
        if (line[0] == 'f' && line[1] == ' ') { faces++; continue; }
        bool isMat = (strncmp(line, "usemtl ", 7) == 0);
        bool isObj = ((line[0] == 'o' || line[0] == 'g') && line[1] == ' ');
        if (!isMat && !isObj) continue;

        std::string name = line + (isMat ? 7 : 2);
        while (!name.empty() && (name[name.size() - 1] == '\n' || name[name.size() - 1] == '\r'
                                 || name[name.size() - 1] == ' ')) {
            name.erase(name.size() - 1);
        }
        if (name.empty()) continue;
        std::vector<std::pair<std::string, long long> >& list = isMat ? matNames : objNames;
        if (isMat) matLines++; else objLines++;
        bool found = false;
        for (size_t i = 0; i < list.size(); i++) {
            if (list[i].first == name) { list[i].second++; found = true; break; }
        }
        if (!found && list.size() < 600) list.push_back(std::make_pair(name, 1LL));
    }
    fclose(f);

    struct ByCount {
        bool operator()(const std::pair<std::string, long long>& a,
                        const std::pair<std::string, long long>& b) const {
            return a.second > b.second;
        }
    };
    std::sort(matNames.begin(), matNames.end(), ByCount());
    std::sort(objNames.begin(), objNames.end(), ByCount());

    out += "    顶点 " + fmtInt(verts) + " · 面 " + fmtInt(faces)
         + " · usemtl 行 " + fmtInt(matLines) + " · o/g 行 " + fmtInt(objLines)
         + (truncated ? "（文件过大，只扫了前 200MB）" : "") + "\n";

    out += "    材质种类 " + fmtInt((long long)matNames.size()) + " 种";
    if (!matNames.empty()) {
        out += "（按使用次数）：\n";
        int n = maxNames > 0 ? maxNames : 40;
        for (size_t i = 0; i < matNames.size() && (int)i < n; i++) {
            out += "        " + matNames[i].first + "  ×" + fmtInt(matNames[i].second) + "\n";
        }
        if ((int)matNames.size() > n) {
            out += "        …（还有 " + fmtInt((long long)matNames.size() - n) + " 种，见 OBJ 本身）\n";
        }
    } else {
        out += "（没有 usemtl：多半是选了「不导出材质」，几何仍在）\n";
    }

    // 顺便替用户找一下玻璃：名字里含 glass 的都列出来（大小写不敏感）
    std::string glass;
    for (size_t i = 0; i < matNames.size(); i++) {
        std::string low = matNames[i].first;
        for (size_t c = 0; c < low.size(); c++) low[c] = (char)tolower((unsigned char)low[c]);
        if (low.find("glass") != std::string::npos) {
            glass += "        " + matNames[i].first + "  ×" + fmtInt(matNames[i].second) + "\n";
        }
    }
    if (!glass.empty()) {
        out += "    → 名字里含 glass 的材质（说明玻璃确实导出了）：\n" + glass;
    } else if (!matNames.empty()) {
        out += "    → 没有任何名字含 glass 的材质：玻璃没被写进这个 OBJ。\n";
    }
    return out;
}

std::string verifyObjGeometry(const std::string& objPathUtf8,
                              int selMinX, int selMinY, int selMinZ,
                              int selMaxX, int selMaxY, int selMaxZ,
                              int rotateDeg, bool zUp, bool centered) {
    std::string out;
    FILE* f = fopen(objPathUtf8.c_str(), "rb");
    if (f == NULL) return std::string("    （读不到 OBJ 文件，跳过自检）\n");

    long long declaredX = -1, declaredY = -1, declaredZ = -1;
    long long vertsDeclared = -1, facesDeclared = -1, blocksDeclared = -1;
    double declaredScale = -1.0;

    double mnx = 0, mny = 0, mnz = 0, mxx = 0, mxy = 0, mxz = 0;
    bool anyV = false;
    long long vCount = 0, bytes = 0;
    bool truncated = false;
    const long long CAP = 200LL * 1024 * 1024;

    char line[1024];
    while (fgets(line, sizeof(line), f) != NULL) {
        bytes += (long long)strlen(line);
        if (bytes > CAP) { truncated = true; break; }

        if (line[0] == '#') {
            long long a = 0, b = 0, c = 0;
            if (declaredX < 0 && sscanf(line, "# block dimensions: X=%lld by Y=%lld by Z=%lld", &a, &b, &c) == 3) {
                declaredX = a; declaredY = b; declaredZ = c;
            } else if (vertsDeclared < 0 && sscanf(line, "# %lld vertices, %lld faces", &a, &b) == 2) {
                vertsDeclared = a; facesDeclared = b;
                const char* p = strstr(line, " blocks");
                if (p != NULL) {
                    const char* q = p;
                    while (q > line && (*(q - 1) >= '0' && *(q - 1) <= '9')) q--;
                    if (q < p) blocksDeclared = atoll(q);
                }
            } else if (declaredScale < 0.0) {
                double sc = 0.0;
                if (sscanf(line, "# block_scale: %lf", &sc) == 1) declaredScale = sc;
            }
            continue;
        }
        if (line[0] == 'v' && line[1] == ' ') {
            double x = 0, y = 0, z = 0;
            if (sscanf(line + 2, "%lf %lf %lf", &x, &y, &z) == 3) {
                if (!anyV) { mnx = mxx = x; mny = mxy = y; mnz = mxz = z; anyV = true; }
                else {
                    if (x < mnx) mnx = x;
                    if (x > mxx) mxx = x;
                    if (y < mny) mny = y;
                    if (y > mxy) mxy = y;
                    if (z < mnz) mnz = z;
                    if (z > mxz) mxz = z;
                }
                vCount++;
            }
        }
    }
    fclose(f);

    if (!anyV) {
        out += "    （OBJ 里没有任何 v 行：模型为空，无法自检）\n";
        return out;
    }

    char nb[64];
    const double sx = mxx - mnx, sy = mxy - mny, sz = mxz - mnz;
    snprintf(nb, sizeof(nb), "%.3f × %.3f × %.3f", sx, sy, sz);
    out += std::string("    OBJ 实测：") + fmtInt(vCount) + " 个 v 行，包围盒 " + nb + "（单位）\n";

    if (declaredX > 0) {
        snprintf(nb, sizeof(nb), "X=%lld by Y=%lld by Z=%lld", declaredX, declaredY, declaredZ);
        out += std::string("    核心声明（OBJ 注释头）：block dimensions ") + nb + " 方块";
        if (blocksDeclared > 0) out += "，共 " + fmtInt(blocksDeclared) + " 个方块";
        if (facesDeclared > 0) out += "，" + fmtInt(facesDeclared) + " 个面";
        if (declaredScale > 0.0) {
            snprintf(nb, sizeof(nb), "%.6g", declaredScale);
            out += std::string("；block_scale=") + nb;
        }
        out += "\n";
    }

    // ① 尺寸 / 缩放：逐轴比较（启用旋转或 Z-up 时只比"排序后的三边"）
    const double ex[3] = {(double)declaredX, (double)declaredY, (double)declaredZ};
    const double gx[3] = {sx, sy, sz};
    const bool sizeKnown = declaredX > 0 && declaredY > 0 && declaredZ > 0;
    if (sizeKnown) {
        if (rotateDeg != 0 || zUp) {
            double a[3] = {gx[0], gx[1], gx[2]}, b[3] = {ex[0], ex[1], ex[2]};
            std::sort(a, a + 3);
            std::sort(b, b + 3);
            double r0 = b[0] > 0 ? a[0] / b[0] : 0, r1 = b[1] > 0 ? a[1] / b[1] : 0, r2 = b[2] > 0 ? a[2] / b[2] : 0;
            snprintf(nb, sizeof(nb), "%.3f / %.3f / %.3f", r0, r1, r2);
            const bool ok = (r0 > 0.995 && r0 < 1.005) && (r1 > 0.995 && r1 < 1.005) && (r2 > 0.995 && r2 < 1.005);
            out += std::string("    → 缩放比（已按旋转/Z-up 换轴比较）：") + nb
                 + (ok ? "  ✓ 1 单位 = 1 方块，未被缩放\n" : "  ✗ 与声明尺寸不符，模型可能被缩放！\n");
        } else {
            const double r0 = ex[0] > 0 ? sx / ex[0] : 0;
            const double r1 = ex[1] > 0 ? sy / ex[1] : 0;
            const double r2 = ex[2] > 0 ? sz / ex[2] : 0;
            snprintf(nb, sizeof(nb), "%.3f / %.3f / %.3f", r0, r1, r2);
            const bool ok = (r0 > 0.995 && r0 < 1.005) && (r1 > 0.995 && r1 < 1.005) && (r2 > 0.995 && r2 < 1.005);
            out += std::string("    → 缩放比（X/Y/Z）：") + nb
                 + (ok ? "  ✓ 1 单位 = 1 方块，未被缩放\n" : "  ✗ 与声明尺寸不符，模型可能被缩放！\n");
        }
    }

    // ② 是否超出选区
    const double selSpanX = selMaxX - selMinX + 1;
    const double selSpanY = selMaxY - selMinY + 1;
    const double selSpanZ = selMaxZ - selMinZ + 1;
    if (sizeKnown) {
        double got[3] = {gx[0], gx[1], gx[2]}, want[3] = {selSpanX, selSpanY, selSpanZ};
        std::sort(got, got + 3);
        std::sort(want, want + 3);
        const bool within = got[0] <= want[0] + 0.001 && got[1] <= want[1] + 0.001 && got[2] <= want[2] + 0.001;
        snprintf(nb, sizeof(nb), "%.0f × %.0f × %.0f", selSpanX, selSpanY, selSpanZ);
        out += std::string("    → 选区范围：") + nb + " 方块；模型"
             + (within ? "未超出选区 ✓\n" : "大于选区 ✗（可能坐标/范围被改写）\n");
    }

    // ③ 方向：绝对值 OBJ 的 X 轴在 Mineways 里是镜像的（与游戏内左右相反）。
    // 只在未启用居中/旋转/Z-up 时判定，避免误报。
    if (sizeKnown && rotateDeg == 0 && !zUp && !centered) {
        const double tol = 1.5;
        const bool xSame = fabs(mnx - selMinX) <= tol;
        const bool xMirror = fabs(mxx - (-(double)selMinX)) <= tol || fabs(mnx - (-(double)(selMaxX + 1))) <= tol;
        const bool sameWithin = fabs(mnx - selMinX) <= tol && fabs(mxx - (selMaxX + 1)) <= tol;
        const bool mirrorWithin = fabs(mnx - (-(double)(selMaxX + 1))) <= tol && fabs(mxx - (-(double)selMinX)) <= tol;
        snprintf(nb, sizeof(nb), "X %.1f..%.1f  Y %.1f..%.1f  Z %.1f..%.1f", mnx, mxx, mny, mxy, mnz, mxz);
        out += std::string("    → 坐标范围：") + nb + "\n";
        if (mirrorWithin) {
            out += "    → 方向判定：X 轴镜像（Mineways 绝对坐标 OBJ 的一贯约定，等于站在北面看你世界的左右关系）；"
                   "Y/Z 与世界坐标一致 ✓\n";
        } else if (sameWithin) {
            out += "    → 方向判定：X/Y/Z 与世界坐标同向 ✓\n";
        } else {
            out += std::string("    → 方向判定：起点与选区不吻合（X 同向？")
                 + (xSame ? "是" : "否") + "，X 镜像？" + (xMirror ? "是" : "否")
                 + "）——若你改过选区或用了「居中/旋转/Z-up」，此项可忽略\n";
        }
    } else if (rotateDeg != 0 || zUp || centered) {
        out += "    → 方向判定：已启用居中 / 旋转 / Z-up，方向自检跳过（只校验尺寸与缩放）\n";
    }

    if (truncated) out += "    （文件超过 200MB，以上为前 200MB 的统计）\n";
    return out;
}

}  // namespace ExportDiag
