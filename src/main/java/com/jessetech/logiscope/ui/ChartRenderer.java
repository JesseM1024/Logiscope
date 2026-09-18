package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.analysis.ChartData;
import com.jessetech.logiscope.analysis.Series;
import com.jessetech.logiscope.analysis.SeriesPoint;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 呈现层：波形渲染器。
 *
 * ★ 它为什么是独立的一个类 ★
 * 上一版的"火花线"是往 ScopeScreen 里塞了几十行。现在要画坐标轴、多条数据线、
 * 自动缩放、框选、十字线读数，再塞进去那个 800 行的界面类就没法读了。
 * 这里只负责"把 ChartData 画到一块矩形里"，不认识 Sampler，也不改任何状态。
 *
 * 【五项关键取舍，都写在对应代码旁边】
 *   1. 纵轴自动缩放要"吸附到整数刻度" —— 否则跟随窗口滚动时数字会一直抖
 *   2. 稠密数据按**列**取 min/max      —— 一个像素列里的尖峰不能被平均掉
 *   3. 稀疏数据按**列**之间线性插值    —— 否则折线会退化成一串孤立的点
 *   4. 断点（换物品 / no_data / 采样中断）一律不连线 —— 连线等于编造数据
 *   5. no_data 的列什么都不画           —— 断档就该看得出来是断档
 *
 * 全程只用 GuiGraphics#fill + drawString，不引入任何图表库。
 * 每帧的复杂度是 O(窗口内采样点数)，且**不产生任何常驻分配**
 * （列聚合用的数组按宽度复用，见 ensureCols）—— 这是"挂机 10 分钟不掉帧"的底气。
 */
public final class ChartRenderer {

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    /**
     * 数据线配色。按 Series 在列表里的**下标**取色。
     *
     * 下标 = 物品首次出现的顺序（见 ChartData#build 里的 LinkedHashMap），
     * 所以颜色是稳定的：某条线不会因为"又冒出一个新物品"而突然换色。
     * 挑色的标准是"在深色底上互相分得开"，而不是好看 —— 观测工具优先看辨得清。
     *
     * ★ 每个颜色都必须带 0xFF 的 alpha 位 ★
     * GuiGraphics#fill 的颜色是 ARGB，alpha 为 0 就是**完全透明**——
     * 它不像 drawString 那样有 adjustColor 兜底（Font.java:172：alpha 位为 0 就补 0xFF）。
     * 第五轮实机"图里没有任何线"就是这里：调色板写成 0x5DCAA5，alpha=0，线全被画没了。
     * 这个坑从第一轮的火花线（0x9BE1CB）就存在，只是当时没人盯着火花线看。
     */
    private static final int[] PALETTE = {
            0xFF5DCAA5, // 青绿
            0xFF6FB6E8, // 蓝
            0xFFEF9F27, // 琥珀
            0xFFE86A6A, // 红
            0xFFB98AE8, // 紫
            0xFF8FD14F, // 黄绿
            0xFFE8A0C8, // 粉
            0xFF4FD1E0, // 青
            0xFFD9C36B, // 土黄
            0xFFA0A8B8, // 灰
    };

    /** "格子空着"单独给一个低饱和的灰蓝：它不是物品，不该和物品线抢注意力 */
    private static final int EMPTY_SLOT_COLOR = 0xFF707888;

    // ------------------------------------------------------------------
    // 布局与配色常量
    // ------------------------------------------------------------------

    /**
     * 左侧留给纵轴数字的宽度。
     *
     * ★ 第十轮加宽（34 → 46），第十轮第三次修复又靠"压缩步长"彻底解决溢出 ★
     * 实测反馈：测"单格堆叠"这种大数量的容器时，刻度数字（如 16000000）长到
     * 画到 GUI 外面去了。修法：刻度改用科学计数法（见 {@link #fmtAxis}），
     * 最长的标签形如 `-9.999E8`（8 字符 ≈ 44px）。
     */
    public static final int AXIS_LEFT = 46;
    /** 底部留给时间刻度的宽度 */
    public static final int AXIS_BOTTOM = 11;
    private static final int PAD_TOP = 5;
    private static final int PAD_RIGHT = 5;

