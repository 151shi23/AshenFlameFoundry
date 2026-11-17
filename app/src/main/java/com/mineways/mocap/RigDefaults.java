package com.mineways.mocap;

/**
 * 内置骨架配置：MC 原版人模（Bedrock 骨骼名）。
 *
 * <p>body 是 passive（不动画），真正输出的恰好 5 根：head / leftArm / rightArm / leftLeg / rightLeg
 * —— 与 Blockbench、Bedrock animation controller、GeckoLib additive 都能直接对骨骼名叠加。
 *
 * <p>pivot 只用于姿态快照（{@code writeSnapshots}）的可视化，不参与解算；
 * 这里给的是 Bedrock 玩家模型的常规像素枢轴。
 */
public final class RigDefaults {

    private RigDefaults() {}

    public static final String VANILLA_JSON =
            "{"
            + "\"rig_name\":\"vanilla\","
            + "\"skeleton_torso_len_m\":0.45,"
            + "\"bones\":["
            + "{\"name\":\"body\",\"kind\":\"body\",\"pivot\":[0,24,0],\"animated\":false},"
            + "{\"name\":\"head\",\"kind\":\"head\",\"pivot\":[0,24,0],\"animated\":true},"
            + "{\"name\":\"leftArm\",\"kind\":\"armL\",\"pivot\":[5,22,0],\"animated\":true},"
            + "{\"name\":\"rightArm\",\"kind\":\"armR\",\"pivot\":[-5,22,0],\"animated\":true},"
            + "{\"name\":\"leftLeg\",\"kind\":\"legL\",\"pivot\":[2,12,0],\"animated\":true},"
            + "{\"name\":\"rightLeg\",\"kind\":\"legR\",\"pivot\":[-2,12,0],\"animated\":true}"
            + "]"
            + "}";

    /** 每次新建：{@link RigConfig} 是可变的，解算器只读它，但别让多线程共用一份。 */
    public static RigConfig vanilla() {
        return RigConfig.fromMap(MiniJson.parse(VANILLA_JSON));
    }

    /**
     * 五骨人模 + 双手手掌与五指（超高精度模式）。
     *
     * <p>骨骼名：{@code leftHand/rightHand} + {@code leftThumb/leftIndex/leftMiddle/leftRing/leftPinky}
     * （右手同理）。手指骨是手掌的子骨 —— 模型里也必须这样挂，否则手掌与手指会各转一次。
     * pivot 只给姿态快照用，不参与解算。
     */
    public static RigConfig vanillaWithHands() {
        RigConfig rc = vanilla();
        for (int s = 0; s < 2; s++) {
            boolean left = s == 0;
            String hand = (left ? "left" : "right") + "Hand";
            double x = left ? 5 : -5;
            addBone(rc, hand, left ? "handL" : "handR", new double[]{x, 10, 0});
            double fx = x;
            String[] names = {"Thumb", "Index", "Middle", "Ring", "Pinky"};
            for (int f = 0; f < names.length; f++) {
                addBone(rc, (left ? "left" : "right") + names[f],
                        (left ? "fingerL" : "fingerR") + names[f],
                        new double[]{fx, 9.5, 0});
                fx += left ? 0.4 : -0.4;
            }
        }
        return rc;
    }

    private static void addBone(RigConfig rc, String name, String kind, double[] pivot) {
        RigConfig.Bone b = new RigConfig.Bone();
        b.name = name;
        b.kind = kind;
        b.pivot = pivot;
        b.animated = true;
        rc.bones.add(b);
    }
}
