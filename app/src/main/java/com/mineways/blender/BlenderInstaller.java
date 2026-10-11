package com.mineways.blender;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 安装 / 校验 Blender 运行环境。
 *
 * <p>全过程都在 {@link RootShell} 建好的 {@code /data/local/tmp/mineways_blender} 里落盘，
 * 下载和解压由 App 侧自己做（纯 Java，不需要 root 写私有目录），只有「建目录 / 改权限 /
 * 跑进程」这三件事走 Shizuku。</p>
 */
public final class BlenderInstaller {

    public interface Progress {
        void onStatus(String msg);

        void onPercent(int percent);
    }

    private BlenderInstaller() {
    }

    /** 抛给 UI 看的失败原因（带处置建议）。 */
    public static final class InstallException extends Exception {
        public InstallException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- 运行目录

    /**
     * 建目录 + 确认「真的能执行」。
     * /data/local/tmp 一般是可执行的；个别机型是 noexec，这时用 root 挂个 tmpfs 顶上。
     */
    public static String ensureRuntime(Context c, Progress pr) throws InstallException {
        final File root = BlenderEnv.dir(c);
        RootShell.Result r = RootShell.sh("mkdir -p " + RootShell.q(root.getAbsolutePath())
                + " && chmod 777 " + RootShell.q(root.getAbsolutePath()));
        if (!r.ok()) {
            throw new InstallException("无法创建运行目录 " + root + "：Shizuku 未授权或已断开（" + r.out + "）");
        }
        final List<String> subs = new ArrayList<>();
        for (File d : BlenderEnv.allDirs(c)) {
            if (!d.equals(root)) subs.add(d.getAbsolutePath());
        }
        for (String s : subs) {
            //noinspection ResultOfMethodCallIgnored
            new File(s).mkdirs();
        }
        StringBuilder sb = new StringBuilder();
        for (String s : subs) {
            sb.append("mkdir -p ").append(RootShell.q(s)).append(" && chmod 777 ").append(RootShell.q(s)).append("; ");
        }
        RootShell.sh(sb.toString());

        if (probeExec(root)) return root.getAbsolutePath();

        if (RootShell.isRoot()) {
            pr.onStatus("该机型的 /data/local/tmp 不可执行，改用 root 挂载 tmpfs…");
            RootShell.sh("mount -t tmpfs -o size=4g tmpfs " + RootShell.q(root.getAbsolutePath()));
            for (String s : subs) {
                //noinspection ResultOfMethodCallIgnored
                new File(s).mkdirs();
            }
            RootShell.sh(sb.toString());
            if (probeExec(root)) return root.getAbsolutePath();
        }
        throw new InstallException("运行目录不可执行：" + root
                + "。Blender 是原生程序，必须放在可执行分区上；请确认 Shizuku 以 root 模式启动。");
    }

