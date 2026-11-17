package com.ember.logan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 诊断知识库：错误模式 → 中文解释 → 解决办法。
 * 两部分资产：
 *   1) Rule 规则表：按日志类型分组的正则诊断规则
 *   2) WIN_EVENTS：Windows 事件 ID 知识表（Event ID → 中文诊断）
 * 引擎核心资产，可持续扩充。
 */
public final class KnowledgeBase {

    /** 一条诊断规则。 */
    public static final class Rule {
        public final Pattern pattern;
        public final String title, explain, advice;
        public final int severity;
        public final String quickKey; // contains 快筛串（小写）
        Rule(String regex, int severity, String title, String explain, String advice, String quickKey) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.severity = severity;
            this.title = title;
            this.explain = explain;
            this.advice = advice;
            this.quickKey = quickKey;
        }
    }

    private static final List<Rule> GLOBAL = new ArrayList<>();
    private static final List<Rule> MC_RULES = new ArrayList<>();
    private static final List<Rule> LOGCAT_RULES = new ArrayList<>();
    private static final List<Rule> WIN_RULES = new ArrayList<>();
    private static final List<Rule> CBS_RULES = new ArrayList<>();
    private static final List<Rule> WER_RULES = new ArrayList<>();
    private static final List<Rule> GRADLE_RULES = new ArrayList<>();
    private static final List<Rule> LINUX_RULES = new ArrayList<>();
    private static final List<Rule> MACOS_RULES = new ArrayList<>();
    private static final List<Rule> TOMBSTONE_RULES = new ArrayList<>();
    private static final List<Rule> BSOD_RULES = new ArrayList<>();

    static {
        // ================= 全局（Java / 通用） =================
        GLOBAL.add(new Rule("UnsupportedClassVersionError|bad class file|class file has wrong version|Unsupported major\\.minor version",
                Finding.CRITICAL, "Java 版本不匹配",
                "游戏/库编译时用的 Java 版本与当前运行的不一致：版本过高会报 UnsupportedClassVersionError，过低会报 class file version。",
                "① 看报错里的 class file version：52=Java8、61=Java17、65=Java21；② 在启动器里切换对应 Java（1.20.1 需 Java 17+，1.20.5+ 需 Java 21）；③ 整合包按作者要求选 Java。",
                "unsupportedclassversionerror"));
        GLOBAL.add(new Rule("Could not reserve enough space|insufficient memory for the JRE",
                Finding.CRITICAL, "JVM 无法申请到足够内存",
                "系统给不出 JVM 要的内存（-Xmx 设得比物理内存还大，或 32 位 Java 限制了 ~1.5GB）。",
                "① 调低 -Xmx（32 位 Java 上限约 1536MB）；② 关闭大内存占用程序；③ 64 位系统一律用 64 位 Java。",
                "could not reserve enough space"));
        GLOBAL.add(new Rule("java\\.lang\\.OutOfMemoryError|OutOfMemoryError:",
                Finding.CRITICAL, "内存溢出（OOM）",
                "运行过程中内存耗尽，JVM 主动终止，常见于大整合包、超远视距或内存泄漏的模组。",
                "① 调大启动器内存分配（-Xmx）；② 减少视距/卸载大型模组验证；③ 若频繁出现，用 -XX:+HeapDumpOnOutOfMemoryError 抓堆转储定位泄漏。",
                "outofmemoryerror"));
        GLOBAL.add(new Rule("UnsatisfiedLinkError|Can't load library|Failed to load library|couldn't find DSO to load",
                Finding.CRITICAL, "本地库（.dll/.so）加载失败",
                "LWJGL 等原生库与系统架构（32/64 位）或 Java 版本不匹配，无法加载。",
                "① 确认 64 位系统 + 64 位 Java；② 1.19+ 需 Java 17+ 且 LWJGL 3.3+；③ 安卓端启动器（FCL 等）检查是否缺对应的 native 库目录。",
                "unsatisfiedlinkerror"));
        GLOBAL.add(new Rule("SocketException|SocketTimeoutException|UnknownHostException|Connection refused|timed out|connect timeout|Could not connect|Failed to connect|下载失败|下载出错|Failed to download|Download failed",
                Finding.WARN, "网络连接 / 资源下载失败",
                "到服务器的连接超时、被拒或中断：常见于网络不稳、DNS 污染、镜像源抽风、代理/防火墙拦截。",
                "① 先重试一次，偶发多为网络抖动；② 启动器里切换下载源/镜像（PCL·HMCL 均支持 BMCLAPI）；③ 换 DNS（223.5.5.5 / 119.29.29.29）或关掉代理再试。",
                "socket"));
        GLOBAL.add(new Rule("exited with (?:exit )?code\\s*-?\\d+|游戏进程已退出|Process exited with code",
                Finding.WARN, "游戏进程异常退出",
                "游戏进程非正常结束（Windows 下 -1073741819=0xC0000005 原生崩溃、1=常规错误、-1=未知错误）。",
                "① 往日志末尾找最后的 ERROR/Caused by 定位根因；② 0xC0000005 优先怀疑显卡驱动与内存条；③ 启动器内联崩溃报告一并查看。",
                "exit"));
        GLOBAL.add(new Rule("Invalid session|Invalid token|Failed to verify authentication|无效会话|登录失败|认证失败|验证失败|Failed to validate",
                Finding.CRITICAL, "账号登录 / 会话验证失败",
                "正版票据或第三方认证（外置登录）校验不通过，无法进入需验证的服务器。",
                "① 启动器里重新登录账号；② 系统时间不准会导致票据校验失败，先校时；③ 离线/外置登录确认服务端地址与账户信息正确。",
                "invalid session"));
        GLOBAL.add(new Rule("Access is denied|AccessDeniedException|拒绝访问",
                Finding.WARN, "文件访问被拒绝",
                "程序没有权限读写目标文件，多见于 OneDrive 同步目录、杀软拦截或只读属性。",
                "① 关掉文件占用（如资源管理器预览、OneDrive 占用）；② 以管理员运行启动器；③ 把杀软误报项加入白名单。",
                "access is denied"));
        GLOBAL.add(new Rule("BindException|Address already in use",
                Finding.WARN, "端口被占用",
                "启动器/服务器要监听的端口已被别的程序占着。",
                "① 重启电脑最省事；② netstat -ano | findstr <端口> 找到占用进程结束它；③ 换一个端口。",
                "address already in use"));
        GLOBAL.add(new Rule("SSLException|SSLHandshakeException|PKIX path building failed|远程证书无效|未能为 SSL",
                Finding.WARN, "HTTPS / SSL 证书校验失败",
                "TLS 握手失败：系统时间错误、网络劫持、Java 信任库过旧都会触发。",
                "① 校准系统时间；② 关闭抓包/代理软件；③ 更新 Java 版本（信任库随版本更新）。",
                "ssl"));
        GLOBAL.add(new Rule("GC overhead limit exceeded",
                Finding.WARN, "GC 开销超限（内存吃紧先兆）",
                "JVM 花了 98% 时间做垃圾回收却回收不到 2% 内存：比直接 OOM 温和，但意味着内存严重不足。",
                "① 适当调大 -Xmx；② 检查是否装了泄漏内存的模组；③ 观察是否伴随卡顿频率上升。",
                "gc overhead"));
        GLOBAL.add(new Rule("EXCEPTION_STACK_OVERFLOW|StackOverflowError",
                Finding.CRITICAL, "栈溢出（递归过深/死递归）",
                "线程栈被耗尽，99% 是代码里的无限递归（模组 bug 或配置循环引用）。",
                "① 看栈顶重复出现的包名，定位到具体模组；② 可用 -Xss 调大线程栈临时缓解，但根治要修代码。",
                "stackoverflow"));

        // ================= Minecraft / 启动器 =================
        MC_RULES.add(new Rule("Mixin apply failed|MixinTransformerError|MixinApplyError|Error injecting constructor",
                Finding.CRITICAL, "模组 Mixin 注入失败（模组冲突）",
                "两个模组修改同一段游戏代码，后注入的失败；或模组版本与 MC/加载器版本不匹配。",
                "① 看日志里 Mixin 报错归属的 mod id；② 二分法排查：先移除，再逐个加回；③ 到模组页确认支持的 MC 版本。",
                "mixin apply failed"));
        MC_RULES.add(new Rule("DuplicateModsFoundException|Duplicate mods found",
                Finding.CRITICAL, "重复安装了同一模组",
                "mods 文件夹里同一模组存在多份（常见于新旧版本混放）。",
                "① 删掉旧版本只留一份；② 一键整合包重解压时清空 mods 再放入。",
                "duplicate"));
        MC_RULES.add(new Rule("Missing or unsupported mandatory dependencies|Missing Mods:|Missing or invalid mandatory dependency|requires mod",
                Finding.CRITICAL, "缺少前置模组（依赖缺失）",
                "某模组声明必须依赖的前置（如 Fabric API / Kotlin for Forge）没装，或版本不符。",
                "① 按报错列出的 mod id 去下载对应前置；② 注意前置版本必须匹配 MC 版本。",
                "missing"));
        MC_RULES.add(new Rule("is missing mods\\.toml file|must have a mods\\.toml|not a valid mod file|Missing or invalid mod metadata",
                Finding.CRITICAL, "mods 文件夹混入了非模组文件",
                "Forge 在 mods 目录发现了没有 mods.toml 元数据的 jar（常是源码包/依赖包/误放的普通 zip）。",
                "① 把报错文件移出 mods 目录；② 只放带 mod 元数据的发布版 jar。",
                "mods.toml"));
        MC_RULES.add(new Rule("Failed to load mod|Failed to load mod file|Error loading mod",
                Finding.CRITICAL, "模组加载失败",
                "模组文件损坏、与加载器版本不匹配，或其依赖初始化出错。",
                "① 定位日志中报错的 mod 文件名；② 重新下载该模组；③ 核对模组说明的 MC/加载器版本。",
                "failed to load mod"));
        MC_RULES.add(new Rule("The game crashed whilst|crash report saved to|游戏崩溃",
                Finding.CRITICAL, "游戏崩溃（已生成崩溃报告）",
                "游戏因未处理异常终止，崩溃报告已写入 crash-reports 目录，里面通常直接写明原因。",
                "① 打开报错里给出的 crash-xxx.txt 看 Description 与 Stacktrace；② 把报告交给整合包作者/模组作者定位。",
                "the game crashed"));
        MC_RULES.add(new Rule("Failed to check session lock|session\\.lock",
                Finding.WARN, "存档被其他程序占用（session.lock）",
                "存档目录被另一个 Minecraft 实例/工具占用，或上次异常退出后锁未释放。",
                "① 关掉所有开这个存档的程序；② 重启后再进；③ 备份后删除存档目录里的 session.lock 文件。",
                "session.lock"));
        MC_RULES.add(new Rule("GLFW error|GLX error|Pixel format not accelerated|OpenGL.*not supported",
                Finding.CRITICAL, "显卡 / OpenGL 初始化失败",
                "显卡驱动过旧、远程桌面无 3D 加速、或驱动不支持所需 OpenGL 版本。",
                "① 更新显卡驱动到最新；② 笔记本确认用的是独立显卡而非核显；③ 安卓端换渲染器（gl4es/zink/virgl）重试。",
                "glfw error"));
        MC_RULES.add(new Rule("OpenAL init failed|无法初始化音频|Failed to initialize.*audio",
                Finding.WARN, "音频设备初始化失败",
                "声卡被占用或驱动异常，游戏一般会静音继续运行。",
                "① 关掉占用声卡的程序（语音软件独占模式）；② 重装/更新声卡驱动。",
                "openal init failed"));
        MC_RULES.add(new Rule("Could not find or load main class",
                Finding.CRITICAL, "游戏主类缺失",
                "JVM 找不到主类：游戏核心 jar 损坏、版本 json 指向错误，或 Java 版本过新把旧参数当主类。",
                "① 用启动器「完整性检查/验证游戏文件」重下核心；② 删除版本目录后重装该版本；③ 确认 Java 版本与游戏匹配。",
                "could not find or load main class"));
        MC_RULES.add(new Rule("Unsupported Java|Java .* is not supported|requires Java \\d+",
                Finding.CRITICAL, "Java 版本不受支持",
                "当前 Java 太老或太新，游戏/启动器明确拒绝运行。",
                "① 按报错提示安装对应大版本 Java；② 启动器设置里指向新装的 javaw.exe。",
                "unsupported java"));
        MC_RULES.add(new Rule("Application error, unable to launch|Failed to start Minecraft|启动失败|无法启动游戏",
                Finding.CRITICAL, "游戏启动失败",
                "启动器侧判定游戏未能正常拉起（常见于安卓启动器：渲染器、native 库或 Java 环境问题）。",
                "① 切换渲染器（gl4es/zink/virgl）重试；② 检查设备架构（arm64）对应的运行时已安装；③ 把完整日志发给社区。",
                "application error, unable to launch"));
        MC_RULES.add(new Rule("InaccessibleObjectException|Unable to make .* accessible|does not \\\"?opens\\\"?|IllegalAccessError",
                Finding.CRITICAL, "Java 17+ 模块封锁（老模组/老库不兼容新 Java）",
                "Java 9+ 的模块系统默认禁止反射访问 JDK 内部 API，老模组/老库在 Java 17+ 上直接崩（1.17 整合包 + 新 Java 的高频坑）。",
                "① 给 JVM 参数加 --add-opens（按报错里 module/package 补）；② 优先降级到模组要求的 Java 大版本；③ 更新模组到支持新 Java 的版本。",
                "inaccessibleobjectexception"));
        MC_RULES.add(new Rule("java\\.lang\\.module\\.ResolutionException|ModuleNotFoundError|finds two versions of module|reads package from both",
                Finding.CRITICAL, "Java 模块系统冲突（JPMS 重复模块）",
                "classpath 上出现同一个模块的两个版本互相冲突。",
                "① 看 module 名，删掉 mods 里重复/旧版 jar；② 别把依赖库手动塞进 mods 目录。",
                "resolutionexception"));
        MC_RULES.add(new Rule("Critical injection failure|could not find any targets|mixin target .* could not be found|InjectionError",
                Finding.CRITICAL, "Mixin 注入目标丢失（模组版本错配）",
                "模组想修改的方法在当前游戏版本里不存在，说明模组与 MC 版本/另一模组版本不匹配。",
                "① 确认模组标注支持的 MC 版本完全一致；② 混装多版本模组是常见诱因，逐个排查。",
                "critical injection failure"));
        MC_RULES.add(new Rule("Incompatible mod set!|incompatible mod set",
                Finding.CRITICAL, "模组集不兼容",
                "模组加载器判定当前 mods 组合互相不兼容（多为版本错配或与加载器冲突）。",
                "① 按报错列出的冲突模组逐个移除验证；② 重建整合包时只保留同一 MC 版本的模组。",
                "incompatible mod set"));
        MC_RULES.add(new Rule("Failed to load world|Exception loading level|Exception loading world|failed to load chunk|corrupted chunk",
                Finding.CRITICAL, "世界 / 存档加载失败（可能损坏）",
                "存档数据读取异常：chunk 损坏、region 文件 IO 错误，或模组改变了世界注册表。",
                "① 先备份整个 saves 目录再动手；② 移除后加的模组再试；③ 用 MCASelector/NBTExplorer 定位损坏区块并删除重建。",
                "failed to load world"));
        MC_RULES.add(new Rule("Exception ticking|Ticking block entity|Ticking entity|TickingMemoryCache",
                Finding.CRITICAL, "游戏主循环崩溃（Ticking 崩溃）",
                "游戏在 tick 某个实体/方块时抛异常直接崩溃，通常由特定模组的实体或方块状态引发。",
                "① 崩溃报告 Description 会写 Ticking entity，里面带实体坐标与 mod id；② 用工具/命令清掉该实体或传送到别处；③ 反馈给对应模组作者。",
                "ticking"));

        // ================= 安卓 logcat =================
        LOGCAT_RULES.add(new Rule("FATAL EXCEPTION",
                Finding.CRITICAL, "应用崩溃（未捕获异常）",
                "AndroidRuntime 捕获到未处理的异常，对应应用直接闪退。",
                "① 取 FATAL EXCEPTION 下方首行异常与 at 包名定位应用；② 栈里 android.* 开头多为系统交互问题，com.* 开头为应用自身；③ 把完整栈给开发者或看对应 issue。",
                "fatal exception"));
        LOGCAT_RULES.add(new Rule("ANR in |Input dispatching timed out",
                Finding.CRITICAL, "应用无响应（ANR）",
                "主线程被阻塞超 5 秒（输入事件）或前台服务超时，系统弹「应用无响应」。",
                "① 看 ANR in 下面的 CPU 占用行，定位卡死的进程；② 常见原因：主线程做 IO/死锁；③ 用户侧先重启应用，开发者需优化主线程。",
                "anr in "));
        LOGCAT_RULES.add(new Rule("lowmemorykiller|to free [0-9.]+MB",
                Finding.WARN, "系统内存不足（低内存查杀）",
                "整机内存吃紧，系统按优先级杀后台进程保前台，可能表现为应用被莫名退后台/重启。",
                "① 清理后台应用、重启手机；② 卸载常驻重应用；③ 关注是否单一应用泄漏内存（反复出现同进程）。",
                "lowmemorykiller"));
        LOGCAT_RULES.add(new Rule("Fatal signal \\d+|SIGSEGV|SIGABRT|SIGBUS",
                Finding.CRITICAL, "原生层崩溃（Native Crash）",
                "C/C++ 层（so 库）崩溃，logcat 会给出 signal 编号与 so 名，常与显卡驱动/渲染器相关。",
                "① 找 \"Fatal signal 11 (SIGSEGV)\" 后面的 backtrace 与 so 名；② 游戏类多为渲染器问题，切换渲染器；③ 带符号的 tombstone 文件在 /data/tombstones。",
                "fatal signal"));
        LOGCAT_RULES.add(new Rule("has died|Process .* died",
                Finding.WARN, "进程异常死亡",
                "系统记录某进程退出，可结合原因标签判断被杀原因（内存不足/崩溃/被冻结）。",
                "① 结合上下行日志判断是被杀还是自杀；② 反复出现时查看对应应用内存策略。",
                "has died"));
        LOGCAT_RULES.add(new Rule("Unable to instantiate application|Unable to start activity|Unable to create service|Unable to resume activity",
                Finding.CRITICAL, "应用组件启动失败",
                "应用的 Application/Activity/Service 初始化抛异常，通常一打开就闪退。",
                "① 看紧跟其后的 Caused by；② 清除应用数据或重装；③ 仍崩溃把日志反馈给开发者。",
                "unable to start activity"));
        LOGCAT_RULES.add(new Rule("BadTokenException|not attached to window manager|ViewRootImpl.*not valid",
                Finding.WARN, "窗口令牌失效（弹窗时页面已销毁）",
                "在 Activity 销毁后才去弹 Dialog/PopupWindow，系统拒绝挂载窗口，应用可能崩溃。",
                "① 用户侧重进应用即可；② 开发者需检查异步回调里弹窗前先判断 isFinishing()。",
                "badtokenexception"));
        LOGCAT_RULES.add(new Rule("Resources\\$NotFoundException",
                Finding.WARN, "资源缺失（打包不完整/引用错误）",
                "代码或布局引用了不存在的资源 id/主题，常见于分包出错、动态下发主题失败。",
                "① 清缓存重装；② 开发者检查 R 资源引用与主题继承关系。",
                "notfoundexception"));
        LOGCAT_RULES.add(new Rule("CleartextAttemptException|cleartext HTTP traffic.*not permitted",
                Finding.WARN, "明文 HTTP 被系统拦截（Android 9+）",
                "Android 9 起默认禁止明文 HTTP 流量，应用还在请求 http:// 地址就会报这个。",
                "① 用户侧：升级应用版本；② 开发者：改用 HTTPS，或在 networkSecurityConfig 里放行明文（不推荐）。",
                "cleartextattemptexception"));

        // ================= Windows 事件（文本导出格式用） =================
        WIN_RULES.add(new Rule("Event ID:\\s*1000|<EventID>1000</EventID>",
                Finding.CRITICAL, "应用程序错误（事件 1000）",
                "某应用进程崩溃，事件里有故障模块名（Faulting module）——这是定位元凶的字段。",
                "① 看「故障模块名称」：显卡驱动（nvoglv64/dxgi）→ 更新驱动；游戏 exe → 校验/重装；② 若固定某应用崩溃，去其官网 issue 检索模块名。",
                "event id:"));
        WIN_RULES.add(new Rule("Event ID:\\s*1002|<EventID>1002</EventID>",
                Finding.WARN, "应用程序挂起（事件 1002）",
                "应用窗口长时间无响应被系统记录（未崩溃，但卡死了）。",
                "① 复现时抓一次 CPU/线程 dump；② 检查是否等网络 IO 没做超时。",
                "event id:"));

        // ================= CBS / SFC =================
        CBS_RULES.add(new Rule("Cannot repair member file|无法修复成员文件",
                Finding.CRITICAL, "SFC 无法修复系统文件（组件存储损坏）",
                "系统文件校验发现损坏且 SFC 自身修不动，多见于强关机/磁盘异常后。",
                "① 管理员 CMD 依次执行：DISM /Online /Cleanup-Image /RestoreHealth，再 sfc /scannow；② 仍失败建议考虑系统还原/重置；③ 先排查硬盘健康（见事件日志 153/129）。",
                "cannot repair member file"));
        CBS_RULES.add(new Rule(", Error\\s+CBS|Error CBS",
                Finding.WARN, "CBS 修复流程报错",
                "组件维护流程出现错误码（hr=0x...），配合错误码可查具体含义。",
                "① 记录 hr= 错误码检索微软文档；② 常见 0x800f0831 = 组件存储缺清单，可用 DISM 挂载源修复。",
                "error cbs"));
        CBS_RULES.add(new Rule("do not match.*hash|Hashes for member.*do not match",
                Finding.WARN, "系统文件哈希校验不一致",
                "文件内容与微软清单不匹配：被篡改、磁盘损坏或第三方改动所致。",
                "① 单个文件且系统无异常可观察；② 多个文件出现建议 DISM 修复。",
                "do not match"));

        // ================= WER =================
        WER_RULES.add(new Rule("Problem signature:|Fault bucket",
                Finding.INFO, "Windows 错误报告（WER）",
                "一次应用/系统故障被记录为故障报告，P1~P8 签名里含应用名、版本、故障模块。",
                "① 用 P8（异常代码）与故障模块检索；② 蓝屏场景可用 BlueScreenView 读 dump 的停止码。",
                "problem signature"));

        // ================= Gradle / 构建 =================
        GRADLE_RULES.add(new Rule("FAILURE: Build failed with an exception.",
                Finding.WARN, "构建失败（Gradle）",
                "构建工具中止了编译/打包流程，具体原因看 '* What went wrong:' 一节。",
                "① 直接看 What went wrong 与 Exception is: 两节；② 按提示的 task 名定位失败环节。",
                "build failed with an exception"));
        GRADLE_RULES.add(new Rule("Could not resolve all (?:files|dependencies)|Could not find artifact|Could not GET",
                Finding.CRITICAL, "依赖解析失败（仓库不可达/版本不存在）",
                "依赖下载失败：网络不通、镜像没配、版本号写错或私仓没权限。",
                "① 看具体哪个 artifact 失败；② 配国内镜像（阿里云 maven）或检查代理；③ 核对依赖坐标版本是否真实存在。",
                "could not resolve"));
        GRADLE_RULES.add(new Rule("SDK location not found|ANDROID_HOME|ANDROID_SDK_ROOT",
                Finding.WARN, "Android SDK 路径未配置",
                "构建找不到 Android SDK（local.properties 缺失或环境变量没设）。",
                "① 项目根目录建 local.properties 写 sdk.dir；② 或设置 ANDROID_HOME 环境变量。",
                "sdk location not found"));
        GRADLE_RULES.add(new Rule("Unsupported class file major version|requires JDK \\d+|Gradle requires",
                Finding.WARN, "Gradle/JDK 版本不匹配",
                "Gradle 或 Android Gradle Plugin 版本与当前 JDK 不兼容（如老 Gradle 遇上 JDK 17+）。",
                "① 查 Gradle 官方兼容表选 Gradle 版本；② 或降低 JAVA_HOME 的 JDK 版本。",
                "unsupported class file major version"));

        // ================= Linux =================
        LINUX_RULES.add(new Rule("Out of memory(?:\\(.*?\\))?: (?:Killed|Kill) process|oom-killer|invoked oom-killer",
                Finding.CRITICAL, "Linux 内存不足查杀（OOM Killer）",
                "系统物理内存+交换空间耗尽，内核按评分强杀进程保命，被杀进程往往莫名其妙消失。",
                "① 看 Killed process 后面的进程名与评分；② 加 swap 或加内存；③ 限制大进程内存（systemd MemoryMax / cgroup）。",
                "out of memory: killed"));
        LINUX_RULES.add(new Rule("segfault at |general protection fault:|BUG: kernel NULL pointer",
                Finding.CRITICAL, "内核段错误（Kernel Oops/Segfault）",
                "程序或内核模块访问非法内存地址崩溃，多见于驱动 bug 与硬件异常。",
                "① 看出错模块（ip 附近的 [module] 名）；② 更新内核/驱动；③ 怀疑硬件时跑 memtest。",
                "segfault at "));
        LINUX_RULES.add(new Rule("Failed to start .*\\.service|Unit .* entered failed state|Dependency failed for",
                Finding.WARN, "systemd 服务启动失败",
                "某个系统服务拉不起来，可能连带依赖它的功能一起失效。",
                "① journalctl -u <服务名> -e 看详细日志；② systemctl status <服务名> 看退出码；③ 按服务名搜对应修复方法。",
                "failed to start"));

        // ================= tombstone / 原生崩溃 =================
        TOMBSTONE_RULES.add(new Rule("signal \\d+ \\((?:SIGSEGV|SIGABRT|SIGBUS|SIGILL)\\)",
                Finding.CRITICAL, "原生层崩溃（tombstone）",
                "C/C++ 代码崩溃被 debuggerd 捕获，文件含完整寄存器与 backtrace，是定位 native 问题的第一现场。",
                "① 看 Abort message 与 backtrace 的 #00~#03 帧；② 游戏类多为渲染器/驱动问题，切渲染器；③ 带符号表用 ndk-stack/addr2line 解析地址。",
                "sigsegv"));

        // ================= macOS =================
        MACOS_RULES.add(new Rule("Exception Type:\\s*EXC_BAD_ACCESS|Exception Type:\\s*EXC_CRASH",
                Finding.CRITICAL, "macOS 应用崩溃（EXC_BAD_ACCESS/EXC_CRASH）",
                "macOS 原生内存访问崩溃（对应 SIGSEGV/SIGABRT），报告里有 Crashed 线程栈。",
                "① 看 Crashed Thread 栈顶的镜像名；② 游戏类优先怀疑显卡驱动/Java 版本；③ 用符号化工具解析地址。",
                "exc_bad_access"));

        // ================= 蓝屏文本 =================
        BSOD_RULES.add(new Rule("Bug Check Code\\s*:\\s*0x|Bugcheck Code\\s*:\\s*0x|bugcheck\\s+0x",
                Finding.CRITICAL, "系统蓝屏（停止码记录）",
                "系统因内核级致命错误而崩溃重启，停止码 + 参数 + 肇事驱动可定位根因。",
                "① 看 Caused By Driver 字段：显卡（nvlddmkm/amdkmdag）→ 重装驱动；② 用 WinDbg 或 BlueScreenView 深挖 dump；③ 频繁蓝屏跑内存与硬盘检测。",
                "bug check code"));
    }

    /** 按日志类型取适用的规则集。 */
    public static List<Rule> rulesFor(LogType t) {
        List<Rule> all = new ArrayList<>(GLOBAL);
        switch (t) {
            case MC_GAME: case JVM_CRASH: case PCL2_LAUNCHER: case HMCL_LAUNCHER:
                all.addAll(MC_RULES); break;
            case FCL_LAUNCHER:
                all.addAll(MC_RULES); all.addAll(LOGCAT_RULES); break;
            case ANDROID_LOGCAT:
                all.addAll(LOGCAT_RULES); break;
            case ANDROID_TOMBSTONE:
                all.addAll(TOMBSTONE_RULES); break;
            case WIN_EVENT:
                all.addAll(WIN_RULES); break;
            case WIN_CBS:
                all.addAll(CBS_RULES); break;
            case WIN_WER:
                all.addAll(WER_RULES); break;
            case WIN_BSOD_TEXT:
                all.addAll(BSOD_RULES); break;
            case LINUX_SYSLOG: case LINUX_XORG:
                all.addAll(LINUX_RULES); break;
            case MACOS_CRASH:
                all.addAll(MACOS_RULES); break;
            case BUILD_GRADLE:
                all.addAll(GRADLE_RULES); break;
            default: break;
        }
        return all;
    }

    // ================================================================
    // Windows 事件 ID 知识库
    // ================================================================

    /** Windows 事件知识条目。 */
    public static final class EventKnowledge {
        public final int severity;
        public final String title, explain, advice;
        EventKnowledge(int severity, String title, String explain, String advice) {
            this.severity = severity; this.title = title; this.explain = explain; this.advice = advice;
        }
    }

    private static final Map<Integer, EventKnowledge> WIN_EVENTS = new LinkedHashMap<>();

    public static EventKnowledge eventKnowledge(int id) {
        if (WIN_EVENTS.isEmpty()) initEvents();
        return WIN_EVENTS.get(id);
    }

    private static synchronized void initEvents() {
        if (!WIN_EVENTS.isEmpty()) return;
        WIN_EVENTS.put(41,   new EventKnowledge(Finding.CRITICAL, "系统未正常关机即重启（Kernel-Power 41）",
                "系统没走正常关机流程就断了电：死机后长按电源、直接断电、电池拔出等。",
                "① 若伴随死机：查温度（CPU/GPU 过热）与内存稳定性；② 看是否总有某程序运行时发生；③ 台式机查电源供电质量。"));
        WIN_EVENTS.put(6008, new EventKnowledge(Finding.CRITICAL, "意外关机（6008）",
                "上一次系统关机是意外的（与 41 互为印证）。",
                "① 与 41 联合判断：偶发一次可忽略；② 频繁出现按死机/断电排查流程走。"));
        WIN_EVENTS.put(1000, new EventKnowledge(Finding.CRITICAL, "应用程序错误（1000，Application Error）",
                "某应用进程崩溃，事件里有故障模块名（Faulting module）——这是定位元凶的字段。",
                "① 看「故障模块名称」：显卡驱动（nvoglv64/dxgi）→ 更新驱动；② 游戏 exe → 校验/重装；③ 固定某应用崩溃，去其官网 issue 检索模块名。"));
        WIN_EVENTS.put(1002, new EventKnowledge(Finding.WARN, "应用程序挂起（1002，Application Hang）",
                "应用窗口长时间不响应被系统记录（卡死未崩）。",
                "① 复现时抓 CPU/线程 dump；② 检查是否在等无超时的网络 IO。"));
        WIN_EVENTS.put(7031, new EventKnowledge(Finding.WARN, "服务意外终止（7031）",
                "某 Windows 服务在运行中突然退出（实例 N）。",
                "① 看服务名：第三方服务 → 更新/重装对应软件；② 系统服务 → 联想最近装过什么/系统更新。"));
        WIN_EVENTS.put(7034, new EventKnowledge(Finding.WARN, "服务意外终止（7034）",
                "某服务意外终止且终止了 N 次。",
                "① 同 7031；② 服务管理器里看该服务的恢复策略。"));
        WIN_EVENTS.put(7000, new EventKnowledge(Finding.WARN, "服务启动失败（7000）",
                "某服务启动失败（错误 1053=超时、1069=登录失败、2=文件找不到）。",
                "① 记录服务名与错误码；② 文件找不到多为软件卸载残留，可清理注册表对应项。"));
        WIN_EVENTS.put(7001, new EventKnowledge(Finding.WARN, "服务依赖失败（7001）",
                "某服务依赖的服务未能启动，连带失败。",
                "① 先修 7000 里失败的底层服务，依赖问题往往随之消失。"));
        WIN_EVENTS.put(10016, new EventKnowledge(Finding.INFO, "DCOM 权限提示（10016）",
                "系统常态噪音：某 COM 组件的本地激活权限提示，几乎每台 Windows 都有，无实际危害。",
                "① 可直接忽略；② 强迫症想消掉需按微软文档改 Component Services 权限（不建议）。"));
        WIN_EVENTS.put(129,  new EventKnowledge(Finding.CRITICAL, "存储控制器命令重置（129，storahci/stornvme）",
                "磁盘命令超时被重置：硬盘响应异常，是硬盘故障/数据线接触不良的典型前兆。",
                "① 立即备份重要数据！② 更新主板存储驱动/固件；③ 观察 SMART 与 153 事件是否伴发；④ 有条件换盘换线测试。"));
        WIN_EVENTS.put(153,  new EventKnowledge(Finding.CRITICAL, "磁盘 IO 错误（153，disk）",
                "磁盘操作出现未恢复的坏块/超时，硬盘存在物理损坏风险。",
                "① 立即备份重要数据！② chkdsk /f 检查文件系统（注意：不能修物理坏道）；③ 观察 SMART（05/C5/C6 项）；④ 准备换盘。"));
        WIN_EVENTS.put(55,   new EventKnowledge(Finding.CRITICAL, "NTFS 文件系统损坏（55，Ntfs）",
                "文件系统结构出现损坏（卷位图/元文件异常）。",
                "① 备份数据后 chkdsk /f；② 若伴随 153/129，优先怀疑硬盘本身。"));
        WIN_EVENTS.put(51,   new EventKnowledge(Finding.WARN, "磁盘分页操作错误（51）",
                "单次分页读写失败，可能由坏道、杀毒软件扫描冲突或休眠唤醒引起。",
                "① 偶发可忽略；② 频繁出现按 153 处理流程走。"));
        WIN_EVENTS.put(4101, new EventKnowledge(Finding.CRITICAL, "显示驱动停止响应并已恢复（4101，Display）",
                "显卡驱动超时被系统重置（TDR）：表现为黑屏几秒后恢复、提示「显示驱动程序已停止响应」。",
                "① 优先彻底重装显卡驱动（DDU 卸载后装最新）；② 降低游戏内画质/超频回默认；③ 笔记本切换驱动版本测试。"));
        WIN_EVENTS.put(219,  new EventKnowledge(Finding.WARN, "驱动加载失败（219，Kernel-PnP）",
                "某设备驱动安装/加载失败，设备可能无法使用。",
                "① 记录设备名；② 设备管理器里查看黄色叹号设备并更新驱动；③ 忽略对系统无影响的虚拟设备项。"));
        WIN_EVENTS.put(364,  new EventKnowledge(Finding.WARN, "Windows 更新下载失败（364）",
                "更新包下载中断/校验失败。",
                "① 重试更新；② 清空 C:\\Windows\\SoftwareDistribution\\Download 后重启更新服务；③ 换 DNS。"));
        WIN_EVENTS.put(4227, new EventKnowledge(Finding.WARN, "TCP 端口耗尽（4227，Tcpip）",
                "出站端口用尽，表现为网络异常、连不上服务器。",
                "① 重启即恢复；② 查是否有程序疯狂发起连接（病毒/爬虫/BT）；③ 修正 TimeWait 过多问题。"));
        WIN_EVENTS.put(1001, new EventKnowledge(Finding.CRITICAL, "系统蓝屏记录（1001，BugCheck）",
                "内核检测到致命错误而崩溃（蓝屏），事件里记录了停止码与 dump 转储路径。",
                "① 记下事件里的 bugcheck 参数第一个即停止码，用 BlueScreenView/WinDbg 读 dump；② 显卡驱动（nvlddmkm）与内存条是两大元凶；③ 频繁蓝屏跑 memtest86 + 硬盘健康检测。"));
        WIN_EVENTS.put(1014, new EventKnowledge(Finding.WARN, "DNS 名称解析超时（1014，DNS Client Events）",
                "域名解析在超时时间内没得到响应：DNS 服务器慢、被墙或本地网络抖动。",
                "① 换公共 DNS（223.5.5.5 / 119.29.29.29）；② 偶发可忽略；③ 集中出现伴随游戏掉线，优先查网络链路。"));
        WIN_EVENTS.put(4625, new EventKnowledge(Finding.WARN, "账户登录失败（4625，Security）",
                "一次失败的登录尝试：密码错误或有人在尝试爆破（尤其暴露公网/远程桌面时）。",
                "① 看登录类型与源地址：3389 远程桌面被爆破很常见；② 关闭不用的远程访问、改强密码；③ 挂 VPN 或防火墙白名单。"));
        WIN_EVENTS.put(36888, new EventKnowledge(Finding.WARN, "TLS 致命错误（36888，Schannel）",
                "SSL/TLS 握手或传输中发生致命错误，常见于老协议被对端拒绝、证书过期。",
                "① 单独出现可忽略；② 伴随应用网络失败时，检查证书与系统时间；③ 老客户端连新服务器需启用 TLS1.2。"));
        WIN_EVENTS.put(6005, new EventKnowledge(Finding.INFO, "事件日志服务启动（6005，正常开机记录）",
                "系统正常开机的时间线记录，用于对照故障发生时间。",
                "① 无需处理，用于排查时对照时间线。"));
        WIN_EVENTS.put(6006, new EventKnowledge(Finding.INFO, "事件日志服务正常关闭（6006，正常关机记录）",
                "系统正常关机的时间线记录。",
                "① 无需处理；② 若关机时间点附近有 41/6008，说明该次关机其实不正常。"));
        WIN_EVENTS.put(10005, new EventKnowledge(Finding.WARN, "DCOM 启动失败（10005）",
                "某 COM 组件启动被拒或超时，多为权限或依赖服务未启动。",
                "① 看具体 CLSID 对应的组件；② 伴随某应用功能失效时优先修该应用；③ 孤立出现可忽略。"));
        WIN_EVENTS.put(5719, new EventKnowledge(Finding.WARN, "域控制器不可用（5719，Netlogon）",
                "加入域的电脑找不到域控：网络不通、域控下线或本机时间偏差过大。",
                "① 家庭/个人电脑出现多半是残留域配置，可退域；② 企业环境检查域控连通与时间同步；③ 临时影响：无法用域账号登录新会话。"));

        // ---- 蓝屏停止码知识表 ----
        BSOD_CODES.put("0x116", new EventKnowledge(Finding.CRITICAL, "停止码 0x116 VIDEO_TDR_FAILURE",
                "显卡驱动在规定时间内没有响应被系统复位失败，显卡驱动/显卡硬件问题的头号蓝屏。",
                "① 全新安装显卡驱动（DDU 彻底清理后装）；② 超频回默认；③ 换驱动旧版本测试。"));
        BSOD_CODES.put("0x117", new EventKnowledge(Finding.CRITICAL, "停止码 0x117 VIDEO_TDR_TIMEOUT_DETECTED",
                "显卡驱动恢复超时，与 0x116 同族。",
                "① 同 0x116 处理；② 检查显卡供电与温度。"));
        BSOD_CODES.put("0x124", new EventKnowledge(Finding.CRITICAL, "停止码 0x124 WHEA_UNCORRECTABLE_ERROR",
                "硬件层不可纠正错误：CPU/内存/主板/供电任一环节的硬件级故障。",
                "① 检查 CPU 温度与电压是否超频；② 跑 memtest86 检内存；③ 更新主板 BIOS。"));
        BSOD_CODES.put("0x1A", new EventKnowledge(Finding.CRITICAL, "停止码 0x1A MEMORY_MANAGEMENT",
                "内存管理器遇到致命错误，多为内存条故障或驱动损坏内核内存。",
                "① 跑 memtest86 检内存条；② 最近装过的驱动回滚；③ XMP 超频关闭测试。"));
        BSOD_CODES.put("0x50", new EventKnowledge(Finding.CRITICAL, "停止码 0x50 PAGE_FAULT_IN_NONPAGED_AREA",
                "访问了不该访问的内存页，驱动 bug 或内存故障。",
                "① 回滚最近装的驱动/软件；② memtest86 检内存；③ 看参数里是否有具体模块名。"));
        BSOD_CODES.put("0x7E", new EventKnowledge(Finding.CRITICAL, "停止码 0x7E SYSTEM_THREAD_EXCEPTION_NOT_HANDLED",
                "系统线程抛出未处理异常，几乎都是驱动问题。",
                "① 看蓝屏界面/事件里指出的 sys 文件名，重装对应硬件驱动；② 安全模式排查最近安装。"));
        BSOD_CODES.put("0xA",  new EventKnowledge(Finding.CRITICAL, "停止码 0xA IRQL_NOT_LESS_OR_EQUAL",
                "驱动在高中断级别访问了非法内存，经典驱动质量蓝屏。",
                "① 记录参数 4（出错地址对应模块）定位驱动；② 更新或回滚网卡/显卡/存储驱动。"));
        BSOD_CODES.put("0xD1", new EventKnowledge(Finding.CRITICAL, "停止码 0xD1 DRIVER_IRQL_NOT_LESS_OR_EQUAL",
                "驱动在不允许的中断级别访问内存，元凶通常直接写在参数里。",
                "① 参数里提到的 .sys 驱动名去搜索对应硬件；② 更新该设备驱动；③ 网卡/无线驱动最常见。"));
        BSOD_CODES.put("0x139", new EventKnowledge(Finding.CRITICAL, "停止码 0x139 KERNEL_SECURITY_CHECK_FAILURE",
                "内核检测到数据结构被破坏（栈缓冲区溢出等），驱动或系统文件问题。",
                "① sfc /scannow + DISM 修复系统文件；② 回滚最近驱动；③ 反复出现检内存。"));
        BSOD_CODES.put("0xEF", new EventKnowledge(Finding.CRITICAL, "停止码 0xEF CRITICAL_PROCESS_DIED",
                "关键系统进程（csrss/wininit 等）意外死亡，系统无法继续运行。",
                "① 看事件日志 1000 记录的死亡进程与故障模块；② 系统文件修复；③ 严重时考虑重置系统。"));
    }

    /** 蓝屏停止码 → 中文解释（容忍前导零与大小写：0x00000116 → 0x116）。 */
    private static final Map<String, EventKnowledge> BSOD_CODES = new LinkedHashMap<>();
    public static EventKnowledge bsodStopCode(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase(java.util.Locale.ROOT).replace("0x", "");
        try {
            long v = Long.parseLong(c, 16);
            EventKnowledge k = BSOD_CODES.get("0x" + Long.toHexString(v));
            if (k != null) return k;
        } catch (NumberFormatException ignore) { }
        return BSOD_CODES.get("0x" + c);
    }
}
