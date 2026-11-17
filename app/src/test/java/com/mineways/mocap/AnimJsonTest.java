package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 已录动画的编辑层：裁剪/删除区间/变速/静音骨骼都必须保数值、保时间轴契约。 */
public class AnimJsonTest {

    private static File sample() throws Exception {
        SyntheticMocap.Result r = SyntheticMocap.generate(SyntheticMocap.walk(), 42L);
        File f = File.createTempFile("anim-edit-src", ".animation.json");
        f.deleteOnExit();
        Main.pipeline(r.clip, RigDefaults.vanilla(), "walk", true, f.getAbsolutePath(), null, 0.5);
        return f;
    }

    private static void assertTimelineSane(AnimJson.Clip c) {
        for (List<KeyframeDecimator.Sample> l : c.bones.values()) {
            double prev = -1e-9;
            for (KeyframeDecimator.Sample s : l) {
                assertTrue("时间键必须严格递增：" + s.t, s.t > prev);
                prev = s.t;
                assertTrue("关键帧不能超出时长：" + s.t + " > " + c.length, s.t <= c.length + 1e-6);
            }
        }
    }

    @Test
    public void readWriteKeepsEverything() throws Exception {
        AnimJson.Clip a = AnimJson.read(sample().getAbsolutePath());
        File out = File.createTempFile("anim-edit-out", ".animation.json");
        out.deleteOnExit();
        AnimJson.write(a, out.getAbsolutePath());
        AnimJson.Clip b = AnimJson.read(out.getAbsolutePath());
        assertEquals(a.name, b.name);
        assertEquals(a.loop, b.loop);
        assertEquals(a.length, b.length, 1e-6);
        assertEquals(a.bones.size(), b.bones.size());
        for (String bone : a.bones.keySet()) {
            List<KeyframeDecimator.Sample> la = a.bones.get(bone), lb = b.bones.get(bone);
            assertEquals(la.size(), lb.size());
            for (int i = 0; i < la.size(); i++) {
                assertEquals(la.get(i).t, lb.get(i).t, 1e-6);
                for (int k = 0; k < 3; k++) {
                    assertEquals(la.get(i).e[k], lb.get(i).e[k], 0.01);
                }
            }
        }
        assertTrue(AnimCheck.check(out.getAbsolutePath(), b.name).ok);
    }

    @Test
    public void keepRangeRetimesAndKeepsValues() throws Exception {
        AnimJson.Clip c = AnimJson.read(sample().getAbsolutePath());
        double old = c.length;
        AnimJson.keep(c, 0.4, 1.4);
        assertEquals(1.0, c.length, 1e-6);
        assertTrue(c.length < old);
        assertTimelineSane(c);
        for (List<KeyframeDecimator.Sample> l : c.bones.values()) {
            assertEquals("首键必须在 0", 0.0, l.get(0).t, 1e-6);
            assertEquals("尾键必须落在新时长", 1.0, l.get(l.size() - 1).t, 1e-6);
        }
    }

    @Test
    public void cutRangeSplicesTimeline() throws Exception {
        AnimJson.Clip c = AnimJson.read(sample().getAbsolutePath());
        double old = c.length;
        AnimJson.cut(c, 0.5, 1.5);
        assertEquals(old - 1.0, c.length, 1e-6);
        assertTimelineSane(c);
        for (List<KeyframeDecimator.Sample> l : c.bones.values()) {
            double prev = -1;
            for (KeyframeDecimator.Sample s : l) {
                assertTrue(s.t >= prev);
                prev = s.t;
            }
        }
    }

    @Test
    public void scaleStretchesTimeline() throws Exception {
        AnimJson.Clip c = AnimJson.read(sample().getAbsolutePath());
        double old = c.length;
        AnimJson.scale(c, 2.0);
        assertEquals(old * 2, c.length, 1e-6);
        assertTimelineSane(c);
        assertEquals(old * 2, c.bones.values().iterator().next()
                .get(c.bones.values().iterator().next().size() - 1).t, 1e-3);
    }

