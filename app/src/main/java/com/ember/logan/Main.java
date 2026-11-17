package com.ember.logan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 万能日志一键分析 Demo · CLI 入口
 * 用法：java -cp out com.ember.logan.Main <日志文件.zip 或单个日志文件> [输出目录]
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法：java -cp out com.ember.logan.Main <日志.zip|日志文件> [输出目录]");
            System.exit(2);
        }
        File src = new File(args[0]);
        if (!src.isFile()) {
            System.out.println("文件不存在：" + src);
            System.exit(2);
        }
        File outDir = args.length >= 2 ? new File(args[1]) : src.getAbsoluteFile().getParentFile();
        FileClassifier.DEBUG = Boolean.getBoolean("logan.debug");

        System.out.println("[1/3] 读取并解包 " + src.getName() + " …");
        List<ZipWalker.Entry> entries = ZipWalker.walk(src);
        System.out.println("      收到 " + entries.size() + " 个日志文件");

        System.out.println("[2/3] 指纹识别 + 深度诊断 …");
        List<AnalyzedFile> results = new ArrayList<>();
        for (ZipWalker.Entry e : entries) {
            FileClassifier classifier = new FileClassifier();
            FileClassifier.Result cr = classifier.classify(e.name, e.head, e.lines);
            AnalyzedFile af = new Analyzer().analyze(e.name, e.size, cr, e.lines);
            af.isBinary = e.binary;
            af.binaryNote = e.binaryNote;
            if (e.binary) {
                af.nature = "二进制文件";
                af.confidence = cr.confidence;
                Finding f = new Finding(Finding.INFO, "二进制格式已识别",
                        e.binaryNote, "按建议导出/使用对应工具后可再行文本分析。", "", 1);
                af.findings.add(f);
            }
            results.add(af);
        }

        System.out.println("[3/3] 渲染中文诊断报告 …");
        String report = new ReportRenderer().render(src, results);

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File out = new File(outDir, "日志分析报告_" + stamp + ".txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(report);
        }
        System.out.println();
        System.out.println("════════ 识别速览 ════════");
        for (AnalyzedFile af : results) {
            System.out.printf("  %-46s → %-14s %5.1f%%%n",
                    shortName(af.fileName, 46), af.type.zh, af.confidence);
        }
        System.out.println();
        System.out.println("报告已导出：" + out.getAbsolutePath());
    }

    private static String shortName(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
