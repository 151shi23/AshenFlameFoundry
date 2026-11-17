package com.mineways.open;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「打开方式」路由表：扩展名 → 候选功能（有序，专用在前、通用兜底在后）。
 *
 * <p>只登记本工程内的类名（白名单），由 {@link OpenWithActivity} 反射解析；
 * 绝不接受外部 Intent 里带来的组件名。类不存在（例如某版本没带文件保险箱）
 * 的候选会被自动剔除，不会崩。
 */
public final class OpenRoute {

    public static final class Target {
        public final String label;
        public final String desc;
        public final String className;
        /** 可选附加 extra（例如材质包转换的方向）。 */
        public final String extraKey;
        public final String extraVal;

        Target(String label, String desc, String className, String extraKey, String extraVal) {
            this.label = label;
            this.desc = desc;
            this.className = className;
            this.extraKey = extraKey;
            this.extraVal = extraVal;
        }
    }

    private static final String CONV = "com.mineways.conv.FormatConvertActivity";
    private static final String PREVIEW = "com.mineways.ObjPreviewActivity";
    private static final String FOURD = "com.mineways.FourDGenActivity";
    private static final String BB2OBJ = "com.mineways.BbToObjActivity";
    private static final String BLOCKBENCH = "com.mineways.BlockbenchActivity";
    private static final String P3D = "com.mineways.P3DConvertActivity";
    private static final String YSM = "com.mineways.ysm.YsmActivity";
    private static final String PACK = "com.mineways.PackConvertActivity";
    private static final String LOGAN = "com.mineways.logan.LogAnalyzerActivity";
    private static final String VAULT = "com.mineways.vault.VaultActivity";
    private static final String SCRUB = "com.mineways.BrandScrubActivity";
    private static final String GIF = "com.mineways.GifActivity";
    private static final String OPENCUT = "com.mineways.OpenCutActivity";
    private static final String PHOTOCRAFT = "com.mineways.PhotoCraftGameActivity";
    private static final String LANDSCAPE = "com.mineways.LandscapeEnhanceActivity";
    private static final String PANO = "com.mineways.vr.PanoActivity";
    private static final String PNG2MODEL = "com.mineways.PngToModelActivity";
    private static final String SNOWSTORM = "com.mineways.SnowstormActivity";
    private static final String PHRASE = "com.mineways.PhraseToolboxActivity";
    private static final String MOCAP = "com.mineways.mocap.MocapActivity";
    private static final String BLENDER = "com.mineways.blender.ProjectToolsActivity";
    private static final String GUARD = "com.mineways.guardlink.GuardLinkActivity";

    private OpenRoute() {
    }

