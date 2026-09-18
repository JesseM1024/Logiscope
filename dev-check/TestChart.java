/*
 * ChartData 的离线验收：不启动游戏，直接喂造好的时间序列。
 *
 * 为什么要有它：断点规则（换物品 / no_data / 采样中断）是波形正确性的核心，
 * 但这些行为在游戏里只能靠肉眼看，而且很难复现（要制造 no_data，要精准暂停）。
 * 这里用几行断言把它们钉死 —— 以后改 ChartData 时跑一遍就知道有没有改坏。
 *
 * 运行（不需要 Gradle，不需要启动游戏；JDK 21 的 javac/java 即可）：
 *
 *   javac -encoding UTF-8 -cp build/classes/java/main -d build/dev-check dev-check/TestChart.java
 *   java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main" TestChart
 *   （Git Bash 里 classpath 分隔符同样是 ;，但要把路径写成 C:/... 正斜杠形式）
 *
 * 前置：先跑一次 gradlew.bat build（或 compileJava），保证 classes 是最新的。
 *
 * 为什么能这样跑：ChartData / Series / SeriesPoint / Sample 刻意不引用任何游戏类 ——
 * 这是"分析层保持纯函数"这条约定的直接回报：它天然可以脱离游戏被验证。
 *
 * 控制台乱码说明：Git Bash 里中文可能显示成乱码，那是终端按 GBK 解码 UTF-8 的
 * 显示问题（源文件本身是好的），不影响断言结果。想要正常显示就在 cmd/PowerShell
 * 里跑，或先 chcp 65001。
 */
import com.jessetech.logiscope.analysis.ChartData;
import com.jessetech.logiscope.analysis.Series;
import com.jessetech.logiscope.analysis.SeriesPoint;
import com.jessetech.logiscope.store.Sample;

import java.util.ArrayList;
import java.util.List;

public class TestChart {

    private static int failed;

