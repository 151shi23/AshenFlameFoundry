package com.mineways.guardlink;

/**
 * 通知节奏：一波操控只发一条手机通知，第一条必须发。
 *
 * <p>上一版有两处都不对：
 * <ul>
 *   <li><b>真正触发的第一次反而不发手机通知</b> —— 只有确认窗拉不起来时才兜底发一条；
 *       人在别的 App 里，看不见弹窗就等于没通知。</li>
 *   <li><b>后面每条各发各的</b> —— 网络复活（每个对账周期都可能再断一次）、装卸拦截、
 *       守护降级（每次健康变化）都往同一个渠道发，响成一片。</li>
 * </ul>
 *
 * <p>口径改成：Lockdown 起来 = 一波的开始，这一波里<b>第一次</b>检测到人发一条高优先级通知，
 * 后续同类事件只写进事件流并说明「已经发过了」；Lockdown 撤掉 = 这一波结束，
 * 下一波重新算第一次。守护降级不是操控事件，按「缺口内容变了才发」算，免得半小时一条。
 *
 * <p>这个类故意不碰 Android：`claim()` 这种「一次性的名额」最容易在四个调用点各写一套逻辑，
 * 写歪一处就变成了要么完全不通知、要么刷屏；集中成一个能断言的小机器。
 */
public final class GuardNotices {

    /** Lockdown 是不是正在进行（用来认「这一波」的边界）。 */
    private boolean episode;
    /** 这一波已经发过手机通知了。 */
    private boolean notified;
    /** 这一波第一条通知的发信时刻，0 = 还没发。 */
    private long firstAt;
    /** 上一次发过的降级缺口文案，变了才再发。 */
    private String gaps = "";

    /** 一波开始（Lockdown 起来的边沿）：允许发这一波的第一条。 */
    public void episodeStarts() {
        episode = true;
        notified = false;
        firstAt = 0L;
    }

    /** 一波结束（Lockdown 撤掉的边沿）：下一波重新算。 */
    public void episodeEnds() {
        episode = false;
        notified = false;
        firstAt = 0L;
    }

    /**
     * 申请发一条这一波的手机通知。先到先得，第二个调用点就领不到了。
     *
     * @return true = 该发；false = 这一波已经发过，调用方只写事件流
     */
    public boolean claim(long ts) {
        if (notified) {
            return false;
        }
        notified = true;
        firstAt = ts;
        return true;
    }

    /** 守护降级那条通知：缺口内容与上次不同才发，别每次健康心跳都补一条。 */
    public boolean gapsChanged(String now) {
        String s = now == null ? "" : now.trim();
        if (s.length() == 0 || s.equals(gaps)) {
            return false;
        }
        gaps = s;
        return true;
    }

    public boolean inEpisode() {
        return episode;
    }

    /** 这一波是不是已经发过通知了（界面与日志要说实话用）。 */
    public boolean notified() {
        return notified;
    }

    public long firstAt() {
        return firstAt;
    }

    /** 停守护 / 重新起守护时清零，别让上一波的状态骗这一波的界面。 */
    public void reset() {
        episode = false;
        notified = false;
        firstAt = 0L;
        gaps = "";
    }

    /**
     * 状态明细与首屏共用的一句说明。
     *
     * @param stamp 已经格式化好的时刻（时间格式归调用方，这里不碰系统 API）
     */
    public String describe(String stamp) {
        if (!episode) {
            return "现在没有进行中的操控事件；下次检测到的第一次会发手机通知。";
        }
        if (!notified) {
            return "本波还没发通知（下一条同类事件就是首次，会发手机通知）。";
        }
        return "本波手机通知已在 " + stamp + " 发过一次，同一波不再打扰，后面只记进事件流。";
    }
}
