package com.jessetech.logiscope.analysis;

import com.jessetech.logiscope.store.Sample;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分析层：把「扁平的时间序列」整理成「按物品分组的折线」，供界面画图。
 *
 * ★ 为什么存储层保持扁平、分组只在这里做 ★
 * 直觉上"出现新物品就新建一个数组"很自然，但那样会带来三个麻烦：
 *   1. 环形缓冲写满时，要跨 N 个数组协调"该丢哪一段最旧的"，很容易写错；
 *   2. "清空"要遍历所有数组；"某一刻这一格里到底是什么"反而要反查；
 *   3. 多条数组之间没有统一的时间轴，画图时还得先做一次归并。
 * 所以这里选了另一条路：**扁平的环形缓冲仍是唯一真相**，
 * 按物品分组的 Series 是它的**派生视图**，每次数据变化重算一次（O(n)）。
 *
 * ★★ 每条线有两层：存量 + 单 tick 流量 ★★
 *
 * | 层 | 怎么来的 | 用来回答 |
 * |---|---|---|
 * | 存量 `points` | 直接来自采样 | 现在有多少 |
 * | 流量 `flow` | 本 tick 存量 − 上一 tick 存量 | 现在进/出多快 |
 *
 * 流量是**算出来的**，不是另采一份数据 —— 所以它和存量天然对齐，
 * 也不会多占缓冲空间。只在**相邻两格**之间算：段起点、中间夹过标记、
 * 这个物品上一格不在被监控的格子里、上一格干脆没观测到（跨格）——
 * 这些位置的变化归不到某一格上，硬写成 `n/t` 就是假的（比如从"64 铁"跳到"1 铜"的 −63），
 * 宁可让那一段的 IO 空着。
 *
 * ★★ 断点规则（什么时候必须把线剪断）★★
 *
 * 规则一：**只有"这条线自己不连续"才剪**（per-series）。
 * 一个 tick 可以有多条样本（多格多物品时每个物品一条），所以不能用"全局上一个样本"判连续性。
 *
 * 规则二：**"不连续"由两件事定义，都与"采样间隔是多少"无关**：
 *
 * | 情况 | 为什么必须断 |
 * |---|---|
 * | 中间夹了标记（`no_data` = 读了读不到，`gap` = 没在记录） | 那段区间我们**没观测**，把两点连起来等于编造数据 |
 * | 这条线的上一个点**不在上一个数据 tick 上** | 那个 tick 里被监控的格子没有这个物品，再连上就是假的连续 |
 *
 * 规则三：还有一个**静默大空档**兜底（{@link #MAX_SILENT_GAP}）。
 *
 * > 为什么不用"tick 间隔 > 1 就断"：时间戳取自服务端权威 `gameTime`（CP15），
 * > 而客户端的观测节拍与服务端 tick 不锁相，偶尔会漏观测一格 ——
 * > 那只是"采样稀疏"，不是数据断裂。（第十轮第四次修复把采样点搬到了世界 tick 上，
 * > 主路径已经不漏格；这条规则仍然兜着回退路径。）
 *
 * ★ 物品种类上限 ★
 * `maxItemKinds` 限的是**画出来的种类数**（每个种类一条存量线 + 一条流量线）。
 * 超出的种类**照样进缓冲、照样进统计**（`dataCount` / `minValue` / `maxValue` 都算它），
 * 只是不建线 —— 于是不会被一个"轮换了上千种物品"的槽位拖垮图例，
 * 面板上会显示 `+N 未显示`，用户知道有多少没画。
 *
 * @param series       按物品"首次出现顺序"排列 —— 顺序稳定，界面才能给每个物品固定的颜色
 * @param firstTick    时间轴起点（含标记样本）
 * @param lastTick     时间轴终点（含标记样本）
 * @param minValue     全体有数据样本的最小值（含未画线的种类）
 * @param maxValue     全体有数据样本的最大值（含未画线的种类）
 * @param dataCount    有数据的**样本条数**（多格多物品时一个 tick 可能好几条）
 * @param noDataCount  无数据的样本数（一个 tick 最多一条；中断标记不计入）
 * @param switches     物品**集合**发生变化的次数（按 tick 比集合）
 * @param droppedKinds 因为超过 `maxItemKinds` 而没有画线的物品种类数
 * @param version      对应的 SampleStore 版本号
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
        int droppedKinds,
        long version) {

    public static final ChartData EMPTY =
            new ChartData(List.of(), 0L, 0L, 0, 0, 0, 0, 0, 0, Long.MIN_VALUE);

    /**
     * 静默大空档兜底（tick）：中间隔了这么多还没断，就直接剪断。
     *
     * 正常情况**用不到它** —— 停顿都有显式标记（见 {@link Sample#gap}）。
     * 它存在只为兜住"没写标记就停摆了"这种 unforeseen 路径，
     * 所以阈值刻意给得很大（1 秒 = 20 tick）。
     */
    public static final long MAX_SILENT_GAP = 20;

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
     * 它表达的是"这段时间里这个物品净变化了多少"。
     *
     * 流量那三个字段是区间内**逐 tick 净变化**的合计：
     * `inflow` 只累加正的、`outflow` 只累加负的 ——
     * 两者在"进和出都很快"的区间里能给出比净变化丰富得多的信息。
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
            if (n == 0) {
                continue;
            }
            int flowSamples = 0;
            int inflow = 0;
            int outflow = 0;
            for (SeriesPoint p : s.flow()) {
                if (p.tick() < lo || p.tick() > hi) {
                    continue;
                }
                flowSamples++;
                if (p.count() > 0) {
                    inflow += p.count();
                } else {
                    outflow += p.count();
                }
            }
            out.add(new SeriesRange(s.itemId(), n, first, last, min, max,
                    flowSamples, inflow, outflow));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /** 不限物品种类数（测试与离线分析用；界面走带配置值的那条）。 */
    public static ChartData build(long version, List<Sample> samples) {
        return build(version, samples, Integer.MAX_VALUE);
    }

    public static ChartData build(long version, List<Sample> samples, int maxItemKinds) {
        int kinds = Math.max(1, maxItemKinds);
        if (samples.isEmpty()) {
            return new ChartData(List.of(), 0L, 0L, 0, 0, 0, 0, 0, 0, version);
        }

        // LinkedHashMap：series 的顺序 = 物品首次出现的顺序，
        // 于是"第几条线用什么颜色"是稳定的 —— 不能让它随数据增长而洗牌。
        Map<String, Builder> builders = new LinkedHashMap<>();
        Set<String> dropped = new HashSet<>();

        long firstTick = samples.get(0).tick();
        long lastTick = samples.get(0).tick();
        int dataCount = 0;
        int noDataCount = 0;
        int minValue = Integer.MAX_VALUE;
        int maxValue = Integer.MIN_VALUE;

        // 上一个**数据 tick**（有内容的那个 tick）与"是否见过"
        long prevDataTick = 0L;
        boolean hasPrevDataTick = false;
        // 自上一个数据 tick 以来见过标记（no_data / gap）没有
        boolean interrupted = false;

        int i = 0;
        int n = samples.size();
        while (i < n) {
            Sample s = samples.get(i);
            firstTick = Math.min(firstTick, s.tick());
            lastTick = Math.max(lastTick, s.tick());

            if (s.isMarker()) {
                // 标记：不产出点，只把"这里断了"记下来，交给下一个数据 tick 消化。
                if (s.isNoData()) {
                    noDataCount++;
                }
                interrupted = true;
                i++;
                continue;
            }

            // ---- 把同一个 tick 的所有条目一起处理 ----
            long t = s.tick();
            int j = i;
            while (j < n && !samples.get(j).isMarker() && samples.get(j).tick() == t) {
                j++;
            }

            for (int k = i; k < j; k++) {
                Sample c = samples.get(k);

                Builder b = builders.get(c.itemId());
                if (b == null) {
                    if (builders.size() >= kinds) {
                        // 超过种类上限：照样进统计，只是不画线
                        dropped.add(c.itemId());
                    } else {
                        b = new Builder(c.itemId());
                        builders.put(c.itemId(), b);
                    }
                }

                if (b != null) {
                    // ★ 断点只看**这条线自己**的上一个点 ★
                    // 1. 中间有标记（读了读不到 / 没在记录）→ 断
                    // 2. 这是这条线的第一个点（契约：首点永远是"段起点"）
                    // 3. 上一个**数据 tick** 里没有它 → 中间它被抽空了 / 换成了别的 → 断
                    // 4. 静默大空档兜底（正常路径用不到，见 MAX_SILENT_GAP）
                    boolean gap = interrupted
                            || !hasPrevDataTick
                            || b.lastTick != prevDataTick
                            || (t - b.lastTick) > MAX_SILENT_GAP;
                    b.points.add(new SeriesPoint(t, c.count(), gap));

                    if (!gap && b.lastTick == t - 1) {
                        // **紧挨着上一格**才算流量：跨格（中间有一格没观测到）的变化
                        // 归不到哪一格上，硬写成 "n/t" 是假的 —— 宁可这一段 IO 空着
                        int delta = c.count() - b.lastCount;
                        b.flow.add(new SeriesPoint(t, delta, b.flowNeedBreak));
                        b.flowNeedBreak = false;
                        b.flowMin = Math.min(b.flowMin, delta);
                        b.flowMax = Math.max(b.flowMax, delta);
                    } else {
                        // 断开了：下一段 IO 的第一个点要标成段起点
                        b.flowNeedBreak = true;
                    }

                    b.lastTick = t;
                    b.lastCount = c.count();
                    b.min = Math.min(b.min, c.count());
                    b.max = Math.max(b.max, c.count());
                }

                dataCount++;
                minValue = Math.min(minValue, c.count());
                maxValue = Math.max(maxValue, c.count());
            }

            prevDataTick = t;
            hasPrevDataTick = true;
            interrupted = false;
            i = j;
        }

        List<Series> out = new ArrayList<>(builders.size());
        for (Builder b : builders.values()) {
            out.add(new Series(b.itemId, List.copyOf(b.points), List.copyOf(b.flow),
                    b.min, b.max, b.flowMin, b.flowMax));
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
                dropped.size(),
                version);
    }

    /**
     * 物品**集合**发生变化的次数（面板上的 `switch N`）。
     *
     * 为什么不是"相邻两个样本的物品不同就算一次"：多格监控下一个 tick 本来就有
     * 多条不同物品的样本，那样算出来的是"每 tick 都切换"，数字虚高且没有意义。
     * 用户想知道的是"这几格里装的东西变了几次"，所以按 tick 归组成集合再比。
     * 标记（no_data / gap）不算切换，它只是"没观测到"，切换链条到这里断掉重算。
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
            if (s.isMarker()) {
                hasPrev = false;
                prev.clear();
                i++;
                continue;
            }
            long t = s.tick();
            cur.clear();
            while (i < n && !samples.get(i).isMarker() && samples.get(i).tick() == t) {
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
        final List<SeriesPoint> flow = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int flowMin = 0;
        int flowMax = 0;
        /** 这条线自己上一次出现的 tick 与数量（判连续性 + 算流量） */
        long lastTick = Long.MIN_VALUE;
        int lastCount;
        /** 下一段流量的第一个点要不要标成段起点 */
        boolean flowNeedBreak;

        Builder(String itemId) {
            this.itemId = itemId;
        }
    }

    /**
     * 框选区间内某条数据线的统计。
     *
     * @param samples     区间内这条线的**存量**采样点数
     * @param first       区间内最早的存量值
     * @param last        区间内最晚的存量值
     * @param flowSamples 区间内可算流量的 tick 数（比采样点数少，段起点不算）
     * @param inflow      区间内**流入**合计（只累加正的净变化，≥ 0）
     * @param outflow     区间内**流出**合计（只累加负的净变化，≤ 0）
     */
    public record SeriesRange(String itemId, int samples, int first, int last, int min, int max,
                              int flowSamples, int inflow, int outflow) {

        /** 净变化（存量首尾之差，与 `inflow + outflow` 在无断档时相等） */
        public int delta() {
            return last - first;
        }
    }
}
