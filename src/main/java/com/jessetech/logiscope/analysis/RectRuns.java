package com.jessetech.logiscope.analysis;

/**
 * 像素矩形的**游程合并**。纯逻辑，离线可断言（TestChart t29）。
 *
 * 【为什么需要它】
 * 波形是"一列一列竖条"画出来的：一条线每帧最多 <绘图区宽> 列 × 2 个矩形
 * （连接线 + 本列 min/max 竖线）。50 条线 × 700 列 × 2 ≈ **7 万个矩形/帧**，
 * 而 MC 的 `GuiGraphics#fill` 每次都要走一遍矩阵与顶点缓冲 —— 实测就是这个把
 * 帧率压到 20（见 PROGRESS 的性能基线表）。
 *
 * 【合并规则：像素级等价】
 * 相邻矩形如果 **y 区间完全相同**，就并成一个更宽的矩形。
 * 这是**严格像素等价**的：两个 [x, x+1) × [y0, y1) 与一个 [x, x+2) × [y0, y1)
 * 覆盖的像素集合一模一样。台阶数据（物品存量、流体量、罐子液位）里
 * "连续几百列一个值"极常见 —— 那些列从 2N 个矩形塌成 1 个。
 *
 * 只合并"完全相同"的 y 区间，**不做"相近就并"**：
 * 那会把 1px 的台阶抹平，属于篡改图形而不是优化（本项目对数据保真的一贯要求）。
 *
 * 【用法】按 x 递增顺序 {@link #push}，再 {@link #rects} 取结果；
 * 分配上限外的矩形直接丢弃（防御病态数据，正常不会触发）。
 */
public final class RectRuns {

    /** 一次绘制最多保留多少条矩形（超出丢弃 —— 正常峰值是绘图区宽度量级） */
    public static final int MAX_RECTS = 4096;

    private int[] x0 = new int[64];
    private int[] x1 = new int[64];
    private int[] y0 = new int[64];
    private int[] y1 = new int[64];
    private int n;

    /** 丢弃掉的矩形数（诊断用：>0 说明触到了 {@link #MAX_RECTS}） */
    private int dropped;

    /**
     * push() 的调用次数（= **合并前**的矩形数 = 老实现会产生的 fill 次数）。
     * 压测就靠它对比"优化前 vs 优化后"的绘制调用量（见 dev-check/BenchRender）。
     */
    private int pushes;

    public void clear() {
        n = 0;
        dropped = 0;
        pushes = 0;
    }

    public int size() {
        return n;
    }

    public int pushes() {
        return pushes;
    }

    public int dropped() {
        return dropped;
    }

    public int x0(int i) {
        return x0[i];
    }

    public int x1(int i) {
        return x1[i];
    }

    public int y0(int i) {
        return y0[i];
    }

    public int y1(int i) {
        return y1[i];
    }

    /**
     * 追加一个矩形（x0/x1 为像素边界，y0/y1 为像素边界，均左闭右开）。
     *
     * 与上一个矩形满足**任一**条件就合并（两条都是严格像素等价的）：
     *
     * | 条件 | 合并方式 | 覆盖的典型情形 |
     * |---|---|---|
     * | **x 区间相同**，y 相接或重叠 | 并成 y 方向的包围盒 | 同一列的"连接线 + 本列 min/max 竖线" |
     * | **y 区间相同**，x 相接或重叠 | 并宽（x 方向取并集） | 台阶数据里连续几十上百列同一个值 |
     *
     * 不合并的情形也说清楚：x 相接但 y 不同（对角的两个竖条）——
     * 并成包围盒会**多画出像素**，那不是优化而是改图，必须保持两个矩形。
     */
    public void push(int px0, int px1, int py0, int py1) {
        if (px1 <= px0 || py1 <= py0) {
            return;   // 空矩形：原样丢弃（等价于画在画布上什么也看不到）
        }
        pushes++;
        if (n > 0) {
            int lx0 = x0[n - 1];
            int lx1 = x1[n - 1];
            int ly0 = y0[n - 1];
            int ly1 = y1[n - 1];
            // A) 同 x 区间 + y 相接/重叠 → y 包围盒（同 x 的竖条，并集就是包围盒）
            if (lx0 == px0 && lx1 == px1 && py0 <= ly1 && py1 >= ly0) {
                y0[n - 1] = Math.min(ly0, py0);
                y1[n - 1] = Math.max(ly1, py1);
                return;
            }
            // B) y 完全相同 + x 相接/重叠 → 并宽
            if (ly0 == py0 && ly1 == py1 && px0 <= lx1 && px1 >= lx0) {
                x0[n - 1] = Math.min(lx0, px0);
                x1[n - 1] = Math.max(lx1, px1);
                return;
            }
        }
        if (n >= MAX_RECTS) {
            dropped++;
            return;
        }
        ensure(n + 1);
        x0[n] = px0;
        x1[n] = px1;
        y0[n] = py0;
        y1[n] = py1;
        n++;
    }

    private void ensure(int need) {
        if (x0.length >= need) {
            return;
        }
        int cap = Math.max(need, x0.length * 2);
        x0 = java.util.Arrays.copyOf(x0, cap);
        x1 = java.util.Arrays.copyOf(x1, cap);
        y0 = java.util.Arrays.copyOf(y0, cap);
        y1 = java.util.Arrays.copyOf(y1, cap);
    }

    // ------------------------------------------------------------------
    // 便于断言：把结果拼成 "x0-x1@y0-y1" 串
    // ------------------------------------------------------------------

    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(x0[i]).append('-').append(x1[i])
                    .append('@').append(y0[i]).append('-').append(y1[i]);
        }
        return sb.toString();
    }
}
