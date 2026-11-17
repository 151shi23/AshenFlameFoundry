package com.mineways.open;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.mineways.ObjPreviewActivity;
import com.mineways.prism.PrismDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部「打开方式 / 分享」的唯一入口（透明无 UI trampoline）。
 *
 * <p>取 {@code ACTION_VIEW.getData()} 或 {@code ACTION_SEND.EXTRA_STREAM}，按扩展名路由：
 * 0 个候选 → 不支持提示；1 个 → 直进；≥2 个 → <b>自建</b>选择弹窗。
 *
 * <p>弹窗不用系统 {@code AlertDialog.setSingleChoiceItems}：本工程暗色 Material3 主题
 * 没配列表项颜色，系统弹窗会「有框没选项」。这里走 {@link PrismDialog#list}，
 * 选项行显式给色，永远看得见。
 */
public class OpenWithActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri uri = pickUri(getIntent());
        if (uri == null) {
            finish();
            return;
        }
        String name = OpenInbox.nameOf(this, uri);
        String ext = OpenInbox.extOf(name);
        String mime = OpenInbox.mimeOf(this, uri);
        List<OpenRoute.Target> cands = OpenRoute.existing(OpenRoute.candidates(ext, mime));

        if (cands.isEmpty()) {
            unsupported(ext, uri, name);
            return;
        }
        if (cands.size() == 1) {
            launch(cands.get(0), uri, name);
            return;
        }
        chooser(cands, uri, name);
    }

    private Uri pickUri(Intent it) {
        if (it == null) {
            return null;
        }
        String action = it.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            return it.getData();
        }
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                return u;
            }
            ClipData clip = it.getClipData();
            return clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getUri() : null;
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ClipData clip = it.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                return clip.getItemAt(0).getUri();
            }
            ArrayList<Uri> list = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            return list != null && !list.isEmpty() ? list.get(0) : null;
        }
        return it.getData();
    }

    private void chooser(List<OpenRoute.Target> cands, Uri uri, String name) {
        long size = OpenInbox.sizeOf(this, uri);
        String head = name + (size >= 0 ? " · " + OpenInbox.humanSize(size) : "");
        List<PrismDialog.Item> items = new ArrayList<>();
        for (OpenRoute.Target t : cands) {
            final OpenRoute.Target ft = t;
            items.add(new PrismDialog.Item(t.label, t.desc, false, () -> launch(ft, uri, name)));
        }
        PrismDialog.list(this, "用哪个功能打开？", head, items, "取消", null);
    }

    private void unsupported(String ext, Uri uri, String name) {
        List<PrismDialog.Item> items = new ArrayList<>();
        if (classExists("com.mineways.vault.VaultActivity")) {
            items.add(new PrismDialog.Item("用文件保险箱加密", "未知格式也能加密成 .mwv", false,
                    () -> launchClass("com.mineways.vault.VaultActivity", null, null, uri, name)));
        }
        String msg = "没有能直接打开 ." + (ext.isEmpty() ? "?" : ext) + " 的功能。\n"
                + "文件可能被改了扩展名；可以换个功能试试，或先加密保存。";
        PrismDialog.list(this, "不支持的格式", msg, items, "取消", null);
    }

    private void launch(OpenRoute.Target t, Uri uri, String name) {
        launchClass(t.className, t.extraKey, t.extraVal, uri, name);
    }

    private void launchClass(String className, String extraKey, String extraVal, Uri uri, String name) {
        try {
            Class<?> cls = Class.forName(className);
            Intent it = new Intent(this, cls);
            if (ObjPreviewActivity.class.getName().equals(className)) {
                it.putExtra(ObjPreviewActivity.EXTRA_DOC_URI, uri.toString());
                it.putExtra(ObjPreviewActivity.EXTRA_LABEL, name);
            } else {
                it.putExtra(OpenInbox.EXTRA_OPEN_URI, uri);
                it.putExtra(OpenInbox.EXTRA_OPEN_NAME, name);
            }
            if (extraKey != null && extraVal != null) {
                it.putExtra(extraKey, extraVal);
            }
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            it.setClipData(ClipData.newRawUri("open", uri));
            startActivity(it);
        } catch (Throwable e) {
            PrismDialog.info(this, "打不开", "目标功能启动失败：" + e.getMessage(), null, null);
            return;
        }
        finish();
    }

    private static boolean classExists(String name) {
        try {
            Class<?> c = Class.forName(name);
            return Activity.class.isAssignableFrom(c);
        } catch (Throwable t) {
            return false;
        }
    }
}
