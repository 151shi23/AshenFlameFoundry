package com.mineways.blender;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 工程编辑：体检、改材质、换贴图、套 UV、调场景、打包、另存、导出。
 *
 * <p>全部通过 {@link BlenderOps} 在沙箱里跑 bpy，所以享受同一套兜底：每项单独 try、
 * 失败只记账不中断、脚本一定回传结果。</p>
 *
 * <p><b>版本兼容是这个模块的重点</b>：同一个 socket 名在 Blender 3.x / 4.x 里不一样
 * （{@code Specular} → {@code Specular IOR Level}、{@code Clearcoat} → {@code Coat Weight}、
 * {@code Transmission} → {@code Transmission Weight}、{@code Emission} → {@code Emission Color}），
 * 视图变换里的 {@code Filmic} 在 4.x 被 {@code AgX} 取代。所以每个字段都走
 * <b>候选名依次尝试</b>，全失败才报错，并且尽量给警告而不是硬失败。</p>
 */
public final class ProjectOps {

    private ProjectOps() {
    }

    // ================================================================ 体检

    /** 工程信息（只读）。 */
    public static final class Info {
        public String blenderVersion = "";
        public String fileVersion = "";
        public String engine = "";
        public String viewTransform = "";
        public int fps = 24;
        public int frameStart = 1;
        public int frameEnd = 1;
        public boolean hasCamera;
        public boolean hasMesh;
        public boolean ffmpegSupported;
        public int imageCount;
        public int totalVerts;
        public int totalFaces;
        public final List<String> materials = new ArrayList<>();
        public final List<String> meshes = new ArrayList<>();
        public final List<String> cameras = new ArrayList<>();
        public final List<String> lights = new ArrayList<>();
        public final List<String> missingFiles = new ArrayList<>();
        public final List<String> noUvMeshes = new ArrayList<>();
        public final List<String> packedImages = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public String summary() {
            final StringBuilder sb = new StringBuilder();
            sb.append("Blender ").append(blenderVersion)
                    .append(" · 工程文件版本 ").append(fileVersion)
                    .append(" · 引擎 ").append(engine)
                    .append(" · 视图变换 ").append(viewTransform)
                    .append('\n');
            sb.append("网格 ").append(meshes.size()).append(" 个（").append(totalVerts)
                    .append(" 顶点 / ").append(totalFaces).append(" 面）· 材质 ")
                    .append(materials.size()).append(" 个 · 贴图 ").append(imageCount).append(" 张")
                    .append("（已打包 ").append(packedImages.size()).append("）\n");
            sb.append("相机 ").append(cameras.isEmpty() ? "无" : cameras.toString())
                    .append(" · 灯 ").append(lights.size())
                    .append(" · 帧 ").append(frameStart).append("-").append(frameEnd)
                    .append(" @ ").append(fps).append("fps")
                    .append(" · FFmpeg ").append(ffmpegSupported ? "可用" : "不可用").append('\n');
            if (!noUvMeshes.isEmpty()) {
                sb.append("没有 UV 的网格 ").append(noUvMeshes.size()).append(" 个：")
                        .append(noUvMeshes.size() > 6 ? noUvMeshes.subList(0, 6) + " …" : noUvMeshes)
                        .append('\n');
            }
            if (!missingFiles.isEmpty()) {
                sb.append("丢失的外部文件 ").append(missingFiles.size()).append(" 个：")
                        .append(missingFiles.size() > 6 ? missingFiles.subList(0, 6) + " …" : missingFiles)
                        .append('\n');
            }
            for (String w : warnings) {
                sb.append("· ").append(w).append('\n');
            }
            return sb.toString();
        }
    }

