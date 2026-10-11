package com.mineways;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 公告条件表达式求值器（{@code ovo.txt} 的 {@code when:} / {@code to:} 用）。
 *
 * <p>纯计算：不反射、不执行任何代码、不碰文件网络。取不到的变量按空串处理，
 * 所以写错变量名的条件是「不成立」，不会把公告误发给所有人。
 *
 * <h3>运算</h3>
 * <pre>
 *   逻辑      &amp;&amp;   ||   !        中文：且/并且/和 或/或者 非/不
 *   比较      ==  !=  &gt;  &lt;  &gt;=  &lt;=
 *            中文：是/等于  不是/不等于  大于  小于  至少/不小于  至多/不大于
 *   文本      包含 contains   匹配 matches(正则)   开头是 startsWith   结尾是 endsWith
 *   集合      在 in   不在 not in / notin        例：uid 在 ("1001","1002")
 *   算术      +  -  *  /  %
 *   括号      可任意嵌套
 * </pre>
 *
 * <h3>函数</h3>
 * <pre>
 *   文本   contains(a,b)  startsWith(a,b)  endsWith(a,b)  matches(a,正则)  find(a,b)
 *          len(x)  lower(x)  upper(x)  trim(x)  substr(s,start[,len])  replace(a,b,c)
 *          split(a,分隔)  join(列表,分隔)  num(a)  str(a)  empty(x)  count(列表,值)
 *   数字   abs(x)  min(...)  max(...)  round(x)  floor(x)  ceil(x)  clamp(x,lo,hi)  mod(a,b)
 *   时间   now()  今天 today()  hour([ts])  weekday([ts])  星期 weekdayName([ts])
 *          year/month/day([ts])  fmt([ts], "yyyy-MM-dd")  daysSince(ts或"yyyy-MM-dd")
 *   灰度   hash(x)        稳定哈希(0..2147483647)
 *          hashMod(x,n)   稳定取模(0..n-1)
 *          roll(x,n)      x 的百分比灰度：hashMod(x,100) &lt; n  → 命中前 n%
 *          rand()  randInt(a,b)   真随机（每次启动都不同）
 *   其它   any(...)  all(...)  none(...)  coalesce(...)  default(...)
 * </pre>
 *
 * <p>表达式写错（括号不配对、正则非法、不认识的函数）抛 {@link Error}，
 * 由 {@link AnnouncementCenter} 跳过这一条公告，不影响其它公告和页面。
 */
public final class Expr {

    /** 表达式本身有问题（区别于「求值结果为假」）。 */
    public static final class Error extends RuntimeException {
        public Error(String message) {
            super(message);
        }
    }

    private static final int K_OP = 0;
    private static final int K_STR = 1;
    private static final int K_NUM = 2;

    /** 中文/英文关键字 → 规范运算符。 */
    private static final Map<String, String> ALIAS = new HashMap<>();

    static {
        // 逻辑
        alias("&&", "and", "且", "并且", "与", "和", "而且");
        alias("||", "or", "或", "或者", "亦或");
        alias("!", "not", "非", "不", "否");
        // 比较
        alias("==", "eq", "等于", "是", "为", "就是");
        alias("!=", "ne", "不等于", "不是", "不为");
        alias(">", "gt", "大于", "超过", "多于", "高于");
        alias("<", "lt", "小于", "低于", "少于");
        alias(">=", "gte", "ge", "大于等于", "不小于", "至少", "不低于");
        alias("<=", "lte", "le", "小于等于", "不大于", "至多", "不超过");
        // 文本
        alias("contains", "包含", "含", "里有", "里面有");
        alias("matches", "匹配", "符合", "正则匹配");
        alias("startsWith", "开头是", "开头为", "以开头");
        alias("endsWith", "结尾是", "结尾为", "以结尾");
        // 集合
        alias("in", "在", "属于", "在列表", "之一是");
        alias("notin", "notin", "不在", "不属于", "不是之一");
    }

