/*
 * 分析层的离线验收：不启动游戏，直接喂造好的时间序列。
 *
 * 覆盖三块：
 *   1. ChartData 的断点规则（per-series 连续性 / no_data / gap 中断标记 / 多物品同 tick）
 *   2. Aggregator 的多格聚合规则（按物品分别求和 —— 第十轮按用户反馈修正过）
 *   3. Analyzer 的按 tick 合计统计
 *
 * 为什么要有它：这些行为在游戏里都只能靠肉眼看，而且很难复现
 * （要制造 no_data、要精准暂停、要摆出"两格装不同物品"的局面）。
 * 这里用几行断言把它们钉死 —— 以后改这几处时跑一遍就知道有没有改坏。
 *
 * 运行（不需要 Gradle，不需要启动游戏；JDK 21 的 javac/java 即可）：
 *
 *   javac -encoding UTF-8 -cp build/classes/java/main -d build/dev-check dev-check/TestChart.java
 *   java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main" TestChart
 *   （Git Bash 里 classpath 分隔符同样是 ;，但要把路径写成 C:/... 正斜杠形式）
 *
 * 前置：先跑一次 gradlew.bat build（或 compileJava），保证 classes 是最新的。
 *
 * 为什么能这样跑：ChartData / Analyzer / Aggregator / Series / SeriesPoint / Sample
 * 刻意不引用任何游戏类 —— 这是"分析层保持纯函数"这条约定的直接回报：
 * 它天然可以脱离游戏被验证。
 *
 * 控制台乱码说明：Git Bash 里中文可能显示成乱码，那是终端按 GBK 解码 UTF-8 的
 * 显示问题（源文件本身是好的），不影响断言结果。想要正常显示就在 cmd/PowerShell
 * 里跑，或先 chcp 65001。
 */
import com.jessetech.logiscope.analysis.Aggregator;
import com.jessetech.logiscope.analysis.Analyzer;
import com.jessetech.logiscope.analysis.ChartData;
import com.jessetech.logiscope.analysis.Series;
import com.jessetech.logiscope.analysis.SeriesPoint;
import com.jessetech.logiscope.store.Sample;
import com.jessetech.logiscope.store.SampleStore;

import java.util.ArrayList;
import java.util.List;

public class TestChart {

    private static int failed;

    public static void main(String[] args) {
        t1ItemGroupingAndColorOrder();
        t2BreakOnItemSwitch();
        t3BreakOnNoData();
        t4ObservationGapDoesNotBreak();
        t5EmptySlotSeries();
        t6Ranges();
        t7AxisBoundsSanity();
        t8AggregateSameItem();
        t9AggregateDifferentItemsStaySeparate();
        t10AggregateDegenerate();
        t11MultiItemTickNoFalseBreak();
        t12ItemVanishesThenReturns();
        t13BreakAfterNoData();
        t14SummaryAggregatesPerTick();
        t15BreakOnGapMarker();
        t16MarkersAreTransparentToStats();
        t17SeriesDropoutInMultiSlot();
        t18LatestTick();
        t19FlowBasics();
        t20FlowInRangeStats();
        t21KindCap();
        t22StoreResize();
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

    // 4) ★ 第十轮第三次修复：**观测空档不再断开** ★
    //    客户端每秒最多观测 20 次，服务端也每秒走 20 tick，两者不锁相 ——
    //    一次帧抖动就会让某个客户端 tick 看到 +2 的世界 tick（少观测了一格）。
    //    这不是"数据断了"，只是采样稀疏。旧规则（间隔 > 1 就断）会在这里凭空剪一刀，
    //    实测现象就是 20t 正常流速下的偶发断线。
    private static void t4ObservationGapDoesNotBreak() {
        // 中间少了一格（游戏里就是"这一 tick 没观测到"）
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                new Sample(4, "minecraft:iron_ingot", 13),
                new Sample(5, "minecraft:iron_ingot", 14));

        ChartData d = ChartData.build(1L, s);
        List<SeriesPoint> p = d.series().get(0).points();
        no("相邻 tick 不断", p.get(1).breakBefore());
        no("少观测一格（1→2→4）也不断", p.get(2).breakBefore());
        no("后面继续连着", p.get(3).breakBefore());
        eq("只有首点一个断点", 1, d.series().get(0).breaks());
        eq("切换次数不把观测空档算进去", 0, d.switches());

        // 高流速（/tick rate 200）下样本天然每 ~10 tick 一个：旧规则会把每个点都判成断点
        List<Sample> fast = List.of(
                new Sample(100, "minecraft:iron_ingot", 10),
                new Sample(110, "minecraft:iron_ingot", 74),
                new Sample(120, "minecraft:iron_ingot", 138));
        ChartData fd = ChartData.build(2L, fast);
        no("稀疏采样仍是连续折线（第二个点）", fd.series().get(0).points().get(1).breakBefore());
        eq("整条线只有 1 个断点", 1, fd.series().get(0).breaks());

        // 兜底：静默大空档（超过 MAX_SILENT_GAP）还是要断 ——
        // 万一有哪条停摆路径忘了写标记，也不让线横穿一大段空白
        List<Sample> silent = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                new Sample(2 + ChartData.MAX_SILENT_GAP + 1, "minecraft:iron_ingot", 12));
        ChartData sd = ChartData.build(3L, silent);
        yes("静默大空档兜底断开", sd.series().get(0).points().get(2).breakBefore());
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

