package com.mineways.mocap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 单设备传感器 → 骨骼旋转的回归：标定消初值、静止=0、90°/180° 方向、断档识别。 */
public class SensorMocapTest {

    private static double[] axisAngle(double ax, double ay, double az, double deg) {
        double th = Math.toRadians(deg) / 2;
        double s = Math.sin(th);
        return new double[]{Math.cos(th), ax * s, ay * s, az * s};
    }

    private static List<SensorMocap.Rv> series(double[] q, int frames) {
        List<SensorMocap.Rv> out = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            out.add(new SensorMocap.Rv(i * 20.0, q[0], q[1], q[2], q[3]));
        }
        return out;
    }

    /** 用导出的欧拉作用在静止方向 (0,-1,0) 上，得到这根骨实际指向。 */
    private static Vec3 dir(double[] e) {
        double[] m = EulerBedrock.toMatrix(e[0], e[1], e[2]);
        return Vec3.norm(EulerBedrock.apply(m, new Vec3(0, -1, 0)));
    }

    @Test
    public void restStaysAtZero() {
        double[] q = {1, 0, 0, 0};
        List<SensorMocap.Rv> rv = series(q, 60);
        double[] q0 = SensorMocap.calibrate(rv);
        SensorMocap.Result r = SensorMocap.build(
                SensorMocap.Limb.LEFT_ARM, false, q0, rv, null, 0.02, 50);
        assertTrue(r.curve.size() >= 2);
        for (KeyframeDecimator.Sample s : r.curve) {
            Vec3 d = dir(s.e);
            assertEquals("静止时肢体应朝下", -1.0, d.y, 0.03);
        }
    }

    @Test
    public void calibrationRemovesMountOrientation() {
        // 手机以任意姿态绑着：标定后相对旋转为 0，肢体仍应朝下
        double[] mount = axisAngle(0.3, 0.8, 0.5, 73);
        List<SensorMocap.Rv> calib = series(mount, 30);
        double[] q0 = SensorMocap.calibrate(calib);
        List<SensorMocap.Rv> rv = series(mount, 60);
        SensorMocap.Result r = SensorMocap.build(
                SensorMocap.Limb.RIGHT_LEG, false, q0, rv, null, 0.02, 50);
        for (KeyframeDecimator.Sample s : r.curve) {
            Vec3 d = dir(s.e);
            assertEquals("标定后静止朝下", -1.0, d.y, 0.05);
        }
    }

    @Test
    public void ninetyDegreeRotationMatchesDirection() {
        double[] q = axisAngle(1, 0, 0, 90);
        List<SensorMocap.Rv> rv = series(q, 60);
        double[] q0 = SensorMocap.calibrate(series(new double[]{1, 0, 0, 0}, 10));
        SensorMocap.Result r = SensorMocap.build(
                SensorMocap.Limb.LEFT_ARM, false, q0, rv, null, 0.02, 50);
        for (KeyframeDecimator.Sample s : r.curve) {
            Vec3 d = dir(s.e);
            assertEquals("绕 X 转 90° 后肢体应朝前/后", 1.0, Math.abs(d.z), 0.05);
            assertEquals("不应再朝下", 0.0, d.y, 0.05);
        }
    }

    @Test
    public void antipodalArmUpIsReached() {
        double[] q = axisAngle(0, 0, 1, 180);
        List<SensorMocap.Rv> rv = series(q, 60);
        double[] q0 = SensorMocap.calibrate(series(new double[]{1, 0, 0, 0}, 10));
        SensorMocap.Result r = SensorMocap.build(
                SensorMocap.Limb.LEFT_ARM, false, q0, rv, null, 0.02, 50);
        for (KeyframeDecimator.Sample s : r.curve) {
            Vec3 d = dir(s.e);
            assertTrue("180° 反极点也必须真的举起来", d.y > 0.9);
        }
    }

    @Test
    public void gapIsReportedAndFades() {
        List<SensorMocap.Rv> rv = new ArrayList<>();
        // 前 200ms 正常，随后 800ms 断档，再补一帧
        for (int i = 0; i <= 10; i++) {
            rv.add(new SensorMocap.Rv(i * 20.0, 1, 0, 0, 0));
        }
        rv.add(new SensorMocap.Rv(1000, 0.7071068, 0, 0, 0.7071068));
        SensorMocap.Result r = SensorMocap.build(
                SensorMocap.Limb.LEFT_ARM, false, new double[]{1, 0, 0, 0}, rv, null, 0.02, 50);
        assertTrue("应识别出断档", r.gaps >= 1);
        assertTrue(r.durationSec > 0.9);
    }

    @Test
    public void reversedMountStillRestsDown() {
        double[] mount = axisAngle(0, 1, 0, 35);
        double[] q0 = SensorMocap.calibrate(series(mount, 20));
        SensorMocap.Result r = SensorMocap.build(
                SensorMocap.Limb.RIGHT_ARM, true, q0, series(mount, 40), null, 0.02, 50);
        for (KeyframeDecimator.Sample s : r.curve) {
            Vec3 d = dir(s.e);
            assertEquals("倒装标定后同样朝下", -1.0, d.y, 0.05);
        }
    }

    @Test
    public void slerpAndMulAreConsistent() {
        double[] a = axisAngle(0, 1, 0, 20);
        double[] b = axisAngle(0, 1, 0, 80);
        double[] mid = SensorMocap.slerp(a, b, 0.5);
        double[] want = axisAngle(0, 1, 0, 50);
        for (int i = 0; i < 4; i++) {
            assertEquals(want[i], mid[i], 1e-6);
        }
        double[] id = SensorMocap.mul(a, SensorMocap.conj(a));
        assertEquals(1.0, Math.abs(id[0]), 1e-9);
        assertEquals(0.0, id[1], 1e-9);
    }
}