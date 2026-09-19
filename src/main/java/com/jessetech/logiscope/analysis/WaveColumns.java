package com.jessetech.logiscope.analysis;

import java.util.function.DoubleToIntFunction;
import java.util.List;

/**
 * 波形列聚合器（第十三轮）：把一条线的采样点铺成**像素列的竖条矩形**。
 * 纯逻辑（不引用任何游戏类），所以离线就能断言与压测（TestChart t29 / BenchRender）。
 *
 * 【为什么单独成一个类】
 * 这是每帧最热的一段：50 条线 × 700 列。抽出来有三个好处：
 *   1. 渲染器那边只剩"把矩形丢给 GuiGraphics"；
 *   2. 离线能测"聚合成什么样"（尖峰 / 断档 / 稀疏插值都不需要开游戏就能验）；
 *   3. 离线能**压测矩形数量**——帧率的大头就是 fill 次数，这个数字是可提前量的代理指标。
 *
 * 【几何约定】与界面层严格一致（改一处必须两边一起改）：
 *   colOf(tick) = (tick - start) * (plotW - 1) / (end - start)
 *   列 c 的画布 x = plotX + c；竖条 = fill(x, yOf(max), x + 1, yOf(min) + 1)
 *
 * 【语义（都是实机踩出来的，改不得）】
 *   · **同一像素列里的多个采样点取 min/max** —— 尖峰不会被平均掉；
 *   · **断档（{@code breakBefore}）不连线**，线在这里断开；
 *   · **相邻列值不同 → 一条竖直连接线**（台阶数据的跳变）；
 *   · **中间隔着空列 → 逐列线性插值**（采样稀疏时折线不断成孤立点）。
 *
 * 【值是 double（第十六轮⑥）】采样值本身是自适应的（{@link Val}：整数 long 精确 / 小数
 * double），但**画图只需要浮点** —— 这里一律走 {@code value.asDouble()}：
 *   · 列缓冲保持 {@code double[]}，逐列聚合零分配（{@code Val} 是不可变 record，
 *     逐列新建对象就是每帧几百次分配，禁止）；
 *   · 精度损失只影响"画在哪个像素"，不影响读数（读数在界面层从 Val 取精确形式）。
 *
 * 【性能上的两条】见 {@link RectRuns}：游程合并（严格像素等价）+ 只扫"有数据的列"。
 * 列缓冲用**代际标记**复用，不再每次清零满数组。
 */
public final class WaveColumns {

    private double[] colMin = new double[0];
    private double[] colMax = new double[0];
    private double[] colFirst = new double[0];
    private double[] colLast = new double[0];
    private int[] colGen = new int[0];
    private boolean[] colStart = new boolean[0];
    private int gen;

    /** 上一次 emit 的"合并前矩形数"（= 老实现会产生的 fill 次数，供压测对比） */
    private int lastRawRects;
    /** 上一次 emit 里"有采样点的列数" */
    private int lastColumns;
    /** 上一次 emit 里"实际被压成几段连接（跳变次数）" */
    private int lastLinks;

    private void ensure(int n) {
        if (colGen.length >= n) {
            return;
        }
        colMin = new double[n];
        colMax = new double[n];
        colFirst = new double[n];
        colLast = new double[n];
        colGen = new int[n];
        colStart = new boolean[n];
    }

    public int lastRawRects() {
        return lastRawRects;
    }

    public int lastColumns() {
        return lastColumns;
    }

    public int lastLinks() {
        return lastLinks;
    }

    /** 与界面层同源的列映射（注意 plotW 减 1：最后一列落在最右像素上） */
    public static int colOf(long tick, long start, long end, int plotW) {
        long span = Math.max(1L, end - start);
        return (int) ((tick - start) * (long) (plotW - 1) / span);
    }

    /**
     * 把一条线在 [start, end] 窗口内的点铺成矩形。
     *
     * @param yOf  值 → 画布 y（界面层传基于 double 的映射；离线传线性映射即可）
     * @param out  矩形出口（内部已做游程合并）
     * @return 有采样点的列数；0 表示这条线在这个窗口里没有数据（调用方什么都不用画）
     */
    public int emit(List<SeriesPoint> pts, long start, long end, int plotW, int plotX,
                    DoubleToIntFunction yOf, RectRuns out) {
        out.clear();
        lastRawRects = 0;
        lastColumns = 0;
        lastLinks = 0;
        if (plotW <= 0 || pts.isEmpty()) {
            return 0;
        }
        ensure(plotW);
        int g = ++gen;

        int cMin = Integer.MAX_VALUE;
        int cMax = -1;
        for (int i = lowerBound(pts, start); i < pts.size(); i++) {
            SeriesPoint p = pts.get(i);
            if (p.tick() > end) {
                break;
            }
            int c = colOf(p.tick(), start, end, plotW);
            if (c < 0 || c >= plotW) {
                continue;
            }
            double v = p.value().asDouble();
            if (colGen[c] != g) {
                colGen[c] = g;
                colStart[c] = p.breakBefore();
                colMin[c] = v;
                colMax[c] = v;
                colFirst[c] = v;
                colLast[c] = v;
            } else {
                if (v < colMin[c]) {
                    colMin[c] = v;
                }
                if (v > colMax[c]) {
                    colMax[c] = v;
                }
                colLast[c] = v;
            }
            if (c < cMin) {
                cMin = c;
            }
            if (c > cMax) {
                cMax = c;
            }
        }
        if (cMax < 0) {
            return 0;
        }

        int prev = -1;
        for (int c = cMin; c <= cMax; c++) {
            if (colGen[c] != g) {
                continue;
            }
            if (prev >= 0 && !colStart[c]) {
                if (prev == c - 1) {
                    lastLinks++;
                    pushVLine(out, c, colFirst[c], colLast[prev], plotX, yOf);
                } else {
                    double v0 = colLast[prev];
                    double v1 = colFirst[c];
                    int d = c - prev;
                    for (int k = prev + 1; k <= c; k++) {
                        double a = v0 + (v1 - v0) * (double) (k - 1 - prev) / d;
                        double b = v0 + (v1 - v0) * (double) (k - prev) / d;
                        pushVLine(out, k, a, b, plotX, yOf);
                    }
                }
            }
            pushVLine(out, c, colMax[c], colMin[c], plotX, yOf);
            prev = c;
            lastColumns++;
        }
        lastRawRects = out.pushes();
        return lastColumns;
    }

    private static void pushVLine(RectRuns out, int c, double vA, double vB, int plotX,
                                  DoubleToIntFunction yOf) {
        int x = plotX + c;
        out.push(x, x + 1,
                yOf.applyAsInt(Math.max(vA, vB)),
                yOf.applyAsInt(Math.min(vA, vB)) + 1);
    }

    /** 第一个 tick >= 目标 的点下标（点按 tick 升序） */
    private static int lowerBound(List<SeriesPoint> pts, long tick) {
        int lo = 0;
        int hi = pts.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (pts.get(mid).tick() < tick) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
