# -*- coding: utf-8 -*-
import io

# ================= Snbt：节点带源文本偏移（悬浮高亮要用） =================
p = "src/main/java/com/jessetech/logiscope/analysis/Snbt.java"
s = io.open(p, encoding="utf-8").read()

old = """    /** 一个 NBT 节点。叶子（STRING / NUMBER）才有 {@code raw} 与数值含义 */
    public static final class Node {

        private final Kind kind;
        private final Map<String, Node> children;
        private final List<Node> items;
        private final String raw;
        private final double number;
        private final boolean numeric;

        private Node(Kind kind, Map<String, Node> children, List<Node> items,
                     String raw, double number, boolean numeric) {
            this.kind = kind;
            this.children = children;
            this.items = items;
            this.raw = raw;
            this.number = number;
            this.numeric = numeric;
        }"""
new = """    /** 一个 NBT 节点。叶子（STRING / NUMBER）才有 {@code raw} 与数值含义 */
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
        }"""
assert old in s
s = s.replace(old, new, 1)

# parseValue：记录区间
old2 = """    @Nullable
    private static Node parseValue(Cursor c, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        c.skipWs();
        char ch = c.peek();
        return switch (ch) {
            case '{' -> parseCompound(c, depth);
            case '[' -> parseList(c, depth);
            case '"', '\\'' -> parseQuoted(c);
            case '\\0' -> null;
            default -> parseBare(c);
        };
    }"""
new2 = """    @Nullable
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
            case '"', '\\'' -> parseQuoted(c);
            case '\\0' -> null;
            default -> parseBare(c);
        };
        if (n != null) {
            n.mark(start, c.pos());
        }
        return n;
    }"""
assert old2 in s
s = s.replace(old2, new2, 1)

# Cursor：暴露 pos
old3 = """        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }"""
new3 = """        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        int pos() {
            return i;
        }"""
assert old3 in s
s = s.replace(old3, new3, 1)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("Snbt ok")
