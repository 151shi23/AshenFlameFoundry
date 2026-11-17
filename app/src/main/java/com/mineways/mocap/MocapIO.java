package com.mineways.mocap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 关键点 CSV 读写（mocap4mc-poses v1 格式）。
 * Android 端 PoseCaptureBridge 输出同格式，桌面端与真机端数据完全同源。
 *
 * 格式：
 *   # mocap4mc-poses v1
 *   # image 1280x720
 *   # fps 30
 *   t_ms,idx,x,y,z,vis[,pres]
 *
 * <p>pres（presence）是后加的**可选第 7 列**：旧 CSV 没有它，读作 -1（未知）。
 * 它和 vis 不是一回事 —— 脚在画面里但很小时 vis 可以低到 0.1，pres 仍接近 1；
 * 只看 vis 会把这些关节当"没看见"，位置退回画面正中（腿朝上）。
 */
public final class MocapIO {
    private MocapIO() {}

    public static final class Clip {
        public int imgW = 1280, imgH = 720;
        public double fps = 30;
        public final List<Pose33> frames = new ArrayList<Pose33>();
    }

    public static Clip readCsv(String path) throws IOException {
        Clip c = new Clip();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8));
        String line;
        Pose33 cur = null;
        try {
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("t_ms,")) {
                    if (line.startsWith("# image ")) {
                        String[] wh = line.substring(8).split("x");
                        c.imgW = (int) Double.parseDouble(wh[0]);
                        c.imgH = (int) Double.parseDouble(wh[1]);
                    } else if (line.startsWith("# fps ")) {
                        c.fps = Double.parseDouble(line.substring(6));
                    }
                    continue;
                }
                String[] p = line.split(",");
                double t = Double.parseDouble(p[0].trim());
                if (cur == null || Math.abs(cur.tMs - t) > 0.01) {
                    cur = new Pose33();
                    cur.tMs = t;
                    c.frames.add(cur);
                }
                int idx = (int) Double.parseDouble(p[1].trim());
                cur.x[idx] = Double.parseDouble(p[2].trim());
                cur.y[idx] = Double.parseDouble(p[3].trim());
                cur.z[idx] = Double.parseDouble(p[4].trim());
                cur.vis[idx] = Double.parseDouble(p[5].trim());
                cur.pres[idx] = p.length >= 7 ? Double.parseDouble(p[6].trim()) : -1.0;
            }
        } finally {
            r.close();
        }
        return c;
    }

    public static void writeCsv(Clip c, String path) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8));
        try {
            w.write("# mocap4mc-poses v1");
            w.newLine();
            w.write("# image " + c.imgW + "x" + c.imgH);
            w.newLine();
            w.write("# fps " + c.fps);
            w.newLine();
            w.write("t_ms,idx,x,y,z,vis,pres");
            w.newLine();
            for (Pose33 f : c.frames)
                for (int i = 0; i < 33; i++) {
                    w.write(String.format(java.util.Locale.US,
                        "%.1f,%d,%.6f,%.6f,%.6f,%.3f,%.3f%n",
                        f.tMs, i, f.x[i], f.y[i], f.z[i], f.vis[i], f.pres[i]));
                }
        } finally {
            w.close();
        }
    }
}
