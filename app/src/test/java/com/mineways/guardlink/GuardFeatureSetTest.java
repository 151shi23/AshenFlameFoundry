package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zeus.guardlink.GuardLinkCore;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 云控特征集（§8.3.160）的回归钉子。
 *
 * <p>云控下发的每一条都可能被写坏，所以这里同时钉两类底线：
 * ① 增/改/删都按预期生效；
 * ② 坏行只影响它自己，内置检测一条都不能丢。
 */
public class GuardFeatureSetTest {

    private static GuardApkAudit.Meta sample() {
        GuardApkAudit.Meta m = new GuardApkAudit.Meta();
        m.pkg = "com.example.video";
        m.label = "示例视频";
        m.signerCn = "CN=Example Media Ltd";
        m.manifestBytes = 120 * 1024L;
        m.entryCount = 400;
        m.dexEntryCount = 2;
        m.resEntryCount = 80;
        return m;
    }

    private static Map<String, String> flags(String... kv) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    public void builtinMatchesFactoryAudit() {
        GuardApkAudit.Meta m = sample();
        m.hasScrcpy = true;
        GuardApkAudit.Report factory = GuardApkAudit.audit(m);
        GuardApkAudit.Report viaSet = GuardApkAudit.audit(m, GuardFeatureSet.builtin());
        assertEquals(factory.score, viaSet.score);
        assertEquals(factory.level, viaSet.level);
        assertEquals(factory.risks.size(), viaSet.risks.size());
    }

    @Test
    public void cloudAddedStaticFeatureIsDetected() {
        GuardApkAudit.Meta m = sample();
        m.entries.add("assets/payload.bin");
        m.permissions.add("android.permission.READ_SMS");

        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags(
                "gsg_payload", "s|35|entry:payload.bin|自定义载荷|安装包里带可疑载荷"));
        GuardApkAudit.Report r = GuardApkAudit.audit(m, fs);

