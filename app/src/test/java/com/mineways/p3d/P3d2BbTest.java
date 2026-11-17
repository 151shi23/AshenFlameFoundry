package com.mineways.p3d;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * P3D → Blockbench 内核的回归锚点。
 *
 * <p>期望值一个都不是编的：全部来自上游工具包里那份 <b>做过真 Blockbench 4.12 实机验收</b> 的
 * {@code p3d2bb.py}（v5.2）在同一份夹具上的输出。本机是 6 份真工程 × 静态/骨骼两套逐一比对，
 * 这里留最小那份（3.0 工程，含 1 盏灯、2 个 clip）当固定输入，于是「字段偏移改错就少一批网格」
 * 「骨骼版把中间组也带上旋转（旧版 Blockbench 会角色内部错乱）」「灯光回转字段名写成大写」
 * 这类回退会在装机之前就红在这里。
 *
 * <p>注意 {@code groups} 数的是「进入过组输出」的次数（含被判空丢弃的组），
 * 所以它比 outliner 里真实出现的组数 113 大 —— 这与参照实现一致，不是缺陷。
 */
public class P3d2BbTest {

    /** 夹具里真实存在的对象名（父链 11 层，含 3 个网格）。 */
    private static final String PART = "Left_eye";

    private static byte[] PRISMA;