    /** 打开工程并体检。 */
    public static Info inspect(Context c, File project, BlenderOps.Log log) {
        final Info info = new Info();
        final String code = "try:\n"
                + "    data(\"blender\", \".\".join(str(x) for x in bpy.app.version))\n"
                + "    data(\"version_file\", \".\".join(str(x) for x in bpy.data.version))\n"
                + "    sc = bpy.context.scene\n"
                + "    data(\"engine\", sc.render.engine)\n"
                + "    data(\"view_transform\", sc.view_settings.view_transform)\n"
                + "    data(\"fps\", int(sc.render.fps))\n"
                + "    data(\"frame_start\", int(sc.frame_start))\n"
                + "    data(\"frame_end\", int(sc.frame_end))\n"
                + "    try:\n"
                + "        data(\"ffmpeg\", bool(bpy.app.build_options.codec_ffmpeg))\n"
                + "    except BaseException:\n"
                + "        data(\"ffmpeg\", False)\n"
                + "    mats = []\n"
                + "    for m in bpy.data.materials:\n"
                + "        mats.append({\"name\": m.name, \"users\": int(m.users), \"nodes\": bool(m.use_nodes)})\n"
                + "    data(\"materials\", mats)\n"
                + "    meshes = []\n"
                + "    for ob in bpy.data.objects:\n"
                + "        if ob.type != \"MESH\":\n"
                + "            continue\n"
                + "        me = ob.data\n"
                + "        meshes.append({\"name\": ob.name, \"verts\": len(me.vertices),\n"
                + "                       \"faces\": len(me.polygons),\n"
                + "                       \"uvs\": [u.name for u in me.uv_layers]})\n"
                + "    data(\"meshes\", meshes)\n"
                + "    data(\"cameras\", [o.name for o in bpy.data.objects if o.type == \"CAMERA\"])\n"
                + "    data(\"lights\", [o.name for o in bpy.data.objects if o.type == \"LIGHT\"])\n"
                + "    imgs = []\n"
                + "    missing = []\n"
                + "    for im in bpy.data.images:\n"
                + "        packed = bool(im.packed_file)\n"
                + "        if im.source == \"FILE\" and im.filepath and not packed:\n"
                + "            try:\n"
                + "                real = bpy.path.abspath(im.filepath)\n"
                + "            except BaseException:\n"
                + "                real = im.filepath\n"
                + "            if not os.path.exists(real):\n"
                + "                missing.append(im.name + \" -> \" + im.filepath)\n"
                + "        imgs.append({\"name\": im.name, \"packed\": packed,\n"
                + "                     \"w\": int(im.size[0]), \"h\": int(im.size[1])})\n"
                + "    data(\"images\", imgs)\n"
                + "    data(\"missing\", missing)\n"
                + "    if getattr(bpy.app, \"autoexec_fail\", False):\n"
                + "        warn(\"工程里的脚本/driver 被安全策略拦下\")\n"
                + "except BaseException as e:\n"
                + "    err(\"inspect: %s\" % e)\n";
        final BlenderOps.Result r = BlenderOps.run(c, project, code, 300, log);
        if (!r.ok && r.data.length() == 0) {
            info.warnings.add(r.summary());
            return info;
        }
        final JSONObject d = r.data;
        info.blenderVersion = d.optString("blender", "?");
        info.fileVersion = d.optString("version_file", "?");
        info.engine = d.optString("engine", "");
        info.viewTransform = d.optString("view_transform", "");
        info.fps = d.optInt("fps", 24);
        info.frameStart = d.optInt("frame_start", 1);
        info.frameEnd = d.optInt("frame_end", 1);
        info.ffmpegSupported = d.optBoolean("ffmpeg", false);
        for (JSONObject m : objects(d.optJSONArray("materials"))) {
            info.materials.add(m.optString("name"));
        }
        for (JSONObject m : objects(d.optJSONArray("meshes"))) {
            info.meshes.add(m.optString("name"));
            info.totalVerts += m.optInt("verts");
            info.totalFaces += m.optInt("faces");
            final JSONArray uvs = m.optJSONArray("uvs");
            if (uvs == null || uvs.length() == 0) info.noUvMeshes.add(m.optString("name"));
        }
        for (String s : strings(d.optJSONArray("cameras"))) {
            info.cameras.add(s);
            info.hasCamera = true;
        }
        info.lights.addAll(strings(d.optJSONArray("lights")));
        final JSONArray imgs = d.optJSONArray("images");
        if (imgs != null) {
            info.imageCount = imgs.length();
            for (int i = 0; i < imgs.length(); i++) {
                final JSONObject im = imgs.optJSONObject(i);
                if (im != null && im.optBoolean("packed")) info.packedImages.add(im.optString("name"));
            }
        }
        info.missingFiles.addAll(strings(d.optJSONArray("missing")));
        info.warnings.addAll(r.warnings);
        info.warnings.addAll(r.errors);
        info.hasMesh = !info.meshes.isEmpty();
        return info;
    }

    // ================================================================ 材质

