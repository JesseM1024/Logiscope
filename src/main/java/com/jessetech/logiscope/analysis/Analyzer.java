package com.jessetech.logiscope.analysis;

import com.jessetech.logiscope.store.Sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析层：纯函数，输入原始序列、输出派生序列。
 *
 * 刻意不引用任何游戏类，也不持有状态 ——
 * 这样它可以被单元测试直接喂造好的数据，也能在导出后离线复用。
 *
 * ★ no_data 样本的处理原则 ★
 * "没读到值"和"格子里是 0 个"是两件完全不同的事，绝不能混进统计：
 *   · 求和 / 极值 / 平均：跳过 no_data
 *   · 首值 / 末值 / 净变化：取最近的两个**有数据**的 tick
 *   · 类型切换：no_data 不算一次切换，它只是断档
 * 图上的处理是"断开"，见 {@code ui/ChartRenderer}（按物品分线 + 断点不连线）
 * 和 {@link ChartData#build}（断点标记的产生处）。
 *
 * ★ 第十轮：一个 tick 可能有**多条**样本 ★
 *   多格监控时每个出现的物品各产生一条样本（见 {@link Aggregator}），
 *   所以本类在统计前先把**同一个 tick 的条目合计成"该 tick 的总存量"**：
 *
 *   | 面板上的数 | 含义 |
 *   |---|---|
 *   | `ticks N` | 有数据的**世界 tick** 数（不是样本条数 —— 多格时两者不等） |
 *   | `min / max / avg` | 每个 tick 的**总存量**的极值与平均 |
 *   | 净变化 | 末 tick 总存量 − 首 tick 总存量 |
 *
 *   这样"总量"这条线在单格和多格下语义一致，数字也可比。
 *
 * 后续要加的东西（见 docs/GOALS.md）：
 *   - 滑窗速率（物品/tick 的平滑曲线）
 *   - 事件检测（物品类型切换、堆叠满仓、净流入/流出阶段）
 *   - 周期检测（判断产线是否达到稳态）
 */
public final class Analyzer {

    private Analyzer() {
    }

    /**
     * 逐 tick 变化量。
     *
     * 注意：物品类型发生变化的那个 tick，count 的差值没有物理意义
     * （比如从 64 个铁锭变成 1 个铜锭，差值 -63 不代表"流出了 63 个"）。
     * 因此这类点用 typeChanged=true 标出来，画图时应断开连线而不是连过去。
     * 中间夹着 no_data 的两个点同样算"断开"，因为它们不相邻。
     *
     * ★ 第十轮：这里刻意保持"逐条样本"而不是"逐 tick 合计" ★
     *   它是给"逐 tick 净流量"这类分析用的底层序列，按物品区分才有意义；
     *   而 {@link #summarize} 要的是"总存量"，所以那边自己先合计。
     *
     *   typeChanged 的判定改成"**同一物品的上一次出现**之后夹了别的物品"，
     *   因为一个 tick 现在可能有多条不同物品的样本（用相邻样本判会误报）。
     */
    public static List<Delta> deltas(List<Sample> samples) {
        List<Delta> out = new ArrayList<>();
        // itemId → {该物品上一次出现的 tick, 数量}。多格监控时每个物品各记各的
        Map<String, long[]> last = new HashMap<>();
        for (Sample cur : samples) {
            if (cur.isNoData()) {
                last.clear();
                continue;
            }
            long[] p = last.get(cur.itemId());
            if (p != null && (cur.tick() - p[0]) <= ChartData.CONTINUITY_GAP) {
                out.add(new Delta(cur.tick(), cur.count() - (int) p[1], false));
            } else {
                // 这个物品上一次出现不在上一个 tick（首见 / 中间断了）→ 差值无意义
                out.add(new Delta(cur.tick(), 0, true));
            }
            last.put(cur.itemId(), new long[]{cur.tick(), cur.count()});
        }
        return out;
    }

    /** 统计摘要，给界面上的数字面板用（跳过 no_data；同 tick 的条目先合计） */
    public static Summary summarize(List<Sample> samples) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        int dataTicks = 0;
        int noDataTicks = 0;
        int rises = 0;
        int falls = 0;

        String dominant = "-";
        int dominantHits = 0;
        Map<String, Integer> hits = new HashMap<>();

        int firstTotal = 0;
        int lastTotal = 0;
        int prevTotal = 0;
        boolean hasPrevTotal = false;

        int i = 0;
        int n = samples.size();
        while (i < n) {
            Sample s = samples.get(i);
            if (s.isNoData()) {
                noDataTicks++;
                // 断档之后不连（净变化仍按"首尾有数据的 tick"算）
                hasPrevTotal = false;
                i++;
                continue;
            }

            // ---- 把同一个 tick 的所有条目合计成"这个 tick 的总存量" ----
            long t = s.tick();
            int total = 0;
            while (i < n) {
                Sample c = samples.get(i);
                if (c.isNoData() || c.tick() != t) {
                    break;
                }
                total += c.count();
                int h = hits.merge(c.itemId(), 1, Integer::sum);
                if (h > dominantHits) {
                    dominantHits = h;
                    dominant = c.itemId();
                }
                i++;
            }

            if (dataTicks == 0) {
                firstTotal = total;
            }
            lastTotal = total;
            dataTicks++;
            min = Math.min(min, total);
            max = Math.max(max, total);
            sum += total;

            if (hasPrevTotal) {
                if (total > prevTotal) {
                    rises++;
                } else if (total < prevTotal) {
                    falls++;
                }
            }
            prevTotal = total;
            hasPrevTotal = true;
        }

        if (dataTicks == 0) {
            return new Summary("-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, noDataTicks);
        }

        return new Summary(
                dominant,
                min,
                max,
                (int) (sum / dataTicks),
                firstTotal,
                lastTotal,
                lastTotal - firstTotal,
                ChartData.countItemSetSwitches(samples),
                rises,
                falls,
                dataTicks,
                noDataTicks);
    }

    /** 单点变化量 */
    public record Delta(long tick, int delta, boolean typeChanged) {
    }

    /**
     * 统计摘要。
     *
     * @param dominantItem 出现次数最多的物品（按样本条数计）
     * @param min/max/avg  每个 tick **总存量**的极值与平均
     * @param first/last   首 / 末个有数据 tick 的总存量
     * @param netChange    末 − 首
     * @param typeChanges  物品集合发生变化的次数（= {@link ChartData#countItemSetSwitches}）
     * @param rises/falls  相邻有数据 tick 之间总量上升 / 下降的次数
     * @param dataTicks    有数据的**世界 tick** 数（不是样本条数 —— 多格时一个 tick 可能好几条）
     * @param noDataTicks  无数据的 tick 数（一个 tick 最多一条 no_data）
     */
    public record Summary(
            String dominantItem,
            int min,
            int max,
            int average,
            int first,
            int last,
            int netChange,
            int typeChanges,
            int rises,
            int falls,
            int dataTicks,
            int noDataTicks) {

        public int totalTicks() {
            return dataTicks + noDataTicks;
        }

        /** 有没有"读到值"的 tick */
        public boolean hasData() {
            return dataTicks > 0;
        }

        public static final Summary EMPTY =
                new Summary("-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