    @BeforeClass
    public static void loadFixture() throws Exception {
        InputStream in = P3d2BbTest.class.getResourceAsStream("/p3d/sample-project.prisma");
        assertNotNull("测试夹具缺失：app/src/test/resources/p3d/sample-project.prisma", in);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            PRISMA = bos.toByteArray();
        } finally {
            in.close();
        }
        assertTrue("夹具太小，不像工程包", PRISMA.length > 10000);
    }

    private static P3d2Bb.Result convert(P3d2Bb.Opts o) throws Exception {
        return P3d2Bb.convert(PRISMA, o);
    }

    private static P3d2Bb.Result run(boolean rig) throws Exception {
        P3d2Bb.Opts o = new P3d2Bb.Opts();
        o.rig = rig;
        return convert(o);
    }

    private static String text(P3d2Bb.Result r) {
        return new String(r.bbmodel, StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int c = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            c++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return c;
    }

    // ---------------------------------------------------------------- 统计口径

    @Test
    public void countsMatchTheVerifiedReference() throws Exception {
        P3d2Bb.Result r = run(false);
        assertEquals("夹具是 3.0 工程", 3, r.version);
        assertEquals(129L, r.groups);
        assertEquals(84L, r.meshes);
        assertEquals(2016L, r.tris);
        assertEquals(1046L, r.verts);
        assertEquals(2L, r.textures);
        assertEquals(1L, r.lights);
        assertEquals(2L, r.clips);
        assertEquals(0L, r.skippedBig);
        assertFalse(r.rig);
    }

    @Test
    public void rigModeDescribesTheSameScene() throws Exception {
        P3d2Bb.Result s = run(false);
        P3d2Bb.Result g = run(true);
        assertEquals(s.groups, g.groups);
        assertEquals(s.meshes, g.meshes);
        assertEquals(s.tris, g.tris);
        assertEquals(s.verts, g.verts);
        assertEquals(s.textures, g.textures);
        assertEquals(s.lights, g.lights);
        assertTrue("骨骼版要把模式记进结果", g.rig);
    }

    // ---------------------------------------------------------------- 容器格式

    @Test
    public void formatVersionStaysAt45() throws Exception {
        // 4.5 是实测出来的最低可用版本：4.0 会触发 mesh 迁移把几何弄坏，4.10 在 4.5~4.9 端被拒。
        String j = text(run(false));
        assertTrue(j.contains("\"format_version\":\"4.5\""));
        assertTrue(j.contains("\"model_format\":\"free\""));
        assertTrue(j.contains("\"resolution\":{\"width\":1024,\"height\":1024}"));
    }

    @Test
    public void texturesAreEmbeddedAndDeclareUvSpace() throws Exception {
        String j = text(run(false));
        assertEquals(84L, count(j, "\"type\":\"mesh\""));
        assertEquals("每张贴图都要显式写 uv 空间", 2L, count(j, "\"uv_width\":1024"));
        assertTrue("PNG 要按真格式写 MIME（写成 image/png 给 jpg 会让部分端解不出图）",
                j.contains("\"source\":\"data:image/png;base64,"));
        assertTrue("贴图必须内嵌，产物是单文件", j.contains("\"internal\":true"));
    }

    // ---------------------------------------------------------------- 骨骼版结构

    @Test
    public void rigModeOnlyTransformsLeafPartGroups() throws Exception {
        // 版本兼容扁平方案：中间组 origin 与 rotation 一起归零，只有带网格的叶子部件组带世界变换。
        // 旧写法是所有组都带 rotation —— 在不懂「骨骼 origin 差减」的旧版 Blockbench 上，
        // 角色内部手臂/头部会逐层复算而错乱。这条把教训钉住。
        String staticJson = text(run(false));
        String rigJson = text(run(true));
        assertEquals("静态版不该有任何组变换", 84L, count(staticJson, "\"rotation\":"));
        assertEquals("骨骼版 = 113 个组 + 84 个元素各带一次 rotation", 197L, count(rigJson, "\"rotation\":"));
        assertEquals(113L, count(staticJson, ",\"export\":true}"));
        assertEquals(113L, count(rigJson, ",\"export\":true}"));
        assertTrue("中间组必须成对归零", rigJson.contains("\"origin\":[0,0,0],\"rotation\":[0,0,0]"));
    }

    // ---------------------------------------------------------------- 灯光与环境光

    @Test
    public void lightsBecomeLocatorsAndKeepParamsInRootFields() throws Exception {
        String j = text(run(false));
        assertEquals("locator 元素数 = 灯数", 1L, count(j, "\"type\":\"locator\""));
        assertEquals("根字段里的回转记录数也要等于灯数", 1L, count(j, "\"p3d_lights\":[{"));
        // 记录里的键名是小写（参照实现的 light_records），只有取值才按 P3D 属性原名 Range/Angle/Shadow 查
        assertTrue(j.contains("\"range\":"));
        assertTrue(j.contains("\"angle\":"));
        assertTrue(j.contains("\"shadow\":"));
        assertFalse("键名写成大写就把回转字段名弄丢了", j.contains("\"Angle\":"));
        assertTrue("环境光/背景色要留在根字段里，Blockbench 会原样保留", j.contains("\"p3d_ambient\":"));
        assertTrue(j.contains("\"p3d_background\":"));
        assertTrue(j.contains("\"p3d_rig_mode\":false"));
    }

    // ---------------------------------------------------------------- 选项语义

    @Test
    public void uvFlipActuallyChangesSampledV() throws Exception {
        P3d2Bb.Opts on = new P3d2Bb.Opts();
        P3d2Bb.Opts off = new P3d2Bb.Opts();
        off.uvFlip = false;
        String a = text(convert(on));
        String b = text(convert(off));
        assertFalse("翻了 v 却一点没变，说明 uvFlip 没接上", a.equals(b));
    }

    @Test
    public void maxTrisDropsWholeMeshesNotPartialFaces() throws Exception {
        // 参照实现的语义是「这个网格面数超限就整个不要」，不是「截到上限为止」：
        // 截一半会得到一个缺面的模型，比没有更难查。
        P3d2Bb.Opts o = new P3d2Bb.Opts();
        o.maxTris = 10L;
        P3d2Bb.Result r = convert(o);
        assertEquals("84 个网格全都超限", 84L, r.skippedBig);
        assertEquals(0L, r.meshes);
        assertEquals(0L, r.tris);
        assertEquals("没有面就没有贴图", 0L, r.textures);
        assertEquals("灯不是网格，仍要留下", 1L, r.lights);
    }

    @Test
    public void onlyNameKeepsTheWholeAncestorChain() throws Exception {
        P3d2Bb.Opts o = new P3d2Bb.Opts();
        o.onlyName = PART;
        P3d2Bb.Result r = convert(o);
        assertEquals("父链 + 子树共 11 个对象进了组", 11L, r.groups);
        assertEquals(3L, r.meshes);
        assertEquals(36L, r.tris);
        assertEquals("链上没有灯", 0L, r.lights);

        P3d2Bb.Opts none = new P3d2Bb.Opts();
        none.onlyName = "根本没有这个名字";
        assertEquals("选不到就什么都不要输出，而不是整包", 0L, convert(none).groups);
    }

    @Test
    public void garbageInputFailsWithReadableReason() {
        byte[] junk = new byte[64];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) i;
        }
        try {
            P3d2Bb.convert(junk, new P3d2Bb.Opts());
            fail("乱字节必须报错，不能悄悄出一个空模型");
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            assertTrue("报错要说清是哪一步：" + m, m.contains("中央目录") || m.contains("ZIP"));
        }
    }

    @Test
    public void reportSaysWhichModeAndWhatWasLost() throws Exception {
        String rig = run(true).report();
        assertTrue(rig.contains("骨骼版"));
        assertTrue("要说清蒙皮与动画不搬", rig.contains("蒙皮"));
        assertTrue("要说清灯光是记号不是可渲染光源", rig.contains("locator"));
        assertFalse(run(false).report().contains("骨骼版（部件可摆姿势）"));
    }
}