    /** 材质字段的候选 socket 名（4.x 与 3.x 名字不同，按顺序试）。 */
    static String socketList(String canonical) {
        switch (canonical) {
            case "Base Color":
                return "[\"Base Color\", \"Base\"]";
            case "Metallic":
                return "[\"Metallic\"]";
            case "Roughness":
                return "[\"Roughness\"]";
            case "IOR":
                return "[\"IOR\"]";
            case "Alpha":
                return "[\"Alpha\"]";
            case "Emission Color":
                return "[\"Emission Color\", \"Emission\"]";
            case "Emission Strength":
                return "[\"Emission Strength\"]";
            case "Specular":
                return "[\"Specular IOR Level\", \"Specular\"]";
            case "Coat Weight":
                return "[\"Coat Weight\", \"Clearcoat\"]";
            case "Sheen Weight":
                return "[\"Sheen Weight\", \"Sheen\"]";
            case "Transmission Weight":
                return "[\"Transmission Weight\", \"Transmission\"]";
            default:
                return "[\"" + canonical + "\"]";
        }
    }

    /** 贴图槽位 → 目标 socket 候选名。 */
    static String mapSocketList(String map) {
        switch (map) {
            case "base_color":
                return "[\"Base Color\", \"Base\"]";
            case "normal":
                return "[\"Normal\"]";
            case "roughness":
                return "[\"Roughness\"]";
            case "metallic":
                return "[\"Metallic\"]";
            case "emission":
                return "[\"Emission Color\", \"Emission\"]";
            case "alpha":
                return "[\"Alpha\"]";
            case "ao":
                return "[\"Ambient Occlusion\"]";
            default:
                return "[\"" + map + "\"]";
        }
    }

    /** 改材质数值字段（颜色字段传 4 个数，其余 1 个）。 */
    public static BlenderOps.Result setMaterialValues(Context c, File project, String materialRegex,
                                                      String field, double[] value, BlenderOps.Log log) {
        final StringBuilder py = new StringBuilder();
        py.append("import re\n");
        py.append("pat = re.compile(r'''").append(materialRegex == null ? "" : materialRegex).append("''')\n");
        py.append("sockets = ").append(socketList(field)).append("\n");
        py.append("val = ").append(pyList(value)).append("\n");
        py.append("targets = [m for m in bpy.data.materials if (not pat.pattern) or pat.search(m.name)]\n");
        py.append("if not targets:\n    warn(\"没有材质匹配: \" + (pat.pattern or \"(全部)\"))\n");
        py.append("changed = []\n");
        py.append("for m in targets:\n");
        py.append("    if not m.use_nodes:\n        m.use_nodes = True\n");
        py.append("    hit = False\n");
        py.append("    for n in m.node_tree.nodes:\n");
        py.append("        if n.type != \"BSDF_PRINCIPLED\":\n            continue\n");
        py.append("        for sname in sockets:\n");
        py.append("            sock = n.inputs.get(sname)\n");
        py.append("            if sock is None:\n                continue\n");
        py.append("            try:\n");
        py.append("                if len(val) >= 3:\n");
        py.append("                    sock.default_value = (val[0], val[1], val[2], val[3] if len(val) > 3 else 1.0)\n");
        py.append("                else:\n");
        py.append("                    sock.default_value = val[0]\n");
        py.append("                hit = True\n");
        py.append("            except BaseException as e:\n");
        py.append("                warn(\"%s.%s: %s\" % (m.name, sname, e))\n");
        py.append("            break\n");
        py.append("        if hit:\n            break\n");
        py.append("    if hit:\n");
        py.append("        changed.append(m.name)\n");
        py.append("        try:\n");
        py.append("            m.diffuse_color = (val[0], val[1], val[2], 1.0) if len(val) >= 3 else (0.8, 0.8, 0.8, 1.0)\n");
        py.append("        except BaseException:\n            pass\n");
        py.append("    else:\n");
        py.append("        err(\"%s 没有 %s 这个输入（Blender 版本不兼容？）\" % (m.name, sockets[0]))\n");
        py.append("data(\"changed\", changed)\n");
        return BlenderOps.run(c, project, py.toString(), 300, log);
    }

