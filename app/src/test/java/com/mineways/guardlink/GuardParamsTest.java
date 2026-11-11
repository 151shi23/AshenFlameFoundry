package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zeus.guardlink.GuardLinkCore;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * v3.2 参数层与能力判定的回归钉子（纯 JVM，不需要 Robolectric）。
 *
 * <p>这里钉的是三件「装进本 App 之后才会成立」的事：
 * ① 内核那 21 处最小补丁真的可写、且判定运行时读的是字段（不是编译期内联的常量）；
 * ② 参数的出厂值取自内核，界面不会写着 6 秒而内核其实跑 5 秒；
 * ③ 无障碍防火墙的信任判定与 Lockdown 的第四种动作（冻结陌生前台包）语义没走样。
 *
 * <p>算法本身的 13 场景 96 断言由 {@code GuardLinkCore.main()} 自带覆盖，那份源码不改一行
 * （除了上述 21 处去 final），所以这里不重复它的场景。
 */
public class GuardParamsTest {

    // ------------------------------------------------------------ 清单本身自洽

    @Test
    public void inventoryIsSelfConsistent() {
        assertEquals(29, GuardParams.items().size());
        Set<String> ids = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (GuardParams.Group g : GuardParams.groups()) {
            groups.add(g.id);
        }
        for (GuardParams.Item it : GuardParams.items()) {
            assertTrue("id 重复：" + it.id, ids.add(it.id));
            assertTrue("分组不存在：" + it.group, groups.contains(it.group));
            assertTrue("范围反了：" + it.id, it.min <= it.max);
            assertTrue("步长必须正：" + it.id, it.step > 0);
            assertTrue("出厂值不在可设范围：" + it.id,
                    it.factory >= it.min && it.factory <= it.max);
            assertTrue("标题为空：" + it.id, it.title.length() > 0);
            assertTrue("说明为空：" + it.id, it.note.length() > 0);
        }
    }

    @Test
    public void factoryValuesComeFromTheKernel() {
        // 这些数字必须来自内核字段本身；一旦上游改了出厂值，这份断言跟着变，界面不会说谎
        assertEquals(GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS,
                GuardParams.find("idle_ms").factory);
        assertEquals(GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS,
                GuardParams.find("pending_ms").factory);
        assertEquals(GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS,
                GuardParams.find("presence_ms").factory);
        assertEquals(GuardLinkCore.GuardController.SILENT_AFTER_FIRES,
                GuardParams.find("silent_after").factory);
        assertEquals(GuardLinkCore.SuspectEngine.W_BEHAVIOR_HIT,
                GuardParams.find("w_behavior").factory);
        assertEquals(GuardLinkCore.SuspectEngine.PRIMARY_SUSPECT_THRESHOLD,
                GuardParams.find("score_min").factory);
    }

    // ------------------------------------------------------------ clamp 与云控解析

    @Test
    public void clampSnapsIntoRangeAndStep() {
        assertEquals(2000, GuardParams.clamp(1, 2000, 30000, 500));
        assertEquals(30000, GuardParams.clamp(999999, 2000, 30000, 500));
        assertEquals(6000, GuardParams.clamp(6120, 2000, 30000, 500));   // 吸附到 6000
        assertEquals(6250, GuardParams.clamp(6240, 2000, 30000, 250));   // 吸附到 6250
        assertEquals(7, GuardParams.clamp(7, 1, 10, 1));
        assertEquals(5, GuardParams.clamp(4, 5, 5, 1));                  // 允许 min==max
    }

    /** 区间写反/步长非法是清单自己的错误，退回较小的界，不能把怪值写进内核。 */
    @Test
    public void illegalRangeFallsBackToMin() {
        assertEquals(2000, GuardParams.clamp(5000, 30000, 2000, 500));   // min>max
        assertEquals(2000, GuardParams.clamp(5000, 2000, 30000, 0));     // step=0
        assertEquals(2000, GuardParams.clamp(5000, 2000, 30000, -5));
    }

    @Test
    public void cloudValueOnlyAcceptsRealNumbers() {
        assertEquals(9000L, GuardParams.cloudValue("9000"));
        assertEquals(-5L, GuardParams.cloudValue(" -5 "));
        assertEquals(Long.MIN_VALUE, GuardParams.cloudValue(null));
        assertEquals(Long.MIN_VALUE, GuardParams.cloudValue(""));
        assertEquals(Long.MIN_VALUE, GuardParams.cloudValue("   "));
        assertEquals(Long.MIN_VALUE, GuardParams.cloudValue("6s"));      // 带单位不算下发了
        assertEquals(Long.MIN_VALUE, GuardParams.cloudValue("1,2"));
    }

