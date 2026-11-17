package com.ember.logan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 单文件深度分析：统计 → 诊断规则扫描 → 类型专项提取 → 健康度评级。 */
public final class Analyzer {

    private static final Pattern RE_TIME_LOG4J  = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2})\\]");
    private static final Pattern RE_TIME_LOGCAT = Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern RE_TIME_FULL   = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern RE_ERR_WORD = Pattern.compile("\\b(?:ERROR|FATAL|SEVERE)\\b|\\[(?:错误|致命|警告)\\]|/(?:ERROR|FATAL)\\]|: (?:Error|Fatal|ERROR)|Exception\\b|错误|致命|崩溃|Exception:");
    private static final Pattern RE_WARN_WORD = Pattern.compile("\\bWARN(?:ING)?\\b|\\[(?:警告|提示)\\]|/WARN\\]|WARNING");
    private static final Pattern RE_CAUSED_BY = Pattern.compile("^Caused by: (.+)$");
    private static final Pattern RE_FCL_SUMMARY = Pattern.compile("Summarize:\\s*(.+)");
    private static final Pattern RE_FCL_DEVICE = Pattern.compile("Device:\\s*(.+)");
    private static final Pattern RE_FCL_RENDERER = Pattern.compile("Renderer:\\s*(.+)");
    private static final Pattern RE_FCL_VERSION = Pattern.compile("FCL Version:\\s*(.+)");
    private static final Pattern RE_FCL_SDK = Pattern.compile("Android SDK:\\s*(\\d+)");
    private static final Pattern RE_ANDROID_PROC = Pattern.compile("Process:\\s*([\\w.]+)");
    private static final Pattern RE_ANDROID_PID = Pattern.compile("^.*?(\\d{4,7}).*?: ([\\w.]+).*?: ");
    private static final Pattern RE_ANR = Pattern.compile("ANR in ([\\w.$]+)");
    private static final Pattern RE_EVENT_ID = Pattern.compile("Event ID:\\s*(\\d+)|<EventID>(\\d+)</EventID>");
    private static final Pattern RE_EVENT_SRC = Pattern.compile("Source:\\s*(.+)|<Provider Name=['\"]([^'\"]+)['\"]");
    private static final Pattern RE_EVENT_LEVEL = Pattern.compile("Level:\\s*(\\w+)|<Level>(\\d+)</Level>");
    private static final Pattern RE_EVENT_DATE = Pattern.compile("Date:\\s*(.+)|<TimeCreated SystemTime=['\"]([^'\"]+)['\"]");
    private static final Pattern RE_GPU = Pattern.compile("Card name:\\s*(.+)");
    private static final Pattern RE_OS = Pattern.compile("Operating System:\\s*(.+)");
    private static final Pattern RE_DXVER = Pattern.compile("DirectX Version:\\s*(.+)");
    private static final Pattern RE_PROB_FRAME = Pattern.compile("Problematic frame:\\s*(.+)");
    private static final Pattern RE_MC_DESC = Pattern.compile("^Description: (.+)$");
    private static final Pattern RE_HMCL_VER = Pattern.compile("\\*{3}\\s*HMCL ([\\w.]+)");
    private static final Pattern RE_PCL_VER = Pattern.compile("Minecraft Launcher \\(PCL\\) ([\\w.]+)");
    private static final Pattern RE_EXIT_CODE = Pattern.compile("exit code\\s*=\\s*(-?\\d+)");
    private static final Pattern RE_JAVA_VER = Pattern.compile("Java Version:\\s*(.+)");
    private static final Pattern RE_BSOD_CODE   = Pattern.compile("(?:Bug Check Code|Bugcheck Code)\\s*:\\s*(0x[0-9A-Fa-f]+)");
    private static final Pattern RE_BSOD_DRIVER = Pattern.compile("Caused By Driver\\s*:\\s*(\\S+)");
    private static final Pattern RE_BSOD_TIME   = Pattern.compile("Crash Time\\s*:\\s*(.+)");
    private static final Pattern RE_LINUX_OOM   = Pattern.compile("Out of memory.*?: (?:Killed|Kill) process (\\d+)");
    private static final Pattern RE_LINUX_SVC   = Pattern.compile("Failed to start ([^.]+)\\.service");
    private static final Pattern RE_XORG_EE     = Pattern.compile("\\(EE\\)\\s*(.+)");
    private static final Pattern RE_MACOS_PROC  = Pattern.compile("^Process:\\s+(\\S+)");
    private static final Pattern RE_MACOS_EXC   = Pattern.compile("^Exception Type:\\s+(.+)");
    private static final Pattern RE_TOMB_FP     = Pattern.compile("Build fingerprint:\\s*'?([^']+)'?");
    private static final Pattern RE_TOMB_ABORT  = Pattern.compile("Abort message:\\s*'(.+)'");
    private static final Pattern RE_TOMB_SIG    = Pattern.compile("signal (\\d+) \\((\\w+)\\)");
    private static final Pattern RE_GRADLE_TASK = Pattern.compile("^> Task (:\\S+) FAILED");
    private static final Pattern RE_GRADLE_WWW  = Pattern.compile("^> (Could not .+)");

    private static final int MAX_ANALYZE_LINES = 40000;

    public AnalyzedFile analyze(String fileName, long size, FileClassifier.Result cr, List<String> lines) {
        AnalyzedFile af = new AnalyzedFile();
        af.fileName = fileName;
        af.sizeBytes = size;
        af.classify = cr;
        af.type = cr.type;
        af.confidence = cr.confidence;

        if (lines.isEmpty()) {
            af.nature = af.isBinary ? "二进制文件" : "空文件";
            af.health = 100;
            af.stars = 5;
            return af;
        }

        int n = Math.min(lines.size(), MAX_ANALYZE_LINES);
        af.totalLines = lines.size();

        String firstTime = null, lastTime = null;
        List<String> lowLines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String l = lines.get(i);
            String low = l.toLowerCase(Locale.ROOT);
            lowLines.add(low);
            if (firstTime == null) { String t = findTime(l); if (t != null) firstTime = t; }
            String t2 = findTime(l);
            if (t2 != null) lastTime = t2;
            if (RE_ERR_WORD.matcher(l).find()) af.errorLines++;
            else if (RE_WARN_WORD.matcher(l).find()) af.warnLines++;
        }
        if (firstTime != null && lastTime != null && !firstTime.equals(lastTime)) {
            af.timeRange = firstTime + " ~ " + lastTime;
        } else if (firstTime != null) {
            af.timeRange = firstTime;
        }

        // ---------- 规则扫描 ----------
        Map<String, Finding> found = new LinkedHashMap<>();
        List<KnowledgeBase.Rule> rules = KnowledgeBase.rulesFor(af.type);
        for (int i = 0; i < n; i++) {
            String raw = lines.get(i);
            String low = lowLines.get(i);
            for (KnowledgeBase.Rule r : rules) {
                if (r.quickKey != null && !low.contains(r.quickKey)) continue;
                Matcher m = r.pattern.matcher(raw);
                if (m.find()) {
                    Finding f = found.get(r.title);
                    if (f == null) {
                        Finding nf = new Finding(r.severity, r.title, r.explain, r.advice,
                                Width.trunc(raw.trim(), 150), 1);
                        found.put(r.title, nf);
                    } else {
                        f.hits++;
                        if (f.sample != null && f.sample.length() < 40) {
                            f.sample = Width.trunc(raw.trim(), 150);
                        }
                    }
                }
            }
        }

        // ---------- Windows 事件专项解析 ----------
        if (af.type == LogType.WIN_EVENT) {
            parseWinEvents(lines, n, found);
        }

        // JVM 崩溃日志本身就是严重故障，无条件记录
        if (af.type == LogType.JVM_CRASH) {
            Finding jf = new Finding(Finding.CRITICAL, "JVM 异常崩溃（hs_err）",
                    "Java 虚拟机在运行中遇到不可恢复错误直接退出（常见触发者：显卡驱动 / 渲染器 native 库 / 内存条不稳定）。",
                    "① 看 Problematic frame 指向的 so/dll：显卡相关（ig9icd/nvoglv/libGL）→ 更新驱动或换渲染器；② libjvm 自身 → 换 Java 版本；③ 偶发一次可观察，反复出现必须处理。", "", 1);
            StringBuilder frames = new StringBuilder();
            for (int i = 0; i < n && frames.length() == 0; i++) {
                Matcher m = RE_PROB_FRAME.matcher(lines.get(i));
                if (m.find()) frames.append(m.group(1).trim());
            }
            if (frames.length() > 0) {
                jf.sample = frames.toString();
                af.highlights.add("崩溃定位帧：" + frames);
            }
            found.put("JVM 异常崩溃（hs_err）", jf);
        }

        // ---------- 类型专项摘要 ----------
        specialSummary(af, lines, n);

        // ---------- 诊断发现排序 ----------
        List<Finding> fs = new ArrayList<>(found.values());
        for (Finding f : fs) f.sourceFile = fileName;
        fs.sort((a, b) -> {
            if (a.severity != b.severity) return Integer.compare(b.severity, a.severity);
            return Integer.compare(b.hits, a.hits);
        });
        // 关联 "Caused by" 链到 MC/启动器日志的发现里
        attachCausedBy(fs, lines, n);
        af.findings = fs;

        // ---------- 性质判定 ----------
        int criticalCount = 0;
        for (Finding f : fs) if (f.severity >= Finding.CRITICAL) criticalCount++;
        for (Finding f : fs) af.severityMax = Math.max(af.severityMax, f.severity);

        if (criticalCount > 0 || af.errorLines > Math.max(3, n * 0.01)) {
            af.nature = "报错日志（检测到明确故障）";
        } else if (af.errorLines > 0 || af.warnLines > 0 || !fs.isEmpty()) {
            af.nature = "运行日志（含少量异常）";
        } else {
            af.nature = "运行状态日志（未见异常）";
        }

        // ---------- 健康度 ----------
        int health = 100;
        for (Finding f : fs) {
            if (f.severity == Finding.CRITICAL) health -= 35;
            else if (f.severity == Finding.WARN) health -= 10;
            else health -= 3;
        }
        if (af.errorLines > 10) health -= Math.min(15, af.errorLines / 5);
        af.health = Math.max(5, Math.min(100, health));
        af.stars = (int) Math.ceil(af.health / 20.0);
        if (af.severityMax >= Finding.CRITICAL) af.stars = Math.min(af.stars, 3);
        if (af.stars < 1) af.stars = 1;
        if (af.stars > 5) af.stars = 5;

        return af;
    }

    private String findTime(String line) {
        Matcher m = RE_TIME_FULL.matcher(line);
        if (m.find()) return m.group(1);
        m = RE_TIME_LOGCAT.matcher(line);
        if (m.find()) return m.group(1);
        m = RE_TIME_LOG4J.matcher(line);
        if (m.find()) return m.group(1);
        return null;
    }

    private void parseWinEvents(List<String> lines, int n, Map<String, Finding> found) {
        Map<String, int[]> stats = new LinkedHashMap<>(); // "ID|Source" -> [count, firstLine]
        String curId = null, curSrc = null;
        int idx = 0;
        for (int i = 0; i < n; i++) {
            String l = lines.get(i);
            Matcher mi = RE_EVENT_ID.matcher(l);
            if (mi.find()) { curId = mi.group(1) != null ? mi.group(1) : mi.group(2); }
            Matcher ms = RE_EVENT_SRC.matcher(l);
            if (ms.find()) { curSrc = (ms.group(1) != null ? ms.group(1) : ms.group(2)).trim(); }
            if (curId != null) {
                String key = curId + "|" + (curSrc == null ? "?" : curSrc);
                int[] c = stats.get(key);
                if (c == null) { c = new int[]{0, i}; stats.put(key, c); }
                c[0]++;
                if (c[0] == 1) {
                    // 命中知识库
                    try {
                        int id = Integer.parseInt(curId);
                        KnowledgeBase.EventKnowledge k = KnowledgeBase.eventKnowledge(id);
                        if (k != null) {
                            String lvl = "?";
                            Matcher ml = RE_EVENT_LEVEL.matcher(l);
                            if (ml.find()) lvl = ml.group(1) != null ? ml.group(1) : ml.group(2);
                            Finding f = found.get(k.title);
                            if (f == null) {
                                found.put(k.title, new Finding(k.severity, k.title,
                                        k.explain + "（来源：" + curSrc + "，事件 ID " + id + "）",
                                        k.advice, Width.trunc(l.trim(), 150), 1));
                            } else {
                                f.hits++;
                            }
                        }
                    } catch (NumberFormatException ignore) {}
                }
                curId = null;
            }
        }
        // 汇总行
        if (!stats.isEmpty()) {
            StringBuilder sb = new StringBuilder("事件统计：");
            int c = 0;
            for (Map.Entry<String, int[]> e : stats.entrySet()) {
                if (c++ >= 4) { sb.append(" 等共 ").append(stats.size()).append(" 类"); break; }
                if (c > 1) sb.append("；");
                sb.append(e.getKey().replace('|', '/')).append("×").append(e.getValue()[0]);
            }
            found.putIfAbsent("__win_stats__" + sb, new Finding(Finding.INFO, "Windows 事件概览",
                    sb.toString(), "重点关注 Error/Critical 级事件，Event ID 已对照知识库解释。", "", 1));
        }
    }

    private void specialSummary(AnalyzedFile af, List<String> lines, int n) {
        List<String> h = af.highlights;
        switch (af.type) {
            case BUILD_GRADLE: {
                for (int i = 0; i < n && h.size() < 4; i++) {
                    Matcher m = RE_GRADLE_TASK.matcher(lines.get(i).trim());
                    if (m.find() && !h.toString().contains(m.group(1))) h.add("失败任务：" + m.group(1));
                    m = RE_GRADLE_WWW.matcher(lines.get(i).trim());
                    if (m.find() && h.stream().noneMatch(s -> s.startsWith("Gradle 自述原因")))
                        h.add("Gradle 自述原因：" + m.group(1));
                }
                break;
            }
            case LINUX_SYSLOG: {
                int oom = 0; String oomProc = null;
                Map<String, Integer> svc = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    Matcher m = RE_LINUX_OOM.matcher(lines.get(i));
                    if (m.find()) { oom++; if (oomProc == null) oomProc = "PID " + m.group(1); }
                    m = RE_LINUX_SVC.matcher(lines.get(i));
                    if (m.find()) svc.merge(m.group(1).trim(), 1, Integer::sum);
                }
                if (oom > 0) h.add("OOM 查杀次数：" + oom + (oomProc != null ? "（首个被杀：" + oomProc + "）" : ""));
                if (!svc.isEmpty()) {
                    StringBuilder sb = new StringBuilder("启动失败的服务：");
                    int c = 0;
                    for (Map.Entry<String, Integer> e : svc.entrySet()) {
                        if (c++ >= 3) break;
                        sb.append(e.getKey()).append("×").append(e.getValue()).append(" ");
                    }
                    h.add(sb.toString().trim());
                }
                if (h.isEmpty()) h.add("系统级运行记录，未提取到明显异常事件");
                break;
            }
            case LINUX_XORG: {
                int ee = 0; String first = null;
                for (int i = 0; i < n; i++) {
                    Matcher m = RE_XORG_EE.matcher(lines.get(i));
                    if (m.find()) {
                        ee++;
                        if (first == null) first = m.group(1).trim();
                    }
                }
                h.add(ee > 0 ? "Xorg 错误行 " + ee + " 条 · 首条：" + Width.trunc(first, 44)
                             : "未见 (EE) 错误行，显示服务运行正常");
                break;
            }
            case MACOS_CRASH: {
                String proc = null, exc = null;
                for (int i = 0; i < n && (proc == null || exc == null); i++) {
                    Matcher m = RE_MACOS_PROC.matcher(lines.get(i).trim());
                    if (proc == null && m.find()) proc = m.group(1);
                    m = RE_MACOS_EXC.matcher(lines.get(i).trim());
                    if (exc == null && m.find()) exc = m.group(1).trim();
                }
                if (proc != null) h.add("崩溃进程：" + proc);
                if (exc != null) h.add("异常类型：" + exc);
                break;
            }
            case ANDROID_TOMBSTONE: {
                String fp = null, abort = null, sig = null;
                for (int i = 0; i < n && (fp == null || abort == null); i++) {
                    String l = lines.get(i);
                    Matcher m = RE_TOMB_FP.matcher(l);
                    if (fp == null && m.find()) fp = m.group(1).trim();
                    m = RE_TOMB_ABORT.matcher(l);
                    if (abort == null && m.find()) abort = m.group(1).trim();
                    m = RE_TOMB_SIG.matcher(l);
                    if (sig == null && m.find()) sig = "signal " + m.group(1) + "（" + m.group(2) + "）";
                }
                if (fp != null) h.add("设备指纹：" + Width.trunc(fp, 46));
                if (sig != null) h.add("崩溃信号：" + sig);
                if (abort != null) h.add("自述原因：" + Width.trunc(abort, 46));
                break;
            }
            case WIN_BSOD_TEXT: {
                String code = null, drv = null, time = null;
                for (int i = 0; i < n && (code == null || drv == null); i++) {
                    String l = lines.get(i);
                    Matcher m = RE_BSOD_CODE.matcher(l);
                    if (code == null && m.find()) code = m.group(1).toUpperCase(Locale.ROOT);
                    m = RE_BSOD_DRIVER.matcher(l);
                    if (drv == null && m.find()) drv = m.group(1);
                    m = RE_BSOD_TIME.matcher(l);
                    if (time == null && m.find()) time = m.group(1).trim();
                }
                if (time != null) h.add("蓝屏时间：" + time);
                if (code != null) {
                    KnowledgeBase.EventKnowledge k = KnowledgeBase.bsodStopCode(code);
                    h.add("停止码：" + code + " · " + (k != null ? k.title : "未知停止码（建议检索微软文档）"));
                }
                if (drv != null) h.add("肇事驱动：" + drv + (drv.toLowerCase(Locale.ROOT).contains("nvlddmkm") ? "（NVIDIA 显卡驱动）" : ""));
                break;
            }
            case FCL_LAUNCHER: {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < n && seen.size() < 6; i++) {
                    String l = lines.get(i);
                    Matcher m = RE_FCL_VERSION.matcher(l);
                    if (m.find() && seen.add("v")) h.add("FCL 版本：" + m.group(1).trim());
                    m = RE_FCL_DEVICE.matcher(l);
                    if (m.find() && seen.add("d")) h.add("设备：" + m.group(1).trim());
                    m = RE_FCL_RENDERER.matcher(l);
                    if (m.find() && seen.add("r")) h.add("渲染器：" + m.group(1).trim());
                    m = RE_FCL_SDK.matcher(l);
                    if (m.find() && seen.add("s")) h.add("Android SDK：" + m.group(1) + "（Android " + sdkToAndroid(m.group(1)) + "）");
                    m = RE_FCL_SUMMARY.matcher(l);
                    if (m.find() && seen.add("m")) h.add("FCL 自述结论：" + m.group(1).trim());
                    m = RE_EXIT_CODE.matcher(l);
                    if (m.find() && seen.add("e")) h.add("游戏退出码：" + m.group(1));
                }
                break;
            }
            case HMCL_LAUNCHER: {
                for (int i = 0; i < n && h.size() < 4; i++) {
                    String l = lines.get(i);
                    Matcher m = RE_HMCL_VER.matcher(l);
                    if (m.find()) h.add("HMCL 版本：" + m.group(1));
                    m = RE_OS.matcher(l);
                    if (m.find()) h.add("操作系统：" + m.group(1).trim());
                    m = RE_JAVA_VER.matcher(l);
                    if (m.find()) h.add("Java 版本：" + m.group(1).trim());
                }
                break;
            }
            case PCL2_LAUNCHER: {
                for (int i = 0; i < n && h.size() < 3; i++) {
                    Matcher m = RE_PCL_VER.matcher(lines.get(i));
                    if (m.find()) h.add("PCL2 版本：" + m.group(1));
                }
                break;
            }
            case ANDROID_LOGCAT: {
                Map<String, Integer> tags = new LinkedHashMap<>();
                String fatalProc = null;
                for (int i = 0; i < n; i++) {
                    String l = lines.get(i);
                    if (fatalProc == null) {
                        Matcher m = RE_ANDROID_PROC.matcher(l);
                        if (m.find() && l.contains("FATAL")) fatalProc = m.group(1);
                    }
                    // 标准格式：... E TagName : message
                    java.util.regex.Matcher tm = Pattern.compile(" [VDIWEF] ([A-Za-z0-9_.\\-]{2,28})\\s*:").matcher(l);
                    if (tm.find()) {
                        String tag = tm.group(1);
                        if (!tag.equals("null")) tags.merge(tag, 1, Integer::sum);
                    }
                }
                if (fatalProc != null) h.add("崩溃应用：" + fatalProc);
                if (!tags.isEmpty()) {
                    List<Map.Entry<String, Integer>> top = new ArrayList<>(tags.entrySet());
                    top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    StringBuilder sb = new StringBuilder("高频标签：");
                    for (int i = 0; i < Math.min(4, top.size()); i++) {
                        if (i > 0) sb.append("、");
                        sb.append(top.get(i).getKey()).append("×").append(top.get(i).getValue());
                    }
                    h.add(sb.toString());
                }
                for (int i = 0; i < n; i++) {
                    Matcher m = RE_ANR.matcher(lines.get(i));
                    if (m.find()) { h.add("ANR 目标：" + m.group(1)); break; }
                }
                break;
            }
            case WIN_DXDIGA: {
                for (int i = 0; i < n && h.size() < 4; i++) {
                    String l = lines.get(i);
                    Matcher m = RE_GPU.matcher(l);
                    if (m.find()) h.add("显卡：" + m.group(1).trim());
                    m = RE_OS.matcher(l);
                    if (m.find()) h.add("系统：" + m.group(1).trim());
                    m = RE_DXVER.matcher(l);
                    if (m.find()) h.add("DirectX：" + m.group(1).trim());
                }
                break;
            }
            case JVM_CRASH:
                // 定位帧已在 analyze() 的无条件诊断块中提取，此处无需重复
                break;
            case MC_GAME: {
                for (int i = 0; i < n && h.size() < 3; i++) {
                    Matcher m = RE_MC_DESC.matcher(lines.get(i));
                    if (m.find()) { h.add("崩溃描述：" + m.group(1).trim()); break; }
                }
                break;
            }
            default: break;
        }
    }

    private String sdkToAndroid(String sdk) {
        try {
            int v = Integer.parseInt(sdk.trim());
            if (v >= 35) return "15+"; if (v >= 34) return "14"; if (v >= 33) return "13";
            if (v >= 32) return "12L"; if (v >= 31) return "12"; if (v >= 30) return "11";
            if (v >= 29) return "10"; if (v >= 28) return "9"; if (v >= 27) return "8.1";
            if (v >= 26) return "8.0"; if (v >= 25) return "7.1"; if (v >= 24) return "7.0";
            return "?";
        } catch (Exception e) { return "?"; }
    }

    /** 抽取 Caused by 异常链（最多展示 3 条），挂到最严重的发现上。 */
    private void attachCausedBy(List<Finding> findings, List<String> lines, int n) {
        List<String> chain = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Matcher m = RE_CAUSED_BY.matcher(lines.get(i).trim());
            if (m.find()) {
                String c = m.group(1).trim();
                if (!chain.contains(c) && chain.size() < 3) chain.add(c);
            }
        }
        if (!chain.isEmpty()) {
            Finding target = findings.isEmpty() ? null : findings.get(0);
            if (target == null) {
                target = new Finding(Finding.CRITICAL, "检测到异常链（Caused by）",
                        "日志存在异常调用链，链尾通常就是根因。", "从最后一条 Caused by 开始排查。", "", 1);
                findings.add(target);
            }
            target.explain = target.explain + "\n       ⤷ 根因链：" + String.join(" ← ", chain);
        }
    }
}