    /** 替换材质贴图（按槽位）。 */
    public static BlenderOps.Result replaceTexture(Context c, File project, String materialRegex,
                                                   String map, String imagePath, BlenderOps.Log log) {
        final StringBuilder py = new StringBuilder();
        py.append("import re\n");
        py.append("pat = re.compile(r'''").append(materialRegex == null ? "" : materialRegex).append("''')\n");
        py.append("sockets = ").append(mapSocketList(map)).append("\n");
        py.append("path = ").append(pyq(imagePath)).append("\n");
        py.append("if not os.path.exists(path):\n");
        py.append("    err(\"贴图不存在: \" + path)\n");
        py.append("    data(\"changed\", [])\n");
        py.append("else:\n");
        py.append("    try:\n");
        py.append("        img = bpy.data.images.load(path, check_existing=True)\n");
        py.append("    except BaseException as e:\n");
        py.append("        err(\"贴图加载失败: %s\" % e)\n");
        py.append("        img = None\n");
        py.append("    done = []\n");
        py.append("    if img is not None:\n");
        py.append("        for m in bpy.data.materials:\n");
        py.append("            if pat.pattern and not pat.search(m.name):\n                continue\n");
        py.append("            if not m.use_nodes:\n                m.use_nodes = True\n");
        py.append("            tree = m.node_tree\n");
        py.append("            hit = False\n");
        py.append("            for sname in sockets:\n");
        py.append("                for n in tree.nodes:\n");
        py.append("                    if n.type != \"BSDF_PRINCIPLED\":\n                        continue\n");
        py.append("                    sock = n.inputs.get(sname)\n");
        py.append("                    if sock is None:\n                        continue\n");
        py.append("                    linked = sock.is_linked and len(sock.links) > 0\n");
        py.append("                    if linked:\n");
        py.append("                        src = sock.links[0].from_node\n");
        py.append("                        if src.type == \"TEX_IMAGE\":\n");
        py.append("                            src.image = img\n");
        py.append("                            hit = True\n");
        py.append("                        else:\n");
        py.append("                            tex = tree.nodes.new(\"ShaderNodeTexImage\")\n");
        py.append("                            tex.image = img\n");
        py.append("                            tex.location = (src.location.x - 240, src.location.y)\n");
        py.append("                            tree.links.new(tex.outputs[\"Color\"], sock)\n");
        py.append("                            hit = True\n");
        py.append("                    else:\n");
        py.append("                        tex = tree.nodes.new(\"ShaderNodeTexImage\")\n");
        py.append("                        tex.image = img\n");
        py.append("                        tex.location = (n.location.x - 240, n.location.y - 120)\n");
        py.append("                        tree.links.new(tex.outputs[\"Color\"], sock)\n");
        py.append("                        hit = True\n");
        py.append("                    if hit:\n                        break\n");
        py.append("                if hit:\n                    break\n");
        py.append("            if hit:\n");
        py.append("                done.append(m.name)\n");
        py.append("            else:\n");
        py.append("                warn(\"%s 没有 %s 贴图槽\" % (m.name, sockets[0]))\n");
        py.append("    data(\"changed\", done)\n");
        py.append("    if not done:\n        warn(\"没有任何材质被替换\")\n");
        return BlenderOps.run(c, project, py.toString(), 300, log);
    }

