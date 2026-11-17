package com.mineways;

import com.mineways.cloud.CloudFlags;

/**
 * 串词工具箱 Pro Max 的云控开关。
 *
 * <p>全部走 {@link CloudFlags#bool}，所以和普通 FLAG 一样支持
 * {@code if=}、{@code rollout=}、{@code from/until}、{@code minv/maxv}、链式覆盖。
 * 默认全开；云端只关掉某一项时，对应入口/控件消失，旧功能不受影响。
 *
 * <pre>
 * phrase_on=0            整个串词工具箱入口隐藏（配合 ui_hide_pages=phrase）
 * phrase_pro=0           回退到旧版：只剩搜索 / 复制 / 谐音 / 随机 / 新增 / 导入
 * phrase_favorites=0     关闭收藏与收藏筛选
 * phrase_recent=0        关闭最近使用与最近筛选
 * phrase_batch=0         关闭批量选择/复制/分享
 * phrase_usage=0         关闭「用过 N 次」统计
 * phrase_random=0        关闭随机来一条
 * phrase_sticker=0       关闭存表情包
 * phrase_import=0        关闭导入 txt
 * phrase_homophone=0     关闭谐音替换（复选框、模式、谐音表）
 * </pre>
 */
public final class PhraseFlags {

    private PhraseFlags() {
    }

    public static boolean on() {
        return bool("phrase_on", true);
    }

    /** Pro Max 总闸；关闭后所有新增功能一起回退。 */
    public static boolean pro() {
        return on() && bool("phrase_pro", true);
    }

    public static boolean favorites() {
        return pro() && bool("phrase_favorites", true);
    }

    public static boolean recent() {
        return pro() && bool("phrase_recent", true);
    }

    public static boolean batch() {
        return pro() && bool("phrase_batch", true);
    }

    public static boolean usage() {
        return pro() && bool("phrase_usage", true);
    }

    public static boolean random() {
        return on() && bool("phrase_random", true);
    }

    public static boolean sticker() {
        return on() && bool("phrase_sticker", true);
    }

    public static boolean importTxt() {
        return on() && bool("phrase_import", true);
    }

    public static boolean homophone() {
        return on() && bool("phrase_homophone", true);
    }

    private static boolean bool(String key, boolean def) {
        try {
            return CloudFlags.bool(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
