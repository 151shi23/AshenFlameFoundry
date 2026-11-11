package com.mineways.guardlink;

import com.mineways.cloud.CloudCatalog;

/**
 * 云控特征集的 Android 适配层：把已签名配置里的 {@code FLAG gsg_*} / {@code gsd_*} 取出来，
 * 交给纯逻辑 {@link GuardFeatureSet}。
 *
 * <p>云控没配置、整份被拒、解析异常，一律退回出厂特征 —— 云端不可用时防护只能更保守，
 * 不能因为「拉不到配置」就把检测关掉。
 */
public final class GuardCloudFeatures {

    private GuardCloudFeatures() {
    }

    public static GuardFeatureSet load() {
        try {
            return GuardFeatureSet.fromFlags(CloudCatalog.gate().flags());
        } catch (Throwable t) {
            return GuardFeatureSet.builtin();
        }
    }

    /** 起守护时打进事件流的一行摘要：云端到底生效了几条、停用了几条、坏了几行。 */
    public static String summary() {
        GuardFeatureSet fs = load();
        String s = fs.summary();
        if (fs.badLineCount() > 0) {
            s += "（坏行已忽略，不影响内置特征）";
        }
        return s;
    }
}
