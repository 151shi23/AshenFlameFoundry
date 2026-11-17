package com.ember.logan;

import java.util.ArrayList;
import java.util.List;

/** 单个日志文件的完整分析结果。 */
public class AnalyzedFile {
    public String fileName;
    public long sizeBytes;
    public int totalLines;
    public String timeRange = "";

    public FileClassifier.Result classify;
    public LogType type = LogType.UNKNOWN;
    public double confidence;

    public String nature;            // 报错日志 / 运行日志（含异常） / 运行状态日志 / 二进制文件
    public int errorLines, warnLines;
    public int severityMax = 0;      // 最高严重度
    public List<Finding> findings = new ArrayList<>();
    public int health = 100;         // 健康度 0~100
    public int stars = 5;            // 星级 1~5

    public List<String> highlights = new ArrayList<>(); // 专项摘要行（中文）
    public boolean isBinary = false;
    public String binaryNote = "";
}
