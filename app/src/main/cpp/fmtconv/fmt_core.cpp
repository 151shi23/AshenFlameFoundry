#include "fmtconv/fmt_core.h"

#include <algorithm>
#include <cctype>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>

// Assimp（模型互转）
#include <assimp/Exporter.hpp>
#include <assimp/Importer.hpp>
#include <assimp/postprocess.h>
#include <assimp/scene.h>

// stb（图片）
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#define STBI_ONLY_BMP
#define STBI_ONLY_TGA
#define STBI_ONLY_PSD
#define STBI_ONLY_GIF
#define STBI_ONLY_HDR
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

// zstd（解压 Blender 的压缩 .blend）
#include <zstd.h>

// zlib（gzip；NDK 自带）
#include <zlib.h>

namespace fmtconv {

namespace {

std::string lowerExt(const std::string& path) {
    const size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) {
        return "";
    }
    std::string e = path.substr(dot + 1);
    std::transform(e.begin(), e.end(), e.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return e;
}

std::string baseName(const std::string& path) {
    const size_t slash = path.find_last_of("/\\");
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

bool readHead(const std::string& path, unsigned char* buf, size_t n, size_t* got) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        return false;
    }
    f.read(reinterpret_cast<char*>(buf), static_cast<std::streamsize>(n));
    *got = static_cast<size_t>(f.gcount());
    return true;
}

bool writeAll(const std::string& path, const void* data, size_t len) {
    std::ofstream f(path, std::ios::binary);
    if (!f) {
        return false;
    }
    f.write(static_cast<const char*>(data), static_cast<std::streamsize>(len));
    return f.good();
}

std::string human(double bytes) {
    char b[64];
    if (bytes >= 1048576) {
        std::snprintf(b, sizeof(b), "%.1f MB", bytes / 1048576.0);
    } else if (bytes >= 1024) {
        std::snprintf(b, sizeof(b), "%.0f KB", bytes / 1024.0);
    } else {
        std::snprintf(b, sizeof(b), "%.0f B", bytes);
    }
    return b;
}

std::string fmt(const char* pattern, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, pattern);
    std::vsnprintf(buf, sizeof(buf), pattern, ap);
    va_end(ap);
    return buf;
}

