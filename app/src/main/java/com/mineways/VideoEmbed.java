package com.mineways;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站 / 抖音官方嵌入代码与分享链接的统一解析器（纯 Java，可在 JVM 断言里跑死）。
 *
 * <p>用户把官方"复制嵌入代码"得到的 iframe 片段、或分享链接、或裸 BV 号原样贴进
 * 云控配置 / 公告里的 {@code url=} / {@code video:}，这里负责识别平台并归一成
 * 一个可直接放进 WebView 的播放页地址。
 *
 * <p>支持的输入（按顺序尝试）：
 * <ul>
 *   <li>B 站嵌入代码 / 播放地址：{@code bvid=BV…} / {@code player.bilibili.com/…}</li>
 *   <li>B 站分享链接：{@code /video/BV…}、{@code av17001}、{@code aid=17001}</li>
 *   <li>裸 BV 号：{@code BV1xx411c7mD}</li>
 *   <li>抖音嵌入代码：{@code /embed/<id>}、{@code vid=<id>}、{@code modal_id=<id>}、{@code /video/<id>}
 *       —— 一律归一到官方播放器 {@code open.douyin.com/player/video?vid=<id>}（无需权限，
 *       分享页/旧 embed 端点都不可靠）</li>
 *   <li>抖音分享短链：{@code https://v.douyin.com/xxxx/}（整条直接交给 WebView，由它跟随跳转）</li>
 * </ul>
 *
 * <p>输出：{@code [平台, 播放页地址]}；识别不了返回 {@code null}（调用方报错，不猜）。
 * 播放页域名白名单由 WebView 侧把关（bilibili.com / douyin.com / iesdouyin.com）。
 */
public final class VideoEmbed {

    private VideoEmbed() {
    }

    /** @return {平台, 播放页地址}；识别不了返回 null。 */
    public static String[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() == 0) {
            return null;
        }

        // ---- 官方嵌入代码优先：B 站「复制嵌入代码」那一段（<iframe src="…player.bilibili.com…">）
        //      原样收下。里面的 isOutside / aid / cid / p 都是 B 站自己下发的参数，改写它们就不再是
        //      官方嵌入了 —— 实测把地址折成 ?bvid=… 去**顶层**加载，B 站会 302 到另一份移动端页面
        //      （/blackboard/webplayer/mbplayer.html），那份页面在 WebView 里不画画面。----
        String official = officialBiliSrc(s);
        if (official != null) {
            return new String[]{"bili", official};
        }

        // ---- B 站：bvid=… 或 player.bilibili.com 里带的 bvid ----
        Matcher m = Pattern.compile("(?i)bvid\\s*=\\s*[\"']?(BV[0-9A-Za-z]{8,})").matcher(s);
        if (m.find()) {
            return new String[]{"bili", biliPlayer(m.group(1))};
        }

        // ---- B 站：分享链接里的 /video/BV… ----
        m = Pattern.compile("(?i)/video/(BV[0-9A-Za-z]{8,})").matcher(s);
        if (m.find()) {
            return new String[]{"bili", biliPlayer(m.group(1))};
        }

        // ---- B 站：裸 BV 号（BV + 10 位）----
        m = Pattern.compile("(?<![0-9A-Za-z])BV[0-9A-Za-z]{10}(?![0-9A-Za-z])").matcher(s);
        if (m.find()) {
            return new String[]{"bili", biliPlayer(m.group())};
        }

        // ---- B 站：av 号 / aid= ----
        m = Pattern.compile("(?i)(?:av|aid=)(\\d{4,})").matcher(s);
        if (m.find()) {
            return new String[]{"bili",
                    "https://player.bilibili.com/player.html?aid=" + m.group(1)
                            + "&page=1&high_quality=1&danmaku=0&autoplay=0"};
        }

        // ---- 抖音：嵌入代码 vid= / /embed/<id> / modal_id= / /video/<id> ----
        // 目标一律用开放平台的官方播放器（open.douyin.com/player/video?vid=<id>）：
        // iesdouyin 分享页对无 ttwid cookie 的 WebView 会 SSR 出「只能在抖音观看」兜底页，
        // douyin.com/embed/<id> 端点也已 404（2026-10-17 实测）；官方播放器是无需权限的
        // 正路（文档：通过 VideoID 获取 IFrame 代码，open.douyin.com）。
        m = Pattern.compile("(?i)(?:vid\\s*=\\s*[\"']?|/embed/|modal_id=|/video/)(\\d{6,})").matcher(s);
        if (m.find()) {
            return new String[]{"douyin", "https://open.douyin.com/player/video?vid="
                    + m.group(1) + "&autoplay=0"};
        }

        // ---- 抖音分享短链：v.douyin.com 直接交给 WebView 跟随跳转 ----
        m = Pattern.compile("(?i)https?://v\\.douyin\\.com/[0-9A-Za-z/_-]+").matcher(s);
        if (m.find()) {
            return new String[]{"douyin", m.group()};
        }

        // ---- 抖音：已经是嵌入地址 → 抠出 id 换成官方播放器 ----
        if (s.contains("douyin.com/embed/")) {
            m = Pattern.compile("(?i)/embed/(\\d{6,})").matcher(s);
            if (m.find()) {
                return new String[]{"douyin", "https://open.douyin.com/player/video?vid="
                        + m.group(1) + "&autoplay=0"};
            }
            return new String[]{"douyin", s};
        }

        return null;
    }

    /**
     * 从**官方嵌入代码**里抠出 B 站播放器的原始 src（属性顺序、引号、是否带协议都容错）；
     * 不是 B 站播放器地址就返回 null，交给下面的归一化分支。
     *
     * <p>只认 {@code player.bilibili.com}：抖音那边官方 {@code /embed/<id>} 端点已经 404，
     * 原样转过去只会得到 404 页，所以抖音必须归一到开放平台播放器，B 站才有真正可用的嵌入码。
     */
    private static String officialBiliSrc(String s) {
        Matcher m = Pattern.compile("(?i)src\\s*=\\s*[\"']?((?:https?:)?//player\\.bilibili\\.com/[^\"'\\s>]+)")
                .matcher(s);
        if (!m.find()) {
            m = Pattern.compile("(?i)((?:https?:)?//player\\.bilibili\\.com/[^\"'\\s<>]+)").matcher(s);
            if (!m.find()) {
                return null;
            }
        }
        String u = m.group(1);
        return u.startsWith("//") ? "https:" + u : u;
    }

    private static String biliPlayer(String bvid) {
        return "https://player.bilibili.com/player.html?bvid=" + bvid
                + "&page=1&high_quality=1&danmaku=0&autoplay=0";
    }

    /** WebView 允许加载的播放页域名（云控只能把展示栏指向这两家的官方播放器）。 */
    public static boolean isAllowedPlayerUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        return u.contains("player.bilibili.com")
                || u.contains(".bilibili.com/")
                || u.contains(".douyin.com/")
                || u.contains(".iesdouyin.com/");
    }
}
