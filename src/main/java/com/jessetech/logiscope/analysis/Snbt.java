package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SNBT 解析（第十一轮③）：把 {@code data get block} 打印出来的那段 NBT 文本
 * 变成一棵可以点、可以选、可以按路径取值的树。
 *
 * ★ 为什么是"通用解析"而不是按模组硬编码键名 ★
 *   模组机器的 NBT 键名千奇百怪（Mekanism 的流体、化学、能量各有各的结构，
 *   每个版本还可能改名）。所以这里只做三件事：**解析 → 摊平成"路径 = 值"的列表 →
 *   让用户点**。用户点中的那条路径就是采样路径，与模组、版本都无关。
 *
 * 【支持的范围】正好是 {@code data get} 会打印的那一套：
 *   · 复合 {@code {a:1,b:"x"}}（键可带引号）
 *   · 列表 {@code [1,2]} 与带类型前缀的 {@code [I;1,2]} / {@code [B;1b,0b]}
 *   · 字符串（带引号，含 {@code \" \\} 等转义；也容忍不带引号的裸串）
 *   · 数字后缀 {@code 1b 1s 1l 1f 1d}（统一按 double 存，采样时四舍五入成整数）
 *
 * 【输入怎么来】命令回复形如
 *   {@code barrel has the following block data: {Items:[...],id:"minecraft:barrel"}}
 * 所以 {@link #parse} 从**第一个 {@code {} 开始**、到与之配对的 {@code }} 结束，
 * 前缀（方块名 / "has the following block data"）自动忽略。
 *
 * 【上限】条目数与深度都有硬上限（见 {@link #MAX_ENTRIES} / {@link #MAX_DEPTH}）：
 * 有些机器的 NBT 能有好几万个数组元素，全部摊平会让界面卡死 —— 超出的直接不列。
 *
 * 纯逻辑层，离线断言覆盖（TestChart t26）。
 */
public final class Snbt {

    /** 摊平后最多列多少条（超出的丢掉，见类注释） */
    public static final int MAX_ENTRIES = 2000;
    /** 最大嵌套深度（防御病态数据） */
    public static final int MAX_DEPTH = 48;

    public enum Kind {
        COMPOUND, LIST, STRING, NUMBER
    }

    /** 一个 NBT 节点。叶子（STRING / NUMBER）才有 {@code raw} 与数值含义 */
    public static final class Node {

        private final Kind kind;
        private final Map<String, Node> children;
        private final List<Node> items;
        private final String raw;
        private final double number;
        private final boolean numeric;
        /**
         * 这个节点在**源文本**里的区间 [start, end)（第十一轮④②）。
         * 界面拿它做"鼠标悬浮值列表 ↔ 高亮 NBT 原文"的联动：偏移是相对
         * 传给 {@link #parse} 的整段文本的（含命令回复的前缀），
         * 与原文折行时的偏移同源，所以直接能用。
         */
        private int start;
        private int end;

        private Node(Kind kind, Map<String, Node> children, List<Node> items,
                     String raw, double number, boolean numeric) {
            this.kind = kind;
            this.children = children;
            this.items = items;
            this.raw = raw;
            this.number = number;
            this.numeric = numeric;
        }

        /** 源文本区间起点（叶子的 token 含引号） */
        public int start() {
            return start;
        }

        /** 源文本区间终点（不含） */
        public int end() {
            return end;
        }

        private void mark(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public Kind kind() {
            return kind;
        }

        /** 复合节点的子项（保持出现顺序）；非复合返回空表 */
        public Map<String, Node> children() {
            return children;
        }

        /** 列表元素；非列表返回空表 */
        public List<Node> items() {
            return items;
        }

        /** 显示用字面量（字符串带引号，数字不带后缀） */
        public String raw() {
            return raw;
        }

        public boolean numeric() {
            return numeric;
        }

        /** 数值（非数值节点为 0） */
        public double number() {
            return number;
        }

        /** 字符串内容（非字符串返回 null） */
        @Nullable
        public String text() {
            return kind == Kind.STRING ? raw : null;
        }
    }

    /** 摊平后的一条"路径 = 值" */
    public record Entry(String path, Node node) {

        public boolean numeric() {
            return node.numeric();
        }

        public boolean textual() {
            return node.kind() == Kind.STRING;
        }

        /** 显示用：`tank.amount = 1000` */
        public String display() {
            return path + " = " + node.raw();
        }
    }

    private Snbt() {
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 从文本里解析出第一个完整的复合节点；找不到 / 不合法返回 null */
    @Nullable
    public static Node parse(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        Cursor c = new Cursor(text, start);
        try {
            Node n = parseValue(c, 0);
            return n != null && n.kind == Kind.COMPOUND ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Node parseValue(Cursor c, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        c.skipWs();
        int start = c.pos();
        char ch = c.peek();
        Node n = switch (ch) {
            case '{' -> parseCompound(c, depth);
            case '[' -> parseList(c, depth);
            case '"', '\'' -> parseQuoted(c);
            case '\0' -> null;
            default -> parseBare(c);
        };
        if (n != null) {
            n.mark(start, c.pos());
        }
        return n;
    }

    private static Node parseCompound(Cursor c, int depth) {
        c.next();   // {
        Map<String, Node> map = new LinkedHashMap<>();
        c.skipWs();
        if (c.peek() == '}') {
            c.next();
            return new Node(Kind.COMPOUND, map, List.of(), "", 0, false);
        }
        while (true) {
            c.skipWs();
            String key = c.peek() == '"' || c.peek() == '\'' ? c.readQuotedString() : c.readUntil(":,}");
            c.skipWs();
            if (c.peek() == ':') {
                c.next();
            }
            Node v = parseValue(c, depth + 1);
            if (v != null) {
                map.put(key, v);
            }
            c.skipWs();
            char sep = c.peek();
            if (sep == ',') {
                c.next();
                continue;
            }
            if (sep == '}') {
                c.next();
                return new Node(Kind.COMPOUND, map, List.of(), "", 0, false);
            }
            if (sep == '\0') {
                // 文本被截断（聊天框有长度上限时会这样）：有多少算多少
                return new Node(Kind.COMPOUND, map, List.of(), "", 0, false);
            }
            c.next();
        }
    }

    private static Node parseList(Cursor c, int depth) {
        c.next();   // [
        List<Node> items = new ArrayList<>();
        c.skipWs();
        // 带类型前缀的列表：[I;1,2] / [B;1b,0b] / [L;...]  —— 前缀直接跳过
        if (Character.isLetter(c.peek())) {
            while (Character.isLetterOrDigit(c.peek())) {
                c.next();
            }
            if (c.peek() == ';') {
                c.next();
            }
        }
        c.skipWs();
        if (c.peek() == ']') {
            c.next();
            return new Node(Kind.LIST, Map.of(), items, "", 0, false);
        }
        while (true) {
            Node v = parseValue(c, depth + 1);
            if (v != null) {
                items.add(v);
            }
            c.skipWs();
            char sep = c.peek();
            if (sep == ',') {
                c.next();
                continue;
            }
            if (sep == ']') {
                c.next();
                return new Node(Kind.LIST, Map.of(), items, "", 0, false);
            }
            if (sep == '\0') {
                return new Node(Kind.LIST, Map.of(), items, "", 0, false);
            }
            c.next();
        }
    }

    private static Node parseQuoted(Cursor c) {
        String s = c.readQuotedString();
        return new Node(Kind.STRING, Map.of(), List.of(), s, 0, false);
    }

    /** 裸串 / 数字（带后缀）：读到分隔符为止 */
    private static Node parseBare(Cursor c) {
        String tok = c.readUntil(",]}");
        if (tok.isEmpty()) {
            c.next();   // 防止死循环
            return null;
        }
        Double num = parseNumber(tok);
        if (num != null) {
            return new Node(Kind.NUMBER, Map.of(), List.of(), trimSuffix(tok), num, true);
        }
        return new Node(Kind.STRING, Map.of(), List.of(), tok, 0, false);
    }

    /** 数字（含 SNBT 后缀 b/s/l/f/d）；不是数字返回 null */
    @Nullable
    public static Double parseNumber(String token) {
        String t = trimSuffix(token);
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去掉 SNBT 的数字后缀（1b / 2s / 3l / 4f / 5d） */
    private static String trimSuffix(String token) {
        if (token.isEmpty()) {
            return token;
        }
        char last = Character.toLowerCase(token.charAt(token.length() - 1));
        if (token.length() > 1 && (last == 'b' || last == 's' || last == 'l'
                || last == 'f' || last == 'd')) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    // ------------------------------------------------------------------
    // 摊平（给界面列出来给用户点）
    // ------------------------------------------------------------------

    /** 把树摊平成"路径 = 值"列表（深度优先，保持 NBT 里的出现顺序） */
    public static List<Entry> flatten(Node root) {
        List<Entry> out = new ArrayList<>();
        flattenInto(root, "", out, 0);
        return out;
    }

    private static void flattenInto(Node n, String path, List<Entry> out, int depth) {
        if (out.size() >= MAX_ENTRIES || depth > MAX_DEPTH) {
            return;
        }
        switch (n.kind) {
            case COMPOUND -> {
                for (Map.Entry<String, Node> e : n.children.entrySet()) {
                    flattenInto(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(),
                            out, depth + 1);
                }
            }
            case LIST -> {
                for (int i = 0; i < n.items.size(); i++) {
                    flattenInto(n.items.get(i), path + "[" + i + "]", out, depth + 1);
                }
                if (n.items.isEmpty() && !path.isEmpty()) {
                    out.add(new Entry(path, n));
                }
            }
            default -> {
                if (!path.isEmpty()) {
                    out.add(new Entry(path, n));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 采样路径
    // ------------------------------------------------------------------

    /** 路径里的一段：{@code key} 或 {@code [index]} */
    public record Token(@Nullable String key, int index, boolean isIndex) {
    }

    /**
     * 解析采样路径：{@code a.b[0].c} → [key a, key b, index 0, key c]。
     * 空路径 / 语法坏掉返回空表（调用方当作"没有路径"处理）。
     */
    public static List<Token> parsePath(String path) {
        List<Token> out = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        int i = 0;
        int n = path.length();
        while (i < n) {
            char ch = path.charAt(i);
            if (ch == '.') {
                i++;
                continue;
            }
            if (ch == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) {
                    return List.of();
                }
                try {
                    out.add(new Token(null, Integer.parseInt(path.substring(i + 1, end).trim()), true));
                } catch (NumberFormatException e) {
                    return List.of();
                }
                i = end + 1;
                continue;
            }
            int start = i;
            while (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') {
                i++;
            }
            String key = path.substring(start, i);
            if (!key.isEmpty()) {
                out.add(new Token(key, -1, false));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 游标
    // ------------------------------------------------------------------

    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s, int start) {
            this.s = s;
            this.i = start;
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void next() {
            if (i < s.length()) {
                i++;
            }
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        int pos() {
            return i;
        }

        /** 读到任一终止字符（不含），用于裸串 / 键 */
        String readUntil(String stops) {
            int start = i;
            while (i < s.length() && stops.indexOf(s.charAt(i)) < 0) {
                i++;
            }
            return s.substring(start, i).trim();
        }

        /** 读带引号的串（含转义） */
        String readQuotedString() {
            char quote = peek();
            if (quote != '"' && quote != '\'') {
                return readUntil(",]}");
            }
            next();
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '\\' && i + 1 < s.length()) {
                    char esc = s.charAt(i + 1);
                    i += 2;
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(esc);
                    }
                    continue;
                }
                if (ch == quote) {
                    i++;
                    break;
                }
                sb.append(ch);
                i++;
            }
            return sb.toString();
        }
    }
}
