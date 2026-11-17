package com.mineways.net;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.mineways.cloud.CloudCatalog;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把网页工具的 WebView 流量接到本机 {@link LocalProxy}。
 *
 * <p><b>2026-10-25 的教训</b>：`ProxyController` 是进程级、fail-closed 的 —— 代理不可用时
 * 不是"退回直连"，而是进程里所有 WebView 一起失败（首页视频也跑不掉）。所以这里改成：
 * <ul>
 *   <li>不再在 App 启动时接管，只在**网页工具要加载远程页**时按需启用；</li>
 *   <li>启用前先用真实 socket 跑一次 CONNECT 自检；</li>
 *   <li>用**主线程 Executor** 设置覆盖（文档要求回调在主线程，之前用直通 Executor 是事故嫌疑之一）；</li>
 *   <li>第一页如果还是失败，立刻清掉覆盖、自动用直连重载一次，本次进程不再尝试。</li>
 * </ul>
 *
 * <p>云控：默认开启；`net_proxy_off=1` / `net_proxy=0` 一键关；`net_proxy_bypass=h1;h2` 给个别站开小门。
 */
public final class WebViewProxy {

    private static final String TAG = "MwProxy";
    private static final int RECENT_MAX = 8;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** ProxyController 的 listener 必须回到主线程（官方最佳实践）。 */
    private static final Executor MAIN_EXEC = new Executor() {
        @Override
        public void execute(Runnable command) {
            MAIN.post(command);
        }
    };

    private static LocalProxy proxy;
    private static boolean overrideSet;
    private static boolean verified;
    private static boolean checking;
    private static boolean failedForSession;
    private static boolean warned;
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static String state = "未启用";

    private WebViewProxy() {
    }

    /** 每次 Activity 回前台：只负责把云控关掉的代理撤掉。 */
    public static void sync(Activity activity) {
        if (activity == null) return;
        if (!isEnabled()) {
            clear();
            state = "已关闭（云控 net_proxy_off=1）";
        }
    }

    /**
     * 网页工具加载远程页前调用。回调一定在主线程，调用方在回调里 {code loadUrl}。
     */
    public static void prepare(Activity activity, Runnable done) {
        if (!isEnabled()) {
            state = "已关闭（云控 net_proxy_off=1）";
            done.run();
            return;
        }
        if (failedForSession) {
            done.run();
            return;
        }
        if (verified) {
            done.run();
            return;
        }
        if (checking) {
            // 另一个页面已经在做自检了：这一页先直连，别叠两次接管
            done.run();
            return;
        }
        if (!supported()) {
            state = "当前系统 WebView 不支持代理，已走直连";
            if (!warned) {
                warned = true;
                toast(activity, state);
            }
            done.run();
            return;
        }

        checking = true;
        try {
            if (proxy == null) {
                proxy = LocalProxy.start(WebViewProxy::onRequest);
            }
        } catch (Throwable t) {
            checking = false;
            failedForSession = true;
            state = "代理启动失败，已走直连";
            Log.w(TAG, "proxy start failed", t);
            done.run();
            return;
        }

        final int port = proxy.port();
        final AtomicBoolean fired = new AtomicBoolean(false);
        final Runnable go = () -> {
            if (!fired.compareAndSet(false, true)) return;
            checking = false;
            done.run();
        };

        new Thread(() -> {
            final boolean ok = preflight(port);
            MAIN.post(() -> {
                if (!ok) {
                    failedForSession = true;
                    stopProxy();
                    state = "代理自检没通过，已走直连";
                    if (!warned) {
                        warned = true;
                        toast(activity, state);
                    }
                    go.run();
                    return;
                }
                try {
                    applyOverride(port, () -> {
                        state = "已接管 · 127.0.0.1:" + port;
                        go.run();
                    });
                } catch (Throwable t) {
                    failedForSession = true;
                    stopProxy();
                    state = "接管失败，已走直连";
                    Log.w(TAG, "setProxyOverride failed", t);
                    go.run();
                    return;
                }
                // 回调迟迟不来也不能让页面一直白着：最多等 1.2 秒就直接加载
                MAIN.postDelayed(go, 1200);
            });
        }, "mw-proxy-check").start();
    }