    /** 新建材质并赋给匹配的网格。 */
    public static BlenderOps.Result applyMaterial(Context c, File project, String materialName,
                                                  String objectRegex, double[] rgba,
                                                  double metallic, double roughness, BlenderOps.Log log) {
        final StringBuilder py = new StringBuilder();
        py.append("import re\n");
        py.append("pat = re.compile(r'''").append(objectRegex == null ? "" : objectRegex).append("''')\n");
        py.append("name = ").append(pyq(materialName)).append("\n");
        py.append("rgba = ").append(pyList(rgba)).append("\n");
        py.append("m = bpy.data.materials.get(name)\n");
        py.append("if m is None:\n    m = bpy.data.materials.new(name)\n");
        py.append("m.use_nodes = True\n");
        py.append("bsdf = None\n");
        py.append("for n in m.node_tree.nodes:\n    if n.type == \"BSDF_PRINCIPLED\":\n        bsdf = n\n");
        py.append("if bsdf is None:\n");
        py.append("    bsdf = m.node_tree.nodes.new(\"ShaderNodeBsdfPrincipled\")\n");
        py.append("    out = None\n");
        py.append("    for n in m.node_tree.nodes:\n        if n.type == \"OUTPUT_MATERIAL\":\n            out = n\n");
        py.append("    if out:\n        m.node_tree.links.new(bsdf.outputs[0], out.inputs[0])\n");
        py.append("for sname in ").append(socketList("Base Color")).append(":\n");
        py.append("    s = bsdf.inputs.get(sname)\n    if s:\n        s.default_value = (rgba[0], rgba[1], rgba[2], rgba[3] if len(rgba) > 3 else 1.0)\n        break\n");
        py.append("for key, v in ((\"Metallic\", ").append(fmt(metallic)).append("), (\"Roughness\", ")
                .append(fmt(roughness)).append(")):\n");
        py.append("    s = bsdf.inputs.get(key)\n    if s:\n        s.default_value = v\n");
        py.append("try:\n    m.diffuse_color = (rgba[0], rgba[1], rgba[2], 1.0)\nexcept BaseException:\n    pass\n");
        py.append("touched = []\n");
        py.append("for ob in bpy.data.objects:\n");
        py.append("    if ob.type != \"MESH\":\n        continue\n");
        py.append("    if pat.pattern and not pat.search(ob.name):\n        continue\n");
        py.append("    ob.data.materials.clear()\n");
        py.append("    ob.data.materials.append(m)\n");
        py.append("    touched.append(ob.name)\n");
        py.append("data(\"objects\", touched)\n");
        py.append("if not touched:\n    warn(\"没有匹配到网格（对象名正则：\" + (pat.pattern or \"(全部)\") + \"）\")\n");
        return BlenderOps.run(c, project, py.toString(), 300, log);
    }

    // ================================================================ UV

    /** UV 操作：smart_project / unwrap / cube / clear。 */
    public static BlenderOps.Result uvOp(Context c, File project, String op, String objectRegex,
                                         double angleDeg, double margin, BlenderOps.Log log) {
        final StringBuilder py = new StringBuilder();
        py.append("import re, math\n");
        py.append("op = ").append(pyq(op)).append("\n");
        py.append("pat = re.compile(r'''").append(objectRegex == null ? "" : objectRegex).append("''')\n");
        py.append("ang = math.radians(max(1.0, min(89.0, ").append(fmt(angleDeg)).append(")))\n");
        py.append("mg = max(0.0, min(2.0, ").append(fmt(margin)).append("))\n");
        py.append("meshes = [o for o in bpy.data.objects if o.type == \"MESH\" and (not pat.pattern or pat.search(o.name))]\n");
        py.append("if not meshes:\n    err(\"没有匹配的网格\")\n");
        py.append("def attempt(fn):\n");
        py.append("    try:\n        fn()\n        return True, \"\"\n");
        py.append("    except BaseException as e:\n        return False, str(e)\n");
        py.append("done = []\nskipped = []\n");
        py.append("for ob in meshes:\n");
        py.append("    try:\n");
        py.append("        try:\n            bpy.ops.object.mode_set(mode=\"OBJECT\")\n        except BaseException:\n            pass\n");
        py.append("        bpy.ops.object.select_all(action=\"DESELECT\")\n");
        py.append("        ob.select_set(True)\n");
        py.append("        bpy.context.view_layer.objects.active = ob\n");
        py.append("        if not ob.data.uv_layers:\n");
        py.append("            ob.data.uv_layers.new(name=\"UVMap\")\n");
        py.append("            skipped.append(ob.name + \"（新建了 UV 层）\")\n");
        py.append("        bpy.ops.object.mode_set(mode=\"EDIT\")\n");
        py.append("        bpy.ops.mesh.select_all(action=\"SELECT\")\n");
        py.append("        ok = False\n        msg = \"\"\n");
        py.append("        if op == \"smart_project\":\n");
        py.append("            ok, msg = attempt(lambda: bpy.ops.uv.smart_project(angle_limit=ang, island_margin=mg, correct_aspect=True, scale_to_bounds=False))\n");
        py.append("            if not ok:\n                ok, msg = attempt(lambda: bpy.ops.uv.smart_project())\n");
        py.append("            if not ok:\n                ok, msg = attempt(lambda: bpy.ops.uv.smart_project(angle_limit=66.0, island_margin=0.02))\n");
        py.append("        elif op == \"unwrap\":\n");
        py.append("            ok, msg = attempt(lambda: bpy.ops.uv.unwrap(method=\"ANGLE_BASED\", angle_limit=ang, margin=mg))\n");
        py.append("            if not ok:\n                ok, msg = attempt(lambda: bpy.ops.uv.unwrap())\n");
        py.append("        elif op == \"cube\":\n");
        py.append("            ok, msg = attempt(lambda: bpy.ops.uv.cube_project(cube_size=max(0.01, mg if mg > 0 else 0.2)))\n");
        py.append("            if not ok:\n                ok, msg = attempt(lambda: bpy.ops.uv.cube_project())\n");
        py.append("        elif op == \"clear\":\n");
        py.append("            ok, msg = attempt(lambda: bpy.ops.uv.unwrap(method=\"MINIMUM_CURVE\"))\n");
        py.append("        else:\n");
        py.append("            err(\"未知 UV 操作: \" + op)\n            ok = True\n");
        py.append("        try:\n            bpy.ops.object.mode_set(mode=\"OBJECT\")\n        except BaseException:\n            pass\n");
        py.append("        if ok:\n            done.append(ob.name)\n");
        py.append("        else:\n            skipped.append(ob.name + \": \" + msg)\n");
        py.append("    except BaseException as e:\n");
        py.append("        skipped.append(ob.name + \": \" + str(e))\n");
        py.append("data(\"done\", done)\ndata(\"skipped\", skipped)\n");
        py.append("if skipped:\n    warn(\"%d 个网格没能展开\" % len(skipped))\n");
        return BlenderOps.run(c, project, py.toString(), 900, log);
    }

