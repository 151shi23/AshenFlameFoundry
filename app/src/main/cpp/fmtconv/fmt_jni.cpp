// JNI 封装：Java com.mineways.conv.FmtConv 的 native 侧
// 返回约定：首字符 '1' = 成功，'0' = 失败，第二行起是给人看的说明。
#include <jni.h>

#include <string>

#include <assimp/Exporter.hpp>
#include <assimp/Importer.hpp>
#include <assimp/cexport.h>

#include "fmtconv/fmt_core.h"

namespace {

std::string txt(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return "";
    }
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c != nullptr) {
        env->ReleaseStringUTFChars(s, c);
    }
    return out;
}

std::string pack(bool ok, const std::string& msg) {
    return std::string(ok ? "1" : "0") + "\n" + msg;
}

jstring ret(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_mineways_conv_FmtConv_nativeSniff(JNIEnv* env, jclass, jstring path) {
    const std::string p = txt(env, path);
    const std::string kind = fmtconv::sniff(p);
    std::string extra;
    if (kind == "blend") {
        std::string v;
        fmtconv::isBlendFile(p, &v);
        extra = "（" + v + "）";
    }
    return ret(env, pack(!kind.empty(), kind.empty() ? "认不出这个文件的类型" : (kind + extra)));
}

/** 运行时真实可用格式（来自 Assimp 自己注册的列表，不是我们猜的）。 */
JNIEXPORT jstring JNICALL
Java_com_mineways_conv_FmtConv_nativeFormats(JNIEnv* env, jclass) {
    Assimp::Importer imp;
    std::string in;
    imp.GetExtensionList(in);          // 输入：Importer 直接给

    std::string out;                    // 输出：Exporter 只能一个个枚举
    Assimp::Exporter ex;
    for (size_t i = 0; i < ex.GetExportFormatCount(); ++i) {
        const aiExportFormatDesc* d = ex.GetExportFormatDescription(i);
        if (d == nullptr || d->fileExtension == nullptr || d->fileExtension[0] == '\0') {
            continue;
        }
        if (!out.empty()) {
            out += ",";
        }
        out += d->fileExtension;
    }
    return ret(env, pack(true, "import=" + in + "\nexport=" + out));
}

JNIEXPORT jstring JNICALL
Java_com_mineways_conv_FmtConv_nativeConvertModel(JNIEnv* env, jclass, jstring in, jstring out) {
    const std::string a = txt(env, in);
    const std::string b = txt(env, out);
    if (a.empty() || b.empty()) {
        return ret(env, pack(false, "输入或输出路径是空的"));
    }
    std::string report;
    const fmtconv::Result r = fmtconv::convertModel(a, b, &report);
    return ret(env, pack(r.ok, r.message.empty() ? report : r.message));
}

JNIEXPORT jstring JNICALL
Java_com_mineways_conv_FmtConv_nativeConvertImage(JNIEnv* env, jclass, jstring in, jstring out,
                                                  jint quality) {
    const std::string a = txt(env, in);
    const std::string b = txt(env, out);
    if (a.empty() || b.empty()) {
        return ret(env, pack(false, "输入或输出路径是空的"));
    }
    std::string report;
    const fmtconv::Result r = fmtconv::convertImage(a, b, static_cast<int>(quality), &report);
    return ret(env, pack(r.ok, r.message.empty() ? report : r.message));
}

JNIEXPORT jstring JNICALL
Java_com_mineways_conv_FmtConv_nativeImageInfo(JNIEnv* env, jclass, jstring in) {
    const std::string a = txt(env, in);
    std::string report;
    const fmtconv::Result r = fmtconv::imageInfo(a, &report);
    return ret(env, pack(r.ok, r.message.empty() ? report : r.message));
}

}  // extern "C"
