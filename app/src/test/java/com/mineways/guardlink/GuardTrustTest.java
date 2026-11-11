package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mineways.guardlink.GuardTrust.Entry;
import com.mineways.guardlink.GuardTrust.Level;
import com.mineways.guardlink.GuardTrust.Table;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 豁免名单的纯逻辑层断言：三档强度、三级来源合并、目录语义匹配。
 *
 * <p>这三件事都必须脱离 Android 也能验：合并顺序写反了，界面照样显示「已豁免」而内核读的是别的值；
 * 目录语义写松了（{@code startsWith("com.tencent")}），{@code com.tencentx.evil} 就直接领了免死金牌；
 * 三档写成一句话，「游戏只是不许动手」就变成了「游戏完全不报警」。
 */
public class GuardTrustTest {

    private static Entry e(String pkg, Level lv) {
        return e(pkg, lv, "内置");
    }

    private static Entry e(String pkg, Level lv, String source) {
        return Table.parseLine(pkg + "|中文名|" + Table.levelName(lv), source, "出处");
    }

    private static Map<String, Entry> table(Entry... entries) {
        return Table.merge(Arrays.asList(entries), Collections.<Entry>emptyList(),
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
                Collections.<String>emptySet());
    }

    // ---------------------------------------------------------------- 目录语义

    @Test
    public void dirEntryCoversSubPackages() {
        Map<String, Entry> t = table(e("com.tencent.", Level.EVIDENCE));
        assertNotNull(Table.find(t, "com.tencent.mm"));
        assertNotNull(Table.find(t, "com.tencent.mm.sub"));
        assertNotNull(Table.find(t, "com.tencent"));      // 父包自己也是合法包，按内核口径同样放行
    }

    /** 仿冒知名前缀是这条路最容易漏的地方，必须挡住。 */
    @Test
    public void dirEntryRejectsLookalikePrefix() {
        Map<String, Entry> t = table(e("com.tencent.", Level.EVIDENCE));
        assertNull(Table.find(t, "com.tencentx.evil"));
        assertNull(Table.find(t, "com.tencentservice"));
    }

    @Test
    public void exactEntryAlsoCoversItsSubPackages() {
        Map<String, Entry> t = table(e("com.icbc", Level.EVIDENCE));
        assertNotNull(Table.find(t, "com.icbc"));
        assertNotNull(Table.find(t, "com.icbc.pay"));
    }

    /** 手打的大小写不该决定生不生效，但档位语义本身不许被绕过。 */
    @Test
    public void lookupIgnoresCaseOnlyInConfigHandwriting() {
        Map<String, Entry> t = table(e("com.miHoYo.Yuanshen", Level.IRREVERSIBLE));
        assertEquals(Level.IRREVERSIBLE, Table.find(t, "com.miHoYo.Yuanshen").level);
        assertEquals(Level.IRREVERSIBLE, Table.find(t, "com.mihoyo.yuanshen").level);
    }

    @Test
    public void emptyOrNullQueryIsNotAMatch() {
        Map<String, Entry> t = table(e("com.tencent.", Level.EVIDENCE));
        assertNull(Table.find(t, null));
        assertNull(Table.find(t, ""));
        assertEquals(Level.NONE, Table.levelOf(t, "com.unknown.toolkit"));
        assertEquals(Level.NONE, Table.levelOf(t, "com.zeus.other"));
    }

    // ---------------------------------------------------------------- 三档强度

    @Test
    public void evidenceLevelIsTheOnlyOneThatSuppressesTheSignal() {
        Map<String, Entry> t = table(e("com.game.one", Level.IRREVERSIBLE),
                e("com.big.two", Level.RANK), e("com.ime.three", Level.EVIDENCE));
        assertFalse(Table.notEvidence(t, "com.game.one"));
        assertFalse(Table.notEvidence(t, "com.big.two"));
        assertTrue(Table.notEvidence(t, "com.ime.three"));
    }