    private static void alias(String canonical, String... words) {
        ALIAS.put(canonical.toLowerCase(Locale.ROOT), canonical);
        for (String w : words) {
            ALIAS.put(w.toLowerCase(Locale.ROOT), canonical);
        }
    }

    private static final class Tok {
        final int kind;
        final String text;
        final double num;

        Tok(int kind, String text, double num) {
            this.kind = kind;
            this.text = text;
            this.num = num;
        }
    }

    private static final Random RANDOM = new Random();

    private final List<Tok> toks;
    private final Map<String, String> vars;
    private int pos;

    private Expr(List<Tok> toks, Map<String, String> vars) {
        this.toks = toks;
        this.vars = vars;
    }

    /** 条件求值：空条件恒真（= 所有人）。 */
    public static boolean test(String src, Map<String, String> vars) {
        if (src == null || src.trim().length() == 0) {
            return true;
        }
        return truthy(eval(src, vars));
    }

    /** 求值：结果可能是布尔 / 数字 / 字符串 / 列表。 */
    public static Object eval(String src, Map<String, String> vars) {
        Expr p = new Expr(lex(src), vars);
        Object v = p.parseOr();
        if (p.pos < p.toks.size()) {
            throw new Error("表达式结尾多了「" + p.toks.get(p.pos).text + "」");
        }
        return v;
    }

