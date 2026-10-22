package com.mineways.cloud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 云端下发的界面资产：目前只有背景图。
 *
 * <p>安全边界（与配置文件同一条底线——只吃数据、必须验签）：
 * <ul>
 *   <li>只收 <b>https</b> 地址；</li>
 *   <li><b>sha256 必填</b>，下载后逐字节校验——镜像被投毒或中间人替换都过不了；</li>
 *   <li>大小上限 {@value #MAX_BYTES} 字节，超限直接丢弃；</li>
 *   <li>落盘前先用 {@link BitmapFactory} 解码一遍，解不开的（伪图片）不收；</li>
 *   <li>先写临时文件再原子重命名，不会出现半张图。</li>
 * </ul>
 *
 * <p>生效路径：云控配置里的 {@code BG set url=… sha256=…}（支持 if= 按机型/系统版本/人群下发）
 * 由 {@code CloudCatalog.applyChain} 在 gate 生效后调 {@link #applyBackground}；
 * 下载成功的新图会让界面重建一次（与配置更新同一条路）。{@code BG reset} 删文件回包内背景。
 *
 * <p>消费方：Compose 的照片层（首页/关于）与原生的 {@code FlowLightDrawable} 都从
 * {@link #backgroundFile} 取图，取不到就用包内 {@code mw_bg.jpg}。
 */
public final class CloudAssets {

    private static final String DIR = "cloud";
    private static final String FILE = "bg.img";
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /** 背景图版本号：每次落盘/删除 +1。Compose 与原生用它决定要不要重新解码。 */
    private static volatile long backgroundVersion = 0;
    private static volatile Bitmap cachedBitmap;
    private static volatile long cachedVersion = -1;

    private CloudAssets() {
    }

    /** 云端背景图落盘位置；不存在返回 null。 */
    public static File backgroundFile(Context c) {
        File f = new File(c.getApplicationContext().getFilesDir(), DIR + File.separator + FILE);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** 当前背景的解码结果（按版本缓存）；null = 没有云端背景，调用方用包内图。 */
    public static Bitmap backgroundBitmap(Context c) {
        long v = backgroundVersion;
        Bitmap bmp = cachedBitmap;
        if (bmp != null && !bmp.isRecycled() && cachedVersion == v) {
            return bmp;
        }
        File f = backgroundFile(c);
        if (f == null) {
            return null;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(f.getPath(), o);
            if (decoded == null) {
                // 解不开的旧文件：删掉，回包内背景
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            cachedBitmap = decoded;
            cachedVersion = v;
            return decoded;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供原生页判断要不要重新解码。 */
    public static long backgroundVersion() {
        return backgroundVersion;
    }

    /**
     * 应用云控的背景指令。url 为空 = 回包内背景（删文件）；有 url = 文件已在且哈希相符就直接用，
     * 否则后台下载。整个方法不抛异常——资产层绝不能把配置生效流程带崩。
     *
     * @return "cached"（直接用）/ "downloaded"（新下载）/ "reset"（删除）/ "unchanged"（没变化）
     */
    public static String applyBackground(final Context c, final String url,
                                         final String sha256, final String from) {
        try {
            final Context app = c.getApplicationContext();
            File f = backgroundFile(app);
            if (url == null || url.length() == 0 || sha256 == null || sha256.length() != 64) {
                if (f != null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    cachedBitmap = null;
                    backgroundVersion++;
                    asyncRecreate();
                    return "reset";
                }
                return "unchanged";
            }
            if (f != null && sha256Hex(f).equalsIgnoreCase(sha256)) {
                backgroundVersion++;
                return "cached";
            }
            // 校验通过才落盘；下载在后台线程，新图落地后重建界面
            new Thread(() -> {
                String rc = download(app, url, sha256);
                if (rc != null) {
                    backgroundVersion++;
                    asyncRecreate();
                } else if (f != null) {
                    // 下载失败：旧文件保留（可能是过期镜像），不回退界面
                }
            }, "MwBgDownload").start();
            return "download-started";
        } catch (Throwable t) {
            return "error";
        }
    }

    /** 下载并校验；成功返回描述（"downloaded"），失败返回 null。 */
    private static String download(Context app, String url, String sha256) {
        HttpURLConnection conn = null;
        File tmp = null;
        try {
            if (!url.startsWith("https://")) {
                return null;
            }
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            long len = conn.getContentLength();
            if (len > MAX_BYTES) {
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            File dir = new File(app.getFilesDir(), DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            tmp = new File(dir, FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    in.close();
                    out.close();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return null;
                }
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            in.close();
            out.close();
            String hex = hex(md.digest());
            if (!hex.equalsIgnoreCase(sha256)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            // 解码自检：解不开的不收
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            File target = new File(dir, FILE);
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            cachedBitmap = null;
            backgroundVersion++;
            return "downloaded";
        } catch (Throwable t) {
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void asyncRecreate() {
        // 新图落地后让界面重建一次（与配置更新同一条路，CloudCatalog.recreateUi 同款逻辑）
        com.mineways.cloud.CloudCatalog.recreateUi();
    }

    private static String sha256Hex(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            in.close();
            return hex(md.digest());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
