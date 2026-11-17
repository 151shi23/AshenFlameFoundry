package com.ember.logan;

/** 一条诊断发现：问题 → 中文解释 → 解决办法。 */
public class Finding {
    public static final int CRITICAL = 3; // 严重
    public static final int WARN     = 2; // 警告
    public static final int INFO     = 1; // 提示

    public final int severity;      // 严重度
    public final String title;      // 问题（中文）
    public String explain;          // 解释（中文）
    public final String advice;     // 解决办法（中文）
    public String sample;           // 日志原文样例（已截断）
    public String sourceFile;       // 发现来源文件名（报告第二章用）
    public int hits;                // 命中次数

    public Finding(int severity, String title, String explain, String advice, String sample, int hits) {
        this.severity = severity;
        this.title = title;
        this.explain = explain;
        this.advice = advice;
        this.sample = sample;
        this.hits = hits;
    }
}