    private static final int COLOR_BG = 0xFF13131B;
    private static final int COLOR_BORDER = 0xFF33333F;
    private static final int COLOR_GRID = 0x18FFFFFF;
    private static final int COLOR_LABEL = 0xFF7A7A92;
    private static final int COLOR_TEXT = 0xFFD0D0DC;
    private static final int COLOR_SEL = 0xFFEF9F27;
    private static final int COLOR_CROSS = 0x60FFFFFF;

    /** 时间刻度的候选步长（tick）。20 tick = 1 秒。 */
    private static final long[] TICK_LADDER = {
            20, 40, 100, 200, 400, 1000, 2000, 4000, 10000, 20000, 40000, 100000, 200000, 600000
    };

    // ------------------------------------------------------------------
    // 每帧复用的列聚合缓冲（不分配 → 不掉帧）
    // ------------------------------------------------------------------

    private int[] colMin = new int[0];
    private int[] colMax = new int[0];
    private int[] colFirst = new int[0];
    private int[] colLast = new int[0];
    private boolean[] colHas = new boolean[0];
    private boolean[] colStart = new boolean[0];

    // ------------------------------------------------------------------
    // 一次 render 期间有效的映射参数
    // （写成字段是为了让 yOf/xOf 这些被调上千次的小函数不用背一长串参数）
    // ------------------------------------------------------------------

    private long start;
    private long end;
    private int chartX;
    private int chartW;
    private int plotX;
    private int plotY;
    private int plotW;
    private int plotH;
    private int plotR;
    private int plotB;
    private int yLo;
    private int ySpan;
    private int usable;

    // ------------------------------------------------------------------
    // 对外的小数据结构
    // ------------------------------------------------------------------

    /** 用户框选出来的一段 tick 区间（G4 的标注会建在它上面） */
    public record Selection(long fromTick, long toTick) {

        public long lo() {
            return Math.min(fromTick, toTick);
        }

        public long hi() {
            return Math.max(fromTick, toTick);
        }

        public long spanTicks() {
            return hi() - lo();
        }

        public boolean isValid() {
            return hi() > lo();
        }
    }

    /**
     * 一帧要画的东西。打包成一个参数，免得 render 的签名长到看不懂。
     *
     * @param startTick      可见窗口起点（tick）
     * @param endTick        可见窗口终点（tick）
     * @param hidden         被图例关掉的物品线
     * @param selection      框选区间；null = 没有
     * @param draft          true = 还在拖（只画框，不画统计）
     * @param selectionStats 框选区间的逐线统计（拖完才算，所以拖的时候传空表）
     * @param mouseX/mouseY  真实鼠标坐标，用来画十字线读数
     */
    public record View(
            long startTick,
            long endTick,
            Set<String> hidden,
            @Nullable Selection selection,
            boolean draft,
            List<ChartData.SeriesRange> selectionStats,
            int mouseX,
            int mouseY) {
    }

    public static int colorOf(int seriesIndex, String itemId) {
        if (ChartData.EMPTY_ITEM.equals(itemId)) {
            return EMPTY_SLOT_COLOR;
        }
        return PALETTE[Math.floorMod(seriesIndex, PALETTE.length)];
    }

    // ------------------------------------------------------------------
    // 几何（界面那边命中判定也要用，所以做成静态的，保证只有一份公式）
    // ------------------------------------------------------------------

    public static int plotWidth(int chartW) {
        return Math.max(10, chartW - AXIS_LEFT - PAD_RIGHT);
    }

    public static int plotHeight(int chartH) {
        return Math.max(10, chartH - PAD_TOP - AXIS_BOTTOM);
    }

