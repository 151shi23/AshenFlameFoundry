package com.mineways.blender;

import android.content.Context;

import java.util.List;

/**
 * 依赖自愈：Blender 启动失败时按报错里的 soname 自动补装对应的 Debian 包。
 *
 * <p>为什么需要：官方 Linux 包的外部依赖随版本、构建选项（Wayland/X11、音频、XR）变化，
 * 靠人肉维护清单迟早会漏。与其赌清单，不如让程序自己看报错 ——
 * {@code error while loading shared libraries: libXxf86vm.so.1} → 装 {@code libxxf86vm1}
 * → 再跑一次，直到 {@code blender --version} 出版本号为止（这也是「缺失就移植」的落地方式）。</p>
 */
public final class BlenderDeps {

    public interface Progress {
        void onStatus(String msg);
    }

    private static final int MAX_ROUNDS = 8;

    private BlenderDeps() {
    }

    /** @return 空串表示环境已就绪；否则是给用户看的失败原因 */
    public static String heal(Context c, Progress pr, Downloader.Cancel cancel) {
        int round = 0;
        while (round++ < MAX_ROUNDS) {
            if (cancel.isCancelled()) return "已取消";
            final BlenderInstaller.Probe probe = BlenderInstaller.probeBlender(c);
            if (probe.ok) {
                pr.onStatus("环境自检通过（补了 " + (round - 1) + " 轮依赖）。");
                return "";
            }
            final String lib = probe.missingLib;
            if (lib.isEmpty()) {
                pr.onStatus("启动失败但没报缺库，先按原样返回。");
                return "Blender 起不来：" + firstLine(probe.output);
            }
            final List<String> candidates = BlenderEnv.packageCandidates(lib);
            if (candidates.isEmpty()) {
                return "缺少共享库 " + lib + "，但认不出对应的 Debian 包名。可在「镜像设置」里换套件后重试。";
            }
            pr.onStatus("缺少 " + lib + "，尝试补装 " + candidates + "…");
            String installed = null;
            for (String pkg : candidates) {
                if (cancel.isCancelled()) return "已取消";
                try {
                    final int n = BlenderInstaller.addPackages(c, new String[]{pkg},
                            new BlenderInstaller.Progress() {
                                @Override
                                public void onStatus(String msg) {
                                    pr.onStatus(msg);
                                }

                                @Override
                                public void onPercent(int percent) {
                                }
                            }, cancel);
                    if (n > 0) {
                        installed = pkg;
                        break;
                    }
                } catch (Throwable t) {
                    pr.onStatus(pkg + " 装不上：" + t.getMessage());
                }
            }
            if (installed == null) {
                return "缺少共享库 " + lib + "，候选包都装不上：" + candidates;
            }
            pr.onStatus("已补装 " + installed + "，重试启动…");
        }
        return "补依赖超过 " + MAX_ROUNDS + " 轮仍未成功，请把日志发出来看。";
    }

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "无输出";
        final int i = s.indexOf('\n');
        return i < 0 ? s.trim() : s.substring(0, i).trim();
    }
}