    /** ①② 都不进榜，③ 可以上榜 —— 这条写错就等于把游戏从报警里抹掉了。 */
    @Test
    public void onlyIrreversibleLevelStillEntersTheRanking() {
        Map<String, Entry> t = table(e("com.game.one", Level.IRREVERSIBLE),
                e("com.big.two", Level.RANK), e("com.ime.three", Level.EVIDENCE));
        assertFalse(Table.keepOutOfRank(t, "com.game.one"));
        assertTrue(Table.keepOutOfRank(t, "com.big.two"));
        assertTrue(Table.keepOutOfRank(t, "com.ime.three"));
        assertFalse(Table.keepOutOfRank(t, "com.nobody"));
    }

    /** 三档全都不许动手：豁免名单的意义正落在这一条上。 */
    @Test
    public void everyLevelBlocksIrreversibleActions() {
        Map<String, Entry> t = table(e("com.game.one", Level.IRREVERSIBLE),
                e("com.big.two", Level.RANK), e("com.ime.three", Level.EVIDENCE));
        assertTrue(Table.blockIrreversible(t, "com.game.one"));
        assertTrue(Table.blockIrreversible(t, "com.big.two"));
        assertTrue(Table.blockIrreversible(t, "com.ime.three"));
        assertFalse(Table.blockIrreversible(t, "com.nobody"));
    }

    /** 挡下来时给的那句要能自证：哪一档、谁定的。 */
    @Test
    public void blockReasonSaysWhichLevelAndWhoseCall() {
        Map<String, Entry> t = table(e("com.big.app", Level.RANK));
        String why = Table.blockReason(Table.find(t, "com.big.app"));
        assertTrue(why.contains("不进榜"));
        assertTrue(why.contains("内置"));
        assertTrue(why.contains("com.big.app") || why.contains("中文名"));
        assertNull(Table.blockReason(null));
    }

    /** 系统应用那条更硬：不靠名单，也不看档位。 */
    @Test
    public void systemAppsAreRefusedEvenWhenNotListed() {
        Map<String, Entry> empty = table();
        String why = Table.refusal(empty, "com.miui.securitycenter", true, "冻结");
        assertTrue(why.contains("FLAG_SYSTEM"));
        assertTrue(why.contains("冻结"));
        assertNull(Table.refusal(empty, "com.unknown.tool", false, "冻结"));
        // 名单里的档位照旧生效（isSystemApp=false）
        Map<String, Entry> t = table(e("com.big.app", Level.RANK), e("com.game.one", Level.IRREVERSIBLE));
        assertTrue(Table.refusal(t, "com.big.app", false, "卸载").contains("不进榜"));
        assertTrue(Table.refusal(t, "com.game.one", false, "精确断网").contains("不许动手"));
        assertNull(Table.refusal(t, "com.nobody", false, "卸载"));
    }

    // ---------------------------------------------------------------- 三级合并

    @Test
    public void cloudLevelBeatsLocalAndBuiltin() {
        Map<String, String> cloud = new LinkedHashMap<>();
        cloud.put("com.big.app", "evidence");
        Map<String, Entry> merged = Table.merge(
                Arrays.asList(e("com.big.app", Level.IRREVERSIBLE)),
                Arrays.asList(e("com.big.app", Level.RANK, "本机")), cloud,
                Collections.<String, String>emptyMap(), Collections.<String>emptySet());
        Entry hit = merged.get("com.big.app");
        assertEquals(Level.EVIDENCE, hit.level);
        assertEquals("云控", hit.source);
        assertTrue(hit.from.contains("evidence"));
    }

    @Test
    public void localBeatsBuiltinWhenCloudSaysNothing() {
        Map<String, Entry> merged = Table.merge(
                Arrays.asList(e("com.big.app", Level.IRREVERSIBLE)),
                Arrays.asList(e("com.big.app", Level.RANK, "本机")),
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
                Collections.<String>emptySet());
        assertEquals(Level.RANK, merged.get("com.big.app").level);
        assertEquals("本机", merged.get("com.big.app").source);
    }

