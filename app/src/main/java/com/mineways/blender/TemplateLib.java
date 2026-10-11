package com.mineways.blender;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 模板工程：两条来源，都直接支持 Blender 原生 {@code .blend}。
 *
 * <ol>
 *   <li><b>官方模板</b>：Blender 发行包里 {@code release/datafiles/templates/} 自带一批
 *       {@code .blend}。它们在运行目录里，可以直接当工程渲染 —— 不复制，
 *       免得丢掉相对路径引用的贴图。</li>
 *   <li><b>内置模板（程序化生成）</b>：用 bpy 脚本现搭一个工程并存成 {@code .blend}。
 *       解决「用户手上只有一个空工程 / 干脆没有工程」的场景，顺带给产品台、肖像布光、
 *       转台展示这类常用机位。生成脚本本身就是模板，可改可扩。</li>
 * </ol>
 */
public final class TemplateLib {

    public static final class Tpl {
        public final String key;
        public final String title;
        public final String desc;

        Tpl(String key, String title, String desc) {
            this.key = key;
            this.title = title;
            this.desc = desc;
        }
    }

    /** 内置模板清单（key 传给生成脚本）。 */
    public static final List<Tpl> BUILTIN = Arrays.asList(
            new Tpl("default", "默认立方体", "立方体 + 地面 + 三点光，等于 Blender 启动文件"),
            new Tpl("blank", "空场景 · 三点布光", "只有地面、世界环境和三点光，适合自己丢模型进去"),
            new Tpl("product", "产品展示台", "圆台 + 背板 + 两盏柔光箱，电商图那种干净布光"),
            new Tpl("portrait", "肖像布光", "主光 / 辅光 / 轮廓光 + 背景板"),
            new Tpl("turntable", "转台展示", "空物体当父级 + 转台圆盘，方便直接做旋转动画"),
            new Tpl("grid", "地面网格", "大平面 + 线框叠加，看大场景比例用"));

    private TemplateLib() {
    }

