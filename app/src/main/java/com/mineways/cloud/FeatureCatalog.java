package com.mineways.cloud;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 云端功能目录配置（GitHub {@code config/} 里的文本文件）的解析内核。
 *
 * <p>刻意不依赖任何安卓类：这样它能用 javac + main 直接在开发机上跑断言，
 * 兜底 / 查重 / 错误解析 / 灰度 / 篡改校验 / 选择器展开这些路径都有真测试。
 *
 * <p>一行一条指令，{@code #} 整行注释，{@code ;} 行内注释。字段之间用空格分隔，
 * 值里带空格<b>不需要</b>加引号 —— 不认识的裸词会被接回上一个值（这是 3.0.0 那份配置
 * {@code id=MC 壁纸站} 匹配不到的根因，见 {@link #parseFields}）。
 *
 * <pre>
 * 头部（一份文件一次）：
 * &#64;file-version=3.1.0          本文件的版本号（与文件名一致，写进状态面板）
 * &#64;ver=1                     协议版本；比 App 认识的还大就整份拒绝
 * &#64;scope=inherit|replace      与上一版共同生效，还是只吃这一版（默认 inherit）
 * &#64;strict=0                  1=有坏行就整份拒绝；0=坏行跳过并上报
 * &#64;rollout=50                本文件的灰度百分比
 * &#64;minv=522 &#64;maxv=0        本文件参与的 versionCode 区间，0=不限
 * &#64;default=apply             本文件指令的默认生效方式 ask|download|apply
 * &#64;sig=sha256:hex            本体摘要
 * &#64;hmac=base64:...           作者密钥签名；缺失或不匹配即判篡改
 *
 * 指令：
 * SITE add id=xxx name=名字 cat=类别 url=https://... desc=介绍 order=10 mode=ask
 * SITE drop id=xxx / SITE drop sel=cat:w_sinc
 * CAT  add id=类别 name=中文名 order=20 / CAT drop id=类别
 * FEAT tier  sel=… to=free|normal|limited|whitelist
 * FEAT gate= to=free|login|login:档|wl|wl:[:档]   登录门禁；档=any|forum|google|ms，可 a+b 并集
 * FEAT hide|show|disable|enable sel=… | id=…
 * WL user add email=a@b.com / WL user drop email=a@b.com
 * WL add email=a@b.com list=forum|google|ms   分档名单（不写 list= 落进共享名单）
 *
 * 公共字段：sel=选择器  id=单个键  if="组合条件"  notice=给用户看的理由
 *           rollout=灰度  minv/maxv=版本门  mode=生效方式  override=builtin（改内置项必填）
 * </pre>
 *
 * <p>选择器（<b>全部 / 全选 / 反选 / 不选</b>）—— {@code sel=} 的值，多个因子用 {@code +} 并集，
 * 前缀 {@code !}（或 {@code not:}）表示对全体取反：
 * <pre>
 * all                     全部功能
 * none                    不选（用于撤销上一版：FEAT show sel=all）
 * cat:w_sinc              某个类别下的全部
 * group:local|web|cloud   本机工具 / 内置网站 / 云端新增网站
 * kind:free|limit|paid    按<b>当前</b>档位选（同一份文件里先改档再反选是有效的）
 * tag:limit               按内置标签串选（"free local" 这些）
 * id:文件修复             单个键
 * 文件修复,P3D 工程工具     逗号列表 = 全选这几项
 * !kind:limit             反选：除限制功能之外的全部
 * </pre>
 */
public final class FeatureCatalog {

	/** 协议版本没有升高：新语法对旧 App 是「忽略该行」而不是「整份拒绝」，测试机上的老包还能继续收配置。 */
	public static final int PROTOCOL_VERSION = 1;

	/** 档位名。 */
	public static final String TIER_WHITELIST = "whitelist";
	public static final String TIER_LIMITED = "limited";
	public static final String TIER_NORMAL = "normal";
	public static final String TIER_FREE = "free";
	private static final Set<String> TIERS = new HashSet<>(Arrays.asList(
			TIER_WHITELIST, TIER_LIMITED, TIER_NORMAL, TIER_FREE));

	/** 生效范围：与上一版共同生效，还是只有这一版。 */
	public static final String SCOPE_INHERIT = "inherit";
	public static final String SCOPE_REPLACE = "replace";

	/** 生效方式。 */
	public static final String MODE_ASK = "ask";
	public static final String MODE_DOWNLOAD = "download";
	public static final String MODE_APPLY = "apply";
	private static final Set<String> MODES = new HashSet<>(Arrays.asList(
			MODE_ASK, MODE_DOWNLOAD, MODE_APPLY));

	/** 入口状态（{@link Result#visOf} 的取值）。 */
	public static final String VIS_HIDDEN = "hidden";
	public static final String VIS_DISABLED = "disabled";

	/** 全局翻译词条挂在 {@link #TR_GLOBAL} 下，站点的盖过它。 */
	public static final String TR_GLOBAL = "*";

	/**
	 * 一个站点最多带多少条译文。整站逐句翻译（含长句、FAQ、素材描述）要 3000~20000 条，
	 * 所以这里按"整站翻干净"来放宽；真喂满了也只影响这一个站点。
	 */
	public static final int TR_PER_HOST_MAX = 30000;
	/** 整条链最多多少条译文（约 10 万条 ≈ 十几 MB 字符串，只在当前站点取子集下发）。 */
	public static final int TR_TOTAL_MAX = 100000;

	/**
	 * 一条译文词条的原文/译文长度上限。整页逐句翻译要把页面里的**整段正文**（介绍段、FAQ 答句、
	 * 条款段落）也收进词表，那些节点常有几百个字符，160 的上限会把最需要翻的长句全部挡掉，
	 * 所以放宽到 600（比页面里最长的文本节点还宽）。
	 */
	public static final int TR_TEXT_MAX = 600;

	/**
	 * {@code i18n=auto} 时自动开汉化所需的本站专属词条下限。设这个下限是因为只翻了三五个按钮的
	 * 页面会中英夹杂，比整页英文更难看；下限之上还要配上 {@code host=*} 的通用词表才够铺满页面，
	 * 所以哪些站算"铺满"由离线覆盖率体检（deepcov.py 拿真实页面节点回查词表）决定，
	 * 达不到就写 {@code i18n=off} 显式关掉，靠这里兜住刚起步的站。
	 */
	public static final int I18N_AUTO_MIN_ENTRIES = 50;

	/** 汉化的三种默认值：自动嗅探 / 一直开 / 不开。 */
	public static final String I18N_AUTO = "auto";
	public static final String I18N_ON = "on";
	public static final String I18N_OFF = "off";
	private static final Set<String> I18N_MODES = new HashSet<>(Arrays.asList(
			I18N_AUTO, I18N_ON, I18N_OFF));

	/** 认得的裸词（不带 = 的单词）。不在这里、也不在 {@link #FIELDS} 里的裸词，一律接回上一个值。 */
	private static final Set<String> BARE_WORDS = new HashSet<>(Arrays.asList(
			"add", "drop", "clear", "tier", "hide", "show", "disable", "enable", "user", "set", "pin",
			"unpin", "builtin", "all", "none", "yes", "no", "on", "off", "true", "false",
			"reset"));

	/** 认得的字段名。写了不认识的 {@code xxx=yyy} 会当成错误上报，而不是静悄悄丢掉。 */
	private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
			"id", "key", "name", "名", "cat", "category", "类别", "url", "link", "desc", "介绍",
			"icon", "order", "mode", "rollout", "minv", "maxv", "if", "when", "to", "override",
			"notice", "提示", "email", "sel", "where", "group", "kind", "tag", "activity",
			"label", "标题", "value", "val", "host", "site", "en", "zh", "i18n",
			"variant", "sha256", "sha", "from", "开始", "until", "结束", "schedule", "时间", "preset", "radius",
			"video", "视频", "code", "代码", "gate", "list", "档",
			"file-version", "ver", "strict", "scope", "default", "sig", "hmac"));

	// ============================================================ 数据

	public static final class Site {
		public String id = "";
		public String name = "";
		public String category = "";
		public String url = "";
		public String desc = "";
		public String icon = "";
		public int order = 100;
		public String mode = MODE_APPLY;
		public int line;
		public int file;

		Site(String id) {
			this.id = id;
		}
	}

	public static final class Category {
		public String id = "";
		public String name = "";
		public int order = 100;
		public int line;
		public int file;
	}

	/**
	 * 一条"没执行"的记录。分两种性质，混在一起就没法当发布关卡用：
	 * {@code error=false} 是<b>本来就不该执行</b>（灰度没命中、条件不成立、版本不在区间），
	 * {@code error=true} 是<b>写错了</b>（字段不认识、找不到这个功能、少写 override）。
	 */
	/**
	 * 展开阶段（目标解析、选择器、override 校验）产生的问题。重跑 resolve() 前会先清掉这一类，
	 * 所以"清单还没登记时先解析一遍、登记完再解析一遍"不会把同一份错误堆成两遍。
	 */
	static Issue exp(int file, int line, String reason, String raw) {
		return new Issue(file, line, reason, raw, true, true);
	}

	public static final class Issue {
		public final int file;
		public final int line;
		public final String reason;
		public final String raw;
		public final boolean error;
		/** 目标解析／选择器展开阶段产生的问题。重跑 resolve() 前要把这一类清掉，否则同一份配置
		 *  在"清单还没登记好"时先跑一遍，之后每次都会多叠一层假的「找不到功能」。 */
		public final boolean expansion;

		Issue(int file, int line, String reason, String raw) {
			this(file, line, reason, raw, true, false);
		}

		Issue(int file, int line, String reason, String raw, boolean error) {
			this(file, line, reason, raw, error, false);
		}

		Issue(int file, int line, String reason, String raw, boolean error, boolean expansion) {
			this.file = file;
			this.line = line;
			this.reason = reason;
			this.raw = raw;
			this.error = error;
			this.expansion = expansion;
		}

		@Override
		public String toString() {
			return "F" + (file + 1) + "L" + line + " " + reason;
		}
	}

	/**
	 * 一条指令（尚未展开目标）。{@link Result#resolve} 按加入顺序重放它们，后面的版本覆盖前面的。
	 *
	 * <p>FEAT 支持<b>一行连写多个动作</b>：{@code FEAT id=文件修复 tier=free hide order=1}。
	 * 应用次序固定为「改文案 → 改档 → 放行 → 停用 → 隐藏」，所以同一行里 {@code hide} 一定赢，
	 * {@code disable} 一定赢过 {@code enable}。SITE/CAT/WL/ENTRY 仍然是「一行一个动作」。
	 */
	public static final class Rule {
		public String verb = "";       // feat | site | cat | wl | entry | flag
		public String action = "";     // add | drop | tier | hide | show | disable | enable | user
		public String id = "";         // 显式单个键
		public String sel = "";        // 选择器表达式
		public String to = "";         // tier 目标档位
		public String name = "";
		public String category = "";
		public String url = "";
		public String desc = "";
		public String icon = "";
		public int order = 100;
		public String mode = MODE_APPLY;
		public String notice = "";
		public boolean overrideBuiltin;

		// —— FEAT 的连写动作（action 字段仍填第一个，好让旧代码读到合理值）——
		public String tierTo;          // tier=free
		/**
		 * {@code gate=wl:forum} 这类「登录门禁」原文（{@link LoginPolicy} 负责解释）。
		 * 与 {@code tier=} 是两件事：tier 说「这一项属于哪一档」，gate 说「要哪种登录、查不查名单」。
		 */
		public String gate;
		/** {@code WL … list=} 的名单档（已归一成 any|forum|google|ms），空 = 共享名单。 */
		public String wlList = LoginPolicy.ANY;
		public boolean hide, show, disable, enable, pin, unpin;
		public String label;           // label=新标题
		public String textDesc;        // desc=新说明
		public String textIcon;        // icon=新图标键
		public String textUrl;         // url=新链接（内置站点换域名不用发版）
		public Integer orderTo;        // order=3
		/** {@code order=none} / {@code auto}：把云端排的序撤掉，回到登记表里的原始位置。 */
		public boolean orderReset;
		public String flagKey;         // FLAG 的键
		public String flagValue;       // FLAG 的值
		public String iconVariant;     // ICON set variant=xxx（预置图标变体键）
		public boolean iconReset;      // ICON reset：回到出厂图标
		public String bgUrl;           // BG set url=https://…（背景图下载地址）
		public String bgSha;           // BG set sha256=…（必填：镜像防投毒）
		public boolean bgReset;        // BG reset：回到包内背景
		public String videoId;         // VIDEO 的键
		public String videoName;       // VIDEO 的展示名
		public String videoPlayer;     // VIDEO 归一后的播放页地址
		public String videoDrop;       // VIDEO drop 的键
		public boolean videoClear;     // VIDEO clear：清空展示栏
		public String activity;        // ENTRY 指向的 Activity 简单类名
		public String host;            // TR 作用的站点主机名（* = 全站通用）
		public String en;              // TR 的原文
		public String zh;              // TR 的译文
		public String i18n;            // on | off | auto：这个站点默认开不开汉化
		public int file;
		public int line;
		public String raw = "";

		public boolean hasFeatAction() {
			return tierTo != null || gate != null || hide || show || disable || enable || pin || unpin
					|| label != null || textDesc != null || textIcon != null
					|| textUrl != null || orderTo != null || orderReset
					|| (i18n != null && i18n.length() > 0);
		}
	}

	/** {@code ENTRY add} 新增出来的本地入口（指向包里已有的 Activity，永远不下发代码）。 */
	public static final class Entry {
		public String id = "";
		public String name = "";
		public String desc = "";
		public String icon = "";
		public String activity = "";
		public String category = "local";
		public String tier = TIER_FREE;
		/** 云端给这个新入口配的登录门禁（{@code gate=}，解释见 {@link LoginPolicy}）。 */
		public String gate = "";
		public int order = 100;
		public int file;
		public int line;

		Entry(String id) {
			this.id = id;
		}
	}

	/**
	 * 本机功能清单。解析内核不认识 App 里有哪些功能，要展开 {@code sel=} 或者校验
	 * {@code override=builtin} 就得问它。键一律用 {@link #norm} 规范化后比较。
	 */
	public interface KeySpace {
		/** 全部可选键（内置功能 + 云端已新增的站点），已规范化。 */
		List<String> keys();

		/** 该键的分类（内置网站是 w_xxx，本地工具是 local），没有返回空串。 */
		String categoryOf(String key);

		/** local | web | cloud。 */
		String groupOf(String key);

		/** 出厂档位名：free | limited | whitelist。 */
		String tierOf(String key);

		/** 内置标签串，如 "limit local"；没有返回空串。 */
		String tagsOf(String key);

		/** 是否内置项（改它必须写 override=builtin）。 */
		boolean isBuiltin(String key);

		/** 别名（图标键、标题、去空格标题、域名……），用来容忍作者少写/多写几个字。 */
		List<String> aliasesOf(String key);

		/** 给人看的名字（卡片标题），报错时要用它，不能把规范化过的小写键甩给用户。 */
		String titleOf(String key);

		/**
		 * 这个键对应的网址（内置网站与云端站点都有，本机功能是空串）。
		 * {@code TR} 用 {@code site=} 指站点时靠它拿到主机名，配置里就不必手抄域名。
		 */
		String urlOf(String key);

		/** 全部类别 id，供 {@code CAT drop} 与 {@code sel=cat:} 使用。 */
		List<String> categories();
	}

	/** 组合条件的求值接口，真空实现放在安卓侧（复用公告中心那套 {@code Expr} 与变量表）。 */
	public interface CondEval {
		boolean test(String expr);
	}

	/**
	 * 能接<b>临时变量</b>的求值器。配置文件里的 {@code FLAG} 要在同一份（或同一条链的上一份）
	 * 文件里就能被 {@code if=} 读到，所以求值时必须把当场收集的开关并进变量表。
	 * 实现了它的实现者优先使用；否则退回 {@link #test(String)}（此时 {@code flag_*} 取不到值，
	 * 条件按不成立处理 —— 宁可少生效，不能多解锁）。
	 */
	public interface CondEvalVars extends CondEval {
		boolean test(String expr, Map<String, String> extraVars);
	}

	/** 一份文件的头部。链式解析时每份文件带着自己的头部参与。 */
	private static final class Head {
		int protocolVersion;
		String fileVersion = "";
		boolean strict;
		int rollout = 100;
		int minv;
		int maxv;
		String mode = MODE_APPLY;
		String scope = SCOPE_INHERIT;
		/** {@code @snapshot=full}：这一份已经包含链上旧每一份的全部指令，旧文件可以安全删除。 */
		boolean snapshot;
		String sig;
		String hmac;
		/** 这份文件能不能参与：不通过时给出拒绝原因。 */
		String reject;
	}

	/** 一份（或一串）文件的解析结果。 */
	public static final class Result {
		public boolean accepted;
		/** 拒绝原因：TAMPERED / PROTOCOL / EMPTY / STRICT / VERSION / null。 */
		public String rejectReason;

		/** 链里最后一份（也就是当前生效那份）的版本号。 */
		public String fileVersion = "";
		/** 链里最后一份的协议版本。 */
		public int protocolVersion;
		/** 链里最后一份的作用域。 */
		public String scope = SCOPE_INHERIT;
		/**
		 * 最新那份是不是 {@code @snapshot=full}（全量快照）。为 true 时调用方才知道：
		 * 把链上更旧的那些文件从磁盘删掉不会丢掉任何一条指令 —— 该版把旧每一份的内容都带着了。
		 */
		public boolean fullSnapshot;
		public int rollout = 100;
		public int bucket = 100;
		public int versionCode;

		/** 所有被接受的指令，按文件顺序、行顺序排列（旧 → 新）。 */
		public final List<Rule> rules = new ArrayList<>();
		/** 参与本次解析的文件版本号，旧 → 新。 */
		public final List<String> chain = new ArrayList<>();
		/** 被剔除的文件与原因，供状态面板解释"为什么上一版没继续生效"。 */
		public final Map<String, String> droppedFiles = new LinkedHashMap<>();

		// —— 以下都是 resolve() 重放 rules 得到的派生态 ——
		public final Map<String, Site> sites = new LinkedHashMap<>();
		public final Set<String> droppedSites = new HashSet<>();
		public final Map<String, Category> categories = new LinkedHashMap<>();
		public final Set<String> droppedCategories = new HashSet<>();
		public final Map<String, String> tiers = new HashMap<>();
		/**
		 * 键 → {@code gate=} 原文（登录门禁）。没有这条 = 这一项不参与登录门禁，沿用档位判定。
		 */
		public final Map<String, String> gates = new HashMap<>();
		public final Set<String> hidden = new HashSet<>();
		public final Set<String> shown = new HashSet<>();
		public final Set<String> disabled = new HashSet<>();
		public final Set<String> enabled = new HashSet<>();
		public final Map<String, String> delivery = new HashMap<>();
		public final Set<String> whitelistAdds = new HashSet<>();
		public final Set<String> whitelistDrops = new HashSet<>();
		/**
		 * 分档名单：档名（{@code forum|google|ms}）→ 账号键。
		 * 不写 {@code list=} 的旧写法落进 {@link #whitelistAdds}（共享名单，四档通吃），
		 * 所以 3.9.26 之前那份配置一条都不用改。
		 */
		public final Map<String, Set<String>> wlAddsByList = new LinkedHashMap<>();
		public final Map<String, Set<String>> wlDropsByList = new LinkedHashMap<>();
		/** 显式写了 {@code override=builtin} 且真的作用到内置项上的键。 */
		public final Set<String> overrideKeys = new HashSet<>();
		public final Map<String, String> notices = new HashMap<>();
		/** 云端改过的文案：标题 / 说明 / 图标键 / 链接（内置站点换域名不用发版）。 */
		public final Map<String, String> labels = new HashMap<>();
		public final Map<String, String> descs = new HashMap<>();
		public final Map<String, String> icons = new HashMap<>();
		public final Map<String, String> urls = new HashMap<>();
		/** 云端排的先后：键 → 序号（小的在前）；{@code pin} 过的另外记一份。 */
		public final Map<String, Integer> orders = new HashMap<>();
		public final Set<String> pinned = new HashSet<>();
		/** {@code FLAG} 下发的小开关，条件里写作 {@code flag_xxx}。 */
		public final Map<String, String> flags = new HashMap<>();
		/**
		 * 云控指定的图标变体（预置别名键，如 {@code ember}；空 = 出厂图标）。
		 * 安卓限制：启动器图标只能在包内预置的变体里切换，云端下发的是「选哪个」。
		 */
		public String iconVariant = "";
		public String iconFrom = "";
		/** 云控下发的背景图（https 地址 + sha256；都空 = 包内背景）。 */
		public String bgUrl = "";
		public String bgSha = "";
		public String bgFrom = "";
		/** 展示栏视频：键 → {名称, 播放页地址}（VIDEO add/drop/clear）。 */
		public final java.util.LinkedHashMap<String, String[]> videos = new java.util.LinkedHashMap<>();
		public final java.util.Set<String> droppedVideos = new java.util.HashSet<>();
		public boolean videosClear;
		/**
		 * 翻译词条：站点主机名（{@code *} = 全站通用）→（规范化英文 → 中文）。
		 * 由 {@code TR add host=… en=… zh=…} 填，同一句在站点表里覆盖全局表。
		 */
		public final Map<String, Map<String, String>> translations =
				new LinkedHashMap<String, Map<String, String>>();
		/** 哪些站点默认开汉化：键（站点 id 或主机名）→ on|off|auto。 */
		public final Map<String, String> i18n = new HashMap<>();
		/** 云端新增的本地入口（Activity 必须在本机存在，否则不显示）。 */
		public final Map<String, Entry> entries = new LinkedHashMap<>();
		public final Set<String> droppedEntries = new HashSet<>();
		/** 键 → 最后命中它的那条指令原文（状态面板要把"是谁改了这条"说清楚）。 */
		public final Map<String, String> reasons = new HashMap<>();
		public final List<Issue> issues = new ArrayList<>();
		/** 本来就不该执行的行（灰度没命中 / 条件不成立 / 版本不在区间），不算写错。 */
		public final List<Issue> notes = new ArrayList<>();
		public int applied;
		public int skipped;

		private boolean resolved;

		public String tierOf(String featureId, String fallback) {
			String t = tiers.get(featureId);
			return t == null ? fallback : t;
		}

		/** 入口状态：{@link #VIS_HIDDEN} / {@link #VIS_DISABLED} / null=照常显示。 */
		public String visOf(String featureId) {
			if (hidden.contains(featureId)) {
				return VIS_HIDDEN;
			}
			if (disabled.contains(featureId)) {
				return VIS_DISABLED;
			}
			return null;
		}

		public String noticeOf(String featureId) {
			String n = notices.get(featureId);
			return n == null ? "" : n;
		}

		/** 全站通用词条之外的总量控制用。 */
		int totalTranslations() {
			int n = 0;
			for (Map<String, String> m : translations.values()) {
				n += m.size();
			}
			return n;
		}

		/**
		 * 按规则顺序重放一遍，算出最终派生态。有了本机功能清单才能展开 {@code sel=}
		 * 和校验 {@code override=builtin}，所以这一步推迟到门面构造时做。
		 *
		 * <p>幂等：可以随时带新的清单再算一次。
		 */
		public void resolve(KeySpace ks) {
			sites.clear();
			droppedSites.clear();
			categories.clear();
			droppedCategories.clear();
			tiers.clear();
			gates.clear();
			hidden.clear();
			shown.clear();
			disabled.clear();
			enabled.clear();
			delivery.clear();
			whitelistAdds.clear();
			whitelistDrops.clear();
			wlAddsByList.clear();
			wlDropsByList.clear();
			overrideKeys.clear();
			notices.clear();
			labels.clear();
			descs.clear();
			icons.clear();
			urls.clear();
			orders.clear();
			pinned.clear();
			flags.clear();
			translations.clear();
			i18n.clear();
			entries.clear();
			droppedEntries.clear();
			reasons.clear();
			// 上一轮展开阶段记的问题先清掉：重跑（清单登记齐了再算一次）不应该把同样的错叠出来
			java.util.Iterator<Issue> old = issues.iterator();
			while (old.hasNext()) {
				if (old.next().expansion) {
					old.remove();
				}
			}
			// 展开目标时产生的问题单独记，别把解析阶段已经报过的行重复一遍
			List<Issue> expandIssues = new ArrayList<>();
			Map<String, String> alias = ks == null ? new HashMap<String, String>() : aliasIndex(ks);

			for (Rule r : rules) {
				if ("flag".equals(r.verb)) {
					if (r.flagKey != null && r.flagKey.length() > 0) {
						flags.put(r.flagKey, r.flagValue == null ? "true" : r.flagValue);
					}
					continue;
				}
				if ("icon".equals(r.verb)) {
					if (r.iconReset) {
						iconVariant = "";
						iconFrom = r.file + " L" + r.line + " reset";
					} else if (r.iconVariant != null && r.iconVariant.length() > 0) {
						iconVariant = r.iconVariant;
						iconFrom = r.file + " L" + r.line;
					}
					continue;
				}
				if ("bg".equals(r.verb)) {
					if (r.bgReset) {
						bgUrl = "";
						bgSha = "";
						bgFrom = r.file + " L" + r.line + " reset";
					} else if (r.bgUrl != null && r.bgUrl.length() > 0) {
						bgUrl = r.bgUrl;
						bgSha = r.bgSha == null ? "" : r.bgSha;
						bgFrom = r.file + " L" + r.line;
					}
					continue;
				}
				if ("video".equals(r.verb)) {
					if (r.videoClear) {
						videos.clear();
					}
					if (r.videoDrop != null && r.videoDrop.length() > 0) {
						videos.remove(norm(r.videoDrop));
						droppedVideos.add(norm(r.videoDrop));
					}
					if (r.videoId != null && r.videoPlayer != null) {
						videos.put(norm(r.videoId), new String[]{r.videoName, r.videoPlayer});
						droppedVideos.remove(norm(r.videoId));
					}
					continue;
				}
				if ("entry".equals(r.verb)) {
					if ("add".equals(r.action)) {
						if (ks != null && ks.isBuiltin(norm(r.id))) {
							// 同 id 再登记一次会冒出第二张一样的卡片，必须挡下来
							expandIssues.add(exp(r.file, r.line,
									"ENTRY id 与已有功能重名，这条没执行：" + r.id, r.raw));
							continue;
						}
						Entry e = new Entry(r.id);
						e.name = r.name.length() == 0 ? r.id : r.name;
						e.desc = r.desc;
						e.icon = r.icon.length() == 0 ? r.id : r.icon;
						e.activity = r.activity;
						e.category = r.category.length() == 0 ? "local" : r.category;
						e.tier = r.tierTo == null ? TIER_FREE : r.tierTo;
						e.gate = r.gate == null ? "" : r.gate;
						e.order = r.order;
						e.file = r.file;
						e.line = r.line;
						entries.put(r.id, e);
						droppedEntries.remove(r.id);
					} else {
						droppedEntries.add(norm(r.id));
						entries.remove(norm(r.id));
					}
					continue;
				}
				if ("wl".equals(r.verb)) {
					// 名单分档：写了 list= 的进那一份名单，没写的进共享名单（旧配置一条不改也照旧生效）。
					// drop 优先只在<b>同一份名单</b>内说话；不写 list= 的 drop 是全局踢人，哪里都进不去。
					String k = norm(r.id);
					String list = r.wlList == null || r.wlList.length() == 0
							? LoginPolicy.ANY : r.wlList;
					boolean shared = LoginPolicy.ANY.equals(list);
					Set<String> adds = shared ? whitelistAdds : bucket(wlAddsByList, list);
					Set<String> drops = shared ? whitelistDrops : bucket(wlDropsByList, list);
					if ("drop".equals(r.action)) {
						drops.add(k);
						adds.remove(k);
					} else {
						adds.add(k);
						drops.remove(k);
					}
					continue;
				}
				if ("cat".equals(r.verb)) {
					if ("add".equals(r.action)) {
						Category c = new Category();
						c.id = r.id;
						c.name = r.name.length() == 0 ? r.id : r.name;
						c.order = r.order;
						c.line = r.line;
						c.file = r.file;
						categories.put(r.id, c);
						droppedCategories.remove(r.id);
					} else {
						for (String id : categoryTargets(r, ks)) {
							droppedCategories.add(id);
							categories.remove(id);
						}
					}
					continue;
				}
				if ("site".equals(r.verb)) {
					if ("add".equals(r.action)) {
						Site s = new Site(r.id);
						s.name = r.name.length() == 0 ? r.id : r.name;
						s.category = r.category;
						s.url = r.url;
						s.desc = r.desc;
						s.icon = r.icon;
						s.order = r.order;
						s.mode = r.mode;
						s.line = r.line;
						s.file = r.file;
						sites.put(r.id, s);
						droppedSites.remove(r.id);
						delivery.put(r.id, r.mode);
						if (r.i18n.length() > 0) {
							i18n.put(r.id, r.i18n);
							String h = hostOf(r.url);
							if (h.length() > 0) {
								i18n.put(h, r.i18n);
							}
						}
						if (r.notice.length() > 0) {
							notices.put(r.id, r.notice);
						}
					} else {
						List<String> hit = targets(r, ks, alias, tiers, expandIssues);
						for (String id : hit) {
							droppedSites.add(id);
							sites.remove(id);
							hidden.add(id);
							reasons.put(id, r.raw);
						}
					}
					continue;
				}
				if ("tr".equals(r.verb)) {
					String host = trHost(r, ks, alias, expandIssues);
					if (host == null) {
						continue;
					}
					String en = normalizeEn(r.en);
					if (en.length() == 0) {
						expandIssues.add(exp(r.file, r.line, "TR 的原文规范化之后是空的（只有标点？）", r.raw));
						continue;
					}
					if ("drop".equals(r.action)) {
						Map<String, String> m = translations.get(host);
						if (m != null) {
							m.remove(en);
						}
						continue;
					}
					Map<String, String> m = translations.get(host);
					if (m == null) {
						m = new LinkedHashMap<String, String>();
						translations.put(host, m);
					}
					boolean fresh = !m.containsKey(en);
					if (fresh && m.size() >= TR_PER_HOST_MAX) {
						expandIssues.add(exp(r.file, r.line,
								"host=" + host + " 的词条已到上限 " + TR_PER_HOST_MAX + " 条，这条没进", r.raw));
						continue;
					}
					if (fresh && totalTranslations() >= TR_TOTAL_MAX) {
						expandIssues.add(exp(r.file, r.line,
								"翻译词条总数已到上限 " + TR_TOTAL_MAX + " 条，这条没进", r.raw));
						continue;
					}
					m.put(en, r.zh);
					continue;
				}
				if ("feat".equals(r.verb)) {
					List<String> hit = targets(r, ks, alias, tiers, expandIssues);
					List<String> blocked = new ArrayList<>();
					for (String key : hit) {
						if (ks != null && ks.isBuiltin(key)) {
							if (!r.overrideBuiltin) {
								blocked.add(key);
								continue;
							}
							overrideKeys.add(key);
						}
						if (r.notice.length() > 0) {
							notices.put(key, r.notice);
						}
						if (r.mode != null && r.mode.length() > 0) {
							delivery.put(key, r.mode);
						}
						reasons.put(key, r.raw);
						if (r.i18n.length() > 0) {
							i18n.put(key, r.i18n);
						}
						// 固定次序：改文案 → 改档 → 放行 → 停用 → 隐藏，所以同一行里 hide 一定赢
						if (r.label != null) {
							labels.put(key, r.label);
						}
						if (r.textDesc != null) {
							descs.put(key, r.textDesc);
						}
						if (r.textIcon != null) {
							icons.put(key, r.textIcon);
						}
						if (r.textUrl != null) {
							urls.put(key, r.textUrl);
						}
						if (r.orderReset) {
							orders.remove(key);
						}
						if (r.orderTo != null) {
							orders.put(key, r.orderTo);
						}
						if (r.tierTo != null) {
							tiers.put(key, r.tierTo);
							// 改成白名单等于放行，别再让它在入口上被当成"停用"
							if (TIER_WHITELIST.equals(r.tierTo)) {
								disabled.remove(key);
								enabled.add(key);
							}
						}
						if (r.gate != null) {
							// gate 只登记原文，怎么解释、够不够格，交给 LoginPolicy 现算
							gates.put(key, r.gate);
						}
						if (r.enable) {
							disabled.remove(key);
							hidden.remove(key);
							droppedSites.remove(key);
							enabled.add(key);
						}
						if (r.show) {
							// inherit 语义下的"撤销上一版"：上一版下架过这条，这一版 show 要能把它捞回来
							hidden.remove(key);
							droppedSites.remove(key);
							droppedEntries.remove(key);
							shown.add(key);
						}
						if (r.disable) {
							disabled.add(key);
						}
						if (r.hide) {
							hidden.add(key);
							disabled.remove(key);
						}
						if (r.unpin) {
							pinned.remove(key);
						}
						if (r.pin) {
							pinned.add(key);
						}
						if ((r.show || r.enable) && r.notice.length() == 0) {
							notices.remove(key);
						}
					}
					// 一条 sel=all 少写 override 会命中上百个键：报一条就够了，但要能看出规模
					if (!blocked.isEmpty()) {
						expandIssues.add(exp(r.file, r.line,
								"内置项要写 override=builtin，这条跳过了 " + blocked.size() + " 个（例如 "
										+ title(ks, blocked.get(0)) + "）", r.raw));
					}
				}
			}
			issues.addAll(expandIssues);
			resolved = true;
		}

		public boolean isResolved() {
			return resolved;
		}
	}

	// ============================================================ 入口

	/**
	 * 解析一份配置正文。
	 *
	 * @param text        文件内容（UTF-8）
	 * @param versionCode 当前 App versionCode，用于版本门
	 * @param deviceId    设备稳定标识（ANDROID_ID），用于灰度分桶；null 视为全量
	 * @param hmacKey     作者密钥；null 表示不启用签名校验（仅限开发期）
	 */
	public static Result parse(String text, int versionCode, String deviceId, byte[] hmacKey) {
		return parse(text, versionCode, deviceId, hmacKey, null);
	}

	public static Result parse(String text, int versionCode, String deviceId, byte[] hmacKey,
			CondEval condEval) {
		List<String> one = new ArrayList<>(1);
		one.add(text);
		return parseChain(one, versionCode, deviceId, hmacKey, condEval);
	}

	/**
	 * 链式解析多份版本文件（旧 → 新）。每份文件<b>单独</b>验签：{@code @hmac} 算的是自己那份
	 * 本体，所以把历史版本拼在一起不会破坏防篡改，任何一份被改过就只剔除那一份。
	 *
	 * <p>最后一份的 {@code @scope=replace} 会让调用方丢掉整条链；这件事由 {@code CloudCatalog}
	 * 决定要喂哪些文件进来，本方法只管按顺序重放。
	 */
	public static Result parseChain(List<String> texts, int versionCode, String deviceId,
			byte[] hmacKey, CondEval condEval) {
		Result r = new Result();
		r.versionCode = versionCode;
		r.bucket = bucketOf(deviceId);
		if (texts == null || texts.isEmpty()) {
			r.accepted = false;
			r.rejectReason = "EMPTY";
			return r;
		}

		List<String[]> bodies = new ArrayList<>();   // 每份文件的指令行
		List<Head> heads = new ArrayList<>();
		String lastReject = null;
		int kept = 0;

		for (int fi = 0; fi < texts.size(); fi++) {
			String text = texts.get(fi);
			Head h = new Head();
			if (text == null || text.trim().isEmpty()) {
				h.reject = "EMPTY";
				r.droppedFiles.put("第" + (fi + 1) + "份", h.reject);
				lastReject = h.reject;
				heads.add(h);
				bodies.add(new String[0]);
				continue;
			}
			String[] lines = stripBom(text).replace("\r\n", "\n").replace('\r', '\n')
					.split("\n", -1);
			readHead(h, lines, hmacKey);
			if (h.reject == null && ((h.minv > 0 && versionCode < h.minv)
					|| (h.maxv > 0 && versionCode > h.maxv))) {
				h.reject = "VERSION";            // 这份文件本来就不该参与本版本
			}
			if (h.reject != null) {
				r.droppedFiles.put(h.fileVersion.length() == 0 ? "第" + (fi + 1) + "份" : h.fileVersion,
						h.reject);
				lastReject = h.reject;
				heads.add(h);
				bodies.add(new String[0]);
				continue;
			}
			heads.add(h);
			bodies.add(lines);
			r.chain.add(h.fileVersion.length() == 0 ? ("v" + (fi + 1)) : h.fileVersion);
			lastReject = null;
			kept++;
		}

		if (kept == 0) {
			r.rejectReason = lastReject == null ? "EMPTY" : lastReject;
			r.accepted = false;
			return r;
		}

		Head newest = heads.get(heads.size() - 1);
		r.protocolVersion = newest.protocolVersion;
		r.fileVersion = newest.fileVersion;
		r.scope = newest.scope;
		r.rollout = newest.rollout;
		// 是不是"全量快照"只看最新那一份的自我声明（@snapshot=full）：
		// 声明了就说明旧文件里每一条指令在这一份里都还在，调用方才可以放心把旧文件删掉腾空间。
		// 老版本 App 不认识 @snapshot，readHead 会直接忽略它，所以加这一行不影响任何已装的设备。
		r.fullSnapshot = newest.snapshot;
		boolean strict = newest.strict;

		/** 链上已经收集到的开关，注入条件变量表用（键已带 flag_ 前缀）。 */
		Map<String, String> liveFlags = new HashMap<>();

		for (int fi = 0; fi < bodies.size(); fi++) {
			Head h = heads.get(fi);
			String[] lines = bodies.get(fi);
			if (h.reject != null) {
				continue;
			}
			/*
			 * 查重是「同一份文件里不许重复」。链式解析时后一版重新声明同一个 id 是
			 * <b>更新</b>那一条（改介绍、改顺序），不是重复 —— 所以这三个集合按文件重建。
			 */
			Set<String> seenSiteId = new HashSet<>();
			Set<String> seenSiteUrl = new HashSet<>();
			Set<String> seenCatId = new HashSet<>();
			for (int i = 0; i < lines.length; i++) {
				int lineNo = i + 1;
				String line = trimComment(lines[i]).trim();
				if (line.length() == 0 || line.charAt(0) == '#' || line.charAt(0) == '@') {
					continue;
				}
				String[] hv = splitDirective(line);
				String verb = hv[0];
				Map<String, String> f = parseFields(hv[1]);
				String unknown = firstUnknownField(f);
				// FLAG 的键本身就是任意小写标识符（ui_glass / hide_music…），
				// 不能按普通字段白名单拦。它的合法性在下面 flag 分支里单独校验。
				// VIDEO 允许整段粘贴官方嵌入代码：`<iframe src=… scrolling=… frameborder=…>` 里的属性
				// 会被拆成一堆“不认识的字段”，那不是写错 —— 下面的 video 分支会把 iframe 的 src 原样取回。
				boolean embedPaste = "video".equals(verb) && line.toLowerCase().contains("<iframe");
				if (unknown != null && !"flag".equals(verb) && !embedPaste) {
					bad(r, strict, fi, lineNo, "true".equals(f.get(unknown))
							? "不认识的字：" + unknown + "（指令动作写错了？）"
							: "字段不认识：" + unknown, line);
					continue;
				}
				int rollout = percentOf(firstVal(f, "rollout"), h.rollout);
				if (rollout < r.bucket) {
					note(r, fi, lineNo, "ROLLOUT 灰度未命中（" + rollout + "% < 本机第 " + r.bucket + " 号桶）", line);
					continue;
				}
				int minv = intOf(firstVal(f, "minv"), 0);
				int maxv = intOf(firstVal(f, "maxv"), 0);
				if ((minv > 0 && versionCode < minv) || (maxv > 0 && versionCode > maxv)) {
					note(r, fi, lineNo, "VERSION 不在 " + minv + "…" + maxv + " 版本区间", line);
					continue;
				}
				String mode = lower(firstVal(f, "mode"));
				if (mode.length() == 0) {
					mode = h.mode;
				} else if (!MODES.contains(mode)) {
					bad(r, strict, fi, lineNo, "BAD mode=" + mode, line);
					continue;
				}
				if (MODE_DOWNLOAD.equals(mode)) {
					// 兜底要说实话：这一端还没有"预取到本地"的能力，按 apply 执行，但绝不静悄悄
					r.notes.add(new Issue(fi, lineNo, "mode=download 本端未实现，按 apply 执行", line, false));
					mode = MODE_APPLY;
				}

				/*
				 * 组合条件与 ovo.txt 同一套语法（公告中心的 Expr），值里有空格要用双引号包起来：
				 *   FEAT hide id=文件修复 if="version_code >= 520 && authorized"
				 */
				String cond = firstVal(f, "if", "when");
				if (cond.length() > 0) {
					if (condEval == null) {
						bad(r, strict, fi, lineNo, "COND 本端没有求值器，带条件的指令不执行", line);
						continue;
					}
					boolean hit;
					try {
						hit = condEval instanceof CondEvalVars
								? ((CondEvalVars) condEval).test(cond, liveFlags)
								: condEval.test(cond);
					} catch (Throwable t) {
						bad(r, strict, fi, lineNo, "COND 求值异常：" + t.getClass().getSimpleName(), line);
						continue;
					}
					if (!hit) {
						note(r, fi, lineNo, "COND 不满足：" + cond, line);
						continue;
					}
				}

				/*
				 * 时间窗：from/until（任何指令通用，含 FLAG）。
				 * 格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm（本地时区，取设备时钟）。
				 * 用法：限时开放功能、节日换图、活动期角标 —— 到点自动生效/失效，不用发版。
				 */
				String schedFrom = firstVal(f, "from", "开始");
				String schedUntil = firstVal(f, "until", "结束");
				if (schedFrom.length() > 0 || schedUntil.length() > 0) {
					long now = System.currentTimeMillis();
					if (!inScheduleWindow(schedFrom, schedUntil, now)) {
						note(r, fi, lineNo, "SCHEDULE 不在时间窗（from=" + schedFrom + " until=" + schedUntil + "）", line);
						continue;
					}
				}

				String err = collect(r, verb, f, mode, fi, lineNo, line,
						seenSiteId, seenSiteUrl, seenCatId);
				if (err != null) {
					bad(r, strict, fi, lineNo, err, line);
				} else {
					r.applied++;
					// 开关当场生效：同一份文件里写在后面的 if="flag_xxx" 就能读到它
					if (!r.rules.isEmpty()) {
						Rule last = r.rules.get(r.rules.size() - 1);
						if ("flag".equals(last.verb) && last.flagKey != null) {
							liveFlags.put("flag_" + last.flagKey, last.flagValue);
						}
					}
				}
			}
		}

		r.accepted = !(strict && !r.issues.isEmpty());
		if (!r.accepted && r.rejectReason == null) {
			r.rejectReason = "STRICT";
		}
		return r;
	}

	private static void bad(Result r, boolean strict, int file, int lineNo, String reason, String raw) {
		r.skipped++;
		r.issues.add(new Issue(file, lineNo, reason, raw));
		if (strict) {
			r.rejectReason = "STRICT";
		}
	}

	/** 这一行没错，只是<b>不该在这台机器上执行</b>：记一笔说明，但不算问题。 */
	private static void note(Result r, int file, int lineNo, String reason, String raw) {
		r.skipped++;
		r.notes.add(new Issue(file, lineNo, reason, raw, false));
	}

	// ============================================================ 头部

	/** 读 {@code @} 头，并当场做完签名/摘要/版本门校验。 */
	private static void readHead(Head h, String[] lines, byte[] hmacKey) {
		for (String rawLine : lines) {
			String line = trimComment(rawLine).trim();
			if (line.length() == 0 || line.charAt(0) != '@') {
				continue;
			}
			Map<String, String> f = parseFields(line.substring(1));
			String key = firstKey(f);
			String val = f.get(key);
			if ("ver".equals(key)) {
				h.protocolVersion = intOf(val, 0);
			} else if ("file-version".equals(key)) {
				h.fileVersion = val == null ? "" : val.trim();
			} else if ("strict".equals(key)) {
				h.strict = intOf(val, 0) != 0;
			} else if ("rollout".equals(key)) {
				h.rollout = percentOf(val, 100);
			} else if ("minv".equals(key)) {
				h.minv = intOf(val, 0);
			} else if ("maxv".equals(key)) {
				h.maxv = intOf(val, 0);
			} else if ("default".equals(key)) {
				String m = lower(val);
				if (MODES.contains(m)) {
					h.mode = m;
				}
			} else if ("scope".equals(key)) {
				String s = lower(val);
				h.scope = SCOPE_REPLACE.equals(s) ? SCOPE_REPLACE : SCOPE_INHERIT;
			} else if ("snapshot".equals(key)) {
				h.snapshot = "full".equals(lower(val));
			} else if ("sig".equals(key)) {
				// 摘要与签名都是大小写敏感编码（hex/base64），绝不能顺手 lower()
				h.sig = val == null ? null : val.trim();
			} else if ("hmac".equals(key)) {
				h.hmac = val == null ? null : val.trim();
			}
		}
		if (h.protocolVersion > PROTOCOL_VERSION) {
			h.reject = "PROTOCOL";
			return;
		}
		String body = bodyOf(lines);
		if (hmacKey != null && hmacKey.length > 0) {
			if (h.hmac == null || h.hmac.length() == 0) {
				h.reject = "TAMPERED";
				return;
			}
			if (!constantTimeEquals(hmac(hex(hmacKey), body), stripAlgo(h.hmac))) {
				h.reject = "TAMPERED";
				return;
			}
		}
		if (h.sig != null && h.sig.length() > 0
				&& !constantTimeEquals(sha256Hex(body), stripAlgo(h.sig))) {
			h.reject = "TAMPERED";
		}
	}

	// ============================================================ 单条指令 → Rule

	private static String collect(Result r, String verb, Map<String, String> f, String mode,
			int file, int lineNo, String raw, Set<String> seenSiteId, Set<String> seenSiteUrl,
			Set<String> seenCatId) {
		String action = verbAction(f);
		String id = lower(firstVal(f, "id", "key"));
		String sel = firstVal(f, "sel", "where");

		Rule rule = new Rule();
		rule.verb = verb;
		rule.action = action;
		rule.id = id;
		rule.sel = sel;
		rule.mode = mode;
		rule.file = file;
		rule.line = lineNo;
		rule.raw = raw;
		rule.name = orDefault(firstVal(f, "name", "名"), id);
		rule.category = lower(firstVal(f, "cat", "category", "类别"));
		rule.url = firstVal(f, "url", "link");
		rule.desc = firstVal(f, "desc", "介绍");
		rule.icon = firstVal(f, "icon");
		rule.order = intOf(firstVal(f, "order"), 100);
		rule.notice = firstVal(f, "notice", "提示");
		rule.to = lower(firstVal(f, "to"));
		rule.overrideBuiltin = "builtin".equals(lower(firstVal(f, "override")))
				|| f.containsKey("builtin");
		rule.activity = firstVal(f, "activity");
		rule.en = firstVal(f, "en", "原文");
		rule.zh = firstVal(f, "zh", "译文");
		rule.host = lower(firstVal(f, "host", "domain", "站点"));
		// 页面里的网址带 www.，但 hostOf() 取到的主机名不带，所以词表这边写不写 www. 都认。
		if (rule.host.startsWith("www.")) {
			rule.host = rule.host.substring(4);
		}
		rule.i18n = lower(firstVal(f, "i18n", "汉化"));
		if (rule.i18n.length() > 0 && !I18N_MODES.contains(rule.i18n)) {
			return "i18n 取值必须是 auto|on|off：" + rule.i18n;
		}

		if ("site".equals(verb)) {
			if ("drop".equals(action)) {
				if (id.length() == 0 && sel.length() == 0) {
					return "SITE drop 需要 id= 或 sel=";
				}
				seenSiteId.remove(id);
				seenSiteUrl.remove(normalizeUrl(rule.url));
				r.rules.add(rule);
				return null;
			}
			if (!"add".equals(action)) {
				return "SITE 需要 add|drop";
			}
			if (id.length() == 0) {
				return "SITE add 缺 id";
			}
			if (!isSafeUrl(rule.url)) {
				return "SITE url 非法（必须 https，且不含空白/引号）";
			}
			if (!seenSiteId.add(id)) {
				return "SITE id 重复：" + id;
			}
			String normUrl = normalizeUrl(rule.url);
			if (!seenSiteUrl.add(normUrl)) {
				return "SITE url 重复：" + normUrl;
			}
			rule.category = lower(rule.category);
			if (rule.category.length() > 0) {
				rule.name = rule.name.length() == 0 ? id : rule.name;
			}
			r.rules.add(rule);
			return null;
		}

		if ("cat".equals(verb)) {
			if ("drop".equals(action)) {
				if (id.length() == 0 && sel.length() == 0 && !isAll(sel)) {
					return "CAT drop 需要 id= 或 sel=";
				}
				seenCatId.remove(id);
				r.rules.add(rule);
				return null;
			}
			if (!"add".equals(action)) {
				return "CAT 需要 add|drop";
			}
			if (id.length() == 0) {
				return "CAT add 缺 id";
			}
			if (!seenCatId.add(id)) {
				return "CAT id 重复：" + id;
			}
			r.rules.add(rule);
			return null;
		}

		if ("feat".equals(verb)) {
			if (id.length() == 0 && sel.length() == 0) {
				return "FEAT 需要 id= 或 sel=";
			}
			// 动作可以连写：FEAT id=文件修复 tier=free hide order=1 notice=维护中
			rule.tierTo = inline(f, "tier", "tier".equals(action) ? rule.to : null);
			rule.hide = actOn(f, "hide", action);
			rule.show = actOn(f, "show", action);
			rule.disable = actOn(f, "disable", action);
			rule.enable = actOn(f, "enable", action);
			rule.pin = actOn(f, "pin", action);
			rule.unpin = actOn(f, "unpin", action);
			if (f.containsKey("label") || f.containsKey("标题")) {
				rule.label = firstVal(f, "label", "标题");
			}
			if (f.containsKey("desc") || f.containsKey("介绍")) {
				rule.textDesc = firstVal(f, "desc", "介绍");
			}
			if (f.containsKey("icon")) {
				rule.textIcon = firstVal(f, "icon");
			}
			if (f.containsKey("url") || f.containsKey("link")) {
				rule.textUrl = firstVal(f, "url", "link");
			}
			if (f.containsKey("order")) {
				String o = firstVal(f, "order");
				String t = norm(o);
				if ("none".equals(t) || "auto".equals(t) || "clear".equals(t) || "默认".equals(t)) {
					rule.orderReset = true;                  // 撤掉云端排的序，回到原始位置
				} else {
					try {
						rule.orderTo = Integer.valueOf(o.trim());
					} catch (Exception e) {
						return "FEAT order 要写整数（或写 none 撤销）：" + o;
					}
				}
			}
			// gate 要排在「有没有动作」那道检查前面：只写 gate= 也是一条完整的指令
			String gateErr = readGate(f, rule);
			if (gateErr != null) {
				return gateErr;
			}
			if (!rule.hasFeatAction()) {
				return "FEAT 没有动作（可写 tier= gate= hide show disable enable pin label= desc= icon= url= order=）";
			}
			if (rule.tierTo != null && !TIERS.contains(rule.tierTo)) {
				return "FEAT tier 取值必须是 " + TIERS;
			}
			if (rule.hide && rule.show) {
				return "FEAT 同一行不能既 hide 又 show";
			}
			if (rule.disable && rule.enable) {
				return "FEAT 同一行不能既 disable 又 enable";
			}
			if (rule.pin && rule.unpin) {
				return "FEAT 同一行不能既 pin 又 unpin";
			}
			if (rule.textUrl != null && !isSafeUrl(rule.textUrl)) {
				return "FEAT url 非法（必须 https，且不含空白/引号）";
			}
			if (rule.label != null && rule.label.trim().length() == 0) {
				return "FEAT label 不能是空串";
			}
			r.rules.add(rule);
			return null;
		}

		if ("entry".equals(verb)) {
			rule.tierTo = inline(f, "tier", "tier".equals(action) ? rule.to : null);
			if (rule.tierTo != null && !TIERS.contains(rule.tierTo)) {
				return "ENTRY tier 取值必须是 " + TIERS;
			}
			String gateErr = readGate(f, rule);
			if (gateErr != null) {
				return gateErr;
			}
			if ("drop".equals(action)) {
				if (id.length() == 0) {
					return "ENTRY drop 缺 id";
				}
				r.rules.add(rule);
				return null;
			}
			if (!"add".equals(action)) {
				return "ENTRY 需要 add|drop";
			}
			if (id.length() == 0) {
				return "ENTRY add 缺 id";
			}
			if (rule.activity.length() == 0) {
				return "ENTRY add 缺 activity=（包里已有的 Activity 简单类名）";
			}
			if (!rule.activity.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
				return "ENTRY activity 只能是类名本身，不能带包名或路径：" + rule.activity;
			}
			r.rules.add(rule);
			return null;
		}

		if ("flag".equals(verb)) {
			String k = lower(firstVal(f, "key", "id", "name"));
			String v = firstVal(f, "to", "value", "val");
			/*
			 * 两种写法都认：
			 *   FLAG key=ui_glass to=0      （规范写法）
			 *   FLAG set ui_glass=0         （简写：键就是那个字段名，值就是它的值）
			 * 简写是代码注释与笔记里一直在用的形式；以前解析器只认规范写法，
			 * 于是"配置里写了、界面没反应"。这里补上，两种都能生效。
			 */
			if (k.length() == 0) {
				for (Map.Entry<String, String> e : f.entrySet()) {
					String key = e.getKey();
					if (BARE_WORDS.contains(key) || FIELDS.contains(key)) {
						continue;
					}
					k = lower(key);
					if (v.length() == 0) {
						v = e.getValue();
					}
					break;
				}
			}
			if (k.length() == 0) {
				return "FLAG 缺 key=（或写成 FLAG set 键=值）";
			}
			if (!k.matches("[a-z][a-z0-9_]{0,31}")) {
				return "FLAG key 只能是小写字母开头的字母数字下划线（要能当条件里的变量名）：" + k;
			}
			rule.flagKey = k;
			rule.flagValue = v.length() == 0 ? "true" : v;
			r.rules.add(rule);
			return null;
		}

		if ("wl".equals(verb)) {
			String key = lower(firstVal(f, "email", "user", "key", "id"));
			if (key.length() == 0) {
				return "WL 缺 email/key";
			}
			if (!"add".equals(action) && !"drop".equals(action)) {
				return "WL 需要 add|drop";
			}
			// list= 决定这份名单归哪一档；不写就是共享名单（旧写法，四档通吃）
			String lv = firstVal(f, "list", "档");
			String lk = LoginPolicy.listKey(lv);
			if (lv.length() > 0 && lk == null) {
				return "WL list 的档名不认识：" + lv + "（可写 any / forum / google / ms，或 通用 / 论坛 / 谷歌 / 微软）";
			}
			rule.wlList = lk == null ? LoginPolicy.ANY : lk;
			rule.id = key;
			r.rules.add(rule);
			return null;
		}

		if ("tr".equals(verb)) {
			if (id.length() == 0) {
				// site= 是 host= 的替身写法：给站点 id，主机名由它的网址推
				id = lower(firstVal(f, "site"));
				rule.id = id;
			}
			if ("drop".equals(action)) {
				if (rule.en.length() == 0) {
					return "TR drop 缺 en=";
				}
				if (rule.host.length() == 0 && rule.id.length() == 0) {
					return "TR drop 要写 host=（或 site=站点id；全站词条写 host=*）";
				}
				r.rules.add(rule);
				return null;
			}
			if (!"add".equals(action)) {
				return "TR 需要 add|drop";
			}
			if (rule.en.length() == 0) {
				return "TR add 缺 en=\"原文\"（带空格的英文短语要加引号）";
			}
			if (rule.zh.length() == 0) {
				return "TR add 缺 zh=\"译文\"";
			}
			if (rule.en.length() > TR_TEXT_MAX || rule.zh.length() > TR_TEXT_MAX) {
				return "TR 的 en/zh 都最长 " + TR_TEXT_MAX + " 字（这是词条，不是整页文章）";
			}
			if (rule.host.length() == 0 && rule.id.length() == 0) {
				return "TR add 要写 host=（具体域名或 * 表示全站）或 site=站点 id";
			}
			if (rule.host.length() > 0 && !isAllowableHost(rule.host)) {
				return "TR host 写法不对：" + rule.host;
			}
			r.rules.add(rule);
			return null;
		}

		if ("video".equals(verb)) {
			if ("clear".equals(action)) {
				rule.videoClear = true;
				r.rules.add(rule);
				return null;
			}
			if ("drop".equals(action)) {
				String dk = lower(firstVal(f, "id", "key"));
				if (dk.length() == 0) {
					return "VIDEO drop 缺 id=";
				}
				rule.videoDrop = dk;
				r.rules.add(rule);
				return null;
			}
			if (!"add".equals(action)) {
				return "VIDEO 需要 add|drop|clear";
			}
			String rawVideo = firstVal(f, "url", "link", "code", "video");
			if (rawVideo.length() == 0) {
				return "VIDEO add 缺 url=（粘贴 B 站/抖音的官方嵌入代码或分享链接）";
			}
			String[] pv = com.mineways.VideoEmbed.parse(rawVideo);
			if (pv == null) {
				// 整段官方嵌入代码会被拆词器按 key=value 切开：iframe 自带的 src="//player.bilibili.com/…"
				// 会被当成一个叫 src 的键，url= 只剩「<iframe」。官方代码本来就该能整段粘进来，
				// 所以先取 iframe 的 src，再退一步取原始整行里 url= 之后的全部内容，各解析一次。
				String sc = firstVal(f, "src");
				pv = com.mineways.VideoEmbed.parse(sc);
				if (pv == null) {
					pv = com.mineways.VideoEmbed.parse(afterVideoKey(raw));
				}
			}
			if (pv == null) {
				return "VIDEO url 解析不出可用的嵌入播放（只认 B 站/抖音官方嵌入代码或分享链接）";
			}
			String vid = lower(firstVal(f, "id", "key"));
			if (vid.length() == 0) {
				vid = pv[0] + "_" + pv[1].substring(pv[1].lastIndexOf('/') + 1);
			}
			rule.videoId = vid;
			rule.videoName = rule.name.length() > 0 ? rule.name : firstVal(f, "name");
			rule.videoPlayer = pv[1];
				r.rules.add(rule);
				return null;
		}

		if ("theme".equals(verb)) {
			if ("reset".equals(action)) {
				rule.flagKey = "ui_accent";
				rule.flagValue = "#FFFFFF";
				r.rules.add(rule);
				addThemeFlags(r, "default", rule);
				return null;
			}
			if (!"set".equals(action)) {
				return "THEME 需要 set|reset";
			}
			String preset = lower(firstVal(f, "preset", "name", "id"));
			if (!THEMES.containsKey(preset)) {
				return "THEME preset 不认识（可用：default/ember/frost/ink/mono）：" + preset;
			}
			addThemeFlags(r, preset, rule);
			String radiusOv = firstVal(f, "radius");
			if (radiusOv.length() > 0) {
				Rule fr = new Rule();
				fr.verb = "flag";
				fr.flagKey = "ui_radius";
				fr.flagValue = radiusOv;
				r.rules.add(fr);
			}
			return null;
		}

		if ("icon".equals(verb)) {
			if ("reset".equals(action)) {
				rule.iconReset = true;
				r.rules.add(rule);
				return null;
			}
			if (!"set".equals(action)) {
				return "ICON 需要 set|reset";
			}
			String v = lower(firstVal(f, "variant", "id"));
			if (v.length() == 0) {
				return "ICON set 缺 variant=（预置图标变体键，见 App 内置清单）";
			}
			rule.iconVariant = v;
			r.rules.add(rule);
			return null;
		}

		if ("bg".equals(verb)) {
			if ("reset".equals(action)) {
				rule.bgReset = true;
				r.rules.add(rule);
				return null;
			}
			if (!"set".equals(action)) {
				return "BG 需要 set|reset";
			}
			String u = firstVal(f, "url", "link");
			if (u.length() == 0) {
				return "BG set 缺 url=（必须 https 的 jpg/png）";
			}
			if (!isSafeUrl(u)) {
				return "BG url 非法（必须 https，且不含空白/引号）";
			}
			String sha = lower(firstVal(f, "sha256", "sha"));
			if (sha.length() != 64) {
				return "BG set 缺 sha256=（64 位十六进制；背景图必须签名校验，防镜像投毒）";
			}
			rule.bgUrl = u;
			rule.bgSha = sha;
			r.rules.add(rule);
			return null;
		}

		return "指令不认识：" + verb;
	}

	private static boolean isAll(String sel) {
		return "all".equals(norm(sel));
	}

	// ============================================================ 目标展开

	/**
	 * 一条规则命中的键集合（已规范化）。展开不了就报 Issue，绝不静悄悄。
	 *
	 * @param live 已经重放到现在的档位表（{@code sel=kind:} 要用它，才能"先改档再反选"）
	 */
	private static List<String> targets(Rule r, KeySpace ks, Map<String, String> alias,
			Map<String, String> live, List<Issue> issues) {
		List<String> out = new ArrayList<>();
		if (r.sel != null && r.sel.length() > 0) {
			if (ks == null) {
				issues.add(exp(r.file, r.line, "sel= 需要本机功能清单，当前没有", r.raw));
				return out;
			}
			out.addAll(selector(r.sel, ks, alias, live, r, issues));
		}
		if (r.id != null && r.id.length() > 0) {
			String hit = matchKey(r.id, ks, alias, r, issues);
			if (hit != null) {
				out.add(hit);
			}
		}
		// 去重但保持顺序
		List<String> uniq = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String k : out) {
			if (seen.add(k)) {
				uniq.add(k);
			}
		}
		return uniq;
	}

	/** TR 词条挂在哪个站点上：写了 host= 直接用，只写 site=/id= 就拿这个站点的网址去推主机名。 */
	private static String trHost(Rule r, KeySpace ks, Map<String, String> alias, List<Issue> issues) {
		if (r.host != null && r.host.length() > 0) {
			return r.host;
		}
		String key = matchKey(r.id, ks, alias, r, issues);
		if (key == null) {
			return null;
		}
		String url = ks == null ? "" : ks.urlOf(key);
		String host = hostOf(url);
		if (host.length() == 0) {
			issues.add(exp(r.file, r.line,
					"site=" + r.id + " 查不到网址，TR 不知道该挂到哪个站点；改写 host= 吧", r.raw));
			return null;
		}
		return host;
	}

	/** CAT drop 的目标是<b>类别</b>，不是功能键，所以单独走一套：id= 或 sel=（含 all / 逗号表 / ! 反选）。 */
	private static List<String> categoryTargets(Rule r, KeySpace ks) {
		Set<String> universe = new LinkedHashSet<>();
		if (ks != null) {
			for (String c : ks.categories()) {
				universe.add(norm(c));
			}
		}
		if (r.sel == null || r.sel.length() == 0) {
			List<String> one = new ArrayList<>();
			if (r.id.length() > 0) {
				one.add(norm(r.id));
			}
			return one;
		}
		Set<String> out = new LinkedHashSet<>();
		for (String piece : r.sel.split("\\+")) {
			String term = piece.trim();
			boolean invert = false;
			if (term.startsWith("!")) {
				invert = true;
				term = term.substring(1).trim();
			} else if (term.startsWith("not:")) {
				invert = true;
				term = term.substring(4).trim();
			}
			String t = norm(term);
			if (t.startsWith("id:")) {
				t = norm(unprefix(t, "id:"));
			}
			if ("all".equals(t)) {
				out.addAll(universe);
				continue;
			}
			if ("none".equals(t)) {
				continue;
			}
			for (String piece2 : t.split(",")) {
				String c = norm(piece2);
				if (c.length() > 0) {
					out.add(c);
				}
			}
		}
		if (r.sel.startsWith("!") || r.sel.startsWith("not:")) {
			Set<String> all = new LinkedHashSet<>(universe);
			all.removeAll(out);
			return new ArrayList<>(all);
		}
		return new ArrayList<>(out);
	}

	/** 键的规范化形式 → 键。别名表一次性建好，展开上百条规则也不会变成 O(n²)。 */
	private static Map<String, String> aliasIndex(KeySpace ks) {
		Map<String, String> idx = new HashMap<>();
		for (String key : ks.keys()) {
			register(idx, norm(key), key);
			register(idx, tight(norm(key)), key);
			List<String> al = ks.aliasesOf(key);
			if (al != null) {
				for (String a : al) {
					register(idx, norm(a), key);
					register(idx, tight(norm(a)), key);
				}
			}
		}
		return idx;
	}

	private static void register(Map<String, String> idx, String k, String key) {
		if (k == null || k.length() == 0) {
			return;
		}
		String had = idx.get(k);
		// 两个键抢同一个写法时记成 null：这种写法必须让作者写清楚，不能猜
		if (had != null && !had.equals(key)) {
			idx.put(k, null);
		} else {
			idx.put(k, key);
		}
	}

	/** 把一个写法解析成真实的键；找不到或不唯一时报 Issue 并给出候选。 */
	private static String matchKey(String want, KeySpace ks, Map<String, String> alias,
			Rule r, List<Issue> issues) {
		String n = norm(want);
		if (ks == null) {
			// 没有清单时照旧按字面键处理（开发期断言、以及只针对云端自己新增条目的指令）
			return n;
		}
		String hit = alias.get(n);
		if (hit == null && alias.containsKey(n)) {
			issues.add(exp(r.file, r.line, "写法有歧义：" + want, r.raw));
			return null;
		}
		if (hit == null) {
			hit = alias.get(tight(n));
			if (hit == null && alias.containsKey(tight(n))) {
				issues.add(exp(r.file, r.line, "写法有歧义：" + want, r.raw));
				return null;
			}
		}
		if (hit == null) {
			hit = uniquePrefix(n, ks);
		}
		if (hit == null) {
			issues.add(exp(r.file, r.line, "找不到功能：" + want
					+ (suggest(n, ks).length() == 0 ? "" : "（是不是想写 " + suggest(n, ks) + "？）"), r.raw));
		}
		return hit;
	}

	private static String uniquePrefix(String n, KeySpace ks) {
		if (n.length() < 2) {
			return null;
		}
		String found = null;
		for (String k : ks.keys()) {
			if (k.startsWith(n)) {
				if (found != null) {
					return null;                     // 不止一个，不猜
				}
				found = k;
			}
		}
		return found;
	}

	private static String suggest(String n, KeySpace ks) {
		StringBuilder sb = new StringBuilder();
		String head = n.length() > 3 ? n.substring(0, 3) : n;
		for (String k : ks.keys()) {
			if (k.contains(head) || tight(k).contains(tight(n))) {
				if (sb.length() > 0) {
					sb.append(" / ");
				}
				sb.append(title(ks, k));
				if (sb.length() > 60) {
					break;
				}
			}
		}
		return sb.toString();
	}

	/** 报错时要给人看的名字；清单没给就退回键本身。 */
	private static String title(KeySpace ks, String key) {
		if (ks == null) {
			return key;
		}
		String t = ks.titleOf(key);
		return t == null || t.length() == 0 ? key : t;
	}

	/**
	 * 选择器求值：{@code a+b} 并集，前缀 {@code !} 或 {@code not:} 对全体取反。
	 * 因子见类注释。{@code kind:} 用的是<b>重放到现在</b>的档位，所以同一份文件里
	 * 先 {@code FEAT tier … to=free} 再 {@code FEAT hide sel=!kind:free} 是有意义的。
	 */
	private static Set<String> selector(String expr, KeySpace ks, Map<String, String> alias,
			Map<String, String> live, Rule rule, List<Issue> issues) {
		Set<String> out = new LinkedHashSet<>();
		String[] terms = expr.split("\\+");
		for (String t : terms) {
			String term = t.trim();
			if (term.length() == 0) {
				continue;
			}
			boolean invert = false;
			if (term.startsWith("!")) {
				invert = true;
				term = term.substring(1).trim();
			} else if (term.startsWith("not:")) {
				invert = true;
				term = term.substring(4).trim();
			}
			Set<String> set = factor(term, ks, alias, live, rule, issues);
			if (invert) {
				set = complement(set, ks);
			}
			out.addAll(set);
		}
		if (out.isEmpty()) {
			issues.add(exp(rule.file, rule.line, "sel=" + expr + " 没有命中任何功能", rule.raw));
		}
		return out;
	}

	private static Set<String> complement(Set<String> minus, KeySpace ks) {
		Set<String> out = new LinkedHashSet<>(ks.keys());
		out.removeAll(minus);
		return out;
	}

	private static Set<String> factor(String term, KeySpace ks, Map<String, String> alias,
			Map<String, String> live, Rule rule, List<Issue> issues) {
		Set<String> out = new LinkedHashSet<>();
		String t = norm(term);
		if (t.length() == 0 || "none".equals(t)) {
			return out;                              // 不选：空集（配合 ! 就是全选）
		}
		if ("all".equals(t)) {
			out.addAll(ks.keys());
			return out;
		}
		if (t.startsWith("cat:")) {
			String want = norm(unprefix(t, "cat:"));
			for (String k : ks.keys()) {
				if (want.equals(norm(ks.categoryOf(k)))) {
					out.add(k);
				}
			}
			return out;
		}
		if (t.startsWith("group:")) {
			String want = norm(unprefix(t, "group:"));
			for (String k : ks.keys()) {
				if (want.equals(norm(ks.groupOf(k)))) {
					out.add(k);
				}
			}
			return out;
		}
		if (t.startsWith("tag:")) {
			String want = norm(unprefix(t, "tag:"));
			for (String k : ks.keys()) {
				if (norm(ks.tagsOf(k)).contains(want)) {
					out.add(k);
				}
			}
			return out;
		}
		if (t.startsWith("kind:") || t.startsWith("tier:")) {
			String want = norm(unprefix(unprefix(t, "kind:"), "tier:"));
			for (String k : ks.keys()) {
				if (kindMatches(want, ks, live, k)) {
					out.add(k);
				}
			}
			return out;
		}
		if (t.startsWith("id:")) {
			String hit = matchKey(unprefix(t, "id:"), ks, alias, rule, issues);
			if (hit != null) {
				out.add(hit);
			}
			return out;
		}
		// 逗号列表 = 全选这几项
		for (String piece : term.split(",")) {
			if (piece.trim().length() == 0) {
				continue;
			}
			String hit = matchKey(piece, ks, alias, rule, issues);
			if (hit != null) {
				out.add(hit);
			}
		}
		return out;
	}

	/**
	 * 把 free/normal/limited/whitelist/paid 这些写法归一后与<b>当前档位</b>比较。
	 * 当前档位 = 云端已经改成的档位（{@code live}），没有改过才取出厂档位，
	 * 于是同一份文件里「先改档、再按档位反选」是能用的。
	 */
	private static boolean kindMatches(String want, KeySpace ks, Map<String, String> live, String key) {
		String actual = live == null ? null : live.get(norm(key));
		if (actual == null) {
			actual = ks == null ? "" : norm(ks.tierOf(key));
		}
		if (TIER_NORMAL.equals(actual)) {
			actual = TIER_FREE;
		}
		String w = norm(want);
		if ("paid".equals(w) || "wl".equals(w) || "whitelist".equals(w)) {
			w = TIER_WHITELIST;
		} else if ("limit".equals(w) || "limited".equals(w)) {
			w = TIER_LIMITED;
		} else if ("free".equals(w) || "normal".equals(w) || "open".equals(w)) {
			w = TIER_FREE;
		}
		return w.equals(actual);
	}

	private static String unprefix(String s, String p) {
		return s.startsWith(p) ? s.substring(p.length()) : s;
	}

	/**
	 * 读取并校验 {@code gate=}（FEAT 与 ENTRY 共用）。写错返回给用户看的原因，没问题返回 null。
	 * 语义全在 {@link LoginPolicy} 里，这里只保证「写错的门禁绝不落地」。
	 */
	private static String readGate(Map<String, String> f, Rule rule) {
		if (!f.containsKey("gate")) {
			return null;
		}
		String gv = f.get("gate");
		gv = gv == null ? "" : gv.trim();
		if (gv.length() == 0 || "true".equalsIgnoreCase(gv)) {
			return "gate 要带值（如 gate=login gate=wl:forum gate=free）";
		}
		LoginPolicy.Gate gt = LoginPolicy.parse(gv);
		if (gt == null || gt.bad.length() > 0) {
			return "gate 写错：" + (gt == null ? "值是空的" : gt.bad);
		}
		rule.gate = gv;
		return null;
	}

	/** 分档名单的那一格，没有就建一个空的。 */
	private static Set<String> bucket(Map<String, Set<String>> map, String key) {
		Set<String> s = map.get(key);
		if (s == null) {
			s = new HashSet<>();
			map.put(key, s);
		}
		return s;
	}

	// ============================================================ 查询辅助

	/** 灰度分桶：稳定、均匀、0~99。deviceId 为空时返回 0（= 任何 rollout 都命中）。 */
	public static int bucketOf(String deviceId) {
		if (deviceId == null || deviceId.trim().length() == 0) {
			return 0;
		}
		try {
			byte[] d = MessageDigest.getInstance("SHA-256")
					.digest(deviceId.trim().toLowerCase(Locale.ROOT).getBytes(CHARSET));
			return (Math.abs(((d[0] & 0xff) << 8 | (d[1] & 0xff)))) % 100;
		} catch (Exception e) {
			return 0;
		}
	}

	/** 本体字节：所有非 &#64; 行、LF 规范化、去掉行尾空白。签名与摘要都算这个。 */
	public static String bodyOf(String[] lines) {
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			String t = line.trim();
			if (t.length() == 0 || t.charAt(0) == '@') {
				continue;
			}
			sb.append(rtrim(line)).append('\n');
		}
		return sb.toString();
	}

	/** 给作者工具用：算出该写进 &#64;sig / &#64;hmac 的值。 */
	public static String signBody(String text, byte[] key) {
		String body = bodyOf(stripBom(text).replace("\r\n", "\n").replace('\r', '\n')
				.split("\n", -1));
		return key == null ? "sha256:" + sha256Hex(body) : "base64:" + hmac(hex(key), body);
	}

	/** 比较用的规范化：去首尾空白、小写、连续空白并成一个空格。 */
	public static String norm(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		boolean space = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ' ' || c == '\t' || c == '\u3000') {
				space = true;
				continue;
			}
			if (space && sb.length() > 0) {
				sb.append(' ');
			}
			space = false;
			sb.append(Character.toLowerCase(c));
		}
		return sb.toString();
	}

	/** 去掉全部空白后的写法，用来容忍"少打一个空格"。 */
	private static String tight(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != ' ' && c != '\t' && c != '\u3000') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * 译文词条里英文侧的规范形式：小写 + 空白并一格 + 去掉首尾标点。
	 *
	 * <p>去首尾标点是必须的：页面上常见的是 {@code Download}，词表里作者写成
	 * {@code Download.}（或反过来），大小写与尾点不一样就换不动了。换行、全角空格也在这里统一。
	 */
	public static String normalizeEn(String s) {
		String base = norm(s).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
		int a = 0;
		int b = base.length();
		while (a < b && isPunct(base.charAt(a))) {
			a++;
		}
		while (b > a && isPunct(base.charAt(b - 1))) {
			b--;
		}
		return base.substring(a, b);
	}

	/** 只吃 ASCII 与常见中英标点，别把字母数字吃掉。 */
	private static boolean isPunct(char c) {
		return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':'
				|| c == '"' || c == '\'' || c == '`' || c == '(' || c == ')' || c == '['
				|| c == ']' || c == '{' || c == '}' || c == '<' || c == '>' || c == '|'
				|| c == '、' || c == '。' || c == '！' || c == '？' || c == '；' || c == '：'
				|| c == '（' || c == '）' || c == '「' || c == '」' || c == '『' || c == '』';
	}

	/** URL → 主机名（去协议、路径、端口与 www.）。取不到返回空串。 */
	public static String hostOf(String url) {
		if (url == null) {
			return "";
		}
		String u = url.trim();
		int p = u.indexOf("//");
		if (p >= 0) {
			u = u.substring(p + 2);
		}
		int end = u.length();
		for (int i = 0; i < u.length(); i++) {
			char c = u.charAt(i);
			if (c == '/' || c == '?' || c == '#' || c == ' ') {
				end = i;
				break;
			}
		}
		u = u.substring(0, end);
		int at = u.indexOf('@');
		if (at >= 0) {
			u = u.substring(at + 1);
		}
		int colon = u.indexOf(':');
		if (colon > 0) {
			u = u.substring(0, colon);
		}
		u = u.toLowerCase(Locale.ROOT);
		return u.startsWith("www.") ? u.substring(4) : u;
	}

	/** 词条允许的 host 写法：具体主机名或 {@code *}。 */
	static boolean isAllowableHost(String h) {
		return TR_GLOBAL.equals(h) || h.matches("[a-z0-9][a-z0-9._-]{1,62}");
	}

	// ============================================================ 词法

	private static String[] splitDirective(String line) {
		int sp = -1;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == ' ' || c == '\t') {
				sp = i;
				break;
			}
		}
		if (sp < 0) {
			return new String[]{lower(line), ""};
		}
		return new String[]{lower(line.substring(0, sp)), line.substring(sp + 1)};
	}

	/**
	 * key=value 序列。值支持双引号；裸词里认得的（{@code add}、{@code builtin} 这些）记成
	 * {@code <裸词>=true}，<b>认不得的裸词接回上一个值</b> —— 于是
	 * {@code id=MC 壁纸站} 与 {@code name=图腾 / 旗帜编辑器} 都能读到完整中文，
	 * 不必强迫作者记得加引号。
	 */
	/**
	 * 取原始整行里第一个 {@code url= / link= / code= / video=} 之后的全部内容。
	 * 官方嵌入代码整段粘贴时用：拆词器会把 iframe 自己的引号与空格切成一堆键，
	 * 但原始行里「url= 后面的那一串」是完好的，正好交给 VideoEmbed 认。
	 */
	private static String afterVideoKey(String raw) {
		if (raw == null) {
			return "";
		}
		String low = raw.toLowerCase();
		String[] keys = {"url=", "link=", "code=", "video="};
		for (String k : keys) {
			int i = low.indexOf(k);
			if (i >= 0) {
				return raw.substring(i + k.length()).trim();
			}
		}
		return raw.trim();
	}

	private static Map<String, String> parseFields(String s) {
		Map<String, String> out = new LinkedHashMap<>();
		if (s == null) {
			return out;
		}
		int i = 0;
		int n = s.length();
		String lastValueKey = null;
		while (i < n) {
			while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
				i++;
			}
			if (i >= n) {
				break;
			}
			int start = i;
			while (i < n && s.charAt(i) != '=' && s.charAt(i) != ' ' && s.charAt(i) != '\t') {
				i++;
			}
			String token = s.substring(start, i);
			if (i < n && s.charAt(i) == '=') {
				i++;
				StringBuilder val = new StringBuilder();
				boolean quoted = i < n && s.charAt(i) == '"';
				if (quoted) {
					i++;
					while (i < n && s.charAt(i) != '"') {
						val.append(s.charAt(i));
						i++;
					}
					if (i < n) {
						i++;
					}
				} else {
					while (i < n && s.charAt(i) != ' ' && s.charAt(i) != '\t') {
						val.append(s.charAt(i));
						i++;
					}
				}
				String key = lower(token);
				out.put(key, val.toString());
				lastValueKey = FIELDS.contains(key) || key.length() > 0 ? key : null;
			} else if (token.length() > 0) {
				String bare = lower(token);
				if (BARE_WORDS.contains(bare)) {
					out.put(bare, "true");
					lastValueKey = null;
				} else if (lastValueKey != null && !"true".equals(out.get(lastValueKey))) {
					// 上一个值的续写：中文名里的空格、URL 里的空格都会走到这里
					out.put(lastValueKey, out.get(lastValueKey) + " " + token);
				} else {
					out.put(bare, "true");
					lastValueKey = null;
				}
			}
		}
		return out;
	}

	/** 第一个不认识的 {@code key=}：作者写错字段名时要能看见，而不是"配置下发了但没反应"。 */
	private static String firstUnknownField(Map<String, String> f) {
		for (String k : f.keySet()) {
			if (!FIELDS.contains(k) && !BARE_WORDS.contains(k)) {
				return k;
			}
		}
		return null;
	}

	private static String firstKey(Map<String, String> f) {
		for (String k : f.keySet()) {
			return k;
		}
		return "";
	}

	/**
	 * 这条指令的动作词。<b>只认裸词</b>（值为 "true" 的那种）：{@code tier=free} 里的 tier
	 * 是修饰写法不是动作，把它当动作会让 {@code ENTRY add … tier=free} 读成动作 "free"。
	 */
	// ============================================================ 时间窗 / 主题

	/** 预置主题：一套 ui_* 的出厂映射（THEME set name=xxx 展开成对应 FLAG）。 */
	private static final java.util.Map<String, java.util.Map<String, String>> THEMES;
	static {
		java.util.Map<String, java.util.Map<String, String>> m = new java.util.HashMap<>();
		m.put("default", themeFlags("ui_accent=#FFFFFF", "ui_radius=20",
				"ui_blur=2", "ui_refract=32"));
		m.put("ember",   themeFlags("ui_accent=#E8582A", "ui_radius=22"));
		m.put("frost",   themeFlags("ui_accent=#9CC8FF", "ui_radius=16", "ui_blur=4"));
		m.put("ink",     themeFlags("ui_accent=#C9B6FF"));
		m.put("mono",    themeFlags("ui_accent=#FFFFFF"));
		THEMES = java.util.Collections.unmodifiableMap(m);
	}

	private static java.util.Map<String, String> themeFlags(String... kv) {
		java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
		for (String pair : kv) {
			int i = pair.indexOf('=');
			m.put(pair.substring(0, i), pair.substring(i + 1));
		}
		return m;
	}

	/** 把预置主题展开成 FLAG 规则，追加到 Result（行内 radius= 等单字段覆盖随后生效）。 */
	private static void addThemeFlags(Result r, String name, Rule template) {
		java.util.Map<String, String> preset = THEMES.get(name);
		if (preset == null) {
			return;
		}
		for (java.util.Map.Entry<String, String> e : preset.entrySet()) {
			Rule fr = new Rule();
			fr.verb = "flag";
			fr.action = "set";
			fr.file = template.file;
			fr.line = template.line;
			fr.raw = template.raw;
			fr.flagKey = e.getKey();
			fr.flagValue = e.getValue();
			r.rules.add(fr);
		}
	}

	/**
	 * 时间窗判断：from/until 任一可空；格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm（本地时区）。
	 * 解析失败一律放行（不能因为一条写坏的时间把指令永久废掉）。
	 */
	private static boolean inScheduleWindow(String from, String until, long nowMillis) {
		try {
			java.time.LocalDateTime now = java.time.LocalDateTime.ofInstant(
					java.time.Instant.ofEpochMilli(nowMillis),
					java.time.ZoneId.systemDefault());
			if (from != null && from.trim().length() > 0) {
				if (now.isBefore(parseTime(from.trim(), false))) {
					return false;
				}
			}
			if (until != null && until.trim().length() > 0) {
				if (now.isAfter(parseTime(until.trim(), true))) {
					return false;
				}
			}
			return true;
		} catch (Throwable t) {
			return true;
		}
	}

	/** yyyy-MM-dd（until 取当日 23:59）或 yyyy-MM-dd HH:mm。 */
	private static java.time.LocalDateTime parseTime(String s, boolean endOfDay) throws Exception {
		s = s.trim();
		if (s.length() <= 10) {
			java.time.LocalDate d = java.time.LocalDate.parse(s);
			return d.atTime(endOfDay ? 23 : 0, endOfDay ? 59 : 0);
		}
		return java.time.LocalDateTime.parse(s.replace(' ', 'T'));
	}

	private static String verbAction(Map<String, String> f) {
		for (String k : new String[]{"add", "drop", "set", "reset", "clear", "user", "tier", "hide", "show",
				"disable", "enable", "pin", "unpin"}) {
			if ("true".equals(f.get(k))) {
				return k;
			}
		}
		return "";
	}

	/**
	 * 连写形式的取值：{@code tier=free} 里 tier 是真值；裸词 {@code tier}（值为 "true"）不算，
	 * 那种是旧写法 {@code FEAT tier id=x to=free}，值在 {@code to=} 上。
	 */
	private static String inline(Map<String, String> f, String key, String legacy) {
		String v = f.get(key);
		if (v != null && v.trim().length() > 0 && !"true".equals(v)) {
			return lower(v);
		}
		return legacy == null || legacy.trim().length() == 0 ? null : lower(legacy);
	}

	/** 这个动作到底有没有出现：裸词（{@code hide}）和显式真值（{@code hide=1/true/是}）都算。 */
	private static boolean actOn(Map<String, String> f, String key, String action) {
		if (key.equals(action)) {
			return true;
		}
		String v = f.get(key);
		if (v == null) {
			return false;
		}
		String t = lower(v);
		return "true".equals(t) || "1".equals(t) || "on".equals(t) || "yes".equals(t)
				|| "y".equals(t) || "是".equals(t);
	}

	private static String firstVal(Map<String, String> f, String... keys) {
		for (String k : keys) {
			String v = f.get(k);
			if (v != null && v.trim().length() > 0 && !"true".equals(v)) {
				return v.trim();
			}
		}
		for (String k : keys) {
			if ("true".equals(f.get(k))) {
				return k;
			}
		}
		return "";
	}

	private static String trimComment(String line) {
		boolean q = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				q = !q;
			} else if (c == ';' && !q) {
				return line.substring(0, i);
			}
		}
		return line;
	}

	// ============================================================ 小工具

	private static final Charset CHARSET = Charset.forName("UTF-8");

	private static String stripBom(String s) {
		return s != null && s.startsWith("\uFEFF") ? s.substring(1) : s;
	}

	private static String rtrim(String s) {
		int end = s.length();
		while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
			end--;
		}
		return s.substring(0, end);
	}

	private static String lower(String s) {
		return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
	}

	private static String orDefault(String v, String d) {
		return v == null || v.length() == 0 ? d : v;
	}

	private static int intOf(String v, int d) {
		try {
			return Integer.parseInt(v.trim());
		} catch (Exception e) {
			return d;
		}
	}

	private static int percentOf(String v, int d) {
		if (v == null || v.trim().length() == 0) {
			return d;
		}
		int p = intOf(v.replace("%", ""), d);
		return p < 0 ? 0 : (p > 100 ? 100 : p);
	}

	private static boolean isSafeUrl(String url) {
		if (url == null) {
			return false;
		}
		String u = url.trim();
		return u.regionMatches(true, 0, "https://", 0, 8)
				&& u.length() > 9 && !u.matches(".*[\\s\"'<>].*");
	}

	private static String normalizeUrl(String url) {
		if (url == null) {
			return "";
		}
		String u = url.trim().toLowerCase(Locale.ROOT);
		while (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		return u;
	}

	private static String stripAlgo(String v) {
		int c = v.indexOf(':');
		return c >= 0 ? v.substring(c + 1) : v;
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			sb.append(String.format(Locale.ROOT, "%02x", x));
		}
		return sb.toString();
	}

	private static String sha256Hex(String s) {
		try {
			return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(CHARSET)));
		} catch (Exception e) {
			return "";
		}
	}

	private static String hmac(String hexKey, String body) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(hexToBytes(hexKey), "HmacSHA256"));
			return base64(mac.doFinal(body.getBytes(CHARSET)));
		} catch (Exception e) {
			return "";
		}
	}

	private static byte[] hexToBytes(String s) {
		int n = s.length() / 2;
		byte[] out = new byte[n];
		for (int i = 0; i < n; i++) {
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static String base64(byte[] b) {
		return java.util.Base64.getEncoder().encodeToString(b);
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null || a.length() != b.length()) {
			return false;
		}
		int diff = 0;
		for (int i = 0; i < a.length(); i++) {
			diff |= a.charAt(i) ^ b.charAt(i);
		}
		return diff == 0;
	}
}
