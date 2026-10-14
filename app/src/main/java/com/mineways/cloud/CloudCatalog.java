package com.mineways.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 云端功能目录的拉取侧：四路镜像链 → 验签解析 → 落 last-good 缓存 → 交给 {@link FeatureGate}。
 *
 * <p>三级兜底（顺序固定）：<b>本次拉取</b> → <b>上次有效缓存</b> → <b>内置默认</b>。
 * 任何一步失败都只是"回退上一层"，绝不出现"配置拉不到就把功能全锁了"。
 * 拉取、解析都在后台线程，主线程只读 {@link #gate()} 的结果。
 *
 * <p>开关：设置里关掉云端配置后，{@link #gate()} 直接返回一个未启用云端的结果，
 * 所有查询退回内置默认（相当于一键回工厂）。
 */
public final class CloudCatalog {

	private static final String TAG = "CloudCatalog";
	private static final String REPO = "151shi23/AshenFlameFoundry";
	private static final String BRANCH = "main";
	/** 配置目录：里面是一份 index.txt 清单 + 各版本配置文件（文件名就是版本号，如 2.1.0.txt）。 */
	private static final String DIR = "config";
	private static final String MANIFEST = "index.txt";

	/** 四路镜像（与 ovo.txt 同一条链），加【镜像序号】就能拼出目录里任意文件的地址。 */
	private static String[] mirrorRoots() {
		return new String[]{
				"https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/",
				"https://ghproxy.net/https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/",
				"https://raw.gitmirror.com/" + REPO + "/" + BRANCH + "/",
				"https://cdn.jsdelivr.net/gh/" + REPO + "@" + BRANCH + "/",
		};
	}

	private static String urlOf(String relPath, int mirror) {
		return mirrorRoots()[mirror] + DIR + "/" + relPath;
	}

	/**
	 * 作者密钥（HMAC-SHA256）。
	 * 说明白：密钥在包里，能挡住"拿到文件改两个字再放回去"这种篡改与镜像投毒，
	 * 挡不住铁了心的逆向（没有任何纯客户端方案挡得住）。真正的红线是——配置只吃数据，
	 * 永远不下发可执行代码，所以最坏情况也只是"多一个网站入口 / 少一个入口"。
	 */
	private static final String KEY_HEX = "6d696e65776179732d636c6f75642d636174616c6f672d6b31";

	private static final String PREFS = "cloud_catalog";
	private static final String K_ENABLED = "enabled";
	private static final String K_CACHE = "cache.txt";
	private static final String K_SOURCE = "source";
	private static final String K_TIME = "time";

	private static final long TTL_MS = 6L * 60 * 60 * 1000; // 6 小时

	private static volatile FeatureGate gate = new FeatureGate(null, false);
	private static volatile String status = "尚未拉取";
	private static volatile String lastGoodText = null;
	private static volatile long lastGoodAt = 0L;
	private static volatile boolean fetching = false;

	private CloudCatalog() {
	}

	public static FeatureGate gate() {
		return gate;
	}

	/** 给设置页看的一行状态：来源镜像 / 时间 / 生效与跳过条数 / 拒绝原因。 */
	public static String status() {
		return status;
	}

	public static boolean isCloudEnabled(Context c) {
		return prefs(c).getBoolean(K_ENABLED, true);
	}

	public static void setCloudEnabled(Context c, boolean on) {
		prefs(c).edit().putBoolean(K_ENABLED, on).apply();
		if (!on) {
			gate = new FeatureGate(null, false);
			status = "云端配置已关闭（按内置默认运行）";
		}
	}

	/** 启动时调一次：先用缓存立刻可用，再后台刷新。 */
	public static void init(final Context ctx) {
		final Context app = ctx.getApplicationContext();
		if (!isCloudEnabled(app)) {
			status = "云端配置已关闭（按内置默认运行）";
			return;
		}
		String cached = readCache(app);
		if (cached != null && cached.length() > 0) {
			FeatureCatalog.Result r = FeatureCatalog.parse(cached, versionCode(app), deviceId(app), key(), evaluator(app));
			if (r.accepted) {
				applyTo(app, r, "缓存", prefs(app).getString(K_TIME, null));
			}
		}
		if (System.currentTimeMillis() - lastGoodAt > TTL_MS) {
			refresh(app);
		}
	}

	/** 强制拉一次（设置页"立即刷新"用）。 */
	public static void refresh(final Context ctx) {
		if (fetching) {
			return;
		}
		fetching = true;
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					String text = null;
					String from = null;
					for (String url : SOURCES) {
						String body = httpGet(url);
						if (body != null && body.trim().length() > 0) {
							text = body;
							from = hostOf(url);
							break;
						}
					}
					if (text == null) {
						status = "拉取失败：四个镜像都没拿到"
								+ (lastGoodText != null ? "（继续用上次有效配置）" : "（按内置默认运行）");
						return;
					}
					FeatureCatalog.Result r = FeatureCatalog.parse(text, versionCode(ctx), deviceId(ctx), key(), evaluator(ctx));
					if (!r.accepted) {
						status = "配置被拒绝（" + r.rejectReason + "）"
								+ (lastGoodText != null ? "，继续用上次有效配置" : "，按内置默认运行");
						return;
					}
					writeCache(ctx, text);
					prefs(ctx).edit()
							.putString(K_SOURCE, from)
							.putString(K_TIME, now())
							.apply();
					applyTo(ctx, r, from, now());
				} catch (Throwable t) {
					Log.w(TAG, "refresh failed", t);
					status = "拉取异常：" + t.getClass().getSimpleName()
							+ (lastGoodText != null ? "（继续用上次有效配置）" : "（按内置默认运行）");
				} finally {
					fetching = false;
				}
			}
		}, "cloud-catalog").start();
	}

	private static void applyTo(Context c, FeatureCatalog.Result r, String from, String at) {
		gate = new FeatureGate(r, isCloudEnabled(c));
		if (r.issues.isEmpty()) {
			status = "生效中｜来源 " + from + "｜" + (at == null ? "" : at)
					+ "｜版本 " + r.protocolVersion + "｜灰度桶 " + r.bucket
					+ "｜生效 " + r.applied + " 条";
		} else {
			StringBuilder sb = new StringBuilder();
			sb.append("生效中｜来源 ").append(from).append("｜").append(at == null ? "" : at)
					.append("｜生效 ").append(r.applied).append(" 条，跳过 ").append(r.skipped).append(" 条：");
			int n = Math.min(3, r.issues.size());
			for (int i = 0; i < n; i++) {
				FeatureCatalog.Issue is = r.issues.get(i);
				sb.append(" 第").append(is.line).append("行 ").append(is.reason).append('；');
			}
			if (r.issues.size() > n) {
				sb.append(" 等共 ").append(r.issues.size()).append(" 条");
			}
			status = sb.toString();
		}
	}

	/**
	 * 组合条件的真求值器：复用公告中心的变量表与表达式引擎
	 * （{@code Expr.test}，支持 且/或/非、比较、包含、在…里、函数），
	 * 额外注入 {@code seen_<公告id>=1}，于是"弹过某条公告"也能当条件：
	 * <pre>
	 *   FEAT hide id=文件修复 override=builtin if="version_code >= 520 &amp;&amp; authorized &amp;&amp; seen_ov2026"
	 * </pre>
	 */
	private static FeatureCatalog.CondEval evaluator(final Context ctx) {
		return new FeatureCatalog.CondEval() {
			@Override
			public boolean test(String expr) {
				java.util.Map<String, String> vars =
						new java.util.HashMap<>(com.mineways.AnnouncementCenter.vars(ctx));
				for (String id : com.mineways.AnnouncementCenter.seenIds(ctx)) {
					vars.put("seen_" + id, "1");
				}
				return com.mineways.Expr.test(expr, vars);
			}
		};
	}

	// ============================================================ 基础设施

	private static byte[] key() {
		int n = KEY_HEX.length() / 2;
		byte[] out = new byte[n];
		for (int i = 0; i < n; i++) {
			out[i] = (byte) Integer.parseInt(KEY_HEX.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static SharedPreferences prefs(Context c) {
		return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static String httpGet(String url) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(6000);
			conn.setReadTimeout(8000);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", "MinewaysMobile");
			conn.setRequestProperty("Cache-Control", "no-cache");
			int code = conn.getResponseCode();
			if (code != 200) {
				return null;
			}
			InputStream in = conn.getInputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				bos.write(buf, 0, n);
			}
			in.close();
			return new String(bos.toByteArray(), Charset.forName("UTF-8"));
		} catch (Throwable t) {
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static String readCache(Context c) {
		File f = new File(c.getFilesDir(), K_CACHE);
		if (!f.exists()) {
			return null;
		}
		try {
			byte[] b = new byte[(int) f.length()];
			java.io.FileInputStream in = new java.io.FileInputStream(f);
			int read = in.read(b);
			in.close();
			lastGoodAt = f.lastModified();
			if (read > 0) {
				String text = new String(b, 0, read, Charset.forName("UTF-8"));
				lastGoodText = text;
				return text;
			}
			return null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static void writeCache(Context c, String text) {
		try {
			File f = new File(c.getFilesDir(), K_CACHE);
			FileOutputStream out = new FileOutputStream(f);
			out.write(text.getBytes(Charset.forName("UTF-8")));
			out.close();
			lastGoodAt = System.currentTimeMillis();
			lastGoodText = text;
		} catch (Throwable t) {
			Log.w(TAG, "write cache failed", t);
		}
	}

	private static String hostOf(String url) {
		try {
			return new URL(url).getHost();
		} catch (Throwable t) {
			return url;
		}
	}

	private static String now() {
		return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date());
	}

	private static int versionCode(Context c) {
		try {
			return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
		} catch (Throwable t) {
			return 0;
		}
	}

	/** 灰度分桶用的设备标识；取不到就返回 null（= 全量命中）。 */
	private static String deviceId(Context c) {
		try {
			return android.provider.Settings.Secure.getString(
					c.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
		} catch (Throwable t) {
			return null;
		}
	}
}
