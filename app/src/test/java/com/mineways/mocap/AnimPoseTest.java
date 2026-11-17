package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 可视化预览的几何层：角度→二维火柴人必须和 Blockbench 里的显示一致。 */
public class AnimPoseTest {

    private static AnimJson.Clip clipWith(String bone, double[] euler) {
        AnimJson.Clip c = new AnimJson.Clip();
        c.name = "t";
        c.length = 1.0;
        List<KeyframeDecimator.Sample> l = new ArrayList<KeyframeDecimator.Sample>();
        l.add(new KeyframeDecimator.Sample(0.0, euler));
        c.bones.put(bone, l);
        return c;
    }

    private static AnimPose.Seg find(AnimPose.Snapshot s, int kind, int index) {
        int n = 0;
        for (AnimPose.Seg seg : s.segs) {
            if (seg.kind == kind) {
                if (n == index) return seg;
                n++;
            }
        }
        return null;
    }

    @Test
    public void restPosePointsLimbsDownAndHeadUp() {
        AnimPose.Snapshot s = AnimPose.at(clipWith("leftArm", new double[]{0, 0, 0}), 0);
        assertEquals(6, s.segs.size());   // 躯干 + 头 + 四肢
        AnimPose.Seg arm = find(s, AnimPose.ARM, 0);
        assertNotNull(arm);
        assertTrue("手臂应朝下（屏幕 y 变大）", arm.y2 > arm.y1 + 1f);
        AnimPose.Seg head = find(s, AnimPose.HEAD_LINE, 0);
        assertNotNull(head);
        assertTrue("头应朝上（屏幕 y 变小）", head.y2 < head.y1 - 1f);
    }

    /** 头绕 Z 转 90°（JSON 值）：向前/侧倒，横向位移必须明显。 */
    @Test
    public void headRotationTiltsSideways() {
        AnimPose.Snapshot s = AnimPose.at(clipWith("head", new double[]{0, 0, 90}), 0.2);
        AnimPose.Seg head = find(s, AnimPose.HEAD_LINE, 0);
        assertNotNull(head);
        assertTrue("头应明显偏向一侧，dx=" + (head.x2 - head.x1),
                Math.abs(head.x2 - head.x1) > 2f);
    }

    /** 反方向的腿旋转：左右腿的横向位移必须相反（不是同一侧）。 */
    @Test
    public void legsSwingToOppositeSides() {
        AnimJson.Clip c = clipWith("leftLeg", new double[]{0, 0, 30});
        c.bones.put("rightLeg", new ArrayList<KeyframeDecimator.Sample>() {{
            add(new KeyframeDecimator.Sample(0.0, new double[]{0, 0, -30}));
        }});
        AnimPose.Snapshot s = AnimPose.at(c, 0);
        AnimPose.Seg l = find(s, AnimPose.LEG, 0);
        AnimPose.Seg r = find(s, AnimPose.LEG, 1);
        assertNotNull(l);
        assertNotNull(r);
        assertEquals("左右腿应朝相反方向", Math.signum(l.x2 - l.x1), -Math.signum(r.x2 - r.x1), 0.001);
    }

    /** 有手骨时才画手；手指骨在就多出手指段。 */
    @Test
    public void handsAndFingersAppearOnlyWhenPresent() {
        AnimPose.Snapshot plain = AnimPose.at(clipWith("leftArm", new double[]{0, 0, 0}), 0);
        assertFalse(plain.hasHands);

        AnimJson.Clip c = clipWith("leftHand", new double[]{0, 0, 0});
        c.bones.put("leftThumb", new ArrayList<KeyframeDecimator.Sample>() {{
            add(new KeyframeDecimator.Sample(0.0, new double[]{0, 0, 0}));
        }});
        AnimPose.Snapshot s = AnimPose.at(c, 0);
        assertTrue(s.hasHands);
        assertTrue(s.hasFingers);
        assertNotNull(find(s, AnimPose.HAND, 0));
        assertNotNull(find(s, AnimPose.FINGER, 0));
    }

    /** 跳跃位移（body.position）必须整体抬升火柴人。 */
    @Test
    public void bodyPositionLiftsTheWholeFigure() {
        AnimJson.Clip c = clipWith("leftArm", new double[]{0, 0, 0});
        List<KeyframeDecimator.Sample> pos = new ArrayList<KeyframeDecimator.Sample>();
        pos.add(new KeyframeDecimator.Sample(0.0, new double[]{0, 6, 0}));
        c.positions.put("body", pos);
        AnimPose.Snapshot s = AnimPose.at(c, 0);
        AnimPose.Seg torso = find(s, AnimPose.TORSO, 0);
        assertNotNull(torso);
        assertEquals("髋部应从 -12 抬到 -18", -18f, torso.y1, 0.01f);
    }

    /** 超出时长的时刻夹到最后；空动画不崩。 */
    @Test
    public void timeIsClampedAndEmptyClipIsSafe() {
        AnimPose.Snapshot s = AnimPose.at(clipWith("leftArm", new double[]{0, 90, 0}), 99);
        assertNotNull(s);
        assertFalse(s.segs.isEmpty());
        AnimPose.Snapshot empty = AnimPose.at(new AnimJson.Clip(), 0);
        assertNotNull(empty);
        assertEquals(6, empty.segs.size());
    }
}