    @Test
    public void muteRemovesOnlyThatBone() throws Exception {
        AnimJson.Clip c = AnimJson.read(sample().getAbsolutePath());
        int bones = c.bones.size();
        AnimJson.mute(c, "leftLeg");
        assertEquals(bones - 1, c.bones.size());
        assertFalse(c.bones.containsKey("leftLeg"));
        File out = File.createTempFile("anim-edit-mute", ".animation.json");
        out.deleteOnExit();
        AnimJson.write(c, out.getAbsolutePath());
        AnimJson.Clip b = AnimJson.read(out.getAbsolutePath());
        assertFalse(b.bones.containsKey("leftLeg"));
        assertNotNull(b.bones.get("rightLeg"));
        assertTrue("编辑过的动画允许少于 5 根骨", AnimCheck.check(out.getAbsolutePath(), b.name, true).ok);
        assertFalse("生成路径不许放宽", AnimCheck.check(out.getAbsolutePath(), b.name).ok);
    }

    /** 跳跃位移通道：读写必须保留，区间编辑也要跟着走。 */
    @Test
    public void positionChannelSurvivesEditing() throws Exception {
        AnimJson.Clip c = new AnimJson.Clip();
        c.name = "jump";
        c.length = 2;
        List<KeyframeDecimator.Sample> rot = new java.util.ArrayList<KeyframeDecimator.Sample>();
        rot.add(new KeyframeDecimator.Sample(0, new double[]{0, 0, 0}));
        rot.add(new KeyframeDecimator.Sample(2, new double[]{0, 10, 0}));
        c.bones.put("leftLeg", rot);
        List<KeyframeDecimator.Sample> pos = new java.util.ArrayList<KeyframeDecimator.Sample>();
        pos.add(new KeyframeDecimator.Sample(0, new double[]{0, 12, 0}));
        pos.add(new KeyframeDecimator.Sample(2, new double[]{0, 0, 0}));
        c.positions.put("body", pos);

        File a = File.createTempFile("pos-a", ".animation.json");
        File b = File.createTempFile("pos-b", ".animation.json");
        a.deleteOnExit();
        b.deleteOnExit();
        AnimJson.write(c, a.getAbsolutePath());
        AnimJson.Clip r = AnimJson.read(a.getAbsolutePath());
        assertTrue("读回必须带位移通道", r.hasPosition("body"));
        AnimJson.keep(r, 0.5, 1.5);
        AnimJson.write(r, b.getAbsolutePath());
        AnimJson.Clip r2 = AnimJson.read(b.getAbsolutePath());
        assertTrue(r2.hasPosition("body"));
        assertEquals(1.0, r2.length, 1e-6);
        List<KeyframeDecimator.Sample> p2 = r2.positions.get("body");
        assertEquals(1.0, p2.get(p2.size() - 1).t, 1e-6);
        assertTrue(AnimCheck.check(b.getAbsolutePath(), "jump", true).ok);
    }

    /** 可视化拖骨骼：把骨头转到新方向并自动打关键帧；同一时刻重拖是替换。 */
    @Test
    public void dragRotatesBoneAndWritesKeyframe() {
        AnimJson.Clip c = new AnimJson.Clip();
        c.name = "drag";
        c.length = 1.0;
        double[] out = AnimJson.drag(c, "leftLeg", 0.0, 6, 0);   // 往右拖 6 像素
        assertNotNull(out);
        assertTrue("腿应向右摆（JSON z 为正），实际 " + out[2], out[2] > 20.0);
        assertEquals(1, c.bones.get("leftLeg").size());

        AnimJson.drag(c, "leftLeg", 0.0, -12, 0);                // 同一时刻再拖 = 替换
        assertEquals(1, c.bones.get("leftLeg").size());

        AnimJson.drag(c, "leftLeg", 0.5, 3, 0);                  // 新时刻 = 追加
        assertEquals(2, c.bones.get("leftLeg").size());
        assertTrue(c.bones.get("leftLeg").get(1).t > c.bones.get("leftLeg").get(0).t);

        double[] head = AnimJson.drag(c, "head", 0.0, 6, 0);     // 头静止朝上，符号相反
        assertNotNull(head);
        assertTrue("头应向右倒（JSON z 为负），实际 " + head[2], head[2] < -20.0);

        AnimJson.drag(c, "leftLeg", 2.0, 2, 0);                  // 超出时长 → 时长跟着延长
        assertEquals(2.0, c.length, 1e-6);
    }