    // ---- 第十轮：多格聚合（按物品分别求和，不再合成"混合"） ----

    // 8) 多个格子装同一种物品 → 那种物品 × 总数（"多格一起计算"）
    private static void t8AggregateSameItem() {
        List<Aggregator.Totals> r = Aggregator.combine(
                List.of("minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot"),
                List.of(64, 16, 1));
        eq("同物品：只出一条", 1, r.size());
        eq("同物品：类目不变", "minecraft:iron_ingot", r.get(0).itemId());
        eq("同物品：数量相加", 81, r.get(0).count());
        no("不是空", r.get(0).empty());

        // 空槽位（count=0）不参与判定，但也不影响总数
        List<Aggregator.Totals> r2 = Aggregator.combine(
                List.of("minecraft:iron_ingot", Sample.EMPTY_ITEM),
                List.of(32, 0));
        eq("带一个空格子：仍是一条", 1, r2.size());
        eq("带一个空格子：仍是铁", "minecraft:iron_ingot", r2.get(0).itemId());
        eq("带一个空格子：数量不变", 32, r2.get(0).count());
    }

    // 9) ★ 本轮语义修正的核心 ★
    //    多个格子装不同物品 → **各自一条**（不同物品仍然是图上的不同颜色线），
    //    而**不是**合成一条"混合物品"。用户实机反馈明确否掉了第一版的混合写法。
    private static void t9AggregateDifferentItemsStaySeparate() {
        List<Aggregator.Totals> r = Aggregator.combine(
                List.of("minecraft:iron_ingot", "minecraft:copper_ingot"),
                List.of(64, 1));
        eq("两种物品 → 两条", 2, r.size());
        eq("第一条是铁", "minecraft:iron_ingot", r.get(0).itemId());
        eq("铁 64", 64, r.get(0).count());
        eq("第二条是铜", "minecraft:copper_ingot", r.get(1).itemId());
        eq("铜 1", 1, r.get(1).count());

        // 同种物品分散在不相邻的格子里，也要合并到同一条
        List<Aggregator.Totals> r2 = Aggregator.combine(
                List.of("minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:iron_ingot"),
                List.of(10, 5, 20));
        eq("分散的铁合并成一条：共两条", 2, r2.size());
        eq("铁 = 10 + 20", 30, r2.get(0).count());
        eq("铜 = 5", 5, r2.get(1).count());
        eq("顺序 = 首次出现顺序", "minecraft:iron_ingot", r2.get(0).itemId());

        // 不能出现任何"混合"这种合成 id
        for (Aggregator.Totals t : r) {
            yes("不能是混合合成 id: " + t.itemId(), !"logiscope:mixed".equals(t.itemId()));
        }
    }

