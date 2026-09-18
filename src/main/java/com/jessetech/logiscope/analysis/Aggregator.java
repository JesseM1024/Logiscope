package com.jessetech.logiscope.analysis;

import java.util.List;

/**
 * 分析层：把「一个探针同时监控的多个槽位」聚合成**一条样本的内容**。
 *
 * ★ 为什么聚合要单独抽出来、而且要放在 analysis 层 ★
 *   存储层（store）和分析层（analysis）都不 import 任何 {@code net.minecraft} 类，
 *   这是 {@code dev-check/} 能脱离游戏跑断言的前提（ARCHITECTURE.md 契约 C8）。
 *   聚合规则恰恰是这次改动里最容易被写错、又最难在游戏里肉眼验证的一段：
 *   "两个槽位装同种物品" 和 "两个槽位装不同物品" 应该产出什么？
 *   把它做成输入是 itemId + count 的纯函数，就能用 javac + java 两秒验证完。
 *
 * ★ 聚合规则（多格监控的语义）★
 *   一条样本只能表示「一个物品 id + 一个数量」，而多个被监控的格子可能装着不同的东西。
 *   规则如下，**不做任何猜测**：
 *
 *   | 被监控的格子 | 聚合结果 |
 *   |---|---|
 *   | 全部为空（或总数为 0） | {@code minecraft:air} × 0 —— 就是"空"，画成那条灰蓝线 |
 *   | 非空的格子里只有**一种**物品 | 那种物品 × **总数**（加在一起，这是需求要的） |
 *   | 非空的格子里有**多种**物品 | {@code logiscope:mixed} × **总数** |
 *
 *   "混合"这一档刻意不选"最多的那个物品"或"第一个物品"：
 *   那会让图表上的物品名与实际内容不符，读数直接错。
 *   代价是混合状态下看不出具体是哪些物品 —— 这是诚实的代价，不是缺漏。
 *
 *   注意：数量在混合时仍然是**所有格子之和**，所以"总吞吐/总存量"这条线始终有效；
 *   只是"这是什么物品"这一刻无从概括。
 */
public final class Aggregator {

    /** 多个被监控的格子里装着不同物品时的合成物品 id */
    public static final String MIXED_ITEM = "logiscope:mixed";

    /** 全空时的物品 id（与 {@code Sample.EMPTY_ITEM} 相同，这里不引 store 包以免反向依赖） */
    public static final String EMPTY_ITEM = "minecraft:air";

    private Aggregator() {
    }

    /**
     * 一次聚合的结果。
     *
     * @param itemId 物品 id（含 {@link #MIXED_ITEM} 与 {@link #EMPTY_ITEM} 两个哨兵）
     * @param count  所有被监控格子的数量之和
     */
    public record Result(String itemId, int count) {

        /** 数量为 0 / 全空 —— 是"读到值是 0"，不是"没读到"（后者是 no_data） */
        public boolean empty() {
            return count <= 0 || EMPTY_ITEM.equals(itemId);
        }

        /** 多个格子里装着不同物品 */
        public boolean mixed() {
            return MIXED_ITEM.equals(itemId);
        }
    }

    /** 空结果（全空） */
    public static final Result EMPTY_RESULT = new Result(EMPTY_ITEM, 0);

    /**
     * 数组版本（采集层每 tick 调用，避免每 tick 造两个 List）。
     *
     * @param itemIds  各格物品 id
     * @param counts   各格数量
     * @param n        实际使用的长度
     */
    public static Result combine(String[] itemIds, int[] counts, int n) {
        int total = 0;
        String single = null;
        boolean mixed = false;
        int len = Math.min(n, Math.min(itemIds.length, counts.length));
        for (int i = 0; i < len; i++) {
            int c = counts[i];
            if (c <= 0) {
                continue;
            }
            total += c;
            String id = itemIds[i];
            if (single == null) {
                single = id;
            } else if (!single.equals(id)) {
                mixed = true;
            }
        }
        if (single == null || total <= 0) {
            return EMPTY_RESULT;
        }
        return new Result(mixed ? MIXED_ITEM : single, total);
    }

    /** List 版本，给界面 / 断言用；与数组版本语义完全一致 */
    public static Result combine(List<String> itemIds, List<Integer> counts) {
        int n = Math.min(itemIds.size(), counts.size());
        String[] ids = new String[n];
        int[] cs = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = itemIds.get(i);
            cs[i] = counts.get(i);
        }
        return combine(ids, cs, n);
    }
}
