package com.ember.logan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 指纹识别器：对单个日志文件做多信号加权打分，输出类型 + 置信度 + 次选。 */
public final class FileClassifier {

    /** 调试开关：打印每类得分明细。 */
    public static boolean DEBUG = false;

    public static class Result {
        public LogType type;
        public double confidence;       // 0~100
        public List<LogType> runnersUp; // 次选类型
        public String matchReason;      // 命中理由（中文，给人看）
    }

    private static final double STRONG_HIT  = 240.0; // 强特征单条
    private static final double FORMAT_CAP  = 300.0; // 行格式满分配额
    private static final double WEAK_HIT    = 18.0;  // 弱词单条
    private static final double NAME_MULT   = 1.0;   // 文件名加分独立叠加
    private static final double TEMPERATURE = 55.0; // softmax 温度（exp(s/T)）

    public Result classify(String fileName, byte[] head, List<String> lines) {
        Signals sigs = SingletonHolder.SIGS;
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        // 二进制魔数直接裁决
        Result magic = checkMagic(head);
        if (magic != null) return magic;

        double[] scores = new double[LogType.values().length];
        String[] reasons = new String[LogType.values().length];
        String lowBody = bodySample(lines);

        for (Signals.Fingerprint fp : sigs.all) {
            double s = 0;
            StringBuilder why = new StringBuilder();

            // 1) 强特征词
            int strongHits = 0;
            for (String w : fp.strongWords) {
                if (lowBody.contains(w.toLowerCase(Locale.ROOT))) {
                    strongHits++;
                    if (strongHits == 1) why.append("特征词「").append(w).append("」");
                }
            }
            if (strongHits > 0) {
                s += STRONG_HIT + (strongHits - 1) * 60.0;
                if (strongHits > 1) why.append("等 ").append(strongHits).append(" 条强特征");
            }

            // 2) 行格式命中率
            int fmtHit = 0, fmtTry = 0;
            for (String raw : lines) {
                String t = raw.trim();
                if (t.isEmpty()) continue;
                fmtTry++;
                for (Pattern p : fp.lineFormats) {
                    Matcher m = p.matcher(raw);
                    if (m.find()) { fmtHit++; break; }
                }
            }
            if (fmtTry > 0) {
                double ratio = (double) fmtHit / fmtTry;
                if (ratio > 0.25) {
                    s += FORMAT_CAP * Math.min(1.0, ratio * 1.35);
                    if (why.length() > 0) why.append("；");
                    why.append(String.format(Locale.ROOT, "行格式命中 %d/%d", fmtHit, fmtTry));
                }
            }

            // 3) 弱关键词
            int weakHits = 0;
            for (String w : fp.weakWords) {
                if (lowBody.contains(w.toLowerCase(Locale.ROOT))) weakHits++;
            }
            if (weakHits > 0) {
                s += Math.min(weakHits, 14) * WEAK_HIT;
                if (weakHits >= 4) {
                    if (why.length() > 0) why.append("；");
                    why.append("弱特征 ").append(weakHits).append(" 处");
                }
            }

            // 4) 文件名提示
            for (Map.Entry<String, Double> e : fp.nameHints.entrySet()) {
                if (lowerName.matches(e.getKey().replace("^", "^"))) {
                    s += e.getValue();
                    if (why.length() > 0) why.append("；");
                    why.append("文件名匹配「").append(e.getKey().replace("\\\\", "\\")).append("」");
                    break;
                }
            }

            scores[fp.type.ordinal()] += s;
            if (reasons[fp.type.ordinal()] == null || reasons[fp.type.ordinal()].length() < why.length()) {
                reasons[fp.type.ordinal()] = why.toString();
            }
        }

        // softmax 归一化置信度：exp(s/T)，T 越小优势类置信度越尖
        double max = 0, sum = 0;
        int best = LogType.UNKNOWN.ordinal(), second = -1;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > max) { max = scores[i]; }
        }
        for (int i = 0; i < scores.length; i++) {
            sum += Math.exp(scores[i] / TEMPERATURE);
        }
        double[] soft = new double[scores.length];
        for (int i = 0; i < scores.length; i++) soft[i] = Math.exp(scores[i] / TEMPERATURE) / sum;
        for (int i = 0; i < scores.length; i++) {
            if (i != best && (second < 0 || soft[i] > soft[second])) second = i;
        }
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > scores[best]) best = i;
        }
        // 重算 second（排除 best）
        second = -1;
        for (int i = 0; i < scores.length; i++) {
            if (i == best) continue;
            if (second < 0 || scores[i] > scores[second]) second = i;
        }

        Result r = new Result();
        r.type = LogType.values()[best];
        double conf = soft[best] * 100.0;
        if (scores[best] <= 0) { r.type = LogType.UNKNOWN; conf = 0; }
        r.confidence = conf;
        r.runnersUp = new ArrayList<>();
        if (second >= 0 && scores[second] > 40) r.runnersUp.add(LogType.values()[second]);
        r.matchReason = reasons[best] == null ? "无有效特征" : reasons[best].toString();
        if (DEBUG) {
            StringBuilder sb = new StringBuilder("  [SCORES] " + fileName + "\n");
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] > 0) sb.append(String.format("    %-16s %.0f%n", LogType.values()[i], scores[i]));
            }
            System.out.print(sb);
        }
        return r;
    }

    private String bodySample(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int budget = 200_000;
        for (String l : lines) {
            if (sb.length() > budget) break;
            sb.append(l).append('\n');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private Result checkMagic(byte[] h) {
        String eight = asciiHead(h, 8);
        String four = asciiHead(h, 4);
        if (eight.startsWith("ElfFile")) return magic(LogType.WIN_EVENT, 99.5, "检测到 .evtx 事件日志二进制魔数（ElfFile）");
        if (four.startsWith("PAGE")) return magic(LogType.WIN_MINIDUMP, 99.0, "检测到 Windows minidump 转储魔数（PAGEDU*）");
        if ((h[0] & 0xFF) == 0x50 && (h[1] & 0xFF) == 0x4B) return null; // zip 由上层解
        if (eight.startsWith("MZ")) return null; // PE 文件忽略
        return null;
    }

    private Result magic(LogType t, double conf, String reason) {
        Result r = new Result();
        r.type = t;
        r.confidence = conf;
        r.runnersUp = new ArrayList<>();
        r.matchReason = reason;
        return r;
    }

    private String asciiHead(byte[] h, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, h.length); i++) {
            char c = (char) (h[i] & 0xFF);
            sb.append(c >= 32 && c < 127 ? c : (c == 0 ? '\0' : '?'));
        }
        return sb.toString();
    }

    private static final class SingletonHolder { static final Signals SIGS = Signals.build(); }
}
