package com.mineways;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.WebView;

import com.mineways.cloud.AdaptScript;
import com.mineways.cloud.CloudCatalog;
import com.mineways.cloud.FeatureCatalog.Adapt;
import com.mineways.cloud.FeatureGate;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 网页适配的界面侧薄壳：把云控下发的 {@code CSS add} / {@code JS add} 注进当前 WebView。
 *
 * <p>判定与取正文都在 {@link AdaptScript}（纯 {@code java.*}，桌面能跑断言），这里只做四件事：
 * 现读云控开关、把外链正文的拉取放到后台线程、回主线程 {@code evaluateJavascript}、
 * 以及<b>第一次给某个站点注入脚本时弹一次告知</b>（用户选关闭就本机永久停）。
 *
 * <p>调用方式：在各页面已有的 {@code WebViewClient.onPageFinished} 里加一行
 * {@code AdaptInject.apply(this, view, url)}。
 *
 * <p><b>只在页面加载完成之后注，不在 {@code onPageStarted} 注</b>。那一次看着是"早点生效"，
 * 实际是在拿上一份文档当这一份：评估落在旧文档上，量到的结论是旧页面的，标记也打进了旧文档，
 * 新文档一提交就全没了 —— 于是"弹了告知、页面里却什么都没有"，重进一次时序变了又时好时坏。
 * 版式早半秒套上远不值得用这个赌注换。
 *
 * <p>注入是幂等的（每条规则在页面里打 {@code mw-adapt-<id>} 标记），所以注完之后<b>再延迟补注三次</b>
 * （100 / 400 / 1200 毫秒）：页面自己 reload、换语言重建 {@code <head>} 都会把标记连节点一起清掉，
 * 补注只会把缺的补回来，不会叠出第二个 {@code <style>}。
 */
public final class AdaptInject {

	private static final String TAG = "AdaptInject";
	private static final String PREFS = "mineways_adapt";
	private static final String K_USER_OFF = "user_off";
	/** 第一次下发 JS 的告知只弹一次（整机一笔账，不分站点）。 */
	private static final String K_NOTICE = "js_notice_shown";
	/** {@code once} 规则的"这台机器已经注过了"记账前缀（键里带正文指纹，改正文就等于换了新规则）。 */
	private static final String K_ONCE = "once_";

	/** 注成之后补注的时间点：页内脚本重建 {@code <head>}（换语言、软刷新）之后还能自愈。 */
	private static final long[] HEAL = {100L, 400L, 1200L};

	/** 一条正文最多注进多少个页面：只是给内存封顶，正常配置远到不了。 */
	private static final int BODY_MEM_MAX = 32;
	/** 拉取失败的冷静期：这段时间内同一 sha256 不再重试，免得每次翻页都去撞墙。 */
	private static final long FAIL_COOLDOWN_MS = 60_000L;

	private static final Executor IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mw-adapt-fetch");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	/** sha256 → 正文。命中就不用再读本机缓存文件，更不会走网络。 */
	private static final Map<String, String> bodies = new HashMap<>();
	/** sha256 → 上次失败的时间戳。 */
	private static final Map<String, Long> failedAt = new HashMap<>();
	/** 最近一次注入的诊断文本（状态面板与页内诊断芯片都读它）。 */
	private static volatile String lastDiag = "还没有注入过";

	private AdaptInject() {
	}

	// ============================================================ 入口

