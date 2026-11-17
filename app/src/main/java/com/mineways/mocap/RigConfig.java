package com.mineways.mocap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 骨架配置（rig JSON）。
 *
 * 默认随包两套：
 *   rig.vanilla.json —— MC 原版人模骨骼名（head/body/leftArm/rightArm/leftLeg/rightLeg），
 *                       body 默认 passive（不动画）→ 实际动画骨骼恰好 5 根，即“MC 五骨骼人模”。
 *   rig.fivebone.json —— 五骨合并示例（legs 合一），演示配置驱动的骨骼子集映射。
 *
 * kind 决定解算器用哪些关键点构造目标方向：
 *   body: 躯干姿态（肩髋基）   head: 耳/鼻   armL/armR: 肩→肘→腕   legL/legR: 髋→膝→踝
 */
public final class RigConfig {
    public static final class Bone {
        public String name;
        public String kind;
        public double[] pivot = new double[3];
        public boolean animated = true;
    }

    public String rigName;
    public final List<Bone> bones = new ArrayList<Bone>();
    /** 解算用真值骨架（米），与 MediaPipe 端拍摄距离/人体尺寸解耦（ torso 归一化）。 */
    public double torsoLen = 0.45;

    public static RigConfig load(String path) throws java.io.IOException {
        String s = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
            java.nio.charset.StandardCharsets.UTF_8);
        return fromMap(MiniJson.parse(s));
    }

    @SuppressWarnings("unchecked")
    static RigConfig fromMap(Object root) {
        RigConfig rc = new RigConfig();
        Map<String, Object> m = (Map<String, Object>) root;
        rc.rigName = (String) m.get("rig_name");
        Object torso = m.get("skeleton_torso_len_m");
        if (torso instanceof Number) rc.torsoLen = ((Number) torso).doubleValue();
        List<Object> arr = (List<Object>) m.get("bones");
        for (Object o : arr) {
            Map<String, Object> b = (Map<String, Object>) o;
            Bone bo = new Bone();
            bo.name = (String) b.get("name");
            bo.kind = (String) b.get("kind");
            List<Object> pv = (List<Object>) b.get("pivot");
            for (int i = 0; i < 3; i++) bo.pivot[i] = ((Number) pv.get(i)).doubleValue();
            Object anim = b.get("animated");
            bo.animated = !(anim instanceof Boolean) || (Boolean) anim;
            rc.bones.add(bo);
        }
        return rc;
    }

    public Bone byKind(String kind) {
        for (Bone b : bones) if (kind.equals(b.kind)) return b;
        return null;
    }
}
