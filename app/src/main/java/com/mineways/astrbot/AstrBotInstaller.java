package com.mineways.astrbot;

import android.content.Context;

import com.mineways.blender.ArReader;
import com.mineways.blender.DebIndex;
import com.mineways.blender.Downloader;
import com.mineways.blender.FileUtil;
import com.mineways.blender.RootShell;
import com.mineways.blender.TarExtractor;
import com.mineways.blender.Xz;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 一键部署：proot 运行时 → 两套 rootfs → AstrBot（PC 最新版 PyPI）→ 桥容器依赖 → NapCat / QQ。
 *
 * <p>所有下载与解包都在 Java 侧完成（落 /data/local/tmp，目录由 Shizuku chmod 777）；
 * 容器内的 apt / pip 用 {@link RootShell#stream} 流式跑，逐行回报到界面日志。</p>
 */
public final class AstrBotInstaller {

    public interface Progress {
        void onStatus(String msg);

        void onPercent(int percent);

        /** 安装过程的原始输出行（界面日志用）。 */
        void onLine(String line);
    }

    public static final class Failed extends Exception {
        public Failed(String message) {
            super(message);
        }
    }

    private static final String[] PROOT_PKGS = {"proot", "libtalloc", "libandroid-shmem"};
    private static final String TERMUX_SUITE = "stable";
    private static final String TERMUX_ARCH = "aarch64";

    private AstrBotInstaller() {
    }

    // ---------------------------------------------------------------- 主流程

    public static void deploy(Context app, Progress pr, Downloader.Cancel cancel) throws Exception {
        if (!AstrBotEnv.enabled(app)) {
            throw new Failed("云控未开放（astrbot_cfg.enabled=0）");
        }
        if (AstrBotEnv.noDeploy(app)) {
            throw new Failed("云控已临时禁止部署（维护中）");
        }
        ensureRuntime(app, pr);
        final String ver = AstrBotEnv.version(app);
        pr.onStatus("准备部署 AstrBot " + ver + "（电脑最新版，手机内运行）…");
        pr.onPercent(2);

        // 1) rootfs 地址：云控优先，未配置时按 proot-distro 最新 release 解析
        pr.onStatus("解析系统镜像地址…");
        final String ubuntuUrl = pick(AstrBotEnv.ubuntuUrl(app),
                () -> ghLatestAsset("termux/proot-distro", "ubuntu-noble-aarch64", ".tar.xz"));
        final String bookUrl = pick(AstrBotEnv.bookwormUrl(app),
                () -> ghLatestAsset("termux/proot-distro", "debian-bookworm-aarch64", ".tar.xz"));
        if (ubuntuUrl.isEmpty() || bookUrl.isEmpty()) {
            throw new Failed("拿不到 rootfs 地址（GitHub 解析失败且云控未配置镜像地址）。\n"
                    + "请让云控下发 astrbot_ubuntu_url / astrbot_bookworm_url。");
        }
        pr.onPercent(5);

        // 2) 下载两套 rootfs
        pr.onStatus("下载 Ubuntu 24.04（AstrBot 容器）…");
        final File ubuntuTar = download(ubuntuUrl, new File(AstrBotEnv.dlDir(app), "ubuntu.tar.xz"), 5, 20, pr, cancel);
        pr.onStatus("下载 Debian bookworm（QQ 桥容器）…");
        final File bookTar = download(bookUrl, new File(AstrBotEnv.dlDir(app), "bookworm.tar.xz"), 20, 34, pr, cancel);

        // 3) 解包 rootfs
        pr.onStatus("解压 Ubuntu 根文件系统…");
        FileUtil.mkdirs(AstrBotEnv.ubuntu(app));
        extractTar(ubuntuTar, AstrBotEnv.ubuntu(app), pr, cancel);
        pr.onPercent(46);
        pr.onStatus("解压 Debian 根文件系统…");
        FileUtil.mkdirs(AstrBotEnv.bookworm(app));
        extractTar(bookTar, AstrBotEnv.bookworm(app), pr, cancel);
        pr.onPercent(56);
        seedRootfs(app, AstrBotEnv.ubuntu(app), pr);
        seedRootfs(app, AstrBotEnv.bookworm(app), pr);

        // 4) proot 运行时（Termux 源里的 Android ELF）
        pr.onStatus("安装 proot 运行时…");
        installProot(app, pr, cancel);
        pr.onPercent(64);
        probeProot(app, pr);

        // 5) AstrBot 本体（容器内 apt + venv + PyPI）
        pr.onStatus("容器内安装 Python 与 AstrBot（耗时较长，保持网络）…");
        runInRootfs(app, AstrBotEnv.ubuntu(app), AstrBotScripts.installAstrBot(app), "ASTRBOT_INSTALL_OK", pr);
        pr.onPercent(78);

        // 6) 桥容器依赖
        pr.onStatus("容器内安装 Xvfb 与 QQ 运行依赖…");
        runInRootfs(app, AstrBotEnv.bookworm(app), AstrBotScripts.installBridgeDeps(app), "BRIDGE_DEPS_OK", pr);
        pr.onPercent(84);

        // 7) 两条 QQ 桥：NapCat（主）+ SnowLuma（ptrace 注入）
        pr.onStatus("安装 NapCat…");
        installNapCat(app, pr, cancel);
        pr.onPercent(88);
        if (AstrBotEnv.snowEnabled(app)) {
            pr.onStatus("安装 SnowLuma…");
            installSnowLuma(app, pr, cancel);
            pr.onStatus("SnowLuma 依赖（npm）…");
            runInRootfs(app, AstrBotEnv.bookworm(app), AstrBotScripts.installSnowDeps(app), "SNOW_DEPS_OK", pr);
        }
        if (AstrBotEnv.novncEnabled(app)) {
            pr.onStatus("安装 noVNC 备用桌面…");
            runInRootfs(app, AstrBotEnv.bookworm(app), AstrBotScripts.installNoVnc(app), "NOVNC_OK", pr);
        }
        pr.onPercent(90);

        // 8) QQ：优先桥项目自带，其次本地导入；没有就跳过（不私自下载）
        pr.onStatus("检查 Linux QQ…");
        final boolean qqOk = installQq(app, pr, cancel);
        pr.onPercent(95);

        // 9) 三端预配置 + 首启生成 cmd_config.json → 注入 platform
        pr.onStatus("写入三端配置并首启 AstrBot…");
        writeConfigs(app);
        firstRunAndPatch(app,
                new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/cmd_config.json"),
                pr, cancel);

        pr.onStatus("安装内置插件（表情包 / 主动消息 / 点歌 / 自我进化 / 天使的记忆 / QQ空间 / 资料管理）…");
        if (AstrBotEnv.pluginsEnabled(app)) {
            installPlugins(app, pr, cancel);
        } else {
            pr.onLine("[部署] 云控关闭了内置插件（astrbot_plugins=0），跳过。");
        }
        pr.onPercent(98);

        RootShell.sh("chmod -R a+rX " + RootShell.q(AstrBotEnv.root(app).getAbsolutePath()) + " 2>/dev/null; true");
        selfCheck(app, pr);
        AstrBotEnv.markDeployed(app, ver);
        pr.onPercent(100);
        pr.onStatus("部署完成：AstrBot " + ver + (qqOk
                ? " + QQ 已就绪。下一步点「启动」，然后扫码登录。"
                : "。尚未安装 Linux QQ —— NapCat / SnowLuma 未附带，点页面「安装 QQ」导入官方 arm64 安装包即可。"));
    }

    // ---------------------------------------------------------------- 运行目录

    public static void ensureRuntime(Context app, Progress pr) throws Failed {
        if (!RootShell.isReady()) {
            throw new Failed("需要 Shizuku 授权：部署要把 proot 与容器放到 /data/local/tmp"
                    + "（应用私有目录是 noexec，Linux 程序跑不起来）。请先安装并授权 Shizuku 后重试。");
        }
        final File root = AstrBotEnv.root(app);
        RootShell.Result r = RootShell.sh("mkdir -p " + RootShell.q(root.getAbsolutePath())
                + " && chmod 777 " + RootShell.q(root.getAbsolutePath()));
        if (!r.ok()) {
            throw new Failed("无法创建运行目录：Shizuku 未授权或已断开（" + r.out + "）");
        }
        final String subs = RootShell.q(AstrBotEnv.ubuntu(app).getAbsolutePath()) + " "
                + RootShell.q(AstrBotEnv.bookworm(app).getAbsolutePath()) + " "
                + RootShell.q(AstrBotEnv.prootDir(app).getAbsolutePath()) + " "
                + RootShell.q(AstrBotEnv.dlDir(app).getAbsolutePath()) + " "
                + RootShell.q(AstrBotEnv.logDir(app).getAbsolutePath()) + " "
                + RootShell.q(AstrBotEnv.shareDir(app).getAbsolutePath());
        RootShell.sh("mkdir -p " + subs + " && chmod -R 777 " + RootShell.q(root.getAbsolutePath()));
        if (pr != null) pr.onLine("[部署] 运行目录就绪：" + root);
    }

    /** 容器内 DNS / hosts /（可选）apt 镜像，解包后立刻写，apt 才能出网。 */
    private static void seedRootfs(Context app, File rootfs, Progress pr) {
        try {
            writeText(new File(rootfs, "etc/resolv.conf"),
                    "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n");
            File hosts = new File(rootfs, "etc/hosts");
            if (!hosts.isFile()) {
                writeText(hosts, "127.0.0.1 localhost\n::1 localhost\n");
            }
            String mirror = AstrBotEnv.aptMirror(app);
            if (!mirror.isEmpty()) {
                for (String f : new String[]{"etc/apt/sources.list"}) {
                    File sf = new File(rootfs, f);
                    if (!sf.isFile()) continue;
                    String text = FileUtil.readText(sf, 1 << 20);
                    text = text.replace("deb.debian.org", mirror)
                            .replace("archive.ubuntu.com", mirror)
                            .replace("ports.ubuntu.com", mirror)
                            .replace("security.debian.org", mirror);
                    writeText(sf, text);
                }
                pr.onLine("[部署] apt 镜像已切到 " + mirror);
            }
        } catch (Throwable t) {
            pr.onLine("! 预置 rootfs 配置失败：" + t);
        }
    }

    // ---------------------------------------------------------------- proot

    private static void installProot(Context app, Progress pr, Downloader.Cancel cancel) throws Exception {
        final String mirror = AstrBotEnv.termuxMirror(app);
        final File idxXz = new File(AstrBotEnv.dlDir(app), "termux-Packages.xz");
        final File idxTxt = new File(AstrBotEnv.dlDir(app), "termux-Packages");
        pr.onStatus("拉取 Termux 软件索引…");
        download(mirror + "/dists/" + TERMUX_SUITE + "/main/binary-" + TERMUX_ARCH + "/Packages.xz",
                idxXz, 62, 63, pr, cancel);
        if (!Xz.decompressToFile(idxXz, idxTxt)) {
            throw new Failed("Termux Packages 索引解压失败（地址：" + mirror + "）");
        }
        final DebIndex idx = DebIndex.parse(idxTxt, cancel);
        final List<String> missing = new ArrayList<>();
        final List<DebIndex.Pkg> closure = idx.closure(Arrays.asList(PROOT_PKGS), missing);
        if (closure.isEmpty()) {
            throw new Failed("Termux 索引里找不到 proot（镜像地址可能不对：" + mirror + "）");
        }
        if (!missing.isEmpty()) pr.onLine("[部署] proot 依赖里缺：" + missing);
        FileUtil.mkdirs(AstrBotEnv.prootDir(app));
        for (DebIndex.Pkg p : closure) {
            final File deb = new File(AstrBotEnv.dlDir(app), p.name + ".deb");
            downloadAny(DebIndex.urls(Arrays.asList(mirror), TERMUX_SUITE, TERMUX_ARCH, p),
                    deb, pr, cancel);
            extractDeb(deb, AstrBotEnv.prootDir(app));
        }
        final File bin = new File(AstrBotEnv.prootBin(app));
        if (!bin.isFile()) {
            throw new Failed("proot 解包后没找到可执行文件：" + bin);
        }
        RootShell.sh("chmod -R 755 " + RootShell.q(AstrBotEnv.prootDir(app).getAbsolutePath()) + "; "
                + "chmod 777 " + RootShell.q(AstrBotEnv.dlDir(app).getAbsolutePath()));
    }

    /** proot 自己也是 Android ELF：直接 --version 验证（不经过容器）。 */
    private static void probeProot(Context app, Progress pr) throws Failed {
        RootShell.Result r = RootShell.sh("LD_LIBRARY_PATH=" + RootShell.q(AstrBotEnv.prootLibs(app))
                + " " + RootShell.q(AstrBotEnv.prootBin(app)) + " --version 2>&1 | head -3");
        String out = r.out == null ? "" : r.out;
        if (!out.toLowerCase(Locale.ROOT).contains("proot")) {
            throw new Failed("proot 起不来：" + out
                    + "\n请把日志发给开发者；常见原因是该机型 /data/local/tmp 不可执行。");
        }
        pr.onLine("[部署] " + out.replace('\n', ' ').trim());
    }

    // ---------------------------------------------------------------- AstrBot

    /** 在容器里跑一段脚本，流式打印；必须看到 expect 才算成功。 */
    private static void runInRootfs(Context app, File rootfs, String script, String expect, Progress pr)
            throws Failed {
        final String cmd = AstrBotScripts.inRootfs(app, rootfs.getAbsolutePath(), script + "\necho __MW_DONE__");
        final boolean[] seen = {false};
        final StringBuilder tail = new StringBuilder();
        RootShell.Handle h = RootShell.stream(cmd, line -> {
            if (pr != null) pr.onLine(line);
            synchronized (tail) {
                tail.append(line).append('\n');
                if (tail.length() > 12000) tail.delete(0, tail.length() - 12000);
            }
            if (line.contains(expect)) seen[0] = true;
        });
        if (h == null) {
            throw new Failed("无法启动容器命令：Shizuku 已断开。");
        }
        final int code = h.waitFor();
        if (!seen[0] || code != 0) {
            throw new Failed("容器内安装失败（exit=" + code + "，未见 " + expect + "）。\n"
                    + "日志尾部：\n" + tail(tail));
        }
        if (pr != null) pr.onLine("[部署] 容器步骤完成：" + expect);
    }

    // ---------------------------------------------------------------- NapCat / QQ

    private static void installNapCat(Context app, Progress pr, Downloader.Cancel cancel) throws Exception {
        String url = AstrBotEnv.napcatUrl(app);
        if (url.isEmpty()) {
            pr.onStatus("解析 NapCat 最新 release…");
            url = ghLatestAsset("NapNeko/NapCatQQ", "NapCat.Shell", ".zip");
        }
        if (url.isEmpty()) {
            throw new Failed("拿不到 NapCat 下载地址（GitHub 解析失败且云控未配置 astrbot_napcat_url）。");
        }
        final File zip = downloadAny(Arrays.asList(url),
                new File(AstrBotEnv.dlDir(app), "napcat.zip"), pr, cancel);
        final File dest = new File(AstrBotEnv.bookworm(app), "opt/napcat");
        FileUtil.mkdirs(dest);
        unzip(zip, dest, cancel);
        // zip 可能带一层目录：把真正的入口目录找出来，重排到 /opt/napcat
        File entryDir = findEntryDir(dest);
        if (entryDir != null && !entryDir.equals(dest)) {
            File[] kids = entryDir.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    File dst = new File(dest, k.getName());
                    if (dst.exists()) FileUtil.deleteRec(dst);
                    if (!k.renameTo(dst)) copyTree(k, dst);
                }
                FileUtil.deleteRec(entryDir);
            }
        }
        pr.onLine("[部署] NapCat 安装到 " + entryDirOrSelf(dest));
    }

    /**
     * SnowLuma：TypeScript 的 OneBot v11 桥，通过 ptrace 注入原生 QQ 进程。
     * release 里带 node_modules 时 npm 步骤会直接跳过。
     */
    private static void installSnowLuma(Context app, Progress pr, Downloader.Cancel cancel) throws Exception {
        final File dest = new File(AstrBotEnv.bookworm(app), "opt/snowluma");
        if (new File(dest, "index.mjs").isFile()) {
            pr.onLine("[部署] 检测到已安装的 SnowLuma，跳过下载。");
            return;
        }
        String url = AstrBotEnv.snowLumaUrl(app);
        if (url.isEmpty()) {
            url = ghLatestZip("SnowLuma/SnowLuma", "snow");
        }
        if (url.isEmpty()) {
            throw new Failed("拿不到 SnowLuma 下载地址（GitHub 解析失败且云控未配置 astrbot_snowluma_url）。");
        }
        final File zip = downloadAny(Arrays.asList(url),
                new File(AstrBotEnv.dlDir(app), "snowluma.zip"), pr, cancel);
        FileUtil.mkdirs(dest);
        unzip(zip, dest, cancel);
        normalizeTopDir(dest);
        if (!new File(dest, "index.mjs").isFile()) {
            throw new Failed("SnowLuma 包里没有 index.mjs，可能下载到了错误的资产。\n"
                    + "请在云控里把 astrbot_snowluma_url 指到官方 release 的 zip。");
        }
        pr.onLine("[部署] SnowLuma 安装到 " + dest.getAbsolutePath());
    }

    /** GitHub release 里找第一个 zip 资产（可带名字关键字过滤）。 */
    private static String ghLatestZip(String repo, String keyword) {
        try {
            final String json = Downloader.text("https://api.github.com/repos/" + repo
                    + "/releases/latest", 4 << 20, "MinewaysMobile-AstrBot");
            final Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.zip)\"")
                    .matcher(json);
            String fallback = "";
            while (m.find()) {
                final String url = m.group(1);
                if (url.toLowerCase(Locale.ROOT).contains("docker")) continue;
                if (fallback.isEmpty()) fallback = url;
                if (url.toLowerCase(Locale.ROOT).contains(keyword)) return url;
            }
            return fallback;
        } catch (Throwable t) {
            return "";
        }
    }

    /** zip 自带一层目录时，把里面的内容整体提到目标根。 */
    private static void normalizeTopDir(File dest) {
        if (new File(dest, "index.mjs").isFile() || new File(dest, "package.json").isFile()) return;
        File entryDir = findEntryDir(dest);
        if (entryDir == null || entryDir.equals(dest)) return;
        File[] kids = entryDir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File dst = new File(dest, k.getName());
            if (dst.exists()) FileUtil.deleteRec(dst);
            if (!k.renameTo(dst)) copyTree(k, dst);
        }
        FileUtil.deleteRec(entryDir);
    }

    private static File findEntryDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isFile() && (k.getName().equals("napcat.mjs") || k.getName().equals("napcat.js")
                    || k.getName().equals("index.js"))) {
                return dir;
            }
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                File r = findEntryDir(k);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String entryDirOrSelf(File dir) {
        return dir.getAbsolutePath();
    }

    /** 装 QQ：优先用桥项目 release 里自带的 arm64 包；没有就让用户导入，绝不私自去官网下载。 */
    private static boolean installQq(Context app, Progress pr, Downloader.Cancel cancel) throws Exception {
        final File qqDir = new File(AstrBotEnv.bookworm(app), "opt/QQ");
        if (isQqInstalled(app)) {
            pr.onLine("[部署] 检测到已安装的 QQ，跳过。");
            return true;
        }
        File deb = null;
        final String cloud = AstrBotEnv.qqDebUrl(app);
        if (!cloud.isEmpty()) {
            pr.onLine("[部署] 使用云控指定的 QQ 安装包。");
            deb = downloadAny(Arrays.asList(cloud),
                    new File(AstrBotEnv.dlDir(app), "qq-arm64.deb"), pr, cancel);
        } else {
            final String bundled = ghLatestAssetContaining("NapNeko/NapCatQQ", "qq", ".deb");
            final String bundled2 = bundled.isEmpty()
                    ? ghLatestAssetContaining("SnowLuma/SnowLuma", "qq", ".deb") : "";
            final String url = !bundled.isEmpty() ? bundled : bundled2;
            if (!url.isEmpty()) {
                pr.onLine("[部署] 使用桥项目自带的 QQ 安装包：" + url);
                deb = downloadAny(Arrays.asList(url),
                        new File(AstrBotEnv.dlDir(app), "qq-arm64.deb"), pr, cancel);
            }
        }
        if (deb == null) {
            final String picked = AstrBotEnv.qqDebPath(app);
            if (!picked.isEmpty() && new File(picked).isFile()) {
                deb = new File(picked);
            }
        }
        if (deb == null) {
            pr.onLine("[部署] NapCat / SnowLuma 的 release 未附带 Linux QQ，本次先不装。\n"
                    + "      部署完成后在页面上点「安装 QQ」导入官方 arm64 .deb 即可。");
            return false;
        }
        extractDeb(deb, AstrBotEnv.bookworm(app));
        ensureQqBin(app);
        return isQqInstalled(app);
    }

    /** 校验解包结果并把可执行位补齐；返回是否可用。 */
    private static boolean ensureQqBin(Context app) {
        final File qqDir = new File(AstrBotEnv.bookworm(app), "opt/QQ");
        final File bin = new File(qqDir, "qq");
        if (!bin.isFile()) return false;
        RootShell.sh("chmod -R 755 " + RootShell.q(qqDir.getAbsolutePath()) + " 2>/dev/null; true");
        return true;
    }

    public static boolean isQqInstalled(Context app) {
        return new File(AstrBotEnv.bookworm(app), "opt/QQ/qq").isFile();
    }

    /** 页面「安装 QQ」：用用户导入的 deb 装进桥容器（部署后补装也走这里）。 */
    public static void installPickedQq(Context app, Progress pr) throws Exception {
        ensureRuntime(app, pr);
        final String picked = AstrBotEnv.qqDebPath(app);
        if (picked.isEmpty() || !new File(picked).isFile()) {
            throw new Failed("还没有选安装包。");
        }
        pr.onStatus("解包 QQ 安装包…");
        extractDeb(new File(picked), AstrBotEnv.bookworm(app));
        if (!ensureQqBin(app)) {
            pr.onStatus("安装失败：包里没有 /opt/QQ/qq，可能不是 arm64 版。");
            throw new Failed("包里没有 /opt/QQ/qq，可能不是 arm64 版。");
        }
        pr.onStatus("QQ 已装进桥容器。");
    }

    /** GitHub release 里找第一个「URL 名包含关键字、以某后缀结尾」的资产。 */
    private static String ghLatestAssetContaining(String repo, String keyword, String suffix) {
        try {
            final String json = Downloader.text("https://api.github.com/repos/" + repo
                    + "/releases/latest", 4 << 20, "MinewaysMobile-AstrBot");
            final Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            while (m.find()) {
                final String url = m.group(1);
                final String lower = url.toLowerCase(Locale.ROOT);
                if (lower.endsWith(suffix) && lower.contains(keyword)) return url;
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 三端配置

    /**
     * 三端配置（部署与每次启动前都会调用，幂等）：
     * NapCat webui token + 已登录账号的 onebot11 反连；SnowLuma 全局 onebot/runtime。
     */
    public static void writeConfigs(Context app) throws IOException {
        final File book = AstrBotEnv.bookworm(app);
        String token = AstrBotEnv.napcatToken(app);
        if (token.isEmpty()) {
            token = app.getSharedPreferences("astrbot", Context.MODE_PRIVATE)
                    .getString("napcat_token", "");
        }
        if (token.isEmpty()) {
            token = AstrBotScripts.randomToken();
        }
        // 记住 token：扫码页 URL 要用（/?token=…）
        app.getSharedPreferences("astrbot", Context.MODE_PRIVATE).edit()
                .putString("napcat_token", token).apply();
        writeText(new File(book, "opt/napcat/config/webui.json"), AstrBotScripts.napcatWebui(token));
        writeText(new File(book, "opt/napcat/config/webui.json.bak"), AstrBotScripts.napcatWebui(token));

        final String uin = AstrBotEnv.botUin(app);
        if (!uin.isEmpty()) {
            writeText(new File(book, "opt/napcat/config/onebot11_" + uin + ".json"),
                    AstrBotScripts.napcatOneBot(app));
        }

        // SnowLuma 全局配置（账号级 onebot_<uin>.json 由它的 WebUI 生成，全局默认已覆盖）
        writeText(new File(book, "opt/snowluma/config/onebot.json"), AstrBotScripts.snowOneBot(app));
        writeText(new File(book, "opt/snowluma/config/runtime.json"), AstrBotScripts.snowRuntime(app));
    }

    /**
     * 首启一次生成 data/cmd_config.json（AstrBot 自己会补全默认键），
     * 等文件出现 → 停掉 → 用本机模板合并 → 写入手机专属的 platform / admins。
     */
    private static void firstRunAndPatch(Context app, File cfg, Progress pr, Downloader.Cancel cancel)
            throws Exception {
        if (cfg.isFile()) {
            applySeedAndPlatform(app, cfg, pr);
            pr.onLine("[部署] 已存在 AstrBot 配置，按本机模板合并完成。");
            return;
        }
        RootShell.sh(AstrBotScripts.startAstrBotFirstRun(app));
        pr.onStatus("等待 AstrBot 生成初始配置（首次启动 1–3 分钟）…");
        long deadline = System.currentTimeMillis() + 240_000L;
        boolean ok = false;
        while (System.currentTimeMillis() < deadline) {
            if (cancel != null && cancel.isCancelled()) throw new IOException("已取消");
            if (cfg.isFile() && cfg.length() > 0) {
                ok = true;
                break;
            }
            Thread.sleep(2000);
        }
        RootShell.sh(AstrBotScripts.stopAstrBot(app));
        if (!ok) {
            final String tailLog = RootShell.cat(AstrBotEnv.astrbotLog(app).getAbsolutePath(), 8000);
            throw new Failed("AstrBot 首启 240 秒内没有生成 data/cmd_config.json。\n"
                    + "日志尾部：\n" + tailLog);
        }
        scrapeDashboardCred(app, pr);
        applySeedAndPlatform(app, cfg, pr);
    }

    /**
     * 用打进 APK 的本机模板（assets/astrbot/cmd_config.seed.json，源自用户电脑上的
     * backend/data/cmd_config.json，已剔除模型/人格/平台/管理员/面板凭据）覆盖默认配置。
     * 平台与管理员始终由手机端自己写。
     */
    private static void applySeedAndPlatform(Context app, File cfg, Progress pr) {
        try {
            final JSONObject cur = new JSONObject(stripBom(FileUtil.readText(cfg, 4 << 20)));
            if (!AstrBotEnv.seedEnabled(app)) {
                pr.onLine("[部署] 云控关闭了本机模板（astrbot_seed=0），保留 AstrBot 默认值。");
            } else {
                final String seedText = readAsset(app, "astrbot/cmd_config.seed.json");
                if (seedText == null || seedText.isEmpty()) {
                    pr.onLine("! 包内没有本机配置模板，保留 AstrBot 默认值。");
                } else {
                    final JSONObject seed = new JSONObject(stripBom(seedText));
                    final java.util.Set<String> skip = new java.util.HashSet<>(Arrays.asList(
                            "platform", "admins_id", "dashboard", "provider", "provider_sources", "persona"));
                    java.util.Iterator<String> it = seed.keys();
                    int n = 0;
                    while (it.hasNext()) {
                        String k = it.next();
                        if (skip.contains(k)) continue;
                        cur.put(k, seed.get(k));
                        n++;
                    }
                    pr.onLine("[部署] 已套用本机配置模板（" + n + " 项；模型/人格/面板凭据除外）。");
                }
            }
            final JSONArray plat = new JSONArray();
            plat.put(new JSONObject(AstrBotScripts.platformEntry(app)));
            cur.put("platform", plat);
            final JSONArray admins = new JSONArray();
            if (!AstrBotEnv.botUin(app).isEmpty()) admins.put(AstrBotEnv.botUin(app));
            cur.put("admins_id", admins);
            writeBom(cfg, cur.toString(2));
        } catch (Throwable t) {
            pr.onLine("! 套用本机配置模板失败：" + t);
        }
    }

    /** 首启日志里的 AstrBot 面板初始密码（WebView 登录页自动填）。 */
    private static void scrapeDashboardCred(Context app, Progress pr) {
        try {
            final String log = RootShell.cat(AstrBotEnv.astrbotLog(app).getAbsolutePath(), 300_000);
            String pass = "";
            // 优先明确标记；抓不到再退回通用的 password 行（最后一条生效）
            Matcher m = Pattern.compile("(?i)(?:initial password|初始密码|dashboard password)[:：= ]+([^\\s，,；;]+)")
                    .matcher(log);
            while (m.find()) pass = m.group(1);
            if (pass.isEmpty()) {
                m = Pattern.compile("(?i)password[:：= ]+([^\\s，,；;]+)").matcher(log);
                while (m.find()) pass = m.group(1);
            }
            if (!pass.isEmpty()) {
                AstrBotEnv.setAstrbotCred(app, "astrbot", pass);
                pr.onLine("[部署] 已记录 AstrBot 面板初始密码（打开面板会自动登录）。");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readAsset(Context app, String path) {
        try (InputStream in = app.getAssets().open(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String stripBom(String s) {
        return s != null && s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** AstrBot 读配置用 utf-8-sig（带 BOM），写回也保持 BOM。 */
    private static void writeBom(File f, String text) throws IOException {
        FileUtil.mkdirs(f.getParentFile());
        try (OutputStream os = new FileOutputStream(f, false)) {
            os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            os.write(text.getBytes("UTF-8"));
        }
        RootShell.sh("chmod 666 " + RootShell.q(f.getAbsolutePath()) + " 2>/dev/null; true");
    }

    /** 扫码拿到 QQ 号后补写 NapCat 的反向 WS 配置（文件名必须带 uin）。 */
    public static void writeNapcatOneBot(Context app, String uin) {
        if (uin == null || uin.isEmpty()) return;
        try {
            writeText(new File(AstrBotEnv.bookworm(app), "opt/napcat/config/onebot11_" + uin + ".json"),
                    AstrBotScripts.napcatOneBot(app));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 内置插件：把 APK assets/astrbot/plugins.zip 展开到 AstrBot 的 data/ 下。
     * 包内是用户本机在用的七个插件 + 对应 config/*.json（不含 __pycache__ / tests）。
     */
    private static void installPlugins(Context app, Progress pr, Downloader.Cancel cancel) throws Exception {
        final File dest = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data");
        FileUtil.mkdirs(dest);
        int count = 0;
        try (InputStream in = app.getAssets().open("astrbot/plugins.zip");
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (cancel != null && cancel.isCancelled()) throw new IOException("已取消");
                final File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath())) {
                    throw new IOException("插件 zip 条目越界：" + e.getName());
                }
                if (e.isDirectory()) {
                    FileUtil.mkdirs(out);
                    continue;
                }
                FileUtil.mkdirs(out.getParentFile());
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                }
                count++;
            }
        }
        RootShell.sh("chmod -R a+rX " + RootShell.q(dest.getAbsolutePath()) + " 2>/dev/null; true");
        pr.onLine("[部署] 内置插件已就位（" + count + " 个文件）："
                + "meme_manager / proactive_chat / music / self_evolution / angel_memory / qzone / qqprofile");
    }

    /** 已部署过但没有插件目录（旧版本升级上来）时补齐。 */
    private static void ensurePlugins(Context app, Progress pr) {
        try {
            if (!AstrBotEnv.pluginsEnabled(app)) return;
            final File dir = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/plugins");
            final String[] kids = dir.list();
            if (kids == null || kids.length == 0) {
                installPlugins(app, pr, Downloader.NEVER);
            }
        } catch (Throwable t) {
            pr.onLine("! 补齐内置插件失败：" + t);
        }
    }

    /** 一键重配：NapCat / SnowLuma / AstrBot 平台条目全部重写一遍（已部署后随时可用）。 */
    public static void reapplyAll(Context app, Progress pr) throws Exception {
        ensureRuntime(app, pr);
        pr.onStatus("重写 NapCat / SnowLuma 配置…");
        writeConfigs(app);
        final File cfg = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/cmd_config.json");
        if (cfg.isFile()) {
            pr.onStatus("套用本机 AstrBot 模板并写入平台条目…");
            applySeedAndPlatform(app, cfg, pr);
        }
        ensurePlugins(app, pr);
        pr.onStatus("配置已应用。重启桥后生效：点「停止」再「启动」。");
    }

    /** 部署末尾自检：关键文件缺失直接报错（比等到点启动才失败清楚）。 */
    private static void selfCheck(Context app, Progress pr) throws Failed {
        final File proot = new File(AstrBotEnv.prootBin(app));
        final File ubuntuSh = new File(AstrBotEnv.ubuntu(app), "bin/sh");
        final File bookSh = new File(AstrBotEnv.bookworm(app), "bin/sh");
        final File venv = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/venv/bin/python");
        final File cfg = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/cmd_config.json");
        final boolean qq = isQqInstalled(app);

        pr.onLine("[自检] proot " + mark(proot.isFile())
                + " · ubuntu " + mark(ubuntuSh.isFile())
                + " · bookworm " + mark(bookSh.isFile())
                + " · AstrBot " + mark(venv.isFile())
                + " · 配置 " + mark(cfg.isFile())
                + " · QQ " + (qq ? "✓" : "未装"));

        if (!proot.isFile() || !ubuntuSh.isFile() || !bookSh.isFile()) {
            throw new Failed("运行环境不完整（proot 或容器缺失），请重试部署。");
        }
        if (!venv.isFile()) {
            throw new Failed("AstrBot 没装进容器（venv 缺失）：多为 pip 安装失败，请查看上面的安装日志。");
        }
        if (!cfg.isFile()) {
            throw new Failed("AstrBot 配置未生成（cmd_config.json 缺失）：请查看首启日志。");
        }
        if (!qq) {
            pr.onLine("[自检] 提醒：Linux QQ 未安装，桥启动会失败；点「安装 QQ」导入 arm64 安装包。");
        }
        final File plugins = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/plugins");
        if (AstrBotEnv.pluginsEnabled(app)) {
            final String[] kids = plugins.list();
            if (kids == null || kids.length == 0) {
                pr.onLine("! [自检] 插件目录为空，内置插件可能没展开。");
            } else {
                pr.onLine("[自检] 插件目录 " + kids.length + " 项。");
            }
        }
    }

    private static String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }

    /** 解锁页面「重新应用配置」：需要已部署的环境。 */
    public static boolean canReapply(Context app) {
        return AstrBotEnv.enabled(app) && AstrBotEnv.deployed(app) && RootShell.isReady();
    }

    /** 从 NapCat 自己生成的配置文件里认登录成功的 QQ 号（日志抓不到时的兜底）。 */
    public static String findNapcatUin(Context app) {
        final File dir = new File(AstrBotEnv.bookworm(app), "opt/napcat/config");
        final File[] files = dir.listFiles((d, n) -> n.startsWith("onebot11_") && n.endsWith(".json"));
        if (files == null) return "";
        for (File f : files) {
            String n = f.getName();
            String uin = n.substring("onebot11_".length(), n.length() - ".json".length());
            if (uin.matches("\\d{5,12}")) return uin;
        }
        return "";
    }

    /** 扫码成功后由编排器调用：把 bot QQ 写进 admins_id。 */
    public static void writeAdmins(Context app, String uin) {
        final File cfg = new File(AstrBotEnv.ubuntu(app), "opt/astrbot/home/data/cmd_config.json");
        if (!cfg.isFile() || uin == null || uin.isEmpty()) return;
        try {
            final JSONObject cur = new JSONObject(stripBom(FileUtil.readText(cfg, 4 << 20)));
            final JSONArray admins = new JSONArray();
            admins.put(uin);
            cur.put("admins_id", admins);
            writeBom(cfg, cur.toString(2));
            AstrBotEnv.setBotUin(app, uin);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 工具

    private interface Resolver {
        String get() throws Exception;
    }

    private static String pick(String first, Resolver fallback) {
        if (first != null && !first.isEmpty()) return first;
        try {
            String v = fallback.get();
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** GitHub release 里按「文件名前缀 + 后缀」找资产下载地址。 */
    private static String ghLatestAsset(String repo, String prefix, String suffix) {
        try {
            final String json = Downloader.text("https://api.github.com/repos/" + repo
                    + "/releases/latest", 4 << 20, "MinewaysMobile-AstrBot");
            final Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            while (m.find()) {
                final String url = m.group(1);
                final String name = url.substring(url.lastIndexOf('/') + 1);
                if (name.startsWith(prefix) && name.endsWith(suffix)) {
                    return url;
                }
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static File download(String url, File dst, int from, int to,
                                 Progress pr, Downloader.Cancel cancel) throws IOException {
        return downloadAny(Arrays.asList(url), dst, pr, cancel, from, to);
    }

    private static File downloadAny(List<String> urls, File dst, Progress pr, Downloader.Cancel cancel)
            throws IOException {
        return downloadAny(urls, dst, pr, cancel, -1, -1);
    }

    private static File downloadAny(List<String> urls, File dst, Progress pr, Downloader.Cancel cancel,
                                    int from, int to) throws IOException {
        IOException last = null;
        for (String u : urls) {
            try {
                FileUtil.mkdirs(dst.getParentFile());
                Downloader.download(u, dst, null, (done, total) -> {
                    if (pr == null || from < 0 || total <= 0) return;
                    int p = from + (int) Math.min(to - from, (to - from) * done / total);
                    pr.onPercent(p);
                }, cancel);
                return dst;
            } catch (IOException e) {
                last = e;
                Downloader.discard(dst);
            }
        }
        throw last == null ? new IOException("下载失败：" + urls) : last;
    }

    private static void extractTar(File tar, File dest, Progress pr, Downloader.Cancel cancel)
            throws IOException {
        TarExtractor.extractArchive(tar, dest, name -> {
        }, cancel);
    }

    /** .deb → data.tar.* → 解到 rootfs 根（deb 里的 ./opt/...、./usr/... 原样落位）。 */
    private static void extractDeb(File deb, File rootfs) throws IOException {
        ArReader.Entry entry = ArReader.openData(deb);
        try (InputStream in = ArReader.decompress(entry)) {
            TarExtractor.extract(in, rootfs, null, Downloader.NEVER);
        } finally {
            FileUtil.closeQuietly(entry.stream());
        }
    }

    private static void unzip(File zip, File dest, Downloader.Cancel cancel) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                if (cancel != null && cancel.isCancelled()) throw new IOException("已取消");
                ZipEntry e = en.nextElement();
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath())) {
                    throw new IOException("zip 条目越界：" + e.getName());
                }
                if (e.isDirectory()) {
                    FileUtil.mkdirs(out);
                    continue;
                }
                FileUtil.mkdirs(out.getParentFile());
                try (InputStream in = zf.getInputStream(e);
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        RootShell.sh("chmod -R a+rX " + RootShell.q(dest.getAbsolutePath()) + " 2>/dev/null; true");
    }

    private static void copyTree(File src, File dst) {
        if (src.isDirectory()) {
            FileUtil.mkdirs(dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            try {
                FileUtil.copy(src, dst);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeText(File f, String text) throws IOException {
        FileUtil.mkdirs(f.getParentFile());
        FileUtil.writeText(f, text);
        RootShell.sh("chmod 666 " + RootShell.q(f.getAbsolutePath()) + " 2>/dev/null; true");
    }

    private static String tail(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString();
            return s.length() > 4000 ? s.substring(s.length() - 4000) : s;
        }
    }

    /** 卸载：停进程 + 删除整个运行目录。 */
    public static void uninstall(Context app) {
        RootShell.sh(AstrBotScripts.stopAll(app));
        RootShell.sh("rm -rf " + RootShell.q(AstrBotEnv.root(app).getAbsolutePath()));
        AstrBotEnv.markUndeployed(app);
        AstrBotEnv.setBotUin(app, "");
    }
}