    // ================================================================ 场景

    /** 场景补丁（null / ≤0 / 空串 = 不改）。 */
    public static final class ScenePatch {
        public int frameStart;
        public int frameEnd;
        public int frameStep;
        public int fps;
        public int resX;
        public int resY;
        public String engine;
        public String viewTransform;
        public String look;
        public String camera;
        public double worldStrength;
        public boolean deviceGpu;
        public int threads;
    }

    public static BlenderOps.Result sceneOp(Context c, File project, ScenePatch patch, BlenderOps.Log log) {
        final StringBuilder py = new StringBuilder();
        py.append("sc = bpy.context.scene\nchanged = []\n");
        if (patch.frameStart > 0) {
            py.append("sc.frame_start = ").append(patch.frameStart).append('\n');
            py.append("changed.append(\"frame_start\")\n");
        }
        if (patch.frameEnd > 0) {
            py.append("sc.frame_end = max(sc.frame_start, ").append(patch.frameEnd).append(")\n");
            py.append("changed.append(\"frame_end\")\n");
        }
        if (patch.frameStep > 0) {
            py.append("try:\n    sc.frame_step = ").append(patch.frameStep)
                    .append("\n    changed.append(\"frame_step\")\nexcept BaseException as e:\n    warn(\"frame_step: %s\" % e)\n");
        }
        if (patch.fps > 0) {
            py.append("sc.render.fps = max(1, min(120, ").append(patch.fps).append("))\n");
            py.append("changed.append(\"fps\")\n");
        }
        if (patch.resX > 0 && patch.resY > 0) {
            py.append("sc.render.resolution_x = ").append(patch.resX).append('\n');
            py.append("sc.render.resolution_y = ").append(patch.resY).append('\n');
            py.append("sc.render.resolution_percentage = 100\n");
            py.append("changed.append(\"resolution\")\n");
        }
        if (patch.threads > 0) {
            py.append("cy = getattr(sc, \"cycles\", None)\n");
            py.append("if cy:\n");
            py.append("    try:\n        cy.threads_mode = \"FIXED\"\n        cy.threads = ").append(patch.threads)
                    .append("\n        changed.append(\"threads\")\n    except BaseException as e:\n        warn(\"threads: %s\" % e)\n");
        }
        if (patch.deviceGpu) {
            py.append("cy = getattr(sc, \"cycles\", None)\n");
            py.append("if cy:\n");
            py.append("    for d in (\"GPU\", \"CPU\"):\n");
            py.append("        try:\n            cy.device = d\n            changed.append(\"device=\" + d)\n            break\n        except BaseException:\n            pass\n");
            py.append("    if cy.device != \"GPU\":\n        warn(\"这个 Blender 构建没有可用 GPU 后端，继续用 CPU\")\n");
        }
        if (patch.engine != null && !patch.engine.isEmpty()) {
            py.append("want = ").append(pyq(patch.engine)).append("\n");
            py.append("cands = [want]\n");
            py.append("if want == \"BLENDER_EEVEE_NEXT\":\n    cands.append(\"BLENDER_EEVEE\")\n");
            py.append("elif want == \"BLENDER_EEVEE\":\n    cands.append(\"BLENDER_EEVEE_NEXT\")\n");
            py.append("got = None\n");
            py.append("for c in cands:\n");
            py.append("    try:\n        sc.render.engine = c\n        got = c\n        break\n    except BaseException:\n        pass\n");
            py.append("if got is None:\n    err(\"引擎不可用: \" + want)\nelse:\n    changed.append(\"engine=\" + got)\n");
        }
        if (patch.viewTransform != null && !patch.viewTransform.isEmpty()) {
            py.append("cands = [").append(pyq(patch.viewTransform)).append(", \"AgX\", \"Filmic\", \"Standard\"]\n");
            py.append("got = None\n");
            py.append("for c in cands:\n");
            py.append("    try:\n        sc.view_settings.view_transform = c\n        got = c\n        break\n    except BaseException:\n        pass\n");
            py.append("if got is None:\n    warn(\"视图变换都不可用，保留工程设置\")\nelse:\n    changed.append(\"view_transform=\" + got)\n");
        }
        if (patch.look != null && !patch.look.isEmpty()) {
            py.append("try:\n    sc.view_settings.look = ").append(pyq(patch.look))
                    .append("\n    changed.append(\"look\")\nexcept BaseException as e:\n    warn(\"look 不可用: %s\" % e)\n");
        }
        if (patch.camera != null && !patch.camera.isEmpty()) {
            py.append("ob = bpy.data.objects.get(").append(pyq(patch.camera)).append(")\n");
            py.append("if ob and ob.type == \"CAMERA\":\n    sc.camera = ob\n    changed.append(\"camera=\" + ob.name)\n");
            py.append("else:\n    err(\"相机不存在: \" + ").append(pyq(patch.camera)).append(")\n");
        }
        if (patch.worldStrength > 0) {
            py.append("w = sc.world\n");
            py.append("if w and w.use_nodes:\n");
            py.append("    bg = w.node_tree.nodes.get(\"Background\")\n");
            py.append("    if bg:\n");
            py.append("        bg.inputs[1].default_value = ").append(fmt(patch.worldStrength)).append("\n");
            py.append("        changed.append(\"world=\" + str(").append(fmt(patch.worldStrength)).append("))\n");
            py.append("    else:\n        warn(\"世界节点里没有 Background\")\n");
            py.append("else:\n    warn(\"工程没有世界环境，跳过\")\n");
        }
        py.append("data(\"changed\", changed)\n");
        return BlenderOps.run(c, project, py.toString(), 300, log);
    }