    @Test
    public void boundaryValueIsInterpolated() throws Exception {
        AnimJson.Clip c = AnimJson.read(sample().getAbsolutePath());
        List<KeyframeDecimator.Sample> leg = c.bones.get("leftLeg");
        assertNotNull(leg);
        double t = (leg.get(0).t + leg.get(leg.size() - 1).t) / 2;
        double[] v = AnimJson.valueAt(leg, t);
        assertNotNull(v);
        assertTrue(String.format(Locale.US, "%s", v), v.length == 3);
    }

    /**
     * Blockbench 的字段契约：动画级（loop 三态 / override / Molang / 特效时间轴）、
     * 骨骼级（relative_to / scale / 未知字段）、关键帧详情对象、同文件其它动画与
     * animation_controllers —— 一次「读→写」必须一个都不少。
     */
    @Test
    public void bbFieldsSurviveRoundTrip() throws Exception {
        String src = "{\"format_version\":\"1.8.0\",\"animations\":{"
                + "\"animation.test\":{\"loop\":\"hold_on_last_frame\",\"animation_length\":2,"
                + "\"override_previous_animation\":true,"
                + "\"anim_time_update\":\"query.anim_time + query.delta_time\","
                + "\"blend_weight\":\"1.0\",\"start_delay\":\"0.0\","
                + "\"particle_effects\":{\"1.0\":{\"effect\":\"p\"}},"
                + "\"sound_effects\":{\"0.5\":{\"effect\":\"s\"}},"
                + "\"timeline\":{\"0.2\":\"@s cmd\"},"
                + "\"bones\":{"
                + "\"head\":{\"relative_to\":{\"rotation\":\"entity\"},"
                + "\"rotation\":{\"0.0\":[0,0,0],"
                + "\"0.5\":{\"pre\":[0,10,0],\"post\":[0,15,0],\"lerp_mode\":\"catmullrom\"},"
                + "\"1.0\":[0,0,0]},\"scale\":[1,1,1],\"custom\":7},"
                + "\"leftArm\":{\"rotation\":[\"math.sin(query.anim_time)\",0,0]}}},"
                + "\"animation.other\":{\"loop\":true,\"animation_length\":1,"
                + "\"bones\":{\"head\":{\"rotation\":{\"0.0\":[1,0,0]}}}}}"
                + ",\"animation_controllers\":{\"controller.animation.test\":{\"states\":{}}}}";

        File in = File.createTempFile("bb-fields", ".animation.json");
        File out = File.createTempFile("bb-fields-out", ".animation.json");
        in.deleteOnExit();
        out.deleteOnExit();
        java.nio.file.Files.write(in.toPath(), src.getBytes("UTF-8"));

        AnimJson.Clip c = AnimJson.read(in.getAbsolutePath());
        assertTrue(AnimSpec.isHold(c.loop));
        assertTrue(c.extras.containsKey("particle_effects"));
        assertTrue(c.extras.containsKey("sound_effects"));
        assertTrue(c.extras.containsKey("timeline"));
        assertTrue(c.extras.containsKey("anim_time_update"));
        assertTrue(c.extras.containsKey("blend_weight"));
        assertTrue(c.extras.containsKey("start_delay"));
        assertTrue(c.boneExtras.get("head").containsKey("relative_to"));
        assertTrue(c.boneExtras.get("head").containsKey("custom"));
        assertTrue(c.hasScale("head"));
        assertEquals("catmullrom", c.bones.get("head").get(1).lerp);
        assertNotNull(c.bones.get("head").get(1).pre);
        assertTrue("Molang 通道必须原样保留", c.opaque.containsKey("leftArm"));
        assertTrue(c.otherAnimations.containsKey("animation.other"));
        assertTrue(c.rootExtras.containsKey("animation_controllers"));

        // 编辑时间轴：特效事件跟着走，catmullrom 标记跟着关键帧走
        AnimJson.keep(c, 0.0, 0.8);
        AnimJson.write(c, out.getAbsolutePath());
        AnimJson.Clip r = AnimJson.read(out.getAbsolutePath());
        assertEquals(0.8, r.length, 1e-6);
        assertEquals("catmullrom", r.bones.get("head").get(1).lerp);
        assertTrue(r.extras.containsKey("sound_effects"));
        assertTrue("1.0s 的粒子事件应被裁掉", !r.extras.containsKey("particle_effects"));
        assertTrue(r.opaque.containsKey("leftArm"));
        assertTrue(r.otherAnimations.containsKey("animation.other"));
        assertTrue(r.rootExtras.containsKey("animation_controllers"));
        assertTrue(AnimCheck.check(out.getAbsolutePath(), r.name, true).ok);
    }