    /** 写一个 shebang 脚本并直接 exec，用来验证 exec 位与挂载点可执行性。 */
    private static boolean probeExec(File root) {
        try {
            final File probe = new File(root, ".mw_probe.sh");
            FileUtil.writeText(probe, "#!/system/bin/sh\necho MW_EXEC_OK\n");
            FileUtil.makeExecutable(probe);
            RootShell.sh("chmod 755 " + RootShell.q(probe.getAbsolutePath()));
            final RootShell.Result r = RootShell.sh(RootShell.q(probe.getAbsolutePath()));
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return r.ok() && r.out.contains("MW_EXEC_OK");
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 主流程

    public static void install(Context c, Progress pr, final Downloader.Cancel cancel) throws InstallException {
        ensureRuntime(c, pr);

        // 1) Debian 索引
        pr.onStatus("拉取 Debian 包索引…");
        pr.onPercent(3);
        final DebIndex index = fetchIndex(c, pr, cancel);

        // 2) 依赖闭包
        final List<String> roots = new ArrayList<>(Arrays.asList(BlenderEnv.PKGS_CORE));
        roots.addAll(Arrays.asList(BlenderEnv.PKGS_SOFTGL));
        final List<String> missing = new ArrayList<>();
        final List<DebIndex.Pkg> closure = index.closure(roots, missing);
        if (closure.isEmpty()) {
            throw new InstallException("包索引里找不到任何依赖包，镜像地址可能不对。");
        }

        // 3) 下载 + 解包 sysroot
        fetchAndUnpack(c, index, closure, true, pr, cancel, 5, 62);

        // 4) Blender 官方包
        pr.onStatus("下载 Blender（几百 MB，请保持网络）…");
        pr.onPercent(70);
        final File tarball = fetchBlender(c, pr, cancel);

        pr.onStatus("解压 Blender…");
        pr.onPercent(86);
        final File top = unpackBlender(c, tarball, pr, cancel);
        final File bin = new File(top, "blender");

        // 5) 权限 + 自检
        pr.onStatus("校验运行环境…");
        pr.onPercent(93);
        RootShell.sh("chmod -R a+rX " + RootShell.q(BlenderEnv.root(c)) + " 2>/dev/null; "
                + "chmod 755 " + RootShell.q(bin.getAbsolutePath()) + " " + RootShell.q(BlenderEnv.dir(c).getAbsolutePath()) + " 2>/dev/null");

        final String loader = resolveLoader(c);
        if (loader.isEmpty()) {
            throw new InstallException("sysroot 里没找到 " + BlenderEnv.loaderName() + "，glibc（libc6）可能没下全。");
        }
        final String libPath = BlenderEnv.libPath(c) + ":" + top.getAbsolutePath() + "/lib";
        final String env = "LD_LIBRARY_PATH=" + libPath + " HOME=" + BlenderEnv.homeDir(c).getAbsolutePath()
                + " TMPDIR=" + BlenderEnv.tmpDir(c).getAbsolutePath() + " LANG=C.UTF-8";

        final RootShell.Result lv = RootShell.sh(env + " " + RootShell.q(loader) + " --version 2>&1 | head -2");
        if (!lv.out.contains("GNU") && !lv.out.contains("glibc")) {
            throw new InstallException("glibc 加载器跑不起来：" + firstLine(lv.out));
        }

        final RootShell.Result bv = runBlender(c, loader, bin, libPath, top, "--version 2>&1 | head -3");
        final String version = parseVersion(bv.out);
        if (version.isEmpty()) {
            throw new InstallException("Blender 启动失败：" + firstLine(bv.out));
        }

        final boolean softGl = probeSoftwareGl(c, loader, bin, libPath, top);

        BlenderEnv.markInstalled(c, version, loader, bin.getAbsolutePath(), softGl);
        pr.onPercent(100);
        pr.onStatus("安装完成：Blender " + version + (softGl ? "（含软件 OpenGL）" : "（仅 Cycles CPU 渲染）"));
    }

    // ---------------------------------------------------------------- 各步骤

    /** 引擎探针结果：能不能起来、缺哪个 so。 */
    public static final class Probe {
        public boolean ok;
        public String version = "";
        public String output = "";
        public String missingLib = "";
        public String loader = "";
        public String libPath = "";
    }

    /** 跑一次 {@code blender --version}，解析成功与否 + 缺哪个共享库。 */
    public static Probe probeBlender(Context c) {
        final Probe p = new Probe();
        final String loader = BlenderEnv.loaderPath(c);
        final String bin = BlenderEnv.blenderBin(c);
        if (loader.isEmpty() || bin.isEmpty() || !new File(bin).isFile()) {
            p.output = "Blender 未安装";
            return p;
        }
        final File top = new File(bin).getParentFile();
        final String libPath = BlenderEnv.libPath(c) + ":" + top.getAbsolutePath() + "/lib";
        p.loader = loader;
        p.libPath = libPath;
        final RootShell.Result r = runBlender(c, loader, new File(bin), libPath, top, "--version 2>&1 | head -5");
        p.output = r.out;
        p.version = parseVersion(r.out);
        p.ok = !p.version.isEmpty();
        p.missingLib = parseMissingLib(r.out);
        return p;
    }

    /** 解析 glibc 加载器的报错：{@code error while loading shared libraries: libX.so.1}。 */
    static String parseMissingLib(String out) {
        if (out == null) return "";
        for (String line : out.split("\\r?\\n")) {
            final int i = line.indexOf("error while loading shared libraries:");
            if (i < 0) continue;
            String rest = line.substring(i + "error while loading shared libraries:".length()).trim();
            final int sp = rest.indexOf(' ');
            if (sp > 0) rest = rest.substring(0, sp);
            return rest.trim();
        }
        return "";
    }

    /**
     * 按包名往现有 sysroot 里补装（不清空，用于「自愈补依赖」和「可选组件」）。
     *
     * @return 实际新装了多少个包
     */
    public static int addPackages(Context c, String[] pkgNames, Progress pr, Downloader.Cancel cancel)
            throws InstallException {
        final DebIndex index = fetchIndex(c, pr, cancel);
        final List<String> roots = new ArrayList<>(Arrays.asList(pkgNames));
        final List<String> missing = new ArrayList<>();
        final List<DebIndex.Pkg> closure = index.closure(roots, missing);
        if (closure.isEmpty()) {
            throw new InstallException("索引里找不到这些包：" + roots);
        }
        fetchAndUnpack(c, index, closure, false, pr, cancel, 0, 100);
        return closure.size();
    }

    /** 可选组件安装（ffmpeg 等）。 */
    public static boolean installExtra(Context c, String key, String[] pkgs, Progress pr,
                                       Downloader.Cancel cancel) {
        try {
            addPackages(c, pkgs, pr, cancel);
            BlenderEnv.markExtra(c, key, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fetchAndUnpack(Context c, DebIndex index, List<DebIndex.Pkg> plan, boolean wipe,
                                       Progress pr, Downloader.Cancel cancel, int pctFrom, int pctTo)
            throws InstallException {
        long expectTotal = 0L;
        for (DebIndex.Pkg p : plan) {
            final long size = cachedDebSize(c, index, p);
            if (size > 0) expectTotal += size;
        }
        long got = 0L;
        final int span = Math.max(1, pctTo - pctFrom);
        for (DebIndex.Pkg p : plan) {
            if (cancel.isCancelled()) throw new InstallException("已取消");
            pr.onStatus("下载 " + p.display() + "…");
            final File deb = fetchDeb(c, index, p, pr, cancel);
            got += deb.length();
            final int pct = expectTotal > 0
                    ? (int) (pctFrom + span * Math.min(1.0, got / (double) expectTotal))
                    : pctFrom + span / 2;
            pr.onPercent(pct);
        }
        pr.onStatus("解包 sysroot（" + plan.size() + " 个包）…");
        unpackSysroot(c, plan, wipe, pr, cancel);
        RootShell.sh("chmod -R a+rX " + RootShell.q(BlenderEnv.root(c)) + " 2>/dev/null");
    }

    private static long cachedDebSize(Context c, DebIndex index, DebIndex.Pkg p) {
        final File deb = new File(BlenderEnv.debsDir(c),
                p.filename.substring(p.filename.lastIndexOf('/') + 1));
        if (deb.isFile()) return deb.length();
        final List<String> urls = DebIndex.urls(BlenderEnv.lines(BlenderEnv.debMirrors(c)),
                BlenderEnv.debSuite(c), BlenderEnv.debArch(), p);
        for (String url : urls) {
            final long n = Downloader.sizeOf(url);
            if (n > 0) return n;
        }
        return 0L;
    }

    private static DebIndex fetchIndex(Context c, Progress pr, Downloader.Cancel cancel) throws InstallException {
        final String arch = BlenderEnv.debArch();
        final String suite = BlenderEnv.debSuite(c);
        final File out = new File(BlenderEnv.dlDir(c), "Packages-" + arch + ".txt");
        if (out.isFile() && out.length() > 100_000) {
            try {
                return DebIndex.parse(out, cancel);
            } catch (IOException ignored) {
            }
        }
        final List<String> mirrors = BlenderEnv.lines(BlenderEnv.debMirrors(c));
        if (mirrors.isEmpty()) throw new InstallException("没有配置 Debian 镜像地址。");
        final List<String> urls = DebIndex.indexUrls(mirrors, suite, arch);
        IOException last = null;
        for (String url : urls) {
            if (cancel.isCancelled()) throw new InstallException("已取消");
            try {
                pr.onStatus("下载包索引：" + shortUrl(url));
                final File xz = new File(BlenderEnv.dlDir(c), "Packages-" + arch + ".xz");
                downloadFirst(url, xz, pr, cancel);
                if (!Xz.decompressToFile(xz, out)) {
                    throw new IOException("xz 解压失败");
                }
                pr.onPercent(5);
                return DebIndex.parse(out, cancel);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new InstallException("包索引下载失败：" + (last == null ? "无" : last.getMessage()));
    }

    private static File fetchDeb(Context c, DebIndex index, DebIndex.Pkg p, Progress pr,
                                Downloader.Cancel cancel) throws InstallException {
        final File dst = new File(BlenderEnv.debsDir(c), p.filename.substring(p.filename.lastIndexOf('/') + 1));
        if (dst.isFile() && !p.sha256.isEmpty()) {
            try {
                if (FileUtil.sha256(dst).equalsIgnoreCase(p.sha256)) return dst;
            } catch (IOException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        final List<String> mirrors = BlenderEnv.lines(BlenderEnv.debMirrors(c));
        final List<String> urls = DebIndex.urls(mirrors, BlenderEnv.debSuite(c), BlenderEnv.debArch(), p);
        IOException last = null;
        for (String url : urls) {
            try {
                downloadFirst(url, dst, pr, cancel);
                if (!p.sha256.isEmpty()) {
                    final String real = FileUtil.sha256(dst);
                    if (!real.equalsIgnoreCase(p.sha256)) {
                        //noinspection ResultOfMethodCallIgnored
                        dst.delete();
                        throw new IOException("SHA-256 不匹配");
                    }
                }
                return dst;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new InstallException("包下载失败：" + p.display() + "（" + (last == null ? "无镜像" : last.getMessage()) + "）");
    }

    private static void unpackSysroot(Context c, List<DebIndex.Pkg> plan, boolean wipe, Progress pr,
                                      Downloader.Cancel cancel) throws InstallException {
        final File sysroot = BlenderEnv.sysrootDir(c);
        if (wipe) {
            FileUtil.deleteRec(sysroot);
        }
        if (!sysroot.isDirectory() && !sysroot.mkdirs()) throw new InstallException("无法创建 sysroot 目录");
        int i = 0;
        for (DebIndex.Pkg p : plan) {
            if (cancel.isCancelled()) throw new InstallException("已取消");
            i++;
            pr.onStatus("解包 " + p.display());
            pr.onPercent(62 + 8 * i / Math.max(1, plan.size()));
            final File deb = new File(BlenderEnv.debsDir(c),
                    p.filename.substring(p.filename.lastIndexOf('/') + 1));
            try {
                final ArReader.Entry entry = ArReader.openData(deb);
                try (java.io.InputStream data = ArReader.decompress(entry)) {
                    TarExtractor.extract(data, sysroot, null, cancel);
                }
            } catch (IOException e) {
                throw new InstallException("解包 " + p.display() + " 失败：" + e.getMessage());
            }
        }
        // Debian 的 /lib、/bin 是指向 /usr 的软链（合并 /usr），这里重建出来，
        // 免得某些脚本按 /lib 找库时找不到。
        linkIfAbsent(sysroot, "lib", "usr/lib");
        linkIfAbsent(sysroot, "bin", "usr/bin");
        linkIfAbsent(sysroot, "sbin", "usr/sbin");
        FileUtil.makeTreeReadable(sysroot);
    }

    private static void linkIfAbsent(File root, String name, String target) {
        final File f = new File(root, name);
        if (f.exists()) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(target));
        } catch (Throwable ignored) {
        }
    }

    private static File fetchBlender(Context c, Progress pr, Downloader.Cancel cancel) throws InstallException {
        final List<String> urls = BlenderEnv.lines(BlenderEnv.blenderMirrors(c));
        if (urls.isEmpty()) throw new InstallException("没有配置 Blender 下载地址。");
        String name = "blender-linux-aarch64.tar.xz";
        try {
            final String tail = urls.get(0).substring(urls.get(0).lastIndexOf('/') + 1);
            name = tail;
        } catch (Throwable ignored) {
        }
        final File dst = new File(BlenderEnv.dlDir(c), name);
        IOException last = null;
        for (String url : urls) {
            if (cancel.isCancelled()) throw new InstallException("已取消");
            try {
                pr.onStatus("下载 Blender：" + shortUrl(url));
                downloadFirst(url, dst, pr, cancel);
                return dst;
            } catch (IOException e) {
                last = e;
                // 换一个镜像前，先确认这个源给的确实是 xz，别把错误页当压缩包解
                if (Xz.sniff(dst) != Xz.Kind.XZ) {
                    Downloader.discard(dst);
                }
            }
        }
        throw new InstallException("Blender 下载失败：" + (last == null ? "无镜像" : last.getMessage())
                + "。可以在下方「镜像地址」里换成别的源再试。");
    }

    private static File unpackBlender(Context c, File tarball, Progress pr, Downloader.Cancel cancel)
            throws InstallException {
        final File blDir = BlenderEnv.blenderDir(c);
        FileUtil.deleteRec(blDir);
        if (!blDir.mkdirs()) throw new InstallException("无法创建 Blender 目录");
        if (tarball == null || !tarball.isFile()) throw new InstallException("下载目录里找不到 Blender 压缩包。");
        pr.onStatus("解压 " + FileUtil.human(tarball.length()) + "…");
        try {
            TarExtractor.extractArchive(tarball, blDir, new TarExtractor.Progress() {
                @Override
                public void onFile(String n) {
                }
            }, cancel);
        } catch (IOException e) {
            throw new InstallException("Blender 解压失败：" + e.getMessage());
        }
        final File top = findBlenderTop(blDir);
        if (top == null) throw new InstallException("Blender 压缩包结构异常：没找到可执行文件。");
        final File bin = new File(top, "blender");
        FileUtil.makeExecutable(bin);
        return top;
    }

    private static File findBlenderTop(File blDir) {
        final File[] kids = blDir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory() && new File(k, "blender").isFile()) return k;
        }
        return null;
    }

    // ---------------------------------------------------------------- 自检

    private static String resolveLoader(Context c) {
        final File s = BlenderEnv.sysrootDir(c);
        final String name = BlenderEnv.loaderName();
        final String[] candidates = {
                "/lib/" + name,
                "/usr/lib/" + name,
                "/usr/lib/" + BlenderEnv.triplet() + "/" + name,
                "/lib/" + BlenderEnv.triplet() + "/" + name,
        };
        for (String rel : candidates) {
            final File f = new File(s, rel);
            if (f.isFile()) {
                FileUtil.makeExecutable(f);
                return f.getAbsolutePath();
            }
        }
        return "";
    }

    private static RootShell.Result runBlender(Context c, String loader, File bin, String libPath,
                                               File top, String tail) {
        return runBlender(c, loader, bin, libPath, top, tail, "");
    }

    /** 跑一次 Blender；{@code extraEnv} 是额外的 export 行（图形环境等）。 */
    private static RootShell.Result runBlender(Context c, String loader, File bin, String libPath,
                                               File top, String tail, String extraEnv) {
        final String env = "LD_LIBRARY_PATH=" + libPath
                + " HOME=" + BlenderEnv.homeDir(c).getAbsolutePath()
                + " TMPDIR=" + BlenderEnv.tmpDir(c).getAbsolutePath()
                + " LANG=C.UTF-8 LC_ALL=C.UTF-8";
        final StringBuilder sb = new StringBuilder();
        sb.append(BlenderEnv.envPrefix(c));
        if (extraEnv != null && !extraEnv.trim().isEmpty()) {
            sb.append(extraEnv.startsWith("export ") ? extraEnv : "export " + extraEnv.trim() + "\n");
        }
        sb.append("cd ").append(RootShell.q(BlenderEnv.tmpDir(c).getAbsolutePath())).append('\n');
        sb.append("exec ").append(RootShell.q(loader))
                .append(" --library-path ").append(RootShell.q(libPath))
                .append(' ').append(RootShell.q(bin.getAbsolutePath()))
                .append(' ').append(tail).append('\n');
        return RootShell.sh(sb.toString());
    }

    /** 用 Blender 的 gpu 模块探一次 headless GL（按当前加速开关选软件/硬件）能不能起来。 */
    private static boolean probeSoftwareGl(Context c, String loader, File bin, String libPath, File top) {
        final String glEnv = BlenderEnv.gpuEnv(c, "BLENDER_EEVEE_NEXT", true);
        final String code = "import sys\n"
                + "try:\n"
                + "    import gpu\n"
                + "    print(\"MW_GL_OK\", gpu.platform.renderer_get())\n"
                + "except BaseException as e:\n"
                + "    print(\"MW_GL_FAIL\", e)\n";
        final File probe = new File(BlenderEnv.tmpDir(c), "glprobe.py");
        try {
            FileUtil.writeText(probe, code);
        } catch (IOException e) {
            return false;
        }
        final RootShell.Result r = runBlender(c, loader, bin, libPath, top,
                "--factory-startup -noaudio -b --python-expr "
                        + RootShell.q("exec(open(\"" + probe.getAbsolutePath() + "\").read())") + " 2>&1",
                glEnv);
        return r.out.contains("MW_GL_OK");
    }

    private static String parseVersion(String out) {
        for (String line : out.split("\\r?\\n")) {
            if (line.startsWith("Blender ")) {
                final String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) return parts[1];
            }
        }
        return "";
    }

    private static String firstLine(String s) {
        if (s == null) return "无输出";
        final int i = s.indexOf('\n');
        return i < 0 ? s.trim() : s.substring(0, i).trim();
    }

    private static String shortUrl(String url) {
        return url.replaceFirst("^https?://", "");
    }

    private static void downloadFirst(String url, File dst, Progress pr, final Downloader.Cancel cancel)
            throws IOException {
        IOException last = null;
        try {
            Downloader.download(url, dst, null, new Downloader.Progress() {
                @Override
                public void onProgress(long done, long total) {
                    pr.onStatus("下载 " + shortUrl(url) + " · " + FileUtil.human(done)
                            + (total > 0 ? " / " + FileUtil.human(total) : ""));
                }
            }, cancel);
            return;
        } catch (IOException e) {
            last = e;
        }
        throw last == null ? new IOException("下载失败") : last;
    }

    // ---------------------------------------------------------------- 卸载

    public static void uninstall(Context c) {
        RootShell.deleteRec(BlenderEnv.root(c));
        BlenderEnv.clearInstalled(c);
    }
}