    // ================================================================ 打包 / 保存 / 导出

    /** 把外部贴图全部打包进 .blend（做单文件工程的关键一步）。 */
    public static BlenderOps.Result packAll(Context c, File project, BlenderOps.Log log) {
        final String code = "packed = 0\nfailed = []\n"
                + "for im in list(bpy.data.images):\n"
                + "    if im.source != \"FILE\" or im.packed_file:\n        continue\n"
                + "    try:\n        im.pack()\n        packed += 1\n"
                + "    except BaseException as e:\n        failed.append(im.name + \": \" + str(e))\n"
                + "try:\n    bpy.ops.file.pack_all()\nexcept BaseException as e:\n    warn(\"pack_all: %s\" % e)\n"
                + "data(\"packed\", packed)\n"
                + "if failed:\n    warn(\"%d 张贴图打包失败\" % len(failed))\n";
        return BlenderOps.run(c, project, code, 900, log);
    }

    /** 另存为（可先把贴图打包进去）。 */
    public static BlenderOps.Result saveAs(Context c, File project, File target, boolean pack,
                                           BlenderOps.Log log) {
        final StringBuilder py = new StringBuilder();
        if (pack) {
            py.append("for im in list(bpy.data.images):\n");
            py.append("    if im.source == \"FILE\" and not im.packed_file:\n");
            py.append("        try:\n            im.pack()\n        except BaseException:\n            pass\n");
        }
        py.append("bpy.ops.wm.save_as_mainfile(filepath=").append(pyq(target.getAbsolutePath()))
                .append(", compress=True)\n");
        py.append("data(\"saved\", bpy.data.filepath)\n");
        final BlenderOps.Result r = BlenderOps.run(c, project, py.toString(), 900, log);
        if (r.ok && target.isFile()) {
            FileUtil.makeTreeReadable(target.getParentFile());
        }
        return r;
    }