	/**
	 * 给这个页面套上适配。任何一步不满足就静默返回（不注入不等于出错：没开闸、没规则、
	 * 这一页不是网页，都属正常），但真出错了会记进 {@link #lastDiag()} 并写日志。
	 *
	 * <p>注入前先在这一页<b>量一遍</b>（{@link AdaptScript#DETECT_JS}）。量出来"原版就适配手机"的页面，
	 * 不再套 {@code host=*} 那条版式兜底（那会把人家本来好的版面搞乱）；
	 * 但<b>点名写了这个主机名的规则照注</b> —— 功能型 JS（游戏修改器之类）注入的站几乎都是已适配的，
	 * 早期版本在这里整页 return，等于这个通道只能改桌面版页面，做不了任何正经功能。
	 */
	public static void apply(Activity act, WebView web, String url) {
		if (act == null || web == null || url == null) {
			return;
		}
		final FeatureGate gate = CloudCatalog.gate();
		if (gate == null || !gate.adaptOn() || userOff(act)) {
			return;
		}
		final AdaptScript.Page p = AdaptScript.pageOf(url);
		if (p.skip) {
			return;
		}
		try {
			web.evaluateJavascript(AdaptScript.DETECT_JS, json -> {
				String v = AdaptScript.verdict(json);
				rememberVerdict(p.host, v);
				proceed(act, web, p, gate, json, AdaptScript.ADAPTED.equals(v));
			});
		} catch (Throwable t) {
			Log.w(TAG, "量这一页失败了，按未适配处理", t);
			proceed(act, web, p, gate, null, false);
		}
	}

	/**
	 * @param skipAdapted 这页原版就适配手机 —— <b>只放过写了 {@code force} 的规则</b>。
	 *                    口径只有一条，好记也好查：版式修复本来就不该往已适配页面上贴，
	 *                    功能型脚本（游戏修改器这类）自己在配置里声明 {@code force}。
	 */
	private static void proceed(Activity act, WebView web, AdaptScript.Page p, FeatureGate gate,
			String measured, boolean skipAdapted) {
		List<Adapt> css = pick(gate.adaptsFor("css", p.host, p.path), skipAdapted, act);
		List<Adapt> js = pick(gate.adaptsFor("js", p.host, p.path), skipAdapted, act);
		if (css.isEmpty() && js.isEmpty()) {
			if (!skipAdapted) {
				lastDiag = p.host + p.path + " 是桌面版（" + AdaptScript.verdictText(measured)
						+ "），但云端没给它写适配规则";
			}
			return;
		}
		// 本地页（appassets）也在范围内 —— 用户点的是"能改内置界面"，这里不另开门。
		if (needsFetch(css) || needsFetch(js)) {
			fetchThenInject(act, web, p, css, js);
		} else {
			inject(act, web, p, css, js, pairs(css, js, null));
		}
	}

	/** 滤掉两类：已适配页面上的非 {@code force} 规则，和这台设备已经注过的 {@code once} 规则。 */
	private static List<Adapt> pick(List<Adapt> in, boolean skipAdapted, Context ctx) {
		if (in.isEmpty()) {
			return in;
		}
		SharedPreferences sp = ctx == null ? null : sp(ctx);
		List<Adapt> out = new ArrayList<>(in.size());
		for (Adapt a : in) {
			if (skipAdapted && !a.force) {
				continue;
			}
			if (a.once && sp != null && sp.getBoolean(K_ONCE + AdaptScript.fingerprint(a), false)) {
				continue;
			}
			out.add(a);
		}
		return out;
	}

	/** 注成功之后才记账：拉取失败或页面没起来，不该把 once 的那一次额度白白用掉。 */
	private static void markOnce(Context ctx, List<Adapt> rules) {
		if (ctx == null || rules.isEmpty()) {
			return;
		}
		SharedPreferences.Editor ed = sp(ctx).edit();
		boolean any = false;
		for (Adapt a : rules) {
			if (a.once) {
				ed.putBoolean(K_ONCE + AdaptScript.fingerprint(a), true);
				any = true;
			}
		}
		if (any) {
			ed.apply();
		}
	}

