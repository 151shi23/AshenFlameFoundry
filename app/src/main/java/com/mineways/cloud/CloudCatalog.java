package com.mineways.cloud;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.mineways.AnnouncementCenter;
import com.mineways.Expr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 云端功能目录的拉取侧。
 *
 * <p>目录结构（GitHub 仓库 {@code 151shi23/AshenFlameFoundry} 的 {@code config/}）：
 * <pre>
 *   config/index.txt    清单，每行「版本号  空白  sha256」
 *   config/3.0.0.txt    本体，文件名就是版本号；正文里有 @hmac 作者签名
 * </pre>
 *
 * <p>每次启动：拉清单 → 本地那份的 sha256 必须等于清单里对应版本那一行（对不上就删掉、
 * 回退内置默认）→ 清单里版本更大就下载 → 与清单比哈希 → 验签 → 解析 → 生效。
 * 三级兜底：本次拉取 → 上次已核对 → 内置默认。离线时用"上次已核对"的那份。
 *
 * <p>界面配合：{@code init} 在 Application 里调用（先于一切界面）；生效了新版本后
 * {@link #appliedVersion()} 会变，{@code MainActivity.onResume} 拿它和自己建列表时的版本比对，
 * 不一致就重建 —— 不依赖"拉取恰好赶在界面活着的时候完成"。
 *
 * <p>安全：清单哈希挡"文件被动过"（含手机本地被改），正文 @hmac 挡"镜像投毒"，
 * 版本号只升不降挡"拿旧文件冒充新的"。密钥在包里，挡篡改不挡逆向——
 * 真正的红线是这套东西只吃数据、永远不下发可执行代码。
 */
public final class CloudCatalog {

	private static final String TAG = "CloudCatalog";
	private static final String REPO = "151shi23/AshenFlameFoundry";
	private static final String BRANCH = "main";
	private static final String DIR = "config";
	private static final String MANIFEST = "index.txt";

	/** 作者密钥（HMAC-SHA256）。与 tools/sign-features.py 的 KEY_HEX 必须一致。 */
	private static final String KEY_HEX = "6d696e65776179732d636c6f75642d636174616c6f672d6b31";

	private static final String PREFS = "cloud_catalog";
	private static final String K_VERSION = "version";
	private static final String K_SHA = "sha";
	private static final String K_SOURCE = "source";
	private static final String K_TIME = "time";
	private static final String K_VERIFIED = "verified";
	/** 作用域链：{@code 2.1.0;3.0.0} 这样按版本从旧到新排。 */
	private static final String K_CHAIN = "chain";
	/** 链上每一份的哈希备份：{@code 2.1.0:sha;3.0.0:sha}，离线时也要能自检。 */
	private static final String K_CHAIN_SHA = "chain_sha";
	/**
	 * 「清单这一轮没列链尾那份」的连击计数：{@code 3.9.17:1}。
	 * index.txt 只有 3 KB，raw / jsdelivr / ghproxy 三家的边缘缓存不同步是常态 —— 读到一份旧副本
	 * 不能就当作者撤回了，否则重启就会把刚生效的最新版踢掉、退回上一版（他报的就是这个）。
	 * 连续两轮都没有才按撤回处理。
	 */
	private static final String K_MISS = "manifest_miss";
	/** 链最长留几份：兜底，避免几年后 filesDir 里堆满没人读的文件。 */
	private static final int MAX_CHAIN = 8;

	/**
	 * 链上文件在本机的总体积预算。配置是一版版累积的，就算压过，八份全量快照照样能吃掉十几兆，
	 * 所以链不是越长越好：<b>最新那份永远保留</b>，其余从新到旧按这份预算取舍，超预算的最老几份直接删。
	 */
	private static final long CHAIN_BUDGET_BYTES = 6L * 1024L * 1024L;

	/**
	 * 镜像顺序：<b>jsdelivr 打头</b>（国内直连最稳，也是 {@code K_SOURCE} 记的「默认源」），
	 * raw 第二、ghproxy 兜底 —— raw.githubusercontent 在国内经常直接超时，把它放第一位等于
	 * 每次拉取先陪它等一轮。jsdelivr 会缓存 {@code index.txt} 的问题，由
	 * {@link #fetchWithMirrors} 给清单加的时间戳破缓存解决。
	 */
	private static final String[] MIRROR_ROOTS = {
			"https://cdn.jsdelivr.net/gh/" + REPO + "@" + BRANCH + "/",
			"https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/",
			"https://ghproxy.net/https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/",
	};
	private static final String[] MIRROR_NAMES = {"jsdelivr", "raw", "ghproxy"};

	private static volatile FeatureGate gate = new FeatureGate(null, false);
	private static volatile String status = "尚未拉取";
	private static volatile String appliedVersion = "";
	private static volatile int appliedRevision = 0;
	private static volatile boolean started = false;
	private static volatile boolean fetching = false;
	private static volatile Activity currentActivity;
	/** 最近一次下发的搬运方式：走了压缩就报省了多少，回退到明文的必须把原因留在状态里。 */
	private static volatile String transferNote = "";
	private static volatile int wireBytes = 0;
	private static volatile int plainBytes = 0;
	/** 本机就地压缩累计腾出来的字节数（老版本配置文件腾地方，内容不改一字）。 */
	private static volatile long compactedBytes = 0L;
	/** 删旧版本腾出来的字节与一句话说明（链太长时按快照声明/体积预算删最老的几份）。 */
	private static volatile long droppedBytes = 0L;
	private static volatile String pruneNote = "";
	/** 记住 application context：状态面板随时要读链，不该再传一遍 Context。 */
	private static volatile Context lastApp;

	private CloudCatalog() {
	}

	// ============================================================ 对外接口

	public static FeatureGate gate() {
		return gate;
	}

	public static String status() {
		return status;
	}

	/** 展示栏视频（VIDEO add，云控下发）：每项 {键, 名称, 播放页地址}。 */
	public static java.util.List<String[]> uiVideos() {
		FeatureGate g = gate;
		return g == null ? new java.util.ArrayList<>() : g.videoList();
	}

	/** 已生效的配置版本，空串表示还没生效过任何云端配置。 */
	public static String appliedVersion() {
		return appliedVersion;
	}

	/**
	 * 生效次数。界面拿它做兜底：**同一个版本号被重新下发（内容改了、版本没动）**时
	 * 版本号比不出来，靠这个计数才能发现"配置换过了"，从而重建界面。
	 */
	public static int appliedRevision() {
		return appliedRevision;
	}

	/**
	 * 最近一次下发的搬运方式（"gzip｜压缩 680 KB，还原后 2236 KB，省下 70%" 这类），
	 * 回退到明文时原因也在这里 —— 「关于」页要能一眼看出这版是怎么拿到的。
	 */
	public static String transferNote() {
		return transferNote;
	}

	/** 实际走了多少网络字节 / 还原后的正文字节（0 表示还没下过）。 */
	public static int wireBytes() {
		return wireBytes;
	}

	public static int plainBytes() {
		return plainBytes;
	}

	/** 本次运行里，本机把多少明文配置就地压掉了（腾出来的空间）。 */
	public static long compactedBytes() {
		return compactedBytes;
	}

	/** 删掉旧版本份数说明与腾出的字节数（链按快照声明/体积预算收短时才非空）。 */
	public static String pruneNote() {
		return pruneNote;
	}

	public static long droppedBytes() {
		return droppedBytes;
	}

	private static void markApplied(String version) {
		appliedVersion = version;
		appliedRevision++;
	}

	/** 由 Application 的回调登记当前前台 Activity：提示与界面重建要挂在它上面（下载不挂它）。 */
	public static void setUi(Activity activity) {
		currentActivity = activity;
	}

	/**
	 * 下载进度状态。
	 *
	 * <p>刻意做成"线程只写、界面自己读"的纯状态：进度不再依赖某个 Activity 的窗口，
	 * 所以退后台、Activity 重建、旋转都不会打断下载 —— 回前台时界面读一次这个对象就能接上。
	 * 以前用的是挂在 Activity 上的 {@code ProgressDialog}，一退后台窗口所在的 Activity 就没了，
	 * 线程里那些 UI 调用全部失效，看起来就像"下载停了"。
	 */
	public static final class Progress {
		public boolean active;
		public int done;
		/** 总字节；-1 表示服务端没给长度（gzip），界面应显示不确定进度。 */
		public int total = -1;
		public String version = "";
		public String mirror = "";
		public String text = "";

		Progress copy() {
			Progress p = new Progress();
			p.active = active;
			p.done = done;
			p.total = total;
			p.version = version;
			p.mirror = mirror;
			p.text = text;
			return p;
		}
	}

	/** 界面侧的订阅者（由 MainActivity 设置；回调可能在后台线程上，需要自己切主线程）。 */
	public interface ProgressListener {
		void onProgress(Progress p);
	}

	private static final Progress PROGRESS = new Progress();
	private static volatile ProgressListener progressListener;

	/** 当前下载状态（返回副本，随便界面怎么读）。 */
	public static synchronized Progress progress() {
		return PROGRESS.copy();
	}

	public static void setProgressListener(ProgressListener listener) {
		progressListener = listener;
	}

	/** 改状态并通知订阅者。通知放在锁外面做：界面回调里很可能马上又读 progress()，别互相等。 */
	private static void publish(boolean active, String text) {
		Progress snapshot;
		synchronized (PROGRESS) {
			PROGRESS.active = active;
			if (text != null) {
				PROGRESS.text = text;
			}
			snapshot = PROGRESS.copy();
		}
		ProgressListener l = progressListener;
		if (l != null) {
			try {
				l.onProgress(snapshot);
			} catch (Throwable ignored) {
				// 界面没了或正在重建，都不影响下载本身继续跑
			}
		}
	}

	private static void begin(String version) {
		synchronized (PROGRESS) {
			PROGRESS.version = version;
			PROGRESS.done = 0;
			PROGRESS.total = -1;
			PROGRESS.mirror = "";
		}
		publish(true, "正在准备获取 " + version + "…");
	}

	private static void end(String text) {
		publish(false, text);
		synchronized (PROGRESS) {
			PROGRESS.done = 0;
			PROGRESS.total = -1;
		}
	}

	/**
	 * 启动时调一次（在 Application.onCreate 里）。之后 Activity 再调只会登记 Activity 并
	 * 清掉可能残留的进度条，不会重复拉取。
	 */
	public static void init(Context context) {
		final Context app = context.getApplicationContext();
		lastApp = app;
		if (context instanceof Activity) {
			currentActivity = (Activity) context;
		}
		if (started) {
			return;
		}
		started = true;
		loadVerifiedLocal(app);
		refresh(app);
	}

	/** Application 上下文（{@code init} 之后一定拿得到）；给没有 Activity 的取值口用。 */
	public static Context app() {
		return lastApp;
	}

	/**
	 * 云端配置<b>永远是开的</b>：用户侧不能关闭，也不能清除。
	 *
	 * <p>以前有个「关闭云控」开关和一份 {@code enabled} 偏好，等于给了一个绕过远端规则的口子 ——
	 * 关掉之后下架的站点会自己冒回来、该停用的功能照样能用，配置里那些 hide/disable 就成了摆设。
	 * 保留这个方法只为状态面板能说明这一点；不要再往回加开关。
	 */
	public static boolean isCloudEnabled(Context context) {
		return true;
	}

	/** 上一次发起拉取的时刻，用于回前台补跑时别刷屏。 */
	private static volatile long lastAttemptAt = 0L;

	/**
	 * 回前台时补一趟。进程被系统回收过的话，那条后台线程是真的没了，界面上看起来就是"下载停了"；
	 * 这里在<b>还没拿到任何配置</b>且距上次尝试超过 60 秒时重新发起，拿到过就不再折腾。
	 */
	public static void resumeIfNeeded(Context context) {
		if (!started || fetching) {
			return;
		}
		if (appliedVersion.length() > 0) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastAttemptAt < 60_000L) {
			return;
		}
		refresh(context);
	}

	/** 强制拉一次（"立即刷新"用）。线程只写状态，不碰任何窗口，所以退后台也不会中断。 */
	public static void refresh(final Context context) {
		if (fetching) {
			publish(true, null);        // 已经有一趟在跑了，让界面把当前进度接上显示
			return;
		}
		fetching = true;
		lastAttemptAt = System.currentTimeMillis();
		final Context app = context.getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					fetchOnce(app);
				} catch (Throwable t) {
					status = "拉取异常：" + t.getClass().getSimpleName();
					toast("云端配置异常：" + t.getClass().getSimpleName());
					Log.w(TAG, "refresh failed", t);
				} finally {
					fetching = false;
					// 不管走到哪条出口，都必须收尾，否则顶部横幅会一直转
					end(status);
				}
			}
		}, "cloud-catalog").start();
	}

	// ============================================================ 主流程

	private static void fetchOnce(Context app) {
		// 1) 拉清单（每次启动都拉，这是唯一能"撤回"一份已发布配置的机制）
		String manifest = fetchWithMirrors(MANIFEST, null);
		if (manifest == null) {
			status = "清单拉取失败" + (hasVerifiedLocal(app) ? "（继续用上次已核对的配置）" : "（按内置默认运行）");
			return;
		}
		Map<String, String> index = parseManifest(manifest);
		if (index.isEmpty()) {
			status = "清单里没有有效记录，已忽略";
			return;
		}
		Map<String, String> gzIndex = CloudCodec.manifestGz(manifest);

		// 2) 链上每一份都要先过「哈希等于清单」这一关；过不了的请出链，并说清是哪一份、为什么
		List<String> chain = verifyChain(app, index);
		applyChain(app, chain, "缓存");
		// 链一收缩就得落账并清盘：以前只有"成功下载新版"这条路径会清理，于是被撤回的、
		// 与清单不符的、以及下面"已是最新"直接 return 的那些旧文件，永远留在应用目录里。
		saveChain(app, chain, index);
		cleanupOldFiles(app, chain);
		// 链上还是明文的老版本就地压成 .gz：内容一字不改，照样能重放，但磁盘能腾出七成
		compactedBytes += compactChain(app, chain);

		// 3) 清单里有更新的就下载
		String best = maxVersion(index.keySet());
		if (!chain.isEmpty() && compareVersion(best, lastOf(chain)) <= 0) {
			prefs(app).edit().putString(K_TIME, now()).apply();
			status = "已是最新 " + lastOf(chain) + "（链上 " + chain.size() + " 份）";
			return;
		}

		Fetched got = downloadVersion(best, gzIndex.get(best));
		if (got == null || got.text == null || got.text.trim().isEmpty()) {
			status = "新版 " + best + " 下载失败"
					+ (chain.isEmpty() ? "（按内置默认运行）" : "（继续用 " + lastOf(chain) + "）");
			return;
		}
		transferNote = (got.via == null ? "" : got.via) + (got.note == null ? "" : "｜" + got.note);
		wireBytes = got.wire;
		plainBytes = got.plain;
		String sha = sha256(got.text);            // 永远核明文：压缩过与没压过，这一关的语义一模一样
		if (!sha.equalsIgnoreCase(index.get(best))) {
			status = "新版 " + best + "（" + got.via + "下发）哈希与清单不符 → 拒绝（可能镜像被动过）";
			toast("云端配置 " + best + " 校验不过，已拒绝");
			return;
		}
		FeatureCatalog.Result probe = FeatureCatalog.parse(
				got.text, versionCode(app), deviceId(app), hmacKey(), evaluator(app));
		if (!probe.accepted) {
			status = "新版 " + best + " 被拒绝（" + probe.rejectReason + "）";
			toast("云端配置 " + best + " 被拒绝：" + probe.rejectReason);
			return;
		}

		// 4) 落盘 + 按 @scope 重排链 + 生效
		if (!writeStored(app, best + got.suffix, got.stored)) {
			// 写不进磁盘就别声称"已生效"：否则下次启动又要重下一遍，还查不出原因
			status = "新版 " + best + " 已下载但写不进应用目录，本次不生效";
			toast("云端配置写入失败：" + best);
			return;
		}
		List<String> next;
		if (FeatureCatalog.SCOPE_REPLACE.equals(probe.scope)) {
			next = new ArrayList<>();
			next.add(best);                       // 只吃这一版：以前的整条链作废
		} else {
			next = new ArrayList<>(chain);
			if (!next.contains(best)) {
				next.add(best);
			}
			while (next.size() > MAX_CHAIN) {     // 兜底：链不会无限增长
				next.remove(0);
			}
		}
		prefs(app).edit()
				.putString(K_VERSION, best)
				.putString(K_SHA, sha)
				.putString(K_SOURCE, MIRROR_NAMES[0])
				.putString(K_TIME, now())
				.putBoolean(K_VERIFIED, true)
				.apply();
		saveChain(app, next, index);
		applyChain(app, next, "已更新");
		cleanupOldFiles(app, next);
		// 这份新版可能是明文下发的（源上没有 .gz 或压缩校验没过），就地补压一次
		compactedBytes += compactChain(app, next);
		toast("云端配置已更新到 " + best + "：生效 " + probe.applied + " 条"
				+ (transferNote.isEmpty() ? "" : "（" + transferNote + "）")
				+ (probe.issues.isEmpty() ? "" : "，" + probe.issues.size() + " 条有问题（到「关于」页看）"));
	}

	/** 链上每一份都拿清单核对一遍，返回仍然可信的那部分（旧 → 新）；被剔除的会说明原因。 */
	private static List<String> verifyChain(Context app, Map<String, String> index) {
		List<String> keep = new ArrayList<>();
		List<String> kicked = new ArrayList<>();
		List<String> held = new ArrayList<>();
		List<String> stored = chainFromPrefs(app);
		String tail = stored.isEmpty() ? "" : stored.get(stored.size() - 1);
		Map<String, String> local = chainShas(app);
		for (String v : stored) {
			String expected = index.get(v);
			String text = readVersionText(app, v);
			boolean selfOk = text != null && !text.isEmpty()
					&& local.get(v) != null && local.get(v).length() == 64
					&& sha256(text).equalsIgnoreCase(local.get(v));
			if (expected == null && v.equals(tail) && selfOk) {
				// 链尾这一份下载时早就核对过清单（chain_sha 里存着那份哈希），这一轮清单却没列它：
				// 极可能只是三家镜像里有一家在发旧的 index.txt。当场踢掉 = 生效退回上一版 + 删文件 +
				// 下次再重下一遍，正是他报的「重启把最新版删了换成上一版」。连续两轮没列才当撤回。
				int n = missCount(app, v) + 1;
				prefs(app).edit().putString(K_MISS, v + ":" + n).apply();
				if (n >= 2) {
					kicked.add(v + "（清单连续 " + n + " 轮都没列 → 按撤回处理）");
				} else {
					keep.add(v);
					held.add(v);
				}
			} else if (expected == null) {
				kicked.add(v + "（清单里已撤回）");
			} else if (text == null || text.isEmpty()) {
				kicked.add(v + "（本地文件不见了）");
			} else if (!sha256(text).equalsIgnoreCase(expected)) {
				// 清单列了这一版但哈希对不上：这一条不软化，作者的反篡改规矩就是「不符即禁止生效」
				kicked.add(v + "（与清单哈希不符）");
			} else {
				keep.add(v);
				if (v.equals(tail) && localMiss(app) != null) {
					prefs(app).edit().remove(K_MISS).apply();   // 清单又认了：连击清零
				}
			}
		}
		if (!kicked.isEmpty()) {
			status = "剔除失效配置：" + join(kicked);
			toast("云端配置剔除 " + kicked.size() + " 份：" + join(kicked));
		} else if (!held.isEmpty()) {
			status = "清单这一轮没列 " + join(held) + "，按上次核对结果保留（连续两轮没列才撤回）";
			toast(status);
		}
		return keep;
	}

	/** 读「这一版被清单漏了几轮」的计数（只对链尾有意义）。 */
	private static int missCount(Context app, String version) {
		String raw = prefs(app).getString(K_MISS, "");
		if (raw == null || !raw.startsWith(version + ":")) {
			return 0;
		}
		try {
			return Integer.parseInt(raw.substring(version.length() + 1).trim());
		} catch (Throwable t) {
			return 0;
		}
	}

	/** 当前挂着连击计数的那一版，没有就返回 null。 */
	private static String localMiss(Context app) {
		String raw = prefs(app).getString(K_MISS, "");
		int c = raw == null ? -1 : raw.indexOf(':');
		return c > 0 ? raw.substring(0, c) : null;
	}

	/** 把链上的文件按顺序重放成一份最终状态并挂上门面。 */
	private static void applyChain(Context app, List<String> versions, String from) {
		List<String> texts = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (String v : versions) {
			String text = readVersionText(app, v);
			if (text != null && text.length() > 0) {
				texts.add(text);
				names.add(v);
			}
		}
		if (texts.isEmpty()) {
			gate = new FeatureGate(null, isCloudEnabled(app));
			appliedVersion = "";
			status = "按内置默认运行（没有可用的云端配置）";
			CloudAssets.applyBackground(app, "", "", "空链");
			return;
		}
		FeatureCatalog.Result result = FeatureCatalog.parseChain(texts, versionCode(app),
				deviceId(app), hmacKey(), evaluator(app));
		if (!result.accepted) {
			gate = new FeatureGate(null, isCloudEnabled(app));
			appliedVersion = "";
			status = "云端配置被拒绝（" + result.rejectReason + "），已回退内置默认";
			CloudAssets.applyBackground(app, "", "", "被拒");
			return;
		}
		String newest = lastOf(names);
		boolean changed = !newest.equals(appliedVersion);
		gate = new FeatureGate(result, isCloudEnabled(app));
		gate.ensureResolved();            // 首页第一次取数就该有完整派生态，不留"第一次查询才展开"的窗口
		// 界面资产：背景图（BG set/reset，支持 if= 按机型/系统版本/人群下发）。
		// 文件已在且哈希相符 = 直接用；不在 = 后台下载，落地后 recreateUi 重建界面。
		CloudAssets.applyBackground(app, result.bgUrl, result.bgSha, result.bgFrom);
		markApplied(newest);
		pruneChain(app, versions, result.fullSnapshot);     // 链要短：留最新，旧份按快照声明与体积预算取舍
		status = "生效中｜" + newest + "｜链 " + names.size() + " 份｜" + result.applied + " 条"
				+ (result.issues.isEmpty() ? "" : "，" + result.issues.size() + " 条有问题")
				+ (transferNote.length() == 0 ? "" : "｜" + transferNote)
				+ "｜来源 " + from + "｜" + prefs(app).getString(K_TIME, "");
		if (changed && from.startsWith("已")) {
			recreateUi();
		}
	}

	/** 单条地址按镜像顺序试；detail 非空时把每个镜像的结果写进去（失败提示要能说清卡在哪）。 */
	private static String fetchWithMirrors(String relPath, StringBuilder detail) {
		for (int i = 0; i < MIRROR_ROOTS.length; i++) {
			String url = MIRROR_ROOTS[i] + DIR + "/" + relPath;
			if (MANIFEST.equals(relPath)) {
				// index.txt 文件名固定，CDN 必然缓存它：实测 jsdelivr 会在新版发布后继续发旧副本
				// 几十秒到几小时，手机连不通 raw 时就表现为「拉不到新版」。清单只有 3 KB，
				// 带一个按分钟取整的时间戳破缓存（raw 忽略查询串；正文文件名自带版本号，不用破）。
				url += "?t=" + (System.currentTimeMillis() / 60000L);
			}
			String body = httpGet(url);
			if (body != null && body.trim().length() > 0) {
				return body;
			}
			if (detail != null) {
				detail.append(MIRROR_NAMES[i]).append(' ');
			}
		}
		return null;
	}

	/** 一次下载的结果：text 是还原后的明文（校验与解析都用它），stored 是真正落盘的那份字节。 */
	private static final class Fetched {
		String text;          // 明文
		byte[] stored;        // 落盘内容：压缩原样存，压不了就存明文
		String suffix;        // ".txt.gz" 或 ".txt"
		int wire;             // 走了多少网络字节
		int plain;            // 明文多少字节
		String via;           // "gzip" / "明文"
		String note;          // 压缩这条路的结论或回退原因（要显示，不能闷声退）
	}

	/**
	 * 下载某个版本的正文并实时上报状态（只有"确实有新版"才会走到这里）。
	 *
	 * <p>清单第 3 列登记了压缩件时先取 {@code <版本>.txt.gz}，否则照旧取 {@code <版本>.txt}。
	 * <b>压缩只是搬运方式</b>：交回上层的永远是明文，第 2 列的明文哈希校验、{@code @hmac} 签名核的
	 * 都是解压之后的正文，所以功能与指令语义不会因为压了而少一条。
	 */
	private static Fetched downloadVersion(String version, String gzSha) {
		begin(version);
		boolean wantGz = gzSha != null && gzSha.length() == 64;
		for (int i = 0; i < MIRROR_ROOTS.length; i++) {
			if (wantGz) {
				publish(true, "正在获取 " + version + "（压缩）· 源 " + MIRROR_NAMES[i] + "（"
						+ (i + 1) + "/" + MIRROR_ROOTS.length + "）");
				byte[] packed = httpGetBytesWithProgress(
						MIRROR_ROOTS[i] + DIR + "/" + version + ".txt.gz", MIRROR_NAMES[i]);
				if (packed != null && packed.length > 0) {
					Fetched f = tryGunzip(version, packed, i, gzSha);
					if (f != null) {
						return f;
					}
				}
			}
			publish(true, "正在获取 " + version + " · 源 " + MIRROR_NAMES[i] + "（"
					+ (i + 1) + "/" + MIRROR_ROOTS.length + "）");
			Fetched f = fetchPlain(version, i);
			if (f != null) {
				f.note = wantGz ? f.note : "清单没登记压缩件，按明文下发";
				return f;
			}
		}
		end("三个源都没取到，继续用现有配置");
		return null;
	}

	/**
	 * 压缩件先按清单第 3 列核哈希，解压后再交明文给调用方（调用方仍按第 2 核明文哈希）。
	 * 压缩这条路任何一步不对就当场回退取明文，并把原因留在 note 里显示出来。
	 */
	private static Fetched tryGunzip(String version, byte[] packed, int mirror, String gzSha) {
		String got = CloudCodec.sha256Hex(packed);
		if (!got.equalsIgnoreCase(gzSha)) {
			return gzBad(version, mirror, "压缩件哈希与清单不符（" + got.substring(0, 12)
					+ "…），改用明文");
		}
		byte[] plain;
		try {
			plain = CloudCodec.gunzip(packed, CloudCodec.MAX_PLAIN_BYTES);
		} catch (Throwable t) {
			return gzBad(version, mirror, "解压失败（" + t.getClass().getSimpleName() + "），改用明文");
		}
		if (plain == null || plain.length == 0) {
			return gzBad(version, mirror, "解压出来是空的，改用明文");
		}
		Fetched f = new Fetched();
		f.text = new String(plain, Charset.forName("UTF-8"));
		f.stored = packed;                       // 磁盘上也存压缩件：正文两兆多字节降到几百千字节
		f.suffix = ".txt.gz";
		f.wire = packed.length;
		f.plain = plain.length;
		f.via = "gzip";
		f.note = "压缩 " + kb(packed.length) + "，还原后 " + kb(plain.length) + "，省下 "
				+ savedPercent(packed.length, plain.length) + "%";
		publish(true, "已取到压缩正文 " + kb(packed.length) + "，正在校验哈希与签名…");
		return f;
	}

	/** 压缩失败不算故障，但必须说清为什么，然后照旧取明文。 */
	private static Fetched gzBad(String version, int mirror, String why) {
		Fetched f = fetchPlain(version, mirror);
		if (f != null) {
			f.note = why;
		}
		return f;
	}

	private static Fetched fetchPlain(String version, int fromMirror) {
		for (int i = Math.max(0, fromMirror); i < MIRROR_ROOTS.length; i++) {
			byte[] raw = httpGetBytesWithProgress(MIRROR_ROOTS[i] + DIR + "/" + version + ".txt",
					MIRROR_NAMES[i]);
			if (raw == null || raw.length == 0) {
				continue;
			}
			publish(true, "已取到 " + kb(raw.length) + "，正在校验哈希与签名…");
			Fetched f = new Fetched();
			f.text = new String(raw, Charset.forName("UTF-8"));
			f.stored = raw;
			f.suffix = ".txt";
			f.wire = raw.length;
			f.plain = raw.length;
			f.via = "明文";
			return f;
		}
		return null;
	}

	private static String savedPercent(int packed, int plain) {
		if (plain <= 0) {
			return "0";
		}
		return String.valueOf(Math.max(0, 100 - 100 * packed / plain));
	}

	private static String kb(int bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
	}

	private static String join(List<String> parts) {
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (sb.length() > 0) {
				sb.append("、");
			}
			sb.append(p);
		}
		return sb.toString();
	}

	// ============================================================ 本地状态

	private static String lastOf(List<String> list) {
		return list.isEmpty() ? "" : list.get(list.size() - 1);
	}

	/** 链：prefs 里记的版本号列表（旧 → 新）。 */
	private static List<String> chainFromPrefs(Context app) {
		List<String> out = new ArrayList<>();
		for (String v : prefs(app).getString(K_CHAIN, "").split(";")) {
			String s = v.trim();
			if (s.matches("[0-9]+(\\.[0-9]+)*") && !out.contains(s)) {
				out.add(s);
			}
		}
		return out;
	}

	private static void saveChain(Context app, List<String> chain, Map<String, String> shas) {
		StringBuilder cv = new StringBuilder();
		StringBuilder cs = new StringBuilder();
		for (String v : chain) {
			if (cv.length() > 0) {
				cv.append(';');
				cs.append(';');
			}
			cv.append(v);
			String sha = shas == null ? prefs(app).getString(K_SHA, "") : shas.get(v);
			cs.append(v).append(':').append(sha == null ? "" : sha);
		}
		prefs(app).edit().putString(K_CHAIN, cv.toString()).putString(K_CHAIN_SHA, cs.toString()).apply();
	}

	/** prefs 里备份的「版本 → 哈希」，清单拿不到时用它自检。 */
	private static Map<String, String> chainShas(Context app) {
		Map<String, String> out = new HashMap<>();
		for (String piece : prefs(app).getString(K_CHAIN_SHA, "").split(";")) {
			int c = piece.indexOf(':');
			if (c > 0) {
				out.put(piece.substring(0, c).trim(), piece.substring(c + 1).trim());
			}
		}
		return out;
	}

	/** 生效链（状态面板用）。 */
	public static List<String> chainVersions() {
		Context app = lastApp;
		return app == null ? new ArrayList<String>() : chainFromPrefs(app);
	}

	/** 启动时先把"上次已核对"的那条链挂上，不用等网络。 */
	private static void loadVerifiedLocal(Context app) {
		Map<String, String> stored = chainShas(app);
		List<String> ok = new ArrayList<>();
		for (String v : chainFromPrefs(app)) {
			String text = readVersionText(app, v);
			String want = stored.get(v);
			if (text == null || text.isEmpty() || want == null || want.length() != 64) {
				continue;                       // 没有可信哈希的份一律不用
			}
			if (sha256(text).equalsIgnoreCase(want)) {
				ok.add(v);
			}
		}
		if (ok.isEmpty()) {
			cleanupOldFiles(app, ok);        // 链上没有一份可信：磁盘上那些老配置同样是垃圾，清掉
			return;
		}
		// 启动这条路径以前从不清盘：哈希不符/文件不见了而被跳过的份会永远留在目录里
		saveChain(app, ok, stored);
		applyChain(app, ok, "启动本地");
		cleanupOldFiles(app, ok);
	}

	private static boolean hasVerifiedLocal(Context app) {
		return prefs(app).getBoolean(K_VERIFIED, false);
	}

	private static boolean hasLocalFile(Context app) {
		return !prefs(app).getString(K_VERSION, "").isEmpty();
	}

	/**
	 * 校验不过时丢掉本地这份（含整条链）。<b>这是内部兜底，不是给用户的按钮</b> ——
	 * 用户不能主动清除云控配置：清掉之后下架的站点与停用效果就没了，等于关掉云控。
	 */
	private static void deleteLocal(Context app) {
		for (String v : chainFromPrefs(app)) {
			new File(app.getFilesDir(), v + ".txt").delete();
		}
		String version = prefs(app).getString(K_VERSION, "");
		if (!version.isEmpty()) {
			new File(app.getFilesDir(), version + ".txt").delete();
		}
		prefs(app).edit().remove(K_VERSION).remove(K_SHA).remove(K_CHAIN).remove(K_CHAIN_SHA)
				.putBoolean(K_VERIFIED, false).apply();
		appliedVersion = "";
		cleanupOldFiles(app, null);             // 一份都不留：本地这些已经不可信了
	}

	// ============================================================ 清单与版本

	/** 清单：每行「版本号 <空白> sha256」，# 注释；坏行直接跳过（错误隔离）。 */
	private static Map<String, String> parseManifest(String text) {
		Map<String, String> out = new HashMap<>();
		for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
			String s = line.trim();
			if (s.isEmpty() || s.charAt(0) == '#') {
				continue;
			}
			String[] parts = s.split("\\s+");
			if (parts.length >= 2
					&& parts[0].matches("[0-9]+(\\.[0-9]+)*")
					&& parts[1].matches("[0-9a-fA-F]{64}")) {
				out.put(parts[0], parts[1]);
			}
		}
		return out;
	}

	/** 版本号按数字段比较；字符串比较会把 "10.0.0" 判成比 "9.0.0" 小。 */
	private static int compareVersion(String a, String b) {
		String[] x = a.split("\\.");
		String[] y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int xi = i < x.length ? parseIntSafe(x[i]) : 0;
			int yi = i < y.length ? parseIntSafe(y[i]) : 0;
			if (xi != yi) {
				return xi < yi ? -1 : 1;
			}
		}
		return 0;
	}

	private static int parseIntSafe(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Throwable t) {
			return 0;
		}
	}

	private static String maxVersion(Set<String> versions) {
		String best = null;
		for (String v : versions) {
			if (best == null || compareVersion(v, best) > 0) {
				best = v;
			}
		}
		return best == null ? "0" : best;
	}

	// ============================================================ 条件求值

	/**
	 * 复用公告中心的变量表与表达式引擎（{@code Expr.test}），所以配置文件里的
	 * {@code if="version_code >= 520 && authorized && seen_ov2026"} 与 ovo.txt 同一套写法。
	 *
	 * <p>在此之上再加两层：{@code seen_<公告id>}（弹过某条公告也算条件）与门面那层的
	 * {@code flag_*} / {@code cloud_version} / {@code bucket} —— 于是"上一版发过的开关"
	 * 和"本机在第几号灰度桶"都能直接写进条件里。
	 */
	private static FeatureCatalog.CondEvalVars evaluator(final Context app) {
		return new FeatureCatalog.CondEvalVars() {
			@Override
			public boolean test(String expr) {
				return test(expr, null);
			}

			@Override
			public boolean test(String expr, Map<String, String> extraVars) {
				Map<String, String> vars = new HashMap<>(AnnouncementCenter.vars(app));
				for (String id : AnnouncementCenter.seenIds(app)) {
					vars.put("seen_" + id, "1");
				}
				vars.putAll(gate.condVars(vars));
				if (extraVars != null) {
					vars.putAll(extraVars);
				}
				return Expr.test(expr, vars);
			}
		};
	}

	// ============================================================ 网络

	/** 普通 GET：4 秒连接、8 秒读，失败返回 null。 */
	private static String httpGet(String url) {
		HttpURLConnection conn = null;
		try {
			conn = open(url, 4000, 8000);
			if (conn.getResponseCode() != 200) {
				return null;
			}
			return readAll(conn.getInputStream());
		} catch (Throwable t) {
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/** 带进度的 GET，返回原始字节：.txt.gz 与 .txt 都走这里，压没压由调用方按魔数判。 */
	private static byte[] httpGetBytesWithProgress(String url, String mirror) {
		HttpURLConnection conn = null;
		try {
			conn = open(url, 4000, 15000);
			int code = conn.getResponseCode();
			if (code != 200) {
				synchronized (PROGRESS) {
					PROGRESS.mirror = mirror;
				}
				publish(true, mirror + " 返回 " + code + "，换下一个源");
				return null;
			}
			synchronized (PROGRESS) {
				PROGRESS.mirror = mirror;
				PROGRESS.total = conn.getContentLength();
				PROGRESS.done = 0;
			}
			publish(true, null);
			return readBytesWithProgress(conn.getInputStream(), mirror);
		} catch (Throwable t) {
			publish(true, mirror + " 连不上（" + t.getClass().getSimpleName() + "），换下一个源");
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static HttpURLConnection open(String url, int connectTimeout, int readTimeout) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setConnectTimeout(connectTimeout);
		conn.setReadTimeout(readTimeout);
		conn.setInstanceFollowRedirects(true);
		conn.setRequestProperty("User-Agent", "MinewaysMobile");
		conn.setRequestProperty("Cache-Control", "no-cache");
		return conn;
	}

	private static String readAll(InputStream in) throws Exception {
		return new String(readBytesWithProgress(in, null), Charset.forName("UTF-8"));
	}

	/** 边下边报进度，返回原始字节（gzip 下发时要先看魔数、再按压缩件哈希核对）。 */
	private static byte[] readBytesWithProgress(InputStream in, String mirror) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		long lastPing = 0L;
		while ((n = in.read(buf)) > 0) {
			bos.write(buf, 0, n);
			long now = System.currentTimeMillis();
			// 配置文件只有几 KB，一次 read 就到头了；限流到 100ms 一次，界面才不会一闪而过
			if (now - lastPing >= 100L) {
				lastPing = now;
				synchronized (PROGRESS) {
					PROGRESS.done = bos.size();
				}
				publish(true, null);
			}
		}
		in.close();
		synchronized (PROGRESS) {
			PROGRESS.done = bos.size();
			if (PROGRESS.total < 0) {
				PROGRESS.total = bos.size();
			}
		}
		publish(true, null);
		return bos.toByteArray();
	}

	// ============================================================ 提示

	private static void toast(final String text) {
		runOnUi(new Runnable() {
			@Override
			public void run() {
				Activity activity = currentActivity;
				if (activity == null) {
					return;
				}
				try {
					Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
				} catch (Throwable ignored) {
				}
			}
		});
	}

	/** 换版本后让界面重建一次；界面自己也会在 onResume 兜一次，两条路都留着。 */
	static void recreateUi() {
		runOnUi(new Runnable() {
			@Override
			public void run() {
				Activity activity = currentActivity;
				if (activity == null) {
					return;
				}
				try {
					activity.recreate();
				} catch (Throwable ignored) {
				}
			}
		});
	}

	private static void runOnUi(Runnable runnable) {
		Activity activity = currentActivity;
		if (activity != null) {
			activity.runOnUiThread(runnable);
		}
	}

	// ============================================================ 基础设施

	private static SharedPreferences prefs(Context context) {
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static byte[] hmacKey() {
		byte[] key = new byte[KEY_HEX.length() / 2];
		for (int i = 0; i < key.length; i++) {
			key[i] = (byte) Integer.parseInt(KEY_HEX.substring(i * 2, i * 2 + 2), 16);
		}
		return key;
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(Charset.forName("UTF-8")));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format(Locale.ROOT, "%02x", b));
			}
			return sb.toString();
		} catch (Throwable t) {
			return "";
		}
	}

	/** 读整个文件为字节；一次 read() 不保证读完，两兆多的配置文件必须循环补读。 */
	private static byte[] readBytes(Context context, String name) {
		File file = new File(context.getFilesDir(), name);
		if (!file.exists()) {
			return null;
		}
		FileInputStream in = null;
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(file.length(), 1 << 20));
			in = new FileInputStream(file);
			byte[] buf = new byte[16384];
			int n;
			while ((n = in.read(buf)) > 0) {
				bos.write(buf, 0, n);
			}
			return bos.toByteArray();
		} catch (Throwable t) {
			return null;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static String readFile(Context context, String name) {
		byte[] raw = readBytes(context, name);
		if (raw == null) {
			return null;
		}
		return new String(raw, Charset.forName("UTF-8"));
	}

	/**
	 * 读某个版本的正文：磁盘上可能是压缩件（新版），也可能是明文（老版本留下来的缓存）。
	 * 两种都还原成正文文本再交出去，所以哈希核对、{@code @hmac} 签名核的永远是同一份明文。
	 */
	private static String readVersionText(Context context, String version) {
		byte[] packed = readBytes(context, version + ".txt.gz");
		if (packed != null && CloudCodec.looksGzipped(packed)) {
			try {
				return new String(CloudCodec.gunzip(packed, CloudCodec.MAX_PLAIN_BYTES),
						Charset.forName("UTF-8"));
			} catch (Throwable t) {
				// 压缩这份坏了：先当明文试，都不行才说不见了（调用方会把这一版请出链）
				status = "本地 " + version + " 压缩件解压失败（" + t.getClass().getSimpleName() + "）";
			}
		}
		return readFile(context, version + ".txt");
	}

	/** 落盘某一份配置（原样字节：压缩下发就存压缩件，省三分之二的本地空间）。 */
	private static boolean writeStored(Context context, String name, byte[] data) {
		FileOutputStream out = null;
		try {
			File tmp = new File(context.getFilesDir(), name + ".part");
			out = new FileOutputStream(tmp);
			out.write(data);
			out.flush();
			out.close();
			out = null;
			File target = new File(context.getFilesDir(), name);
			target.delete();
			tmp.renameTo(target);
			return target.exists() && target.length() == data.length;
		} catch (Throwable t) {
			Log.w(TAG, "write " + name + " failed", t);
			return false;
		} finally {
			if (out != null) {
				try {
					out.close();
				} catch (Throwable ignored) {
				}
			}
		}
	}

	/**
	 * 把链上仍是明文的旧配置<b>就地</b>压成 {@code 版本号.txt.gz}。
	 *
	 * <p>为什么这样做：他要求「不改变遵从最新配置、也照样能执行老版本配置文件」的前提下，
	 * 把老版本占的空间清出大部分来。删掉老版本就断了链式重放，留着又占地方 —— 而就地压缩
	 * <b>一个字节的内容都没改</b>：解压后逐字节等于原来那份，清单第 2 列的明文哈希、{@code @hmac}
	 * 签名、按顺序重放出来的最终状态全都照旧（{@link #readVersionText} 两种命名都认）。
	 *
	 * <p>每一步都回读核对：压完重新读盘解压、比哈希，<b>核不上就删掉压缩件、保留明文</b>，
	 * 绝不让一份"看着压好了其实坏了"的文件顶替原文件。压不动的（本来就小或越压越大）也不动。
	 *
	 * @return 腾出来的字节数
	 */
	private static long compactChain(Context app, Collection<String> chain) {
		long saved = 0L;
		for (String v : chain) {
			File plain = new File(app.getFilesDir(), v + ".txt");
			byte[] bytes = readBytes(app, v + ".txt");
			if (bytes == null || bytes.length < 4096 || !plain.exists()) {
				continue;                       // 没有明文这份，或本来就小，不值得折腾
			}
			byte[] packed;
			try {
				packed = CloudCodec.gzip(bytes);
			} catch (Throwable t) {
				continue;
			}
			if (packed.length == 0 || packed.length >= bytes.length) {
				continue;
			}
			if (!writeStored(app, v + ".txt.gz", packed)) {
				continue;
			}
			byte[] back = readBytes(app, v + ".txt.gz");
			String want = CloudCodec.sha256Hex(bytes);
			String got;
			try {
				got = back == null ? "" : CloudCodec.sha256Hex(
						CloudCodec.gunzip(back, CloudCodec.MAX_PLAIN_BYTES));
			} catch (Throwable t) {
				got = "";
			}
			if (!want.equals(got)) {
				new File(app.getFilesDir(), v + ".txt.gz").delete();   // 回读核不上：只留原来那份明文
				status = "本机 " + v + " 就地压缩回读不符，已保留明文";
				continue;
			}
			saved += bytes.length - packed.length;
			plain.delete();
		}
		return saved;
	}

	/**
	 * 链要能执行老版本，但也不能无限占空间 —— 这里决定"哪些旧份还留着，哪些删掉"。
	 *
	 * <p>两条取舍：
	 * <ul>
	 * <li><b>最新那份永远保留</b>（遵从最新配置这条不能动摇）。</li>
	 * <li>最新那份声明了 {@code @snapshot=full}（全量快照）时，旧每一份的指令在这一份里都还在，
	 * 于是只留「最新 + 上一份」：上一份留着是为了撤回新版时能立刻回退，不用重下一遍。</li>
	 * <li>没声明时也按体积预算从新到旧收：超过 {@link #CHAIN_BUDGET_BYTES} 的最老几份删掉。
	 * 压缩过的配置一份几百 KB，八份累积起来照样吃掉十几兆。</li>
	 * </ul>
	 *
	 * <p>删掉的份并没有消失：{@code config/index.txt} 仍然登记着每一个版本，真要用到老版本
	 * （撤回、回退、排查历史）随时能再取回来。取舍结果会写进状态，手机上看得见留了几份、删了几份。
	 */
	private static void pruneChain(Context app, List<String> chain, boolean fullSnapshot) {
		if (chain.size() <= 1) {
			return;
		}
		List<String> keep = new ArrayList<>(chain);
		if (fullSnapshot && keep.size() > 2) {
			keep = new ArrayList<>(keep.subList(keep.size() - 2, keep.size()));
		}
		List<String> within = new ArrayList<>();
		long total = 0L;
		for (int i = keep.size() - 1; i >= 0; i--) {
			long sz = sizeOf(app, keep.get(i));
			if (!within.isEmpty() && total + sz > CHAIN_BUDGET_BYTES) {
				break;                       // 最新那份无论多大都留，其余按预算收
			}
			total += sz;
			within.add(0, keep.get(i));
		}
		if (within.size() == chain.size()) {
			return;
		}
		int dropped = chain.size() - within.size();
		saveChain(app, within, chainShas(app));
		cleanupOldFiles(app, within);
		droppedBytes += totalBytesOf(app, difference(chain, within));
		// 只记到旁注里：status 那句"生效中｜版本｜链 N 份"是排查时第一眼要看的，不能被腾空间这件事盖掉
		pruneNote = "删掉 " + dropped + " 份旧配置、留 " + within.size() + " 份（"
				+ (fullSnapshot ? "该版是全量快照，旧份内容都在新版里" : "按体积预算取舍")
				+ "，最新那份始终保留）";
	}

	private static List<String> difference(List<String> all, List<String> keep) {
		List<String> out = new ArrayList<>(all);
		out.removeAll(keep);
		return out;
	}

	private static long sizeOf(Context app, String version) {
		long sz = new File(app.getFilesDir(), version + ".txt.gz").length();
		if (sz <= 0) {
			sz = new File(app.getFilesDir(), version + ".txt").length();
		}
		return sz;
	}

	private static long totalBytesOf(Context app, List<String> versions) {
		long sum = 0L;
		for (String v : versions) {
			sum += sizeOf(app, v);
		}
		return sum;
	}

	/**
	 * 只留生效链上那些版本文件，其余的 {@code 版本号.txt} / {@code 版本号.txt.gz} 清掉 ——
	 * 否则每换一版就在应用目录里多留一个没人读的文件，一两年下来就是一堆垃圾。
	 *
	 * <p>两个要点：① 每个版本只留实际在用的一种编码（有 .txt.gz 就删 .txt，反之亦然），
	 * 换了编码方式也不会留下双份；② 只认「版本号 + 配置后缀」这种命名，
	 * 别的功能写在 filesDir 里的东西（存档、缓存、字体…）一律不碰。
	 *
	 * @param keep 要保留的版本号集合；null 或空表示一份都不留
	 */
	private static void cleanupOldFiles(Context app, Collection<String> keep) {
		File[] files = app.getFilesDir().listFiles();
		if (files == null) {
			return;
		}
		Set<String> versions = new LinkedHashSet<>();
		if (keep != null) {
			versions.addAll(keep);
		}
		String current = prefs(app).getString(K_VERSION, "");
		if (current.length() > 0) {
			versions.add(current);
		}
		// **磁盘上版本号最大的那份永远保留**：清盘是为了回收旧版本，不是为了删掉刚下载的最新版。
		// 以前这里会跟着一条被误判的链把最新版一起删掉，于是「重启 → 最新版没了 → 生效退回上一版
		// → 下次再重下」成了循环。留着这一份，下一轮清单认回来就能直接自愈。
		String newest = newestOnDisk(files);
		if (newest != null) {
			versions.add(newest);
		}
		Set<String> keepNames = new LinkedHashSet<>();
		for (String v : versions) {
			if (new File(app.getFilesDir(), v + ".txt.gz").exists()) {
				keepNames.add(v + ".txt.gz");
			} else if (new File(app.getFilesDir(), v + ".txt").exists()) {
				keepNames.add(v + ".txt");
			}
		}
		for (File file : files) {
			String name = file.getName();
			if (keepNames.contains(name) || name.equals(MANIFEST)) {
				continue;
			}
			// 写完改名前被打断留下的半成品，也按版本号命名，一并清掉
			if (name.matches("[0-9]+(\\.[0-9]+)*\\.(txt|txt\\.gz)(\\.part)?")) {
				file.delete();
			}
		}
	}

	/** 目录里配置文件名（{@code 版本号.txt} / {@code 版本号.txt.gz}）中版本号最大的那个；没有返回 null。 */
	private static String newestOnDisk(File[] files) {
		String best = null;
		for (File f : files) {
			String n = f.getName();
			if (!n.matches("[0-9]+(\\.[0-9]+)*\\.txt(\\.gz)?")) {
				continue;
			}
			String v = n.substring(0, n.indexOf(".txt"));
			if (best == null || compareVersion(v, best) > 0) {
				best = v;
			}
		}
		return best;
	}

	private static String now() {
		return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date());
	}

	private static int versionCode(Context context) {
		try {
			return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
		} catch (Throwable t) {
			return 0;
		}
	}

	/** 灰度分桶用的设备标识；取不到返回 null（= 全量命中）。 */
	private static String deviceId(Context context) {
		try {
			return android.provider.Settings.Secure.getString(
					context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
		} catch (Throwable t) {
			return null;
		}
	}
}
