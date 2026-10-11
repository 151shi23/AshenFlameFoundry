package com.mineways.blender;

import java.io.File;
import java.io.IOException;

/**
 * 生成 Blender 的 headless 渲染脚本（Python）。
 *
 * <p>参数不走命令行而是走环境变量（{@code BW_*}），因为命令行里塞一堆引号在
 * {@code sh -c} + glibc 加载器两层转义下很容易破。所有 setter 都包了 try，
 * 换 Blender 版本时字段改名不会让整个渲染崩掉。</p>
 *
 * <p>输出：静帧（PNG / JPEG / WEBP / TIFF / BMP / OpenEXR）、PNG 序列、
 * 视频（官方构建自带 FFmpeg，可直出 MP4(H.264/H.265) / WebM(VP9) / MKV，带工程里的音频）。</p>
 */
public final class BlenderScript {

    private BlenderScript() {
    }

    /** 输出格式。 */
    public static final class Fmt {
        public static final String PNG = "PNG";
        public static final String JPEG = "JPEG";
        public static final String WEBP = "WEBP";
        public static final String TIFF = "TIFF";
        public static final String BMP = "BMP";
        public static final String EXR = "OPEN_EXR";
        public static final String FFMPEG = "FFMPEG";

        private Fmt() {
        }
    }

    /** 渲染参数。 */
    public static final class Params {
        public String engine = "CYCLES";          // CYCLES / BLENDER_EEVEE_NEXT / BLENDER_WORKBENCH
        public int samples = 64;
        public int threads = 0;                   // 0 = 用满全部核心
        public int resX = 1920;
        public int resY = 1080;
        public int resPercent = 100;
        public boolean animation = false;
        public int frame = 1;
        public int frameStart = 1;
        public int frameEnd = 1;
        public int frameStep = 1;
        public String viewTransform = "";         // 空 = 保持工程设置
        public String camera = "";                // 空 = 保持工程相机
        public String output = "";                // 由调用方填
        public boolean filmTransparent = false;
        public boolean overwrite = true;

        // ── 输出 ──
        public String format = Fmt.PNG;
        public int imageQuality = 90;             // JPEG / WEBP
        public String videoContainer = "MPEG4";   // MPEG4 / MKV / WEBM
        public String videoCodec = "H264";        // H264 / H265 / VP9 / MPEG4
        public int fps = 24;
        public String quality = "HIGH";           // constant_rate_factor
        public boolean audio = true;

        /** 视频参数是否自洽（WEBM 只能配 VP9 之类）。 */
        public void normalizeVideo() {
            if ("WEBM".equals(videoContainer)) {
                videoContainer = "WEBM";
                videoCodec = "VP9";
            }
            if ("VP9".equals(videoCodec) && !"WEBM".equals(videoContainer)) {
                videoContainer = "WEBM";
            }
            if ("H265".equals(videoCodec) && "MKV".equals(videoContainer)) {
                videoContainer = "MKV";
            }
            if (videoCodec == null || videoCodec.isEmpty()) videoCodec = "H264";
            if (videoContainer == null || videoContainer.isEmpty()) videoContainer = "MPEG4";
        }

        public boolean isVideo() {
            return Fmt.FFMPEG.equals(format);
        }
    }

    /** 写脚本文件（放 work 目录，沙箱内可读）。 */
    public static File write(File workDir, Params p) throws IOException {
        final File f = new File(workDir, "mw_render.py");
        FileUtil.writeText(f, source(p));
        FileUtil.makeExecutable(f);
        return f;
    }

