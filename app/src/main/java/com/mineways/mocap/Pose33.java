package com.mineways.mocap;

/** MediaPipe Pose 33 关键点单帧数据。 */
public final class Pose33 {
    public static final int NOSE = 0;
    /** 双眼（2=左眼、5=右眼）：耳朵被头发/侧脸遮住时用眼线近似头部左右方向。 */
    public static final int L_EYE = 2, R_EYE = 5;
    public static final int L_EAR = 7, R_EAR = 8;
    public static final int L_SHOULDER = 11, R_SHOULDER = 12;
    public static final int L_ELBOW = 13, R_ELBOW = 14;
    public static final int L_WRIST = 15, R_WRIST = 16;
    public static final int L_HIP = 23, R_HIP = 24;
    public static final int L_KNEE = 25, R_KNEE = 26;
    public static final int L_ANKLE = 27, R_ANKLE = 28;

    public double tMs;
    /** MediaPipe 原始归一化坐标：x/y ∈ [0,1]（x 按宽、y 按高），z 以髋中点为原点（越小越靠近相机）。 */
    public final double[] x = new double[33], y = new double[33], z = new double[33];
    /** MediaPipe visibility：遮挡/模糊置信度（0..1）。旧 CSV 只有这一列。 */
    public final double[] vis = new double[33];
    /**
     * MediaPipe presence：「这个关节在不在画面里」的概率（0..1，未知时 <0）。
     *
     * <p>与 visibility 分开：脚在画面里但很小/偏暗时，visibility 可能只有 0.1 而 presence 仍接近 1。
     * 旧版只看 visibility，把这种脚踝当成"没看见"，位置退回 (0,0,0)＝画面正中 → 腿朝上。
     */
    public final double[] pres = new double[33];

    /** 双手 21 点（MediaPipe Hand Landmarker，图像归一化坐标）。{@code handSeen} 为假时其余无效。 */
    public final double[][] handX = new double[2][21], handY = new double[2][21], handZ = new double[2][21];
    public final double[][] handVis = new double[2][21];
    public final boolean[] handSeen = new boolean[2];

    public Pose33() {
        java.util.Arrays.fill(pres, -1.0);   // -1 = 未知（旧数据/合成数据没有 presence）
    }
}
