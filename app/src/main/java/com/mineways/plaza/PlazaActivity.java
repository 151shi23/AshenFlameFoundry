package com.mineways.plaza;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.widget.Toast;

/** 资源广场独立入口：直接承载 Compose 版资源页面，底部 Tab 仍由 MainActivity 的玻璃导航负责。 */
public class PlazaActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!PlazaFlags.on()) {
            Toast.makeText(this, "资源广场已被云端关闭", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        try {
            Window w = getWindow();
            w.setStatusBarColor(0xFF08090C);
            w.setNavigationBarColor(0xFF050506);
        } catch (Throwable ignored) {
        }
        setContentView(new PlazaTabView(this));
    }
}
