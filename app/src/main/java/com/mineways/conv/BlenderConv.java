package com.mineways.conv;

import android.content.Context;

import com.mineways.blender.BlenderOps;
import com.mineways.blender.BlenderEnv;
import com.mineways.blender.ProjectOps;

import java.io.File;

/**
 * 用真 Blender 做转换 —— 专门补 Assimp 没有的方向：
 * <ul>
 *   <li><b>任意模型 → .blend</b>：Assimp 没有 .blend 导出器，只能让 Blender 导入后另存；</li>
 *   <li><b>旧版 .blend → 当前版本 .blend</b>（顺带升级格式）；</li>
 *   <li>GLB/GLTF 里的贴图会被 Blender 打包进 .blend，不再出现「转完丢贴图」。</li>
 * </ul>
 *
 * <p>导入算子的名字在各版本间变过（4.0 起 OBJ/STL/PLY 挪到 {@code bpy.ops.wm.*}），
 * 所以这里对每种格式都准备多个候选，逐个试。</p>
 */
public final class BlenderConv {

    private BlenderConv() {
    }

    public static boolean ready(Context c) {
        return com.mineways.blender.RootShell.isReady() && BlenderEnv.installed(c);
    }

    /** 把任意支持的模型转成 .blend。 */
    public static FmtConv.Result toBlend(Context c, File src, File out, BlenderOps.Log log) {
        if (!ready(c)) {
            return new FmtConv.Result(false, "需要先装「Blender 环境」（Blender 渲染页 → 安装 / 更新）");
        }
        final String code = script(src, out);
        final BlenderOps.Result r = BlenderOps.run(c, null, code, 1800, log);
        if (!out.isFile() || out.length() < 64) {
            final String why = r.summary().isEmpty() ? "没有产出文件" : r.summary();
            return new FmtConv.Result(false, "Blender 转换失败：\n" + why);
        }
        com.mineways.blender.FileUtil.makeTreeReadable(out.getParentFile());
        return new FmtConv.Result(true, "用 Blender 转成 .blend：" + out.getName()
                + "（" + com.mineways.blender.FileUtil.human(out.length()) + "）");
    }

    /** 生成 bpy 脚本：按扩展名选导入器，逐个候选试，最后 save_as_mainfile。 */
    static String script(File src, File out) {
        final StringBuilder py = new StringBuilder();
        py.append("src = ").append(ProjectOps.pyq(src.getAbsolutePath())).append('\n');
        py.append("out = ").append(ProjectOps.pyq(out.getAbsolutePath())).append('\n');
        py.append("ext = os.path.splitext(src)[1].lower()\n");
        py.append("bpy.ops.wm.read_homefile(use_empty=True)\n");
        py.append("warn(\"工程内容：\" + str(len(bpy.data.objects)) + \" 个对象\")\n");
        py.append("def attempt(desc, fn):\n");
        py.append("    try:\n");
        py.append("        fn()\n");
        py.append("        data(\"used\", desc)\n");
        py.append("        return True\n");
        py.append("    except BaseException as e:\n");
        py.append("        warn(desc + \" 不可用: \" + str(e)[:120])\n");
        py.append("        return False\n");
        py.append("ok = False\n");
        py.append("if ext == \".obj\"):\n");
        py.append("    ok = attempt(\"wm.obj_import\", lambda: bpy.ops.wm.obj_import(filepath=src)) or attempt(\"import_scene.obj\", lambda: bpy.ops.import_scene.obj(filepath=src))\n");
        py.append("elif ext in (\".gltf\", \".glb\"):\n");
        py.append("    ok = attempt(\"import_scene.gltf\", lambda: bpy.ops.import_scene.gltf(filepath=src))\n");
        py.append("elif ext == \".fbx\":\n");
        py.append("    ok = attempt(\"import_scene.fbx\", lambda: bpy.ops.import_scene.fbx(filepath=src))\n");
        py.append("elif ext == \".stl\":\n");
        py.append("    ok = attempt(\"wm.stl_import\", lambda: bpy.ops.wm.stl_import(filepath=src)) or attempt(\"import_mesh.stl\", lambda: bpy.ops.import_mesh.stl(filepath=src))\n");
        py.append("elif ext == \".ply\":\n");
        py.append("    ok = attempt(\"wm.ply_import\", lambda: bpy.ops.wm.ply_import(filepath=src)) or attempt(\"import_mesh.ply\", lambda: bpy.ops.import_mesh.ply(filepath=src))\n");
        py.append("elif ext == \".dae\":\n");
        py.append("    ok = attempt(\"wm.collada_import\", lambda: bpy.ops.wm.collada_import(filepath=src))\n");
        py.append("elif ext == \".3ds\":\n");
        py.append("    ok = attempt(\"import_scene.autodesk_3ds\", lambda: bpy.ops.import_scene.autodesk_3ds(filepath=src))\n");
        py.append("elif ext == \".blend\":\n");
        py.append("    ok = attempt(\"wm.open_mainfile\", lambda: bpy.ops.wm.open_mainfile(filepath=src))\n");
        py.append("    data(\"used\", \"wm.open_mainfile（顺带升级到当前版本）\")\n");
        py.append("else:\n");
        py.append("    err(\"Blender 这条路线不认识 \" + ext + \"（可用：obj/gltf/glb/fbx/stl/ply/dae/3ds/blend）\")\n");
        py.append("if ok:\n");
        py.append("    n = len(bpy.data.objects)\n");
        py.append("    data(\"objects\", n)\n");
        py.append("    if n == 0:\n");
        py.append("        err(\"导入成功但场景是空的，文件可能不含网格\")\n");
        py.append("    else:\n");
        py.append("        img = len([i for i in bpy.data.images if i.packed_file])\n");
        py.append("        data(\"packed_images\", img)\n");
        py.append("        try:\n");
        py.append("            bpy.ops.wm.save_as_mainfile(filepath=out, compress=True)\n");
        py.append("        except BaseException as e:\n");
        py.append("            err(\"保存 .blend 失败: %s\" % e)\n");
        return py.toString();
    }
}