	/** 每个主机名最近一次的实测结论（状态面板要说"谁被跳过、谁被重构"，不许含糊）。 */
	private static final java.util.LinkedHashMap<String, String> verdicts =
			new java.util.LinkedHashMap<String, String>() {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
					return size() > 40;
				}
			};

	private static void rememberVerdict(String host, String v) {
		synchronized (verdicts) {
			verdicts.put(host.length() == 0 ? "?" : host, v);
		}
	}

	/** 实测结论清单，状态面板与诊断芯片用。 */
	public static String verdictSummary() {
		synchronized (verdicts) {
			if (verdicts.isEmpty()) {
				return "还没量过任何页面";
			}
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, String> e : verdicts.entrySet()) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(e.getKey()).append(" → ").append(label(e.getValue()));
			}
			return sb.toString();
		}
	}

	private static String label(String v) {
		if (AdaptScript.ADAPTED.equals(v)) {
			return "原版已适配，跳过";
		}
		if (AdaptScript.FAKE.equals(v)) {
			return "原版假适配，按容器补";
		}
		if (AdaptScript.DESKTOP.equals(v)) {
			return "桌面版，重构布局";
		}
		return "没量到";
	}

	/** 这条规则是不是正文在外面、得先拉。 */
	private static boolean needsFetch(List<Adapt> items) {
		for (Adapt a : items) {
			boolean inline = a.data != null && a.data.length() > 0;
			if (!inline && a.url.length() > 0 && !bodies.containsKey(a.sha)) {
				return true;
			}
		}
		return false;
	}

	private static void fetchThenInject(final Activity act, final WebView web,
			final AdaptScript.Page p, final List<Adapt> css, final List<Adapt> js) {
		final File dir = cacheDir(act);
		final List<Adapt> todo = new ArrayList<>();
		todo.addAll(css);
		todo.addAll(js);
		IO.execute(() -> {
			Map<String, String> got = new HashMap<>();
			List<String> errs = new ArrayList<>();
			for (Adapt a : todo) {
				boolean inline = a.data != null && a.data.length() > 0;
				if (inline || a.url.length() == 0) {
					continue;
				}
				String cached = bodies.get(a.sha);
				if (cached != null) {
					got.put(a.sha, cached);
					continue;
				}
				Long since = failedAt.get(a.sha);
				if (since != null && System.currentTimeMillis() - since < FAIL_COOLDOWN_MS) {
					errs.add(a.id + "：刚试过，等一会再拉");
					continue;
				}
				AdaptScript.Fetched f = AdaptScript.fetch(a, dir, CloudCatalog.mirrorRoots(),
						CloudCatalog.branch());
				if (f.body == null) {
					failedAt.put(a.sha, System.currentTimeMillis());
					errs.add(a.id + "：" + f.note);
				} else {
					failedAt.remove(a.sha);
					remember(a.sha, f.body);
					got.put(a.sha, f.body);
				}
			}
			final String why = errs.isEmpty() ? "" : String.join("；", errs);
			web.post(() -> {
				if (act.isFinishing()) {
					return;
				}
				inject(act, web, p, css, js, pairs(css, js, got));
				if (!why.isEmpty()) {
					lastDiag = "有正文没拉到（" + p.host + "）：" + why;
					Log.w(TAG, lastDiag);
				}
			});
		});
	}

	/** 组出 {@link AdaptScript#build} 要的两组 {id, 正文}；拉不到的那条直接跳过（宁可不改，也不注半条）。 */
	private static List<List<String[]>> pairs(List<Adapt> css, List<Adapt> js, Map<String, String> got) {
		List<String[]> c = new ArrayList<>();
		List<String[]> j = new ArrayList<>();
		fillPairs(c, css, got);
		fillPairs(j, js, got);
		List<List<String[]>> out = new ArrayList<>(2);
		out.add(c);
		out.add(j);
		return out;
	}

	private static void fillPairs(List<String[]> dst, List<Adapt> src, Map<String, String> got) {
		for (Adapt a : src) {
			String body = a.data;
			if (body == null || body.length() == 0) {
				body = got == null ? null : got.get(a.sha);
			}
			if (body == null || body.length() == 0) {
				continue;
			}
			dst.add(new String[]{AdaptScript.safeId(a.id), body});
		}
	}

	/** 注进页面。失败要说清是哪一步（唯一入口是 {@code evaluateJavascript}，没什么可含糊的）。 */
	private static boolean run(WebView web, AdaptScript.Page p, String script) {
		try {
			web.evaluateJavascript(script, null);
			return true;
		} catch (Throwable t) {
			lastDiag = "注入失败（" + p.host + "）：" + t.getClass().getSimpleName();
			Log.w(TAG, lastDiag, t);
			return false;
		}
	}

	private static void inject(Activity act, WebView web, AdaptScript.Page p,
			List<Adapt> css, List<Adapt> js, List<List<String[]>> pairs) {
		String script = AdaptScript.build(pairs.get(0), pairs.get(1));
		if (script.isEmpty() || !run(web, p, script)) {
			return;
		}
		lastDiag = "已适配 " + p.host + p.path + "：css " + pairs.get(0).size() + " 条 / js "
				+ pairs.get(1).size() + " 条";
		// 注成功了才记 once 的账（下面两行）；失败时上面已经 return 了
		markOnce(act, css);
		markOnce(act, js);
		maybeNotify(act, p.host, css, js);
		// 同一份脚本再补注三次：页面自己 reload／换语言重建 head 会把标记连节点一起带走，
		// 幂等脚本重投只补缺不叠层。告知只在第一次那条路径上弹。
		for (long delay : HEAL) {
			web.postDelayed(() -> run(web, p, script), delay);
		}
	}

	// ============================================================ 首次告知

	/**
	 * 第一次检测到云端下发 JS 脚本时告知用户（液态玻璃弹窗；起不来才退回普通对话框）。
	 *
	 * <p>口径按他要的那几句话说清楚：加 JS 是为了<strong>网页适配手机</strong>和<strong>紧急运维</strong>，
	 * 不是不法用途；应用内联网<strong>只拉取、不上传</strong>。整台设备只弹这一次，
	 * 之后想关就到「关于 → 云端配置 → 网页适配」那行按关闭。
	 */
	private static void maybeNotify(Activity act, String host, List<Adapt> css, List<Adapt> js) {
		if (js.isEmpty() || act == null) {
			return;
		}
		final SharedPreferences sp = sp(act);
		if (sp.getBoolean(K_NOTICE, false)) {
			return;
		}
		sp.edit().putBoolean(K_NOTICE, true).apply();
		final String site = host.length() == 0 ? "这个页面" : host;
		final String title = "网页脚本已启用";
		final String body = "为了让 " + site + " 这类页面能在手机上正常用，应用会按作者的签名配置给网页加脚本："
				+ "把不支持手机端的版面改成支持手机端的，以及在出问题时无需重装就能临时修一修（紧急运维）。\n\n"
				+ "本次生效：样式 " + css.size() + " 条、脚本 " + js.size() + " 条。\n"
				+ "这些脚本只来自签名配置 —— 云控没开 js_on 时一条都不会注；配置没带脚本时，本弹窗也不会出现。\n\n"
				+ "它不做不法用途：不改你的账号密码、不外传你的数据。应用内的联网动作只有拉取，不会把手机里的东西上传出去。\n"
				+ "不想用可以马上关掉（关掉后本机永久不再注入脚本）。";
		final Context ctx = act;
		boolean shown = com.mineways.ui.MwAdaptOverlay.show(act, title, body,
				() -> { },
				() -> setUserOff(ctx, true));
		if (shown) {
			return;
		}
		try {
			new AlertDialog.Builder(act)
					.setTitle(title)
					.setMessage(body)
					.setPositiveButton("知道了", null)
					.setNeutralButton("关闭 JS 功能", (d, w) -> setUserOff(ctx, true))
					.show();
		} catch (Throwable t) {
			// 弹不出来也别把注入回滚掉：账已经记上，下次不再打扰；状态行里仍然看得见这个功能开着
			Log.w(TAG, "JS 告知弹窗没弹出来", t);
		}
	}

	// ============================================================ 本机开关与诊断

	/** 用户在告知里选了「不再注入」，或到设置里手动关掉 —— 本机永久停，云控再开也没用。 */
	public static boolean userOff(Context ctx) {
		return sp(ctx).getBoolean(K_USER_OFF, false);
	}

	public static void setUserOff(Context ctx, boolean off) {
		sp(ctx).edit().putBoolean(K_USER_OFF, off).apply();
		lastDiag = off ? "网页适配已在本机关闭（设置里可再打开）" : "网页适配已重新打开";
	}

	/** 最近一次注入的诊断文本；云控状态面板和页内诊断芯片都读这一句。 */
	public static String lastDiag() {
		return lastDiag;
	}

	/** 这次链里一共挂了多少条适配（0 = 配置没带这个功能）。 */
	public static int ruleCount() {
		FeatureGate gate = CloudCatalog.gate();
		return gate == null ? 0 : gate.adaptCount();
	}

	/** 「关于 → 云控状态」里的一行话。 */
	public static String statusLine(Context ctx) {
		FeatureGate gate = CloudCatalog.gate();
		int n = ruleCount();
		if (userOff(ctx)) {
			return "网页适配：已在本机关闭（配置里带了 " + n + " 条）";
		}
		if (gate == null || !gate.adaptOn()) {
			return "网页适配：云控没开（FLAG key=js_on to=1 才启用），配置里带了 " + n + " 条";
		}
		int adapted = 0;
		int desktop = 0;
		int fake = 0;
		synchronized (verdicts) {
			for (String v : verdicts.values()) {
				if (AdaptScript.ADAPTED.equals(v)) {
					adapted++;
				} else if (AdaptScript.DESKTOP.equals(v)) {
					desktop++;
				} else if (AdaptScript.FAKE.equals(v)) {
					fake++;
				}
			}
		}
		return "网页适配：已启用，共 " + n + " 条规则 · 量过 " + (adapted + desktop + fake)
				+ " 个站点：桌面版 " + desktop + "、假适配 " + fake + "、原版已适配（跳过）" + adapted
				+ " · 最近：" + lastDiag;
	}

	/** 这一页现在被套了哪些规则（页内诊断芯片用，逐条列出来才好查"改了没反应"）。 */
	public static String describeFor(String url) {
		FeatureGate gate = CloudCatalog.gate();
		AdaptScript.Page p = AdaptScript.pageOf(url);
		if (gate == null || p.skip) {
			return "这一页不是网页，适配不参与";
		}
		List<Adapt> css = gate.adaptsFor("css", p.host, p.path);
		List<Adapt> js = gate.adaptsFor("js", p.host, p.path);
		if (css.isEmpty() && js.isEmpty()) {
			return "这一页没有适配规则（" + p.host + p.path + "）";
		}
		StringBuilder sb = new StringBuilder("适配 " + p.host + p.path + "\n");
		for (Adapt a : css) {
			sb.append("· ").append(AdaptScript.describe(a)).append('\n');
		}
		for (Adapt a : js) {
			sb.append("· ").append(AdaptScript.describe(a)).append('\n');
		}
		return sb.toString();
	}

	// ============================================================ 内部

	private static SharedPreferences sp(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static File cacheDir(Context ctx) {
		File d = new File(ctx.getApplicationContext().getCacheDir(), "adapt");
		if (!d.isDirectory()) {
			d.mkdirs();
		}
		return d;
	}

	private static void remember(String sha, String body) {
		synchronized (bodies) {
			if (bodies.size() >= BODY_MEM_MAX) {
				bodies.clear();
			}
			bodies.put(sha, body);
		}
	}
}