    /** 官方发行包里的 .blend 模板（没有就返回空列表）。 */
    public static List<File> officialBlends(Context c) {
        final List<File> out = new ArrayList<>();
        final File dir = BlenderEnv.templatesDir(c);
        if (dir == null) return out;
        final File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".blend")) {
                out.add(f);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * 生成一个内置模板工程。
     *
     * @return 生成的 .blend 路径
     */
    public static File generate(Context c, String key) throws IOException {
        if (!BlenderEnv.installed(c)) {
            throw new IOException("Blender 还没装好。");
        }
        final File tplDir = new File(BlenderEnv.workDir(c), "tpl");
        FileUtil.mkdirs(tplDir);
        final File out = new File(tplDir, key + ".blend");
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        final File script = new File(BlenderEnv.tmpDir(c), "mw_tpl.py");
        FileUtil.writeText(script, python());
        FileUtil.makeExecutable(script);

        final String loader = BlenderEnv.loaderPath(c);
        final File bin = new File(BlenderEnv.blenderBin(c));
        final File top = bin.getParentFile();
        final String libPath = BlenderEnv.libPath(c) + ":" + top.getAbsolutePath() + "/lib";
        final String env = "LD_LIBRARY_PATH=" + libPath
                + " HOME=" + BlenderEnv.homeDir(c).getAbsolutePath()
                + " TMPDIR=" + BlenderEnv.tmpDir(c).getAbsolutePath()
                + " LANG=C.UTF-8 PYTHONDONTWRITEBYTECODE=1";

        final RootShell.Result r = RootShell.sh(env + " " + RootShell.q(loader)
                + " --library-path " + RootShell.q(libPath)
                + " " + RootShell.q(bin.getAbsolutePath())
                + " --factory-startup -noaudio -b"
                + " -P " + RootShell.q(script.getAbsolutePath())
                + " -- " + RootShell.q(key) + " " + RootShell.q(out.getAbsolutePath())
                + " 2>&1 | tail -8");
        if (!out.isFile() || out.length() < 64) {
            throw new IOException("模板生成失败：" + r.out.trim());
        }
        FileUtil.makeTreeReadable(tplDir);
        return out;
    }

    /** 模板生成脚本：所有内置模板都在这里用 bpy 搭出来。 */
    public static String python() {
        return "import bpy, sys, math\n"
                + "from mathutils import Vector\n"
                + "\n"
                + "argv = []\n"
                + "if \"--\" in sys.argv:\n"
                + "    argv = sys.argv[sys.argv.index(\"--\") + 1:]\n"
                + "key = argv[0] if len(argv) > 0 else \"default\"\n"
                + "out = argv[1] if len(argv) > 1 else \"/tmp/tpl.blend\"\n"
                + "\n"
                + "bpy.ops.wm.read_homefile(use_empty=True)\n"
                + "scene = bpy.context.scene\n"
                + "scene.render.engine = \"CYCLES\"\n"
                + "scene.cycles.samples = 128\n"
                + "scene.cycles.use_denoising = True\n"
                + "scene.render.resolution_x = 1920\n"
                + "scene.render.resolution_y = 1080\n"
                + "try:\n"
                + "    scene.view_settings.view_transform = \"AgX\"\n"
                + "except BaseException:\n"
                + "    pass\n"
                + "\n"
                + "COLL = scene.collection\n"
                + "\n"
                + "def aim(ob, target):\n"
                + "    d = Vector(target) - ob.location\n"
                + "    ob.rotation_euler = d.to_track_quat(\"-Z\", \"Y\").to_euler()\n"
                + "\n"
                + "def area(name, loc, look, size, energy, color=(1.0, 1.0, 1.0)):\n"
                + "    lt = bpy.data.lights.new(name, \"AREA\")\n"
                + "    lt.size = size\n"
                + "    lt.energy = energy\n"
                + "    lt.color = color\n"
                + "    ob = bpy.data.objects.new(name, lt)\n"
                + "    ob.location = loc\n"
                + "    aim(ob, look)\n"
                + "    COLL.objects.link(ob)\n"
                + "    return ob\n"
                + "\n"
                + "def point(name, loc, energy, radius=0.05):\n"
                + "    lt = bpy.data.lights.new(name, \"POINT\")\n"
                + "    lt.energy = energy\n"
                + "    lt.shadow_soft_size = radius\n"
                + "    ob = bpy.data.objects.new(name, lt)\n"
                + "    ob.location = loc\n"
                + "    COLL.objects.link(ob)\n"
                + "    return ob\n"
                + "\n"
                + "def cam(name, loc, target, lens=50):\n"
                + "    cd = bpy.data.cameras.new(name)\n"
                + "    cd.lens = lens\n"
                + "    ob = bpy.data.objects.new(name, cd)\n"
                + "    ob.location = loc\n"
                + "    aim(ob, target)\n"
                + "    COLL.objects.link(ob)\n"
                + "    scene.camera = ob\n"
                + "    return ob\n"
                + "\n"
                + "def plane(name, loc, size=20, rot=(0, 0, 0)):\n"
                + "    bpy.ops.mesh.primitive_plane_add(size=size, location=loc, rotation=rot)\n"
                + "    ob = bpy.context.active_object\n"
                + "    ob.name = name\n"
                + "    return ob\n"
                + "\n"
                + "def cube(name, loc, scale=(1, 1, 1), rot=(0, 0, 0)):\n"
                + "    bpy.ops.mesh.primitive_cube_add(size=2, location=loc, rotation=rot)\n"
                + "    ob = bpy.context.active_object\n"
                + "    ob.name = name\n"
                + "    ob.scale = scale\n"
                + "    bpy.ops.object.shade_smooth()\n"
                + "    return ob\n"
                + "\n"
                + "def cyl(name, loc, r=1.0, d=0.6, verts=96):\n"
                + "    bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=d, vertices=verts, location=loc)\n"
                + "    ob = bpy.context.active_object\n"
                + "    ob.name = name\n"
                + "    bpy.ops.object.shade_smooth()\n"
                + "    return ob\n"
                + "\n"
                + "def three_point(target=(0, 0, 1), dist=6.0, key=1400.0, fill=400.0, rim=900.0):\n"
                + "    area(\"Key\", (dist * 0.6, -dist, dist * 0.75), target, 3.0, key)\n"
                + "    area(\"Fill\", (-dist, -dist * 0.5, dist * 0.5), target, 3.5, fill, (0.85, 0.9, 1.0))\n"
                + "    area(\"Rim\", (-dist * 0.4, dist, dist * 0.9), target, 2.0, rim)\n"
                + "\n"
                + "def world(strength, color):\n"
                + "    w = bpy.data.worlds.new(\"World\")\n"
                + "    scene.world = w\n"
                + "    w.use_nodes = True\n"
                + "    bg = w.node_tree.nodes.get(\"Background\")\n"
                + "    if bg:\n"
                + "        bg.inputs[0].default_value = color\n"
                + "        bg.inputs[1].default_value = strength\n"
                + "\n"
                + "if key == \"default\":\n"
                + "    cube(\"Cube\", (0, 0, 0))\n"
                + "    plane(\"Ground\", (0, 0, -1), 20)\n"
                + "    three_point((0, 0, 0), 6.0, 1200.0, 300.0, 700.0)\n"
                + "    cam(\"Camera\", (7.4, -6.5, 4.8), (0, 0, 0), 50)\n"
                + "    world(1.0, (0.05, 0.05, 0.06, 1.0))\n"
                + "elif key == \"blank\":\n"
                + "    plane(\"Ground\", (0, 0, 0), 40)\n"
                + "    three_point((0, 0, 1), 7.0, 1500.0, 400.0, 900.0)\n"
                + "    cam(\"Camera\", (6.0, -7.0, 3.2), (0, 0, 1), 45)\n"
                + "    world(1.0, (0.03, 0.03, 0.035, 1.0))\n"
                + "elif key == \"product\":\n"
                + "    cyl(\"Plinth\", (0, 0, 0.3), 1.4, 0.6)\n"
                + "    plane(\"Backdrop\", (0, 2.6, 1.8), 14, (math.radians(90), 0, 0))\n"
                + "    cube(\"Hero\", (0, 0, 1.1), (0.9, 0.6, 0.6))\n"
                + "    area(\"Softbox_Key\", (2.6, -2.4, 3.0), (0, 0, 1.0), 3.0, 1500.0)\n"
                + "    area(\"Softbox_Fill\", (-2.8, -1.6, 2.0), (0, 0, 1.0), 3.5, 500.0, (0.9, 0.94, 1.0))\n"
                + "    area(\"Rim\", (0, 3.2, 2.6), (0, 0, 1.2), 1.6, 700.0)\n"
                + "    cam(\"Camera\", (4.2, -5.2, 2.4), (0, 0, 1.0), 70)\n"
                + "    world(1.0, (0.12, 0.12, 0.13, 1.0))\n"
                + "elif key == \"portrait\":\n"
                + "    plane(\"BG\", (0, 1.8, 1.6), 12, (math.radians(90), 0, 0))\n"
                + "    area(\"Key\", (1.8, -2.2, 2.2), (0, 0, 1.5), 1.6, 900.0)\n"
                + "    area(\"Fill\", (-2.0, -1.4, 1.6), (0, 0, 1.5), 2.4, 260.0, (0.86, 0.9, 1.0))\n"
                + "    area(\"Rim\", (-0.8, 2.2, 2.4), (0, 0, 1.6), 1.0, 1000.0)\n"
                + "    point(\"EyeLight\", (0, -0.6, 2.0), 60.0, 0.05)\n"
                + "    cam(\"Camera\", (0, -3.4, 1.55), (0, 0, 1.5), 85)\n"
                + "    world(1.0, (0.04, 0.04, 0.045, 1.0))\n"
                + "elif key == \"turntable\":\n"
                + "    cyl(\"Turntable\", (0, 0, 0.12), 2.0, 0.24)\n"
                + "    ctrl = bpy.data.objects.new(\"Turntable_CTRL\", None)\n"
                + "    ctrl.empty_display_size = 1.2\n"
                + "    COLL.objects.link(ctrl)\n"
                + "    cyl(\"Subject\", (0, 0, 1.0), 0.7, 1.4).parent = ctrl\n"
                + "    three_point((0, 0, 1.0), 7.0, 1300.0, 350.0, 800.0)\n"
                + "    cam(\"Camera\", (5.2, -5.2, 2.6), (0, 0, 1.0), 60)\n"
                + "    scene.frame_start = 1\n"
                + "    scene.frame_end = 120\n"
                + "    world(1.0, (0.05, 0.05, 0.055, 1.0))\n"
                + "elif key == \"grid\":\n"
                + "    plane(\"Ground\", (0, 0, 0), 200)\n"
                + "    mod = plane(\"Wire\", (0, 0, 0.01), 200).modifiers.new(\"Wireframe\", \"WIREFRAME\")\n"
                + "    mod.thickness = 0.02\n"
                + "    three_point((0, 0, 0), 12.0, 2000.0, 600.0, 1200.0)\n"
                + "    cam(\"Camera\", (10.0, -12.0, 6.0), (0, 0, 0), 35)\n"
                + "    world(1.0, (0.06, 0.07, 0.08, 1.0))\n"
                + "else:\n"
                + "    three_point((0, 0, 1), 7.0, 1200.0, 350.0, 800.0)\n"
                + "    cam(\"Camera\", (6.0, -7.0, 3.2), (0, 0, 1), 50)\n"
                + "    world(1.0, (0.03, 0.03, 0.035, 1.0))\n"
                + "\n"
                + "bpy.ops.wm.save_as_mainfile(filepath=out, compress=True)\n"
                + "print(\"MW_TPL_OK \" + out)\n";
    }
}
