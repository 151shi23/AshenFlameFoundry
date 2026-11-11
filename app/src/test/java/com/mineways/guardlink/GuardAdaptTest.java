package com.mineways.guardlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zeus.guardlink.GuardLinkCore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 守护「适配层」的回归钉子。
 *
 * <p>算法本体由 vendored 的 {@code GuardLinkCore.main()} 自带 9 场景 62 条断言覆盖（那份源码不改一行，
 * 将来拿新 zip 整包覆盖即可）。这里只钉三件为了装进本 App 而写的东西：
 * ① 进 shell 的包名校验（demo 没有，是本包的注入防线）；
 * ② 界面与日志用的纯函数；
 * ③ 文档里承诺过的内核常量与白名单语义 —— 内核被替换时这些会先炸，
 * 而不是线上行为静悄悄变了还没人知道。
 */
public class GuardAdaptTest {

    private static final long T0 = 1_000_000L;

    /** 这些用例钉的是「触摸 6s 后」的内核状态迁移，先关掉真人驻留期，避免 5 分钟把窗口盖住。 */
    private long savedPresence;

    @Before
    public void disableHumanPresence() {
        savedPresence = GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS;
        GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = 0;
    }

    @After
    public void restoreHumanPresence() {
        GuardLinkCore.NoHumanDetector.HUMAN_PRESENCE_MS = savedPresence;
    }

    // ---------------------------------------------------------- 提权命令的入口校验

    @Test
    public void realPackageNamesPassTheShellGuard() {
        assertTrue(GuardShell.safePkg("com.tencent.mm"));
        assertTrue(GuardShell.safePkg("com.android.packageinstaller"));
        assertTrue(GuardShell.safePkg("a-b_c.d0"));
    }

