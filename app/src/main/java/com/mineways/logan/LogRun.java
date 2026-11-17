package com.mineways.logan;

import com.ember.logan.AnalyzedFile;
import com.ember.logan.Analyzer;
import com.ember.logan.FileClassifier;
import com.ember.logan.Finding;
import com.ember.logan.ReportRenderer;
import com.ember.logan.ZipWalker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志分析的一次运行：把引擎（com.ember.logan）从「攒完整包再算」改成「解一个算一个」，
 * 顺便给界面报进度。手机端不能把整包所有条目的行都留在堆里。
 */
public final class LogRun {

    /** 每个文件分析完回调一次（在后台线程上）。 */
    public interface Progress {
        void onFile(String name, int done, int severityMax);
    }

    public static final class Item {
        public final String name;
        public final String typeZh;
        public final double confidence;
        public final int findings;
        public final int severityMax;

        Item(String name, String typeZh, double confidence, int findings, int severityMax) {
            this.name = name;
            this.typeZh = typeZh;
            this.confidence = confidence;
            this.findings = findings;
            this.severityMax = severityMax;
        }
    }

    public static final class Result {
        /** 渲染好的中文报告全文；analyze() 走完才有内容，所以不是 final。 */
        public String report = "";
        public final List<Item> items = new ArrayList<>();
        public int critical;
        public int warn;
        public int info;
        public long totalBytes;

        /** 报告可能很长，界面顶部先给一行速览。 */
        public String brief() {
            int types = 0;
            for (int i = 0; i < items.size(); i++) {
                String t = items.get(i).typeZh;
                boolean seen = false;
                for (int j = 0; j < i; j++) {
                    if (items.get(j).typeZh.equals(t)) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) types++;
            }
            return items.size() + " 个文件 · " + types + " 类来源 · 严重 " + critical
                    + " / 警告 " + warn + " / 提示 " + info;
        }
    }

    private LogRun() {
    }

    public static Result analyze(final File src, final Progress progress) throws Exception {
        final Result result = new Result();
        final List<AnalyzedFile> files = new ArrayList<>();

        ZipWalker.walk(src, new ZipWalker.EntrySink() {
            @Override
            public void onEntry(ZipWalker.Entry e) {
                AnalyzedFile af = one(e);
                files.add(af);
                result.totalBytes += e.size;
                int severity = 0;
                for (Finding f : af.findings) {
                    if (f.severity == Finding.CRITICAL) result.critical++;
                    else if (f.severity == Finding.WARN) result.warn++;
                    else result.info++;
                    if (f.severity > severity) severity = f.severity;
                }
                result.items.add(new Item(af.fileName, af.type.zh, af.confidence,
                        af.findings.size(), severity));
                if (progress != null) {
                    progress.onFile(af.fileName, files.size(), severity);
                }
            }
        });

        result.report = new ReportRenderer().render(src, files);
        return result;
    }

    /** 单文件：识别 → 诊断，和上游 CLI 走同一套调用顺序。 */
    private static AnalyzedFile one(ZipWalker.Entry e) {
        FileClassifier.Result cr = new FileClassifier().classify(e.name, e.head, e.lines);
        AnalyzedFile af = new Analyzer().analyze(e.name, e.size, cr, e.lines);
        af.isBinary = e.binary;
        af.binaryNote = e.binaryNote;
        if (e.binary) {
            af.nature = "二进制文件";
            af.confidence = cr.confidence;
            af.findings.add(new Finding(Finding.INFO, "二进制格式已识别",
                    e.binaryNote, "按建议导出/使用对应工具后可再行文本分析。", "", 1));
        }
        return af;
    }
}
