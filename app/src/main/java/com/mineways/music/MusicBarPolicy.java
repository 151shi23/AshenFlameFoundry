package com.mineways.music;

/**
 * 软件内悬浮播放条的判定（纯函数，零安卓依赖，可以直接 javac 跑断言）。
 *
 * <p>三个事实凑在一起决定「现在该不该画出来」和「退出音乐页时该不该问一句」：
 * 用户上次的答案、云控让不让（{@code music_bar_off} / {@code music_in_off}）、
 * 系统级悬浮条是不是开着（两条同时出现就是重复）。
 */
public final class MusicBarPolicy {

    /** 还没问过用户 —— 退出音乐页时要问一次。 */
    public static final String ASK = "ask";
    /** 用户同意过：退出后在软件任意界面挂悬浮条。 */
    public static final String ON = "on";
    /** 用户说过不要：不再问，也不再挂。想改回来在音乐页「⋯」里翻。 */
    public static final String OFF = "off";

    public final boolean show;
    public final boolean ask;
    public final String reason;

    private MusicBarPolicy(boolean show, boolean ask, String reason) {
        this.show = show;
        this.ask = ask;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return (show ? "显示" : (ask ? "该问" : "不显示")) + "（" + reason + "）";
    }

    /**
     * @param consent      {@link #ASK} / {@link #ON} / {@link #OFF}
     * @param barAllowed   云控允许软件内悬浮条（{@code music_bar_off} 没写、且软件内播放这档开着）
     * @param inAppAllowed 云控允许「软件内播放」
     * @param playing      有曲目在放或备放（{@code MusicCtl.active()}）
     * @param overlayOn    系统级悬浮条开着
     */
    public static MusicBarPolicy of(String consent, boolean barAllowed, boolean inAppAllowed,
                                   boolean playing, boolean overlayOn) {
        if (!inAppAllowed) {
            return new MusicBarPolicy(false, false, "云控关了软件内播放");
        }
        if (!barAllowed) {
            return new MusicBarPolicy(false, false, "云控关了软件内悬浮条（music_bar_off）");
        }
        if (!playing) {
            return new MusicBarPolicy(false, false, "现在没在播放");
        }
        if (overlayOn) {
            return new MusicBarPolicy(false, false, "系统悬浮条已经开着，不再挂第二条");
        }
        String c = consent == null ? ASK : consent.trim().toLowerCase(java.util.Locale.ROOT);
        if (OFF.equals(c)) {
            return new MusicBarPolicy(false, false, "用户选过不要悬浮条");
        }
        if (ON.equals(c)) {
            return new MusicBarPolicy(true, false, "用户同意在软件内播放");
        }
        return new MusicBarPolicy(false, true, "还没问过用户");
    }

    /** 用户刚答完一次：同意 → on，拒绝 → off（不再骚扰）。 */
    public static String afterAnswer(boolean agreed) {
        return agreed ? ON : OFF;
    }

    /** 云控键没写过就按「问过并且同意」处理？不行 —— 没写过就是还没问过。 */
    public static String normalize(String stored) {
        String c = stored == null ? "" : stored.trim().toLowerCase(java.util.Locale.ROOT);
        return ON.equals(c) || OFF.equals(c) ? c : ASK;
    }
}