    public static List<Target> candidates(String ext, String mime) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        List<Target> out = new ArrayList<>();
        switch (e) {
            case "obj": case "glb": case "gltf": case "fbx": case "stl":
            case "ply": case "dae": case "3ds": case "pmx":
                add(out, "格式转换", "转 OBJ / GLB / STL 等，贴图自动嵌入", CONV);
                add(out, "3D 预览", "网页查看器，旋转 / 缩放 / 诊断", PREVIEW);
                add(out, "4D 皮肤", "导入模型做皮肤 / 动画", FOURD);
                break;
            case "vrm":
                add(out, "4D 皮肤", "导入 VRM 做皮肤 / 动画", FOURD);
                add(out, "格式转换", "转 GLB / OBJ 等", CONV);
                break;
            case "bbmodel":
                add(out, "bb 模型转 OBJ", "导出 OBJ + 贴图", BB2OBJ);
                add(out, "Blockbench", "网页建模器直接打开", BLOCKBENCH);
                break;
            case "prisma":
                add(out, "P3D 工程工具", "Prisma3D 工程转换 / 修复", P3D);
                break;
            case "ysm":
                add(out, "YSM 解密", "还原 Blockbench 工程", YSM);
                break;
            case "mcpack": case "jar":
                add(out, "材质包转换", "基岩 ↔ Java 材质包", PACK);
                break;
            case "zip":
                add(out, "材质包转换", "基岩 → Java", PACK);
                add(out, "Java→基岩", "Java → 基岩材质包", PACK, "dir", "java2bedrock");
                add(out, "bb 模型转 OBJ", "压缩包里的 bbmodel", BB2OBJ);
                add(out, "3D 预览", "压缩包里的模型", PREVIEW);
                add(out, "日志诊断", "压缩包里的日志", LOGAN);
                break;
            case "寂密": case "mwv":
                add(out, "文件保险箱", "解密还原文件", VAULT);
                break;
            case "mp4": case "mov": case "mkv": case "webm": case "avi":
            case "flv": case "m4v": case "3gp": case "ts":
                add(out, "剪辑标识清除", "去水印 / 标识分析", SCRUB);
                add(out, "GIF 工具", "视频转 GIF / 拆帧", GIF);
                add(out, "视频剪辑", "OpenCut 时间轴剪辑", OPENCUT);
                break;
            case "jpg": case "jpeg": case "png": case "webp": case "bmp":
            case "tga": case "hdr": case "psd": case "avif": case "heic":
            case "heif": case "ico": case "tif": case "tiff":
                add(out, "修图", "PhotoCraft：图层 / 选区 / 滤镜", PHOTOCRAFT);
                add(out, "格式转换", "转 PNG / WebP 等", CONV);
                add(out, "风景增强", "风景照调色 / 加印章", LANDSCAPE);
                add(out, "全景", "多张照片拼 360°", PANO);
                add(out, "PNG 转模型", "像素图转 bbmodel", PNG2MODEL);
                break;
            case "gif":
                add(out, "GIF 工具", "拆帧 / 合成 / 材质动画", GIF);
                add(out, "修图", "PhotoCraft 编辑", PHOTOCRAFT);
                add(out, "格式转换", "转 PNG / WebP 等", CONV);
                add(out, "风景增强", "调色 / 加印章", LANDSCAPE);
                add(out, "PNG 转模型", "像素图转 bbmodel", PNG2MODEL);
                break;
            case "mp3": case "wav": case "flac": case "m4a": case "aac":
            case "ogg": case "opus": case "amr": case "wma": case "aiff":
            case "aif": case "mka": case "ape": case "mp2":
                add(out, "格式转换", "音频转码", CONV);
                add(out, "视频剪辑", "OpenCut 音轨", OPENCUT);
                break;
            case "log": case "txt":
                add(out, "日志诊断", "自动识别来源出中文报告", LOGAN);
                add(out, "串词工具箱", "导入串词 / 谐音表", PHRASE);
                break;
            case "csv":
                add(out, "动作捕捉", "关键点 CSV 解算成动画", MOCAP);
                break;
            case "animation.json":
                add(out, "动作捕捉", "动画 JSON 预览 / 编辑", MOCAP);
                break;
            case "json":
                add(out, "粒子编辑器", "Snowstorm 粒子工程", SNOWSTORM);
                add(out, "动作捕捉", "动画 JSON 预览 / 编辑", MOCAP);
                break;
            case "blend":
                add(out, "Blender 渲染", "离线渲染工程", BLENDER);
                add(out, "格式转换", "转 OBJ / GLB 等", CONV);
                break;
            case "apk":
                add(out, "安装包审计", "安全守护 / 安装包检查", GUARD);
                break;
            default:
                // 未知格式兜底：文件保险箱加密
                add(out, "文件保险箱", "加密成 .mwv 容器", VAULT);
                break;
        }
        return out;
    }

    private static void add(List<Target> out, String label, String desc, String cls) {
        out.add(new Target(label, desc, cls, null, null));
    }

    private static void add(List<Target> out, String label, String desc, String cls,
                            String key, String val) {
        out.add(new Target(label, desc, cls, key, val));
    }

    /** 只保留本工程真实存在的类（缺失的功能不显示，避免点了崩）。 */
    public static List<Target> existing(List<Target> in) {
        List<Target> out = new ArrayList<>();
        for (Target t : in) {
            try {
                Class<?> c = Class.forName(t.className);
                if (android.app.Activity.class.isAssignableFrom(c)) {
                    out.add(t);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
