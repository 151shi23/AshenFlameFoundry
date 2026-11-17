package com.ember.logan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 日志类型指纹库。
 * 每个类型由三类信号构成：
 *   1) 强特征词（出现即大幅加分，近乎一锤定音）
 *   2) 行格式正则（按命中行占比加分）
 *   3) 弱关键词（辅助加分）
 */
public final class Signals {

    /** 单类型指纹。 */
    public static final class Fingerprint {
        public final LogType type;
        public final List<String> strongWords;      // 强特征（不区分大小写）
        public final List<Pattern> lineFormats;     // 行格式正则
        public final List<String> weakWords;        // 弱关键词
        public final Map<String, Double> nameHints; // 文件名正则 -> 加分

        Fingerprint(LogType type, List<String> strong, List<String> formats,
                    List<String> weak, Map<String, Double> nameHints) {
            this.type = type;
            this.strongWords = strong;
            this.lineFormats = new ArrayList<>();
            for (String f : formats) this.lineFormats.add(Pattern.compile(f));
            this.weakWords = weak;
            this.nameHints = nameHints;
        }
    }

    public final List<Fingerprint> all = new ArrayList<>();

    // ---------- 各类型行格式正则 ----------
    private static final String RE_LOGCAT_LINE =
            "^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} +\\d{2,7} +\\d{2,7} [VDIWEF] ";
    private static final String RE_LOGCAT_COLON =
            "^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} +[VDIWEF]/";
    private static final String RE_PCL_LINE =
            "^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] \\[(?:普通|提示|重要|警告|错误|致命|调试)\\] ";
    private static final String RE_HMCL_LINE =
            "^\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[\\w$.]+/(?:INFO|WARN|ERROR|DEBUG|TRACE|FINE)\\] ";
    private static final String RE_MC_LINE =
            "^\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[^/\\[\\]]{1,40}/(?:INFO|WARN|ERROR|FATAL|DEBUG|TRACE)\\]: ";
    private static final String RE_CBS_LINE =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}, (?:Info|Warning|Error) (?:CBS|CSI|SQM|POQ) ";
    private static final String RE_WIN_EVENT_FIELD =
            "^(?:Log Name|Source|Event ID|Task Category|Level|Keywords|User|Computer|Description|OpCode|记录|来源|事件 ID|级别|计算机):\\s*";
    private static final String RE_WIN_EVENT_XML =
            "<Event xmlns=['\"]http://schemas\\.microsoft\\.com/win/2004/08/events/event|<EventID>|<Provider Name=";
    private static final String RE_WER_LINE =
            "^(?:Fault bucket|Problem signature:|P[1-9]:|Event\\[0\\]|Sig\\[\\d\\])";
    private static final String RE_DXDIGA_LINE =
            "^(?:DirectX Diagnostic Tool|Card name:|Manufacturer:|Chip type:|Display Memory:|Current Mode:|Driver Version:|DxDiag Notes:)";
    private static final String RE_HSERR_LINE =
            "^#\\s+(?:A fatal error has been detected|Java Runtime Environment|There is insufficient memory|JRE version|Problematic frame)";
    private static final String RE_CRASH_REPORT_LINE =
            "^---- Minecraft Crash Report ----|^// |^Time: |^Description: |^at \\w+\\.";
    private static final String RE_SYSLOG_LINE =
            "^[A-Z][a-z]{2} [ 0-9]\\d \\d{2}:\\d{2}:\\d{2} [\\w.-]+ [\\w/.-]+(?:\\[\\d+\\])?: ";
    private static final String RE_DMESG_LINE =
            "^\\[\\s*\\d+\\.\\d{4,6}\\] ";
    private static final String RE_XORG_LINE =
            "^\\s*\\[\\s*\\d+\\.\\d+\\] \\(([EIW]|--|\\*\\*)\\) |^X\\.Org X Server";
    private static final String RE_MACOS_CRASH_LINE =
            "^(?:Process:|Path:|Identifier:|Version:|Exception Type:|Exception Codes:|Crashed:|Termination Reason:|Thread \\d+ Crashed|Application Specific Information:)";
    private static final String RE_TOMBSTONE_LINE =
            "^(?:\\*\\*\\* \\*\\*\\* \\*\\*\\*|pid: \\d+, tid: \\d+|Build fingerprint:|signal \\d+ \\(|backtrace:|#\\d+ pc [0-9a-f]+ |Abort message:)";
    private static final String RE_BSOD_TEXT_LINE =
            "^(?:Dump File|Crash Time|Bug Check Code|BugCheck Code|Parameter [1-4]|Caused By Driver|File Description|Bug Check String)\\s*:";
    private static final String RE_GRADLE_LINE =
            "^> (?:Task|Could not|What went wrong)|^\\* (?:What went wrong|Try:|Exception is:)|^FAILURE: Build failed|^\\d+ actionable tasks?";

    private Signals() {}

    private static Map<String, Double> names(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Double) kv[i + 1]);
        return m;
    }

    public static Signals build() {
        Signals s = new Signals();

        s.all.add(new Fingerprint(LogType.PCL2_LAUNCHER,
                Arrays.asList("minecraft launcher (pcl)", "plain craft launcher", "PCL.版本", "PCL 启动器"),
                Arrays.asList(RE_PCL_LINE, "^#a#|^#de#|^#pl#"),
                Arrays.asList("正在启动", "下载失败", "离线登录", "正版登录", "版本隔离", "开始下载", "已启动游戏", "PCL"),
                names("Log[1-9]\\.txt", 8.0, ".*PCL.*\\.txt", 6.0, "Log-.*\\.txt", 6.0)));

        s.all.add(new Fingerprint(LogType.HMCL_LAUNCHER,
                Arrays.asList("*** hmcl", "org.jackhuang.hmcl", "hello minecraft! launcher"),
                Arrays.asList(RE_HMCL_LINE),
                Arrays.asList("HMCL Directory", "Operating System:", "Java VM Version:", "Java Executable",
                        "hmcl.log", "启动器版本", "Exporting logs"),
                names("hmcl.*\\.log", 8.0, "hmcl-.*\\.log", 8.0)));

        s.all.add(new Fingerprint(LogType.FCL_LAUNCHER,
                Arrays.asList("com.tungsten.fcl", "fold craft launcher", "fcl version:", "[fcl internal]", "fcl_exported"),
                Arrays.asList("^={2,} ?[A-Za-z ]+ ?={2,}$", "^invoke \\w+", "^Env: \\w+="),
                Arrays.asList("POJAV_RENDERER", "Android SDK", "Renderer:", "Basic Information",
                        "Start Minecraft", "FCL", "setFCLNativeWindow", "Summarize:"),
                names("fcl.*\\.log", 8.0, "fcl-exported-logs.*", 10.0)));

        s.all.add(new Fingerprint(LogType.MC_GAME,
                Arrays.asList("---- minecraft crash report ----", "modlauncher running", "launchwrapper", "forge mod loading"),
                Arrays.asList(RE_MC_LINE, RE_CRASH_REPORT_LINE),
                Arrays.asList("net.minecraft", "net.minecraftforge", "net.fabricmc", "org.spongepowered",
                        "Setting user:", "Loading for game", "minecraft", "Mod list", "crash-report"),
                names("latest\\.log", 6.0, "debug\\.log", 5.0, "crash-.*\\.txt", 8.0, ".*\\.minecraft.*", 3.0)));

        s.all.add(new Fingerprint(LogType.JVM_CRASH,
                Arrays.asList("a fatal error has been detected by the java runtime environment", "exception_access_violation", "hs_err_pid"),
                Arrays.asList(RE_HSERR_LINE),
                Arrays.asList("Problematic frame", "siginfo:", "Registers:", "Stack:", "Current thread", "# C ",
                        "libjvm.so", "ntdll.dll"),
                names("hs_err_pid.*", 15.0, "replay_pid.*", 6.0)));

        s.all.add(new Fingerprint(LogType.ANDROID_LOGCAT,
                Arrays.asList("fatal exception: ", "androidruntime", "beginning of crash", "beginning of main"),
                Arrays.asList(RE_LOGCAT_LINE, RE_LOGCAT_COLON),
                Arrays.asList("ANR in", "ActivityManager", "lowmemorykiller", "System.err", "Zygote",
                        "Force finishing activity", "has died", "PackageManager"),
                names("logcat.*", 8.0, ".*logcat.*", 8.0, "radio\\.log", 5.0, "events.*\\.log", 4.0)));

        s.all.add(new Fingerprint(LogType.WIN_EVENT,
                Arrays.asList("http://schemas.microsoft.com/win/2004/08/events/event"),
                Arrays.asList(RE_WIN_EVENT_FIELD, RE_WIN_EVENT_XML),
                Arrays.asList("Event ID", "Service Control Manager", "Kernel-Power", "Application Error",
                        "事件查看器", "System", "Application", "windows update", "*FAILED* [", "bugcheck"),
                names(".*evtx", 6.0, ".*event.*\\.txt", 6.0, ".*event.*\\.xml", 6.0, "system\\.evtx", 10.0)));

        s.all.add(new Fingerprint(LogType.WIN_CBS,
                Arrays.asList("cannot repair member file", "poqexec", "checksur"),
                Arrays.asList(RE_CBS_LINE),
                Arrays.asList("CBS", "Component Store", "corruption", "SFC", "修复", "CSI"),
                names("cbs\\.log", 15.0, "sfcdetails\\.txt", 12.0, ".*cbs.*", 8.0)));

        s.all.add(new Fingerprint(LogType.WIN_WER,
                Arrays.asList("fault bucket", "problem signature:"),
                Arrays.asList(RE_WER_LINE),
                Arrays.asList("EventType", "DynamicSig", "WER", "Application Name", "AppPath"),
                names(".*wer.*", 6.0, "ReportArchive.*", 4.0)));

        s.all.add(new Fingerprint(LogType.WIN_DXDIGA,
                Arrays.asList("directx diagnostic tool", "dxdiag"),
                Arrays.asList(RE_DXDIGA_LINE),
                Arrays.asList("Card name", "Display Memory", "Sound Devices", "DirectX Version",
                        "Whql", "Page File", "Windows System"),
                names("dxdiag.*", 15.0)));

        s.all.add(new Fingerprint(LogType.WIN_BSOD_TEXT,
                Arrays.asList("bug check code", "bugcheck code", "caused by driver",
                        "your computer crashed", "bug check string"),
                Arrays.asList(RE_BSOD_TEXT_LINE),
                Arrays.asList("Dump File", "Full Path", "Stop Code", "蓝屏", "WHEA", "minidump", "Probably caused by"),
                names(".*bsod.*", 10.0, ".*bluescreen.*", 10.0, "dump.*\\.txt", 6.0)));

        s.all.add(new Fingerprint(LogType.LINUX_SYSLOG,
                Arrays.asList("out of memory: killed process", "oom-killer", "invoked oom-killer"),
                Arrays.asList(RE_SYSLOG_LINE, RE_DMESG_LINE),
                Arrays.asList("systemd", "kernel:", "audit:", "sshd", "dbus", "NetworkManager",
                        "segfault", "Failed to start", "cron"),
                names("syslog.*", 8.0, "messages", 5.0, "kern\\.log", 6.0, "journal.*\\.txt", 4.0, "dmesg.*", 8.0)));

        s.all.add(new Fingerprint(LogType.LINUX_XORG,
                Arrays.asList("x.org x server", "xorg server"),
                Arrays.asList(RE_XORG_LINE),
                Arrays.asList("(EE)", "(WW)", "AIGLX error", "NVIDIA(0)", "AMDGPU", "modeset",
                        "glx", "screen 0", "Virtual screen size"),
                names("xorg.*\\.log", 12.0, "xorg\\.0.*", 12.0)));

        s.all.add(new Fingerprint(LogType.MACOS_CRASH,
                Arrays.asList("exception type: exc_", "crashed thread", "exception codes",
                        "application specific information:"),
                Arrays.asList(RE_MACOS_CRASH_LINE),
                Arrays.asList("macOS Version", "libsystem", "com.apple", "Abort trap", "Segmentation fault"),
                names(".*\\.crash", 12.0, ".*ips.*", 3.0)));

        s.all.add(new Fingerprint(LogType.ANDROID_TOMBSTONE,
                Arrays.asList("build fingerprint:", ">>> ", "abort message:"),
                Arrays.asList(RE_TOMBSTONE_LINE),
                Arrays.asList("backtrace:", "libc", "Fatal signal", "name: ", "tombstone", "cmdline"),
                names("tombstone.*", 15.0, ".*tombstone.*", 10.0)));

        s.all.add(new Fingerprint(LogType.BUILD_GRADLE,
                Arrays.asList("failure: build failed with an exception.", "* what went wrong:",
                        "what went wrong:", "execution failed for task"),
                Arrays.asList(RE_GRADLE_LINE),
                Arrays.asList("gradle", "> task", "build failed in", "assembleRelease", "compileDebugJavaWithJavac"),
                names(".*gradle.*\\.txt", 6.0, "build.*\\.log", 4.0)));

        return s;
    }
}
