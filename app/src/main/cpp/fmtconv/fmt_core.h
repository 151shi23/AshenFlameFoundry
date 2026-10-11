// 格式转换内核（纯 C++，不依赖 Android，方便在电脑上单独验证）
//
// 模型：Assimp（BSD-3）读入 → 按目标格式导出；.blend 先做压缩探测，
//       压缩过的（zstd/gzip）先用 zstd/zlib 解一层再交给 Assimp。
// 图片：stb_image / stb_image_write（公有领域）。
#pragma once

#include <string>
#include <vector>

namespace fmtconv {

// ---------------------------------------------------------------- 通用
struct Result {
    bool ok = false;
    std::string message;        // 给人看的结果/错误说明
};

/** 文件魔数探测：返回 "blend" / "blend-zstd" / "blend-gzip" / "png" / ... / "" */
std::string sniff(const std::string& path);

/** .blend 是否是压缩的；是的话解到 outPath（未压缩），返回是否成功。 */
bool ensurePlainBlend(const std::string& in, const std::string& outTmp, std::string* err);

/** 该（未压缩）文件是不是 Blender 工程；顺带把版本号带出来（如 "Blender 3.6"）。 */
bool isBlendFile(const std::string& path, std::string* versionOut);

// ---------------------------------------------------------------- 模型
/** Assimp 支持的导入扩展名（小写，不含点），逗号分隔，用于界面展示。 */
std::string modelImportExtensions();
/** Assimp 支持的导出扩展名。 */
std::string modelExportExtensions();

/** 模型转换：in → out（按扩展名自动选导出器）。report 里带顶点/面数等摘要。 */
Result convertModel(const std::string& in, const std::string& out, std::string* report);

// ---------------------------------------------------------------- 图片
/** stb 能读的图片扩展名。 */
std::string imageImportExtensions();
/** stb 能写的图片格式（按扩展名）。 */
std::string imageExportExtensions();

/** 图片转换（stb 路线：png/jpg/bmp/tga/hdr/psd/gif 读；png/jpg/bmp/tga/hdr 写）。 */
Result convertImage(const std::string& in, const std::string& out, int quality, std::string* report);

/** 图片信息（宽高/通道/格式）。 */
Result imageInfo(const std::string& in, std::string* report);

}  // namespace fmtconv