    /** 云控 drop 要能把内置那条撤掉，否则「撤下来」只是界面好看。 */
    @Test
    public void cloudDropRemovesEvenTheBuiltinEntry() {
        Set<String> drops = new LinkedHashSet<>();
        drops.add("com.big.app");
        Map<String, Entry> merged = Table.merge(Arrays.asList(e("com.big.app", Level.RANK)),
                Collections.<Entry>emptyList(), Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(), drops);
        assertNull(Table.find(merged, "com.big.app"));
    }

    @Test
    public void cloudAddAndDropInSameBatchKeepTheLaterOne() {
        Set<String> drops = new LinkedHashSet<>();
        drops.add("com.big.app");
        Map<String, String> cloud = new LinkedHashMap<>();
        cloud.put("com.big.app", "rank");
        Map<String, Entry> merged = Table.merge(Collections.<Entry>emptyList(),
                Collections.<Entry>emptyList(), cloud, Collections.<String, String>emptyMap(), drops);
        assertNotNull(Table.find(merged, "com.big.app"));   // drop 先执行，同批的 add 仍然生效
    }

    @Test
    public void cloudNameIsUsedForDisplayWhenGiven() {
        Map<String, String> cloud = new LinkedHashMap<>();
        cloud.put("com.x.y", "rank");
        Map<String, String> names = new LinkedHashMap<>();
        names.put("com.x.y", "云控起的名字");
        Map<String, Entry> merged = Table.merge(Collections.<Entry>emptyList(),
                Collections.<Entry>emptyList(), cloud, names, Collections.<String>emptySet());
        assertEquals("云控起的名字", merged.get("com.x.y").name);
    }

    @Test
    public void garbageCloudLevelIsIgnoredNotGuessed() {
        Map<String, String> cloud = new LinkedHashMap<>();
        cloud.put("com.big.app", "maybe");
        Map<String, Entry> merged = Table.merge(Collections.<Entry>emptyList(),
                Collections.<Entry>emptyList(), cloud, Collections.<String, String>emptyMap(),
                Collections.<String>emptySet());
        assertTrue(merged.isEmpty());
    }

    // ---------------------------------------------------------------- 行解析

    @Test
    public void badLinesAreSkippedNotFatal() {
        List<String> lines = Arrays.asList(
                "# 注释",
                "",
                "com.tencent.mm|微信|evidence|出处",
                "nodotsplit|无点|rank|坏包名",
                "com.bad.level|档位不认识|whitelist|坏档",
                "com.short");
        List<Entry> out = new ArrayList<>();
        for (String l : lines) {
            Entry e = Table.parseLine(l, "内置", "");
            if (e != null) {
                out.add(e);
            }
        }
        assertEquals(1, out.size());
        assertEquals("com.tencent.mm", out.get(0).pkg);
        assertEquals("出处", out.get(0).from);
    }

    @Test
    public void nameFallsBackToPackageWhenAbsent() {
        Entry noName = Table.parseLine("com.x.y||rank", "内置", "");
        assertNotNull(noName);
        assertEquals("com.x.y", noName.name);
    }

    @Test
    public void chineseLevelWordsAreAccepted() {
        assertEquals(Level.EVIDENCE, Table.toLevel(
                com.mineways.cloud.FeatureCatalog.pkgLevelOf("不算证据")));
        assertEquals(Level.RANK, Table.toLevel(
                com.mineways.cloud.FeatureCatalog.pkgLevelOf("不进榜")));
        assertEquals(Level.IRREVERSIBLE, Table.toLevel(
                com.mineways.cloud.FeatureCatalog.pkgLevelOf("不可逆")));
        assertEquals(Level.NONE, Table.toLevel(
                com.mineways.cloud.FeatureCatalog.pkgLevelOf("随便写点什么")));
    }
}
