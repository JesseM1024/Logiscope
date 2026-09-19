/*
 * 渲染路径的离线压测（第十三轮）：**不动游戏，量"每帧要发多少次 fill"**。
 *
 * 为什么用 fill 次数当指标：实测基线（2026-09-18，界面显示的 FPS）显示
 * 掉帧随"可见线条数"近似线性恶化，大头就是"每条线每帧全窗口重画"——
 * 那条路径的产物就是一堆 GuiGraphics#fill。fill 次数降下来，帧率自然上去。
 *
 * 这个压测走的是**真实代码**（analysis/WaveColumns + analysis/RectRuns），
 * 不是复刻一份逻辑 —— 所以数字可信、也不会随实现漂移。
 *
 * 运行（不需要 Gradle，不需要启动游戏）：
 *
 *   javac -encoding UTF-8 -cp build/classes/java/main -d build/dev-check dev-check/BenchRender.java
 *   java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main" BenchRender
 *
 * 场景与实机基线对应：
 *   台阶（物品存量 / 液位）—— 最常见的形状，游程合并收益最大
 *   阶梯（罐子一格格进料）—— 中等
 *   噪声（FE 能量逐 tick 变化）—— 最坏情况，合并几乎帮不上
 *   流量（IO 线：绝大多数 tick 为 0，偶尔一根尖峰）
 */
import com.jessetech.logiscope.analysis.RectRuns;
import com.jessetech.logiscope.analysis.SeriesPoint;
import com.jessetech.logiscope.analysis.WaveColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BenchRender {

    private static final int PLOT_W = 700;      // 绘图区宽度（像素列数）
    private static final int PLOT_H = 220;
    private static final int WINDOW_TICKS = 1200;   // 窗口覆盖的 tick 数
    private static final int LINES = 50;            // 同时可见的线条数（25 物品 × 存量+IO）

    public static void main(String[] args) {
        System.out.println("渲染路径离线压测：每条线在窗口内的 fill 次数（" + LINES + " 线 × "
                + PLOT_W + " 列，窗口 " + WINDOW_TICKS + " tick）");
        System.out.println();

        bench("台阶（物品存量 / 液位）", shapePlateau());
        bench("阶梯（罐子逐格进料）", shapeSteps());
        bench("噪声（FE 能量逐 tick）", shapeNoisy());
        bench("流量（IO：多为 0，偶尔尖峰）", shapeFlow());
        bench("混合（实机近似：一半台阶 + 一半噪声）", shapeMixed());
    }

    // ------------------------------------------------------------------
    // 场景构造
    // ------------------------------------------------------------------

    /** 台阶：每 240 tick 变一次（一个窗口只有 5 段） */
    private static List<SeriesPoint> shapePlateau() {
        List<SeriesPoint> pts = new ArrayList<>();
        int v = 1000;
        for (int t = 0; t <= WINDOW_TICKS; t++) {
            if (t % 240 == 0) {
                v += 7;
            }
            pts.add(new SeriesPoint(t, v, t == 0));
        }
        return pts;
    }

    /** 阶梯：每 20 tick 变一次（罐子一格格进） */
    private static List<SeriesPoint> shapeSteps() {
        List<SeriesPoint> pts = new ArrayList<>();
        int v = 0;
        for (int t = 0; t <= WINDOW_TICKS; t++) {
            if (t % 20 == 0) {
                v += 3;
            }
            pts.add(new SeriesPoint(t, v, t == 0));
        }
        return pts;
    }

    /** 噪声：逐 tick 变化（最坏情况） */
    private static List<SeriesPoint> shapeNoisy() {
        List<SeriesPoint> pts = new ArrayList<>();
        Random rnd = new Random(42);
        int v = 500_000;
        for (int t = 0; t <= WINDOW_TICKS; t++) {
            v += rnd.nextInt(4001) - 2000;
            pts.add(new SeriesPoint(t, v, t == 0));
        }
        return pts;
    }

    /** 流量：90% 的 tick 是 0，偶尔一根尖峰 */
    private static List<SeriesPoint> shapeFlow() {
        List<SeriesPoint> pts = new ArrayList<>();
        Random rnd = new Random(7);
        for (int t = 0; t <= WINDOW_TICKS; t++) {
            int v = rnd.nextInt(10) == 0 ? rnd.nextInt(64) + 1 : 0;
            pts.add(new SeriesPoint(t, v, t == 0));
        }
        return pts;
    }

    /** 混合：一半台阶一半噪声（实机里物品线和数据线同时开着就是这样） */
    private static List<SeriesPoint> shapeMixed() {
        List<SeriesPoint> a = shapePlateau();
        List<SeriesPoint> b = shapeNoisy();
        List<SeriesPoint> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size() && i < b.size(); i++) {
            long v = i % 2 == 0 ? a.get(i).value().i() : b.get(i).value().i() / 1000;
            out.add(new SeriesPoint(a.get(i).tick(), v, a.get(i).breakBefore()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 压测
    // ------------------------------------------------------------------

    private static void bench(String name, List<SeriesPoint> pts) {
        WaveColumns cols = new WaveColumns();
        RectRuns out = new RectRuns();
        // 与界面层同源的值→y 映射（线性、带钳制）；离线只需要形状，不需要真轴范围
        int[] range = valueRange(pts);
        int lo = range[0];
        int span = Math.max(1, range[1] - range[0]);

        int totalMerged = 0;
        int totalRaw = 0;
        int columns = 0;

        // 预热（JIT）
        for (int i = 0; i < 20; i++) {
            runOnce(cols, out, pts, lo, span);
        }
        long t0 = System.nanoTime();
        int reps = 30;
        for (int i = 0; i < reps; i++) {
            int[] r = runOnce(cols, out, pts, lo, span);
            totalMerged = r[0];
            totalRaw = r[1];
            columns = r[2];
        }
        long t1 = System.nanoTime();

        double perLineUs = (t1 - t0) / (double) reps / 1000.0;
        System.out.printf("%-34s 列数 %4d | 合并前 %6d | 合并后 %6d | 降幅 %5.1f%% | 全帧(%d 线) fill=%d | 计算 %.0f µs/帧%n",
                name, columns, totalRaw, totalMerged,
                totalRaw == 0 ? 0.0 : 100.0 * (totalRaw - totalMerged) / totalRaw,
                LINES, totalMerged * LINES, perLineUs * LINES);
    }

    private static int[] runOnce(WaveColumns cols, RectRuns out, List<SeriesPoint> pts,
                                 int lo, int span) {
        cols.emit(pts, 0, WINDOW_TICKS, PLOT_W, 0,
                v -> clampY((int) Math.round(v), lo, span), out);
        return new int[]{out.size(), out.pushes(), cols.lastColumns()};
    }

    private static int clampY(int v, int lo, int span) {
        int y = PLOT_H - 2 - (int) Math.round((v - lo) * (double) (PLOT_H - 4) / span);
        return Math.max(0, Math.min(PLOT_H - 1, y));
    }

    private static int[] valueRange(List<SeriesPoint> pts) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (SeriesPoint p : pts) {
            lo = Math.min(lo, (int) p.value().asDouble());
            hi = Math.max(hi, (int) p.value().asDouble());
        }
        return new int[]{lo, hi};
    }
}
