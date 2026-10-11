package com.mineways.blender;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * bpy 脚本宿主：在沙箱里跑一段 Python 改工程，并把结果用 JSON 回传给 App。
 *
 * <p>这是「工程编辑 / 材质 / UV / 体检」所有能力的底座。设计要点全是为了兜底：</p>
 * <ul>
 *   <li>脚本无论成功、抛异常、还是被 Ctrl-C，<b>一定会写结果文件</b>（try/finally 里 emit），
 *       所以 App 侧不会遇到「等半天没反应」；</li>
 *   <li>退出码单独解读：134=SIGABRT、139=SIGSEGV、137=SIGKILL（多半是 OOM 被杀），
 *       会翻译成人能看懂的原因；</li>
 *   <li>带超时，超时按「取消」处理而不是干等；</li>
 *   <li>打开工程用 {@code --factory-startup + --enable-autoexec}（工程里的 driver / 脚本
 *       要能跑），但 autoexec 可关（默认开，因为很多模板工程靠它驱动动画）。</li>
 * </ul>
 */
public final class BlenderOps {

    private BlenderOps() {
    }

    /** 脚本回传的结果。 */
    public static final class Result {
        public boolean ok;
        public String exitCode = "";
        public String crash = "";          // 非空 = 崩了（sigabrt / sigsegv / oom-killed）
        public String stdout = "";
        public String error = "";          // 宿主级错误（超时、结果文件缺失…）
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public JSONObject data = new JSONObject();

        public boolean hasErrors() {
            return !error.isEmpty() || !errors.isEmpty();
        }

        public String summary() {
            if (!error.isEmpty()) return error;
            if (!errors.isEmpty()) return errors.get(0);
            if (!crash.isEmpty()) return crash;
            return "";
        }
    }

    public interface Log {
        void onLog(String line);
    }

    /** 跑一段脚本（可选打开工程）。{@code code} 里可以用 {@code emit/warn/err} 三个辅助函数。 */
    public static Result run(Context c, File project, String code, int timeoutSeconds, Log log) {
        final Result res = new Result();
        if (!BlenderEnv.installed(c)) {
            res.error = "Blender 未安装";
            return res;
        }
        final String loader = BlenderEnv.loaderPath(c);
        final File bin = new File(BlenderEnv.blenderBin(c));
        if (loader.isEmpty() || !bin.isFile()) {
            res.error = "Blender 可执行文件缺失，请重新安装";
            return res;
        }
        try {
            final File tmp = BlenderEnv.tmpDir(c);
            FileUtil.mkdirs(tmp);
            final File script = new File(tmp, "mw_op.py");
            final File result = new File(tmp, "mw_result.json");
            //noinspection ResultOfMethodCallIgnored
            result.delete();
            FileUtil.writeText(script, bootstrap(c, result, code));
            FileUtil.makeExecutable(script);

            final StringBuilder cmd = new StringBuilder();
            cmd.append(BlenderEnv.envPrefix(c));
            cmd.append(resultEnv(result));
            cmd.append("cd ").append(RootShell.q(BlenderEnv.tmpDir(c).getAbsolutePath())).append('\n');
            if (project != null) {
                cmd.append("exec ").append(RootShell.q(loader))
                        .append(" --library-path ")
                        .append(RootShell.q(BlenderEnv.libPath(c) + ":"
                                + bin.getParentFile().getAbsolutePath() + "/lib"))
                        .append(' ').append(RootShell.q(bin.getAbsolutePath()))
                        .append(" --factory-startup --enable-autoexec -noaudio")
                        .append(" -b ").append(RootShell.q(project.getAbsolutePath()))
                        .append(" -P ").append(RootShell.q(script.getAbsolutePath()))
                        .append('\n');
            } else {
                cmd.append("exec ").append(RootShell.q(loader))
                        .append(" --library-path ")
                        .append(RootShell.q(BlenderEnv.libPath(c) + ":"
                                + bin.getParentFile().getAbsolutePath() + "/lib"))
                        .append(' ').append(RootShell.q(bin.getAbsolutePath()))
                        .append(" --factory-startup -noaudio")
                        .append(" -b -P ").append(RootShell.q(script.getAbsolutePath()))
                        .append('\n');
            }

            final StringBuilder out = new StringBuilder();
            final RootShell.Handle h = RootShell.stream(cmd.toString(), line -> {
                synchronized (out) {
                    if (out.length() < 1 << 18) {
                        out.append(line).append('\n');
                    }
                }
                if (log != null) log.onLog(line);
            });
            if (h == null) {
                res.error = "无法启动 Blender（Shizuku 未授权或已断开）";
                return res;
            }
            final int code0 = waitWithTimeout(h, timeoutSeconds);
            res.stdout = out.toString();
            res.exitCode = String.valueOf(code0);
            if (code0 == 124 || (h.isCancelled())) {
                res.error = "超时（" + timeoutSeconds + " 秒）被中止";
                return res;
            }
            res.crash = crashOf(code0, res.stdout);
            if (!result.isFile()) {
                res.error = res.error.isEmpty()
                        ? "脚本没有回传结果（exit=" + code0 + "）" : res.error;
                return res;
            }
            parseInto(res, result);
            return res;
        } catch (Throwable t) {
            res.error = "执行异常：" + t;
            return res;
        }
    }

