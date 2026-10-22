package com.mineways.home;

import android.content.Context;
import android.content.Intent;

import com.mineways.AiAnimActivity;
import com.mineways.BbToObjActivity;
import com.mineways.BlockbenchActivity;
import com.mineways.BrandScrubActivity;
import com.mineways.CloudTrainActivity;
import com.mineways.ColorCodeActivity;
import com.mineways.PackConvertActivity;
import com.mineways.ExportActivity;
import com.mineways.FileRepairActivity;
import com.mineways.GifActivity;
import com.mineways.LandscapeEnhanceActivity;
import com.mineways.MiniGameActivity;
import com.mineways.ObjPreviewActivity;
import com.mineways.OpenCutActivity;
import com.mineways.P3DConvertActivity;
import com.mineways.PhraseToolboxActivity;
import com.mineways.PixelmapActivity;
import com.mineways.PngToModelActivity;
import com.mineways.R;
import com.mineways.RealWorldActivity;
import com.mineways.RhineLabActivity;
import com.mineways.SeedMapActivity;
import com.mineways.SnowstormActivity;
import com.mineways.WebToolActivity;
import com.mineways.cloud.FeatureCatalog;
import com.mineways.cloud.FeatureGate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 首页唯一的「功能与入口」模型。
 *
 * <p>以前首页有三条各自为政的取数路径 —— {@code allTools()} 走了门面、
 * {@code pagePaid()} 直接按出厂标签串收卡片、关于页的收录清单又自己遍历 {@code WEB_TOOLS} ——
 * 于是云端配置「生效了 17 条」而界面上看不出变化：只要有一条路径绕过门面，隐藏与停用就漏。
 * 现在所有页面、页签、搜索、分类、点击放行、关于页清单，一律从本类的 {@link #items} 取，
 * 一个入口、一份答案。
 *
 * <p>本类负责的四件事：
 * <ol>
 *   <li><b>登记</b>：把内置功能（含别名：图标键、去空格标题、站点域名）交给 {@link FeatureGate}，
 *       这样 {@code override=builtin} 那道保险不再是空转，云端也才认得中文标题；</li>
 *   <li><b>合并</b>：内置 + 云端新增站点 + 云端新增本地入口，排成同一张表（置顶与云端序号优先）；</li>
 *   <li><b>判定</b>：每个条目的档位 / 是否显示 / 是否可用 / 提示语 / 生效方式，全部来自门面的一次问答；</li>
 *   <li><b>取目标</b>：图标键与 Intent 的对应关系集中在这里，MainActivity 不再自己写一长串 if-else。</li>
 * </ol>
 */
public final class HomeIndex {

	/** 内置条目的原始一行：{标题, 说明, 图标键, 标签, 分类或目标页}。 */
	public static final class Row {
		public final String title;
		public final String sub;
		public final String iconKey;
		public final String tags;
		/** 本地工具是导出页标识（可空），网页工具是分类 key。 */
		public final String tail;
		public final boolean web;
		/** 网页工具的真实链接；本地工具为空。 */
		public final String url;

		public Row(String title, String sub, String iconKey, String tags, String tail,
				boolean web, String url) {
			this.title = title;
			this.sub = sub;
			this.iconKey = iconKey;
			this.tags = tags == null ? "" : tags;
			this.tail = tail == null ? "" : tail;
			this.web = web;
			this.url = url == null ? "" : url;
		}

		String category() {
			return web ? tail : "local";
		}
	}

	/** 一条最终呈现给首页的功能。 */
	public static final class Item {
		public final String key;
		public final String title;
		public final String sub;
		public final int icon;
		public final Intent intent;
		public final String cat;
		/** 分类的中文名（搜索要能按「皮肤」「素材」这些板块名搜到）。 */
		public final String catName;
		/** local | web | cloud（云端新增站点）| cloud-local（云端新增本地入口）。 */
		public final String group;
		public final int builtinKind;
		public final int kind;
		public final boolean visible;
		public final boolean usable;
		public final String notice;
		public final String mode;
		public final int order;
		public final boolean pinned;
		/** 云端加进来的条目（要在卡片上标出来，并允许 mode=ask 的先问一句）。 */
		public final boolean fromCloud;
		/**
		 * 激活记录用的稳定标识：内置条目永远是<b>出厂标题</b>，云端改名也不会让用户和那张
		 * 激活码脱钩；云端新增的条目用它的 id。
		 */
		public final String feature;
		/** 命中它的云端指令原文，状态面板用。 */
		public final String source;
		/**
		 * 云控给这一条配的登录门禁原文（{@code gate=}，空 = 没配）。
		 * 门禁的<b>结论</b>不在这里存：登录态会变，而这张表是按配置指纹缓存的 ——
		 * 每次渲染/点击现问 {@code LoginAccess.check}，才会跟着「刚登录完」立刻变。
		 */
		public final String gate;

		Item(String key, String title, String sub, int icon, Intent intent, String cat,
				String catName, String group, int builtinKind, FeatureGate.State st,
				String feature, boolean fromCloud) {
			this.key = key;
			this.feature = feature;
			this.title = title;
			this.sub = sub;
			this.icon = icon;
			this.intent = intent;
			this.cat = cat;
			this.catName = catName;
			this.group = group;
			this.builtinKind = builtinKind;
			this.kind = st.kind;
			this.visible = st.visible;
			this.usable = st.usable;
			this.notice = st.notice;
			this.mode = st.mode;
			this.order = st.order;
			this.pinned = st.pinned;
			this.fromCloud = fromCloud;
			this.source = st.source;
			this.gate = st.gate == null ? "" : st.gate;
		}

		/** 搜索用的 haystack。 */
		public String haystack() {
			return (title + " " + sub + " " + catName).toLowerCase(Locale.US);
		}
	}

	/** 内置类别的一行：{key, 中文名, 说明}。 */
	public static final class Cat {
		public final String id;
		public final String name;
		public final String desc;

		public Cat(String id, String name, String desc) {
			this.id = id;
			this.name = name;
			this.desc = desc;
		}
	}

	private static List<Item> items = Collections.emptyList();
	private static List<Cat> cats = Collections.emptyList();
	private static String builtWith = "";
	/** 已登记进门面的内置键，避免每次重建列表都重登记一遍。 */
	private static final Set<String> registered = new HashSet<>();

	private HomeIndex() {
	}

	// ============================================================ 建表

	/**
	 * 只登记功能清单，不建列表。<b>必须在云端配置解析之前调用</b>（放在 Application.onCreate 里）——
	 * 否则解析器面对一张空清单，每一条 {@code id=} 都会报「找不到功能」，那些假错误会一直留在
	 * 关于页的面板上，让人以为配置写坏了。
	 */
	public static void register(List<Row> rows, List<Cat> catRows) {
		registerBuiltins(rows, catRows);
	}

	/**
	 * 建首页列表。{@code rows} 是内置登记表（原样传过来，本类不认识数组下标含义之外的东西），
	 * 云端新增的条目由门面提供。返回值已经把隐藏项去掉了 —— 页面只管渲染，不再判断门。
	 *
	 * @param stamp 用于识别「配置换过了」的指纹（版本号 + 生效次数），变了就重建
	 */
	public static synchronized List<Item> build(Context ctx, List<Row> rows, List<Cat> catRows,
			String stamp) {
		if (!stamp.equals(builtWith)) {
			items = Collections.emptyList();
			builtWith = stamp;
		}
		if (!items.isEmpty()) {
			return items;
		}
		FeatureGate gate = com.mineways.cloud.CloudCatalog.gate();
		registerBuiltins(rows, catRows);
		gate.ensureResolved();
		// 先算板块，条目才能在构造时带上板块中文名（搜索要能按「皮肤」「素材」搜到）
		cats = categories(catRows, gate);

		List<Item> out = new ArrayList<>();
		for (Row r : rows) {
			int builtinKind = kindOfTags(r.tags);
			FeatureGate.State st = gate.stateOf(r.title, r.category(), builtinKind);
			if (!st.visible) {
				continue;
			}
			String title = st.label != null && st.label.trim().length() > 0 ? st.label : r.title;
			String sub = st.desc != null && st.desc.trim().length() > 0 ? st.desc : r.sub;
			String iconKey = st.icon != null && st.icon.trim().length() > 0 ? st.icon : r.iconKey;
			String url = st.url != null && st.url.trim().length() > 0 ? st.url : r.url;
			out.add(new Item(FeatureCatalog.norm(r.title), title, sub, iconFor(iconKey),
					target(ctx, r, url), r.category(), catName(r.category()),
					r.web ? "web" : "local", builtinKind, st, r.title, false));
		}
		// 云端新增的在线站点
		for (FeatureCatalog.Site s : gate.extraSites()) {
			FeatureGate.State st = gate.stateOf(s.id, s.category, FeatureGate.KIND_FREE);
			if (!st.visible) {
				continue;
			}
			String title = st.label != null && st.label.trim().length() > 0 ? st.label : s.name;
			String sub = st.desc != null && st.desc.trim().length() > 0 ? st.desc : s.desc;
			String url = st.url != null && st.url.trim().length() > 0 ? st.url : s.url;
			String cat = s.category == null || s.category.length() == 0 ? "w_sinc" : s.category;
			out.add(new Item(FeatureCatalog.norm(s.id), title, sub, R.drawable.ic_preview,
					webIntent(ctx, title, url), cat, catName(cat), "cloud",
					FeatureGate.KIND_FREE, st, s.id, true));
		}
		// 云端新增的本地入口：Activity 必须真在包里，否则不显示，并且把原因留在状态面板
		for (FeatureCatalog.Entry e : gate.extraEntries()) {
			FeatureGate.State st = gate.stateOf(e.id, e.category, kindOfTier(e.tier));
			if (!st.visible) {
				continue;
			}
			Class<?> cls = activityFor(ctx, e.activity);
			if (cls == null) {
				continue;
			}
			String title = st.label != null && st.label.trim().length() > 0 ? st.label : e.name;
			String sub = st.desc != null && st.desc.trim().length() > 0 ? st.desc : e.desc;
			Intent it = new Intent(ctx, cls);
			out.add(new Item(FeatureCatalog.norm(e.id), title, sub, iconFor(e.icon), it,
					e.category, catName(e.category), "cloud-local",
					kindOfTier(e.tier), st, e.id, true));
		}
		sort(out, gate);
		items = out;
		return items;
	}

	/** 页签/分组用的类别列表：内置顺序在前，云端新增的板块排在后面；云端改过名的用新名字。 */
	public static List<Cat> categories(final List<Cat> builtin, FeatureGate gate) {
		List<Cat> out = new ArrayList<>();
		Map<String, String> rename = new HashMap<>();
		for (FeatureCatalog.Category c : gate.extraCategories()) {
			rename.put(c.id, c.name);
		}
		Set<String> seen = new HashSet<>();
		for (Cat c : builtin) {
			String name = rename.get(c.id);
			out.add(new Cat(c.id, name == null ? c.name : name, c.desc));
			seen.add(c.id);
		}
		for (FeatureCatalog.Category c : gate.extraCategories()) {
			if (seen.add(c.id)) {
				out.add(new Cat(c.id, c.name, "云端新增板块"));
			}
		}
		return out;
	}

	/** 类别标题（含云端改名）。 */
	public static String catName(String id) {
		for (Cat c : cats) {
			if (c.id.equals(id)) {
				return c.name;
			}
		}
		if ("local".equals(id) || "cloud-local".equals(id)) {
			return "本地工具";
		}
		return "";
	}

	public static String catDesc(String id) {
		for (Cat c : cats) {
			if (c.id.equals(id)) {
				return c.desc;
			}
		}
		return "";
	}

	/** 类别顺序：内置在前，云端新增的按 order 排在后面。 */
	public static List<Cat> categories() {
		return cats;
	}

	/** 按生效档位收条目（页签用）。kind 传 {@link FeatureGate#KIND_FREE} 等。 */
	public static List<Item> ofKind(int kind) {
		List<Item> out = new ArrayList<>();
		for (Item it : items) {
			if (it.kind == kind) {
				out.add(it);
			}
		}
		return out;
	}

	/** 某类别下的条目（首页分小节、关于页收录清单都用它）。 */
	public static List<Item> ofCategory(String cat) {
		List<Item> out = new ArrayList<>();
		for (Item it : items) {
			if (it.cat.equals(cat)) {
				out.add(it);
			}
		}
		return out;
	}

	/** 本地条目（含云端新增的本地入口）。 */
	public static List<Item> localItems() {
		List<Item> out = new ArrayList<>();
		for (Item it : items) {
			if ("local".equals(it.cat) || "cloud-local".equals(it.cat)) {
				out.add(it);
			}
		}
		return out;
	}

	public static List<Item> all() {
		return items;
	}

	/** 按标题取条目（点击放行、状态面板都用键来找）。 */
	public static Item find(String key) {
		String k = FeatureCatalog.norm(key);
		for (Item it : items) {
			if (it.key.equals(k)) {
				return it;
			}
		}
		return null;
	}

	// ============================================================ 登记

	/**
	 * 把内置功能登记进门面。带空格的中文标题必须同时登记「去空格写法」和图标键当别名，
	 * 否则 {@code id=Mine-imator 动画} 这种（解析器截断后只剩 {@code mine-imator}）就匹配不上。
	 */
	private static void registerBuiltins(List<Row> rows, List<Cat> catRows) {
		for (Cat c : catRows) {
			FeatureGate.registerCategory(c.id);
		}
		for (Row r : rows) {
			String key = FeatureCatalog.norm(r.title);
			if (!registered.add(key)) {
				continue;
			}
			String host = hostOf(r.url);
			FeatureGate.register(r.title, kindOfTags(r.tags), r.category(),
					r.web ? "web" : "local", r.tags, r.url,
					r.iconKey, tight(r.title), host);
		}
	}

	private static String tight(String s) {
		return s == null ? "" : s.replace(" ", "").replace("\u3000", "");
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

	// ============================================================ 档位

	/** 出厂标签串 → 档位。云端没改过就用它。 */
	public static int kindOfTags(String tags) {
		String t = tags == null ? "" : tags;
		if (t.contains("paid")) {
			return FeatureGate.KIND_PAID;
		}
		if (t.contains("limit")) {
			return FeatureGate.KIND_LIMIT;
		}
		return FeatureGate.KIND_FREE;
	}

	private static int kindOfTier(String tier) {
		String t = FeatureCatalog.norm(tier);
		if (FeatureCatalog.TIER_WHITELIST.equals(t)) {
			return FeatureGate.KIND_PAID;
		}
		if (FeatureCatalog.TIER_LIMITED.equals(t)) {
			return FeatureGate.KIND_LIMIT;
		}
		return FeatureGate.KIND_FREE;
	}

	// ============================================================ 排序

	/** 置顶优先 → 云端序号 → 表里的原始顺序（不写就还是大家熟悉的位置）。 */
	private static void sort(List<Item> out, final FeatureGate gate) {
		final Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < out.size(); i++) {
			index.put(out.get(i).key, i);
		}
		Collections.sort(out, new Comparator<Item>() {
			@Override
			public int compare(Item a, Item b) {
				if (a.pinned != b.pinned) {
					return a.pinned ? -1 : 1;
				}
				if (a.order >= 0 || b.order >= 0) {
					int aOrder = a.order < 0 ? 1000 : a.order;
					int bOrder = b.order < 0 ? 1000 : b.order;
					if (aOrder != bOrder) {
						return Integer.compare(aOrder, bOrder);
					}
				}
				return Integer.compare(index.get(a.key), index.get(b.key));
			}
		});
	}

	// ============================================================ 图标与目标

	/** 图标键 → drawable。与登记表第三个字段同一套写法；云端可以用 {@code icon=} 换。 */
	public static int iconFor(String key) {
		String k = FeatureCatalog.norm(key);
		if ("opencut".equals(k)) {
			return R.drawable.ic_opencut;
		}
		if ("scrub".equals(k)) {
			return R.drawable.ic_scrub;
		}
		if ("rhinelab".equals(k)) {
			return R.drawable.ic_preview;
		}
		if ("cloudtrain".equals(k)) {
			return R.drawable.ic_cloudtrain;
		}
		if ("export".equals(k)) {
			return R.drawable.ic_export;
		}
		if ("world".equals(k)) {
			return R.drawable.ic_world;
		}
		if ("realworld".equals(k)) {
			return R.drawable.ic_realworld;
		}
		if ("tools".equals(k)) {
			return R.drawable.ic_tools;
		}
		if ("preview".equals(k)) {
			return R.drawable.ic_preview;
		}
		if ("png2model".equals(k)) {
			return R.drawable.ic_png2model;
		}
		if ("bb2obj".equals(k)) {
			return R.drawable.ic_bb2obj;
		}
		if ("particle".equals(k)) {
			return R.drawable.ic_particle;
		}
		if ("blockbench".equals(k)) {
			return R.drawable.ic_blockbench;
		}
		if ("ai".equals(k)) {
			return R.drawable.ic_ai;
		}
		if ("seedmap".equals(k)) {
			return R.drawable.ic_world;
		}
		if ("pixelmap".equals(k)) {
			return R.drawable.ic_realworld;
		}
		if ("colorcode".equals(k)) {
			return R.drawable.ic_particle;
		}
		if ("gif".equals(k)) {
			return R.drawable.ic_preview;
		}
		if ("landscape".equals(k)) {
			return R.drawable.ic_landscape;
		}
		if ("game".equals(k)) {
			return R.drawable.ic_game;
		}
		if ("phrase".equals(k)) {
			return R.drawable.ic_phrase;
		}
		if ("mineimator".equals(k)) {
			return R.drawable.mim_logo;
		}
		if ("fmtconv".equals(k)) {
			return R.drawable.ic_tools;
		}
		if ("packconv".equals(k)) {
			return R.drawable.ic_tools;
		}
		if ("blender".equals(k)) {
			return R.drawable.ic_blender;
		}
		if ("repair".equals(k)) {
			return R.drawable.ic_tools;
		}
		return R.drawable.ic_tools;
	}

	/** 登记表里的图标键 / 目标页 → 真正的 Intent。这一段以前散在 MainActivity 里，是入口加载逻辑的一部分。 */
	private static Intent target(Context ctx, Row r, String urlOverride) {
		if (r.web) {
			return webIntent(ctx, r.title, urlOverride);
		}
		String k = FeatureCatalog.norm(r.iconKey);
		if ("png2model".equals(k)) {
			return new Intent(ctx, PngToModelActivity.class);
		}
		if ("realworld".equals(k)) {
			return new Intent(ctx, RealWorldActivity.class);
		}
		if ("preview".equals(k)) {
			return new Intent(ctx, ObjPreviewActivity.class);
		}
		if ("bb2obj".equals(k)) {
			return new Intent(ctx, BbToObjActivity.class);
		}
		if ("packconv".equals(k)) {
			return new Intent(ctx, PackConvertActivity.class);
		}
		if ("packj2b".equals(k)) {
			return new Intent(ctx, PackConvertActivity.class).putExtra("dir", "java2bedrock");
		}
		if ("particle".equals(k)) {
			return new Intent(ctx, SnowstormActivity.class);
		}
		if ("blockbench".equals(k)) {
			return new Intent(ctx, BlockbenchActivity.class);
		}
		if ("ai".equals(k)) {
			return new Intent(ctx, AiAnimActivity.class);
		}
		if ("seedmap".equals(k)) {
			return new Intent(ctx, SeedMapActivity.class);
		}
		if ("pixelmap".equals(k)) {
			return new Intent(ctx, PixelmapActivity.class);
		}
		if ("colorcode".equals(k)) {
			return new Intent(ctx, ColorCodeActivity.class);
		}
		if ("gif".equals(k)) {
			return new Intent(ctx, GifActivity.class);
		}
		if ("landscape".equals(k)) {
			return new Intent(ctx, LandscapeEnhanceActivity.class);
		}
		if ("game".equals(k)) {
			return new Intent(ctx, MiniGameActivity.class);
		}
		if ("phrase".equals(k)) {
			return new Intent(ctx, PhraseToolboxActivity.class);
		}
		if ("p3d".equals(k)) {
			return new Intent(ctx, P3DConvertActivity.class);
		}
		if ("repair".equals(k)) {
			return new Intent(ctx, FileRepairActivity.class);
		}
		if ("fmtconv".equals(k)) {
			return new Intent(ctx, com.mineways.conv.FormatConvertActivity.class);
		}
		if ("blender".equals(k)) {
			return new Intent(ctx, com.mineways.blender.BlenderActivity.class);
		}
		if ("rhinelab".equals(k)) {
			return new Intent(ctx, RhineLabActivity.class);
		}
		if ("cloudtrain".equals(k)) {
			return new Intent(ctx, CloudTrainActivity.class);
		}
		if ("opencut".equals(k)) {
			return new Intent(ctx, OpenCutActivity.class);
		}
		if ("scrub".equals(k)) {
			return new Intent(ctx, BrandScrubActivity.class);
		}
		if ("mineimator".equals(k)) {
			// 内置引擎（libmineimator.so + Qt5）自绘界面；架构不支持时由该 Activity 自己提示
			return new Intent(ctx, com.mineimator.app.MainActivity.class);
		}
		if ("p3d".equals(k)) {
			return new Intent(ctx, P3DConvertActivity.class);
		}
		// export / world / tools / 空串：都进导出页，登记表的最后一列是页内定位
		Intent it = new Intent(ctx, ExportActivity.class);
		if (r.tail.length() > 0) {
			it.putExtra("page", r.tail);
		}
		return it;
	}

	private static Intent webIntent(Context ctx, String title, String url) {
		Intent it = new Intent(ctx, WebToolActivity.class);
		it.putExtra(WebToolActivity.EXTRA_URL, url);
		it.putExtra(WebToolActivity.EXTRA_TITLE, title);
		return it;
	}

	/**
	 * 云端 {@code ENTRY add activity=} 只允许类名，且必须能在包里找到 ——
	 * 配置永远不下发可执行代码，这条线不能松。
	 */
	private static Class<?> activityFor(Context ctx, String simpleName) {
		if (simpleName == null || simpleName.length() == 0) {
			return null;
		}
		String[] allowed = {
				"SeedMapActivity", "PixelmapActivity", "ColorCodeActivity", "GifActivity",
				"LandscapeEnhanceActivity", "PhraseToolboxActivity", "SnowstormActivity",
				"BlockbenchActivity", "AiAnimActivity", "MiniGameActivity", "RhineLabActivity",
				"CloudTrainActivity", "OpenCutActivity", "BrandScrubActivity", "PngToModelActivity",
				"BbToObjActivity", "ObjPreviewActivity", "FileRepairActivity", "P3DConvertActivity",
				"RealWorldActivity", "ExportActivity", "FormatConvertActivity", "BlenderActivity",
				"PackConvertActivity",
		};
		boolean named = false;
		for (String a : allowed) {
			if (a.equals(simpleName)) {
				named = true;
				break;
			}
		}
		if (!named) {
			return null;
		}
		String[] prefixes = {"com.mineways.", "com.mineways.conv.", "com.mineways.blender."};
		for (String p : prefixes) {
			try {
				Class<?> c = Class.forName(p + simpleName);
				if (android.app.Activity.class.isAssignableFrom(c)) {
					return c;
				}
			} catch (Throwable ignored) {
				// 换下一个包名继续找；三个都找不到就是不显示这条入口
			}
		}
		return null;
	}

	/** 状态面板要按顺序列出「这次配置都动了谁」。 */
	public static Map<String, String> touched() {
		Map<String, String> out = new LinkedHashMap<>();
		for (Item it : items) {
			if (it.source != null && it.source.length() > 0) {
				out.put(it.title, it.source);
			}
		}
		return out;
	}

	/** 换配置后让缓存作废（MainActivity 的 onResume 兜底里调）。 */
	public static synchronized void invalidate() {
		items = Collections.emptyList();
		builtWith = "";
	}
}
