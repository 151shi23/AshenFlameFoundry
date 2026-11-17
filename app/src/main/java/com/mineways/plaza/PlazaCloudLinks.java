package com.mineways.plaza;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** 云盘条目只做外部跳转/复制提取码，不抓取、不登录、不绕过限制。 */
public final class PlazaCloudLinks {

    private PlazaCloudLinks() {
    }

    public static boolean open(Context ctx, PlazaItem item) {
        if (ctx == null || item == null || !item.isCloud()) return false;
        if (!PlazaItem.providerAllowed(item.provider) || !PlazaRepo.https(item.shareUrl)) return false;
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(item.shareUrl));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean copyCode(Context ctx, PlazaItem item) {
        if (ctx == null || item == null || item.accessCode.isEmpty()) return false;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("提取码", item.accessCode));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
