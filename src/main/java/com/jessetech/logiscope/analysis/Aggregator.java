package com.jessetech.logiscope.analysis;

import com.jessetech.logiscope.store.Sample;

import java.util.ArrayList;
import java.util.List;

/**
 * 分析层：把「一个探针同时监控的多个槽位」按**物品**求和。
 *
 * 现行语义。需求是："多格槽位**一起计算**，但不同物品仍使用先前的**同一图表中不同颜色线**
 *   的显示模式。"
 *
 *   也就是**不要**把多种物品合成一条"混合物品"线，而是：
 *
 *   | 两个被监控的格子 | 记下来的内容 |
 *   |---|---|
 *   | 都是 64 铁 | 铁 × 128（一条线） |
 *   | 64 铁 + 32 铜 | **铁 × 64、铜 × 32**（两条线，各有各的颜色） |
 *   | 都是空 | 空（`minecraft:air` × 0，一条灰蓝线） |
 *
 *   于是"多格"只体现在**同种物品的数量相加**上，物品维度完全交给原来的
 *   "按物品分线 + 每种一条颜色"机制 —— 下游（存储 / 波形 / 图例 / 框选统计）
 *   一行都不用改，这正是复用既有架构的好处。
 *
 *   这里刻意**没有**"混合物品"这种合成 id。第一版做过，需求明确否掉了：
 *   那种画法把一个格子的物流信息压成一条线，等于把分析工具最重要的维度丢了。
 *
 * 一个 tick 会产生多条样本：输出的每一条 → `SampleStore` 里的一条 `Sample`（同一个 tick 值）。
 *   所以"每 tick 一条样本"这条老契约升级为"**每 tick 每条物品一条样本**"，
 *   相应的断点判定改成 per-series（见 {@link ChartData#build}）——
 *   不改的话同一 tick 的两条样本会被当成"物品切换"，波形碎成一堆孤立点。
 *
 * 纯函数、不 import 任何游戏类（可离线断言，见 {@code dev-check/TestChart.java}）。
 */
public final class Aggregator {

    /** 全部为空时的物品 id（与 {@code Sample.EMPTY_ITEM} 相同，这里不引 store 包以免反向依赖） */
    public static final String EMPTY_ITEM = "minecraft:air";

    private Aggregator() {
    }

    /**
     * 一次求和的结果。
     *
     * @param itemId 物品 id（{@link #EMPTY_ITEM} 表示"全空"这一档）
     * @param count  所有装着该物品的格子的数量之和
     */
    public record Totals(String itemId, Val count) {

        /** 数量为 0 / 全空 —— 是"读到值是 0"，不是"没读到"（后者是 no_data） */
        public boolean empty() {
            return count.compareTo(Val.ZERO) <= 0 || EMPTY_ITEM.equals(itemId);
        }
    }

    public static final Totals EMPTY_TOTAL = new Totals(EMPTY_ITEM, Val.ZERO);

    /**
     * 数组版本（采集层每 tick 调用，零分配：输出写进调用方复用的数组）。
     *
     * @param itemIds  各格物品 id
     * @param counts   各格数量
     * @param n        实际使用的长度
     * @param outIds   输出缓冲（长度需 ≥ n）
     * @param outCounts 输出缓冲（长度需 ≥ n）
     * @return 产出的条目数。**全空时返回 1**（一条 {@code air × 0}，让"被抽空了"仍然看得见）
     */
    public static int combine(String[] itemIds, Val[] counts, int n,
                              String[] outIds, Val[] outCounts) {
        int len = Math.min(n, Math.min(itemIds.length, counts.length));
        int cap = Math.min(outIds.length, outCounts.length);
        int m = 0;
        for (int i = 0; i < len; i++) {
            Val c = counts[i];
            if (c.compareTo(Val.ZERO) <= 0) {
                continue;
            }
            String id = itemIds[i];
            int at = indexOf(outIds, m, id);
            if (at >= 0) {
                outCounts[at] = outCounts[at].plus(c);
            } else if (m < cap) {
                outIds[m] = id;
                outCounts[m] = c;
                m++;
            }
            // 缓冲不够时直接丢弃多出来的物品（正常调用不会发生：缓冲按选中格数分配）
        }
        if (m == 0 && cap > 0) {
            outIds[0] = EMPTY_ITEM;
            outCounts[0] = Val.ZERO;
            return 1;
        }
        return m;
    }

    /**
     * 数据目标的合并：与 {@link #combine} 同为"同名求和"，但**保留 0 与负数**。
     *
     * 为什么不能复用 {@code combine}：那是物品语义——数量 ≤ 0 的格子是"空"，
     * 直接跳过；而 NBT 里取的数（能量、流体量）**0 是真实读数**，某些量还可能为负。
     * 把 0 丢掉 = 编造"这一刻没观测到"，违反本工具的数据保真底线。
     *
     * 调用约定：入参里**没有**需要跳过的条目（读不到的目标在调用侧已剔除），
     * 所以本方法不产生 air 占位、不跳过任何条目。
     *
     * @return 产出的条目数（同名合并后）
     */
    public static int combineAll(String[] ids, Val[] counts, int n,
                                 String[] outIds, Val[] outCounts) {
        int len = Math.min(n, Math.min(ids.length, counts.length));
        int cap = Math.min(outIds.length, outCounts.length);
        int m = 0;
        for (int i = 0; i < len; i++) {
            String id = ids[i];
            int at = indexOf(outIds, m, id);
            if (at >= 0) {
                outCounts[at] = outCounts[at].plus(counts[i]);
            } else if (m < cap) {
                outIds[m] = id;
                outCounts[m] = counts[i];
                m++;
            }
        }
        return m;
    }

    private static int indexOf(String[] a, int len, String v) {
        for (int i = 0; i < len; i++) {
            if (a[i].equals(v)) {
                return i;
            }
        }
        return -1;
    }

    /** List 版本，给界面 / 断言用；与数组版本语义完全一致 */
    public static List<Totals> combine(List<String> itemIds, List<Val> counts) {
        int n = Math.min(itemIds.size(), counts.size());
        String[] ids = new String[n];
        Val[] cs = new Val[n];
        for (int i = 0; i < n; i++) {
            ids[i] = itemIds.get(i);
            cs[i] = counts.get(i);
        }
        String[] outIds = new String[Math.max(1, n)];
        Val[] outCounts = new Val[Math.max(1, n)];
        int m = combine(ids, cs, n, outIds, outCounts);
        List<Totals> out = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            out.add(new Totals(outIds[i], outCounts[i]));
        }
        return out;
    }
}
