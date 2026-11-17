package com.mineways.open;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.util.Locale;

/**
 * 网页创作页（Blockbench / 4D 皮肤 / 粒子 / 剪辑）的「文件直投」。
 *
 * <p>这些页面的文件入口统一是 {@code onShowFileChooser}。流程：
 * <ol>
 *   <li>目标页 {@code onCreate} 把外部 Uri 交给 {@link #setPending};</li>
 *   <li>{@code onPageFinished} 后延迟调 {@link #tryAutoTrigger}，注入 JS 点中匹配的文件输入框；</li>
 *   <li>输入框被点触发 {@code onShowFileChooser}，页里调 {@link #consume}：匹配则直接把外部 Uri
 *       交回页面、<b>不弹系统选择器</b>；不匹配放行给正常选择器。</li>
 * </ol>
 * 页面一行 JS 都不用改。
 */
public final class WebOpenBridge {

    private static Uri pendingUri;
    private static String pendingName;
    private static String pendingMime;
    private static String pendingExt;

    private WebOpenBridge() {
    }

    public static void setPending(Uri uri, String name, String mime) {
        pendingUri = uri;
        pendingName = name;
        pendingMime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        pendingExt = OpenInbox.extOf(name);
    }

    public static boolean hasPending() {
        return pendingUri != null;
    }

    public static void clear() {
        pendingUri = null;
        pendingName = null;
        pendingMime = null;
        pendingExt = null;
    }

    /** 注入 JS 点中 accept 匹配的文件输入框；点中后会走 onShowFileChooser → consume。 */
    public static void tryAutoTrigger(WebView wv) {
        if (wv == null || pendingUri == null) {
            return;
        }
        String major = pendingMime.contains("/") ? pendingMime.split("/")[0] : "";
        String js = "(function(){var ins=[].slice.call(document.querySelectorAll('input[type=file]'));"
                + "var major='" + major + "';var ext='" + pendingExt + "';"
                + "for(var i=0;i<ins.length;i++){var a=(ins[i].getAttribute('accept')||'').toLowerCase();"
                + "if(!a||a.indexOf('*/*')>=0||(major&&a.indexOf(major)>=0)||(ext&&a.indexOf(ext)>=0)){"
                + "ins[i].click();return true;}}return false;})()";
        wv.evaluateJavascript(js, null);
    }

    /**
     * 在 {@code onShowFileChooser} 里调用。accept 匹配待投文件时直接交回 Uri 并返回 true
     * （页面拿到文件，不弹选择器）；否则返回 false，走正常选择器。
     */
    public static boolean consume(String[] acceptTypes, ValueCallback<Uri[]> callback) {
        if (pendingUri == null || callback == null) {
            return false;
        }
        if (!matchAccept(acceptTypes)) {
            return false;
        }
        Uri u = pendingUri;
        clear();
        callback.onReceiveValue(new Uri[]{u});
        return true;
    }

    private static boolean matchAccept(String[] accept) {
        if (accept == null || accept.length == 0) {
            return true;
        }
        String major = pendingMime.contains("/") ? pendingMime.split("/")[0] : "";
        for (String a : accept) {
            if (a == null) {
                continue;
            }
            String s = a.toLowerCase(Locale.ROOT).trim();
            if (s.isEmpty() || s.equals("*/*") || s.equals("*")) {
                return true;
            }
            if (!major.isEmpty() && (s.startsWith(major + "/") || s.equals(major))) {
                return true;
            }
            if (!pendingExt.isEmpty() && (s.endsWith("." + pendingExt) || s.contains(pendingExt))) {
                return true;
            }
        }
        return false;
    }
}