    /** 等待进程，超时则销毁。返回退出码；超时返回 124。 */
    private static int waitWithTimeout(RootShell.Handle h, int timeoutSeconds) {
        if (timeoutSeconds <= 0) return h.waitFor();
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!h.isAlive()) return h.waitFor();
        }
        h.cancel();
        return 124;
    }

    static String crashOf(int code, String stdout) {
        // 137 = 128+9(SIGKILL)，Android 上多半是内存不足被 LMK 杀
        if (code == 137) return "进程被系统杀掉（137/SIGKILL），通常是内存不够";
        if (code == 139) return "段错误（139/SIGSEGV）";
        if (code == 134) return "进程中止（134/SIGABRT），常见于断言失败或 glibc 报错";
        if (code == 136) return "浮点异常（136/SIGFPE）";
        if (stdout != null) {
            if (stdout.contains("Segmentation fault")) return "段错误";
            if (stdout.contains("out of memory") || stdout.contains("Cannot allocate memory")) {
                return "内存不足";
            }
            if (stdout.contains("error while loading shared libraries")) return "缺少共享库";
        }
        return "";
    }

    private static void parseInto(Result res, File resultFile) {
        final String text = FileUtil.readText(resultFile, 1 << 20);
        if (text.isEmpty()) {
            res.error = "结果文件为空";
            return;
        }
        try {
            final JSONObject o = new JSONObject(text);
            res.ok = o.optBoolean("ok", false);
            final JSONArray errs = o.optJSONArray("errors");
            if (errs != null) {
                for (int i = 0; i < errs.length(); i++) {
                    res.errors.add(errs.optString(i));
                }
            }
            final JSONArray warns = o.optJSONArray("warnings");
            if (warns != null) {
                for (int i = 0; i < warns.length(); i++) {
                    res.warnings.add(warns.optString(i));
                }
            }
            res.data = o.optJSONObject("data") != null ? o.getJSONObject("data") : new JSONObject();
        } catch (Throwable t) {
            res.error = "结果解析失败：" + t;
        }
    }

    /**
     * 公共 bootstrap。
     *
     * <p>{@code RESULT} 用环境变量传进来而不是拼进脚本，避免路径里的引号问题；
     * {@code emit} 保证任何路径都会写出结果。</p>
     */
    private static String bootstrap(Context c, File result, String code) {
        return "import bpy, json, os, sys, math, traceback\n"
                + "MW_RESULT = os.environ.get(\"MW_RESULT\") or \"result.json\"\n"
                + "_DATA = {}\n"
                + "_ERR = []\n"
                + "_WARN = []\n"
                + "def emit(ok=True):\n"
                + "    try:\n"
                + "        with open(MW_RESULT, \"w\") as f:\n"
                + "            json.dump({\"ok\": bool(ok), \"data\": _DATA, \"errors\": _ERR, \"warnings\": _WARN}, f)\n"
                + "    except BaseException as e:\n"
                + "        pass\n"
                + "def err(m):\n"
                + "    _ERR.append(str(m))\n"
                + "def warn(m):\n"
                + "    _WARN.append(str(m))\n"
                + "def data(k, v):\n"
                + "    _DATA[k] = v\n"
                + "\n"
                + "try:\n"
                + indent(code, 1)
                + "    emit(True)\n"
                + "except BaseException as e:\n"
                + "    err(\"%s: %s\" % (type(e).__name__, e))\n"
                + "    err(traceback.format_exc().strip().replace(\"\\n\", \" | \"))\n"
                + "    emit(False)\n";
    }

    private static String indent(String code, int levels) {
        final StringBuilder pad = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            pad.append("    ");
        }
        final StringBuilder sb = new StringBuilder();
        for (String line : code.split("\\r?\\n")) {
            sb.append(pad).append(line).append('\n');
        }
        return sb.toString();
    }

    /** 生成一个只跑 Python 片段的执行环境变量（配合 {@link #run} 使用）。 */
    public static String resultEnv(File result) {
        return "MW_RESULT=" + RootShell.q(result.getAbsolutePath()) + "\n";
    }
}