    public static String source(Params p) {
        p.normalizeVideo();
        return "import bpy, os, sys\n"
                + "\n"
                + "def E(k, d=None):\n"
                + "    v = os.environ.get(\"BW_\" + k)\n"
                + "    return d if v is None or v == \"\" else v\n"
                + "\n"
                + "def S(obj, attr, val):\n"
                + "    try:\n"
                + "        setattr(obj, attr, val)\n"
                + "        return True\n"
                + "    except BaseException:\n"
                + "        return False\n"
                + "\n"
                + "def I(k, d):\n"
                + "    try:\n"
                + "        return int(E(k, str(d)))\n"
                + "    except BaseException:\n"
                + "        return d\n"
                + "\n"
                + "def F(k, d):\n"
                + "    return E(k, \"1\" if d else \"0\") == \"1\"\n"
                + "\n"
                + "scene = bpy.context.scene\n"
                + "r = scene.render\n"
                + "\n"
                + "# ── 引擎（EEVEE 在不同版本里 id 变过：BLENDER_EEVEE / BLENDER_EEVEE_NEXT）──\n"
                + "want = E(\"ENGINE\", \"CYCLES\").upper()\n"
                + "cands = [want]\n"
                + "if want == \"BLENDER_EEVEE_NEXT\":\n"
                + "    cands.append(\"BLENDER_EEVEE\")\n"
                + "elif want == \"BLENDER_EEVEE\":\n"
                + "    cands.append(\"BLENDER_EEVEE_NEXT\")\n"
                + "picked = None\n"
                + "for c in cands:\n"
                + "    if S(r, \"engine\", c):\n"
                + "        picked = c\n"
                + "        break\n"
                + "print(\"MW_ENGINE \" + str(picked))\n"
                + "if picked is None:\n"
                + "    print(\"MW_ERROR engine_not_available \" + want)\n"
                + "\n"
                + "samples = I(\"SAMPLES\", 64)\n"
                + "threads = I(\"THREADS\", 0)\n"
                + "\n"
                + "if r.engine == \"CYCLES\":\n"
                + "    cy = getattr(scene, \"cycles\", None)\n"
                + "    if cy is not None:\n"
                + "        S(cy, \"samples\", samples)\n"
                + "        S(cy, \"use_denoising\", True)\n"
                + "        S(cy, \"use_adaptive_sampling\", True)\n"
                + "        S(cy, \"device\", E(\"DEVICE\", \"CPU\"))\n"
                + "        if threads > 0:\n"
                + "            S(cy, \"threads\", threads)\n"
                + "            S(cy, \"threads_mode\", \"FIXED\")\n"
                + "elif \"EEVEE\" in r.engine:\n"
                + "    ev = getattr(scene, \"eevee\", None)\n"
                + "    if ev is not None:\n"
                + "        S(ev, \"taa_render_samples\", samples)\n"
                + "        S(ev, \"use_gtao\", True)\n"
                + "        S(ev, \"use_raytracing\", True)\n"
                + "\n"
                + "# ── 分辨率 / 帧率 ──\n"
                + "S(r, \"resolution_x\", I(\"RES_X\", 1920))\n"
                + "S(r, \"resolution_y\", I(\"RES_Y\", 1080))\n"
                + "S(r, \"resolution_percentage\", I(\"RES_PCT\", 100))\n"
                + "S(r, \"use_file_extension\", True)\n"
                + "S(r, \"use_overwrite\", F(\"OVERWRITE\", True))\n"
                + "S(r, \"use_placeholder\", False)\n"
                + "S(r, \"fps\", max(1, I(\"FPS\", 24)))\n"
                + "S(r, \"fps_base\", 1.0)\n"
                + "\n"
                + "# ── 输出格式：静帧 / 序列 / 视频（官方构建自带 FFmpeg）──\n"
                + "fmt = E(\"FORMAT\", \"PNG\").upper()\n"
                + "transparent = F(\"TRANSPARENT\", False)\n"
                + "if fmt == \"FFMPEG\":\n"
                + "    if not S(r.image_settings, \"file_format\", \"FFMPEG\"):\n"
                + "        print(\"MW_ERROR ffmpeg_not_available\")\n"
                + "    ff = getattr(r, \"ffmpeg\", None)\n"
                + "    if ff is not None:\n"
                + "        S(ff, \"format\", E(\"V_CONTAINER\", \"MPEG4\"))\n"
                + "        S(ff, \"codec\", E(\"V_CODEC\", \"H264\"))\n"
                + "        S(ff, \"constant_rate_factor\", E(\"V_QUALITY\", \"HIGH\"))\n"
                + "        S(ff, \"ffmpeg_preset\", \"GOOD\")\n"
                + "        S(ff, \"gopsize\", max(1, I(\"V_GOP\", 12)))\n"
                + "        S(ff, \"use_max_b_frames\", True)\n"
                + "        S(ff, \"max_b_frames\", 2)\n"
                + "        if F(\"V_AUDIO\", True):\n"
                + "            S(ff, \"audio_codec\", \"AAC\")\n"
                + "            S(ff, \"audio_bitrate\", 192)\n"
                + "            S(ff, \"audio_mixrate\", 48000)\n"
                + "        else:\n"
                + "            S(ff, \"audio_codec\", \"NONE\")\n"
                + "    S(r.image_settings, \"color_mode\", \"RGB\")\n"
                + "    print(\"MW_VIDEO \" + str(getattr(ff, \"format\", \"\")) + \"/\" + str(getattr(ff, \"codec\", \"\")))\n"
                + "else:\n"
                + "    S(r.image_settings, \"file_format\", fmt)\n"
                + "    S(r.image_settings, \"color_mode\", \"RGBA\" if transparent else \"RGB\")\n"
                + "    S(r.image_settings, \"quality\", max(1, min(100, I(\"IMG_QUALITY\", 90))))\n"
                + "    S(r.image_settings, \"compression\", 15)\n"
                + "print(\"MW_FORMAT \" + str(r.image_settings.file_format))\n"
                + "\n"
                + "S(r, \"film_transparent\", transparent)\n"
                + "\n"
                + "vt = E(\"VIEW_TRANSFORM\", \"\")\n"
                + "if vt:\n"
                + "    S(scene.view_settings, \"view_transform\", vt)\n"
                + "    S(scene.view_settings, \"look\", \"None\")\n"
                + "\n"
                + "cam = E(\"CAMERA\", \"\")\n"
                + "if cam:\n"
                + "    ob = bpy.data.objects.get(cam)\n"
                + "    if ob is not None:\n"
                + "        scene.camera = ob\n"
                + "    else:\n"
                + "        print(\"MW_ERROR camera_not_found \" + cam)\n"
                + "if scene.camera is None:\n"
                + "    for ob in bpy.data.objects:\n"
                + "        if ob.type == \"CAMERA\":\n"
                + "            scene.camera = ob\n"
                + "            break\n"
                + "if scene.camera is None:\n"
                + "    print(\"MW_ERROR no_camera_in_file\")\n"
                + "\n"
                + "out = E(\"OUT\", \"/tmp/out.png\")\n"
                + "r.filepath = out\n"
                + "print(\"MW_OUT \" + out)\n"
                + "\n"
                + "def mw_report(*args):\n"
                + "    try:\n"
                + "        sc = bpy.context.scene\n"
                + "        print(\"MW_FRAME %d %d\" % (sc.frame_current, sc.frame_end))\n"
                + "        sys.stdout.flush()\n"
                + "    except BaseException:\n"
                + "        pass\n"
                + "\n"
                + "bpy.app.handlers.render_write.append(mw_report)\n"
                + "bpy.app.handlers.render_complete.append(mw_report)\n"
                + "\n"
                + "if F(\"ANIM\", False):\n"
                + "    fs = I(\"FRAME_START\", 1)\n"
                + "    fe = I(\"FRAME_END\", 1)\n"
                + "    if fe < fs:\n"
                + "        fe = fs\n"
                + "    S(scene, \"frame_start\", fs)\n"
                + "    S(scene, \"frame_end\", fe)\n"
                + "    S(scene, \"frame_step\", max(1, I(\"FRAME_STEP\", 1)))\n"
                + "    print(\"MW_ANIM %d %d\" % (fs, fe))\n"
                + "    bpy.ops.render.render(animation=True)\n"
                + "else:\n"
                + "    scene.frame_set(I(\"FRAME\", 1))\n"
                + "    bpy.ops.render.render(write_still=True)\n"
                + "\n"
                + "print(\"MW_DONE\")\n";
    }