    /** 图表外框的哪一块是"绘图区"（鼠标框选、十字线判定用） */
    public static boolean inPlot(int chartX, int chartY, int chartW, int chartH,
                                 double mx, double my) {
        int px = chartX + AXIS_LEFT;
        int py = chartY + PAD_TOP;
        return mx >= px && mx < px + plotWidth(chartW)
                && my >= py && my < py + plotHeight(chartH);
    }

    public static int xOfTick(int chartX, int chartW, long startTick, long endTick, long tick) {
        long span = Math.max(1L, endTick - startTick);
        return chartX + AXIS_LEFT
                + (int) Math.round((tick - startTick) * (double) (plotWidth(chartW) - 1) / span);
    }

    public static long tickAtX(int chartX, int chartW, long startTick, long endTick, double mx) {
        long span = Math.max(1L, endTick - startTick);
        int px = chartX + AXIS_LEFT;
        double ratio = (mx - px) / Math.max(1.0, plotWidth(chartW) - 1);
        long t = startTick + Math.round(ratio * span);
        return Math.max(startTick, Math.min(endTick, t));
    }

    // ------------------------------------------------------------------
    // 主入口
    // ------------------------------------------------------------------

    public void render(GuiGraphics g, Font font, ChartData data,
                       int x, int y, int w, int h, View view) {
        this.chartX = x;
        this.chartW = w;
        this.start = view.startTick();
        this.end = view.endTick();
        this.plotX = x + AXIS_LEFT;
        this.plotY = y + PAD_TOP;
        this.plotW = plotWidth(w);
        this.plotH = plotHeight(h);
        this.plotR = plotX + plotW;
        this.plotB = plotY + plotH;

        g.fill(plotX, plotY, plotR, plotB, COLOR_BG);
        g.fill(plotX, plotY, plotX + 1, plotB, COLOR_BORDER);
        g.fill(plotR - 1, plotY, plotR, plotB, COLOR_BORDER);
        g.fill(plotX, plotB - 1, plotR, plotB, COLOR_BORDER);

        if (plotW < 40 || plotH < 22) {
            centered(g, font, tr("logiscope.chart.too_small"),
                    plotX + plotW / 2, plotY + plotH / 2 - 4, COLOR_LABEL);
            return;
        }

        List<Series> all = data.series();
        boolean anyVisible = false;
        int vLo = Integer.MAX_VALUE;
        int vHi = Integer.MIN_VALUE;

        for (Series s : all) {
            if (view.hidden().contains(s.itemId())) {
                continue;
            }
            anyVisible = true;
            List<SeriesPoint> pts = s.points();
            // 二分找窗口起点：全览 30 分钟数据时，这一步能省掉几万次无用的比较
            for (int i = lowerBound(pts, start); i < pts.size(); i++) {
                SeriesPoint p = pts.get(i);
                if (p.tick() > end) {
                    break;
                }
                if (p.count() < vLo) {
                    vLo = p.count();
                }
                if (p.count() > vHi) {
                    vHi = p.count();
                }
            }
        }

        if (vLo == Integer.MAX_VALUE) {
            // 窗口里没有采样点（暂停中、刚接上探头、或最近一段全是 no_data）。
            // 时间轴还是要画，否则玩家看到一块纯黑，分不清"没数据"和"面板坏了"。
            drawTimeAxis(g, font);
            centered(g, font,
                    anyVisible ? tr("logiscope.chart.no_data_in_window") : tr("logiscope.chart.all_hidden"),
                    plotX + plotW / 2, plotY + plotH / 2 - 4, COLOR_LABEL);
            // 框选照样要画 —— 否则"窗口里恰好没数据"时拖动没有任何反馈，
            // 第五轮 N8 就是这么全部失败的（用户以为框选坏了，其实是没数据 + 没反馈）
            if (view.selection() != null) {
                drawSelection(g, font, view.selection(), List.of(), data);
            }
            return;
        }

        // ---- 纵轴：自动缩放 + 吸附到整数刻度 ----
        // 吸附（而不是直接用 min/max）有两个好处：
        //   · 刻度落在整数上，读得出来
        //   · 窗口滚动时范围只会偶尔跳一次，不会每一帧都在抖
        int step = niceStep((int) Math.ceil((vHi - vLo) / 4.0));
        // ★ 刻度必须"读得出来"：相邻两个标签不能是同一个字符串 ★
        // 大数量 + 小窗口时科学计数法只有 3 位小数，步长太小就会印出
        // 一排一模一样的 `1.79E6`（实测反馈的截图里就是这样）。
        // 解不出更多有效数字时（轴的宽度就 46px），就把步长顶上去 ——
        // 宁可刻度稀疏，也不要一排看不懂的数字。
        for (int guard = 0; guard < 12; guard++) {
            int lo = Math.max(0, Math.floorDiv(vLo, step) * step);
            if (lo + (long) step > vHi) {
                break;   // 窗口里只有一条刻度线，无所谓重复
            }
            if (!fmtAxis(lo).equals(fmtAxis(lo + step))) {
                break;
            }
            int next = nextNiceStep(step);
            if (next <= step) {
                break;
            }
            step = next;
        }
        yLo = Math.max(0, Math.floorDiv(vLo, step) * step);
        int yHi = ceilDiv(vHi, step) * step;
        if (yHi <= yLo) {
            yHi = yLo + step;
        }
        ySpan = yHi - yLo;
        usable = Math.max(1, plotH - 4);

        drawYAxis(g, font, yLo, yHi, step);
        drawTimeAxis(g, font);

        // ---- 数据线 ----
        ensureCols(plotW);
        for (int i = 0; i < all.size(); i++) {
            Series s = all.get(i);
            if (view.hidden().contains(s.itemId())) {
                continue;
            }
            drawSeries(g, s, colorOf(i, s.itemId()));
        }

        // ---- 框选 ----
        Selection sel = view.selection();
        if (sel != null) {
            drawSelection(g, font, sel, view.draft() ? List.of() : view.selectionStats(), data);
        }

        // ---- 十字线读数 ----
        drawCrosshair(g, font, data, view);

        // ---- 右上角：窗口信息 ----
        long span = Math.max(1L, end - start);
        String win = fmtSecs(span) + " / " + span + "t";
        g.drawString(font, win, plotR - 4 - font.width(win), plotY + 3, COLOR_LABEL, false);
    }

