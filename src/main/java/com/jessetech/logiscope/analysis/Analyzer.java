package com.jessetech.logiscope.analysis;

import com.jessetech.logiscope.store.Sample;

import java.util.ArrayList;
import java.util.List;

/**
 * 分析层：纯函数，输入原始序列、输出派生序列。
 *
 * 刻意不引用任何游戏类，也不持有状态 ——
 * 这样它可以被单元测试直接喂造好的数据，也能在导出后离线复用。
 *
 * ★ no_data 样本的处理原则 ★
 * "没读到值"和"格子里是 0 个"是两件完全不同的事，绝不能混进统计：
 *   · 求和 / 极值 / 平均：跳过 no_data
 *   · 首值 / 末值 / 净变化：取最近的两个**有数据**样本
 *   · 类型切换：no_data 不算一次切换，它只是断档
 * 图上的处理是"断开"，见 ScopeScreen#renderSparkline。
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
     */
    public static List<Delta> deltas(List<Sample> samples) {
        List<Delta> out = new ArrayList<>();
        Sample prev = null;
        for (Sample cur : samples) {
            if (cur.isNoData()) {
                continue;
            }
            if (prev != null) {
                boolean typeChanged = !prev.itemId().equals(cur.itemId());
                out.add(new Delta(cur.tick(), typeChanged ? 0 : cur.count() - prev.count(), typeChanged));
            }
            prev = cur;
        }
        return out;
    }

    /** 统计摘要，给界面上的数字面板用（跳过 no_data） */
    public static Summary summarize(List<Sample> samples) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        int dataCount = 0;
        int noDataCount = 0;
        int typeChanges = 0;
        int rises = 0;
        int falls = 0;

        String dominant = "-";
        int dominantStreak = 0;
        int streak = 0;
        Sample prev = null;
        Sample first = null;
        Sample last = null;

        for (Sample s : samples) {
            if (s.isNoData()) {
                noDataCount++;
                continue;
            }

            dataCount++;
            last = s;
            if (first == null) {
                first = s;
            }

            min = Math.min(min, s.count());
            max = Math.max(max, s.count());
            sum += s.count();

            if (prev != null) {
                if (!prev.itemId().equals(s.itemId())) {
                    typeChanges++;
                    streak = 0;
                }
                int d = s.count() - prev.count();
                if (d > 0) {
                    rises++;
                } else if (d < 0) {
                    falls++;
                }
            }

            streak++;
            if (streak > dominantStreak) {
                dominantStreak = streak;
                dominant = s.itemId();
            }
            prev = s;
        }

        if (dataCount == 0) {
            return new Summary("-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, noDataCount);
        }

        return new Summary(
                dominant,
                min,
                max,
                (int) (sum / dataCount),
                first.count(),
                last.count(),
                last.count() - first.count(),
                typeChanges,
                rises,
                falls,
                dataCount,
                noDataCount);
    }

    /** 单点变化量 */
    public record Delta(long tick, int delta, boolean typeChanged) {
    }

    /** 统计摘要 */
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
            int dataCount,
            int noDataCount) {

        public int totalCount() {
            return dataCount + noDataCount;
        }

        /** 有没有"读到值"的样本 */
        public boolean hasData() {
            return dataCount > 0;
        }

        public static final Summary EMPTY =
                new Summary("-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