    /** 拼出 {@code BW_*} 环境变量前缀（供 shell 用）。 */
    public static String env(Params p) {
        p.normalizeVideo();
        final StringBuilder sb = new StringBuilder();
        sb.append("BW_ENGINE=").append(q(p.engine)).append(' ');
        sb.append("BW_SAMPLES=").append(p.samples).append(' ');
        sb.append("BW_THREADS=").append(p.threads).append(' ');
        sb.append("BW_RES_X=").append(p.resX).append(' ');
        sb.append("BW_RES_Y=").append(p.resY).append(' ');
        sb.append("BW_RES_PCT=").append(p.resPercent).append(' ');
        sb.append("BW_ANIM=").append(p.animation ? 1 : 0).append(' ');
        sb.append("BW_FRAME=").append(p.frame).append(' ');
        sb.append("BW_FRAME_START=").append(p.frameStart).append(' ');
        sb.append("BW_FRAME_END=").append(p.frameEnd).append(' ');
        sb.append("BW_FRAME_STEP=").append(p.frameStep).append(' ');
        sb.append("BW_TRANSPARENT=").append(p.filmTransparent ? 1 : 0).append(' ');
        sb.append("BW_OVERWRITE=").append(p.overwrite ? 1 : 0).append(' ');
        sb.append("BW_FPS=").append(p.fps).append(' ');
        sb.append("BW_FORMAT=").append(q(p.format)).append(' ');
        sb.append("BW_IMG_QUALITY=").append(p.imageQuality).append(' ');
        sb.append("BW_V_CONTAINER=").append(q(p.videoContainer)).append(' ');
        sb.append("BW_V_CODEC=").append(q(p.videoCodec)).append(' ');
        sb.append("BW_V_QUALITY=").append(q(p.quality)).append(' ');
        sb.append("BW_V_AUDIO=").append(p.audio ? 1 : 0).append(' ');
        sb.append("BW_DEVICE=").append(q("CPU")).append(' ');
        if (p.viewTransform != null && !p.viewTransform.isEmpty()) {
            sb.append("BW_VIEW_TRANSFORM=").append(q(p.viewTransform)).append(' ');
        }
        if (p.camera != null && !p.camera.isEmpty()) {
            sb.append("BW_CAMERA=").append(q(p.camera)).append(' ');
        }
        sb.append("BW_OUT=").append(q(p.output));
        return sb.toString();
    }

    private static String q(String s) {
        return "'" + String.valueOf(s).replace("'", "'\\''") + "'";
    }
}