    public static void main(String[] args) {
        t1ItemGroupingAndColorOrder();
        t2BreakOnItemSwitch();
        t3BreakOnNoData();
        t4BreakOnTickGap();
        t5EmptySlotSeries();
        t6Ranges();
        t7AxisBoundsSanity();
        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过");
        } else {
            System.out.println(failed + " 项失败");
            System.exit(1);
        }
    }

    // 1) 出现新物品就新建一条线；顺序 = 首次出现顺序（颜色靠它才稳定）
    private static void t1ItemGroupingAndColorOrder() {
        List<Sample> s = new ArrayList<>();
        s.add(new Sample(10, "minecraft:iron_ingot", 1));
        s.add(new Sample(11, "minecraft:iron_ingot", 2));
        s.add(new Sample(12, "minecraft:copper_ingot", 5));
        s.add(new Sample(13, "minecraft:copper_ingot", 6));
        s.add(new Sample(14, "minecraft:iron_ingot", 9));

        ChartData d = ChartData.build(1L, s);
        eq("线数", 2, d.series().size());
        eq("第一条是铁", "minecraft:iron_ingot", d.series().get(0).itemId());
        eq("第二条是铜", "minecraft:copper_ingot", d.series().get(1).itemId());
        eq("铁有 3 个点", 3, d.series().get(0).points().size());
        eq("铜有 2 个点", 2, d.series().get(1).points().size());
        eq("切换次数", 2, d.switches());
        eq("有数据样本", 5, d.dataCount());
        eq("首 tick", 10L, d.firstTick());
        eq("末 tick", 14L, d.lastTick());
        eq("铁线的范围", "1..9", d.series().get(0).min() + ".." + d.series().get(0).max());
        eq("最后读到的铁数量", 9, d.series().get(0).lastCount());
    }

    // 2) 换物品那一点必须断开（从 64 个铁变成 1 个铜，差值 -63 没有物理意义）
    private static void t2BreakOnItemSwitch() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 64),
                new Sample(2, "minecraft:copper_ingot", 1),
                new Sample(3, "minecraft:copper_ingot", 2));

        ChartData d = ChartData.build(1L, s);
        List<SeriesPoint> copper = d.series().get(1).points();
        yes("铜的起点必须断开", copper.get(0).breakBefore());
        no("铜的第二点连着", copper.get(1).breakBefore());
        List<SeriesPoint> iron = d.series().get(0).points();
        yes("每条线的第一点都断开", iron.get(0).breakBefore());
        eq("铁只有 1 个点", 1, iron.size());
    }

    // 3) 中间夹 no_data：断档前后的同类物品也必须断开（连线等于编造数据）
    private static void t3BreakOnNoData() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                Sample.noData(3),
                Sample.noData(4),
                new Sample(5, "minecraft:iron_ingot", 12));

        ChartData d = ChartData.build(1L, s);
        Series iron = d.series().get(0);
        eq("铁仍是同一条线", 1, d.series().size());
        eq("铁有 3 个点", 3, iron.points().size());
        // 契约：每条线的第一个点一定是"段起点"（它前面没东西可连）。
        // 渲染层靠 colStart 判断"要不要连到前一列"，而首列本来就没有前一列，所以无害。
        yes("首点标记为段起点", iron.points().get(0).breakBefore());
        no("断档前不断", iron.points().get(1).breakBefore());
        yes("no_data 之后必须断开", iron.points().get(2).breakBefore());
        eq("断点个数", 2, iron.breaks());
        eq("no_data 样本数", 2, d.noDataCount());
        eq("时间轴仍然推进到 no_data 那一段", 5L, d.lastTick());
    }

    // 4) tick 不连续（暂停 / 状态切换）也要断开
    private static void t4BreakOnTickGap() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                new Sample(60, "minecraft:iron_ingot", 12));

        ChartData d = ChartData.build(1L, s);
        List<SeriesPoint> p = d.series().get(0).points();
        no("相邻 tick 不断", p.get(1).breakBefore());
        yes("跳了 58 tick 必须断开", p.get(2).breakBefore());
        eq("切换次数不把断档算进去", 0, d.switches());
    }

    // 5) 空格子（air × 0）也是一条正经的线，不该被丢掉
    private static void t5EmptySlotSeries() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 64),
                new Sample(2, Sample.EMPTY_ITEM, 0),
                new Sample(3, Sample.EMPTY_ITEM, 0));

        ChartData d = ChartData.build(1L, s);
        eq("两条线：铁 + 空", 2, d.series().size());
        Series air = d.byItem(Sample.EMPTY_ITEM);
        yes("空格子那条线存在", air != null);
        eq("空格子数量恒为 0", 0, air.max());
        yes("被识别为空格子线", air.isEmptyItem());
        eq("空格子线有 2 个点", 2, air.points().size());
    }

    // 6) 框选统计
    private static void t6Ranges() {
        List<Sample> s = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            s.add(new Sample(100 + i, "minecraft:iron_ingot", i * 10));
        }
        s.add(new Sample(110, Sample.EMPTY_ITEM, 0));
        s.add(new Sample(111, Sample.EMPTY_ITEM, 0));

        ChartData d = ChartData.build(1L, s);
        List<ChartData.SeriesRange> r = d.ranges(102, 106);
        eq("区间内只有铁", 1, r.size());
        ChartData.SeriesRange iron = r.get(0);
        eq("区间内采样点数", 5, iron.samples());
        eq("区间首值", 20, iron.first());
        eq("区间末值", 60, iron.last());
        eq("净变化", 40, iron.delta());
        eq("区间极值", "20..60", iron.min() + ".." + iron.max());

        List<ChartData.SeriesRange> all = d.ranges(0, 1000);
        eq("全区间两条线", 2, all.size());
    }

    // 7) 自动缩放要用的 min/max，以及"全 no_data / 空输入"的退化情形
    private static void t7AxisBoundsSanity() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 3),
                new Sample(2, "minecraft:iron_ingot", 71));
        ChartData d = ChartData.build(1L, s);
        eq("全局最小", 3, d.minValue());
        eq("全局最大", 71, d.maxValue());
        yes("有数据", d.hasData());

        List<Sample> only = List.of(Sample.noData(1), Sample.noData(2));
        ChartData n = ChartData.build(2L, only);
        no("全 no_data 时 hasData 为假", n.hasData());
        eq("全 no_data 时 min 归零而不是 MAX_VALUE", 0, n.minValue());
        eq("全 no_data 时 max 归零", 0, n.maxValue());
        eq("时间轴仍在推进", 2L, n.lastTick());

        ChartData e = ChartData.build(3L, List.of());
        no("空输入 hasData 为假", e.hasData());
        yes("空输入 isEmpty", e.isEmpty());
    }

    // ---- 断言 ----

    private static void eq(String what, Object expect, Object actual) {
        if (expect == null ? actual == null : expect.equals(actual)) {
            System.out.println("  ok   " + what + " = " + actual);
        } else {
            failed++;
            System.out.println("  FAIL " + what + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void yes(String what, boolean b) {
        eq(what, true, b);
    }

    private static void no(String what, boolean b) {
        eq(what, false, b);
    }
}
