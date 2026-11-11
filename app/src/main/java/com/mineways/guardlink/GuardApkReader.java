package com.mineways.guardlink;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 安装包体检的 Android 采集层：把用户选中的 APK 解成 {@link GuardApkAudit.Meta}。
 *
 * <p>只读压缩包目录与包管理器元数据，<b>不加载代码、不执行任何东西</b>；
 * 全程在守护的工作线程里跑，不碰主线程。
 *
 * <p>两条保命线：文件先复制到应用缓存目录再解析（内容 URI 不能直接喂给 ZipFile）；
 * 任何一步失败都只把对应事实标成「读不到」，由规则层给出保守结论，绝不让体检本身崩掉。
 */
public final class GuardApkReader {

    private GuardApkReader() {
    }

    public static GuardApkAudit.Meta read(Context ctx, Uri uri) {
        GuardApkAudit.Meta m = new GuardApkAudit.Meta();
        File tmp = new File(ctx.getCacheDir(), "guard-audit-" + System.currentTimeMillis() + ".apk");
        try {
            copy(ctx, uri, tmp);
            scanZip(tmp, m);
            scanPackage(ctx, tmp, m);
        } catch (Throwable t) {
            // 连复制都失败：这份文件根本不是可读的安装包
            m.zipUnreadable = true;
        } finally {
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
        return m;
    }

    private static void copy(Context ctx, Uri uri, File dst) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("openInputStream 返回 null");
        }
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void scanZip(File apk, GuardApkAudit.Meta m) {
        try {
            ZipFile zf = new ZipFile(apk);
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String name = e.getName();
                    if (name == null) {
                        continue;
                    }
                    m.entryCount++;
                    String lower = name.toLowerCase(Locale.ROOT);
                    // 云控静态特征（entry:/asset:）要按条目名匹配；上限防止畸形包堆爆内存
                    if (m.entries.size() < 4000) {
                        m.entries.add(lower);
                    }
                    if (lower.endsWith(".dex")) {
                        m.dexEntryCount++;
                        String base = lower.substring(lower.lastIndexOf('/') + 1);
                        // 根目录只允许 classes.dex / classes2.dex ...；其余路径一律算隐藏载荷
                        if (!base.matches("classes\\d*\\.dex")) {
                            m.hiddenDexCount++;
                        }
                    }
                    if (lower.startsWith("res/")) {
                        m.resEntryCount++;
                    }
                    if (lower.contains("scrcpy")) {
                        m.hasScrcpy = true;
                    }
                    if (lower.contains("spake2")) {
                        m.hasSpake2 = true;
                    }
                    if ("androidmanifest.xml".equals(lower)) {
                        long size = e.getSize();
                        if (size > 0) {
                            m.manifestBytes = size;
                        }
                    }
                }
            } finally {
                zf.close();
            }
        } catch (Throwable t) {
            m.zipUnreadable = true;
        }
    }

    private static void scanPackage(Context ctx, File apk, GuardApkAudit.Meta m) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= 28) {
                flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            }
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (pi == null) {
                m.manifestUnparsable = true;
                return;
            }
            m.pkg = pi.packageName;
            if (pi.applicationInfo != null) {
                // Android 8+ 需要把 sourceDir 指回去，getApplicationLabel 才读得到资源
                pi.applicationInfo.sourceDir = apk.getAbsolutePath();
                pi.applicationInfo.publicSourceDir = apk.getAbsolutePath();
                try {
                    CharSequence label = pm.getApplicationLabel(pi.applicationInfo);
                    m.label = label == null ? null : label.toString();
                } catch (Throwable ignored) {
                    m.label = null;
                }
            }
            if (pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    applyPermission(m, p);
                }
            }
            if (pi.services != null) {
                for (ServiceInfo si : pi.services) {
                    if (si != null) {
                        applyBoundPermission(m, si.permission);
                    }
                }
            }
            if (pi.receivers != null) {
                for (ActivityInfo ri : pi.receivers) {
                    if (ri == null) {
                        continue;
                    }
                    if ("android.permission.BIND_DEVICE_ADMIN".equals(ri.permission)
                            || (ri.metaData != null
                            && ri.metaData.containsKey("android.app.device_admin"))) {
                        m.deviceAdminReceiver = true;
                    }
                    applyBoundPermission(m, ri.permission);
                }
            }
            m.componentCount = len(pi.activities) + len(pi.services)
                    + len(pi.receivers) + len(pi.providers);
            collectComponents(m, pi);
            m.signerCn = signerCn(pi);
        } catch (Throwable t) {
            m.manifestUnparsable = true;
        }
    }

    private static int len(Object[] a) {
        return a == null ? 0 : a.length;
    }

    /** 组件全名（pkg/cls）收进 Meta，供云控 component: 特征匹配；上限防止超大清单堆爆内存。 */
    private static void collectComponents(GuardApkAudit.Meta m, PackageInfo pi) {
        addComponents(m, pi.activities);
        addComponents(m, pi.services);
        addComponents(m, pi.receivers);
        addComponents(m, pi.providers);
    }

    private static void addComponents(GuardApkAudit.Meta m, android.content.pm.PackageItemInfo[] items) {
        if (items == null) {
            return;
        }
        for (android.content.pm.PackageItemInfo it : items) {
            if (it == null || it.name == null || m.components.size() >= 200) {
                continue;
            }
            m.components.add(it.packageName + "/" + it.name);
        }
    }

    private static void applyBoundPermission(GuardApkAudit.Meta m, String perm) {
        if (perm == null) {
            return;
        }
        switch (perm) {
            case "android.permission.BIND_ACCESSIBILITY_SERVICE":
                m.a11yService = true;
                break;
            case "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE":
                m.notificationListenerService = true;
                break;
            case "android.permission.BIND_VPN_SERVICE":
                m.vpnService = true;
                break;
            case "android.permission.BIND_DEVICE_ADMIN":
                m.deviceAdminReceiver = true;
                break;
            default:
                break;
        }
    }

    private static void applyPermission(GuardApkAudit.Meta m, String p) {
        if (p == null) {
            return;
        }
        if (!m.permissions.contains(p)) {
            m.permissions.add(p);
        }
        applyBoundPermission(m, p);
        switch (p) {
            case "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION":
                m.mediaProjection = true;
                break;
            case "android.permission.REQUEST_DELETE_PACKAGES":
                m.requestDelete = true;
                break;
            case "android.permission.SYSTEM_ALERT_WINDOW":
                m.overlay = true;
                break;
            case "android.permission.RECEIVE_BOOT_COMPLETED":
                m.boot = true;
                break;
            case "android.permission.READ_SMS":
            case "android.permission.RECEIVE_SMS":
                m.sms = true;
                break;
            case "android.permission.READ_CONTACTS":
                m.contacts = true;
                break;
            case "android.permission.RECORD_AUDIO":
                m.recordAudio = true;
                break;
            case "android.permission.CAMERA":
                m.camera = true;
                break;
            case "android.permission.MANAGE_EXTERNAL_STORAGE":
                m.manageStorage = true;
                break;
            case "android.permission.REQUEST_INSTALL_PACKAGES":
                m.installPackages = true;
                break;
            case "android.permission.READ_PHONE_STATE":
                m.readPhoneState = true;
                break;
            default:
                break;
        }
    }

    private static String signerCn(PackageInfo pi) {
        try {
            android.content.pm.Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) {
                return null;
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(sigs[0].toByteArray()));
            return cert.getSubjectX500Principal().getName();
        } catch (Throwable t) {
            return null;
        }
    }
}
