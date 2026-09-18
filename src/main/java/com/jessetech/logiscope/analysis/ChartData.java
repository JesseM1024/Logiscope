package com.jessetech.logiscope.analysis;

import com.jessetech.logiscope.store.Sample;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析层：把「扁平的时间序列」整理成「按物品分组的折线」，供界面画图。
 *
 * ★ 为什么存储层保持扁平、分组只在这里做 ★
 * 直觉上"出现新物品就新建一个数组"很自然，但那样会带来三个麻烦：
 *   1. 环形缓冲写满时，要跨 N 个数组协调"该丢哪一段最旧的"，很容易写错；
 *   2. "清空"要遍历所有数组；"某一刻这一格里到底是什么"反而要反查；
 *   3. 多条数组之间没有统一的时间轴，画图时还得先做一次归并。
 * 所以这里选了另一条路：**扁平的环形缓冲仍是唯一真相**，
 * 按物品分组的 Series 是它的**派生视图**，每次数据变化重算一次（20 次/秒，O(n)）。
 * 结果是同一件事只写一遍，而且顺序、断档、清空全都自动正确。
 *
 * ★ 断点规则（什么时候必须把线剪断）★
 *   1. 物品换了       —— 从"64 个铁锭"变成"1 个铜锭"，差值 -63 是假的
 *   2. 中间是 no_data —— 那段时间我们没观测到，连线等于编造数据
 *   3. tick 不连续    —— 采样停了（暂停 / 状态切换），同样不能连
 * 三种情况统一落到 SeriesPoint#breakBefore 一个标记上，渲染层只管照着断开。
 *
 * ★ 为什么要有 version ★
 *   界面每帧都要这份数据。带上 version 后界面可以"数据没变就不重算"，
 *   把 60 次/秒 的全量遍历降到 20 次/秒（每次数据真的变化才发生一次）。
 *   纯函数 + 显式版本号，比在界面里做增量更新更容易证明是对的。
 *
 * @param series      按物品"首次出现顺序"排列 —— 顺序稳定，界面才能给每个物品固定的颜色
 * @param firstTick   时间轴起点（含 no_data 样本）
 * @param lastTick    时间轴终点（含 no_data 样本）
 * @param minValue    全体有数据样本的最小值
 * @param maxValue    全体有数据样本的最大值
 * @param dataCount   有数据的样本数
 * @param noDataCount 无数据的样本数
 * @param switches    物品类型切换次数（= 断点里"因为换物品"的那一类）
 * @param version     对应的 SampleStore 版本号
 */