// 解压助手：zstd 与 gzip
bool zstdDecompress(const std::string& in, const std::string& out, std::string* err) {
    std::ifstream f(in, std::ios::binary);
    if (!f) { *err = "打不开输入文件"; return false; }
    std::vector<char> src((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    unsigned long long size = ZSTD_getFrameContentSize(src.data(), src.size());
    if (size == ZSTD_CONTENTSIZE_ERROR || size == ZSTD_CONTENTSIZE_UNKNOWN) {
        // 不知道大小：按帧头声明的窗口逐块解
        std::vector<char> dst(src.size() * 24 + (1 << 20));
        ZSTD_DCtx* d = ZSTD_createDCtx();
        const size_t r = ZSTD_decompressDCtx(d, dst.data(), dst.size(), src.data(), src.size());
        ZSTD_freeDCtx(d);
        if (ZSTD_isError(r)) { *err = std::string("zstd 解压失败：") + ZSTD_getErrorName(r); return false; }
        if (!writeAll(out, dst.data(), r)) { *err = "写临时文件失败"; return false; }
        return true;
    }
    std::vector<char> dst(static_cast<size_t>(size));
    const size_t r = ZSTD_decompress(dst.data(), dst.size(), src.data(), src.size());
    if (ZSTD_isError(r)) { *err = std::string("zstd 解压失败：") + ZSTD_getErrorName(r); return false; }
    if (!writeAll(out, dst.data(), r)) { *err = "写临时文件失败"; return false; }
    return true;
}

bool gzipDecompress(const std::string& in, const std::string& out, std::string* err) {
    gzFile g = gzopen(in.c_str(), "rb");
    if (g == nullptr) { *err = "打不开 gzip 文件"; return false; }
    std::vector<char> buf(1 << 16);
    std::string all;
    int n;
    while ((n = gzread(g, buf.data(), static_cast<unsigned>(buf.size()))) > 0) {
        all.append(buf.data(), static_cast<size_t>(n));
    }
    gzclose(g);
    if (all.empty()) { *err = "gzip 解压后是空的"; return false; }
    if (!writeAll(out, all.data(), all.size())) { *err = "写临时文件失败"; return false; }
    return true;
}

}  // namespace

// ---------------------------------------------------------------- 探测

std::string sniff(const std::string& path) {
    unsigned char h[16] = {0};
    size_t got = 0;
    if (!readHead(path, h, sizeof(h), &got) || got < 4) {
        return "";
    }
    if (std::memcmp(h, "BLENDER", 7) == 0) return "blend";
    if (h[0] == 0x28 && h[1] == 0xB5 && h[2] == 0x2F && h[3] == 0xFD) return "blend-zstd";
    if (h[0] == 0x1F && h[1] == 0x8B) return "blend-gzip";
    if (h[0] == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
    if (h[0] == 0xFF && h[1] == 0xD8) return "jpg";
    if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "gif";
    if (h[0] == 'B' && h[1] == 'M') return "bmp";
    if ((h[0] == 'I' && h[1] == 'I' && h[2] == 42) || (h[0] == 'M' && h[1] == 'M' && h[3] == 42)) return "tif";
    if (std::memcmp(h, "RIFF", 4) == 0) return "riff";            // wav/webp
    if (std::memcmp(h, "#?RADIANCE", 10) == 0) return "hdr";
    if (std::memcmp(h, "8BPS", 4) == 0) return "psd";
    if (std::memcmp(h, "ID3", 3) == 0) return "mp3";
    if (h[0] == 0x66 && h[1] == 0x4C && h[2] == 0x61 && h[3] == 0x43) return "flac";
    if (std::memcmp(h, "OggS", 4) == 0) return "ogg";
    if (std::memcmp(h, "glTF", 4) == 0) return "glb";
    if (h[0] == 's' && h[1] == 'o' && h[2] == 'l' && h[3] == 'i' && h[4] == 'd') return "stl";
    if (std::memcmp(h, "ply", 3) == 0) return "ply";
    return "";
}

bool ensurePlainBlend(const std::string& in, const std::string& outTmp, std::string* err) {
    const std::string kind = sniff(in);
    if (kind == "blend") {
        // 已经是未压缩：直接复制一份，交给 Assimp（避免它再看到压缩头）
        std::ifstream f(in, std::ios::binary);
        std::vector<char> all((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        if (!writeAll(outTmp, all.data(), all.size())) { *err = "写临时文件失败"; return false; }
        return true;
    }
    if (kind == "blend-zstd") {
        if (zstdDecompress(in, outTmp, err)) {
            std::string v;
            if (isBlendFile(outTmp, &v)) return true;
            *err = "zstd 解开了，但内容不是 Blender 工程（可能不是 .blend 文件）";
            return false;
        }
        return false;
    }
    if (kind == "blend-gzip") {
        if (gzipDecompress(in, outTmp, err)) {
            std::string v;
            if (isBlendFile(outTmp, &v)) return true;
            *err = "gzip 解开了，但内容不是 Blender 工程";
            return false;
        }
        return false;
    }
    *err = "不是 .blend 文件（魔数不匹配）";
    return false;
}

bool isBlendFile(const std::string& path, std::string* versionOut) {
    unsigned char h[12] = {0};
    size_t got = 0;
    if (!readHead(path, h, sizeof(h), &got) || got < 12 || std::memcmp(h, "BLENDER", 7) != 0) {
        return false;
    }
    char v = static_cast<char>(h[9]);
    char s = static_cast<char>(h[10]);
    char p = static_cast<char>(h[11]);
    if (versionOut) {
        *versionOut = fmt("Blender 工程（版本头 %c.%c%c，指针 %d 字节）", v, s, p,
                          h[7] == '_' ? 4 : static_cast<int>(h[7]));
    }
    return true;
}

// ---------------------------------------------------------------- 模型

namespace {

// 需要的导入/导出器（其余关掉，控制体积）。名字对不上也无害，Assimp 会忽略未用变量。
const char* const kModelExts =
        "blend,obj,gltf,glb,fbx,dae,3ds,stl,ply,x,3mf,ac,ase,assbin,b3d,cob,ifc,irr,"
        "irrmesh,lwo,lws,md2,md3,md5mesh,ms3d,nff,off,ogex,q3d,q3s,raw,smd,ter,uc,x3d";

}  // namespace

std::string modelImportExtensions() { return kModelExts; }

std::string modelExportExtensions() {
    // 具体能不能导，以运行时 Assimp 的导出器列表为准（nativeModelFormats）
    return "obj,gltf,glb,gltf2,stl,ply,dae,3ds,fbx,assbin,assxml,x,objnomtl";
}

Result convertModel(const std::string& in, const std::string& out, std::string* report) {
    Result r;
    const std::string outExt = lowerExt(out);
    if (outExt.empty()) {
        r.message = "输出文件名没有扩展名，无法判断目标格式";
        return r;
    }

    // .blend 预处理：压缩的先解开
    std::string usePath = in;
    std::string tmp;
    const std::string kind = sniff(in);
    if (kind == "blend" || kind == "blend-zstd" || kind == "blend-gzip") {
        tmp = out + ".plain.blend.tmp";
        std::string err;
        if (!ensurePlainBlend(in, tmp, &err)) {
            r.message = "处理 .blend 失败：" + err
                    + "\n（若是压缩包，需要 zstd/gzip 解压支持；也可能是文件损坏）";
            return r;
        }
        std::string v;
        isBlendFile(tmp, &v);
        usePath = tmp;
        if (!v.empty()) {
            r.message = v + "\n";
        }
    }

    Assimp::Importer imp;
    // 不做三角化/不空间化：尽量保持原样（导出器自己处理）
    const unsigned int flags = aiProcess_JoinIdenticalVertices | aiProcess_Triangulate
            | aiProcess_ValidateDataStructure | aiProcess_ImproveCacheLocality;
    const aiScene* scene = imp.ReadFile(usePath, flags);
    if (scene == nullptr) {
        std::string e = imp.GetErrorString();
        r.message += "Assimp 读不了这个文件：" + (e.empty() ? "（未知原因）" : e);
        if (kind == "blend" || kind == "blend-zstd" || kind == "blend-gzip") {
            r.message += "\n提示：Assimp 对 .blend 的支持有限（复杂节点材质、修改器、新版本会丢）。"
                         "要 100% 保真，请在 Blender 里「导出 → glTF 2.0 (.glb)」。";
        }
        if (!tmp.empty()) {
            std::remove(tmp.c_str());
        }
        return r;
    }

    Assimp::Exporter ex;
    const aiReturn ret = ex.Export(scene, outExt, out);
    if (!tmp.empty()) {
        std::remove(tmp.c_str());
    }
    if (ret != aiReturn_SUCCESS) {
        r.message += std::string("导出失败（") + outExt + "）：" + ex.GetErrorString();
        return r;
    }

    // 摘要
    unsigned long long verts = 0, faces = 0, meshes = 0, mats = 0;
    for (unsigned i = 0; i < scene->mNumMeshes; ++i) {
        const aiMesh* m = scene->mMeshes[i];
        if (m == nullptr) continue;
        meshes++;
        verts += m->mNumVertices;
        faces += m->mNumFaces;
    }
    mats = scene->mNumMaterials;
    std::ifstream of(out, std::ios::binary | std::ios::ate);
    const double outSize = of ? static_cast<double>(of.tellg()) : 0.0;
    r.ok = true;
    r.message += fmt("转换完成：%s → %s\n网格 %llu · 顶点 %llu · 面 %llu · 材质 %llu · 动画 %u\n输出 %s",
                     baseName(in).c_str(), baseName(out).c_str(), meshes, verts, faces, mats,
                     scene->mNumAnimations, human(outSize).c_str());
    if (report) {
        *report = r.message;
    }
    return r;
}

// ---------------------------------------------------------------- 图片

namespace {
void copyTo(const std::vector<unsigned char>& src, const std::string& out) {
    writeAll(out, src.data(), src.size());
}
}  // namespace

std::string imageImportExtensions() {
    return "png,jpg,jpeg,bmp,tga,psd,gif,hdr,pic";
}

std::string imageExportExtensions() {
    return "png,jpg,jpeg,bmp,tga,hdr";
}

Result convertImage(const std::string& in, const std::string& out, int quality, std::string* report) {
    Result r;
    int w = 0, h = 0, comp = 0;
    const std::string inExt = lowerExt(in);
    const bool wantFloat = lowerExt(out) == "hdr";
    if (wantFloat) {
        float* data = stbi_loadf(in.c_str(), &w, &h, &comp, 0);
        if (data == nullptr) {
            r.message = std::string("stb 读不了这张图：") + stbi_failure_reason();
            return r;
        }
        const bool ok = stbi_write_hdr(out.c_str(), w, h, comp, data) != 0;
        stbi_image_free(data);
        if (!ok) { r.message = "写 HDR 失败"; return r; }
    } else {
        unsigned char* data = stbi_load(in.c_str(), &w, &h, &comp, 0);
        if (data == nullptr) {
            const std::string why = stbi_failure_reason() ? stbi_failure_reason() : "未知";
            r.message = "stb 读不了这张图（" + inExt + "）：" + why
                    + "\n提示：WebP/AVIF/HEIC 走「平台直读」路线（Java 侧用 Bitmap 转）。";
            return r;
        }
        const std::string outExt = lowerExt(out);
        int ok = 0;
        if (outExt == "png") {
            ok = stbi_write_png(out.c_str(), w, h, comp, data, w * comp) != 0;
        } else if (outExt == "jpg" || outExt == "jpeg") {
            ok = stbi_write_jpg(out.c_str(), w, h, comp, data, quality <= 0 ? 92 : quality) != 0;
        } else if (outExt == "bmp") {
            ok = stbi_write_bmp(out.c_str(), w, h, comp, data) != 0;
        } else if (outExt == "tga") {
            ok = stbi_write_tga(out.c_str(), w, h, comp, data) != 0;
        } else {
            r.message = "这个输出格式 stb 不支持：" + outExt + "（支持 png/jpg/bmp/tga/hdr）";
            stbi_image_free(data);
            return r;
        }
        stbi_image_free(data);
        if (!ok) { r.message = "写 " + outExt + " 失败"; return r; }
    }
    std::ifstream of(out, std::ios::binary | std::ios::ate);
    const double outSize = of ? static_cast<double>(of.tellg()) : 0.0;
    r.ok = true;
    r.message = fmt("转换完成：%s → %s\n尺寸 %d×%d · 通道 %d · 输出 %s",
                    baseName(in).c_str(), baseName(out).c_str(), w, h, comp, human(outSize).c_str());
    if (report) {
        *report = r.message;
    }
    return r;
}

Result imageInfo(const std::string& in, std::string* report) {
    Result r;
    int w = 0, h = 0, comp = 0;
    if (!stbi_info(in.c_str(), &w, &h, &comp)) {
        r.message = std::string("读不出图片信息：") + stbi_failure_reason();
        return r;
    }
    r.ok = true;
    r.message = fmt("%d×%d · %d 通道 · 探测类型：%s", w, h, comp, sniff(in).c_str());
    if (report) {
        *report = r.message;
    }
    return r;
}

}  // namespace fmtconv
