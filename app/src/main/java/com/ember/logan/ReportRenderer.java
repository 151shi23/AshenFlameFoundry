package com.ember.logan;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 美观中文 TXT 诊断报告渲染器。 */
public final class ReportRenderer {

    private static final int W = 66;   // 内容宽度（列）
    private static final String DOUBLE = "═";
    private static final String SINGLE = "─";

    public String render(File source, List<AnalyzedFile> files) {
        StringBuilder b = new StringBuilder();

        long totalBytes = 0;
        for (AnalyzedFile f : files) totalBytes += f.sizeBytes;

        // ================= 头部 =================
        header(b, "万 能 日 志 一 键 分 析 · 智 能 诊 断 报 告");
        kv(b, "生成时间", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        kv(b, "样本来源", source.getName() + "（" + files.size() + " 个文件 · " + human(totalBytes) + "）");
        kv(b, "分析引擎", "EmberLog v1.0 · 指纹识别 × 规则诊断知识库（70+ 条）");
        b.append('\n');

        // ================= 一、总体结论 =================
        section(b, "一、总体结论");

        // 类型聚合
        Map<LogType, List<AnalyzedFile>> byType = new LinkedHashMap<>();
        for (AnalyzedFile f : files) byType.computeIfAbsent(f.type, k -> new ArrayList<>()).add(f);

        List<String> typeNames = new ArrayList<>();
        for (Map.Entry<LogType, List<AnalyzedFile>> e : byType.entrySet()) {
            typeNames.add(e.getKey().zh + (e.getValue().size() > 1 ? "×" + e.getValue().size() : ""));
        }
        bullet(b, "识别出 " + byType.size() + " 类日志来源：" + String.join("、", typeNames));

        int errorFiles = 0, mixedFiles = 0, okFiles = 0;
        for (AnalyzedFile f : files) {
            if (f.nature != null && f.nature.startsWith("报错")) errorFiles++;
            else if (f.nature != null && f.nature.contains("含少量异常")) mixedFiles++;
            else okFiles++;
        }
        bullet(b, "其中报错日志 " + errorFiles + " 份 · 含少量异常 " + mixedFiles + " 份 · 正常运行记录 " + okFiles + " 份。");

        // 最值得关注的文件：健康度最低
        AnalyzedFile worst = null;
        for (AnalyzedFile f : files) {
            if (worst == null || f.health < worst.health) worst = f;
        }
        if (worst != null && worst.health < 100) {
            bullet(b, "最值得关注：「" + Width.trunc(worst.fileName, 30) + "」");
            bullet(b, "→ " + worst.type.zh + " · 健康度 " + worst.health + "/100 · 报错日志");
        }

        int totalCritical = 0, totalWarn = 0;
        for (AnalyzedFile f : files) {
            for (Finding fd : f.findings) {
                if (fd.severity == Finding.CRITICAL) totalCritical += 1;
                else if (fd.severity == Finding.WARN) totalWarn += 1;
            }
        }
        bullet(b, "全包共发现 " + totalCritical + " 处严重问题、" + totalWarn + " 处警告，详见第二章与第三章。");
        b.append('\n');

        // ================= 二、重点故障诊断 =================
        section(b, "二、重点故障 · 自动诊断");
        // 合并所有文件发现，按严重度排序，同 title 合并出处
        Map<String, List<Finding>> merged = new LinkedHashMap<>();
        for (AnalyzedFile f : files) {
            for (Finding fd : f.findings) {
                merged.computeIfAbsent(fd.title, k -> new ArrayList<>()).add(fd);
            }
        }
        List<Map.Entry<String, List<Finding>>> mergedList = new ArrayList<>(merged.entrySet());
        mergedList.sort((a, b2) -> {
            int sa = maxSeverity(a.getValue()), sb = maxSeverity(b2.getValue());
            if (sa != sb) return Integer.compare(sb, sa);
            return Integer.compare(totalHits(b2.getValue()), totalHits(a.getValue()));
        });

        int shown = 0;
        boolean anyCriticalShown = false;
        for (Map.Entry<String, List<Finding>> e : mergedList) {
            List<Finding> fs = e.getValue();
            Finding main = fs.get(0);
            if (main.severity < Finding.WARN) continue;
            if (main.title.equals("Windows 事件概览")) continue;
            if (shown >= 6) break;
            if (main.severity == Finding.CRITICAL) anyCriticalShown = true;

            String tag = main.severity == Finding.CRITICAL ? "【严重】" : "【警告】";
            String srcs = sourcesOf(fs);
            b.append(' ').append(tag).append(e.getKey()).append('\n');
            b.append("   出处：").append(Width.trunc(srcs, W - 10)).append('\n');
            String[] expLines = main.explain.split("\n");
            for (String el : expLines) {
                String t = el.trim();
                if (t.startsWith("⤷")) b.append("   ").append(t).append('\n');
                else b.append("   原因：").append(t).append('\n');
            }
            b.append("   建议：").append(main.advice.replace("；", "\n         ")).append('\n');
            b.append('\n');
            shown++;
        }
        if (shown == 0) {
            bullet(b, "未发现匹配知识库的故障模式 —— 从指纹与统计看，这些日志属于正常运行的记录。");
            bullet(b, "提示：若用户主观感觉有问题，请结合具体应用场景在日志中检索时间点。");
        } else if (!anyCriticalShown) {
            bullet(b, "整体无严重故障，以上为需要留意的警告项。");
        }
        b.append('\n');

        // ================= 三、逐文件识别与诊断 =================
        section(b, "三、逐文件识别与诊断");
        int idx = 0;
        for (AnalyzedFile f : files) {
            idx++;
            b.append(' ').append(String.format("%02d", idx)).append(" ┃ ").append(f.fileName).append('\n');
            String confLine = "类型：" + f.type.zh
                    + "    置信度 " + Width.bar(f.confidence, 14) + " "
                    + String.format("%.1f%%", f.confidence);
            b.append("     ").append(Width.trunc(confLine, W)).append('\n');
            b.append("     性质：").append(f.nature);
            if (f.totalLines > 0) {
                b.append("    行数 ").append(f.totalLines)
                 .append(" · 错误行 ").append(f.errorLines)
                 .append(" · 警告行 ").append(f.warnLines);
            }
            b.append('\n');
            if (!f.timeRange.isEmpty()) {
                b.append("     时间跨度：").append(Width.trunc(f.timeRange, W - 12)).append('\n');
            }
            b.append("     健康度：").append(Width.stars(f.stars, 5)).append(' ')
             .append(f.health).append("/100");
            if (f.severityMax == Finding.CRITICAL) b.append("  ⚠ 检测到严重问题");
            b.append('\n');

            if (!f.highlights.isEmpty()) {
                for (String h : f.highlights) {
                    b.append("     · ").append(Width.trunc(h, W - 8)).append('\n');
                }
            }

            if (!f.findings.isEmpty()) {
                b.append("     ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ 诊断发现 ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄\n");
                int k = 0;
                for (Finding fd : f.findings) {
                    if (k++ >= 4) {
                        b.append("     （其余 ").append(f.findings.size() - k + 1)
                         .append(" 条同类/低危发现已折叠）\n");
                        break;
                    }
                    String tag = fd.severity == Finding.CRITICAL ? "【严重】"
                            : fd.severity == Finding.WARN ? "【警告】" : "【提示】";
                    b.append("     ").append(tag).append(fd.title);
                    if (fd.hits > 1) b.append("（命中 ").append(fd.hits).append(" 次）");
                    b.append('\n');
                    if (!fd.sample.isEmpty()) {
                        b.append("       原文：").append(Width.trunc(fd.sample, W - 10)).append('\n');
                    }
                    for (String el : fd.explain.split("\n")) {
                        String t = el.trim();
                        if (t.startsWith("⤷")) b.append("       ").append(t).append('\n');
                        else b.append("       解释：").append(t).append('\n');
                    }
                    b.append("       建议：").append(fd.advice.replace("；", "\n             ")).append('\n');
                }
            }
            b.append('\n');
        }

        // ================= 四、附录 =================
        section(b, "四、附录");
        b.append(" 识别明细表：\n");
        b.append("   ").append(Width.padEnd("文件", 26)).append(Width.padEnd("识别类型", 24))
          .append("置信度\n");
        b.append("   ").append(repeat(SINGLE, W - 3)).append('\n');
        for (AnalyzedFile f : files) {
            b.append("   ").append(Width.padEnd(Width.trunc(f.fileName, 23), 26))
             .append(Width.padEnd(f.type.zh, 24))
             .append(String.format("%.1f%%", f.confidence)).append('\n');
        }
        b.append('\n');
        bullet(b, "识别原理：强特征词 + 行格式正则命中率 + 弱关键词 + 文件名提示，四路信号加权打分，Softmax 归一化置信度。");
        bullet(b, "诊断原理：70+ 条中文规则知识库（Java/Minecraft/安卓/Windows 事件 ID 等）全文扫描 + 事件 ID 映射。");
        bullet(b, "本报告由程序自动生成，诊断结论供参考；无法覆盖的问题建议携带本报告咨询对应社区。");
        footer(b);
        return b.toString();
    }

    // ---------- 渲染小组件 ----------

    private void header(StringBuilder b, String title) {
        b.append(repeat(DOUBLE, W)).append('\n');
        b.append(Width.center(title, W)).append('\n');
        b.append(repeat(DOUBLE, W)).append('\n');
    }

    private void footer(StringBuilder b) {
        b.append(repeat(DOUBLE, W)).append('\n');
        b.append(Width.center("— 报 告 完 · 祝 排 查 顺 利 —", W)).append('\n');
        b.append(repeat(DOUBLE, W)).append('\n');
    }

    private void section(StringBuilder b, String title) {
        b.append('\n').append(repeat(SINGLE, W)).append('\n');
        b.append("  ").append(title).append('\n');
        b.append(repeat(SINGLE, W)).append('\n');
        b.append('\n');
    }

    private void kv(StringBuilder b, String k, String v) {
        b.append(' ').append(k).append("：").append(v).append('\n');
    }

    private void bullet(StringBuilder b, String text) {
        List<String> wrapped = wrap(text, W - 6);
        b.append(" ● ").append(wrapped.get(0)).append('\n');
        for (int i = 1; i < wrapped.size(); i++) {
            b.append("   ").append(wrapped.get(i)).append('\n');
        }
    }

    /** 按显示宽度折行（中文 2 列），不断词硬切但中文可切。 */
    private List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = Width.w(String.valueOf(c));
            if (acc + cw > width) {
                out.add(cur.toString());
                cur = new StringBuilder();
                acc = 0;
            }
            cur.append(c);
            acc += cw;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private int maxSeverity(List<Finding> fs) {
        int s = 0;
        for (Finding f : fs) s = Math.max(s, f.severity);
        return s;
    }

    private int totalHits(List<Finding> fs) {
        int s = 0;
        for (Finding f : fs) s += f.hits;
        return s;
    }

    private String sourcesOf(List<Finding> fs) {
        List<String> names = new ArrayList<>();
        for (Finding f : fs) {
            String src = f.sourceFile != null && !f.sourceFile.isEmpty() ? f.sourceFile : "(多文件)";
            if (!names.contains(src)) names.add(src);
        }
        return String.join(" ／ ", names);
    }

    private String human(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
