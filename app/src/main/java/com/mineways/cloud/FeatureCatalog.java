package com.mineways.cloud;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 云端功能目录配置（GitHub 上一个文本文件）的解析内核。
 *
 * <p>刻意不依赖任何安卓类：这样它能用 javac + main 直接在开发机上跑断言，
 * 解析器的兜底 / 查重 / 错误隔离 / 灰度 / 篡改校验五条路径都有真测试，
 * 而不是"只能在手机上试"。
 *
 * <p>书写规则（一行一条指令，{@code #} 整行注释，{@code ;} 行内注释，值可用双引号包空格）：
 * <pre>
 * &#64;ver=1                     文件协议版本（不认识的大版本整份拒绝）
 * &#64;strict=0                  1=有任何坏行就整份拒绝；0=坏行跳过并上报（默认）
 * &#64;rollout=50                文件级灰度百分比，与指令级取较小值
 * &#64;minv=522 &#64;maxv=0        文件级 versionCode 区间，0 表示不限
 * &#64;default=apply             文件级生效方式 ask|download|apply
 * &#64;sig=sha256:hex            对本体（去掉所有 &#64; 行、LF 规范化）的摘要
 * &#64;hmac=base64:...           作者密钥签名，缺失或不匹配即判篡改
 *
 * SITE add id=xxx name=名字 cat=影视 url=https://... desc=介绍 icon=... order=10 mode=ask rollout=20 minv=520
 * SITE drop id=xxx
 * CAT  add id=影视 name=影视专区 order=20
 * CAT  drop id=影视
 * FEAT tier id=brand_scrub to=whitelist|limited|normal|free
 * FEAT hide id=xxx / FEAT show id=xxx        入口隐藏（仍可用）
 * FEAT disable id=xxx / FEAT enable id=xxx   功能停用（点了也不给用）
 * WL user add email=a@b.com / WL user drop key=a@b.com   授权用户名单
 * </pre>
 */
public final class FeatureCatalog {

	public static final int PROTOCOL_VERSION = 1;

	/** 档位：白名单（免验证放行）/ 限制（要激活登录配额）/ 普通 / 免费。 */
	public static final String TIER_WHITELIST = "whitelist";
	public static final String TIER_LIMITED = "limited";
	public static final String TIER_NORMAL = "normal";
	public static final String TIER_FREE = "free";
	private static final Set<String> TIERS = new HashSet<>(Arrays.asList(
			TIER_WHITELIST, TIER_LIMITED, TIER_NORMAL, TIER_FREE));

	/** 生效方式。 */
	public static final String MODE_ASK = "ask";
	public static final String MODE_DOWNLOAD = "download";
	public static final String MODE_APPLY = "apply";
	private static final Set<String> MODES = new HashSet<>(Arrays.asList(
			MODE_ASK, MODE_DOWNLOAD, MODE_APPLY));

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

		Site(String id) {
			this.id = id;
		}
	}

	public static final class Category {
		public String id = "";
		public String name = "";
		public int order = 100;
		public int line;
	}

	/** 一条被丢弃/跳过的指令，带行号与原因，供设置页展示。 */
	public static final class Issue {
		public final int line;
		public final String reason;
		public final String raw;

		Issue(int line, String reason, String raw) {
			this.line = line;
			this.reason = reason;
			this.raw = raw;
		}

		@Override
		public String toString() {
			return "L" + line + " " + reason;
		}
	}

	public static final class Result {
		public boolean accepted;
		/** 拒绝原因：TAMPERED / PROTOCOL / EMPTY / STRICT / VERSION / null。 */
		public String rejectReason;
		public int protocolVersion;
		public int rollout = 100;
		public int bucket = 100;
		public int versionCode;
		public final Map<String, Site> sites = new LinkedHashMap<>();
		public final Set<String> droppedSites = new HashSet<>();
		public final Map<String, Category> categories = new LinkedHashMap<>();
		public final Set<String> droppedCategories = new HashSet<>();
		/** featureId -> 档位。 */
		public final Map<String, String> tiers = new HashMap<>();
		public final Set<String> hidden = new HashSet<>();
		public final Set<String> shown = new HashSet<>();
		public final Set<String> disabled = new HashSet<>();
		public final Set<String> enabled = new HashSet<>();
		public final Map<String, String> delivery = new HashMap<>();
		public final Set<String> whitelistAdds = new HashSet<>();
		public final Set<String> whitelistDrops = new HashSet<>();
		/** 显式写了 {@code override=builtin} 的键：只有这些才允许云端改动内置项。 */
		public final Set<String> overrideKeys = new HashSet<>();
		/** 键 -> 提示语（隐藏/停用时给用户看的原因）。 */
		public final Map<String, String> notices = new HashMap<>();
		public final List<Issue> issues = new ArrayList<>();
		public int applied;
		public int skipped;

		public String tierOf(String featureId, String fallback) {
			String t = tiers.get(featureId);
			return t == null ? fallback : t;
		}
	}

	/**
	 * 组合条件的求值接口。真空实现放在安卓侧（复用公告中心那套 {@code Expr} 与变量表），
	 * 于是配置文件与 ovo.txt 用的是同一套条件语法：{@code version_code >= 520 && authorized}
	 * 这种直接可写，维护只需要学一次。
	 *
	 * <p>刻意做成接口：内核不依赖安卓，断言里可以喂一个假求值器，
	 * 把"条件为假就跳过""没有求值器时带条件的指令一律不执行"这两条路径真的测到。
	 */
	public interface CondEval {
		boolean test(String expr);
	}

	private FeatureCatalog() {
	}

	// ============================================================ 入口

	/**
	 * 解析配置正文。
	 *
	 * @param text       文件内容（UTF-8 文本）
	 * @param versionCode 当前 App versionCode，用于版本门
	 * @param deviceId   设备稳定标识（ANDROID_ID 之类），用于灰度分桶；null 视为全量
	 * @param hmacKey    作者密钥；null 表示不启用签名校验（仅限开发期）
	 */
	public static Result parse(String text, int versionCode, String deviceId, byte[] hmacKey) {
		return parse(text, versionCode, deviceId, hmacKey, null);
	}

	/**
	 * 解析配置正文（带组合条件求值器）。
	 *
	 * @param condEval 组合条件求值器；为 null 时带 {@code if=} 的指令一律不执行（安全侧默认）
	 */
	public static Result parse(String text, int versionCode, String deviceId, byte[] hmacKey,
			CondEval condEval) {
		Result r = new Result();
		r.versionCode = versionCode;
		r.bucket = bucketOf(deviceId);
		if (text == null || text.trim().isEmpty()) {
			r.accepted = false;
			r.rejectReason = "EMPTY";
			return r;
		}

		String[] lines = stripBom(text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

		// 第一遍：只吃 @ 头部，决定这份文件到底能不能用。
		boolean strict = false;
		int fileRollout = 100;
		int fileMin = 0;
		int fileMax = 0;
		String fileMode = MODE_APPLY;
		String declaredSig = null;
		String declaredHmac = null;
		for (String rawLine : lines) {
			String line = trimComment(rawLine).trim();
			if (line.length() == 0 || line.charAt(0) != '@') {
				continue;
			}
			Map<String, String> f = parseFields(line.substring(1));
			String key = firstKey(f);
			String val = f.get(key);
			if ("ver".equals(key)) {
				r.protocolVersion = intOf(val, 0);
			} else if ("strict".equals(key)) {
				strict = intOf(val, 0) != 0;
			} else if ("rollout".equals(key)) {
				fileRollout = percentOf(val, 100);
			} else if ("minv".equals(key)) {
				fileMin = intOf(val, 0);
			} else if ("maxv".equals(key)) {
				fileMax = intOf(val, 0);
			} else if ("default".equals(key)) {
				String m = lower(val);
				if (MODES.contains(m)) {
					fileMode = m;
				}
			} else if ("sig".equals(key)) {
				// 摘要与签名都是大小写敏感编码（hex/base64），绝不能顺手 lower()
				declaredSig = val == null ? null : val.trim();
			} else if ("hmac".equals(key)) {
				declaredHmac = val == null ? null : val.trim();
			}
		}

		if (r.protocolVersion > PROTOCOL_VERSION) {
			r.accepted = false;
			r.rejectReason = "PROTOCOL";
			return r;
		}
		if (fileMin > 0 && versionCode < fileMin) {
			r.accepted = false;
			r.rejectReason = "VERSION";
			return r;
		}
		if (fileMax > 0 && versionCode > fileMax) {
			r.accepted = false;
			r.rejectReason = "VERSION";
			return r;
		}

		// 完整性：本体 = 去掉所有 @ 行、LF 规范化后的字节。
		if (hmacKey != null && hmacKey.length > 0) {
			if (declaredHmac == null || declaredHmac.length() == 0) {
				r.accepted = false;
				r.rejectReason = "TAMPERED";
				return r;
			}
			String want = hmac(hex(hmacKey), bodyOf(lines));
			if (!constantTimeEquals(want, stripAlgo(declaredHmac))) {
				r.accepted = false;
				r.rejectReason = "TAMPERED";
				return r;
			}
		}
		if (declaredSig != null && declaredSig.length() > 0) {
			String want = sha256Hex(bodyOf(lines));
			if (!constantTimeEquals(want, stripAlgo(declaredSig))) {
				r.accepted = false;
				r.rejectReason = "TAMPERED";
				return r;
			}
		}

		r.rollout = fileRollout;
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
			int rollout = percentOf(firstVal(f, "rollout"), fileRollout);
			if (rollout < r.bucket) {
				r.skipped++;
				r.issues.add(new Issue(lineNo, "ROLLOUT", line));
				continue;
			}
			int minv = intOf(firstVal(f, "minv"), 0);
			int maxv = intOf(firstVal(f, "maxv"), 0);
			if ((minv > 0 && versionCode < minv) || (maxv > 0 && versionCode > maxv)) {
				r.skipped++;
				r.issues.add(new Issue(lineNo, "VERSION", line));
				continue;
			}
			String mode = lower(firstVal(f, "mode"));
			if (mode.length() == 0) {
				mode = fileMode;
			} else if (!MODES.contains(mode)) {
				bad(r, strict, lineNo, "BAD mode=" + mode, line);
				continue;
			}

			/*
			 * 组合条件：和 ovo.txt 同一套语法（公告中心那个 Expr 引擎），
			 * 因为条件里带空格与符号，写的时候要用双引号包起来：
			 *   FEAT hide id=文件修复 if="version_code >= 520 && authorized"
			 */
			String cond = firstVal(f, "if", "when");
			if (cond.length() > 0) {
				if (condEval == null) {
					bad(r, strict, lineNo, "COND 本端没有求值器，带条件的指令不执行", line);
					continue;
				}
				boolean hit;
				try {
					hit = condEval.test(cond);
				} catch (Throwable t) {
					bad(r, strict, lineNo, "COND 求值异常：" + t.getClass().getSimpleName(), line);
					continue;
				}
				if (!hit) {
					r.skipped++;
					r.issues.add(new Issue(lineNo, "COND 不满足：" + cond, line));
					continue;
				}
			}

			String err = apply(r, verb, f, mode, lineNo, line,
					seenSiteId, seenSiteUrl, seenCatId);
			if (err != null) {
				bad(r, strict, lineNo, err, line);
			} else {
				r.applied++;
			}
		}

		r.accepted = !(strict && !r.issues.isEmpty());
		if (!r.accepted && r.rejectReason == null) {
			r.rejectReason = "STRICT";
		}
		return r;
	}

	private static void bad(Result r, boolean strict, int lineNo, String reason, String raw) {
		r.skipped++;
		r.issues.add(new Issue(lineNo, reason, raw));
		if (strict) {
			r.rejectReason = "STRICT";
		}
	}

	// ============================================================ 单条指令

	private static String apply(Result r, String verb, Map<String, String> f, String mode,
			int lineNo, String raw, Set<String> seenSiteId, Set<String> seenSiteUrl,
			Set<String> seenCatId) {
		String action = lower(firstVal(f, "add", "drop", "tier", "hide", "show", "disable", "enable", "user"));
		String id = lower(firstVal(f, "id", "key"));

		if ("site".equals(verb)) {
			if ("drop".equals(action)) {
				if (id.length() == 0) {
					return "SITE drop 缺 id";
				}
				r.droppedSites.add(id);
				r.sites.remove(id);
				return null;
			}
			if (!"add".equals(action)) {
				return "SITE 需要 add|drop";
			}
			if (id.length() == 0) {
				return "SITE add 缺 id";
			}
			String url = firstVal(f, "url");
			if (!isSafeUrl(url)) {
				return "SITE url 非法（必须 https，且不含空白/引号）";
			}
			if (!seenSiteId.add(id)) {
				return "SITE id 重复：" + id;
			}
			String normUrl = normalizeUrl(url);
			if (!seenSiteUrl.add(normUrl)) {
				return "SITE url 重复：" + normUrl;
			}
			Site s = new Site(id);
			s.line = lineNo;
			s.url = url.trim();
			s.name = orDefault(firstVal(f, "name", "名"), id);
			s.category = lower(firstVal(f, "cat", "category", "类别"));
			s.desc = firstVal(f, "desc", "介绍");
			s.icon = firstVal(f, "icon");
			s.order = intOf(firstVal(f, "order"), 100);
			s.mode = mode;
			r.sites.put(id, s);
			r.delivery.put(id, mode);
			if (s.category.length() > 0 && !seenCatId.contains(s.category)) {
				// 站点自带新类别时先登记一个占位类别，避免"类别不存在"把站点丢掉。
				Category c = new Category();
				c.id = s.category;
				c.name = s.category;
				c.line = lineNo;
				r.categories.put(c.id, c);
			}
			return null;
		}

		if ("cat".equals(verb)) {
			if ("drop".equals(action)) {
				if (id.length() == 0) {
					return "CAT drop 缺 id";
				}
				r.droppedCategories.add(id);
				r.categories.remove(id);
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
			Category c = new Category();
			c.id = id;
			c.name = orDefault(firstVal(f, "name", "名"), id);
			c.order = intOf(firstVal(f, "order"), 100);
			c.line = lineNo;
			r.categories.put(id, c);
			return null;
		}

		if ("feat".equals(verb)) {
			if (id.length() == 0) {
				return "FEAT 缺 id";
			}
			if ("builtin".equals(lower(firstVal(f, "override")))) {
				r.overrideKeys.add(id);
			}
			String notice = firstVal(f, "notice", "提示");
			if (notice.length() > 0) {
				r.notices.put(id, notice);
			}
			if ("tier".equals(action)) {
				String to = lower(firstVal(f, "to"));
				if (!TIERS.contains(to)) {
					return "FEAT tier 取值必须是 " + TIERS;
				}
				r.tiers.put(id, to);
				if (TIER_WHITELIST.equals(to)) {
					r.enabled.add(id);
				}
				return null;
			}
			if ("hide".equals(action)) {
				r.hidden.add(id);
				return null;
			}
			if ("show".equals(action)) {
				r.shown.add(id);
				return null;
			}
			if ("disable".equals(action)) {
				r.disabled.add(id);
				return null;
			}
			if ("enable".equals(action)) {
				r.enabled.add(id);
				return null;
			}
			return "FEAT 动作不认识：" + action;
		}

		if ("wl".equals(verb)) {
			String key = lower(firstVal(f, "email", "user", "key", "id"));
			if (key.length() == 0) {
				return "WL 缺 email/key";
			}
			if ("drop".equals(action)) {
				r.whitelistDrops.add(key);
				r.whitelistAdds.remove(key);
				return null;
			}
			if (!"add".equals(action)) {
				return "WL 需要 add|drop";
			}
			r.whitelistAdds.add(key);
			r.whitelistDrops.remove(key);
			return null;
		}

		return "指令不认识：" + verb;
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

	/** 本体字节：所有非 @ 行、LF 规范化、去掉行尾空白。签名与摘要都算这个。 */
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

	/** 给作者工具用：算出该写进 @sig / @hmac 的值。 */
	public static String signBody(String text, byte[] key) {
		String body = bodyOf(stripBom(text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
		return key == null ? "sha256:" + sha256Hex(body) : "base64:" + hmac(hex(key), body);
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

	/** key=value 序列，值支持双引号；裸词（如 {@code add}）记成 {@code <裸词>=true}。 */
	private static Map<String, String> parseFields(String s) {
		Map<String, String> out = new LinkedHashMap<>();
		if (s == null) {
			return out;
		}
		int i = 0;
		int n = s.length();
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
				out.put(lower(token), val.toString());
			} else if (token.length() > 0) {
				out.put(lower(token), "true");
			}
		}
		return out;
	}

	private static String firstKey(Map<String, String> f) {
		for (String k : f.keySet()) {
			return k;
		}
		return "";
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
		return s != null && s.startsWith("﻿") ? s.substring(1) : s;
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