    // ------------------------------------------------------------------
    // 坐标轴
    // ------------------------------------------------------------------

    private void drawYAxis(GuiGraphics g, Font font, int lo, int hi, int step) {
        int lines = (hi - lo) / step;
        if (lines > 10) {
            step = (hi - lo) / 5;
        }
        for (int v = lo; v <= hi; v += step) {
            int yy = yOf(v);
            g.fill(plotX + 1, yy, plotR - 1, yy + 1, COLOR_GRID);
            // 只有**纵轴**用科学计数法（用户要求）：它要容纳 8 位数量级的刻度，
            // 而读数（十字线、框选统计、面板数字）一律给精确整数（见 fmtExact）。
            String lb = fmtAxis(v);
            g.drawString(font, lb, plotX - 3 - font.width(lb), yy - 3, COLOR_LABEL, false);
        }
    }

    private void drawTimeAxis(GuiGraphics g, Font font) {
        long span = Math.max(1L, end - start);
        long step = niceTickStep(span / 4);
        // 窗口很短时按 tick 标，稍长就换算成秒 —— '−3t' 比 '−0s' 有意义得多
        boolean asSeconds = span >= 400;
        for (long t = ceilTo(start, step); t < end; t += step) {
            int xx = xOf(t);
            if (xx < plotX + 2) {
                continue;
            }
            g.fill(xx, plotY + 1, xx + 1, plotB - 1, COLOR_GRID);
            String lb = asSeconds ? "-" + ((end - t) / 20) + "s" : "-" + (end - t) + "t";
            if (xx + 2 + font.width(lb) < plotR) {
                g.drawString(font, lb, xx + 2, plotB + 2, COLOR_LABEL, false);
            }
        }
    }

