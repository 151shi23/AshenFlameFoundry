package com.mineways.prism;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 棱镜玩机的「按系统自适应」是纯数据行为，可以离线验证：
 * 每个品牌只能看到自己的专属项，通用项必须全品牌可见。
 */
public class PrismFeaturesTest {

    private static Set<String> ids(PrismProfile.Vendor v) {
        Set<String> s = new HashSet<String>();
        for (PrismFeatures.Feature f : PrismFeatures.forVendor(v)) s.add(f.id);
        return s;
    }

    @Test
    public void xiaomiSeesOnlyXiaomiSpecificAndCommon() {
        Set<String> s = ids(PrismProfile.Vendor.XIAOMI);
        assertTrue("小米应有 5G 开关", s.contains("five_g"));
        assertTrue("通用项必须有", s.contains("no_lock") && s.contains("anim_scale")
                && s.contains("app_actions") && s.contains("block_update"));
        assertFalse("小米不该出现 vivo 插帧", s.contains("frame_interp"));
        assertFalse("小米不该出现 ColorOS 高刷", s.contains("refresh_rate"));
        assertFalse("小米不该出现指纹动画", s.contains("fingerprint"));
        assertFalse("小米不该出现直驱供电", s.contains("direct_charge"));
    }

    @Test
    public void vivoSeesOriginSpecific() {
        Set<String> s = ids(PrismProfile.Vendor.VIVO);
        assertTrue(s.contains("frame_interp"));
        assertTrue(s.contains("direct_charge"));
        assertTrue(s.contains("charging_aod"));
        assertTrue(s.contains("device_name"));
        assertTrue(s.contains("charge_anim"));
        assertTrue("vivo 应有清除更新提醒", s.contains("clear_update_notice"));
        assertFalse(s.contains("refresh_rate"));
        assertFalse(s.contains("five_g"));
        assertTrue(s.contains("no_lock"));
    }

    @Test
    public void oppoSeesColorSpecific() {
        Set<String> s = ids(PrismProfile.Vendor.OPPO);
        assertTrue(s.contains("refresh_rate"));
        assertTrue(s.contains("ultra_dark"));
        assertTrue(s.contains("fingerprint"));
        assertTrue(s.contains("chain3"));
        assertTrue(s.contains("app_network"));
        assertTrue(s.contains("install_popup"));
        assertTrue(s.contains("game_space"));
        assertFalse(s.contains("frame_interp"));
        assertFalse(s.contains("five_g"));
        assertTrue(s.contains("no_lock"));
    }

    @Test
    public void genericShowsOnlyCommonFeatures() {
        Set<String> s = ids(PrismProfile.Vendor.GENERIC);
        assertTrue(s.contains("no_lock"));
        assertTrue(s.contains("demo_mode"));
        assertTrue(s.contains("app_actions"));
        assertTrue(s.contains("ad_guide"));
        assertTrue("通用机也要有通知隐藏", s.contains("hide_notifications"));
        assertTrue("通用机也要有电量模拟", s.contains("battery_magic"));
        assertTrue(s.contains("dial_codes"));
        assertFalse(s.contains("frame_interp"));
        assertFalse(s.contains("refresh_rate"));
        assertFalse(s.contains("five_g"));
        assertFalse(s.contains("fingerprint"));
    }

    @Test
    public void cloudTogglingUsesFeatureRisk() {
        assertEquals(PrismFeatures.SAFE, PrismFeatures.riskOf("no_lock"));
        assertEquals(PrismFeatures.CAUTION, PrismFeatures.riskOf("direct_charge"));
        assertEquals(PrismFeatures.DANGER, PrismFeatures.riskOf("install_popup"));
        assertTrue(PrismFeatures.exists("chain3"));
        assertFalse(PrismFeatures.exists("not_a_feature"));
        // 未知 id 按最安全处理，云端写错一个 id 不会误关高风险项
        assertEquals(PrismFeatures.SAFE, PrismFeatures.riskOf("typo_id"));
    }

    @Test
    public void prismFlagsDefaultToAllOnWithoutCloud() {
        // 单元测试进程没有 CloudCatalog.init → FeatureGate 未生效 → 全部走默认值
        assertTrue(PrismFlags.on(null));
        assertEquals(PrismFeatures.DANGER, PrismFlags.risk(null));
        assertEquals("", PrismFlags.vendor(null));
        assertTrue(PrismFlags.feature(null, "no_lock"));
        assertTrue(PrismFlags.feature(null, "install_popup"));
        assertTrue(PrismFlags.allowDanger(null));
    }

    @Test
    public void sectionsAndRiskAreSane() {
        List<PrismFeatures.Feature> all = PrismFeatures.all();
        assertTrue("功能不能少于 20 项", all.size() >= 20);
        for (PrismFeatures.Feature f : all) {
            assertNotNull(f.id);
            assertNotNull(f.title);
            assertNotNull(f.desc);
            assertTrue("分区越界：" + f.id, f.section >= PrismFeatures.SEC_DISPLAY
                    && f.section <= PrismFeatures.SEC_SHORTCUT);
            assertTrue("风险级别越界：" + f.id, f.risk >= PrismFeatures.SAFE
                    && f.risk <= PrismFeatures.DANGER);
            assertNotNull(PrismFeatures.sectionName(f.section));
        }
    }