    /** BB 的塌缩规则：单键线性通道写裸值；scale 三分量相同再塌缩成单值。 */
    @Test
    public void singleKeyChannelsCollapseLikeBlockbench() throws Exception {
        AnimJson.Clip c = new AnimJson.Clip();
        c.name = "collapse";
        c.loop = true;
        c.length = 1.0;
        c.scales.put("head", new java.util.ArrayList<KeyframeDecimator.Sample>(
                java.util.Collections.singletonList(
                        new KeyframeDecimator.Sample(0, new double[]{1, 1, 1}))));
        c.bones.put("leftArm", new java.util.ArrayList<KeyframeDecimator.Sample>(
                java.util.Collections.singletonList(
                        new KeyframeDecimator.Sample(0, new double[]{0, 0, 0}))));
        File f = File.createTempFile("collapse", ".animation.json");
        f.deleteOnExit();
        AnimJson.write(c, f.getAbsolutePath());

        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(
                new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
        Map<String, Object> bones = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) root.get("animations")).get("collapse")).get("bones");
        assertTrue("scale 应塌缩成单值", ((Map<String, Object>) bones.get("head")).get("scale") instanceof Number);
        assertTrue("单键 rotation 应塌缩成裸数组",
                ((Map<String, Object>) bones.get("leftArm")).get("rotation") instanceof List);
        assertEquals(1.0, AnimSpec.decode(
                ((Map<String, Object>) bones.get("head")).get("scale")).value[2], 1e-9);
    }

    /** 生成路径：平滑 / 覆盖前序 / 保持末帧 必须落成 BB 认识的字段。 */
    @Test
    public void generationWritesBbAnimationFields() throws Exception {
        SyntheticMocap.Result res = SyntheticMocap.generate(SyntheticMocap.wave(), 42L);
        File f = File.createTempFile("anim-bb-fields", ".animation.json");
        f.deleteOnExit();
        Main.Options opt = new Main.Options();
        opt.smooth = true;
        opt.overridePose = true;
        opt.holdLastFrame = true;
        Main.pipeline(res.clip, RigDefaults.vanilla(), "wave", false, f.getAbsolutePath(), null, 0.5, opt);

        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(
                new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
        Map<String, Object> anim = (Map<String, Object>) ((Map<String, Object>) root.get("animations"))
                .get("wave");
        assertEquals(AnimSpec.LOOP_HOLD, anim.get("loop"));
        assertEquals(Boolean.TRUE, anim.get("override_previous_animation"));
        Map<String, Object> bones = (Map<String, Object>) anim.get("bones");
        Map<String, Object> rot = (Map<String, Object>) ((Map<String, Object>) bones.get("head")).get("rotation");
        Object any = rot.values().iterator().next();
        assertTrue("平滑导出必须是 catmullrom 详情对象：" + any, any instanceof Map);
        assertEquals("catmullrom", ((Map<String, Object>) any).get("lerp_mode"));
        assertTrue(AnimCheck.check(f.getAbsolutePath(), "wave").ok);

        // 再经过一次编辑层（读→裁→写），平滑与 loop 三态都必须活下来
        AnimJson.Clip clip = AnimJson.read(f.getAbsolutePath());
        AnimJson.keep(clip, 0.0, 1.0);
        File g = File.createTempFile("anim-bb-fields-edit", ".animation.json");
        g.deleteOnExit();
        AnimJson.write(clip, g.getAbsolutePath());
        AnimJson.Clip back = AnimJson.read(g.getAbsolutePath());
        assertEquals(AnimSpec.LOOP_HOLD, back.loop);
        boolean keptSmooth = false;
        for (List<KeyframeDecimator.Sample> l : back.bones.values()) {
            for (KeyframeDecimator.Sample s : l) keptSmooth |= s.lerp != null;
        }
        assertTrue("编辑后 catmullrom 标记不能丢", keptSmooth);
        assertTrue(AnimCheck.check(g.getAbsolutePath(), "wave", true).ok);
    }
}