    /** 导出 glTF / GLB / OBJ / USD / FBX。 */
    public static BlenderOps.Result export(Context c, File project, File target, String format,
                                            BlenderOps.Log log) {
        final String fmt = format == null ? "GLB" : format.toUpperCase(java.util.Locale.ROOT);
        final StringBuilder py = new StringBuilder();
        py.append("fmt = ").append(pyq(fmt)).append("\n");
        py.append("path = ").append(pyq(target.getAbsolutePath())).append("\n");
        py.append("cands = {\n");
        py.append("    \"GLB\": [lambda: bpy.ops.export_scene.gltf(filepath=path, export_format=\"GLB\")],\n");
        py.append("    \"GLTF\": [lambda: bpy.ops.export_scene.gltf(filepath=path, export_format=\"GLTF_SEPARATE\")],\n");
        py.append("    \"OBJ\": [lambda: bpy.ops.wm.obj_export(filepath=path),\n");
        py.append("           lambda: getattr(bpy.ops.wm, \"stl_export\")(filepath=path)],\n");
        py.append("    \"USD\": [lambda: bpy.ops.wm.usd_export(filepath=path)],\n");
        py.append("    \"FBX\": [lambda: bpy.ops.export_scene.fbx(filepath=path)],\n");
        py.append("}\n");
        py.append("fns = cands.get(fmt)\n");
        py.append("if not fns:\n    err(\"未知导出格式: \" + fmt)\nelse:\n");
        py.append("    ok = False\n    msgs = []\n");
        py.append("    for fn in fns:\n");
        py.append("        try:\n            fn()\n            ok = True\n            break\n");
        py.append("        except BaseException as e:\n            msgs.append(str(e))\n");
        py.append("    if ok:\n        data(\"done\", True)\n");
        py.append("    else:\n        err(\"导出 %s 失败: %s\" % (fmt, \" / \".join(msgs)))\n");
        final BlenderOps.Result r = BlenderOps.run(c, project, py.toString(), 1200, log);
        if (r.ok && target.isFile()) {
            FileUtil.makeTreeReadable(target.getParentFile());
        }
        return r;
    }

    /** 无相机时补一台默认相机（预检自动修复用）。 */
    public static BlenderOps.Result ensureCamera(Context c, File project, BlenderOps.Log log) {
        final String code = "sc = bpy.context.scene\n"
                + "if sc.camera is not None:\n"
                + "    data(\"camera\", sc.camera.name)\n"
                + "else:\n"
                + "    import mathutils\n"
                + "    cd = bpy.data.cameras.new(\"MW_Camera\")\n"
                + "    cd.lens = 50\n"
                + "    ob = bpy.data.objects.new(\"MW_Camera\", cd)\n"
                + "    ob.location = (6.0, -7.0, 3.2)\n"
                + "    d = mathutils.Vector((0, 0, 1)) - ob.location\n"
                + "    ob.rotation_euler = d.to_track_quat(\"-Z\", \"Y\").to_euler()\n"
                + "    sc.collection.objects.link(ob)\n"
                + "    sc.camera = ob\n"
                + "    data(\"camera\", ob.name)\n"
                + "    warn(\"工程里没有相机，已自动补一台\")\n";
        return BlenderOps.run(c, project, code, 300, log);
    }

    /** 把贴图复制进工程目录（保持相对引用可用）。 */
    public static File importTexture(File project, File src) {
        final File dir = new File(project.getParentFile(), "textures");
        if (!FileUtil.mkdirs(dir)) return src;
        final File dst = new File(dir, src.getName());
        try {
            FileUtil.copy(src, dst);
            return dst;
        } catch (Throwable t) {
            return src;
        }
    }

    // ================================================================ 小工具

    /** 把字符串安全嵌进 Python 单引号字面量（其它模块生成 bpy 脚本时复用）。 */
    public static String pyq(String s) {
        return "'" + String.valueOf(s).replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String pyList(double[] v) {
        if (v == null || v.length == 0) return "[0.0]";
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(v[i]));
        }
        return sb.append(']').toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    private static List<JSONObject> objects(JSONArray a) {
        final List<JSONObject> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    private static List<String> strings(JSONArray a) {
        final List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            final String s = a.optString(i);
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }
}