    // ------------------------------------------------------------------
    // 数据线
    // ------------------------------------------------------------------

    private void drawSeries(GuiGraphics g, Series s, int color) {
        List<SeriesPoint> pts = s.points();
        Arrays.fill(colHas, false);
        Arrays.fill(colStart, false);

        boolean any = false;
        for (int i = lowerBound(pts, start); i < pts.size(); i++) {
            SeriesPoint p = pts.get(i);
            if (p.tick() > end) {
                break;
            }
            int c = colOf(p.tick());
            if (c < 0 || c >= plotW) {
                continue;
            }
            if (!colHas[c]) {
                colHas[c] = true;
                colStart[c] = p.breakBefore();
                colMin[c] = p.count();
                colMax[c] = p.count();
                colFirst[c] = p.count();
                colLast[c] = p.count();
            } else {
                if (p.count() < colMin[c]) {
                    colMin[c] = p.count();
                }
                if (p.count() > colMax[c]) {
                    colMax[c] = p.count();
                }
                colLast[c] = p.count();
            }
            any = true;
        }
        if (!any) {
            return;
        }

        int prev = -1;
        for (int c = 0; c < plotW; c++) {
            if (!colHas[c]) {
                continue;
            }
            if (prev >= 0 && !colStart[c]) {
                if (prev == c - 1) {
                    // 相邻两列：一条竖直的连接线（数据是台阶状，跳变就发生在这里）
                    vline(g, plotX + c, colFirst[c], colLast[prev], color);
                } else {
                    // 中间隔了几列没有采样点 —— 但这一段折线在数据上是连续的
                    // （只是采样点稀疏，例如刚接上探头、或缩放到很近）。
                    // 线性补上：不补的话折线会变成一串孤立的点。
                    // 注意断档不会走到这里：断档会让下一列的 colStart = true。
                    int v0 = colLast[prev];
                    int v1 = colFirst[c];
                    int d = c - prev;
                    for (int k = prev + 1; k <= c; k++) {
                        double a = v0 + (v1 - v0) * (double) (k - 1 - prev) / d;
                        double b = v0 + (v1 - v0) * (double) (k - prev) / d;
                        vlineF(g, plotX + k, a, b, color);
                    }
                }
            }
            // 本列的 min/max 竖线：落在同一个像素列里的所有采样点取极值，
            // 于是"一瞬冲到 64 又落回 0"这种尖峰不会被平均掉（第 2 项取舍）。
            vline(g, plotX + c, colMax[c], colMin[c], color);
            prev = c;
        }
    }

    // ------------------------------------------------------------------
    // 框选
    // ------------------------------------------------------------------