        assertEquals(35, r.score);
        assertTrue(hasTitle(r, "自定义载荷"));
        assertEquals(1, fs.customStatic().size());
    }

    @Test
    public void cloudCanDisableBuiltinFeature() {
        GuardApkAudit.Meta m = sample();
        m.hasScrcpy = true;

        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags("gsd_st_scrcpy", "1"));
        GuardApkAudit.Report r = GuardApkAudit.audit(m, fs);

        assertEquals(0, r.score);
        assertFalse(hasTitle(r, "内置 scrcpy 远控服务"));
        assertTrue(fs.suppressed("st_scrcpy"));
    }

    @Test
    public void cloudCanOverrideBuiltinWeightAndTitle() {
        GuardApkAudit.Meta m = sample();
        m.hasScrcpy = true;

        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags(
                "gsg_st_scrcpy", "s|80|entry:scrcpy_server|内置远控服务（云端升级）|云端认为这一条必须拦下"));
        GuardApkAudit.Report r = GuardApkAudit.audit(m, fs);

        assertEquals(80, r.score);
        assertEquals(GuardApkAudit.Level.DANGEROUS, r.level);
        assertTrue(hasTitle(r, "内置远控服务（云端升级）"));
        // 内置 id 被覆盖时不该再叠加一遍云端新增
        assertEquals(0, fs.customStatic().size());
    }

    @Test
    public void dropRemovesCloudFeatureOnly() {
        GuardApkAudit.Meta m = sample();
        m.entries.add("assets/payload.bin");
        m.hasScrcpy = true;

        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags(
                "gsg_payload", "s|30|entry:payload.bin|自定义载荷|x"));
        assertEquals(30, fs.weight("payload", 0));

        GuardFeatureSet dropped = GuardFeatureSet.fromFlags(flags("gsg_payload", "drop"));
        GuardApkAudit.Report r = GuardApkAudit.audit(m, dropped);

        assertEquals(50, r.score);   // 只剩内置 scrcpy
        assertFalse(hasTitle(r, "自定义载荷"));
        assertEquals(0, dropped.cloudCount());
    }

    @Test
    public void badLinesAreIgnoredNotFatal() {
        GuardApkAudit.Meta m = sample();
        m.hasScrcpy = true;

        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags(
                "gsg_bad1", "x|10|entry:a",        // 未知类型
                "gsg_bad2", "s|abc|entry:a",       // 分值不是数字
                "gsg_bad3", "s|999|entry:a",       // 分值越界
                "gsg_bad-id", "s|10|entry:a",      // id 非法
                "gsg_bad4", "s|10|",               // 匹配式为空
                "gsd_st_scrcpy", "0"));            // 显式 0 = 不停用

        GuardApkAudit.Report r = GuardApkAudit.audit(m, fs);

        assertEquals(5, fs.badLineCount());
        assertEquals(50, r.score);
        assertTrue(fs.enabled(GuardFeatureSet.ST_SCRCPY));
    }

    @Test
    public void staticMatchersCoverStructureAndPermissions() {
        GuardApkAudit.Meta m = sample();
        m.pkg = "com.example.bad";
        m.entries.add("lib/arm64-v8a/libspake2.so");
        m.permissions.add("android.permission.CAMERA");
        m.components.add("com.example.bad/.SpyService");
        m.signerCn = "CN=r67ab9cbb04161792";
        m.manifestBytes = 3_000_000L;
        m.dexEntryCount = 9;
        m.resEntryCount = 600;
        m.a11yService = true;
        m.mediaProjection = true;
        m.requestDelete = true;

        assertTrue(GuardFeatureSet.matchStatic("entry:libspake2.so", m));
        assertTrue(GuardFeatureSet.matchStatic("permission:android.permission.CAMERA", m));
        assertTrue(GuardFeatureSet.matchStatic("component:spyservice", m));
        assertTrue(GuardFeatureSet.matchStatic("pkg:com.example.bad", m));
        assertTrue(GuardFeatureSet.matchStatic("pkgre:example.*bad", m));
        assertTrue(GuardFeatureSet.matchStatic("signer:r67ab9cbb", m));
        assertTrue(GuardFeatureSet.matchStatic("manifest_gt:2000000", m));
        assertTrue(GuardFeatureSet.matchStatic("dex_count_gt:7", m));
        assertTrue(GuardFeatureSet.matchStatic("res_count_gt:500", m));
        assertTrue(GuardFeatureSet.matchStatic("a11y_service", m));
        assertTrue(GuardFeatureSet.matchStatic("media_projection", m));
        assertTrue(GuardFeatureSet.matchStatic("request_delete", m));
        assertFalse(GuardFeatureSet.matchStatic("permission:android.permission.INTERNET", m));
        assertFalse(GuardFeatureSet.matchStatic("vpn_service", m));
    }

    @Test
    public void cloudDynamicFeatureNeedsAllConditions() {
        GuardLinkCore.SuspectEngine.AppProfile p = new GuardLinkCore.SuspectEngine.AppProfile();
        p.pkg = "com.example.tool";
        p.declaredAccessibilityService = true;
        p.enabledAccessibilityService = true;
        p.notificationListenerEnabled = true;

        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags(
                "gsg_dy_combo", "d|20|a11y_enabled+notify_enabled|无障碍与通知监听同时开启|验证码和读屏同时失守"));

        fs.applyDynamic(p);
        assertEquals(20, p.cloudScore);
        assertEquals(1, p.cloudDetails.size());

        p.notificationListenerEnabled = false;
        GuardLinkCore.SuspectEngine.AppProfile only = new GuardLinkCore.SuspectEngine.AppProfile();
        only.pkg = "com.example.tool";
        only.enabledAccessibilityService = true;
        fs.applyDynamic(only);
        assertEquals(0, only.cloudScore);
    }

    @Test
    public void cloudDynamicCanSuppressBuiltinForProfile() {
        GuardLinkCore.SuspectEngine.AppProfile p = new GuardLinkCore.SuspectEngine.AppProfile();
        p.pkg = "com.example.tool";
        p.declaredAccessibilityService = true;

        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags("gsd_dy_a11y", "1"));
        fs.applyDynamic(p);
        assertTrue(p.suppressed.contains(GuardFeatureSet.DY_A11Y));
        assertEquals(0, p.cloudScore);
    }

    @Test
    public void dynamicMatcherSupportsTrafficRank() {
        GuardLinkCore.SuspectEngine.AppProfile p = new GuardLinkCore.SuspectEngine.AppProfile();
        p.pkg = "com.example.tool";
        p.trafficRank = 2;
        p.trafficDelta = 1024;

        assertTrue(GuardFeatureSet.matchDynamic("traffic_rank_le:3", p));
        assertFalse(GuardFeatureSet.matchDynamic("traffic_rank_le:1", p));
        assertFalse(GuardFeatureSet.matchDynamic("nonsense", p));
    }

    @Test
    public void summaryReportsCounts() {
        GuardFeatureSet fs = GuardFeatureSet.fromFlags(flags(
                "gsg_s1", "s|10|entry:a|新增静态|x",
                "gsg_d1", "d|10|a11y|新增动态|x",
                "gsd_st_zip", "1"));
        String s = fs.summary();
        assertTrue(s.contains("新增静态 1"));
        assertTrue(s.contains("动态 1"));
        assertTrue(s.contains("停用 1"));
    }

    private static boolean hasTitle(GuardApkAudit.Report r, String title) {
        for (GuardApkAudit.Risk risk : r.risks) {
            if (title.equals(risk.title)) {
                return true;
            }
        }
        return false;
    }
}
