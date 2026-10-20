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
 * 标记样本（no_data / gap）的处理原则 * "没读到值"、"没在记录"和"格子里是 0 个"是三件完全不同的事，绝不能混进统计：
 *   · 求和 / 极值 / 平均：跳过标记（{@link Sample#isMarker()}）
 *   · 首值 / 末值 / 净变化：取最近的两个**有数据**的 tick
 *   · 类型切换：标记不算一次切换，它只是断档
 * 图上的处理是"断开"，见 {@code ui/ChartRenderer}（按物品分线 + 断点不连线）
 * 和 {@link ChartData#build}（断点标记的产生处）。
 *
 * 一个 tick 可能有**多条**样本。多格监控时每个出现的物品各产生一条样本（见 {@link Aggregator}），
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

    /**
     * {@link #deltas} 里"上一个点还算是相邻"的最大 tick 间隔。
     *
     * 与画图的断点规则无关（那边看标记，见 {@link ChartData#build}）：
     * 这里判的是"这个物品的变化量能不能直接相减"。间隔 1 = 紧挨着的上一个 tick；
     * 中间哪怕只漏观测一个 tick，差值也可能少算一次 IO，所以标记为"不可直接相减"。
     */
    private static final long MAX_DELTA_GAP = 1;

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
     * 这里刻意保持"逐条样本"而不是"逐 tick 合计"：它是给"逐 tick 净流量"这类分析用的底层序列，按物品区分才有意义；
     *   而 {@link #summarize} 要的是"总存量"，所以那边自己先合计。
     *
     *   typeChanged 的判定改成"**同一物品的上一次出现**之后夹了别的物品"，
     *   因为一个 tick 现在可能有多条不同物品的样本（用相邻样本判会误报）。
     */
    public static List<Delta> deltas(List<Sample> samples) {
        List<Delta> out = new ArrayList<>();
        // itemId → 该物品上一次出现（tick + 值）。多格监控时每个物品各记各的
        Map<String, Last> last = new HashMap<>();
        for (Sample cur : samples) {
            if (cur.isMarker()) {
                last.clear();
                continue;
            }
            Last p = last.get(cur.itemId());
            if (p != null && (cur.tick() - p.tick()) <= MAX_DELTA_GAP) {
                out.add(new Delta(cur.tick(), cur.value().minus(p.value()), false));
            } else {
                // 这个物品上一次出现不在上一个 tick（首见 / 中间断了）→ 差值无意义
                out.add(new Delta(cur.tick(), Val.ZERO, true));
            }
            last.put(cur.itemId(), new Last(cur.tick(), cur.value()));
        }
        return out;
    }

    /** deltas 的"上一次出现"记录 */
    private static final class Last {
        private final long tick;
        private final Val value;

        public Last(long tick, Val value) {
            this.tick = tick;
            this.value = value;
        }

        public long tick() {
            return tick;
        }

        public Val value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Last other = (Last) o;
            return java.util.Objects.equals(this.tick, other.tick) && java.util.Objects.equals(this.value, other.value);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(tick, value);
        }

        @Override
        public String toString() {
            return "Last[" + "tick=" + tick + ", " + "value=" + value + "]";
        }

    }

    /** 统计摘要，给界面上的数字面板用（跳过 no_data；同 tick 的条目先合计） */
    public static Summary summarize(List<Sample> samples) {
        Val min = null;
        Val max = null;
        Val sum = Val.ZERO;
        int dataTicks = 0;
        int noDataTicks = 0;
        int rises = 0;
        int falls = 0;

        String dominant = "-";
        int dominantHits = 0;
        Map<String, Integer> hits = new HashMap<>();

        Val firstTotal = Val.ZERO;
        Val lastTotal = Val.ZERO;
        Val prevTotal = Val.ZERO;
        boolean hasPrevTotal = false;

        int i = 0;
        int n = samples.size();
        while (i < n) {
            Sample s = samples.get(i);
            if (s.isMarker()) {
                // 中断标记不计入 no-data（那是"读了读不到"的专属计数），
                // 但两者的统计行为一样：都不是数据，都让"净变化"的链条断开
                if (s.isNoData()) {
                    noDataTicks++;
                }
                hasPrevTotal = false;
                i++;
                continue;
            }

            // ---- 把同一个 tick 的所有条目合计成"这个 tick 的总存量" ----
            long t = s.tick();
            Val total = Val.ZERO;
            while (i < n) {
                Sample c = samples.get(i);
                if (c.isMarker() || c.tick() != t) {
                    break;
                }
                total = total.plus(c.value());
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
            if (min == null || total.compareTo(min) < 0) {
                min = total;
            }
            if (max == null || total.compareTo(max) > 0) {
                max = total;
            }
            sum = sum.plus(total);

            if (hasPrevTotal) {
                if (total.compareTo(prevTotal) > 0) {
                    rises++;
                } else if (total.compareTo(prevTotal) < 0) {
                    falls++;
                }
            }
            prevTotal = total;
            hasPrevTotal = true;
        }

        if (dataTicks == 0) {
            return new Summary("-", Val.ZERO, Val.ZERO, Val.ZERO, Val.ZERO, Val.ZERO, Val.ZERO,
                    0, 0, 0, 0, noDataTicks);
        }

        return new Summary(
                dominant,
                min,
                max,
                Val.of(sum.asDouble() / dataTicks),
                firstTotal,
                lastTotal,
                lastTotal.minus(firstTotal),
                ChartData.countItemSetSwitches(samples),
                rises,
                falls,
                dataTicks,
                noDataTicks);
    }

    /** 单点变化量（自适应数值：整数差保持精确，小数差是 double） */
    public static final class Delta {
        private final long tick;
        private final Val delta;
        private final boolean typeChanged;

        public Delta(long tick, Val delta, boolean typeChanged) {
            this.tick = tick;
            this.delta = delta;
            this.typeChanged = typeChanged;
        }

        public long tick() {
            return tick;
        }

        public Val delta() {
            return delta;
        }

        public boolean typeChanged() {
            return typeChanged;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Delta other = (Delta) o;
            return java.util.Objects.equals(this.tick, other.tick) && java.util.Objects.equals(this.delta, other.delta) && java.util.Objects.equals(this.typeChanged, other.typeChanged);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(tick, delta, typeChanged);
        }

        @Override
        public String toString() {
            return "Delta[" + "tick=" + tick + ", " + "delta=" + delta + ", " + "typeChanged=" + typeChanged + "]";
        }

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
    public static final class Summary {
        private final String dominantItem;
        private final Val min;
        private final Val max;
        private final Val average;
        private final Val first;
        private final Val last;
        private final Val netChange;
        private final int typeChanges;
        private final int rises;
        private final int falls;
        private final int dataTicks;
        private final int noDataTicks;

        public Summary(String dominantItem, Val min, Val max, Val average, Val first, Val last, Val netChange, int typeChanges, int rises, int falls, int dataTicks, int noDataTicks) {
            this.dominantItem = dominantItem;
            this.min = min;
            this.max = max;
            this.average = average;
            this.first = first;
            this.last = last;
            this.netChange = netChange;
            this.typeChanges = typeChanges;
            this.rises = rises;
            this.falls = falls;
            this.dataTicks = dataTicks;
            this.noDataTicks = noDataTicks;
        }

        public String dominantItem() {
            return dominantItem;
        }

        public Val min() {
            return min;
        }

        public Val max() {
            return max;
        }

        public Val average() {
            return average;
        }

        public Val first() {
            return first;
        }

        public Val last() {
            return last;
        }

        public Val netChange() {
            return netChange;
        }

        public int typeChanges() {
            return typeChanges;
        }

        public int rises() {
            return rises;
        }

        public int falls() {
            return falls;
        }

        public int dataTicks() {
            return dataTicks;
        }

        public int noDataTicks() {
            return noDataTicks;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Summary other = (Summary) o;
            return java.util.Objects.equals(this.dominantItem, other.dominantItem) && java.util.Objects.equals(this.min, other.min) && java.util.Objects.equals(this.max, other.max) && java.util.Objects.equals(this.average, other.average) && java.util.Objects.equals(this.first, other.first) && java.util.Objects.equals(this.last, other.last) && java.util.Objects.equals(this.netChange, other.netChange) && java.util.Objects.equals(this.typeChanges, other.typeChanges) && java.util.Objects.equals(this.rises, other.rises) && java.util.Objects.equals(this.falls, other.falls) && java.util.Objects.equals(this.dataTicks, other.dataTicks) && java.util.Objects.equals(this.noDataTicks, other.noDataTicks);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(dominantItem, min, max, average, first, last, netChange, typeChanges, rises, falls, dataTicks, noDataTicks);
        }

        @Override
        public String toString() {
            return "Summary[" + "dominantItem=" + dominantItem + ", " + "min=" + min + ", " + "max=" + max + ", " + "average=" + average + ", " + "first=" + first + ", " + "last=" + last + ", " + "netChange=" + netChange + ", " + "typeChanges=" + typeChanges + ", " + "rises=" + rises + ", " + "falls=" + falls + ", " + "dataTicks=" + dataTicks + ", " + "noDataTicks=" + noDataTicks + "]";
        }


        public int totalTicks() {
            return dataTicks + noDataTicks;
        }

        /** 有没有"读到值"的 tick */
        public boolean hasData() {
            return dataTicks > 0;
        }

        public static final Summary EMPTY =
                new Summary("-", Val.ZERO, Val.ZERO, Val.ZERO, Val.ZERO, Val.ZERO, Val.ZERO,
                        0, 0, 0, 0, 0);
    }
}