    // ------------------------------------------------------------ 补丁真的生效

    /**
     * 这条是整份补丁的验收：写进去之后，判定运行时读的就是新值。
     *
     * <p>如果那 21 处还留着 final，Java 会把常量在编译期内联，写就写不进去、
     * 或写进去了但 {@code feed()} 里的比较还在用老数字 —— 界面调窗口就成了纯粹的安慰剂。
     */
    @Test
    public void detectorActuallyReadsThePatchedField() {
        long original = GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS;
        long originalPresence = GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS;
        try {
            // 这条量的是「无人操作窗口」本身；先关掉真人驻留期，避免 5 分钟把它盖住
            GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = 0;
            // 窗口拉到 20 秒：10 秒无触摸不该命中
            GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS = 20_000L;
            GuardLinkCore.NoHumanDetector d = detector();
            d.feed(event(0, GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, null));
            d.feed(event(10_000, GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, "com.evil.spy"));
            assertEquals(GuardLinkCore.NoHumanDetector.State.IDLE, d.getState());

            // 窗口缩到 3 秒：同一份事件就该命中
            GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS = 3_000L;
            GuardLinkCore.NoHumanDetector d2 = detector();
            d2.feed(event(0, GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, null));
            d2.feed(event(10_000, GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, "com.evil.spy"));
            assertEquals(GuardLinkCore.NoHumanDetector.State.PENDING, d2.getState());
        } finally {
            GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS = original;
            GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = originalPresence;
        }
    }

    @Test
    public void itemWriteLandsInKernelAndRestores() {
        GuardParams.Item it = GuardParams.find("pending_ms");
        long factory = it.factory;
        try {
            it.write(4_000L);                       // 4000 落在 250 的网格上
            assertEquals(4_000L, GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS);
            assertEquals(4_000L, it.current());
            it.write(4_321L);                       // 网格外的值必须被吸附，而不是原样塞进内核
            assertEquals(4_250L, GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS);
        } finally {
            it.write(factory);
        }
        assertEquals(factory, GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS);
    }

    /** 范围外的值不该被原样写进内核 —— 滑杆拖动与脏数据都靠这一条兜住。 */
    @Test
    public void outOfRangeWriteIsClampedNotStored() {
        GuardParams.Item it = GuardParams.find("idle_ms");
        long factory = it.factory;
        try {
            it.write(999_999_999L);
            assertEquals(it.max, GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS);
            it.write(-1L);
            assertEquals(it.min, GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS);
        } finally {
            it.write(factory);
        }
    }

    /** 整数型参数（权重/次数）写入时按 int 落地，不能悄悄截成别的数。 */
    @Test
    public void integerParamsRoundTripExactly() {
        GuardParams.Item w = GuardParams.find("w_behavior");
        GuardParams.Item b = GuardParams.find("burst");
        long fw = w.factory, fb = b.factory;
        try {
            w.write(37);
            b.write(5);
            assertEquals(37, GuardLinkCore.SuspectEngine.W_BEHAVIOR_HIT);
            assertEquals(5, GuardLinkCore.NoHumanDetector.PENDING_ACTIVITY_BURST);
        } finally {
            w.write(fw);
            b.write(fb);
        }
    }

    // ------------------------------------------------------------ v3.2 新语义

    /** 防火墙只掐无名小厂：这条判定错了就是「把用户的 TalkBack/输入法关掉」事故。 */
    @Test
    public void a11yTrustOnlyCutsUnknownVendors() {
        assertTrue(GuardLinkCore.A11yPolicy.isTrusted("com.tencent.mm", false));
        assertTrue(GuardLinkCore.A11yPolicy.isTrusted("com.eg.android.AlipayGphone", false));
        assertTrue(GuardLinkCore.A11yPolicy.isTrusted("com.iflytek.inputmethod", false));
        assertTrue(GuardLinkCore.A11yPolicy.isTrusted("com.icbc", false));
        assertTrue(GuardLinkCore.A11yPolicy.isTrusted("com.xiaomi.smarthome", false));
        // 真系统包不看名字，看 FLAG_SYSTEM —— TalkBack 与各家自带服务都靠这条放行
        assertTrue(GuardLinkCore.A11yPolicy.isTrusted("com.some.rom.service", true));
        assertFalse(GuardLinkCore.A11yPolicy.isTrusted("com.unknown.toolkit", false));
        // 前缀伪装：com.tencentx. 不等于 com.tencent.
        assertFalse(GuardLinkCore.A11yPolicy.isTrusted("com.tencentx.evil", false));
        assertFalse(GuardLinkCore.A11yPolicy.isTrusted(null, false));
        // 上游没明说的边界：base 恰好等于包名本身也要放行（com.icbc 而不是只有 com.icbc.pay）
        assertTrue(GuardLinkCore.A11yPolicy.isTrusted("com.icbc", false));
    }

