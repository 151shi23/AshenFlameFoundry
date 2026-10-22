package com.mineways;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.mineways.cloud.FeatureGate;
import com.mineways.home.HomeIndex;
import com.mineways.AiOps.Pending;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


/**
 * AI 可调用的本软件工具：目录、调用格式、解析与本地执行。
 *
 * <p><b>为什么在客户端做</b>：接口文档 §一 明确写了这条软件端通道「不提供论坛操作工具、
 * 不允许 App 指定模型」，服务端不做 function calling。所以调用协议完全由本类定义：
 * 系统提示词把「可用功能清单 + 输出格式」交给模型，模型在回答末尾输出一个
 * {@code ```mw } 代码块，客户端解析、执行、把结果以 {@code mw_result} 回喂进行下一轮。
 *
 * <p><b>开什么、怎么说，全由 {@link AiPolicy} 现读云控</b>：提示词里的工具段是按当前开放
 * 集临时拼出来的，被云控废掉的工具<b>连名字都不会出现在模型眼前</b>；执行侧再挡一次，
 * 免得模型凭记忆调一个已经关掉的工具。
 *
 * <p><b>不绕门禁</b>：打开功能一律走 {@link MainActivity#launchTool}，与点卡片同一条路
 * —— 激活码、专业版、云端「先问一句」一个都不跳过。
 */
public final class AiTools {

    /** 云控读取口：每条都现读，改配置不用重编 App。 */
    public static final AiPolicy.Flags FLAGS = (key, def) -> AiAccess.flag(key, def);

    private AiTools() {
    }

    /** 一次工具调用。 */
    public static final class Call {
        public final String tool;
        public final JSONObject args;
        /** 模型写的一句话理由（可空），显示在卡片上。 */
        public final String why;

        Call(String tool, JSONObject args, String why) {
            this.tool = tool;
            this.args = args == null ? new JSONObject() : args;
            this.why = why == null ? "" : why;
        }
    }

    /** 调用块的分隔标记：模型写在正文末尾，客户端显示前会剥掉。 */
    private static final String FENCE = "```mw";

    /** 界面喂进来的「用户现在在哪个页面」，进提示词，也决定 ask_page 有没有内容可问。 */
    private static volatile String pageContext = "";

    public static void setPageContext(String s) {
        pageContext = s == null ? "" : s.trim();
    }

    // ---------------------------------------------------------------- 提示词