    // 10) 退化情形：全空 / 零长度 / 输出缓冲复用
    private static void t10AggregateDegenerate() {
        List<Aggregator.Totals> allEmpty = Aggregator.combine(
                List.of(Sample.EMPTY_ITEM, Sample.EMPTY_ITEM), List.of(0, 0));
        eq("全空 → 仍出一条（那条灰蓝的空槽线）", 1, allEmpty.size());
        eq("全空 → air", Sample.EMPTY_ITEM, allEmpty.get(0).itemId());
        eq("全空 → 0", 0, allEmpty.get(0).count());
        yes("全空 empty() 为真", allEmpty.get(0).empty());

        List<Aggregator.Totals> none = Aggregator.combine(List.of(), List.of());
        eq("零个槽位 → 出一条 air", 1, none.size());
        eq("零个槽位 → air", Sample.EMPTY_ITEM, none.get(0).itemId());

        // 数组版本与 List 版本必须完全一致（采集层每 tick 走的是数组版本）
        String[] ids = {"minecraft:iron_ingot", "minecraft:gold_ingot"};
        int[] counts = {5, 7};
        String[] outIds = new String[4];
        int[] outCounts = new int[4];
        int m = Aggregator.combine(ids, counts, 2, outIds, outCounts);
        List<Aggregator.Totals> lst = Aggregator.combine(List.of(ids[0], ids[1]), List.of(5, 7));
        eq("数组版条目数", lst.size(), m);
        eq("数组版类目一致", lst.get(0).itemId(), outIds[0]);
        eq("数组版数量一致", lst.get(0).count(), outCounts[0]);
        eq("数组版第二条类目一致", lst.get(1).itemId(), outIds[1]);

        // 输出缓冲是复用的：连续调两次不能残留上一次的内容
        int m2 = Aggregator.combine(new String[]{"minecraft:stone"}, new int[]{3}, 1, outIds, outCounts);
        eq("复用缓冲：第二次只有一条", 1, m2);
        eq("复用缓冲：内容正确", "minecraft:stone", outIds[0]);
        eq("复用缓冲：数量正确", 3, outCounts[0]);
    }

    // ---- 第十轮：一个 tick 多条样本时的断点规则（per-series） ----