    /** 主框架加载成功：确认代理真的能用，本进程后续不再回退。 */
    public static void onPageLoaded() {
        if (overrideSet && !failedForSession) {
            verified = true;
            state = "已开启 · 127.0.0.1:" + (proxy == null ? 0 : proxy.port());
        }
    }

    /**
     * 首屏失败且代理还没验证过：撤掉覆盖，本次进程不再尝试。
     *
     * @param afterReload 覆盖真正清掉后回调（在主线程），调用方在这里用直连重载
     * @return true 表示确实撤掉了代理
     */
    public static boolean fallbackIfUnverified(Runnable afterReload) {
        if (verified) return false;
        if (!overrideSet && proxy == null) return false;
        failedForSession = true;
        state = "代理不可用，已切回直连";
        clear(afterReload);
        return true;
    }

    private static boolean isEnabled() {
        try {
            if (com.mineways.cloud.CloudFlags.bool("net_proxy_off", false)
                    || !com.mineways.cloud.CloudFlags.bool("net_proxy", true)) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean supported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 真正接管 WebView 流量（只在自检通过后调用）。listener 在主线程执行。 */
    private static void applyOverride(int port, Runnable applied) {
        ProxyConfig.Builder b = new ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:" + port)
                .addBypassRule("127.0.0.1")
                .addBypassRule("localhost")
                .addBypassRule("*.androidplatform.net")
                .addBypassRule("androidplatform.net")
                // 首页宣传栏视频：它跟"网页工具稳定访问"无关，永远直连，避免被代理连带影响
                .addBypassRule("bilibili.com")
                .addBypassRule("*.bilibili.com")
                .addBypassRule("*.hdslb.com")
                .addBypassRule("*.bilivideo.com");
        String extra = "";
        try {
            extra = com.mineways.cloud.CloudFlags.str("net_proxy_bypass", "");
        } catch (Throwable ignored) {
        }
        for (String h : extra.split("[;,\\s]+")) {
            if (!h.isEmpty()) b.addBypassRule(h);
        }
        ProxyController.getInstance().setProxyOverride(b.build(), MAIN_EXEC, () -> {
            overrideSet = true;
            if (applied != null) applied.run();
        });
    }

    private static void clear() {
        clear(null);
    }

    /** @param after 覆盖清掉之后在主线程回调；没有覆盖时立即回调。 */
    private static void clear(Runnable after) {
        stopProxy();
        verified = false;
        if (!overrideSet) {
            if (after != null) after.run();
            return;
        }
        overrideSet = false;
        try {
            ProxyController.getInstance().clearProxyOverride(MAIN_EXEC, () -> {
                if (after != null) after.run();
            });
        } catch (Throwable t) {
            Log.w(TAG, "clearProxyOverride failed", t);
            if (after != null) after.run();
        }
    }

    private static void stopProxy() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    /** 从本机连一次代理，确认 CONNECT 能拿到 200。 */
    private static boolean preflight(int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 700);
            s.setSoTimeout(1600);
            java.io.OutputStream out = s.getOutputStream();
            String req = "CONNECT www.baidu.com:443 HTTP/1.1\r\nHost: www.baidu.com:443\r\n\r\n";
            out.write(req.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            out.flush();
            java.io.InputStream in = s.getInputStream();
            StringBuilder line = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0 && line.length() < 200) {
                if (b == '\n') break;
                line.append((char) b);
            }
            return line.indexOf(" 200") > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void onRequest(String host, String ip, String source, int ms, boolean ok) {
        String line = host + " → " + (ip == null || ip.isEmpty() ? "失败" : ip)
                + "（" + source + ", " + ms + "ms" + (ok ? "" : ", 连不上") + "）";
        synchronized (RECENT) {
            RECENT.addFirst(line);
            while (RECENT.size() > RECENT_MAX) RECENT.removeLast();
        }
    }

    private static void toast(Activity a, String text) {
        try {
            Toast.makeText(a, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /** 关于页显示的一行状态。 */
    public static String status() {
        String last;
        synchronized (RECENT) {
            last = RECENT.peekFirst();
        }
        return last == null ? state : state + " · 最近 " + last;
    }

    /** 诊断用：最近几条连接（host→IP）。 */
    public static String recent() {
        StringBuilder sb = new StringBuilder();
        synchronized (RECENT) {
            for (String s : RECENT) sb.append(s).append('\n');
        }
        return sb.toString();
    }
}