    /**
     * 默认系统提示词：人设 + 本机状态 + <b>当前开放</b>的工具 + 调用格式 + 规则，
     * 最后才是真实功能清单（逐字取自 {@link HomeIndex}，云端改名也跟着变）。
     *
     * <p>清单放最后是因为整段有 4000 字上限（文档 §4.1）：宁可清单被截，
     * 也不能把「怎么调用」的规则截掉。
     */
    public static String prompt() {
        StringBuilder head = new StringBuilder();
        head.append("你是「烬火工坊」(AshenFlame Foundry) 安卓 App 内置的 AI 助手。")
                .append("这个 App 是 Minecraft 相关的工具箱，下面的工具能让你直接替用户操作它。");
        String ctx = pageContext;
        if (ctx.length() > 0) {
            head.append("\n\n当前：").append(ctx);
        }
        String mem = AiMemory.forPrompt();
        if (mem.length() > 0) {
            head.append(mem);
        }

        StringBuilder body = new StringBuilder();
        String tools = AiPolicy.toolLines(FLAGS);
        if (tools.trim().length() == 0) {
            body.append("\n\n这次对话没有开放任何工具：只用文字回答，")
                    .append("不要输出 ```mw 代码块，也不要声称自己打开了什么东西。");
        } else {
            body.append("\n\n可用工具：").append(tools);
            body.append("\n\n要调用工具时，在回答的最后单开一个代码块，块内只放一个 JSON：")
                    .append("\n").append(FENCE).append("\n")
                    .append("{\"calls\":[{\"tool\":\"open\",\"args\":{\"name\":\"粒子编辑器\"},")
                    .append("\"why\":\"你要的粒子编辑器在这个入口\"}]}")
                    .append("\n```\n");
            body.append("\n规则：");
            if (AiPolicy.toolAllowed(FLAGS, AiPolicy.T_OPEN)) {
                body.append("\n- open 一次只调用一个（打开页面是排他的，多写几个只会执行第一个）；");
                body.append("\n- 只有工具说明里写了「可接收内容」的页面才传 input，别把路径或文字硬塞给别的页面；");
            }
            if (AiPolicy.toolAllowed(FLAGS, AiPolicy.T_SEARCH)
                    || AiPolicy.toolAllowed(FLAGS, AiPolicy.T_DESCRIBE)) {
                body.append("\n- 功能名必须逐字取自下面的清单，不确定就先 search_tools 或 describe；");
            } else {
                body.append("\n- 功能名必须逐字取自下面的清单，别编名字；");
            }
            body.append("\n- 没必要时不要调用；调用失败就照实说，不要假装已经打开；")
                    .append("\n- 你会收到形如 {\"mw_result\":[...]} 的消息，那是上一条调用的执行结果，")
                    .append("收到后据此把话说完，不要再重复输出同一个调用；")
                    .append("\n- ops 这类会真出产物的作业，拿到 out 路径后要原样念给用户，"
                            + "不要改写、不要只说「已经转好了」。");
            if (AiPolicy.opAllowed(FLAGS, AiPolicy.OP_PACK)) {
                body.append("\n- 用户贴过来一个路径又让你「看着办」时，优先考虑 ops(op=pack) 直接转，"
                        + "而不是把页面打开让用户自己点。");
            }
            if (AiPolicy.suggest(FLAGS)) {
                body.append("\n\n回答的最后可以单起一行写下一步建议，格式严格照抄："
                        + "`mw_suggest: 建议一｜建议二｜建议三`（2~3 条，每条是一句用户可以直接问你的话）；"
                        + "没有值得建议的下一步就整行不写。");
            }
        }
        body.append("\n回答用简体中文，简洁直接，不确定就说不确定。")
                .append("不要提及你的模型名称、版本号或供应商。");

        String core = head.append(body).toString();
        // 整段有 4000 字上限（文档 §4.1）。清单是可挤掉的那一段，规则与工具不是 ——
        // 所以按剩余额度分级装配：装得下就全量，装不下换压掉空格的短名，再装不下只留分类。
        // 以前这里是无脑 substring(3900)，会把清单从名字中间砍断，模型拿着半截名字去 open 必然失败。
        String mode = AiPolicy.listMode(FLAGS);
        if ("ask".equals(mode)) {
            return core + LIST_TITLE + "\n（这次没把清单塞进提示词：要哪个功能先 list_tools 或 "
                    + "search_tools 现查，名字必须逐字用查到的那个，别凭印象编）";
        }
        int room = MAX_PROMPT - core.length() - LIST_TITLE.length();
        if (room < 80) {
            return core + LIST_TITLE + "\n（清单太长这次没塞下，用 list_tools 现查，别猜功能名）";
        }
        String full = featureList(true);
        String shortish = featureList(false);
        if (!"short".equals(mode) && full.length() <= room) {
            return core + LIST_TITLE + full;
        }
        if (shortish.length() <= room) {
            return core + LIST_TITLE + "\n（下面名称里的空格是我压掉的，open 时请还原成带空格的正式名）"
                    + shortish;
        }
        return core + LIST_TITLE + "\n（功能太多这次只塞下分类：" + categories()
                + "；具体名字用 list_tools 或 search_tools 现查）";
    }

    /** 整段 system 的字数上限（服务端 4000，留 100 字余量）。 */
    private static final int MAX_PROMPT = 3900;

    private static final String LIST_TITLE = "\n\n功能清单（分类 → 功能名，名称必须逐字照抄）：";

    /** 分类 → 「功能名」，被 {@code ai_features_off} 点名的功能直接不出现在提示词里。 */
    private static String featureList(boolean spaced) {
        StringBuilder sb = new StringBuilder();
        String lastCat = "";
        int n = 0;
        for (HomeIndex.Item it : safeItems()) {
            if (it == null || !it.visible || !AiPolicy.featureVisible(FLAGS, it.title)) {
                continue;
            }
            String cat = it.catName == null || it.catName.length() == 0 ? "其它" : it.catName;
            if (!cat.equals(lastCat)) {
                lastCat = cat;
                sb.append("\n【").append(cat).append("】");
            }
            String t = it.title == null ? "" : it.title;
            sb.append('「').append(spaced ? t : t.replace(" ", "")).append("」");
            n++;
        }
        return n == 0 ? "\n（这台机器上还没有可用的功能入口）" : sb.toString();
    }

