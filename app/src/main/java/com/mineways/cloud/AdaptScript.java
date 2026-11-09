package com.mineways.cloud;

import com.mineways.cloud.FeatureCatalog.Adapt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网页适配的纯逻辑层：把 {@code CSS add} / {@code JS add} 下发的规则，变成"能注进 WebView 的一段脚本"。
 *
 * <p>这一层<b>不碰 android 任何类</b>（只有 {@code java.*}），所以能在桌面 JVM 上直接拿真配置跑断言 ——
 * 与 {@code MusicResolve}、{@code StoryPackages} 同一套路子：判定归判定，界面归界面。
 * 界面侧的薄壳是 {@code com.mineways.AdaptInject}。
 *
 * <p>三件事：
 * <ol>
 *   <li>{@link #pageOf(String)}：把 WebView 的 url 变成「主机名 + 路径」，{@code host=} / {@code path=} 就是照它匹配的；</li>
 *   <li>{@link #fetch(Adapt, File)}：外链正文的拉取（镜像顺序复用 {@code CloudCatalog} 那份，不另抄一张表）、
 *       sha256 校验、按摘要命名的本机缓存；</li>
 *   <li>{@link #build(List, List)}：生成幂等的注入脚本 —— 同一条规则重复注入只会有一次效果，
 *       页面软刷新／SPA 换路由都不会叠出第二个 {@code <style>}。</li>
 * </ol>
 */
public final class AdaptScript {

	private AdaptScript() {
	}

	/** 正文体积上限（跟解析器共用一个数，配置侧和本机侧不会各说各话）。 */
	public static final int BODY_CAP = FeatureCatalog.ADAPT_FETCH_MAX;

	/** 注入脚本给每个标签打的标记前缀，幂等判定就靠它。 */
	public static final String MARKER = "mw-adapt";

	/** 拉取一层的失败原因都拼进 {@link Fetched#note}，界面要把这句话原样显示出来。 */
	public static final class Fetched {
		/** 正文；null = 没拿到。 */
		public final String body;
		/** 人话说明：走了哪条镜像 / 命中缓存 / 为什么失败。 */
		public final String note;
		/** 是不是本机缓存直接命中的（缓存命中不需要网络）。 */
		public final boolean cached;

		Fetched(String body, String note, boolean cached) {
			this.body = body;
			this.note = note == null ? "" : note;
			this.cached = cached;
		}

		Fetched(String body, String note) {
			this(body, note, false);
		}
	}

	/** 页面身份。{@link #skip} 为真表示这压根不是个网页（about:blank、data:、javascript:），不该注。 */
	public static final class Page {
		public final String host;
		public final String path;
		public final boolean skip;

		Page(String host, String path, boolean skip) {
			this.host = host == null ? "" : host;
			this.path = path == null ? "/" : path;
			this.skip = skip;
		}
	}

	// ============================================================ 页面身份

	/**
	 * WebView 的 url → 「主机名 + 路径」。
	 *
	 * <p>{@code https://appassets.androidplatform.net/assets/snowstorm/index.html} 这类本地工具页
	 * 主机名都在一个域下，靠 {@code path=/assets/snowstorm} 才分得开谁是谁；
	 * {@code file:///android_asset/…} 根本没有主机名，这时只能用 {@code host=* path=/android_asset/…}。
	 */
	public static Page pageOf(String url) {
		String u = url == null ? "" : url.trim();
		if (u.length() == 0) {
			return new Page("", "/", true);
		}
		String low = u.toLowerCase(Locale.ROOT);
		if (low.startsWith("about:") || low.startsWith("data:") || low.startsWith("blob:")
				|| low.startsWith("javascript:") || low.startsWith("file:")) {
			// file:// 也放过：这类页面是应用自己的产物预览，没有可写的 host，规则会误伤到别的 file 页
			return new Page("", "/", true);
		}
		String rest = u;
		int p = rest.indexOf("//");
		if (p >= 0) {
			rest = rest.substring(p + 2);
		} else if (p < 0 && !rest.contains("/")) {
			// 连路径都没有的裸串，不是页面地址
			return new Page("", "/", true);
		}
		int cut = rest.length();
		for (int i = 0; i < rest.length(); i++) {
			char c = rest.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				cut = i;
				break;
			}
		}
		String host = rest.substring(0, cut);
		String tail = rest.substring(cut);
		int q = tail.length();
		for (int i = 0; i < tail.length(); i++) {
			char c = tail.charAt(i);
			if (c == '?' || c == '#') {
				q = i;
				break;
			}
		}
		String path = tail.substring(0, q);
		if (path.length() == 0) {
			path = "/";
		}
		int at = host.lastIndexOf('@');
		if (at >= 0) {
			host = host.substring(at + 1);
		}
		int colon = host.indexOf(':');
		if (colon > 0) {
			host = host.substring(0, colon);
		}
		host = host.toLowerCase(Locale.ROOT);
		if (host.startsWith("www.")) {
			host = host.substring(4);
		}
		return new Page(host, path, false);
	}

	// ============================================================ 注入脚本

	/**
	 * 生成注入用的整段脚本。
	 *
	 * @param css 每条 {@code {id, 正文}}，已按 order 排好；顺序就是层叠顺序，后面盖前面
	 * @param js  同上
	 * @return 交给 {@code WebView.evaluateJavascript} 的表达式；没有内容时返回空串
	 */
	public static String build(List<String[]> css, List<String[]> js) {
		boolean hasCss = css != null && !css.isEmpty();
		boolean hasJs = js != null && !js.isEmpty();
		if (!hasCss && !hasJs) {
			return "";
		}
		StringBuilder sb = new StringBuilder(256 + sizeOf(css) + sizeOf(js));
		sb.append("(function(){var M={css:");
		writePairs(hasCss ? css : new ArrayList<String[]>(), sb);
		sb.append(",js:");
		writePairs(hasJs ? js : new ArrayList<String[]>(), sb);
		sb.append("};");
		sb.append(APPLY);
		sb.append("})();");
		return sb.toString();
	}

	/**
	 * 页面上真正干活的那段。三个要点：
	 * <ul>
	 *   <li>幂等：每条按 id 打标记，已经注过就跳过 —— 页面 {@code setPageScriptInstead} 之后我们会再注一次；</li>
	 *   <li>等 DOM：{@code documentElement} 还没长出来就每 100ms 重试，最多 40 次（4 秒），
	 *       不是一次评估就完事，SPA 换路由也照样补得上；</li>
	 *   <li>JS 正文用 {@code <script>} 元素承载而不是直接 eval：出问题时页面里能看见是哪条规则（带 id），
	 *       控制台报错也能定位到 {@code mw-adapt-…}。</li>
	 * </ul>
	 */
	private static final String APPLY =
			"function go(){var d=document;"
			+ "if(!d||!d.documentElement||!d.head&&d.getElementsByTagName('head').length<1){return false;}"
			+ "var box=d.head||d.documentElement,i,t,e,ex;"
			+ "for(i=0;i<M.css.length;i++){t=M.css[i];ex=d.getElementById('" + MARKER + "-'+t[0]);"
			+ "if(ex){ex.textContent=t[1];continue;}"
			+ "e=d.createElement('style');e.id='" + MARKER + "-'+t[0];e.setAttribute('data-" + MARKER + "',t[0]);"
			+ "e.textContent=t[1];box.appendChild(e);}"
			+ "for(i=0;i<M.js.length;i++){t=M.js[i];if(d.querySelector('script[data-" + MARKER + "=\"'+t[0]+'\"]')){continue;}"
			+ "e=d.createElement('script');e.setAttribute('data-" + MARKER + "',t[0]);e.textContent=t[1];box.appendChild(e);}"
			+ "return true;}"
			+ "if(go()){return;}"
			+ "var n=0,tm=setInterval(function(){n++;if(go()||n>40){clearInterval(tm);}},100);";

	private static int sizeOf(List<String[]> items) {
		int n = 0;
		if (items != null) {
			for (String[] it : items) {
				n += it.length > 1 && it[1] != null ? it[1].length() : 0;
			}
		}
		return n;
	}

	private static void writePairs(List<String[]> items, StringBuilder sb) {
		sb.append('[');
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			String[] it = items.get(i);
			sb.append('[').append(quote(safeId(it.length > 0 ? it[0] : ""))).append(',')
					.append(quote(it.length > 1 && it[1] != null ? it[1] : "")).append(']');
		}
		sb.append(']');
	}

	/**
	 * 规则 id 在脚本里同时当元素 id 和 CSS 属性选择器用，所以只留 {@code [a-z0-9._-]}；
	 * 汉字、空格、引号统统换成 {@code -}。宁可难看，也不能让一条 id 把整段注入脚本的语法带坏。
	 */
	public static String safeId(String raw) {
		String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder(s.length() + 4);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '.' || c == '_' || c == '-';
			sb.append(ok ? c : '-');
		}
		while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
			sb.deleteCharAt(sb.length() - 1);
		}
		String out = sb.toString();
		if (out.length() > 80) {
			out = out.substring(0, 80);
		}
		return out.length() == 0 ? "anon" : out;
	}

	/** JSON 字符串字面量：换行、引号、反斜杠都要转义，尖角号也转（防 {@code </script>} 提前闭合）。 */
	static String quote(String s) {
		StringBuilder o = new StringBuilder(s.length() + 16);
		o.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':
					o.append("\\\"");
					break;
				case '\\':
					o.append("\\\\");
					break;
				case '\n':
					o.append("\\n");
					break;
				case '\r':
					o.append("\\r");
					break;
				case '\t':
					o.append("\\t");
					break;
				case '<':
					o.append("\\u003c");
					break;
				case '>':
					o.append("\\u003e");
					break;
				default:
					if (c < 0x20) {
						o.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
					} else {
						o.append(c);
					}
			}
		}
		return o.append('"').toString();
	}

	// ============================================================ 正文取用

	/**
	 * 拿到这条规则要注入的正文：内联的直接给，外链的先看缓存、再按镜像顺序拉。
	 *
	 * <p>必须在非主线程调用（外链那条会走网络）。镜像表由调用方传进来：
	 * 这张表{@code CloudCatalog} 已经有一份，纯逻辑层不抄第二份，桌面断言里也好塞假镜像。
	 *
	 * @param roots 镜像根顺序（{@code CloudCatalog.mirrorRoots()}）
	 * @param branch 配置仓分支名（{@code CloudCatalog.branch()}）
	 */
	public static Fetched fetch(Adapt a, File cacheDir, String[] roots, String branch) {
		if (a == null) {
			return new Fetched(null, "规则是空的", false);
		}
		if (a.data != null && a.data.length() > 0) {
			return new Fetched(a.data, "内联 " + a.data.length() + " 字", false);
		}
		if (a.url.length() == 0) {
			return new Fetched(null, "这条既没有内联正文也没有 url", false);
		}
		String why = "";
		for (String u : candidates(a.url, roots, branch)) {
			Fetched f = httpGet(u, a, cacheDir);
			if (f.body != null) {
				return f;
			}
			why = why.length() == 0 ? f.note : why + "；" + f.note;
		}
		return new Fetched(null, "正文没拿到：" + why, false);
	}

	/**
	 * 同一条正文的候选地址：原地址打头，再把镜像顺序里能对上的换进来。
	 *
	 * <p>换算是按「仓内相对路径」做的：{@code …/main/adapt/x.js} 与 {@code …/gh/仓库@main/adapt/x.js}
	 * 指的是同一个文件，所以只要认出分支标记后面的那段就能跨镜像。
	 */
	public static List<String> candidates(String url, String[] roots, String branch) {
		List<String> out = new ArrayList<>();
		if (url == null || url.length() == 0) {
			return out;
		}
		out.add(url);
		String b = branch == null || branch.length() == 0 ? "main" : branch;
		String rel = repoRel(url, b);
		if (rel == null) {
			return out;                     // 不是 GitHub 仓库里的文件，原地址就是唯一候选
		}
		if (roots != null) {
			for (String root : roots) {
				if (root == null || !root.endsWith(b + "/")) {
					continue;
				}
				String alt = root + rel;
				if (!out.contains(alt)) {
					out.add(alt);
				}
			}
		}
		return out;
	}

	/** 取分支标记之后那段仓内路径；认不出来返回 null。 */
	static String repoRel(String url, String branch) {
		if (url == null || branch == null || branch.length() == 0) {
			return null;
		}
		int a = url.indexOf("/" + branch + "/");
		if (a >= 0) {
			return url.substring(a + branch.length() + 2);
		}
		int b = url.indexOf("@" + branch + "/");
		if (b >= 0) {
			return url.substring(b + branch.length() + 2);
		}
		return null;
	}

	private static Fetched httpGet(String url, Adapt a, File cacheDir) {
		String name = shortHost(url);
		// 先看缓存：正文按 sha256 命名，命中就意味着内容和作者签名时一致，断网也能注
		File hit = cacheFile(cacheDir, a.sha);
		if (hit != null && hit.isFile()) {
			byte[] b = readAll(hit, BODY_CAP);
			if (b != null && sha256Hex(b).equals(a.sha)) {
				return new Fetched(text(b), "命中本机缓存（" + name + "，" + b.length + " 字节）", true);
			}
		}
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(8000);
			c.setReadTimeout(12000);
			c.setRequestProperty("User-Agent", "MinewaysMobile/5.14 adapt-fetch");
			c.setInstanceFollowRedirects(true);
			int code = c.getResponseCode();
			if (code < 200 || code >= 300) {
				return new Fetched(null, name + " HTTP " + code);
			}
			byte[] b = readBody(c, BODY_CAP);
			if (b == null) {
				return new Fetched(null, name + " 正文超过上限 " + BODY_CAP + " 字节");
			}
			String got = sha256Hex(b);
			if (!got.equals(a.sha)) {
				return new Fetched(null, name + " sha256 对不上（期望 " + head(a.sha) + "，实得 " + head(got)
						+ "），这条不注入");
			}
			if (a.size > 0 && a.size != b.length) {
				// 声明的字节数只是作者给自己看的备注，摘要对得上才是硬道理
				String note = name + " 正文 " + b.length + " 字节，与声明的 size=" + a.size + " 不一致（照用）";
				cache(hit, b);
				return new Fetched(text(b), note);
			}
			cache(hit, b);
			return new Fetched(text(b), "已从 " + name + " 取到 " + b.length + " 字节");
		} catch (Exception e) {
			return new Fetched(null, name + " " + e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : "：" + e.getMessage()));
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}

	private static File cacheFile(File dir, String sha) {
		if (dir == null || sha == null || sha.length() != 64) {
			return null;
		}
		if (!dir.isDirectory() && !dir.mkdirs()) {
			return null;
		}
		return new File(dir, sha + ".txt");
	}

	private static void cache(File f, byte[] b) {
		if (f == null) {
			return;
		}
		File tmp = new File(f.getAbsolutePath() + ".part");
		try (FileOutputStream os = new FileOutputStream(tmp)) {
			os.write(b);
			os.flush();
		} catch (Exception e) {
			return;
		}
		// 原子改名：中途被杀进程不会留下半个文件，下次读还是重新拉
		if (!tmp.renameTo(f)) {
			try {
				FileOutputStream os = new FileOutputStream(f);
				os.write(b);
				os.close();
			} catch (Exception ignore) {
				// 缓存写不进去不影响这一轮注入
			}
			tmp.delete();
		}
	}

	private static byte[] readAll(File f, int cap) {
		try (InputStream in = new java.io.FileInputStream(f)) {
			return pump(in, cap);
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] readBody(HttpURLConnection c, int cap) throws Exception {
		InputStream in = null;
		try {
			in = c.getInputStream();
			return pump(in, cap);
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception ignore) {
					// 收尾失败不改变结论
				}
			}
		}
	}

	/** 读到上限就返回 null（多读一个字节确认真的到头，避免恰好等于上限时误判）。 */
	private static byte[] pump(InputStream in, int cap) throws Exception {
		ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
		byte[] buf = new byte[8192];
		int n;
		int total = 0;
		while ((n = in.read(buf)) > 0) {
			total += n;
			if (total > cap) {
				return null;
			}
			bo.write(buf, 0, n);
		}
		return bo.toByteArray();
	}

	private static String text(byte[] b) {
		String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
		// UTF-8 BOM 会被当成一个不可见字符注进页面，直接吃掉
		return s.length() > 0 && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
	}

	// ============================================================ 分辨：这一页原版适配手机吗

	/**
	 * 在页面里就地量一遍，返回一段 JSON：它自己有没有声明 viewport、正文宽度、
	 * 超出屏幕多少个元素、最宽的六个是谁（带选择器，写重构规则就照这些名字）。
	 *
	 * <p>这是"分辨"的设备侧依据：静态抓取看不到脚本渲染出来的真实宽度，也只有在这里
	 * 能分清"原版就适配"和"声明了 viewport 但一屏装不下"。
	 */
	public static final String DETECT_JS =
			"(function(){try{"
			+ "var d=document,vw=window.innerWidth||0,"
			+ "sw=Math.max(d.documentElement?d.documentElement.scrollWidth:0,d.body?d.body.scrollWidth:0),"
			+ "vp=!!d.querySelector('meta[name=viewport]'),all=d.getElementsByTagName('*'),"
			+ "over=0,fixed=0,wide=[],seen={},i,e,r,sel;"
			+ "for(i=0;i<all.length;i++){e=all[i];"
			+ "if(!e.getBoundingClientRect){continue;}"
			+ "r=e.getBoundingClientRect();"
			+ "if(vw&&r.right>vw+2){over++;}"
			+ "if(r.width>vw+2&&vw){"
			+ "sel=e.tagName.toLowerCase();"
			+ "if(e.id){sel=sel+'#'+e.id;}"
			+ "else if(e.className&&String(e.className).length<60&&String(e.className).indexOf(' ')<0){sel=sel+'.'+e.className;}"
			+ "if(!seen[sel]){seen[sel]=1;wide.push([sel,Math.round(r.width)]);}"
			+ "}}"
			+ "wide.sort(function(a,b){return b[1]-a[1];});"
			+ "return JSON.stringify({vp:vp,vw:vw,sw:sw,over:over,fixed:fixed,"
			+ "wide:wide.slice(0,6),ready:d.readyState});"
			+ "}catch(err){return JSON.stringify({vp:false,vw:0,sw:0,over:0,fixed:0,wide:[],err:String(err)}});}})()";

	/** 分辨结果（{@link #verdict(String)} 的取值）。 */
	public static final String ADAPTED = "adapted";
	public static final String FAKE = "fake";
	public static final String DESKTOP = "desktop";
	public static final String UNKNOWN = "unknown";

	/**
	 * 把 {@link #DETECT_JS} 量到的 JSON 判成一句结论。
	 *
	 * <ul>
	 *   <li>{@link #DESKTOP} —— 没声明 viewport：原版就是桌面页，必须重构；</li>
	 *   <li>{@link #FAKE} —— 声明了 viewport 但仍有元素超出屏幕：原版半吊子，要针对那几个容器补；</li>
	 *   <li>{@link #ADAPTED} —— 声明了 viewport 且不溢出：<b>原版就适配，一条都别注</b>；</li>
	 *   <li>{@link #UNKNOWN} —— 没量到（页面还没好 / JSON 坏了），别乱判。</li>
	 * </ul>
	 */
	public static String verdict(String json) {
		if (json == null || json.length() == 0) {
			return UNKNOWN;
		}
		String s = json.trim();
		if (s.startsWith("\"") && s.endsWith("\"")) {
			// evaluateJavascript 把返回值再编码一层，对象会变成字符串
			s = s.substring(1, Math.max(1, s.length() - 1)).replace("\\\"", "\"").replace("\\\\", "\\");
		}
		boolean vp = s.contains("\"vp\":true");
		int over = intAfter(s, "\"over\":");
		int vw = intAfter(s, "\"vw\":");
		int sw = intAfter(s, "\"sw\":");
		if (!s.contains("\"vp\":")) {
			return UNKNOWN;
		}
		if (!vp) {
			return DESKTOP;
		}
		if (over > 0 || (vw > 0 && sw > vw + 8)) {
			return FAKE;
		}
		return ADAPTED;
	}

	private static int intAfter(String s, String key) {
		int a = s.indexOf(key);
		if (a < 0) {
			return 0;
		}
		int i = a + key.length();
		int n = 0;
		while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
			n = n * 10 + (s.charAt(i) - '0');
			i++;
		}
		return n;
	}

	/**
	 * 一条规则的指纹，给 {@code once} 记账用：范围 + 正文（外链用 sha）一起算，
	 * 所以作者改了正文或改了 {@code path=}，指纹就变，那一条会再注一次 —— 不会"改配置改不动"。
	 */
	public static String fingerprint(FeatureCatalog.Adapt a) {
		if (a == null) {
			return "none";
		}
		String body = a.data != null && a.data.length() > 0
				? sha256Hex(a.data.getBytes(java.nio.charset.StandardCharsets.UTF_8))
				: (a.sha.length() > 0 ? a.sha : "");
		return sha256Hex((a.kind + "|" + a.scope + "|" + a.host + a.path + "|" + body)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
	}

	/** 给人看的一句话（诊断芯片与状态面板共用）。 */
	public static String verdictText(String json) {
		String v = verdict(json);
		if (ADAPTED.equals(v)) {
			return "原版已适配手机（有 viewport，无溢出）——不需要重构";
		}
		if (FAKE.equals(v)) {
			return "原版假适配（声明了 viewport 但仍有元素超出屏幕）——要针对容器补";
		}
		if (DESKTOP.equals(v)) {
			return "原版是桌面版（没声明 viewport）——必须重构布局";
		}
		return "还没量到（页面没加载完）";
	}

	// ============================================================ 摘要 / 描述

	/** 这条规则的正文是不是已经在本机缓存里（界面用它区分"离线可用"和"还得等网"）。 */
	public static boolean cached(Adapt a, File cacheDir) {
		if (a == null || a.data != null && a.data.length() > 0) {
			return true;
		}
		File f = cacheFile(cacheDir, a.sha);
		return f != null && f.isFile();
	}

	/** 状态面板/诊断芯片上的一行话：谁在改、改哪页、正文哪来的、多大。 */
	public static String describe(Adapt a) {
		if (a == null) {
			return "";
		}
		String where = "*".equals(a.host) ? "所有网页" : a.host + (a.path.length() == 0 ? "" : a.path);
		String src = a.data != null && a.data.length() > 0
				? "内联 " + a.data.length() + " 字"
				: "外链 " + shortHost(a.url) + "（sha " + head(a.sha) + "）";
		return a.kind + " " + a.id + " 作用=" + where + " order=" + a.order + " 正文=" + src
				+ " 出处=" + a.file + " L" + a.line;
	}

	private static String head(String s) {
		if (s == null || s.length() == 0) {
			return "-";
		}
		return s.length() <= 8 ? s : s.substring(0, 8);
	}

	private static String shortHost(String url) {
		if (url == null || url.length() == 0) {
			return "-";
		}
		try {
			String h = new URL(url).getHost();
			return h == null || h.length() == 0 ? url : h;
		} catch (Exception e) {
			return url;
		}
	}

	static String sha256Hex(byte[] b) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return hex(md.digest(b == null ? new byte[0] : b));
		} catch (Exception e) {
			return "";
		}
	}

	private static String hex(byte[] raw) {
		StringBuilder sb = new StringBuilder(raw.length * 2);
		for (byte x : raw) {
			String h = Integer.toHexString(x & 0xFF);
			if (h.length() < 2) {
				sb.append('0');
			}
			sb.append(h);
		}
		return sb.toString();
	}
}
