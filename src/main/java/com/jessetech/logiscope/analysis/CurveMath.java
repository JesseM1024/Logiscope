package com.jessetech.logiscope.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 曲线段的数学计算（第十一轮②）。**纯逻辑层** —— 输入 {@link SeriesPoint} 列表，
 * 输出不可变结果，不引用任何游戏类，离线断言直接覆盖（TestChart t25）。
 *
 * 【调用约定】传入的列表已经裁到框选区间内（首尾都在区间里，见
 * {@code ScopeScreen#inRange}）；"要不要把边界外的一格算进来"是界面层的语义决定，
 * 不在这里做（IO 的段内口径见下）。
 *
 * 【六种运算】
 *
 * | 运算 | 输入回答 | 备注 |
 * |---|---|---|
 * | 极值 | min / max（含所在 tick） | 值相等时取**先出现**的那个 tick |
 * | 简单统计 | mean / med / var | var = **总体方差**（除以 n）——样本就是区间内的全部观测 |
 * | 变化总量 | 上升 Σ / 下降 Σ（net = Δ） | 只计**相邻两次采样之间**的变化；断档（breakBefore）不计，缺口数单独报出 |
 * | 变更区间 | 变更次数 / 变化量 mean·var / 周期 mean·var | 变化量为 0 不算"变更"；周期 = 相邻两次变更的 tick 间隔 |
 * | 上升事件 | 同"变更区间"，只收 Δ > 0 | 断档不计；周期 = 相邻两次**上升**的 tick 间隔 |
 * | 下降事件 | 同"变更区间"，只收 Δ < 0 | 断档不计；周期 = 相邻两次**下降**的 tick 间隔 |
 *
 * 【数量线 vs IO 线】
 *   · 数量线：变化 = 相邻两个采样值之差，且必须 {@code !breakBefore()}
 *     （连续性规则与 {@code ChartData#build} 的流量计算同源 —— 断档处硬算差值就是编数据）。
 *   · IO 线：**值本身就是变化量**（flow(t) = 存量(t) − 存量(t−1)），所以
 *     上升/下降 = 对**全部**流量值求和（每个流量点都只在"相邻两格都观测到"时才产生），
 *     变更事件 = 非 0 的流量点；缺口数 = **真的少了几笔 Δ**（段起点之后每一条断线算一次），
 *     **不拿 {@code breakBefore} 当"值不可信"** —— 那是渲染要的断线标记，见 {@link #flowTotal}。
 *     ★ 这一条是第十六轮⑤修的：旧实现把渲染标记当数据语义，于是每个数据段都吞掉一笔真实流量、
 *     还谎报一次断档（实测：存量 10→14→12 时 IO 线报 `上升=0 断档=1`）。★
 *
 * 【为什么 ±Σ = Δ 会与框选统计的 Δ 对不上】
 *   断档处的变化没有留在任何一次观测里（CP18 的老边界）——所以它**本来就不该**
 *   被算进任何一栏。缺口多时 Σ升+Σ降 ≠ Δ，结果框会把缺口数报出来，
 *   而不是悄悄让它对上。
 */
public final class CurveMath {

    private CurveMath() {
    }

    // ------------------------------------------------------------------
    // 极值
    // ------------------------------------------------------------------

    public record Extremes(Val min, long minTick, Val max, long maxTick) {
    }

    public static Extremes extremes(List<SeriesPoint> pts) {
        Val min = null;
        long minT = Long.MIN_VALUE;
        Val max = null;
        long maxT = Long.MIN_VALUE;
        for (SeriesPoint p : pts) {
            if (min == null || p.value().compareTo(min) < 0) {
                min = p.value();
                minT = p.tick();
            }
            if (max == null || p.value().compareTo(max) > 0) {
                max = p.value();
                maxT = p.tick();
            }
        }
        return new Extremes(min == null ? Val.ZERO : min, minT,
                max == null ? Val.ZERO : max, maxT);
    }

    // ------------------------------------------------------------------
    // 简单统计
    // ------------------------------------------------------------------

    /** @param var 总体方差（除以 n；单一采样时为 0） */
    public record Stats(double mean, double med, double var) {
    }

    public static Stats stats(List<SeriesPoint> pts) {
        int n = pts.size();
        double[] vals = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            vals[i] = pts.get(i).value().asDouble();
            sum += vals[i];
        }
        double mean = sum / n;
        double[] sorted = vals.clone();
        Arrays.sort(sorted);
        double med = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        double ss = 0;
        for (double v : vals) {
            double d = v - mean;
            ss += d * d;
        }
        return new Stats(mean, med, n > 0 ? ss / n : 0);
    }

    // ------------------------------------------------------------------
    // 变化总量
    // ------------------------------------------------------------------

    /**
     * @param rise 相邻采样之间的上升合计（≥ 0）
     * @param fall 下降合计（≤ 0）
     * @param gaps 断档处数（那里的变化没有留在观测里，不计入）
     */
    public record TotalChange(Val rise, Val fall, int gaps) {

        /** net = rise + fall（无断档时 == 区间 Δ） */
        public Val net() {
            return rise.plus(fall);
        }
    }

    /** 数量线：变化 = 相邻采样值之差（断档不计） */
    public static TotalChange totalChange(List<SeriesPoint> pts) {
        Val rise = Val.ZERO;
        Val fall = Val.ZERO;
        int gaps = 0;
        for (int i = 1; i < pts.size(); i++) {
            SeriesPoint prev = pts.get(i - 1);
            SeriesPoint cur = pts.get(i);
            if (cur.breakBefore()) {
                gaps++;
                continue;
            }
            Val d = cur.value().minus(prev.value());
            if (d.compareTo(Val.ZERO) > 0) {
                rise = rise.plus(d);
            } else if (d.compareTo(Val.ZERO) < 0) {
                fall = fall.plus(d);
            }
        }
        return new TotalChange(rise, fall, gaps);
    }

    /**
     * IO 线：流量值本身就是变化量。
     *
     * ★ 为什么这里**不**按 {@code breakBefore} 过滤 ★（第十六轮⑤修）
     * {@code ChartData#build} 只在"**相邻两格都观测到**"时才产生流量点
     * （条件就是 {@code !gap && lastTick == t - 1}），所以**每一个流量点的值都成立**。
     * 而它给"段起点之后的第一个流量点"打的 {@code breakBefore} 是**渲染**要的
     * —— 画图时不要把线跨过缺口连起来（呈现层的"断开"）。
     *
     * 旧实现把这两件事混了（拿渲染标记当"值不可信"），实测后果：
     * 存量 10 → 14 → 12（两格都观测到）时，存量线报 `上升=4 断档=0`（对），
     * IO 线却报 `上升=0 断档=1` —— **吞掉一笔真实流入、还编了一个不存在的断档**。
     *
     * 现在缺口数按"**真的少了一笔 Δ**"来数：段起点之后的每一个断线对应一次缺失
     * （首个流量点的断线只是"这条线从这儿开始"，它的值是本区间内的第一笔真实 Δ）。
     */
    public static TotalChange flowTotal(List<SeriesPoint> flow) {
        Val rise = Val.ZERO;
        Val fall = Val.ZERO;
        int gaps = 0;
        for (int i = 0; i < flow.size(); i++) {
            SeriesPoint p = flow.get(i);
            if (i > 0 && p.breakBefore()) {
                gaps++;
            }
            Val v = p.value();
            if (v.compareTo(Val.ZERO) > 0) {
                rise = rise.plus(v);
            } else if (v.compareTo(Val.ZERO) < 0) {
                fall = fall.plus(v);
            }
        }
        return new TotalChange(rise, fall, gaps);
    }

    // ------------------------------------------------------------------
    // 变更区间
    // ------------------------------------------------------------------

    /**
     * 变更区间 / 上升事件 / 下降事件共用的结果。
     * 上升、下降是"过滤后的事件序列"——变化量已经只含同向的那些，
     * 周期也相应变成"相邻两次同向事件"的间隔。
     *
     * @param count 事件次数（变化量 ≠ 0；上升/下降运算里只数符合方向的那些）
     * @param dMean / dVar 变化量的均值 / 总体方差（count == 0 时为 NaN）
     * @param pMean / pVar 周期（相邻两次事件的 tick 间隔）的均值 / 方差（事件 < 2 时为 NaN）
     */
    public record Events(int count, double dMean, double dVar, double pMean, double pVar) {
    }

    /** 数量线：变更事件 = 相邻采样值之差 ≠ 0（断档不计） */
    public static Events changeEvents(List<SeriesPoint> pts) {
        return changeEvents(pts, 0);
    }

    /** 数量线：仅上升事件 = 相邻采样值之差 > 0（断档不计；相等的差值不算事件） */
    public static Events changeRiseEvents(List<SeriesPoint> pts) {
        return changeEvents(pts, 1);
    }

    /** 数量线：仅下降事件 = 相邻采样值之差 < 0（断档不计；相等的差值不算事件） */
    public static Events changeFallEvents(List<SeriesPoint> pts) {
        return changeEvents(pts, -1);
    }

    /**
     * @param sign 0 = 全部变更 / +1 = 仅上升 / −1 = 仅下降
     */
    private static Events changeEvents(List<SeriesPoint> pts, int sign) {
        List<Double> deltas = new ArrayList<>();
        List<Long> ticks = new ArrayList<>();
        for (int i = 1; i < pts.size(); i++) {
            SeriesPoint prev = pts.get(i - 1);
            SeriesPoint cur = pts.get(i);
            if (cur.breakBefore()) {
                continue;
            }
            Val d = cur.value().minus(prev.value());
            if (keepEvent(d.compareTo(Val.ZERO), sign)) {
                deltas.add(d.asDouble());
                ticks.add(cur.tick());
            }
        }
        return events(deltas, ticks);
    }

    /**
     * IO 线：变更事件 = 非 0 的流量点。
     * 同样**不按 {@code breakBefore} 过滤**（理由见 {@link #flowTotal}）——
     * 每个流量点的值都是两个相邻观测之差，段起点那一笔也是真实事件。
     * 变更两侧的 tick 差值（周期）在这里也更有意义：它数的是"真实变化之间隔了几格"。
     */
    public static Events flowEvents(List<SeriesPoint> flow) {
        return flowEvents(flow, 0);
    }

    /** IO 线：仅上升事件 = 流量值 > 0（不按 {@code breakBefore} 过滤，理由同 {@link #flowEvents}） */
    public static Events flowRiseEvents(List<SeriesPoint> flow) {
        return flowEvents(flow, 1);
    }

    /** IO 线：仅下降事件 = 流量值 < 0（不按 {@code breakBefore} 过滤，理由同 {@link #flowEvents}） */
    public static Events flowFallEvents(List<SeriesPoint> flow) {
        return flowEvents(flow, -1);
    }

    /**
     * @param sign 0 = 全部变更 / +1 = 仅上升 / −1 = 仅下降
     */
    private static Events flowEvents(List<SeriesPoint> flow, int sign) {
        List<Double> deltas = new ArrayList<>();
        List<Long> ticks = new ArrayList<>();
        for (SeriesPoint p : flow) {
            Val v = p.value();
            if (keepEvent(v.compareTo(Val.ZERO), sign)) {
                deltas.add(v.asDouble());
                ticks.add(p.tick());
            }
        }
        return events(deltas, ticks);
    }

    /** 事件过滤：Δ = 0 永远不算事件；sign 0 = 全部 / +1 = 只收上升 / −1 = 只收下降 */
    private static boolean keepEvent(int cmp, int sign) {
        if (cmp == 0) {
            return false;
        }
        if (sign > 0) {
            return cmp > 0;
        }
        if (sign < 0) {
            return cmp < 0;
        }
        return true;
    }

    private static Events events(List<Double> deltas, List<Long> ticks) {
        List<Long> periods = new ArrayList<>();
        for (int i = 1; i < ticks.size(); i++) {
            periods.add(ticks.get(i) - ticks.get(i - 1));
        }
        return new Events(deltas.size(), mean(deltas), var(deltas), mean(periods), var(periods));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static double mean(List<? extends Number> xs) {
        if (xs.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (Number x : xs) {
            sum += x.doubleValue();
        }
        return sum / (double) xs.size();
    }

    /** 总体方差（除以 n）；n < 2 时为 0（单一观测没有离散可言，不是 NaN —— 例子：+1,+1 → var=0） */
    private static double var(List<? extends Number> xs) {
        if (xs.size() < 2) {
            return xs.isEmpty() ? Double.NaN : 0;
        }
        double m = mean(xs);
        double ss = 0;
        for (Number x : xs) {
            double d = x.doubleValue() - m;
            ss += d * d;
        }
        return ss / xs.size();
    }
}