    /** 分类名一行（装不下清单时的兜底）。 */
    private static String categories() {
        StringBuilder sb = new StringBuilder();
        try {
            for (HomeIndex.Cat c : HomeIndex.categories()) {
                if (c == null || c.name == null || c.name.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(c.name);
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "本地工具、在线站点" : sb.toString();
    }

    private static List<HomeIndex.Item> safeItems() {
        try {
            List<HomeIndex.Item> all = HomeIndex.all();
            return all == null ? new ArrayList<HomeIndex.Item>() : all;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 从模型回答里取出调用。<b>不依赖 ```mw 围栏</b>：直接在正文里找含 {@code "calls"} 的 JSON 对象，
     * 用花括号配平切出来（算法在 {@link AiProtocol}，那边有 JVM 断言）。围栏是模型自己写的，
     * 它写 {@code ```}、{@code ```json}、少写一个反引号、甚至不写围栏都很常见 ——
     * 上一版就是靠围栏匹配，漏掉的块直接以原文形式显示给了用户。
     */
    public static List<Call> parse(String answer) {
        List<Call> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        int i = 0;
        while (out.size() < 3) {
            int key = answer.indexOf("\"calls\"", i);
            if (key < 0) {
                break;
            }
            int open = answer.lastIndexOf('{', key);
            int close = open < 0 ? -1 : AiProtocol.matchBrace(answer, open);
            if (open < 0 || close < 0) {
                break;
            }
            appendCalls(out, answer.substring(open, close + 1));
            i = close + 1;
        }
        return out;
    }

    private static void appendCalls(List<Call> out, String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray list = o.optJSONArray("calls");
            if (list == null && o.has("tool")) {
                list = new JSONArray().put(o);      // 模型偶尔直接写单个调用，不套 calls
            }
            for (int k = 0; list != null && k < list.length() && out.size() < 3; k++) {
                JSONObject c = list.optJSONObject(k);
                if (c == null) {
                    continue;
                }
                String tool = normalize(c.optString("tool", ""));
                if (tool.length() == 0) {
                    continue;
                }
                out.add(new Call(tool, c.optJSONObject("args"), c.optString("why", "")));
            }
        } catch (Throwable ignored) {
            // 半截 JSON：当普通文本处理，不影响正文显示
        }
    }

    /** 从回答末尾取「下一步建议」（{@code mw_suggest: A｜B｜C}），最多 3 条；没有就回空列表。 */
    public static List<String> suggest(String answer) {
        return AiProtocol.suggest(answer);
    }

    /** 只把建议<b>那一行</b>从正文里剥掉（界面上画成能点的胶囊，不显示原始标记）。 */
    public static String stripSuggest(String answer) {
        return AiProtocol.stripSuggest(answer);
    }

    /** 现在可以直接执行哪些活（给 describe / 界面用的一句话）。 */
    public static String opsNote() {
        String s = AiPolicy.opLines(FLAGS);
        return s.length() == 0 ? "没有可直接执行的作业（只能打开页面）" : s;
    }

    /**
     * 剥掉协议噪声后的正文：调用块（围栏写不写 {@code mw} 都认）、{@code mw_suggest} 那行、
     * 以及流式过程里可能留下的孤立围栏。算法与断言在 {@link AiProtocol}。
     */
    public static String strip(String answer) {
        return AiProtocol.strip(answer);
    }

    /**
     * 流式期间界面该显示的那一段：从第一个协议标记处截断。
     *
     * <p>为什么不能等 {@code strip()}：模型是逐字吐的，围栏和 JSON 在半路上就已经显示到气泡里了；
     * 而 {@code onDone} 之后还可能有排队中的增量回调把原文追加回来。所以<b>显示这一侧自己挡住</b>，
     * 用户从头到尾都不会看到 {@code ```mw} 或 {@code mw_suggest}。
     */
    public static String visible(String raw) {
        return AiProtocol.visible(raw);
    }

    /** 模型爱写的别名与大小写都归一到 AiPolicy 里的正式名。 */
    private static String normalize(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "open_tool":
            case "launch":
            case "goto":
                return AiPolicy.T_OPEN;
            case "search":
            case "find":
                return AiPolicy.T_SEARCH;
            case "list":
                return AiPolicy.T_LIST;
            case "info":
                return AiPolicy.T_INFO;
            case "site":
                return AiPolicy.T_SITE;
            case "ask":
            case "ask_current_page":
                return AiPolicy.T_PAGE_ASK;
            case "what":
                return AiPolicy.T_DESCRIBE;
            default:
                return t;
        }
    }

    // ---------------------------------------------------------------- 执行

    /** 执行一次调用，返回给模型看的结果文本（同时够短，能塞回下一轮）。 */
    public static String exec(Context ctx, Call c) {
        Pending p = execPending(ctx, c);
        return p == null || p.result() == null ? "{\"ok\":false}" : p.result();
    }

    /**
     * 同上，但把<b>异步</b>留给界面：{@code ops} 这类真活可能跑十几秒，
     * 界面先画「正在算」，算完同一个 JSON 再回喂模型 —— 不能让主线程等，
     * 也不能让人对着静止的界面猜是不是卡死了。
     */
    public static Pending execPending(Context ctx, Call c) {
        if (c != null && AiPolicy.T_OPS.equals(c.tool)) {
            return ops(ctx, c);
        }
        JSONObject r = new JSONObject();
        try {
            r.put("tool", c.tool);
            if (!AiPolicy.toolAllowed(FLAGS, c.tool)) {
                r.put("ok", false);
                r.put("error", "「" + c.tool + "」这个工具已经被云端停用，这次对话里不可用");
                return Pending.now(r.toString());
            }
            switch (c.tool) {
                case AiPolicy.T_OPEN:
                    return Pending.now(open(ctx, r, arg(c.args, "name", "tool", "功能"),
                            arg(c.args, "input", "path", "text", "内容")));
                case AiPolicy.T_DESCRIBE:
                    return Pending.now(describe(r, arg(c.args, "name", "tool", "功能")));
                case AiPolicy.T_SEARCH:
                    return Pending.now(search(r, arg(c.args, "keyword", "q", "name")));
                case AiPolicy.T_LIST:
                    return Pending.now(list(r));
                case AiPolicy.T_INFO:
                    return Pending.now(info(ctx, r));
                case AiPolicy.T_SITE:
                    return Pending.now(openSite(ctx, r, arg(c.args, "name", "site", "url")));
                case AiPolicy.T_PAGE_ASK:
                    return Pending.now(askPage(ctx, r, arg(c.args, "question", "q", "ask")));
                default:
                    r.put("ok", false);
                    r.put("error", "没有这个工具：" + c.tool);
                    return Pending.now(r.toString());
            }
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", t.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
            return Pending.now(r.toString());
        }
    }

    /**
     * {@code ops}：不开界面，直接把活干完。作业级门禁在这里，{@link AiOps} 只管执行 ——
     * 云控写 {@code ai_ops_off=gif} 就只剩转换和语法检查，模型连这个选项都看不到。
     */
    private static Pending ops(Context ctx, Call c) {
        String name = arg(c.args, "op", "name", "操作").toLowerCase(Locale.ROOT);
        if (!AiPolicy.opAllowed(FLAGS, name)) {
            JSONObject r = new JSONObject();
            try {
                r.put("ok", false);
                r.put("tool", AiPolicy.T_OPS);
                r.put("error", AiPolicy.toolAllowed(FLAGS, AiPolicy.T_OPS)
                        ? "作业「" + name + "」已被云端停用（ai_ops_off），只能用 open 打开对应页面"
                        : "ops 这个工具已被云端停用，只能用 open 打开对应页面");
            } catch (Throwable ignored) {
            }
            return Pending.now(r.toString());
        }
        return AiOps.exec(name, c.args, ctx);
    }

    private static String arg(JSONObject a, String... names) {
        if (a != null) {
            for (String n : names) {
                String v = a.optString(n, "").trim();
                if (v.length() > 0) {
                    return v;
                }
            }
        }
        return "";
    }

    private static String open(Context ctx, JSONObject r, String name, String input) throws Exception {
        if (name.length() == 0) {
            r.put("ok", false);
            r.put("error", "没给功能名");
            return r.toString();
        }
        String msg = launch(ctx, name, false, input);
        r.put("ok", msg.startsWith("已打开"));
        r.put("result", msg);
        if (msg.startsWith("已打开")) {
            AiMemory.remember(ctx, "开过", name);
        }
        if (input != null && input.trim().length() > 0) {
            r.put("input", input.trim());
        }
        return r.toString();
    }

    private static String openSite(Context ctx, JSONObject r, String name) throws Exception {
        if (name.length() == 0) {
            r.put("ok", false);
            r.put("error", "没给站点名");
            return r.toString();
        }
        String msg = launch(ctx, name, true, "");
        r.put("ok", msg.startsWith("已打开"));
        r.put("result", msg);
        return r.toString();
    }

    /** 只在首页那条路上做，且复用点卡片同一条门禁。onlyWeb=true 时限定第三方站点条目。 */
    private static String launch(Context ctx, String name, boolean onlyWeb, String input) {
        MainActivity home = ctx instanceof MainActivity ? (MainActivity) ctx : MainActivity.home();
        if (home == null) {
            return "首页不在前台，暂时没法打开功能；请让用户回到首页再试";
        }
        String m = onlyWeb ? "站点" : "功能";
        HomeIndex.Item it = home.findTool(name, onlyWeb);
        if (it == null) {
            List<String> near = like(name, 5, onlyWeb);
            return "没有找到这个" + m + "：" + name + (near.isEmpty() ? "" : "；相近的有 " + near);
        }
        if (onlyWeb && input != null && input.trim().length() > 0) {
            input = "";     // 站点页不接收内容，别让它以为带过去了
        }
        return home.launchTool(it, input);
    }

    /** describe：把条目的真实判定（档位 / 可不可用 / 收不收内容）说给模型听。 */
    private static String describe(JSONObject r, String name) throws Exception {
        HomeIndex.Item it = null;
        if (name.length() > 0) {
            it = HomeIndex.find(name);
            if (it == null) {
                MainActivity home = MainActivity.home();
                it = home == null ? null : home.findTool(name, false);
            }
        }
        if (it == null) {
            r.put("ok", false);
            r.put("error", name.length() == 0 ? "没给功能名" : "清单里没有「" + name + "」这个功能，别编");
            List<String> near = like(name, 5, false);
            if (!near.isEmpty()) {
                r.put("matches", new JSONArray(near));
            }
            return r.toString();
        }
        r.put("ok", true);
        r.put("name", it.title);
        r.put("about", it.sub);
        r.put("category", it.catName == null || it.catName.length() == 0 ? "其它" : it.catName);
        r.put("tier", tierName(it.kind));
        r.put("available", it.usable);
        if (!it.usable) {
            r.put("why_not", it.notice == null || it.notice.length() == 0 ? "云端已停用" : it.notice);
        }
        if (it.fromCloud) {
            r.put("from_cloud", true);
        }
        if (com.mineways.cloud.FeatureCatalog.MODE_ASK.equals(it.mode)) {
            r.put("needs_confirm", "云端收录的站点，打开前会先让用户确认");
        }
        String accepts = AiHandoff.accepts(it);
        r.put("accepts_input", accepts != null ? accepts : "不接收外部内容，open 只传 name");
        // 「这类活有没有能直接执行的作业」是模型最想知道的一件事 —— 有就不必把页面打开让用户自己点
        String kind = it.catName == null ? "" : it.catName;
        if (kind.contains("材质") || (it.title != null && it.title.contains("材质包"))) {
            r.put("ops", AiPolicy.opAllowed(FLAGS, AiPolicy.OP_PACK)
                    ? "这种包可以直接用 ops(op=pack, path=…) 转完，不用开页面"
                    : "转换作业已被云端停用（ai_ops_off/ai_tools_off），只能 open 打开页面让用户自己点");
        }
        return r.toString();
    }

    private static String tierName(int kind) {
        if (kind == FeatureGate.KIND_PAID) {
            return "需要白名单账号登录";
        }
        if (kind == FeatureGate.KIND_LIMIT) {
            return "需要激活码（每用一次扣 1 次机会）";
        }
        return "免费";
    }

    private static String search(JSONObject r, String keyword) throws Exception {
        List<String> hits = like(keyword, 12, false);
        r.put("ok", true);
        r.put("count", hits.size());
        r.put("matches", new JSONArray(hits));
        if (hits.isEmpty()) {
            r.put("note", "清单里没有相近的功能，别编一个名字出来");
        }
        return r.toString();
    }

    /** 模糊找：去空格包含匹配，命中名 + 分类，最多 n 条；被云控摘掉的功能不参与匹配。 */
    private static List<String> like(String keyword, int n, boolean onlyWeb) {
        List<String> out = new ArrayList<>();
        String k = com.mineways.cloud.FeatureCatalog.norm(keyword);
        if (k.length() == 0) {
            return out;
        }
        String bare = k.replace(" ", "");
        for (HomeIndex.Item it : safeItems()) {
            if (it == null || !it.visible || out.size() >= n) {
                continue;
            }
            if (!AiPolicy.featureVisible(FLAGS, it.title)) {
                continue;
            }
            if (onlyWeb && !"web".equals(it.group) && !it.group.contains("cloud")) {
                continue;
            }
            String t = it.title == null ? "" : it.title;
            String tb = com.mineways.cloud.FeatureCatalog.norm(t).replace(" ", "");
            String sub = it.sub == null ? "" : com.mineways.cloud.FeatureCatalog.norm(it.sub);
            if (tb.contains(bare) || bare.contains(tb) || sub.contains(bare)) {
                out.add(t + "（" + (it.catName == null ? "其它" : it.catName) + "）");
            }
        }
        return out;
    }

    private static String list(JSONObject r) throws Exception {
        JSONArray cats = new JSONArray();
        String lastCat = "";
        StringBuilder cur = null;
        JSONObject co = null;
        for (HomeIndex.Item it : safeItems()) {
            if (it == null || !it.visible
                    || !AiPolicy.featureVisible(FLAGS, it.title)) {
                continue;
            }
            String cat = it.catName == null || it.catName.length() == 0 ? "其它" : it.catName;
            if (!cat.equals(lastCat)) {
                if (cur != null) {
                    cats.put(co.put("cat", lastCat).put("items", cur.toString().trim()));
                }
                lastCat = cat;
                cur = new StringBuilder();
                co = new JSONObject();
            }
            if (cur != null) {
                cur.append(it.title).append('、');
            }
        }
        if (cur != null) {
            cats.put(co.put("cat", lastCat).put("items", cur.toString().trim()));
        }
        r.put("ok", true);
        r.put("categories", cats);
        return r.toString();
    }

    private static String info(Context ctx, JSONObject r) throws Exception {
        r.put("ok", true);
        r.put("app", ctx.getString(ctx.getApplicationInfo().labelRes));
        r.put("version", BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")");
        r.put("env", ApiEnv.label());
        r.put("endpoint", ApiEnv.base().replaceAll("https?://", ""));
        try {
            r.put("cloud", com.mineways.cloud.CloudCatalog.appliedVersion());
            r.put("gate", com.mineways.cloud.CloudCatalog.status());
        } catch (Throwable ignored) {
        }
        r.put("ai_policy", AiPolicy.stateNote(FLAGS));
        r.put("ai_ops", opsNote());
        r.put("ai_memory", AiMemory.dump());
        r.put("abi", android.os.Build.SUPPORTED_ABIS != null
                && android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "?");
        r.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " Android " + android.os.Build.VERSION.RELEASE);
        // 用户在设置里看到的名字 + 玻璃机型策略的生效结果：机型相关的反馈能直接对上话
        r.put("device_show", DeviceId.marketing());
        r.put("glass_policy", GlassPolicy.debugLine());
        return r.toString();
    }

    /**
     * ask_page：把用户现在看着的那个页面的文字念给模型听。
     *
     * <p>页面主体是原生 View，所以这里遍历可见 TextView；WebView 页面（剪辑、Blockbench 这类）
     * 抓不到内容，会如实回一句「这页是网页，读不到」，而不是编一个答案。
     */
    private static String askPage(Context ctx, JSONObject r, String question) throws Exception {
        r.put("question", question);
        if (!(ctx instanceof Activity)) {
            r.put("ok", false);
            r.put("error", "当前界面不在前台，读不到页面内容");
            return r.toString();
        }
        Activity a = (Activity) ctx;
        r.put("page", com.mineways.ui.MwPageGlass.pageTitle(a));
        String screen = screenText(a);
        if (screen.length() == 0) {
            r.put("ok", true);
            r.put("screen_note", "这一屏是网页或自绘界面，App 读不到里面的文字，"
                    + "只能按页面名回答，别装作看见了");
            return r.toString();
        }
        r.put("ok", true);
        r.put("screen", screen);
        return r.toString();
    }

    /** 屏幕文字快照：深度限 12、最多 40 条、总量 900 字，够说明问题又不烧配额。 */
    private static String screenText(Activity a) {
        StringBuilder sb = new StringBuilder();
        int[] counted = {0};
        try {
            View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
            collect(root, 0, sb, counted);
        } catch (Throwable ignored) {
        }
        String s = sb.toString().trim();
        return s.length() > 900 ? s.substring(0, 900) + "…" : s;
    }

    private static void collect(View v, int depth, StringBuilder sb, int[] counted) {
        if (v == null || depth > 12 || sb.length() > 900 || !v.isShown() || counted[0] >= 40) {
            return;
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            String s = t == null ? "" : t.toString().trim();
            if (s.length() > 0 && sb.indexOf(s) < 0) {
                sb.append(s).append(" | ");
                counted[0]++;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), depth + 1, sb, counted);
            }
        }
    }

