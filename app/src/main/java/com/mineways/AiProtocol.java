package com.mineways;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 回答里<b>协议文本</b>的处理：调用块、{@code mw_suggest} 行、流式期间该显示哪一段。
 *
 * <p>单独成类、且<b>不 import 任何 Android / org.json 类型</b>，为的是能在 JVM 上直接断言
 * （{@code AiProtocolCheck}）。这一层出过一次真机事故：流式竞态让 {@code ```mw} 调用块和
 * {@code mw_suggest} 原文显示在了气泡里，用户看到的就是"输出不规范"。
 *
 * <p>三条口径：
 * <ol>
 *   <li><b>不依赖围栏</b>：围栏是模型自己写的，{@code ```} / {@code ```json} / 少一个反引号 /
 *       干脆不写都常见，所以按花括号配平找 {@code "calls"}；</li>
 *   <li><b>只删该删的那一块</b>：建议行只删那一行、围栏块只删那一块，绝不"从标记删到结尾" ——
 *       模型常在块后再补一句人话，整段砍掉等于把回答截断；</li>
 *   <li><b>显示这一侧自己挡</b>：{@link #visible} 从第一个协议标记处截断，
 *       所以哪怕 {@code onDone} 之后有晚到的增量把原文糊回来，屏上也看不到协议。</li>
 * </ol>
 */
public final class AiProtocol {

    /** 调用块的分隔标记。 */
    public static final String FENCE = "```mw";

    /** 建议行的标记。 */
    public static final String SUGGEST = "mw_suggest";

    private AiProtocol() {
    }

    /** 正文里第一个协议标记的位置；没有就返回 -1。 */
    public static int firstMark(String s) {
        if (s == null || s.length() == 0) {
            return -1;
        }
        int best = -1;
        int i = s.indexOf(FENCE);
        if (i >= 0) {
            best = i;
        }
        i = s.indexOf("\"calls\"");
        if (i >= 0) {
            int open = s.lastIndexOf('{', i);
            int at = open < 0 ? i : open;
            best = best < 0 ? at : Math.min(best, at);
        }
        i = s.indexOf(SUGGEST);
        if (i >= 0) {
            best = best < 0 ? i : Math.min(best, i);
        }
        return best;
    }

    /** 流式期间界面该显示的那一段：协议标记之后的全部不显示，尾巴上孤零零的围栏行也不显示。 */
    public static String visible(String raw) {
        if (raw == null || raw.length() == 0) {
            return "";
        }
        int cut = firstMark(raw);
        String out = cut < 0 ? raw : raw.substring(0, cut);
        // 模型爱在块前后各写一行 ```：截完之后那行壳子还挂在尾巴上
        out = out.replaceAll("(?s)\\n+\\s*```[a-zA-Z]{0,8}\\s*$", "");
        return out.trim();
    }

    /** 回答末尾的下一步建议（{@code mw_suggest: A｜B｜C}），最多 3 条；没有回空列表。 */
    public static List<String> suggest(String answer) {
        List<String> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        int i = answer.indexOf(SUGGEST);
        if (i < 0) {
            return out;
        }
        String line = answer.substring(i);
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl);
        }
        int c = line.indexOf(':');
        if (c < 0) {
            c = line.indexOf('：');
        }
        if (c < 0) {
            return out;
        }
        for (String s : line.substring(c + 1).split("[｜|]")) {
            String t = s.trim();
            if (t.length() > 0 && out.size() < 3) {
                out.add(t.length() > 48 ? t.substring(0, 48) : t);
            }
        }
        return out;
    }

    /** 只删建议<b>那一行</b>，后面的正文留着。 */
    public static String stripSuggest(String answer) {
        if (answer == null || !answer.contains(SUGGEST)) {
            return answer;
        }
        int i = answer.indexOf(SUGGEST);
        int lineStart = answer.lastIndexOf('\n', i - 1) + 1;
        int lineEnd = answer.indexOf('\n', i);
        String tail = lineEnd < 0 ? "" : answer.substring(lineEnd + 1);   // 连那个换行一起吃掉，否则留个空行
        return (answer.substring(0, lineStart) + tail).trim();
    }

    /**
     * 剥掉全部协议噪声后的人话：调用块（带不带 {@code mw} 围栏都认）、建议行、孤立围栏。
     *
     * <p>调用块按花括号配平整块删除，所以围栏写错、少写闭合都不影响；剩下的
     * {@code ```} 壳子只在"整行只有一个围栏"时才删，正文里合法的代码示例不会被吃掉。
     */
    public static String strip(String answer) {
        if (answer == null || answer.length() == 0) {
            return "";
        }
        String s = answer;
        int guard = 0;
        while (guard++ < 6) {
            int key = s.indexOf("\"calls\"");
            if (key < 0) {
                break;
            }
            int open = s.lastIndexOf('{', key);
            int close = open < 0 ? -1 : matchBrace(s, open);
            if (open < 0 || close < 0) {
                break;
            }
            s = s.substring(0, open) + s.substring(close + 1);
        }
        s = stripSuggest(s);
        s = s.replaceAll("(?s)" + FENCE + ".*?```", "");          // 写了闭合的 ```mw 块
        s = s.replaceAll("(?s)" + FENCE + "[\\s\\S]*$", "");       // 没闭合的：这一块到结尾
        s = s.replaceAll("(?m)^\\s*```[a-zA-Z]{0,8}\\s*$", "");    // 剩下的孤立围栏行
        return s.replaceAll("\n{3,}", "\n\n").trim();
    }

    /** 从 {@code from} 处的 '{' 找到配对的 '}'（跳过字符串内的括号与转义）；不配对回 -1。 */
    public static int matchBrace(String s, int from) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