public record ChartData(
        List<Series> series,
        long firstTick,
        long lastTick,
        int minValue,
        int maxValue,
        int dataCount,
        int noDataCount,
        int switches,
        long version) {

    public static final ChartData EMPTY =
            new ChartData(List.of(), 0L, 0L, 0, 0, 0, 0, 0, Long.MIN_VALUE);

    /**
     * 相邻两个样本的 tick 间隔超过它就算断档。
     * 正常采样是每 tick 一条，所以间隔只能是 1。
     * （将来若做"每 N tick 采样一次"的降频，这里要跟着改成 N。）
     */
    public static final long CONTINUITY_GAP = 1;

    public static final String EMPTY_ITEM = Sample.EMPTY_ITEM;

    public boolean hasData() {
        return dataCount > 0;
    }

    public boolean isEmpty() {
        return series.isEmpty() && noDataCount == 0;
    }

    /** 时间轴总跨度（tick） */
    public long spanTicks() {
        return Math.max(0L, lastTick - firstTick);
    }

    /** 这个物品在列表里的下标（= 颜色分配依据）；不存在返回 -1 */
    public int indexOf(String itemId) {
        for (int i = 0; i < series.size(); i++) {
            if (series.get(i).itemId().equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    public Series byItem(String itemId) {
        for (Series s : series) {
            if (s.itemId().equals(itemId)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 某个 tick 区间内、每条数据线的统计。给"框选一段区间"用。
     *
     * 语义说明：`first`/`last` 取的是区间内**最早/最晚**的采样值，
     * 中间若夹着别的物品（槽位被清空又装回来），差值仍然按"首尾"算 ——
     * 它表达的是"这段时间里这个物品净变化了多少"，这正是物流分析想要的东西。
     */
    public List<SeriesRange> ranges(long fromTick, long toTick) {
        long lo = Math.min(fromTick, toTick);
        long hi = Math.max(fromTick, toTick);
        List<SeriesRange> out = new ArrayList<>();
        for (Series s : series) {
            int n = 0;
            int first = 0;
            int last = 0;
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (SeriesPoint p : s.points()) {
                if (p.tick() < lo || p.tick() > hi) {
                    continue;
                }
                if (n == 0) {
                    first = p.count();
                }
                last = p.count();
                n++;
                min = Math.min(min, p.count());
                max = Math.max(max, p.count());
            }
            if (n > 0) {
                out.add(new SeriesRange(s.itemId(), n, first, last, min, max));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    public static ChartData build(long version, List<Sample> samples) {
        if (samples.isEmpty()) {
            return new ChartData(List.of(), 0L, 0L, 0, 0, 0, 0, 0, version);
        }

        // LinkedHashMap：series 的顺序 = 物品首次出现的顺序，
        // 于是"第几条线用什么颜色"是稳定的 —— 不能让它随数据增长而洗牌。
        Map<String, Builder> builders = new LinkedHashMap<>();

        long firstTick = samples.get(0).tick();
        long lastTick = samples.get(0).tick();
        int dataCount = 0;
        int noDataCount = 0;
        int switches = 0;
        int minValue = Integer.MAX_VALUE;
        int maxValue = Integer.MIN_VALUE;

        long prevTick = 0;
        // 上一个"有数据"样本的物品。null = 上一个样本是 no_data（或这还是开头）
        String prevItem = null;

        for (Sample s : samples) {
            firstTick = Math.min(firstTick, s.tick());
            lastTick = Math.max(lastTick, s.tick());

            if (s.isNoData()) {
                noDataCount++;
                prevItem = null;      // 断档之后必须重新起一段
                prevTick = s.tick();
                continue;
            }

            boolean typeChanged = prevItem != null && !prevItem.equals(s.itemId());
            boolean gap = prevItem != null && (s.tick() - prevTick) > CONTINUITY_GAP;
            boolean breakBefore = prevItem == null || typeChanged || gap;
            if (typeChanged) {
                switches++;
            }

            Builder b = builders.get(s.itemId());
            if (b == null) {
                b = new Builder(s.itemId());
                builders.put(s.itemId(), b);
            }
            b.points.add(new SeriesPoint(s.tick(), s.count(), breakBefore));
            b.min = Math.min(b.min, s.count());
            b.max = Math.max(b.max, s.count());

            dataCount++;
            minValue = Math.min(minValue, s.count());
            maxValue = Math.max(maxValue, s.count());

            prevItem = s.itemId();
            prevTick = s.tick();
        }

        List<Series> out = new ArrayList<>(builders.size());
        for (Builder b : builders.values()) {
            out.add(new Series(b.itemId, List.copyOf(b.points), b.min, b.max));
        }

        if (dataCount == 0) {
            minValue = 0;
            maxValue = 0;
        }

        return new ChartData(
                List.copyOf(out),
                firstTick,
                lastTick,
                minValue,
                maxValue,
                dataCount,
                noDataCount,
                switches,
                version);
    }

    /** 构建期的可变累加器 */
    private static final class Builder {
        final String itemId;
        final List<SeriesPoint> points = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        Builder(String itemId) {
            this.itemId = itemId;
        }
    }

    /**
     * 框选区间内某条数据线的统计。
     *
     * @param samples 区间内这条线的采样点数
     * @param first   区间内最早的值
     * @param last    区间内最晚的值
     */
    public record SeriesRange(String itemId, int samples, int first, int last, int min, int max) {

        /** 净变化 */
        public int delta() {
            return last - first;
        }
    }
}
