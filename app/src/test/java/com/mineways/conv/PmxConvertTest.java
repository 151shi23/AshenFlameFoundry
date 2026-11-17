package com.mineways.conv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * PMX → GLB 的端到端守卫：真实 PMX（武器，2966 顶点 / 43 骨骼）必须能解析并写出 GLB。
 * 这条测试挡住“集成后格式转换里 PMX 按钮点了没反应/产物是空壳”这类事故。
 */
public class PmxConvertTest {

    @Test
    public void convertsRealPmxToValidGlb() throws Exception {
        InputStream in = getClass().getResourceAsStream("/pmx/weapon.pmx");
        assertNotNull("测试资源缺失", in);
        File pmx = File.createTempFile("weapon", ".pmx");
        File glb = File.createTempFile("weapon", ".glb");
        pmx.deleteOnExit();
        glb.deleteOnExit();
        try {
            Files.copy(in, pmx.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            in.close();
        }

        PmxConvert.Result r = PmxConvert.convert(pmx, glb, pmx.getParentFile());
        assertTrue(r.message, r.ok);
        assertTrue("结果应带统计：" + r.message, r.message.contains("顶点"));
        assertTrue("产物太小：" + glb.length(), glb.length() > 100_000);

        byte[] head = new byte[4];
        try (InputStream gi = new java.io.FileInputStream(glb)) {
            assertEquals(4, gi.read(head));
        }
        assertEquals("glTF", new String(head, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    public void parserKeepsBonesAndMaterials() throws Exception {
        InputStream in = getClass().getResourceAsStream("/pmx/weapon.pmx");
        assertNotNull(in);
        byte[] bytes = readAll(in);
        Pmx2Glb conv = new Pmx2Glb();
        byte[] glb = conv.convert(bytes, new java.util.HashMap<String, byte[]>());
        assertTrue(glb.length > 100_000);
        assertTrue("顶点数应 > 2000，实际 " + conv.vertexCount(), conv.vertexCount() > 2000);
        assertTrue("骨骼数应 > 0，实际 " + conv.boneCount(), conv.boneCount() > 0);
        assertTrue("材质数应 > 0，实际 " + conv.materialCount(), conv.materialCount() > 0);
        assertTrue(conv.summary().contains("骨骼"));
    }

    @Test
    public void boneNodesComposeBackToPmxAbsolutePositions() throws Exception {
        InputStream in = getClass().getResourceAsStream("/pmx/weapon.pmx");
        assertNotNull(in);
        byte[] bytes = readAll(in);
        Pmx2Glb conv = new Pmx2Glb();
        Pmx2Glb.PmxModel model = conv.prepare(bytes);
        assertTrue(model.bones.size() > 0);
        for (int i = 0; i < model.bones.size(); i++) {
            Pmx2Glb.Bone b = model.bones.get(i);
            float[] sum = {0f, 0f, 0f};
            int cur = i;
            int guard = 0;
            while (cur >= 0 && guard++ < model.bones.size() + 1) {
                float[] t = conv.boneTranslation(cur);
                sum[0] += t[0];
                sum[1] += t[1];
                sum[2] += t[2];
                cur = model.bones.get(cur).parent;
            }
            assertEquals("bone " + i + " x", b.pos[0], sum[0], 0.0005f);
            assertEquals("bone " + i + " y", b.pos[1], sum[1], 0.0005f);
            assertEquals("bone " + i + " z", -b.pos[2], sum[2], 0.0005f);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
