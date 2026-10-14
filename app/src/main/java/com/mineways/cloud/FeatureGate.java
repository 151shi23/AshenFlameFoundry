package com.mineways.cloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 功能门面的查询侧：把已解析的 {@link FeatureCatalog.Result} 和内置默认合并成
 * 工具箱/在线网站真正要用的答案（这一档是什么、要不要显示、能不能用、还多出租哪些站点）。
 *
 * <p>刻意不做成单例持有 Context：这里只有纯数据，安卓侧（缓存、SharedPreferences、
 * 拉取线程）在 {@code CloudCatalog} 里，这样这套判定在开发机上就能用 javac 直接断言。
 *
 * <p>合并规则（安全优先）：
 * <ul>
 *   <li>云端只能<b>改档、隐藏、停用、增删条目</b>；改档要写 {@code override=builtin} 才允许动内置项，
 *       否则一个笔误就能把出厂功能全锁死。</li>
 *   <li>同一条指令同时被 hide/disable 时，hide 优先（连入口都不给，比"点了拒绝"更干净）。</li>
 *   <li>{@code enabled=false}（用户在设置里关掉云端配置）时，所有答案退回内置默认。</li>
 * </ul>
 */
public final class FeatureGate {

	/** 与 MainActivity 的 KIND_* 对齐：0 免费、1 限制、2 白名单。 */
	public static final int KIND_FREE = 0;
	public static final int KIND_LIMIT = 1;
	public static final int KIND_PAID = 2;

	/** 内置工具的一条：档位 + 是否可被云端改动。 */
	public static final class Builtin {
		public final String key;
		public final int kind;
		public final String category;

		public Builtin(String key, int kind, String category) {
			this.key = key;
			this.kind = kind;
			this.category = category;
		}
	}

	private final FeatureCatalog.Result catalog;
	private final boolean cloudOn;
	private final Map<String, String> builtinTiers = new HashMap<>();

	public FeatureGate(FeatureCatalog.Result catalog, boolean cloudOn) {
		this.catalog = catalog != null && catalog.accepted ? catalog : null;
		this.cloudOn = cloudOn;
	}

	/** 有没有真正生效的云端配置（决定设置页显示什么、以及 override 校验是否要卡）。 */
	public boolean active() {
		return cloudOn && catalog != null;
	}

	public FeatureCatalog.Result raw() {
		return catalog;
	}

	/** 登记内置功能，供 {@code override} 校验用。 */
	public void registerBuiltin(String key, int kind, String category) {
		builtinTiers.put(norm(key), tierName(kind));
	}

	// ============================================================ 查询

	/** 功能档位（白名单/限制/普通/免费）。 */
	public int kindOf(String key, int fallback) {
		if (!active() || key == null) {
			return fallback;
		}
		String k = norm(key);
		if (!allowedToTouch(k)) {
			return fallback;
		}
		String tier = catalog.tierOf(k, null);
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
		if (!active() || key == null) {
			return false;
		}
		String k = norm(key);
		if (category != null && catalog.droppedCategories.contains(norm(category))) {
			return true;
		}
		if (catalog.droppedSites.contains(k)) {
			return true;
		}
		if (!allowedToTouch(k)) {
			return false;
		}
		return catalog.hidden.contains(k) || catalog.disabled.contains(k);
	}

	/** 功能是否被停用（入口还在，但点了不给用）。 */
	public boolean isDisabled(String key) {
		if (!active() || key == null) {
			return false;
		}
		String k = norm(key);
		return allowedToTouch(k) && catalog.disabled.contains(k) && !catalog.hidden.contains(k);
	}

	/** 停用/隐藏时给用户的提示语，没有则空串。 */
	public String notice(String key) {
		if (!active() || key == null) {
			return "";
		}
		String k = norm(key);
		if (!allowedToTouch(k)) {
			return "";
		}
		String n = catalog.notices.get(k);
		return n == null ? "" : n;
	}

	/** 云端新加的在线站点（按 order 排好序）。 */
	public List<FeatureCatalog.Site> extraSites() {
		if (!active()) {
			return Collections.emptyList();
		}
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
		return active() && key != null && !key.trim().isEmpty()
				&& catalog.whitelistDrops.contains(norm(key));
	}

	/** 授权用户名单：内置名单 ∪ 云端增 − 云端删。 */
	public boolean userAllowed(String normalizedKey, boolean builtinHit) {
		if (!active() || normalizedKey == null) {
			return builtinHit;
		}
		String k = norm(normalizedKey);
		if (catalog.whitelistDrops.contains(k)) {
			return false;
		}
		return builtinHit || catalog.whitelistAdds.contains(k);
	}

	/** 某条云端站点是"问用户/自动下载/自动加载"。 */
	public String deliveryOf(String key) {
		if (!active() || key == null) {
			return FeatureCatalog.MODE_APPLY;
		}
		String m = catalog.delivery.get(norm(key));
		return m == null ? FeatureCatalog.MODE_APPLY : m;
	}

	// ============================================================ 内部

	/**
	 * 是否允许云端动这个键。内置功能必须显式写 {@code override=builtin} 才放行 ——
	 * 这样"手滑写错一个 id"不会把出厂功能关掉。云端自己新增的条目（SITE add 过的）不受此限。
	 */
	private boolean allowedToTouch(String key) {
		if (catalog.sites.containsKey(key)) {
			return true;
		}
		if (!builtinTiers.containsKey(key)) {
			return true;
		}
		return catalog.overrideKeys.contains(key);
	}

	public static String tierName(int kind) {
		if (kind == KIND_PAID) {
			return FeatureCatalog.TIER_WHITELIST;
		}
		return kind == KIND_LIMIT ? FeatureCatalog.TIER_LIMITED : FeatureCatalog.TIER_NORMAL;
	}

	private static String norm(String s) {
		return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
	}
}