    @Test
    public void emptyAndOversizedAreRejected() {
        assertFalse(GuardShell.safePkg(null));
        assertFalse(GuardShell.safePkg(""));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('a');
        }
        assertFalse(GuardShell.safePkg(sb.toString()));
    }

    /** 包名是拼进 {@code am force-stop <pkg>} 的字符串：任何 shell 元字符都等于开后门。 */
    @Test
    public void shellInjectionShapesAreRejected() {
        String[] attacks = {
                "com.a; rm -rf /",
                "com.a && curl http://x",
                "com.a|nc 1.2.3.4 9",
                "$(reboot)",
                "`id`",
                "com.a b",
                "com.a\tb",
                "com.a\nb",
                "com.a'b",
                "com.a\"b",
                "com.a*",
                "com.a>",
                "com.a&",
                "中文.包",
        };
        for (String s : attacks) {
            assertFalse("应当拒绝：" + s, GuardShell.safePkg(s));
        }
    }

    // ---------------------------------------------------------- 界面 / 日志纯函数

    @Test
    public void flatCollapsesEveryLineBreak() {
        assertEquals("", GuardText.flat(null));
        assertEquals("a b c", GuardText.flat("a\nb\r\nc  "));
        assertEquals("单行不动", GuardText.flat("单行不动"));
    }

    @Test
    public void bytesUsesChineseNumberStyle() {
        assertEquals("0 B", GuardText.bytes(0));
        assertEquals("1023 B", GuardText.bytes(1023));
        assertEquals("1.0 KB", GuardText.bytes(1024));
        assertEquals("1.0 MB", GuardText.bytes(1048576));
    }

    @Test
    public void normalizeTurnsCrlfIntoLf() {
        assertEquals("", GuardText.normalize(null));
        assertEquals("a\nb", GuardText.normalize("a\r\nb"));
    }

    // ---------------------------------------------------------- 内核常量：文档口径不静漂

    @Test
    public void detectorWindowsMatchTheDocumentedNumbers() {
        assertEquals(6_000L, GuardLinkCore.NoHumanDetector.HUMAN_IDLE_WINDOW_MS);
        assertEquals(2_500L, GuardLinkCore.NoHumanDetector.PENDING_CONFIRM_MS);
        assertEquals(2, GuardLinkCore.NoHumanDetector.PENDING_ACTIVITY_BURST);
        assertEquals(10_000L, GuardLinkCore.GuardController.HUMAN_CONFIRM_WINDOW_MS);
    }

    // ---------------------------------------------------------- 白名单语义（决定服务侧过滤）

    /**
     * 内核白名单里写的是 demo 自己的包名 {@code com.zeus.guardlink}，没有本 App 的 {@code com.mineways}。
     * 也就是说：不加服务侧那道过滤，「弹出自己的确认窗」就会被内核当成无人操作下的界面自行变化，
     * 守护自己把自己判成嫌疑人。这条断言就是那层过滤存在的理由 ——
     * 哪天内核真把本包名加进白名单了，这条会失败，提醒我们过滤已经多余。
     */
    @Test
    public void ownPackageIsNotWhitelistedSoTheServiceMustFilterIt() {
        GuardLinkCore.NoHumanDetector d = armedDetector();
        d.feed(event(T0 + 60_000, GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, "com.mineways"));
        assertEquals(GuardLinkCore.NoHumanDetector.State.PENDING, d.getState());
    }

    @Test
    public void whitelistedPackagesNeverArmTheDetector() {
        GuardLinkCore.NoHumanDetector d = armedDetector();
        d.feed(event(T0 + 60_000, GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, "com.android.systemui"));
        assertEquals(GuardLinkCore.NoHumanDetector.State.IDLE, d.getState());
    }

    /** 严格 equals：仿冒前缀（com.android.systemui.tools）不算白名单，交给溯源加权。 */
    @Test
    public void prefixSpoofingIsNotWhitelisted() {
        GuardLinkCore.NoHumanDetector d = armedDetector();
        d.feed(event(T0 + 60_000, GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY,
                "com.android.systemui.tools"));
        assertEquals(GuardLinkCore.NoHumanDetector.State.PENDING, d.getState());
    }

    /** 缓冲期内真人碰了屏幕 → 立刻撤防，这是「不误报活人」的第一道。 */
    @Test
    public void humanTouchDisarmsThePendingWindow() {
        GuardLinkCore.NoHumanDetector d = armedDetector();
        d.feed(event(T0 + 60_000, GuardLinkCore.InteractionEvent.Type.UI_ACTIVITY, "com.victim.app"));
        assertEquals(GuardLinkCore.NoHumanDetector.State.PENDING, d.getState());
        d.feed(event(T0 + 61_000, GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, null));
        assertEquals(GuardLinkCore.NoHumanDetector.State.IDLE, d.getState());
    }

    // ---------------------------------------------------------- 真人按压真伪鉴定

    @Test
    public void programmaticAndInjectedClicksAreRefused() {
        // 无障碍 performAction：根本没有 MotionEvent
        assertNotNull(GuardLinkCore.HumanVerifier.verify(T0, null, null));
        // input tap / adb 注入：按下与抬起同帧（远小于 40ms）
        assertNotNull(GuardLinkCore.HumanVerifier.verify(T0, T0 - 10, T0 - 8));
        // 只按下没抬起
        assertNotNull(GuardLinkCore.HumanVerifier.verify(T0, T0 - 200, null));
        // 按压太久以前，和这次点击脱节
        assertNotNull(GuardLinkCore.HumanVerifier.verify(T0, T0 - 60_000, T0 - 59_900));
    }

    @Test
    public void aRealPressPasses() {
        assertNull(GuardLinkCore.HumanVerifier.verify(T0, T0 - 130, T0 - 10));
        assertEquals(40L, GuardLinkCore.HumanVerifier.MIN_TOUCH_DURATION_MS);
        assertEquals(1500L, GuardLinkCore.HumanVerifier.TOUCH_AGE_WINDOW_MS);
    }

    // ------------------------------------------------------------------ helpers

    /** 状态机：一次人类触摸打底，之后的事件都发生在「无人窗口」之外。 */
    private static GuardLinkCore.NoHumanDetector armedDetector() {
        GuardLinkCore.NoHumanDetector d = new GuardLinkCore.NoHumanDetector(
                new GuardLinkCore.NoHumanDetector.Listener() {
                    @Override
                    public void onNoHuman(GuardLinkCore.NoHumanDetector.TriggerResult r) {
                        // 这些断言只看状态迁移，触发本身由内核自己的 62 条断言覆盖
                    }
                });
        d.feed(event(T0, GuardLinkCore.InteractionEvent.Type.HUMAN_TOUCH, null));
        return d;
    }

    private static GuardLinkCore.InteractionEvent event(
            long ts, GuardLinkCore.InteractionEvent.Type type, String pkg) {
        return new GuardLinkCore.InteractionEvent(ts, type, pkg);
    }
}