    @Test
    public void otaPackagesMatchVendor() {
        assertEquals("com.android.updater", PrismFeatures.otaPackages(PrismProfile.Vendor.XIAOMI)[0]);
        Set<String> vivo = new HashSet<String>();
        for (String p : PrismFeatures.otaPackages(PrismProfile.Vendor.VIVO)) vivo.add(p);
        assertTrue(vivo.contains("com.vivo.ota") && vivo.contains("com.iqoo.ota"));
        Set<String> oppo = new HashSet<String>();
        for (String p : PrismFeatures.otaPackages(PrismProfile.Vendor.OPPO)) oppo.add(p);
        assertTrue(oppo.contains("com.oplus.ota"));
        assertEquals("bbk_update_notice", PrismFeatures.updateNoticeKey(PrismProfile.Vendor.VIVO));
        assertEquals(null, PrismFeatures.updateNoticeKey(PrismProfile.Vendor.OPPO));
    }

    @Test
    public void protectedPackagesBlockTheDangerousOnes() {
        assertTrue(PrismFeatures.protectedPackage("com.android.systemui"));
        assertTrue(PrismFeatures.protectedPackage("com.android.settings"));
        assertTrue(PrismFeatures.protectedPackage("com.miui.home"));
        assertTrue(PrismFeatures.protectedPackage("com.bbk.launcher2"));
        assertFalse(PrismFeatures.protectedPackage("com.tencent.mm"));
        assertFalse(PrismFeatures.protectedPackage("com.example.demo"));
    }

    @Test
    public void adGuidesExistForEveryVendorAndHaveSteps() {
        for (PrismProfile.Vendor v : PrismProfile.Vendor.values()) {
            List<PrismFeatures.Guide> guides = PrismFeatures.adGuides(v);
            assertFalse("广告向导不能为空：" + v, guides.isEmpty());
            for (PrismFeatures.Guide g : guides) {
                assertNotNull(g.label);
                assertNotNull(g.pkg);
                assertNotNull(g.steps);
                assertFalse("步骤不能为空：" + g.label, g.steps.trim().isEmpty());
            }
        }
    }

    @Test
    public void settingsJumpsAreComplete() {
        List<PrismFeatures.Jump> jumps = PrismFeatures.settingsJumps(PrismProfile.Vendor.GENERIC);
        assertTrue(jumps.size() >= 7);
        for (PrismFeatures.Jump j : jumps) {
            assertNotNull(j.label);
            assertTrue("组件或 Action 至少要有一个：" + j.label,
                    j.component != null || j.action != null);
            if (j.component != null) assertTrue(j.component.contains("/"));
        }
        boolean vivo = false;
        for (PrismFeatures.Jump j : PrismFeatures.settingsJumps(PrismProfile.Vendor.VIVO)) {
            if (j.label.contains("vivo")) vivo = true;
        }
        assertTrue("vivo 应多一个专属子设置页", vivo);
    }

    @Test
    public void wallpaperAndDialTablesAreUsable() {
        assertTrue(PrismFeatures.wallpaperPackages(PrismProfile.Vendor.OPPO).length > 0);
        assertTrue(PrismFeatures.wallpaperPackages(PrismProfile.Vendor.XIAOMI).length > 0);
        assertEquals(0, PrismFeatures.wallpaperPackages(PrismProfile.Vendor.GENERIC).length);
        assertTrue(PrismFeatures.dialCodes().size() >= 4);
    }

    @Test
    public void vendorOverrideParsingIsCaseInsensitive() {
        assertEquals(PrismProfile.Vendor.XIAOMI, PrismProfile.parseVendor("Xiaomi"));
        assertEquals(PrismProfile.Vendor.VIVO, PrismProfile.parseVendor(" vivo "));
        assertEquals(PrismProfile.Vendor.OPPO, PrismProfile.parseVendor("OPPO"));
        assertEquals(PrismProfile.Vendor.GENERIC, PrismProfile.parseVendor(""));
        assertEquals(PrismProfile.Vendor.GENERIC, PrismProfile.parseVendor(null));
    }

    @Test
    public void playFeaturesAreCommonToEveryVendor() {
        String[] ids = {"privacy_audit", "app_perms", "bg_cleaner", "battery_cycle",
                "refresh_lock", "wifi_adb", "hw_profile", "lock_screen"};
        for (PrismProfile.Vendor v : PrismProfile.Vendor.values()) {
            Set<String> s = ids(v);
            for (String id : ids) {
                assertTrue(v + " 缺功能 " + id, s.contains(id));
                assertTrue(PrismFeatures.exists(id));
            }
        }
    }

    @Test
    public void runtimePermissionDumpParsingIsScoped() {
        String dump = "Packages:\n"
                + "  Package [com.demo.app] (123):\n"
                + "    userId=10123\n"
                + "    runtime permissions:\n"
                + "      android.permission.CAMERA: granted=true, flags=[ USER_SET ]\n"
                + "      android.permission.RECORD_AUDIO: granted=false, flags=[]\n"
                + "    install permissions:\n"
                + "      android.permission.INTERNET: granted=true, flags=[ INSTALLED ]\n";
        List<String[]> perms = PrismShell.parseRuntimePerms(dump);
        assertEquals("只取 runtime 段，不能把 install 权限算进来", 2, perms.size());
        assertEquals("android.permission.CAMERA", perms.get(0)[0]);
        assertEquals("1", perms.get(0)[1]);
        assertEquals("0", perms.get(1)[1]);
        assertEquals("CAMERA", PrismShell.shortPerm("android.permission.CAMERA"));
        assertTrue(PrismShell.parseRuntimePerms(null).isEmpty());
    }
}
