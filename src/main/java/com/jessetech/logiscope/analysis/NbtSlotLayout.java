package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **NBT 自带槽位号**的容器布局（第十六轮）：MEK 那类机器把物品栏存成
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
 * 【拾取时的规则】点到一个格子时用"物品 id + 数量"去 NBT 里反查 slot 号：
 *   · 唯一命中 → 用它；
 *   · 没有命中（空格子，NBT 里没有对应元素）→ **不选它**（没法知道 slot 号）；
 *   · 命中多个（同物品同数量有好几格，分不清）→ 也**不选**（宁可少选，不猜）。
 *
 * 纯逻辑（不引用任何游戏类），离线可断言（TestChart t30）。
 */
public final class NbtSlotLayout {

    /** 列表里一个元素：槽位号 + 物品 + 数量 */
    public record Entry(int slot, @Nullable String itemId, int count) {
    }

    /** 检测到的布局：列表在 NBT 里的路径（如 {@code Items}）+ 当前元素 */
    public record Layout(String listPath, List<Entry> entries) {
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
     * ★ 为什么按 id 而不是"id + 数量" ★
     * 运行中的机器每秒都在改变数量（输入被消耗、产出被拿走），
     * 用数量匹配会因为"菜单快照"和"NBT 快照"读取时刻不同而**漏格**（实测踩过）。
     * 物品 id 在一次会话里稳定得多 —— 而且同种物品多格本来就该**加起来**，
     * 不需要区分是哪一格。
     */
    public static List<Entry> byItem(@Nullable Snbt.Node root, @Nullable String listPath,
                                     @Nullable String itemId) {
        List<Entry> out = new ArrayList<>();
        if (itemId == null || itemId.isBlank()) {
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
        if (root == null || listPath == null || listPath.isBlank()) {
            return List.of();
        }
        Snbt.Node node = root;
        for (Snbt.Token t : Snbt.parsePath(listPath)) {
            if (!t.isIndex()) {
                node = node.children().get(t.key());
                if (node == null) {
                    return List.of();
                }
            } else {
                if (t.index() < 0 || t.index() >= node.items().size()) {
                    return List.of();
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
        for (var e : node.children().entrySet()) {
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
            return List.of();
        }
        List<Entry> out = new ArrayList<>(list.items().size());
        for (Snbt.Node el : list.items()) {
            if (el == null || el.kind() != Snbt.Kind.COMPOUND) {
                return List.of();
            }
            Integer slot = intField(el, SLOT_KEYS);
            if (slot == null) {
                return List.of();
            }
            Snbt.Node itemNode = firstChild(el, ITEM_KEYS);
            Snbt.Node idHolder = itemNode != null ? itemNode : el;
            String id = stringField(idHolder, ID_KEYS);
            Integer count = intField(idHolder, COUNT_KEYS);
            out.add(new Entry(slot, id, count == null ? 0 : Math.max(0, count)));
        }
        return out;
    }

    /** 找一个"整数"字段（名字在 keys 里，忽略大小写；数值可以是 byte/short/int/long） */
    @Nullable
    private static Integer intField(Snbt.Node compound, String[] keys) {
        Snbt.Node n = firstChild(compound, keys);
        if (n == null || !n.numeric()) {
            return null;
        }
        return (int) n.number();
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
        for (var e : compound.children().entrySet()) {
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
