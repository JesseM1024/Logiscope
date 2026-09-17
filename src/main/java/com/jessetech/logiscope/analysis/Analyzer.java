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
     */
    public static List<Delta> deltas(List<Sample> samples) {
        List<Delta> out = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            Sample prev = samples.get(i - 1);
            Sample cur = samples.get(i);
            boolean typeChanged = !prev.itemId().equals(cur.itemId());
            out.add(new Delta(cur.tick(), typeChanged ? 0 : cur.count() - prev.count(), typeChanged));
        }
        return out;
    }

    /** 统计摘要，给界面上的数字面板用 */
    public static Summary summarize(List<Sample> samples) {
        if (samples.isEmpty()) {
            return Summary.EMPTY;
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        int typeChanges = 0;
        int rises = 0;
        int falls = 0;

        String dominant = samples.get(0).itemId();
        int dominantStreak = 0;
        int streak = 0;
        String prevItem = null;

        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            min = Math.min(min, s.count());
            max = Math.max(max, s.count());
            sum += s.count();

            if (prevItem != null) {
                if (!prevItem.equals(s.itemId())) {
                    typeChanges++;
                    streak = 0;
                }
                int d = s.count() - samples.get(i - 1).count();
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
            prevItem = s.itemId();
        }

        Sample first = samples.get(0);
        Sample last = samples.get(samples.size() - 1);

        return new Summary(
                dominant,
                min,
                max,
                (int) (sum / samples.size()),
                first.count(),
                last.count(),
                last.count() - first.count(),
                typeChanges,
                rises,
                falls,
                samples.size());
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
            int sampleCount) {

        public static final Summary EMPTY =
                new Summary("-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