    /** 真值判定：布尔照值；数字非 0；空串/false/0/no/null 为假；列表看是否非空。 */
    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Double) {
            return Math.abs((Double) v) > 1e-9;
        }
        if (v instanceof List) {
            return !((List<?>) v).isEmpty();
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.length() > 0 && !"false".equals(s) && !"0".equals(s)
                && !"no".equals(s) && !"null".equals(s);
    }

    // ---------------------------------------------------------------- 分词

    private static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"' || c == '\'' || c == '\u201c' || c == '\u2018') {   // " ' “ ‘
                char close = (c == '\u201c') ? '\u201d' : (c == '\u2018' ? '\u2019' : c);
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != close) {
                    char d = src.charAt(j);
                    // 转义：\\ \" \' 得原字符，\n \t 得换行/制表；
                    // 其它（如正则里的 \d \w）原样保留，省得写公告还要双写反斜杠
                    if (d == '\\' && j + 1 < n) {
                        char e = src.charAt(j + 1);
                        if (e == '\\' || e == '"' || e == '\'' || e == close) {
                            sb.append(e);
                            j += 2;
                            continue;
                        }
                        if (e == 'n') {
                            sb.append('\n');
                            j += 2;
                            continue;
                        }
                        if (e == 't') {
                            sb.append('\t');
                            j += 2;
                            continue;
                        }
                    }
                    sb.append(d);
                    j++;
                }
                out.add(new Tok(K_STR, sb.toString(), 0));
                i = (j < n) ? j + 1 : j;
                continue;
            }
            if (c == '&' || c == '|') {
                if (i + 1 < n && src.charAt(i + 1) == c) {
                    out.add(new Tok(K_OP, c == '&' ? "&&" : "||", 0));
                    i += 2;
                } else {
                    out.add(new Tok(K_OP, String.valueOf(c), 0));
                    i++;
                }
                continue;
            }
            if (c == '!' || c == '=' || c == '>' || c == '<') {
                String two = (i + 1 < n) ? src.substring(i, i + 2) : "";
                if ("==".equals(two) || "!=".equals(two) || ">=".equals(two) || "<=".equals(two)) {
                    out.add(new Tok(K_OP, two, 0));
                    i += 2;
                } else {
                    out.add(new Tok(K_OP, String.valueOf(c), 0));
                    i++;
                }
                continue;
            }
            if (c == '(' || c == ')' || c == ',' || c == '+' || c == '-'
                    || c == '*' || c == '/' || c == '%') {
                out.add(new Tok(K_OP, String.valueOf(c), 0));
                i++;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) {
                    j++;
                }
                String s = src.substring(i, j);
                double d;
                try {
                    d = Double.parseDouble(s);
                } catch (Throwable t) {
                    throw new Error("数字写错了：" + s);
                }
                out.add(new Tok(K_NUM, s, d));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '.') {
                int j = i;
                while (j < n) {
                    char d = src.charAt(j);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '.' || d == '@' || d == '-') {
                        j++;
                    } else {
                        break;
                    }
                }
                String word = src.substring(i, j);
                String canon = ALIAS.get(word.toLowerCase(Locale.ROOT));
                out.add(new Tok(K_OP, canon != null ? canon : word, 0));
                i = j;
                continue;
            }
            throw new Error("不认识的字符：" + c);
        }
        return out;
    }

    // ---------------------------------------------------------------- 语法

    private boolean peekOp(String op) {
        return pos < toks.size() && toks.get(pos).kind == K_OP && op.equals(toks.get(pos).text);
    }

    private boolean peekOpIgnoreCase(String op) {
        return pos < toks.size() && toks.get(pos).kind == K_OP && op.equalsIgnoreCase(toks.get(pos).text);
    }

    private boolean eatOp(String op) {
        if (peekOp(op)) {
            pos++;
            return true;
        }
        return false;
    }

    private Object parseOr() {
        Object left = parseAnd();
        while (eatOp("||")) {
            Object right = parseAnd();
            left = truthy(left) || truthy(right);
        }
        return left;
    }

    private Object parseAnd() {
        Object left = parseNot();
        while (eatOp("&&")) {
            Object right = parseNot();
            left = truthy(left) && truthy(right);
        }
        return left;
    }

    private Object parseNot() {
        if (eatOp("!")) {
            return !truthy(parseNot());
        }
        return parseCmp();
    }

    private static boolean isCmp(String t) {
        return "==".equals(t) || "!=".equals(t) || ">".equals(t) || "<".equals(t)
                || ">=".equals(t) || "<=".equals(t)
                || "contains".equalsIgnoreCase(t) || "matches".equalsIgnoreCase(t)
                || "startsWith".equalsIgnoreCase(t) || "endsWith".equalsIgnoreCase(t)
                || "in".equalsIgnoreCase(t) || "notin".equalsIgnoreCase(t);
    }

    private Object parseCmp() {
        Object left = parseAdd();
        String op = null;
        if (pos < toks.size() && toks.get(pos).kind == K_OP) {
            String t = toks.get(pos).text;
            if (("!".equals(t) || "not".equalsIgnoreCase(t))
                    && pos + 1 < toks.size() && "in".equalsIgnoreCase(toks.get(pos + 1).text)) {
                op = "notin";       // 「a 不 在 b」/「a not in b」
                pos += 2;
            } else if (isCmp(t)) {
                op = t.toLowerCase(Locale.ROOT);
                pos++;
            }
        }
        if (op == null) {
            return left;
        }
        Object right = ("in".equals(op) || "notin".equals(op)) ? parseListOrValue() : parseAdd();
        return compare(op, left, right);
    }

    /** {@code in} 的右边：可以是 {@code ("a","b")} 列表，也可以是逗号分隔的字符串。 */
    private Object parseListOrValue() {
        if (peekOp("(")) {
            pos++;
            List<Object> list = new ArrayList<>();
            if (!peekOp(")")) {
                list.add(parseOr());
                while (eatOp(",")) {
                    list.add(parseOr());
                }
            }
            if (!eatOp(")")) {
                throw new Error("列表的括号没有配对");
            }
            return list;
        }
        return parseAdd();
    }

    private Object parseAdd() {
        Object left = parseMul();
        while (true) {
            if (eatOp("+")) {
                Object r = parseMul();
                Double a = num(left);
                Double b = num(r);
                left = (a != null && b != null) ? (Object) (a + b) : (Object) (str(left) + str(r));
            } else if (eatOp("-")) {
                left = numOrZero(left) - numOrZero(parseMul());
            } else {
                return left;
            }
        }
    }

    private Object parseMul() {
        Object left = parseUnary();
        while (true) {
            if (eatOp("*")) {
                left = numOrZero(left) * numOrZero(parseUnary());
            } else if (eatOp("/")) {
                double d = numOrZero(parseUnary());
                left = d == 0 ? 0.0 : numOrZero(left) / d;
            } else if (eatOp("%")) {
                double d = numOrZero(parseUnary());
                left = d == 0 ? 0.0 : numOrZero(left) % d;
            } else {
                return left;
            }
        }
    }

    private Object parseUnary() {
        if (eatOp("-")) {
            return -numOrZero(parseUnary());
        }
        if (eatOp("+")) {
            return numOrZero(parseUnary());
        }
        return parsePrimary();
    }

    private Object parsePrimary() {
        if (pos >= toks.size()) {
            throw new Error("表达式不完整");
        }
        Tok t = toks.get(pos);
        pos++;
        if (t.kind == K_STR) {
            return t.text;
        }
        if (t.kind == K_NUM) {
            return t.num;
        }
        if ("(".equals(t.text)) {
            Object v = parseOr();
            if (!eatOp(")")) {
                throw new Error("括号没有配对");
            }
            return v;
        }
        if (")".equals(t.text) || ",".equals(t.text)) {
            throw new Error("表达式位置不对：" + t.text);
        }
        String name = t.text;
        if (peekOp("(")) {                    // 函数调用
            pos++;
            List<Object> args = new ArrayList<>();
            if (!peekOp(")")) {
                args.add(parseOr());
                while (eatOp(",")) {
                    args.add(parseOr());
                }
            }
            if (!eatOp(")")) {
                throw new Error("函数 " + name + " 的括号没有配对");
            }
            return call(name, args);
        }
        if ("true".equalsIgnoreCase(name) || "yes".equalsIgnoreCase(name) || "真".equals(name)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(name) || "no".equalsIgnoreCase(name) || "假".equals(name)) {
            return Boolean.FALSE;
        }
        if ("null".equalsIgnoreCase(name) || "none".equalsIgnoreCase(name) || "空".equals(name)) {
            return "";
        }
        String v = (vars == null) ? null : vars.get(name);
        if (v == null) {
            String low = name.toLowerCase(Locale.ROOT);
            v = (vars == null) ? null : vars.get(low);
        }
        return v == null ? "" : v;            // 未知变量 = 空串：条件不成立
    }

    // ---------------------------------------------------------------- 运算与函数

    private static Object compare(String op, Object a, Object b) {
        if ("contains".equalsIgnoreCase(op)) {
            if (b instanceof List) {
                for (Object o : (List<?>) b) {
                    if (str(a).toLowerCase(Locale.ROOT).contains(str(o).toLowerCase(Locale.ROOT))) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }
            return str(a).toLowerCase(Locale.ROOT).contains(str(b).toLowerCase(Locale.ROOT));
        }
        if ("startsWith".equalsIgnoreCase(op)) {
            return str(a).toLowerCase(Locale.ROOT).startsWith(str(b).toLowerCase(Locale.ROOT));
        }
        if ("endsWith".equalsIgnoreCase(op)) {
            return str(a).toLowerCase(Locale.ROOT).endsWith(str(b).toLowerCase(Locale.ROOT));
        }
        if ("matches".equalsIgnoreCase(op)) {
            return regex(str(b)).matcher(str(a)).find();
        }
        if ("in".equalsIgnoreCase(op) || "notin".equalsIgnoreCase(op)) {
            boolean hit = inList(a, b);
            return "in".equalsIgnoreCase(op) ? hit : !hit;
        }
        Double da = num(a);
        Double db = num(b);
        int c = (da != null && db != null) ? da.compareTo(db) : str(a).compareToIgnoreCase(str(b));
        if ("==".equals(op)) {
            return c == 0;
        }
        if ("!=".equals(op)) {
            return c != 0;
        }
        if (">".equals(op)) {
            return c > 0;
        }
        if ("<".equals(op)) {
            return c < 0;
        }
        if (">=".equals(op)) {
            return c >= 0;
        }
        if ("<=".equals(op)) {
            return c <= 0;
        }
        throw new Error("不认识的比较符：" + op);
    }

    private static boolean inList(Object a, Object b) {
        if (b instanceof List) {
            for (Object o : (List<?>) b) {
                if (eq(a, o)) {
                    return true;
                }
            }
            return false;
        }
        String s = str(b);
        for (String part : s.split("[,，、;；|\\s]+")) {
            if (part.trim().length() > 0 && eq(a, part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean eq(Object a, Object b) {
        Double da = num(a);
        Double db = num(b);
        if (da != null && db != null) {
            return da.compareTo(db) == 0;
        }
        return str(a).equalsIgnoreCase(str(b));
    }

    private static Object call(String name, List<Object> a) {
        String n = name.toLowerCase(Locale.ROOT);
        // ---- 文本
        if ("contains".equals(n)) {
            return argN(a, 2) && compare("contains", a.get(0), a.get(1)).equals(Boolean.TRUE);
        }
        if ("startswith".equals(n)) {
            return argN(a, 2) && compare("startsWith", a.get(0), a.get(1)).equals(Boolean.TRUE);
        }
        if ("endswith".equals(n)) {
            return argN(a, 2) && compare("endsWith", a.get(0), a.get(1)).equals(Boolean.TRUE);
        }
        if ("matches".equals(n)) {
            return argN(a, 2) && regex(str(a.get(1))).matcher(str(a.get(0))).find();
        }
        if ("find".equals(n) || "indexof".equals(n)) {
            return argN(a, 2) ? (double) str(a.get(0)).toLowerCase(Locale.ROOT)
                    .indexOf(str(a.get(1)).toLowerCase(Locale.ROOT)) : -1.0;
        }
        if ("len".equals(n) || "length".equals(n)) {
            return a.isEmpty() ? 0.0 : (double) str(a.get(0)).length();
        }
        if ("lower".equals(n)) {
            return a.isEmpty() ? "" : str(a.get(0)).toLowerCase(Locale.ROOT);
        }
        if ("upper".equals(n)) {
            return a.isEmpty() ? "" : str(a.get(0)).toUpperCase(Locale.ROOT);
        }
        if ("trim".equals(n)) {
            return a.isEmpty() ? "" : str(a.get(0)).trim();
        }
        if ("substr".equals(n)) {
            if (a.isEmpty()) {
                return "";
            }
            String s = str(a.get(0));
            int from = a.size() > 1 ? (int) numOrZero(a.get(1)) : 0;
            from = Math.max(0, Math.min(from, s.length()));
            int to = a.size() > 2 ? from + (int) numOrZero(a.get(2)) : s.length();
            to = Math.max(from, Math.min(to, s.length()));
            return s.substring(from, to);
        }
        if ("replace".equals(n)) {
            return argN(a, 3) ? str(a.get(0)).replace(str(a.get(1)), str(a.get(2))) : str(a.isEmpty() ? "" : a.get(0));
        }
        if ("split".equals(n)) {
            List<Object> list = new ArrayList<>();
            if (argN(a, 2)) {
                for (String p : str(a.get(0)).split(Pattern.quote(str(a.get(1))))) {
                    list.add(p.trim());
                }
            }
            return list;
        }
        if ("join".equals(n)) {
            if (a.isEmpty()) {
                return "";
            }
            String sep = a.size() > 1 ? str(a.get(1)) : ",";
            StringBuilder sb = new StringBuilder();
            Object first = a.get(0);
            if (first instanceof List) {
                for (Object o : (List<?>) first) {
                    if (sb.length() > 0) {
                        sb.append(sep);
                    }
                    sb.append(str(o));
                }
            } else {
                sb.append(str(first));
            }
            return sb.toString();
        }
        if ("empty".equals(n)) {
            return a.isEmpty() || str(a.get(0)).trim().length() == 0;
        }
        if ("num".equals(n) || "number".equals(n)) {
            Double d = a.isEmpty() ? null : num(a.get(0));
            return d == null ? 0.0 : d;
        }
        if ("int".equals(n)) {
            return a.isEmpty() ? 0.0 : (double) (long) numOrZero(a.get(0));
        }
        if ("str".equals(n)) {
            return a.isEmpty() ? "" : str(a.get(0));
        }
        if ("count".equals(n) || "size".equals(n)) {
            if (a.isEmpty()) {
                return 0.0;
            }
            Object v0 = a.get(0);
            if (v0 instanceof List) {
                return (double) ((List<?>) v0).size();
            }
            String sep = a.size() > 1 ? str(a.get(1)) : "";
            String[] parts = sep.length() > 0
                    ? str(v0).split(Pattern.quote(sep))
                    : str(v0).split("[,，、;；|\\s]+");
            int c = 0;
            for (String p : parts) {
                if (p.trim().length() > 0) {
                    c++;
                }
            }
            return (double) c;
        }
        // ---- 数字
        if ("abs".equals(n)) {
            return Math.abs(numOrZero(first(a)));
        }
        if ("round".equals(n)) {
            return (double) Math.round(numOrZero(first(a)));
        }
        if ("floor".equals(n)) {
            return Math.floor(numOrZero(first(a)));
        }
        if ("ceil".equals(n)) {
            return Math.ceil(numOrZero(first(a)));
        }
        if ("min".equals(n)) {
            return argN(a, 1) ? Math.min(numOrZero(a.get(0)), numOrZero(a.get(1))) : 0.0;
        }
        if ("max".equals(n)) {
            return argN(a, 1) ? Math.max(numOrZero(a.get(0)), numOrZero(a.get(1))) : 0.0;
        }
        if ("clamp".equals(n)) {
            return argN(a, 3)
                    ? Math.max(numOrZero(a.get(1)), Math.min(numOrZero(a.get(2)), numOrZero(a.get(0)))) : 0.0;
        }
        if ("mod".equals(n)) {
            double d = argN(a, 1) ? numOrZero(a.get(1)) : 0;
            return d == 0 ? 0.0 : numOrZero(a.get(0)) % d;
        }
        // ---- 时间
        if ("now".equals(n)) {
            return (double) System.currentTimeMillis();
        }
        if ("today".equals(n)) {
            return fmt(System.currentTimeMillis(), "yyyy-MM-dd");
        }
        if ("hour".equals(n)) {
            return (double) field(a.isEmpty() ? System.currentTimeMillis() : (long) numOrZero(a.get(0)), Calendar.HOUR_OF_DAY);
        }
        if ("weekday".equals(n)) {
            long ts = a.isEmpty() ? System.currentTimeMillis() : (long) numOrZero(a.get(0));
            Calendar c = new GregorianCalendar();
            c.setTimeInMillis(ts);
            return (double) c.get(Calendar.DAY_OF_WEEK);
        }
        if ("weekdayname".equals(n)) {
            long ts = a.isEmpty() ? System.currentTimeMillis() : (long) numOrZero(a.get(0));
            Calendar c = new GregorianCalendar();
            c.setTimeInMillis(ts);
            return c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.CHINA);
        }
        if ("year".equals(n)) {
            return (double) field(a.isEmpty() ? System.currentTimeMillis() : (long) numOrZero(a.get(0)), Calendar.YEAR);
        }
        if ("month".equals(n)) {
            return (double) (field(a.isEmpty() ? System.currentTimeMillis() : (long) numOrZero(a.get(0)), Calendar.MONTH) + 1);
        }
        if ("day".equals(n)) {
            return (double) field(a.isEmpty() ? System.currentTimeMillis() : (long) numOrZero(a.get(0)), Calendar.DAY_OF_MONTH);
        }
        if ("fmt".equals(n)) {
            long ts = a.isEmpty() ? System.currentTimeMillis() : (long) numOrZero(a.get(0));
            String pattern = a.size() > 1 ? str(a.get(1)) : "yyyy-MM-dd";
            return fmt(ts, pattern);
        }
        if ("dayssince".equals(n) || "daysbetween".equals(n)) {
            return (double) daysSince(a.isEmpty() ? "" : str(a.get(0)));
        }
        // ---- 灰度
        if ("hash".equals(n)) {
            return (double) stableHash(str(first(a)));
        }
        if ("hashmod".equals(n)) {
            int m = (int) (argN(a, 1) ? numOrZero(a.get(1)) : 100);
            return (double) (m <= 0 ? 0 : stableHash(str(a.get(0))) % m);
        }
        if ("roll".equals(n)) {
            double p = argN(a, 1) ? numOrZero(a.get(1)) : 0;
            return (double) stableHash(str(a.get(0))) % 100 < p;
        }
        if ("rand".equals(n)) {
            return RANDOM.nextDouble();
        }
        if ("randint".equals(n)) {
            int lo = (int) (a.size() > 0 ? numOrZero(a.get(0)) : 0);
            int hi = (int) (a.size() > 1 ? numOrZero(a.get(1)) : lo + 1);
            if (hi <= lo) {
                return (double) lo;
            }
            return (double) (lo + RANDOM.nextInt(hi - lo));
        }
        // ---- 其它
        if ("any".equals(n)) {
            for (Object o : a) {
                if (truthy(o)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
        if ("all".equals(n)) {
            if (a.isEmpty()) {
                return Boolean.FALSE;
            }
            for (Object o : a) {
                if (!truthy(o)) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        }
        if ("none".equals(n)) {
            for (Object o : a) {
                if (truthy(o)) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        }
        if ("coalesce".equals(n) || "default".equals(n)) {
            for (Object o : a) {
                if (truthy(o)) {
                    return o;
                }
            }
            return "";
        }
        throw new Error("不认识的函数：" + name);
    }

    private static Object first(List<Object> a) {
        return a.isEmpty() ? "" : a.get(0);
    }

    private static boolean argN(List<Object> a, int n) {
        return a.size() >= n;
    }

    private static int field(long ts, int f) {
        Calendar c = new GregorianCalendar();
        c.setTimeInMillis(ts);
        return c.get(f);
    }

    private static String fmt(long ts, String pattern) {
        try {
            return new java.text.SimpleDateFormat(pattern, Locale.CHINA).format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 距离今天的天数：接受 yyyy-MM-dd / yyyy-MM-dd HH:mm / 毫秒时间戳。 */
    private static long daysSince(String value) {
        long ts = parseTime(value);
        if (ts <= 0) {
            return 0;
        }
        return (System.currentTimeMillis() - ts) / 86400000L;
    }

    /** 解析时间：数字=毫秒；yyyy-MM-dd[ HH:mm[:ss]] 按本地时区。 */
    public static long parseTime(String value) {
        if (value == null) {
            return 0;
        }
        String v = value.trim();
        if (v.length() == 0) {
            return 0;
        }
        if (v.matches("\\d{10,14}")) {
            try {
                return Long.parseLong(v);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
                "yyyy/MM/dd HH:mm", "yyyy/MM/dd", "MM-dd", "HH:mm"};
        for (String p : patterns) {
            try {
                String src = v;
                if ("MM-dd".equals(p)) {
                    src = new GregorianCalendar().get(Calendar.YEAR) + "-" + v;
                    p = "yyyy-MM-dd";
                }
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, Locale.CHINA);
                f.setLenient(true);
                Date d = f.parse(src);
                if (d != null) {
                    return d.getTime();
                }
            } catch (Throwable ignored) {
                // 试下一个格式
            }
        }
        return 0;
    }

    /** 稳定哈希（同一个输入永远同一个值，用于灰度分流）。 */
    public static int stableHash(String s) {
        if (s == null) {
            s = "";
        }
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return (int) Math.abs(h % 2147483647L);
    }

    private static Pattern regex(String re) {
        try {
            return Pattern.compile(re, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new Error("正则写错了：" + e.getDescription());
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object x : (List<?>) o) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(str(x));
            }
            return sb.toString();
        }
        return String.valueOf(o);
    }

    private static Double num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Double) {
            return (Double) o;
        }
        if (o instanceof Boolean) {
            return ((Boolean) o) ? 1.0 : 0.0;
        }
        String s = String.valueOf(o).trim();
        if (s.length() == 0) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static double numOrZero(Object o) {
        Double d = num(o);
        return d == null ? 0 : d;
    }
}