    /** 给界面显示用的一行摘要。 */
    public static String label(Call c) {
        String a = arg(c.args, "name", "keyword", "q", "site", "tool", "功能", "question", "ask");
        if (a.length() == 0) {
            a = arg(c.args);
        }
        String input = arg(c.args, "input", "path", "text", "内容");
        String tail = input.length() == 0 ? "" : " ← 带上「" + cut(input) + "」";
        switch (c.tool) {
            case AiPolicy.T_OPEN:
                return "打开功能：" + a + tail;
            case AiPolicy.T_DESCRIBE:
                return "看功能详情：" + a;
            case AiPolicy.T_SITE:
                return "打开站点：" + a;
            case AiPolicy.T_SEARCH:
                return "查找功能：" + a;
            case AiPolicy.T_LIST:
                return "列出全部功能";
            case AiPolicy.T_INFO:
                return "读取 App 与运行环境信息";
            case AiPolicy.T_PAGE_ASK:
                return "读当前页面：" + a;
            case AiPolicy.T_OPS:
                return "直接执行：" + arg(c.args, "op", "name", "操作")
                        + (a.length() == 0 ? "" : "（" + a + "）") + tail;
            default:
                return c.tool;
        }
    }

    private static String cut(String s) {
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    /** 结果回喂给模型时用的那层包装（文档 §7.5：历史由 App 自己带）。 */
    public static String wrapResults(List<String> jsonResults) {
        JSONArray arr = new JSONArray();
        for (String s : jsonResults) {
            try {
                arr.put(new JSONObject(s));
            } catch (Throwable t) {
                try {
                    arr.put(new JSONObject().put("ok", false).put("error", s));
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            return new JSONObject().put("mw_result", arr).toString();
        } catch (Throwable t) {
            return "{\"mw_result\":[]}";
        }
    }

    /** 需要 Activity 才能起 Intent；面板传进来的就是宿主 Activity。 */
    public static boolean canLaunch(Activity a) {
        return a instanceof MainActivity || MainActivity.home() != null;
    }

    /** 云控生效情况，给界面/诊断用的一句话。 */
    public static String policyNote() {
        try {
            return AiPolicy.stateNote(FLAGS);
        } catch (Throwable t) {
            return "云控读取失败，按客户端默认执行";
        }
    }

    /** 一次问题最多让 AI 连着做几步（云控 {@code ai_hops}）。 */
    public static int hopLimit() {
        try {
            return AiPolicy.hops(FLAGS);
        } catch (Throwable t) {
            return 3;
        }
    }

    /** 当前应该发出去的 system（云控可整段替换或追加）。 */
    public static String systemForThisRound() {
        String builtin;
        try {
            builtin = prompt();
        } catch (Throwable t) {
            builtin = FALLBACK_PROMPT;
        }
        try {
            return AiPolicy.system(FLAGS, builtin);
        } catch (Throwable t) {
            return builtin;
        }
    }

    /** 连提示词都拼不出来时的最后一层：至少别露出供应商的出厂人设。 */
    private static final String FALLBACK_PROMPT =
            "你是「烬火工坊」安卓 App 内置的 AI 助手。这次对话没有可用工具，只用文字回答，"
                    + "简洁、直接，不确定就说不确定。不要提及你的模型名称、版本号或供应商。";
}
