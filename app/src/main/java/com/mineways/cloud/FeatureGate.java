package com.mineways.cloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 功能门面的查询侧：把 {@link FeatureCatalog} 重放出来的最终状态和本机清单合并成
 * 首页真正要用的答案 —— 这一档是什么、入口要不要显示、点了能不能用、多出租哪些站点。
 *
 * <p>刻意不做成单例持有 Context：这里只有纯数据，安卓侧（缓存、SharedPreferences、
 * 拉取线程）在 {@code CloudCatalog} 里，这样整套判定在开发机上就能用 javac 直接断言。
 *
 * <p><b>档位常量只有一份</b>（{@link #KIND_FREE}/{@link #KIND_LIMIT}/{@link #KIND_PAID}），
 * 首页必须用这三个数。以前 {@code MainActivity} 自己另写了一套 1=白名单、2=限制，
 * 与这里的 1=限制、2=白名单 正好相反，于是云端每改一次档位都会落到错误的页签上。
 *
 * <p>合并规则（安全优先）：
 * <ul>
 *   <li>云端只能<b>改档、隐藏、停用、增删条目</b>；改内置项要写 {@code override=builtin} 才允许，
 *       否则一个笔误就能把出厂功能全锁死。少写了会跳 <b>并上报</b>，不再静悄悄。</li>
 *   <li>指令按文件顺序重放，后一版覆盖前一版；{@code hide} 与 {@code disable} 同时命中时 hide 优先
 *       （连入口都不给，比"点了拒绝"更干净）。</li>
 *   <li>{@code enabled=false}（用户在设置里关掉云端配置）时，所有答案退回内置默认。</li>
 * </ul>
 */
public final class FeatureGate {

	public static final int KIND_FREE = 0;
	public static final int KIND_LIMIT = 1;
	public static final int KIND_PAID = 2;

	// ============================================================ 本机功能清单

	/** 一条内置功能登记进门面时需要的信息。 */
	public static final class Builtin {
		public final String key;
		public final String title;
		public final int kind;
		public final String category;
		public final String group;
		public final String tags;
		/** 内置网站的真实链接；本机功能是空串。TR 用 site= 指站点时靠它推主机名。 */
		public final String url;
		public final List<String> aliases;

		Builtin(String key, String title, int kind, String category, String group, String tags,
				String url, List<String> aliases) {
			this.key = key;
			this.title = title == null || title.trim().length() == 0 ? key : title;
			this.kind = kind;
			this.category = category == null ? "" : category;
			this.group = group == null ? "" : group;
			this.tags = tags == null ? "" : tags;
			this.url = url == null ? "" : url;
			this.aliases = aliases;
		}
	}

	/**
	 * 全局功能清单。它是 static 的：{@code CloudCatalog} 每次生效新版本都会换一个新的
	 * {@link FeatureGate} 对象，但清单本身跟版本无关，登记一次就一直在那儿。
	 */
	public static final class Space implements FeatureCatalog.KeySpace {
		private final Map<String, Builtin> byKey = new LinkedHashMap<>();
		private final Set<String> cats = new LinkedHashSet<>();
		private volatile int version;

	 synchronized void add(Builtin b) {
			byKey.put(b.key, b);
			if (b.category.length() > 0) {
				cats.add(FeatureCatalog.norm(b.category));
			}
			version++;
		}

		synchronized void addCategory(String id) {
			if (id != null && id.length() > 0) {
				cats.add(FeatureCatalog.norm(id));
				version++;
			}
		}

		@Override
		public synchronized List<String> keys() {
			return new ArrayList<>(byKey.keySet());
		}

		@Override
		public synchronized String categoryOf(String key) {
			Builtin b = byKey.get(key);
			return b == null ? "" : b.category;
		}

		@Override
		public synchronized String groupOf(String key) {
			Builtin b = byKey.get(key);
			return b == null ? "" : b.group;
		}

		@Override
		public synchronized String tierOf(String key) {
			Builtin b = byKey.get(key);
			return b == null ? FeatureCatalog.TIER_FREE : tierName(b.kind);
		}

		@Override
		public synchronized String tagsOf(String key) {
			Builtin b = byKey.get(key);
			return b == null ? "" : b.tags;
		}

		@Override
		public synchronized boolean isBuiltin(String key) {
			return byKey.containsKey(key);
		}

		@Override
		public synchronized List<String> aliasesOf(String key) {
			Builtin b = byKey.get(key);
			return b == null ? Collections.<String>emptyList() : b.aliases;
		}

		@Override
		public synchronized String urlOf(String key) {
			Builtin b = byKey.get(key);
			return b == null ? "" : b.url;
		}

		@Override
		public synchronized String titleOf(String key) {
			Builtin b = byKey.get(key);
			return b == null ? key : b.title;
		}

		@Override
		public synchronized List<String> categories() {
			return new ArrayList<>(cats);
		}

		synchronized int version() {
			return version;
		}
	}

	private static final Space SPACE = new Space();

	/** 门面对象构造时用的清单（内置项 + 内置类别）。 */
	public static FeatureCatalog.KeySpace keySpace() {
		return SPACE;
	}

	/**
	 * 登记一条内置功能。首页建列表前调用一次；不登记的话 {@code override=builtin} 那道保险
	 * 就是空转的（以前正是因为从来没人调用它，云端配置里写没写 override 都没区别）。
	 *
	 * @param title   卡片标题，同时是功能键（本地激活记录也按它区分）
	 * @param aliases 别名写法：图标键、去空格标题、站点域名……作者少打几个字也能命中
	 */
	public static void register(String title, int kind, String category, String group,
			String tags, String url, String... aliases) {
		if (title == null || title.trim().length() == 0) {
			return;
		}
		String key = FeatureCatalog.norm(title);
		List<String> al = new ArrayList<>(Arrays.asList(aliases));
		al.add(title);
		SPACE.add(new Builtin(key, title.trim(), kind, category, group, tags, url, al));
	}

	/** 登记一个内置类别（网页工具分类），供 {@code CAT drop} 与 {@code sel=cat:} 使用。 */
	public static void registerCategory(String id) {
		SPACE.addCategory(id);
	}

	/** 清单版本号：变了就要重算一次派生态。 */
	public static int spaceVersion() {
		return SPACE.version();
	}

	// ============================================================ 门面对象

	/** 内置项 + 云端本轮新增的站点与入口，合成一份完整清单交给解析器。 */
	private static final class Composed implements FeatureCatalog.KeySpace {
		private final FeatureCatalog.Result catalog;
		private List<String> keys;
		private final Map<String, FeatureCatalog.Site> sites = new LinkedHashMap<>();
		private final Map<String, FeatureCatalog.Entry> entries = new LinkedHashMap<>();

		Composed(FeatureCatalog.Result catalog) {
			this.catalog = catalog;
			/*
			 * 键空间从<b>规则本身</b>取，而不是从解析结果取：解析结果要等重放完才有内容，
			 * 而别名索引在重放开始前就要建好，否则后一版的 FEAT hide id=<上一版新增的条目>
			 * 会因为"还不认识这个键"而报找不到。
			 */
			for (FeatureCatalog.Rule r : catalog.rules) {
				if (!"add".equals(r.action)) {
					continue;
				}
				String key = FeatureCatalog.norm(r.id);
				if ("site".equals(r.verb)) {
					FeatureCatalog.Site s = new FeatureCatalog.Site(r.id);
					s.name = r.name.length() == 0 ? r.id : r.name;
					s.category = r.category;
					s.url = r.url;
					s.desc = r.desc;
					s.icon = r.icon;
					s.order = r.order;
					s.mode = r.mode;
					sites.put(key, s);
				} else if ("entry".equals(r.verb)) {
					FeatureCatalog.Entry e = new FeatureCatalog.Entry(r.id);
					e.name = r.name.length() == 0 ? r.id : r.name;
					e.desc = r.desc;
					e.icon = r.icon;
					e.activity = r.activity;
					e.category = r.category;
					e.order = r.order;
					entries.put(key, e);
				}
			}
		}

		private List<String> all() {
			if (keys == null) {
				List<String> out = new ArrayList<>(SPACE.keys());
				for (String id : sites.keySet()) {
					if (!out.contains(id)) {
						out.add(id);
					}
				}
				for (String id : entries.keySet()) {
					if (!out.contains(id)) {
						out.add(id);
					}
				}
				keys = out;
			}
			return keys;
		}

		@Override
		public List<String> keys() {
			return all();
		}

		@Override
		public String categoryOf(String key) {
			FeatureCatalog.Entry e = entries.get(key);
			if (e != null) {
				return e.category;
			}
			FeatureCatalog.Site s = sites.get(key);
			return s == null ? SPACE.categoryOf(key) : s.category;
		}

		@Override
		public String groupOf(String key) {
			if (entries.containsKey(key)) {
				return "cloud-local";
			}
			if (sites.containsKey(key)) {
				return "cloud";
			}
			return SPACE.groupOf(key);
		}

		@Override
		public String tierOf(String key) {
			FeatureCatalog.Entry e = entries.get(key);
			if (e != null) {
				return e.tier;
			}
			return sites.containsKey(key) ? FeatureCatalog.TIER_FREE : SPACE.tierOf(key);
		}

		@Override
		public String tagsOf(String key) {
			if (entries.containsKey(key)) {
				return "free cloud local";
			}
			if (sites.containsKey(key)) {
				return "free cloud";
			}
			return SPACE.tagsOf(key);
		}

		@Override
		public boolean isBuiltin(String key) {
			return SPACE.isBuiltin(key);
		}

		@Override
		public List<String> aliasesOf(String key) {
			FeatureCatalog.Site s = sites.get(key);
			if (s != null) {
				List<String> out = new ArrayList<>();
				out.add(s.id);
				out.add(s.name);
				String host = hostOf(s.url);
				if (host.length() > 0) {
					out.add(host);
				}
				return out;
			}
			FeatureCatalog.Entry e = entries.get(key);
			if (e != null) {
				List<String> out = new ArrayList<>();
				out.add(e.id);
				out.add(e.name);
				out.add(e.activity);
				return out;
			}
			return SPACE.aliasesOf(key);
		}

		private static String hostOf(String url) {
			int p = url == null ? -1 : url.indexOf("//");
			if (p < 0) {
				return "";
			}
			String host = url.substring(p + 2);
			int slash = host.indexOf('/');
			return slash > 0 ? host.substring(0, slash) : host;
		}

		@Override
		public String titleOf(String key) {
			FeatureCatalog.Entry e = entries.get(key);
			if (e != null) {
				return e.name;
			}
			FeatureCatalog.Site s = sites.get(key);
			return s == null ? SPACE.titleOf(key) : s.name;
		}

		@Override
		public String urlOf(String key) {
			FeatureCatalog.Site s = sites.get(key);
			if (s != null) {
				return s.url;
			}
			return SPACE.urlOf(key);
		}

		@Override
		public List<String> categories() {
			List<String> out = new ArrayList<>(SPACE.categories());
			for (FeatureCatalog.Site s : sites.values()) {
				String c = FeatureCatalog.norm(s.category);
				if (c.length() > 0 && !out.contains(c)) {
					out.add(c);
				}
			}
			return out;
		}
	}

	private final FeatureCatalog.Result catalog;
	private final boolean cloudOn;
	private int resolvedAt = -1;

	/** 一个功能的完整答案。首页只问这一次，之后显示、点击、页签、文案都从这里取。 */
	public static final class State {
		public final String key;
		public int kind;
		public boolean visible = true;
		public boolean usable = true;
		public String notice = "";
		/** 云端改过的文案；null 表示这一项没被改，用包里的原文。 */
		public String label;
		public String desc;
		public String icon;
		public String url;
		/** 云端排的序号；-1 表示没排。pin 过的另外说。 */
		public int order = -1;
		public boolean pinned;
		/** ask = 打开前要问用户一句。 */
		public String mode = FeatureCatalog.MODE_APPLY;
		/** 云控写的登录门禁原文（{@code gate=}）；空 = 这一项不参与登录门禁。 */
		public String gate = "";
		/** 是谁改了它 —— 最后命中这条的那个指令原文，状态面板要把这件事说清楚。 */
		public String source = "";

		State(String key) {
			this.key = key;
		}
	}

	public FeatureGate(FeatureCatalog.Result catalog, boolean cloudOn) {
		this.catalog = catalog != null && catalog.accepted ? catalog : null;
		this.cloudOn = cloudOn;
	}

	/** 有没有真正生效的云端配置（决定状态面板显示什么、以及 override 校验是否要卡）。 */
	public boolean active() {
		return cloudOn && catalog != null;
	}

	public FeatureCatalog.Result raw() {
		return catalog;
	}

	/**
	 * 重放规则算出最终状态。清单可能比门面构造时更晚才登记齐（{@code MinewaysApp} 先跑、
	 * {@code MainActivity} 后才建列表），所以只要清单版本号变了就重算一次；解析本身是幂等的。
	 */
	public synchronized void ensureResolved() {
		if (catalog == null || catalog.rules.isEmpty()) {
			return;
		}
		int v = SPACE.version();
		if (resolvedAt == v && catalog.isResolved()) {
			return;
		}
		catalog.resolve(new Composed(catalog));
		resolvedAt = v;
	}

	// ============================================================ 查询

	/** 云端指定的图标变体键（预置别名；空 = 出厂图标）。 */
	public String iconVariant() {
		if (!active()) {
			return "";
		}
		ensureResolved();
		return catalog.iconVariant == null ? "" : catalog.iconVariant;
	}

	/** 云端下发的背景图地址（空 = 用包内背景）。 */
	public String bgUrl() {
		if (!active()) {
			return "";
		}
		ensureResolved();
		return catalog.bgUrl == null ? "" : catalog.bgUrl;
	}

	/** 云端背景图的 sha256（空 = 无）。 */
	public String bgSha() {
		if (!active()) {
			return "";
		}
		ensureResolved();
		return catalog.bgSha == null ? "" : catalog.bgSha;
	}

	/** 展示栏视频列表：每项 {键, 名称, 播放页地址}。 */
	public java.util.List<String[]> videoList() {
		java.util.List<String[]> out = new java.util.ArrayList<>();
		if (!active()) {
			return out;
		}
		ensureResolved();
		for (java.util.Map.Entry<String, String[]> e : catalog.videos.entrySet()) {
			String[] v = e.getValue();
			out.add(new String[]{e.getKey(), v[0], v[1]});
		}
		return out;
	}

	/** 功能档位（普通/限制/白名单），云端没改过就用 {@code fallback}。 */
	public int kindOf(String key, int fallback) {
		if (!active() || key == null) {
			return fallback;
		}
		ensureResolved();
		String k = FeatureCatalog.norm(key);
		String tier = catalog.tiers.get(k);
		if (tier == null) {
			return fallback;
		}
		if (FeatureCatalog.TIER_WHITELIST.equals(tier)) {
			return KIND_PAID;
		}
		if (FeatureCatalog.TIER_LIMITED.equals(tier)) {
			return KIND_LIMIT;
		}
		return KIND_FREE;
	}

	/** 入口是否该藏起来（含被整类下架的站点）。 */
	public boolean isHidden(String key, String category) {
		return stateOf(key, category, kindOf(key, KIND_FREE)).visible == false;
	}

	/** 功能是否被停用（入口还在，但点了不给用）。 */
	public boolean isDisabled(String key) {
		if (!active() || key == null) {
			return false;
		}
		ensureResolved();
		return catalog.disabled.contains(FeatureCatalog.norm(key));
	}

	/**
	 * 首页唯一该问的接口：一次给出档位 + 是否显示 + 是否可用 + 提示语 + 命中的指令。
	 *
	 * @param category 该功能所属分类，用于 {@code CAT drop} 整类下架
	 */
	public State stateOf(String key, String category, int builtinKind) {
		State s = new State(key == null ? "" : FeatureCatalog.norm(key));
		s.kind = builtinKind;
		if (!active() || key == null) {
			return s;
		}
		ensureResolved();
		String k = s.key;
		// 文案/排序先取，隐藏与停用也一样要带着它们（状态面板要能说出"云端把它改名成了什么"）
		s.label = catalog.labels.get(k);
		s.desc = catalog.descs.get(k);
		s.icon = catalog.icons.get(k);
		s.url = catalog.urls.get(k);
		Integer o = catalog.orders.get(k);
		s.order = o == null ? -1 : o.intValue();
		s.pinned = catalog.pinned.contains(k);
		String m = catalog.delivery.get(k);
		if (m != null) {
			s.mode = m;
		}
		String g = catalog.gates.get(k);
		if (g != null) {
			s.gate = g;
		}
		if (category != null && category.length() > 0
				&& catalog.droppedCategories.contains(FeatureCatalog.norm(category))) {
			s.visible = false;
			s.source = "CAT drop " + FeatureCatalog.norm(category);
			return s;
		}
		if (catalog.droppedSites.contains(k) || catalog.droppedEntries.contains(k)) {
			s.visible = false;
			s.source = orSource(k, "下架 " + k);
			return s;
		}
		if (catalog.hidden.contains(k)) {
			s.visible = false;
			s.notice = notice(k);
			s.source = orSource(k, "FEAT hide " + k);
			return s;
		}
		s.kind = kindOf(k, builtinKind);
		if (catalog.disabled.contains(k)) {
			s.usable = false;
			s.notice = notice(k);
			s.source = orSource(k, "FEAT disable " + k);
		}
		return s;
	}

	/** 云端新增的本地入口（Activity 由首页核对，包里不存在就不显示）。 */
	public List<FeatureCatalog.Entry> extraEntries() {
		if (!active()) {
			return Collections.emptyList();
		}
		ensureResolved();
		List<FeatureCatalog.Entry> out = new ArrayList<>(catalog.entries.values());
		Collections.sort(out, new Comparator<FeatureCatalog.Entry>() {
			@Override
			public int compare(FeatureCatalog.Entry a, FeatureCatalog.Entry b) {
				return Integer.compare(a.order, b.order);
			}
		});
		return out;
	}

	/** {@code FLAG} 下发的小开关。 */
	public String flag(String key, String fallback) {
		if (!active() || key == null) {
			return fallback;
		}
		ensureResolved();
		String v = catalog.flags.get(FeatureCatalog.norm(key));
		return v == null ? fallback : v;
	}

	/** 全部开关，注入条件变量表用。 */
	public Map<String, String> flags() {
		if (!active()) {
			return Collections.emptyMap();
		}
		ensureResolved();
		return new LinkedHashMap<>(catalog.flags);
	}

	/**
	 * 这个站点能用的译文表：全站通用词条（{@code host=*}）打底，站点专属词条覆盖它。
	 *
	 * <p>返回的表按<b>原文长度从长到短</b>排，注入页面的脚本因此会先试整句再试短语 ——
	 * 不然 {@code "Sound"} 会抢在 {@code "Sound Effects"} 前面，把「音效」翻成「声音效果」。
	 */
	public Map<String, String> translationsFor(String host) {
		Map<String, String> out = new LinkedHashMap<>();
		if (!active()) {
			return out;
		}
		ensureResolved();
		applyInto(out, catalog.translations.get(FeatureCatalog.TR_GLOBAL));
		if (host != null && host.length() > 0) {
			applyInto(out, catalog.translations.get(FeatureCatalog.norm(host)));
		}
		List<Map.Entry<String, String>> es = new ArrayList<>(out.entrySet());
		Collections.sort(es, new Comparator<Map.Entry<String, String>>() {
			@Override
			public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
				return Integer.compare(b.getKey().length(), a.getKey().length());
			}
		});
		out.clear();
		for (Map.Entry<String, String> e : es) {
			out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	private static void applyInto(Map<String, String> dst, Map<String, String> src) {
		if (src != null) {
			dst.putAll(src);
		}
	}

	/** 译文词条总数（状态面板要说清楚这次带了多少条）。 */
	public int translationCount() {
		if (!active()) {
			return 0;
		}
		ensureResolved();
		int n = 0;
		for (Map<String, String> m : catalog.translations.values()) {
			n += m.size();
		}
		return n;
	}

	/**
	 * 这个站点<b>自己</b>有多少条专属词条（不含全站通用那批）。
	 * {@code i18n=auto} 用它判断要不要自动开：有专属词表才开，避免有了通用词之后
	 * 连中文站、没整理过用词的站都被自动动一遍。
	 */
	public int siteEntryCount(String host) {
		if (!active() || host == null || host.length() == 0) {
			return 0;
		}
		ensureResolved();
		Map<String, String> m = catalog.translations.get(FeatureCatalog.norm(host));
		return m == null ? 0 : m.size();
	}

	/**
	 * 这个站点默认开不开汉化。站点键与主机名都能登记（{@code SITE add … i18n=on} 两个都写），
	 * 都没登记就是 {@code auto}：由界面自己嗅探页面是不是一片英文再决定。
	 */
	public String i18nMode(String key, String host) {
		if (!active()) {
			return FeatureCatalog.I18N_AUTO;
		}
		ensureResolved();
		String v = null;
		if (key != null) {
			v = catalog.i18n.get(FeatureCatalog.norm(key));
		}
		if (v == null && host != null) {
			v = catalog.i18n.get(FeatureCatalog.norm(host));
		}
		if (v == null && catalog.i18n.containsKey(FeatureCatalog.TR_GLOBAL)) {
			v = catalog.i18n.get(FeatureCatalog.TR_GLOBAL);
		}
		return v == null ? FeatureCatalog.I18N_AUTO : v;
	}

	/**
	 * 条件变量表：把公告中心那份（版本、登录、设备、时间、{@code roll}…）与云端这一层的变量
	 * 合起来，供指令里的 {@code if=} 用。合成在同一处的原因是配置文件与 ovo.txt 公告共用一套
	 * 语法，维护只需要学一次。这里额外提供的变量：
	 * <ul>
	 *   <li>{@code flag_<开关>} —— {@code FLAG} 下发的小开关；</li>
	 *   <li>{@code cloud_version} —— 当前生效的配置版本号；</li>
	 *   <li>{@code cloud_chain} —— 链上有几份文件在共同生效；</li>
	 *   <li>{@code bucket} —— 本机灰度分桶（0~99，与 {@code rollout=} 用的是同一个数）。</li>
	 * </ul>
	 */
	public Map<String, String> condVars(Map<String, String> base) {
		Map<String, String> v = base == null ? new HashMap<String, String>() : new HashMap<>(base);
		if (!active()) {
			return v;
		}
		ensureResolved();
		for (Map.Entry<String, String> e : catalog.flags.entrySet()) {
			v.put("flag_" + e.getKey(), e.getValue());
		}
		v.put("cloud_version", catalog.fileVersion);
		v.put("cloud_chain", String.valueOf(catalog.chain.size()));
		v.put("bucket", String.valueOf(catalog.bucket));
		return v;
	}

	/**
	 * 某条指令的 {@code if=} 是否成立。没有条件就是恒真；求值器缺失或求值异常都按<b>不成立</b>算
	 * —— 宁可少生效，也不能因为条件算不出来就多解锁一个功能。
	 */
	public static boolean condOk(String expr, FeatureCatalog.CondEval eval) {
		if (expr == null || expr.trim().length() == 0) {
			return true;
		}
		if (eval == null) {
			return false;
		}
		try {
			return eval.test(expr);
		} catch (Throwable t) {
			return false;
		}
	}

	private String orSource(String key, String dflt) {
		String r = catalog.reasons.get(key);
		return r == null || r.length() == 0 ? dflt : r;
	}

	/** 隐藏/停用给用户的提示语，没有则空串。 */
	public String notice(String key) {
		if (!active() || key == null) {
			return "";
		}
		ensureResolved();
		String n = catalog.notices.get(FeatureCatalog.norm(key));
		return n == null ? "" : n;
	}

	/** 云端新加的在线站点（按 order 排好序，且已经去掉被后续版本下架的那些）。 */
	public List<FeatureCatalog.Site> extraSites() {
		if (!active()) {
			return Collections.emptyList();
		}
		ensureResolved();
		List<FeatureCatalog.Site> out = new ArrayList<>(catalog.sites.values());
		Collections.sort(out, new Comparator<FeatureCatalog.Site>() {
			@Override
			public int compare(FeatureCatalog.Site a, FeatureCatalog.Site b) {
				return Integer.compare(a.order, b.order);
			}
		});
		return out;
	}

	/** 云端新加的类别。 */
	public List<FeatureCatalog.Category> extraCategories() {
		if (!active()) {
			return Collections.emptyList();
		}
		ensureResolved();
		List<FeatureCatalog.Category> out = new ArrayList<>(catalog.categories.values());
		Collections.sort(out, new Comparator<FeatureCatalog.Category>() {
			@Override
			public int compare(FeatureCatalog.Category a, FeatureCatalog.Category b) {
				return Integer.compare(a.order, b.order);
			}
		});
		return out;
	}

	/** 该账号是否被云端显式踢出授权名单（优先级高于内置名单）。 */
	public boolean userDropped(String key) {
		return active() && key != null && key.trim().length() > 0
				&& catalog.whitelistDrops.contains(FeatureCatalog.norm(key));
	}

	/** 授权用户名单：内置名单 ∪ 云端增 − 云端删。 */
	public boolean userAllowed(String normalizedKey, boolean builtinHit) {
		if (!active() || normalizedKey == null) {
			return builtinHit;
		}
		ensureResolved();
		String k = FeatureCatalog.norm(normalizedKey);
		if (catalog.whitelistDrops.contains(k)) {
			return false;
		}
		return builtinHit || catalog.whitelistAdds.contains(k);
	}

	/** 某条云端站点是"问用户 / 自动下载 / 自动加载"。 */
	public String deliveryOf(String key) {
		if (!active() || key == null) {
			return FeatureCatalog.MODE_APPLY;
		}
		ensureResolved();
		String m = catalog.delivery.get(FeatureCatalog.norm(key));
		return m == null ? FeatureCatalog.MODE_APPLY : m;
	}

	// ============================================================ 登录门禁 / 分档名单

	/**
	 * 这个功能的 {@code gate=} 原文（空串 = 云控没写，沿用档位判定）。
	 * 解释交给 {@link LoginPolicy}，这里只负责「是谁改的」。
	 */
	public String gateOf(String key) {
		if (!active() || key == null) {
			return "";
		}
		ensureResolved();
		String g = catalog.gates.get(FeatureCatalog.norm(key));
		return g == null ? "" : g;
	}

	/** 云控改过门禁的功能数（状态面板那一行要用）。 */
	public int gatedCount() {
		if (!active()) {
			return 0;
		}
		ensureResolved();
		return catalog.gates.size();
	}

	/** 账号键里有没有任何一个是「全局被踢」的（不写 list= 的 WL drop = 哪里都进不去）。 */
	public boolean wlKickedAnywhere(String... ids) {
		if (!active() || ids == null) {
			return false;
		}
		ensureResolved();
		for (String id : ids) {
			String k = id == null ? "" : FeatureCatalog.norm(id);
			if (k.length() > 0 && catalog.whitelistDrops.contains(k)) {
				return true;
			}
		}
		return false;
	}

	/** 命中共享名单（旧的不写 list= 的 WL add）。 */
	public boolean wlShared(String... ids) {
		return wlInList(null, ids);
	}

	/** 命中某一档的名单（档名已归一：{@code forum|google|ms}）；共享名单同样算命中。 */
	public boolean wlInList(String list, String... ids) {
		if (!active() || ids == null) {
			return false;
		}
		ensureResolved();
		java.util.Set<String> adds = catalog.whitelistAdds;
		java.util.Set<String> drops = catalog.whitelistDrops;
		if (list != null && !LoginPolicy.ANY.equals(list)) {
			java.util.Set<String> sc = catalog.wlAddsByList.get(list);
			if (sc != null) {
				adds = union(adds, sc);
			}
			java.util.Set<String> sd = catalog.wlDropsByList.get(list);
			if (sd != null) {
				drops = union(drops, sd);
			}
		}
		for (String id : ids) {
			String k = id == null ? "" : FeatureCatalog.norm(id);
			if (k.length() > 0 && adds.contains(k) && !drops.contains(k)) {
				return true;
			}
		}
		return false;
	}

	private static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b) {
		java.util.Set<String> out = new java.util.HashSet<>(a);
		out.addAll(b);
		return out;
	}

	// ============================================================ 小工具

	/** 档位号 → 档位名（登记内置项时用，解析器按名字比较）。 */
	public static String tierName(int kind) {
		if (kind == KIND_PAID) {
			return FeatureCatalog.TIER_WHITELIST;
		}
		return kind == KIND_LIMIT ? FeatureCatalog.TIER_LIMITED : FeatureCatalog.TIER_FREE;
	}
}
