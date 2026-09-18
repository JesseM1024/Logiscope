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
 *   规则现在是**每条线各判各的**（per-series），因为一个 tick 会有多条样本
 *   —— 多格监控时，每个出现的物品各一条（见 {@code analysis/Aggregator}）：
 *
 *   | 情况 | 为什么必须断 |
 *   |---|---|
 *   | 这条线自己出现空档（间隔 > {@link #CONTINUITY_GAP}） | 中间那几 tick 没观测到它（`no_data`，或者它已经不在被监控的格子里了）。**把两点连起来等于编造数据** |
 *   | 这条线的第一个点 | 它前面没东西可连（契约：首点一定是"段起点"，无害） |
 *
 *   ★ 第十轮的关键修改：不再用"全局上一个样本"判连续性 ★
 *   第一版用的是"上一个样本的物品变了就断开"。单格时代没问题（一个 tick 只有一个物品），
 *   但多格之后**同一 tick 有多条不同物品的样本** —— 用全局 prev 判的话第二条样本
 *   必然被判成"物品切换" → **每条线在每一个 tick 都断开** → 波形碎成一堆孤立点。
 *   改成 per-series 之后，"物品换了"这件事天然由"它属于哪条线"解决，
 *   断点只剩"时间上不连续"一个原因 —— 比原来更简单，也更准。
 *
 *   顺带一个好性质：`no_data` 不需要任何特殊处理了 —— 它只要让那条线少一个点，
 *   下一点自然就因为超大间隔被判成断点。
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
 * @param dataCount   有数据的**样本条数**（多格多物品时一个 tick 可能好几条）
 * @param noDataCount 无数据的样本数（一个 tick 最多一条）
 * @param switches    物品**集合**发生变化的次数（按 tick 比集合，不是比单个样本）
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
     * 相邻两条样本的 tick 间隔超过它就算断档。
     * 正常采样是每世界 tick 一条，所以同一条线上相邻两点只可能差 1。
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
        int minValue = Integer.MAX_VALUE;
        int maxValue = Integer.MIN_VALUE;

        for (Sample s : samples) {
            firstTick = Math.min(firstTick, s.tick());
            lastTick = Math.max(lastTick, s.tick());

            if (s.isNoData()) {
                // no_data 不需要任何特殊处理：它只要让每条线都少一个点，
                // 下一点自然因为"间隔超过 CONTINUITY_GAP"被判成断点。
                noDataCount++;
                continue;
            }

            Builder b = builders.get(s.itemId());
            if (b == null) {
                b = new Builder(s.itemId());
                builders.put(s.itemId(), b);
            }

            // ★ 断点只看**这条线自己**的上一个点 ★
            // 首点（lastTick 还是初值）也算"段起点"—— 这是老契约：
            // 它前面没东西可连，渲染层靠 colStart 决定"要不要连到左邻列"，
            // 而首列本来就没有左邻列，所以标记为 true 无害。
            boolean gap = b.lastTick == Long.MIN_VALUE
                    || (s.tick() - b.lastTick) > CONTINUITY_GAP;
            b.points.add(new SeriesPoint(s.tick(), s.count(), gap));
            b.lastTick = s.tick();
            b.min = Math.min(b.min, s.count());
            b.max = Math.max(b.max, s.count());

            dataCount++;
            minValue = Math.min(minValue, s.count());
            maxValue = Math.max(maxValue, s.count());
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
                countItemSetSwitches(samples),
                version);
    }

    /**
     * 物品**集合**发生变化的次数（面板上的 `switch N`）。
     *
     * 为什么不是"相邻两个样本的物品不同就算一次"：多格监控下一个 tick 本来就有
     * 多条不同物品的样本，那样算出来的是"每 tick 都切换"，数字虚高且没有意义。
     * 用户想知道的是"这几格里装的东西变了几次"，所以按 tick 归组成集合再比。
     *
     * 代价：一次额外的 O(n) 扫描（只在 rebuild 时跑，最多 20 次/秒）。
     * 刻意用两个复用的 List 而不是 HashSet —— k（一个 tick 里的物品种数）很小，
     * 线性比较的常数比建哈希表小，也不产生每 tick 的分配。
     */
    public static int countItemSetSwitches(List<Sample> samples) {
        int switches = 0;
        List<String> prev = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        boolean hasPrev = false;
        int i = 0;
        int n = samples.size();
        while (i < n) {
            Sample s = samples.get(i);
            if (s.isNoData()) {
                // 断档不算"切换"（与旧行为一致）：它只是没观测到
                hasPrev = false;
                prev.clear();
                i++;
                continue;
            }
            long t = s.tick();
            cur.clear();
            while (i < n && !samples.get(i).isNoData() && samples.get(i).tick() == t) {
                cur.add(samples.get(i).itemId());
                i++;
            }
            if (hasPrev && !sameSet(prev, cur)) {
                switches++;
            }
            prev.clear();
            prev.addAll(cur);
            hasPrev = true;
        }
        return switches;
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (String s : a) {
            if (!b.contains(s)) {
                return false;
            }
        }
        return true;
    }

    /** 构建期的可变累加器 */
    private static final class Builder {
        final String itemId;
        final List<SeriesPoint> points = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        /** 这条线自己的上一个采样 tick；用来判"这条线是否连续"（见 build 的注释） */
        long lastTick = Long.MIN_VALUE;

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
