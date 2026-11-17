package com.ember.logan;

/** 支持识别的日志类型。 */
public enum LogType {
    PCL2_LAUNCHER("PCL2 启动器日志", "Plain Craft Launcher 2（Windows）启动器自身日志，多见于 Log1~Log5.txt"),
    HMCL_LAUNCHER ("HMCL 启动器日志", "Hello Minecraft! Launcher 启动器自身日志（hmcl.log / 导出日志）"),
    FCL_LAUNCHER  ("FCL 启动器日志", "Fold Craft Launcher（安卓 MC Java 版启动器）导出的启动器日志"),
    MC_GAME       ("Minecraft 游戏日志", "游戏本体输出（latest.log / crash-report），log4j2 格式"),
    JVM_CRASH     ("JVM 崩溃日志", "Java 虚拟机异常退出记录（hs_err_pid*.log），通常伴随游戏/启动器闪退"),
    ANDROID_LOGCAT("安卓 logcat 手机日志", "Android 系统级日志，包含应用崩溃、ANR、系统事件与运行状态"),
    WIN_EVENT     ("Windows 事件日志", "事件查看器导出的系统/应用事件（Event ID 驱动）"),
    WIN_CBS       ("Windows 系统修复日志", "CBS.log / SFC 组件修复日志，记录系统文件损坏与修复情况"),
    WIN_WER       ("Windows 错误报告", "WER 故障报告（Fault bucket / Problem signature）"),
    WIN_DXDIGA    ("Windows 硬件诊断报告", "DXDiag DirectX 诊断工具输出，含显卡/声卡/驱动信息"),
    WIN_MINIDUMP  ("Windows 蓝屏转储文件", ".dmp 二进制 minidump，需 WinDbg 深度解析（本工具仅识别）"),
    WIN_BSOD_TEXT ("Windows 蓝屏分析文本", "BlueScreenView 等工具导出的 dump 分析记录，含停止码与肇事驱动"),
    LINUX_SYSLOG  ("Linux 系统日志", "syslog / journalctl / dmesg 风格的系统级日志"),
    LINUX_XORG    ("Linux Xorg 显示日志", "X.Org 服务器日志（Xorg.0.log），显卡/驱动问题常见出处"),
    MACOS_CRASH   ("macOS 崩溃报告", "macOS 系统生成的应用崩溃报告（EXC_* 异常）"),
    ANDROID_TOMBSTONE("安卓 tombstone 原生崩溃", "Android 原生层（C/C++）崩溃转储文本，含 backtrace 与 abort 信息"),
    BUILD_GRADLE  ("构建失败日志", "Gradle / 构建工具的失败输出（依赖解析、SDK 配置等）"),
    UNKNOWN       ("未识别内容", "不属于已知日志类型，可能是文本、配置或其他文件");

    public final String zh;
    public final String desc;

    LogType(String zh, String desc) { this.zh = zh; this.desc = desc; }
}
