package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 安装包体检规则层的回归钉子（§8.3.159）。
 *
 * <p>这份规则的价值全在「装前拦得住、正常包不误伤」两件事上，所以钉两类：
 * ① 真实远控样本的结构特征组合必须落到「高危」；
 * ② 单条普通权限、无入口插件、正常多 DEX 应用不能被打成高危。
 */
public class GuardApkAuditTest {

    private static GuardApkAudit.Meta clean() {
        GuardApkAudit.Meta m = new GuardApkAudit.Meta();
        m.pkg = "com.example.video";
        m.label = "示例视频";
        m.signerCn = "CN=Example Media Ltd, O=Example";
        m.manifestBytes = 120 * 1024L;
        m.entryCount = 420;
        m.dexEntryCount = 2;
        m.resEntryCount = 80;
        m.componentCount = 12;
        return m;
    }

    /** 复刻本目录 app.apk 的结构事实：scrcpy + SPAKE2 + 隐藏 DEX + 56 MB 清单 + 全采集面。 */
    private static GuardApkAudit.Meta ratSample() {
        GuardApkAudit.Meta m = clean();
        m.pkg = "app.cedar.weather.app";
        m.label = "喵喵视频";
        m.signerCn = "CN=r67ab9cbb04161792";
        m.manifestBytes = 56_368_241L;
        m.entryCount = 981;
        m.dexEntryCount = 16;
        m.hiddenDexCount = 15;
        m.resEntryCount = 811;
        m.hasScrcpy = true;
        m.hasSpake2 = true;
        m.a11yService = true;
        m.mediaProjection = true;
        m.requestDelete = true;
        m.overlay = true;
        m.boot = true;
        m.sms = true;
        m.contacts = true;
        m.recordAudio = true;
        m.camera = true;
        m.manageStorage = true;
        m.installPackages = true;
        return m;
    }

    @Test
    public void cleanApkStaysClean() {
        GuardApkAudit.Report r = GuardApkAudit.audit(clean());
        assertEquals(GuardApkAudit.Level.CLEAN, r.level);
        assertEquals(0, r.score);
        assertTrue(r.risks.isEmpty());
        assertEquals("未见明显恶意特征 —— 仍不代表绝对安全", r.headline());
    }

    @Test
    public void ratSampleIsDangerous() {
        GuardApkAudit.Report r = GuardApkAudit.audit(ratSample());
        assertEquals(GuardApkAudit.Level.DANGEROUS, r.level);
        assertEquals(100, r.score);
        assertTrue(hasTitle(r, "内置 scrcpy 远控服务"));
        assertTrue(hasTitle(r, "内置 ADB 无线调试配对库（SPAKE2）"));
        assertTrue(hasTitle(r, "隐藏 DEX 载荷"));
        assertTrue(hasTitle(r, "清单体积异常（清单炸弹）"));
        assertTrue(hasTitle(r, "声明无障碍服务"));
        assertTrue(hasTitle(r, "签名证书可疑"));
        String text = r.report(ratSample());
        assertTrue(text.contains("高危"));
        assertTrue(text.contains("app.cedar.weather.app"));
        assertTrue(text.contains("56.0 MB") || text.contains("53.8 MB") || text.contains("MB"));
    }

    /** 单条重证据只到「可疑」：scrcpy 可能是某类投屏工具，不宜一条就判死。 */
    @Test
    public void singleStructuralSignalIsSuspiciousNotDangerous() {
        GuardApkAudit.Meta m = clean();
        m.hasScrcpy = true;
        GuardApkAudit.Report r = GuardApkAudit.audit(m);
        assertEquals(GuardApkAudit.Level.SUSPICIOUS, r.level);
        assertEquals(50, r.score);
    }

    @Test
    public void hiddenDexOrManifestBombAloneIsSuspicious() {
        GuardApkAudit.Meta dex = clean();
        dex.hiddenDexCount = 2;
        assertEquals(GuardApkAudit.Level.SUSPICIOUS, GuardApkAudit.audit(dex).level);

        GuardApkAudit.Meta bomb = clean();
        bomb.manifestBytes = 3_000_000L;
        assertEquals(GuardApkAudit.Level.SUSPICIOUS, GuardApkAudit.audit(bomb).level);
    }

    /** 无障碍服务本身不判死：正规辅助工具也声明它；要和权限面组合才升档。 */
    @Test
    public void accessibilityAloneIsNotDangerous() {
        GuardApkAudit.Meta m = clean();
        m.a11yService = true;
        GuardApkAudit.Report r = GuardApkAudit.audit(m);
        assertEquals(GuardApkAudit.Level.CLEAN, r.level);
        assertEquals(25, r.score);
    }

    /** 多 DEX 的普通应用不该因为 DEX 数量被打成高危。 */
    @Test
    public void normalMultidexAppIsNotDangerous() {
        GuardApkAudit.Meta m = clean();
        m.dexEntryCount = 10;
        m.resEntryCount = 200;
        GuardApkAudit.Report r = GuardApkAudit.audit(m);
        assertEquals(GuardApkAudit.Level.CLEAN, r.level);
        assertTrue(r.score < 40);
    }

    /** 什么都没读到（选了非 APK）时不下结论。 */
    @Test
    public void unreadableInputDoesNotAccuse() {
        GuardApkAudit.Report r = GuardApkAudit.audit(new GuardApkAudit.Meta());
        assertEquals(GuardApkAudit.Level.CLEAN, r.level);
        assertEquals(0, r.score);
    }

    @Test
    public void nullMetaIsSafe() {
        GuardApkAudit.Report r = GuardApkAudit.audit(null);
        assertEquals(GuardApkAudit.Level.CLEAN, r.level);
        assertEquals(0, r.score);
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