    private void drawSelection(GuiGraphics g, Font font, Selection sel,
                               List<ChartData.SeriesRange> stats, ChartData data) {
        int xa = xOf(sel.lo());
        int xb = xOf(sel.hi());
        int left = Math.max(plotX, Math.min(xa, xb));
        int right = Math.min(plotR, Math.max(xa, xb) + 1);
        if (right > left) {
            g.fill(left, plotY + 1, right, plotB - 1, 0x30EF9F27);
        }
        if (xa >= plotX && xa < plotR) {
            g.fill(xa, plotY + 1, xa + 1, plotB - 1, 0xC0EF9F27);
        }
        if (xb >= plotX && xb < plotR) {
            g.fill(xb, plotY + 1, xb + 1, plotB - 1, 0xC0EF9F27);
        }
        if (stats.isEmpty()) {
            return;
        }

        // ---- 统计块：谁在框里、变了多少 ----
        long span = Math.max(1L, sel.spanTicks());
        int maxLines = Math.max(1, Math.min(4, (plotH - 16) / 10));
        StringBuilder head = new StringBuilder()
                .append("tick ").append(sel.lo()).append("→").append(sel.hi())
                .append("  ").append(fmtSecs(span));

        String[] rows = new String[maxLines];
        int[] rowColors = new int[maxLines];
        int n = 0;
        int extra = 0;
        for (ChartData.SeriesRange r : stats) {
            if (n >= maxLines) {
                extra++;
                continue;
            }
            String name = label(data.indexOf(r.itemId()), r.itemId());
            // 框选统计 = 读数，用**准确整数**（用户要求：只有纵轴用科学计数法）
            rows[n] = name + " " + fmtExact(r.first()) + "→" + fmtExact(r.last())
                    + "  Δ" + signedExact(r.delta())
                    + "  " + fmtRate(r.delta(), span);
            rowColors[n] = colorOf(data.indexOf(r.itemId()), r.itemId());
            n++;
        }
        if (n == 0) {
            return;
        }

        int width = font.width(head.toString());
        for (int i = 0; i < n; i++) {
            width = Math.max(width, font.width(rows[i]) + 9);
        }
        int boxH = (n + 2) * 10 + 2;
        int bx = plotX + 4;
        int by = plotY + 4;
        g.fill(bx - 2, by - 2, bx + width + 4, by + boxH, 0xE0181822);
        g.fill(bx - 2, by - 2, bx + width + 4, by - 1, 0x80EF9F27);
        g.drawString(font, head.toString(), bx, by, COLOR_SEL, false);
        int ly = by + 11;
        for (int i = 0; i < n; i++) {
            g.fill(bx, ly + 2, bx + 5, ly + 7, rowColors[i]);
            g.drawString(font, rows[i], bx + 8, ly, COLOR_TEXT, false);
            ly += 10;
        }
        if (extra > 0) {
            g.drawString(font, "+" + extra, bx + 2, ly, COLOR_LABEL, false);
        }
    }

    // ------------------------------------------------------------------
    // 十字线读数
    // ------------------------------------------------------------------

    /**
     * 鼠标在绘图区里时画一条竖直准线，并报出"界面上每条线此刻是多少"。
     *
     * 取值规则：找该 tick 之前**最近的一个采样点**（数据是台阶状的，值会一直保持到下次采样）。
     * 若最近的点离得太远（超过一个像素列的时间，说明这里其实是断档），
     * 就报 "-" —— 宁可显示"不知道"，也不要拿旧值冒充。
     */
    private void drawCrosshair(GuiGraphics g, Font font, ChartData data, View view) {
        int mx = view.mouseX();
        int my = view.mouseY();
        if (mx < plotX || mx >= plotR || my < plotY || my >= plotB) {
            return;
        }
        g.fill(mx, plotY + 1, mx + 1, plotB - 1, COLOR_CROSS);

        long t = tickAtX(chartX, chartW, start, end, mx);
        long span = Math.max(1L, end - start);
        long tolerance = Math.max(1L, span / Math.max(1, plotW)) + 1;

        StringBuilder sb = new StringBuilder();
        sb.append("t ").append(t).append("  ").append(fmtSecs(t - start));
        int shown = 0;
        for (int i = 0; i < data.series().size(); i++) {
            Series s = data.series().get(i);
            if (view.hidden().contains(s.itemId())) {
                continue;
            }
            if (shown >= 3) {
                sb.append("  +").append(data.series().size() - i);
                break;
            }
            String v = valueAt(s, t, tolerance);
            sb.append("   ").append(label(i, s.itemId())).append(' ').append(v);
            shown++;
        }

        String text = sb.toString();
        int tw = font.width(text);
        int tx = Math.max(plotX + 2, Math.min(mx + 3, plotR - 3 - tw));
        int ty = plotB - 12;
        g.fill(tx - 2, ty - 2, tx + tw + 3, ty + 10, 0xE0181822);
        g.drawString(font, text, tx, ty, COLOR_TEXT, false);
    }