    /** v3.2 起 Lockdown 多一种动作：陌生前台包要冻结，桌面与自身要豁免。 */
    @Test
    public void lockdownRoutesFourActions() {
        GuardLinkCore.LockdownController lk = new GuardLinkCore.LockdownController();
        assertEquals(GuardLinkCore.LockdownController.Action.NONE, lk.evaluate("com.evil.spy"));
        lk.engage();
        GuardLinkCore.LockdownController.EXTRA_WHITELIST.add("com.android.launcher3");
        assertEquals(GuardLinkCore.LockdownController.Action.BLOCK_INSTALLER,
                lk.evaluate("com.android.packageinstaller"));
        assertEquals(GuardLinkCore.LockdownController.Action.KICK_HOME,
                lk.evaluate("com.tencent.mm"));
        assertEquals(GuardLinkCore.LockdownController.Action.NONE,
                lk.evaluate("com.android.launcher3"));
        assertEquals(GuardLinkCore.LockdownController.Action.FREEZE_SUSPECT,
                lk.evaluate("com.evil.accomplice"));
        lk.disarm();
        assertEquals(GuardLinkCore.LockdownController.Action.NONE, lk.evaluate("com.tencent.mm"));
    }

    /** 降噪的另一半：桌面 widget 自刷新不计作案证据。 */
    @Test
    public void whitelistedPackagesNeverEnterPending() {
        GuardLinkCore.NoHumanDetector.EXTRA_WHITELIST.add("com.android.launcher3");
        GuardLinkCore.NoHumanDetector d = detector();
        d.feed(event(0, GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, null));
        d.feed(event(60_000, GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, "com.android.launcher3"));
        assertEquals(GuardLinkCore.NoHumanDetector.State.IDLE, d.getState());
    }

    /** C1 改双条件：光抬手亮屏不该触发，亮屏 + 窗口内自启动才触发。 */
    @Test
    public void screenOnAloneDoesNotFire() {
        long originalPresence = GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS;
        try {
            GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = 0;
            GuardLinkCore.NoHumanDetector d = detector();
            d.feed(event(0, GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, null));
            d.feed(event(60_000, GuardLinkCore.InteractionEvent.Type.SCREEN_ON, null));
            d.onPendingTimeout(70_000);
            assertEquals(GuardLinkCore.NoHumanDetector.State.IDLE, d.getState());

            d.feed(event(70_000, GuardLinkCore.InteractionEvent.Type.APP_RESUMED, "com.evil.spy", 0, true));
            assertEquals(GuardLinkCore.NoHumanDetector.State.PENDING, d.getState());
        } finally {
            GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = originalPresence;
        }
    }

    // ------------------------------------------------------------ 文案换算

    @Test
    public void durationReadsLikeHumanSpeech() {
        assertEquals("40 毫秒", GuardText.duration(40));
        assertEquals("6 秒", GuardText.duration(6000));
        assertEquals("2.5 秒", GuardText.duration(2500));
        assertEquals("1.5 秒", GuardText.duration(1500));
        assertEquals("0 毫秒", GuardText.duration(0));
        assertEquals("60 秒", GuardText.duration(60000));
        assertNotNull(GuardText.stamp(0));
    }

    @Test
    public void stampNeverRendersEpochZero() {
        assertEquals("没发生过", GuardText.stamp(0));
        assertEquals("没发生过", GuardText.stamp(-1));
        assertFalse(GuardText.stamp(1_700_000_000_000L).contains("1970"));
        assertNull(GuardParams.find("no_such_key"));
    }

    // ------------------------------------------------------------------ helpers

    private static GuardLinkCore.NoHumanDetector detector() {
        return new GuardLinkCore.NoHumanDetector(new GuardLinkCore.NoHumanDetector.Listener() {
            @Override
            public void onNoHuman(GuardLinkCore.NoHumanDetector.TriggerResult r) {
                // 这些断言只看状态迁移，触发链由内核自己的 96 条覆盖
            }
        });
    }

    private static GuardLinkCore.InteractionEvent event(
            long ts, GuardLinkCore.InteractionEvent.Type type, String pkg) {
        return new GuardLinkCore.InteractionEvent(ts, type, pkg);
    }

    private static GuardLinkCore.InteractionEvent event(
            long ts, GuardLinkCore.InteractionEvent.Type type, String pkg, long delta, boolean confirmed) {
        return new GuardLinkCore.InteractionEvent(ts, type, pkg, delta, confirmed);
    }
}
