package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **NBT 自带槽位号**的容器布局：MEK 那类机器把物品栏存成
 * "一个列表 + 每个元素自带 {@code slot} 号"，例如
 *
 * <pre>{@code
 * {Items:[
 *    {Slot:0b, Item:{id:"minecraft:cobblestone", Count:2}},
 *    {Slot:5b, Item:{id:"minecraft:iron_ingot",  Count:64}}
 * ], Energy:1234}
 * }</pre>
 *
 * 【为什么需要它】
 * 这类机器的槽位号**不在菜单里、也不在能力(handler)的下标里** —— 它写在 NBT 自己的
 * {@code Slot} 字段上。按菜单位置读、按容器下标读，两条路都会对不上（实测出现过
 * "GUI 里 2 个、关掉 GUI 后 8 个"）。唯一可靠的读法是：**认 NBT 里的 slot 号**。
 *
 * 【由此得到的采样语义（比菜单/世界两条路都简单）】
 *   · 选中某一格 → 记下它的 NBT slot 号 → 之后每 tick 读
 *     "列表里 slot == n 的那个元素" → {@code Item.Count} / {@code Item.id}；
 *   · **GUI 开不开都一样**（这一路完全不碰菜单），于是不存在"两条路径不一致"；
 *   · 元素不存在（格子空了）= 数量 0（这才是"这格是空的"的诚实表达）。
 *
 * 【拾取时的规则】见 {@link SlotAlign}—— 这里只提供它需要的原料：
 * 按 slot 找元素（{@link #bySlot}）、按物品 id 反查（{@link #byItem}）。
 * **"点到的格子 ↔ 哪个 slot"这件事不在本类里定** —— 它靠"顺序 + 内容"对齐，
 * 因为"容器下标"这种做法在模组世界里会骗人（实测：Mekanism 每格都报 0）。
 *
 * 纯逻辑（不引用任何游戏类），离线可断言（TestChart t30）。
 */
public final class NbtSlotLayout {

    /** 列表里一个元素：槽位号 + 物品 + 数量 */
    public static final class Entry {
        private final int slot;
        private final @Nullable String itemId;
        private final int count;

        public Entry(int slot, @Nullable String itemId, int count) {
            this.slot = slot;
            this.itemId = itemId;
            this.count = count;
        }

        public int slot() {
            return slot;
        }

        public String itemId() {
            return itemId;
        }

        public int count() {
            return count;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Entry other = (Entry) o;
            return java.util.Objects.equals(this.slot, other.slot) && java.util.Objects.equals(this.itemId, other.itemId) && java.util.Objects.equals(this.count, other.count);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(slot, itemId, count);
        }

        @Override
        public String toString() {
            return "Entry[" + "slot=" + slot + ", " + "itemId=" + itemId + ", " + "count=" + count + "]";
        }

    }

    /** 检测到的布局：列表在 NBT 里的路径（如 {@code Items}）+ 当前元素 */
    public static final class Layout {
        private final String listPath;
        private final List<Entry> entries;

        public Layout(String listPath, List<Entry> entries) {
            this.listPath = listPath;
            this.entries = entries;
        }

        public String listPath() {
            return listPath;
        }

        public List<Entry> entries() {
            return entries;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Layout other = (Layout) o;
            return java.util.Objects.equals(this.listPath, other.listPath) && java.util.Objects.equals(this.entries, other.entries);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(listPath, entries);
        }

        @Override
        public String toString() {
            return "Layout[" + "listPath=" + listPath + ", " + "entries=" + entries + "]";
        }

    }

    /** NBT 里表示槽位号的字段名（忽略大小写） */
    private static final String[] SLOT_KEYS = {"slot", "slotslot", "slotindex", "slotid"};
    /** 表示"物品"的嵌套复合字段名（忽略大小写） */
    private static final String[] ITEM_KEYS = {"item", "stack", "itemstack"};
    /** 数量 / 名称字段名（忽略大小写） */
    private static final String[] COUNT_KEYS = {"count", "amount", "size"};
    private static final String[] ID_KEYS = {"id", "name", "item", "itemid"};

    private NbtSlotLayout() {
    }

    /**
     * 在一整棵 NBT 树里找"自带 slot 号的物品列表"。找不到返回 null。
     * 多个候选时取**元素最多**的那个（物品栏通常比别的列表长）。
     */
    @Nullable
    public static Layout detect(@Nullable Snbt.Node root) {
        if (root == null) {
            return null;
        }
        List<Layout> found = new ArrayList<>();
        walk(root, "", found);
        Layout best = null;
        for (Layout l : found) {
            if (best == null || l.entries().size() > best.entries().size()) {
                best = l;
            }
        }
        return best;
    }

    /**
     * 这棵树的**方块实体类型 id**（根上的 {@code id} 字段，如 {@code mekanism:factory}）。
     *
     * 用来回答"这块地方的设备换掉了没有" —— 锚点建档时记下它，采样时跟方块实体现读的
     * 类型比一比：不一样就说明"这个 slot 号对现在这个方块已经没有意义了"，该记 no_data
     * 而不是记 0。`data get block` 的输出带 {@code id}
     * （`BlockDataAccessor` 走 `saveWithFullMetadata` → `saveId`），所以建档时拿得到。
     */
    @Nullable
    public static String rootTypeId(@Nullable Snbt.Node root) {
        if (root == null || root.kind() != Snbt.Kind.COMPOUND) {
            return null;
        }
        Snbt.Node id = root.children().get("id");
        return id == null ? null : id.text();
    }

    /** 按 slot 号找元素；不存在（= 这一格是空的）返回 null */
    @Nullable
    public static Entry bySlot(@Nullable Snbt.Node root, @Nullable String listPath, int slot) {
        List<Entry> entries = entriesAt(root, listPath);
        for (Entry e : entries) {
            if (e.slot() == slot) {
                return e;
            }
        }
        return null;
    }

    /**
     * 按**物品 id**反查全部占用该物品的 slot（拾取时用）。
     *
     * 为什么按 id 而不是"id + 数量" * 运行中的机器每秒都在改变数量（输入被消耗、产出被拿走），
     * 用数量匹配会因为"菜单快照"和"NBT 快照"读取时刻不同而**漏格**（实测踩过）。
     * 物品 id 在一次会话里稳定得多 —— 而且同种物品多格本来就该**加起来**，
     * 不需要区分是哪一格。
     */
    public static List<Entry> byItem(@Nullable Snbt.Node root, @Nullable String listPath,
                                     @Nullable String itemId) {
        List<Entry> out = new ArrayList<>();
        if (itemId == null || itemId.trim().isEmpty()) {
            return out;
        }
        for (Entry e : entriesAt(root, listPath)) {
            if (itemId.equals(e.itemId())) {
                out.add(e);
            }
        }
        return out;
    }

    /** 按"物品 id + 数量"反查（保留给需要精确匹配的场合） */
    public static List<Entry> byContent(@Nullable Snbt.Node root, @Nullable String listPath,
                                        @Nullable String itemId, int count) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entriesAt(root, listPath)) {
            if (e.itemId() != null && e.itemId().equals(itemId) && e.count() == count) {
                out.add(e);
            }
        }
        return out;
    }

    /** 取某个列表路径下的全部元素（路径为空/找不到 → 空表） */
    public static List<Entry> entriesAt(@Nullable Snbt.Node root, @Nullable String listPath) {
        if (root == null || listPath == null || listPath.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Snbt.Node node = root;
        for (Snbt.Token t : Snbt.parsePath(listPath)) {
            if (!t.isIndex()) {
                node = node.children().get(t.key());
                if (node == null) {
                    return java.util.Collections.emptyList();
                }
            } else {
                if (t.index() < 0 || t.index() >= node.items().size()) {
                    return java.util.Collections.emptyList();
                }
                node = node.items().get(t.index());
            }
        }
        return readEntries(node);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 深度优先找所有"像槽位列表"的列表 */
    private static void walk(Snbt.Node node, String path, List<Layout> out) {
        if (node == null) {
            return;
        }
        if (node.kind() == Snbt.Kind.LIST) {
            List<Entry> entries = readEntries(node);
            if (!entries.isEmpty()) {
                out.add(new Layout(path, entries));
            }
            // 列表里的复合也可能继续嵌套（少见），顺手也走一遍
            for (int i = 0; i < node.items().size(); i++) {
                walk(node.items().get(i), path + "[" + i + "]", out);
            }
            return;
        }
        for (java.util.Map.Entry<String, Snbt.Node> e : node.children().entrySet()) {
            String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
            walk(e.getValue(), child, out);
        }
    }

    /**
     * 把一个 LIST 读成槽位元素；**每个元素都得有 slot 号**才算（否则这不是槽位列表，
     * 例如 {@code tanks:[{FluidName:...,amount:...}]} 不该被当成物品栏）。
     * 一个元素都没有（列表空）时也返回空表 —— 空列表无法证明它是槽位列表。
     */
    private static List<Entry> readEntries(@Nullable Snbt.Node list) {
        if (list == null || list.kind() != Snbt.Kind.LIST || list.items().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>(list.items().size());
        for (Snbt.Node el : list.items()) {
            if (el == null || el.kind() != Snbt.Kind.COMPOUND) {
                return java.util.Collections.emptyList();
            }
            Integer slot = intField(el, SLOT_KEYS);
            if (slot == null) {
                return java.util.Collections.emptyList();
            }
            Snbt.Node itemNode = firstChild(el, ITEM_KEYS);
            Snbt.Node idHolder = itemNode != null ? itemNode : el;
            String id = stringField(idHolder, ID_KEYS);
            Integer count = intField(idHolder, COUNT_KEYS);
            out.add(new Entry(slot, id, count == null ? 0 : Math.max(0, count)));
        }
        return out;
    }

    /**
     * 找一个"整数"字段（名字在 keys 里，忽略大小写；数值可以是 byte/short/int/long）。
     * **装不进 int 或不是有限值就当作没有这个字段** —— 直接 `(int)` 窄化会把
     * 超大值变成 MAX_VALUE、NaN 变成 0，然后当成真实的 slot 号/数量用。
     */
    @Nullable
    private static Integer intField(Snbt.Node compound, String[] keys) {
        Snbt.Node n = firstChild(compound, keys);
        if (n == null || !n.numeric()) {
            return null;
        }
        double v = n.number();
        if (!Double.isFinite(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            return null;
        }
        return (int) v;
    }

    @Nullable
    private static String stringField(Snbt.Node compound, String[] keys) {
        Snbt.Node n = firstChild(compound, keys);
        if (n == null) {
            return null;
        }
        if (n.text() != null) {
            return n.text();
        }
        return n.numeric() ? n.raw() : null;
    }

    @Nullable
    private static Snbt.Node firstChild(Snbt.Node compound, String[] keys) {
        if (compound == null || compound.kind() != Snbt.Kind.COMPOUND) {
            return null;
        }
        for (java.util.Map.Entry<String, Snbt.Node> e : compound.children().entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            for (String k : keys) {
                if (lower.equals(k)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }
}
