package com.mineimator.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件导入桥：原生引擎请求选文件 → 系统选择器 → 复制到应用私有目录 imports/ → 把绝对路径回填给原生。
 * 方法名与签名不可改（原生侧会静态调用 {@code open(Landroid/app/Activity;)V}）。
 */
public final class FilePicker {

    public static final FilePicker INSTANCE = new FilePicker();

    public static final int REQUEST_OPEN = 41;

    private static volatile boolean picking;

    private FilePicker() {
    }

    public boolean getPicking() {
        return picking;
    }

    public void setPicking(boolean value) {
        picking = value;
    }

    /** 由原生引擎调用：打开系统文件选择器。 */
    public static void open(final Activity activity) {
        picking = true;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                launch(activity);
            }
        });
    }

    private static void launch(Activity activity) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_OPEN);
        } catch (RuntimeException e) {
            picking = false;
            NativeHost.INSTANCE.nativePickResult("");
        }
    }

    /** 选完文件回来。取消 / 失败都回填空串。 */
    public void onResult(Activity activity, int resultCode, Intent data) {
        picking = false;
        final Uri uri = (data != null) ? data.getData() : null;
        if (resultCode != Activity.RESULT_OK || uri == null) {
            NativeHost.INSTANCE.nativePickResult("");
            return;
        }
        NativeHost.INSTANCE.nativePickResult(copyIntoImports(activity, uri));
    }

    private String copyIntoImports(Activity activity, Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            String name = displayName(activity, uri);
            if (name == null || name.trim().length() == 0) {
                name = "import.bin";
            }
            final File dest = new File(activity.getFilesDir(), "imports/" + name);
            final File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            in = activity.getContentResolver().openInputStream(uri);
            if (in == null) {
                return "";
            }
            out = new FileOutputStream(dest);
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return dest.getAbsolutePath();
        } catch (Throwable t) {
            return "";
        } finally {
            close(in);
            close(out);
        }
    }

    private String displayName(Activity activity, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver()
                    .query(uri, new String[]{"_display_name"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int idx = cursor.getColumnIndex("_display_name");
                if (idx >= 0) {
                    final String value = cursor.getString(idx);
                    return value != null ? value : "";
                }
            }
        } catch (Throwable ignored) {
        } finally {
            close(cursor);
        }
        return "";
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
