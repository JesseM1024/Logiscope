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
 * ★★ 断点规则（什么时候必须把线剪断）★★
 *
 * 规则一：**只有"这条线自己不连续"才剪**（per-series）。
 * 一个 tick 可以有多条样本（多格监控时每个出现的物品各一条，见 {@code analysis/Aggregator}），
 * 所以不能用"全局上一个样本"判连续性 —— 那样第二条样本必然被判成"换了物品"，
 * 波形会碎成一堆孤立点。
 *
 * 规则二：**"不连续"由两件事定义，都与"采样间隔是多少"无关**：
 *
 * | 情况 | 为什么必须断 |
 * |---|---|
 * | 中间夹了标记（`no_data` = 读了读不到，`gap` = 没在记录） | 那段区间我们**没观测**，把两点连起来等于编造数据 |
 * | 这条线的上一个点**不在上一个数据 tick 上** | 那个 tick 里被监控的格子没有这个物品（被抽空了 / 换成了别的），再连上就是假的连续 |
 *
 * 规则三：还有一个**静默大空档**兜底（{@link #MAX_SILENT_GAP}）：
 * 万一有一条没写标记的停摆路径，超过它就直接剪断，不让线横穿一大段空白。
 *
 * ★ 为什么不再用"tick 间隔 > 1 就断"（第十轮第三次修复推翻的旧规则）★
 *   旧规则是"正常的采样是一 tick 一条，所以间隔只可能是 1"。它在**客户端 tick 与服务端
 *   tick 锁相**的年代成立（时间戳取自客户端自己的计数器，恒定 +1），但第八轮把时间戳
 *   换成**服务端权威 gameTime** 之后就不成立了：
 *
 *   客户端每秒最多观测 20 次，服务端也每秒走 20 tick，两者**不锁相**。一次帧抖动
 *   （GC、区块生成、卡顿）就会让某个客户端 tick 看到 ±0 或 +2 的世界 tick。客户端
 *   看见 0 时按"世界没走 tick 就不记"跳过，于是**下一个样本的 tick 自然跳 2** ——
 *   这只是"少观测了一个 tick"，数据本身两端都真的、也没断，但旧规则会在这里剪一刀。
 *   实测现象就是 20t 正常流速下**偶发的断线**（第九轮之前从没出现过，因为那时时钟
 *   恰好恒 +1），而真正的停顿/读取失败**本来就有显式标记**，用不着靠数间隔去猜。
 *
 *   换到高流速（`/tick rate 200`）就更明显：样本天然每 ~10 tick 一个，旧规则会把
 *   **每一个点**都判成断点。新规则下它只是一条采样稀疏的连续折线。
 *
 * ★ 为什么要有 version ★
 *   界面每帧都要这份数据。带上 version 后界面可以"数据没变就不重算"，
 *   把 60 次/秒 的全量遍历降到 20 次/秒（每次数据真的变化才发生一次）。
 *   纯函数 + 显式版本号，比在界面里做增量更新更容易证明是对的。
 *
 * @param series      按物品"首次出现顺序"排列 —— 顺序稳定，界面才能给每个物品固定的颜色
 * @param firstTick   时间轴起点（含标记样本）
 * @param lastTick    时间轴终点（含标记样本）
 * @param minValue    全体有数据样本的最小值
 * @param maxValue    全体有数据样本的最大值
 * @param dataCount   有数据的**样本条数**（多格多物品时一个 tick 可能好几条）
 * @param noDataCount 无数据的样本数（一个 tick 最多一条；中断标记不计入）
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
     * 静默大空档兜底（tick）：中间隔了这么多还没断，就直接剪断。
     *
     * 正常情况**用不到它** —— 停顿都有显式标记（见 {@link Sample#gap}）。
     * 它存在只为兜住"没写标记就停摆了"这种 unforeseen 路径，
     * 所以阈值刻意给得很大（1 秒 = 20 tick）：观测抖动最多漏一两 tick，碰不到它。
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
                // 注意 interrupted 会一直挂到下一个数据 tick —— 中间不管隔多少个标记都一样断。
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
                    b = new Builder(c.itemId());
                    builders.put(c.itemId(), b);
                }

                // ★ 断点只看**这条线自己**的上一个点 ★
                // 1. 中间有标记（读了读不到 / 没在记录）→ 断
                // 2. 这是这条线的第一个点（契约：首点永远是"段起点"，渲染层靠它
                //    决定"要不要连到左邻列"，而首列本来没有左邻列，所以标记为 true 无害）
                // 3. 上一个**数据 tick** 里没有它 → 中间它被抽空了 / 换成了别的 → 断
                // 4. 静默大空档兜底（正常路径用不到，见 MAX_SILENT_GAP）
                boolean gap = interrupted
                        || !hasPrevDataTick
                        || b.lastTick != prevDataTick
                        || (t - b.lastTick) > MAX_SILENT_GAP;
                b.points.add(new SeriesPoint(t, c.count(), gap));
                b.lastTick = t;
                b.min = Math.min(b.min, c.count());
                b.max = Math.max(b.max, c.count());

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
     * 标记（no_data / gap）不算切换，它只是"没观测到"，切换链条到这里断掉重算。
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
            if (s.isMarker()) {
                // 断档不算"切换"：它只是没观测到
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
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        /** 这条线自己上一次出现的 tick；用来判"它在上一个数据 tick 里在不在" */
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