    private String valueAt(Series s, long tick, long tolerance) {
        List<SeriesPoint> pts = s.points();
        int idx = lowerBound(pts, tick + 1) - 1;
        if (idx < 0) {
            return "-";
        }
        SeriesPoint p = pts.get(idx);
        if (tick - p.tick() > tolerance) {
            return "-";
        }
        // 十字线读数 = 读数，用**准确整数**（用户要求：只有纵轴用科学计数法）
        return fmtExact(p.count());
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private int colOf(long tick) {
        long span = Math.max(1L, end - start);
        return (int) ((tick - start) * (long) (plotW - 1) / span);
    }

    private int xOf(long tick) {
        return xOfTick(chartX, chartW, start, end, tick);
    }

    private int yOf(int v) {
        return clampY(plotB - 2 - (int) Math.round((v - yLo) * (double) usable / ySpan));
    }

    private int yOfF(double v) {
        return clampY(plotB - 2 - (int) Math.round((v - yLo) * usable / ySpan));
    }

    private int clampY(int y) {
        return Math.max(plotY, Math.min(plotB - 1, y));
    }

    private void vline(GuiGraphics g, int x, int vA, int vB, int color) {
        if (x < plotX || x >= plotR) {
            return;
        }
        g.fill(x, yOf(Math.max(vA, vB)), x + 1, yOf(Math.min(vA, vB)) + 1, color);
    }

    private void vlineF(GuiGraphics g, int x, double vA, double vB, int color) {
        if (x < plotX || x >= plotR) {
            return;
        }
        g.fill(x, yOfF(Math.max(vA, vB)), x + 1, yOfF(Math.min(vA, vB)) + 1, color);
    }

    private void ensureCols(int n) {
        if (colHas.length >= n) {
            return;
        }
        colMin = new int[n];
        colMax = new int[n];
        colFirst = new int[n];
        colLast = new int[n];
        colHas = new boolean[n];
        colStart = new boolean[n];
    }

    /** 第一个 tick >= 目标 的点下标（点按 tick 升序，所以可以二分） */
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

    private static int niceStep(int raw) {
        int r = Math.max(1, raw);
        int exp = 1;
        while (exp * 10 <= r) {
            exp *= 10;
        }
        for (int m : new int[]{1, 2, 5, 10}) {
            int cand = exp * m;
            if (cand >= r) {
                return cand;
            }
        }
        return exp * 10;
    }

    /**
     * 比 {@code step} 大的下一个"整齐"步长（1/2/5/10 × 10^k）。
     * 用在"相邻刻度标签重复"时把步长顶上去（见 {@code render}）。
     */
    private static int nextNiceStep(int step) {
        int exp = 1;
        while (exp * 10 <= step) {
            exp *= 10;
        }
        for (int m : new int[]{1, 2, 5, 10, 20}) {
            int cand = exp * m;
            if (cand > step) {
                return cand;
            }
        }
        return step * 10;
    }

    private static long niceTickStep(long raw) {
        for (long v : TICK_LADDER) {
            if (v >= raw) {
                return v;
            }
        }
        return TICK_LADDER[TICK_LADDER.length - 1];
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static long ceilTo(long value, long step) {
        if (step <= 0) {
            return value;
        }
        long rem = value % step;
        return rem == 0 ? value : value + (step - rem);
    }

    /** 给图例 / 读数用的短名字 */
    public static String label(int seriesIndex, String itemId) {
        if (ChartData.EMPTY_ITEM.equals(itemId)) {
            return tr("logiscope.slot.empty");
        }
        int i = itemId.indexOf(':');
        return i < 0 ? itemId : itemId.substring(i + 1);
    }

    /**
     * **纵轴刻度专用**：数量 → 给人看的短标签（科学计数法）。
     *
     * ★ 为什么大数要用科学计数法（第十轮实测反馈）★
     * 用户测"单格堆叠"这类大数量的容器时，纵轴刻度是 16000000 这种 8 位数，
     * 直接画到 GUI 外面去了。改成 {@code 1.6E7} 之后 7 个字符就够，
     * 配合 {@link #AXIS_LEFT} 的 46px 怎么都不会溢出。
     *
     * 规则（小数字保持可读的整数，只有大到读不动才换写法）：
     *
     * | 数值 | 显示 |
     * |---|---|
     * | 0 … 9999 | 原样整数（`0` / `64` / `9999`） |
     * | ≥ 10000 | 科学计数法，**固定 3 位小数**、去掉多余的 0（`1E4` / `1.5E5` / `1.786E6`） |
     *
     * 固定 3 位小数（而不是"最多 2 位"）是第十轮第三次修复的改动：
     * 尾数少一位，`1786000` 和 `1786500` 会印成同一个 `1.79E6`，
     * 用户实测就撞上了"一整条轴上 5 个刻度全是 1.79E6"。
     * 精度顶到头之后仍可能重复（那说明窗口窄得像放大镜），
     * 由调用方把**步长**顶上去解决（见 {@code render} 里的刻度去重）。
     *
     * 用整数运算做尾数，避免 {@code 9999999} 被浮点四舍五入成 {@code 1.0E7} 那种假精度。
     *
     * ★ 只给纵轴用 ★ 读数（十字线 / 框选统计 / 面板数字）一律用 {@link #fmtExact}，
     * 因为那几处要的是**准确整数**，不是"看得出来大概多少"。
     */
    public static String fmtAxis(int v) {
        if (v == 0) {
            return "0";
        }
        String sign = v < 0 ? "-" : "";
        long a = Math.abs((long) v);
        if (a < 10000) {
            return String.valueOf(v);
        }
        // 把 |v| 缩到 [1000, 9999] 的整数（= 尾数保留三位小数），同时数出指数
        int exp = 0;
        long m = a;
        while (m >= 10000) {
            m /= 10;
            exp++;
        }
        int displayExp = exp + 3;
        int whole = (int) (m / 1000);
        int frac = (int) (m % 1000);
        if (frac == 0) {
            return sign + whole + "E" + displayExp;
        }
        // 尾数补成三位再剪掉末尾的 0：`1.500E6` → `1.5E6`，`1.050E6` → `1.05E6`
        StringBuilder f = new StringBuilder(3);
        int tmp = frac;
        for (int i = 100; i >= 1; i /= 10) {
            f.append((char) ('0' + (tmp / i) % 10));
        }
        while (f.length() > 1 && f.charAt(f.length() - 1) == '0') {
            f.setLength(f.length() - 1);
        }
        return sign + whole + "." + f + "E" + displayExp;
    }

    /**
     * **读数专用**：原样整数，一位不省。
     *
     * 用户要求（第十轮第三次修复）：只有纵轴用科学计数法，
     * 框选统计、十字线读数要的是**准确整数数值** ——
     * 一个物流示波器报出 `1.79E6` 是没有意义的，用户要知道到底是几个。
     */
    public static String fmtExact(int v) {
        return String.valueOf(v);
    }

    private static String fmtSecs(long ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    /** 速率单位是 **n per tick**（第七轮 N12：横轴是世界 tick，速率也按 tick 算才对得上） */
    private static String fmtRate(int delta, long spanTicks) {
        return String.format(Locale.ROOT, "%.3f/t", delta / (double) Math.max(1L, spanTicks));
    }

    /** 带正负号的读数标签（准确整数） */
    private static String signedExact(int v) {
        return v > 0 ? "+" + fmtExact(v) : fmtExact(v);
    }

    private static void centered(GuiGraphics g, Font font, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