    // 11) 同一 tick 有多个物品的样本时，每条线**不该在每个 tick 都断开**
    //     （旧的"全局上一个样本"判法会在这里把波形碎成一堆孤立点）
    private static void t11MultiItemTickNoFalseBreak() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(1, "minecraft:copper_ingot", 5),
                new Sample(2, "minecraft:iron_ingot", 12),
                new Sample(2, "minecraft:copper_ingot", 5),
                new Sample(3, "minecraft:iron_ingot", 14),
                new Sample(3, "minecraft:copper_ingot", 6));

        ChartData d = ChartData.build(1L, s);
        eq("两条线", 2, d.series().size());
        Series iron = d.byItem("minecraft:iron_ingot");
        Series copper = d.byItem("minecraft:copper_ingot");
        yes("铁线存在", iron != null);
        yes("铜线存在", copper != null);
        eq("铁 3 个点", 3, iron.points().size());
        yes("铁首点断开（契约）", iron.points().get(0).breakBefore());
        no("铁第 2 点连着", iron.points().get(1).breakBefore());
        no("铁第 3 点连着", iron.points().get(2).breakBefore());
        eq("铁只有 1 个断点（= 首点）", 1, iron.breaks());
        no("铜第 2 点连着", copper.points().get(1).breakBefore());
        eq("每条线的值各自独立", "10..14", iron.min() + ".." + iron.max());
        eq("样本条数（一个 tick 两条）", 6, d.dataCount());
        eq("物品集合没变 → switch 0", 0, d.switches());
        eq("时间轴 1..3", "1..3", d.firstTick() + ".." + d.lastTick());
    }

    // 12) 某个物品中途从"被监控的格子"里消失又出现 → 它那条线要断开
    private static void t12ItemVanishesThenReturns() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                new Sample(3, "minecraft:copper_ingot", 5),
                new Sample(4, "minecraft:copper_ingot", 6),
                new Sample(5, "minecraft:iron_ingot", 12));

        ChartData d = ChartData.build(1L, s);
        Series iron = d.byItem("minecraft:iron_ingot");
        eq("铁 3 个点", 3, iron.points().size());
        no("铁第 2 点连着", iron.points().get(1).breakBefore());
        yes("铁第 3 点必须断开（上一个数据 tick 里它在不在 = 不在）", iron.points().get(2).breakBefore());
        eq("铁 2 个断点", 2, iron.breaks());
        eq("物品集合变了两次 → switch 2", 2, d.switches());
    }

    // 13) no_data 之后自动断开（per-series 之后**不需要**任何特殊处理）
    private static void t13BreakAfterNoData() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                Sample.noData(3),
                new Sample(4, "minecraft:iron_ingot", 12));

        ChartData d = ChartData.build(1L, s);
        Series iron = d.byItem("minecraft:iron_ingot");
        eq("铁 3 个点", 3, iron.points().size());
        no("断档前不断", iron.points().get(1).breakBefore());
        yes("no_data 之后必须断开", iron.points().get(2).breakBefore());
        eq("no_data 不计入 switch", 0, d.switches());
        eq("no_data 样本数", 1, d.noDataCount());
        eq("时间轴仍推进到 4", 4L, d.lastTick());
    }

    // 14) Analyzer 按 tick 合计：min/max/avg 是"每个 tick 的总存量"，不是单条样本
    private static void t14SummaryAggregatesPerTick() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(1, "minecraft:copper_ingot", 5),
                new Sample(2, "minecraft:iron_ingot", 20),
                new Sample(2, "minecraft:copper_ingot", 5),
                Sample.noData(3),
                new Sample(4, "minecraft:iron_ingot", 7));

        Analyzer.Summary sum = Analyzer.summarize(s);
        eq("有数据的 tick 数", 3, sum.dataTicks());
        eq("no-data 的 tick 数", 1, sum.noDataTicks());
        eq("总存量最小值（7）", 7, sum.min());
        eq("总存量最大值（25）", 25, sum.max());
        eq("平均 (15+25+7)/3 = 15", 15, sum.average());
        eq("首 tick 总存量", 15, sum.first());
        eq("末 tick 总存量", 7, sum.last());
        eq("净变化", -8, sum.netChange());
        eq("出现最多的物品", "minecraft:iron_ingot", sum.dominantItem());
    }

    // ---- 第十轮第三次修复：中断标记（gap）与"观测空档"的区别 ----

    // 15) 用户按了暂停 → 记一条 gap 标记 → 恢复之后**必须断开**。
    //     同时：gap 不是"读失败"，不能混进 no-data 计数。
    private static void t15BreakOnGapMarker() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                Sample.gap(3),
                new Sample(60, "minecraft:iron_ingot", 9),
                new Sample(61, "minecraft:iron_ingot", 8));

        ChartData d = ChartData.build(1L, s);
        Series iron = d.byItem("minecraft:iron_ingot");
        eq("铁 4 个点", 4, iron.points().size());
        no("暂停前不断", iron.points().get(1).breakBefore());
        yes("gap 之后必须断开", iron.points().get(2).breakBefore());
        no("恢复之后自己连着", iron.points().get(3).breakBefore());
        eq("gap 不计入 no-data", 0, d.noDataCount());
        eq("gap 不算物品切换", 0, d.switches());
        eq("时间轴推进到恢复点", 61L, d.lastTick());

        // 只有标记、没有任何数据 → 图上没有线，但也不算"有数据"
        ChartData only = ChartData.build(2L, List.of(Sample.gap(1), Sample.gap(2)));
        no("只有 gap 时 hasData 为假", only.hasData());
        eq("只有 gap 时没有数据线", 0, only.series().size());
    }

    // 16) 标记对统计是透明的：不占 min/max/avg 的样本，也不破坏"首尾净变化"
    private static void t16MarkersAreTransparentToStats() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                Sample.gap(2),
                Sample.noData(3),
                new Sample(4, "minecraft:iron_ingot", 30));

        Analyzer.Summary sum = Analyzer.summarize(s);
        eq("有数据的 tick 只有 2 个", 2, sum.dataTicks());
        eq("no-data 只数真读失败的（gap 不算）", 1, sum.noDataTicks());
        eq("min 只看数据", 10, sum.min());
        eq("max 只看数据", 30, sum.max());
        eq("avg = (10+30)/2", 20, sum.average());
        eq("净变化 = 30 - 10", 20, sum.netChange());
    }

    // 17) 多格聚合下某一种物品被抽空 → 它那条线断（但别的线不受影响）
    private static void t17SeriesDropoutInMultiSlot() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(1, "minecraft:copper_ingot", 5),
                new Sample(2, "minecraft:iron_ingot", 12),
                new Sample(2, "minecraft:copper_ingot", 5),
                // 铜那一格被抽空了：这个 tick 只剩铁
                new Sample(3, "minecraft:iron_ingot", 14),
                new Sample(4, "minecraft:iron_ingot", 16),
                // 铜又来了
                new Sample(5, "minecraft:iron_ingot", 18),
                new Sample(5, "minecraft:copper_ingot", 7));

        ChartData d = ChartData.build(1L, s);
        Series iron = d.byItem("minecraft:iron_ingot");
        Series copper = d.byItem("minecraft:copper_ingot");
        eq("铁 5 个点", 5, iron.points().size());
        for (int i = 1; i < 5; i++) {
            no("铁第 " + (i + 1) + " 点一直连着（它每个数据 tick 都在）", iron.points().get(i).breakBefore());
        }
        eq("铜 3 个点", 3, copper.points().size());
        yes("铜首点断（契约）", copper.points().get(0).breakBefore());
        no("铜第 2 点连着", copper.points().get(1).breakBefore());
        yes("铜消失两 tick 后回来 → 必须断开", copper.points().get(2).breakBefore());
    }

    // 18) 面板读数用的"时间轴最后一格"（SampleStore#latestTick）
    private static void t18LatestTick() {
        SampleStore st = new SampleStore(8);
        yes("空 store → null", st.latestTick() == null);

        st.add(new Sample(1, "minecraft:iron_ingot", 10));
        st.add(new Sample(2, "minecraft:iron_ingot", 11));
        st.add(new Sample(2, "minecraft:copper_ingot", 5));
        List<Sample> two = st.latestTick();
        eq("最后一格两条（一个 tick 一个物品一条）", 2, two.size());
        eq("第一条 = 写入顺序", "minecraft:iron_ingot", two.get(0).itemId());
        eq("第二条 = 写入顺序", "minecraft:copper_ingot", two.get(1).itemId());

        // 标记不是数据：这一刻没有值，不能拿上一格的值冒充
        st.add(Sample.gap(3));
        yes("最后一格是 gap → null", st.latestTick() == null);
        st.add(Sample.noData(4));
        yes("最后一格是 no_data → null", st.latestTick() == null);
        st.add(new Sample(5, "minecraft:stone", 7));
        eq("标记之后重新有数据", 1, st.latestTick().size());
        eq("内容正确", "minecraft:stone", st.latestTick().get(0).itemId());

        // 环形覆盖之后仍然只认"最新的那一格"
        SampleStore small = new SampleStore(3);
        small.add(new Sample(1, "minecraft:iron_ingot", 1));
        small.add(new Sample(2, "minecraft:iron_ingot", 2));
        small.add(new Sample(3, "minecraft:iron_ingot", 3));
        small.add(new Sample(4, "minecraft:iron_ingot", 4));
        eq("覆盖后只剩最新一条", 1, small.latestTick().size());
        eq("覆盖后是新值", 4, small.latestTick().get(0).count());
    }

    // ---- 第十轮第五次修复：单 tick IO（净流量） ----

    // 19) 流量 = 相邻两格的存量差；跨格 / 断档 / 首格都不产生流量
    private static void t19FlowBasics() {
        // 存量在涨
        List<Sample> up = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 14),
                new Sample(3, "minecraft:iron_ingot", 12));
        Series a = ChartData.build(1L, up).byItem("minecraft:iron_ingot");
        eq("首格没有流量（没有上一格）", 2, a.flow().size());
        eq("第二格 +4", 4, a.flow().get(0).count());
        eq("第三格 -2（流出）", -2, a.flow().get(1).count());
        yes("流量的第一个点是段起点", a.flow().get(0).breakBefore());
        no("后面接着", a.flow().get(1).breakBefore());
        eq("流量范围（含负数）", "-2..4", a.flowMin() + ".." + a.flowMax());
        eq("最近一次流量", -2, a.lastFlow());

        // 中间少观测一格（1 → 2 → 4）：存量线连着，但那一格的流量归不出来
        List<Sample> sparse = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                new Sample(4, "minecraft:iron_ingot", 13),
                new Sample(5, "minecraft:iron_ingot", 14));
        Series b = ChartData.build(2L, sparse).byItem("minecraft:iron_ingot");
        eq("存量线不断（4 个点）", 4, b.points().size());
        eq("流量只有 2 个点（跨格那两格没有流量）", 2, b.flow().size());
        eq("第一段流量 +1", 1, b.flow().get(0).count());
        eq("第二段流量 +1（tick 5）", 1, b.flow().get(1).count());
        yes("跨格之后的新段起点", b.flow().get(1).breakBefore());

        // no_data / gap 之后同样没有流量
        List<Sample> broken = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 12),
                Sample.noData(3),
                new Sample(4, "minecraft:iron_ingot", 20));
        Series c = ChartData.build(3L, broken).byItem("minecraft:iron_ingot");
        eq("断档后不产生流量", 1, c.flow().size());
        eq("断档前那格 +2", 2, c.flow().get(0).count());
    }

    // 20) 框选区间里的流入 / 流出合计（进和出都很快时，净变化看不出来的东西）
    private static void t20FlowInRangeStats() {
        // 100 → 90 → 90 → 110：净 +10，但中间有 −10 与 +20
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 100),
                new Sample(2, "minecraft:iron_ingot", 90),
                new Sample(3, "minecraft:iron_ingot", 90),
                new Sample(4, "minecraft:iron_ingot", 110));
        ChartData d = ChartData.build(1L, s);
        List<ChartData.SeriesRange> all = d.ranges(0, 100);
        eq("只有一条线", 1, all.size());
        ChartData.SeriesRange r = all.get(0);
        eq("净变化 +10", 10, r.delta());
        eq("流入合计 +20", 20, r.inflow());
        eq("流出合计 -10", -10, r.outflow());
        eq("可算流量的 tick 数 3", 3, r.flowSamples());

        // 区间只取 tick 3 那一格：那里恰好没有变化
        ChartData.SeriesRange flat = d.ranges(3, 3).get(0);
        eq("区间内流量点 1 个", 1, flat.flowSamples());
        eq("那一格流入 0", 0, flat.inflow());
        eq("那一格流出 0", 0, flat.outflow());

        // 区间只取 tick 2 那一格：−10 落在这里
        ChartData.SeriesRange drop = d.ranges(2, 2).get(0);
        eq("区间内流量点 1 个", 1, drop.flowSamples());
        eq("流入 0", 0, drop.inflow());
        eq("流出 -10", -10, drop.outflow());
    }

    // 21) 物品种类上限：超出的种类照样进统计，只是不画线
    private static void t21KindCap() {
        List<Sample> s = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(1, "minecraft:copper_ingot", 5),
                new Sample(2, "minecraft:iron_ingot", 11),
                new Sample(2, "minecraft:copper_ingot", 6),
                new Sample(2, "minecraft:gold_ingot", 100));

        ChartData cap1 = ChartData.build(1L, s, 1);
        eq("只画 1 种", 1, cap1.series().size());
        eq("画的是首次出现的那种", "minecraft:iron_ingot", cap1.series().get(0).itemId());
        eq("未显示的种类数", 2, cap1.droppedKinds());
        eq("数据照样全记（3 tick 里 5 条样本）", 5, cap1.dataCount());
        eq("全局最大值仍然包含未显示的种类", 100, cap1.maxValue());

        ChartData capAll = ChartData.build(2L, s, 256);
        eq("上限够大时三种都画", 3, capAll.series().size());
        eq("没有未显示的", 0, capAll.droppedKinds());

        ChartData unlimited = ChartData.build(3L, s);
        eq("不带上限的重载 = 全画", 3, unlimited.series().size());
    }

    // 22) 缓冲容量热切换（配置改了不重启）
    private static void t22StoreResize() {
        SampleStore st = new SampleStore(4);
        for (int i = 1; i <= 4; i++) {
            st.add(new Sample(i, "minecraft:iron_ingot", i));
        }
        eq("写满", 4, st.size());
        long v0 = st.version();
        st.resize(2);
        eq("缩小后容量", 2, st.capacity());
        eq("缩小后保留 2 条", 2, st.size());
        eq("保留的是最新的两条", "3..4", st.toList().get(0).count() + ".." + st.toList().get(1).count());
        yes("版本号变了（界面会重算）", st.version() != v0);

        st.resize(6);
        eq("放大后容量", 6, st.capacity());
        eq("放大后条数不变", 2, st.size());
        st.add(new Sample(5, "minecraft:iron_ingot", 5));
        eq("继续可写", 3, st.size());
        eq("最新一条", 5, st.latestTick().get(0).count());

        st.resize(2);
        eq("再缩小仍然只留最新", 2, st.size());
        eq("最新还是那两条", "4..5", st.toList().get(0).count() + ".." + st.toList().get(1).count());
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
