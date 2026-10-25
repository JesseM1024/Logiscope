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
import com.jessetech.logiscope.analysis.AeKeyText;
import com.jessetech.logiscope.analysis.Aggregator;
import com.jessetech.logiscope.analysis.Analyzer;
import com.jessetech.logiscope.analysis.ChartData;
import com.jessetech.logiscope.analysis.CurveMath;
import com.jessetech.logiscope.analysis.NbtSlotLayout;
import com.jessetech.logiscope.analysis.PickerFilter;
import com.jessetech.logiscope.analysis.RectRuns;
import com.jessetech.logiscope.analysis.RsKeyText;
import com.jessetech.logiscope.analysis.SlotAlign;
import com.jessetech.logiscope.analysis.WaveColumns;
import com.jessetech.logiscope.analysis.RectRuns;
import com.jessetech.logiscope.analysis.WaveColumns;
import com.jessetech.logiscope.analysis.Snbt;
import com.jessetech.logiscope.analysis.CurveMath;
import com.jessetech.logiscope.analysis.Series;
import com.jessetech.logiscope.analysis.Val;
import com.jessetech.logiscope.analysis.SeriesPoint;
import com.jessetech.logiscope.archive.ArchiveData;
import com.jessetech.logiscope.archive.ArchiveFormat;
import com.jessetech.logiscope.bind.DataTarget;
import com.jessetech.logiscope.collect.DataProbe;
import com.jessetech.logiscope.store.MarkerList;
import com.jessetech.logiscope.store.Sample;
import com.jessetech.logiscope.store.SampleStore;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

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
        t21KindCap();
        t23Markers();
        t24ArchiveFormat();
        t25CurveMath();
        t26Snbt();
        t27Offsets();
        t28CombineAll();
        t29RenderGeometry();
        t30NbtSlotLayout();
        t31SlotMatchById();
        t33SlotResolve();
        t34FlowStats();
        try {
            t35ArchiveHeader();
        } catch (Exception e) {
            failed++;
            System.out.println("  FAIL t35 抛异常: " + e);
        }
        t36NumberGuards();
        t37Val();
        t38AeTargets();
        t39RiseFallEvents();
        t40ForcedNumeric();
        t41PickerFilter();
        t42AeKeyText();
        t43RsKeyText();
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
        eq("最后读到的铁数量", 9, d.series().get(0).lastCount().asDouble(), 1e-9);
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
        eq("空格子数量恒为 0", 0, air.max().asDouble(), 1e-9);
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
        eq("区间首值", 20, iron.first().asDouble(), 1e-9);
        eq("区间末值", 60, iron.last().asDouble(), 1e-9);
        eq("净变化", 40, iron.delta().asDouble(), 1e-9);
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
        eq("全局最小", 3, d.minValue().asDouble(), 1e-9);
        eq("全局最大", 71, d.maxValue().asDouble(), 1e-9);
        yes("有数据", d.hasData());

        List<Sample> only = List.of(Sample.noData(1), Sample.noData(2));
        ChartData n = ChartData.build(2L, only);
        no("全 no_data 时 hasData 为假", n.hasData());
        eq("全 no_data 时 min 归零而不是 MAX_VALUE", 0, n.minValue().asDouble(), 1e-9);
        eq("全 no_data 时 max 归零", 0, n.maxValue().asDouble(), 1e-9);
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
                List.of(Val.of(64), Val.of(16), Val.of(1)));
        eq("同物品：只出一条", 1, r.size());
        eq("同物品：类目不变", "minecraft:iron_ingot", r.get(0).itemId());
        eq("同物品：数量相加", 81, r.get(0).count().asDouble(), 1e-9);
        no("不是空", r.get(0).empty());

        // 空槽位（count=0）不参与判定，但也不影响总数
        List<Aggregator.Totals> r2 = Aggregator.combine(
                List.of("minecraft:iron_ingot", Sample.EMPTY_ITEM),
                List.of(Val.of(32), Val.of(0)));
        eq("带一个空格子：仍是一条", 1, r2.size());
        eq("带一个空格子：仍是铁", "minecraft:iron_ingot", r2.get(0).itemId());
        eq("带一个空格子：数量不变", 32, r2.get(0).count().asDouble(), 1e-9);
    }

    // 9) ★ 本轮语义修正的核心 ★
    //    多个格子装不同物品 → **各自一条**（不同物品仍然是图上的不同颜色线），
    //    而**不是**合成一条"混合物品"。用户实机反馈明确否掉了第一版的混合写法。
    private static void t9AggregateDifferentItemsStaySeparate() {
        List<Aggregator.Totals> r = Aggregator.combine(
                List.of("minecraft:iron_ingot", "minecraft:copper_ingot"),
                List.of(Val.of(64), Val.of(1)));
        eq("两种物品 → 两条", 2, r.size());
        eq("第一条是铁", "minecraft:iron_ingot", r.get(0).itemId());
        eq("铁 64", 64, r.get(0).count().asDouble(), 1e-9);
        eq("第二条是铜", "minecraft:copper_ingot", r.get(1).itemId());
        eq("铜 1", 1, r.get(1).count().asDouble(), 1e-9);

        // 同种物品分散在不相邻的格子里，也要合并到同一条
        List<Aggregator.Totals> r2 = Aggregator.combine(
                List.of("minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:iron_ingot"),
                List.of(Val.of(10), Val.of(5), Val.of(20)));
        eq("分散的铁合并成一条：共两条", 2, r2.size());
        eq("铁 = 10 + 20", 30, r2.get(0).count().asDouble(), 1e-9);
        eq("铜 = 5", 5, r2.get(1).count().asDouble(), 1e-9);
        eq("顺序 = 首次出现顺序", "minecraft:iron_ingot", r2.get(0).itemId());

        // 不能出现任何"混合"这种合成 id
        for (Aggregator.Totals t : r) {
            yes("不能是混合合成 id: " + t.itemId(), !"logiscope:mixed".equals(t.itemId()));
        }
    }

    // 10) 退化情形：全空 / 零长度 / 输出缓冲复用
    private static void t10AggregateDegenerate() {
        List<Aggregator.Totals> allEmpty = Aggregator.combine(
                List.of(Sample.EMPTY_ITEM, Sample.EMPTY_ITEM), List.of(Val.of(0), Val.of(0)));
        eq("全空 → 仍出一条（那条灰蓝的空槽线）", 1, allEmpty.size());
        eq("全空 → air", Sample.EMPTY_ITEM, allEmpty.get(0).itemId());
        eq("全空 → 0", 0, allEmpty.get(0).count().asDouble(), 1e-9);
        yes("全空 empty() 为真", allEmpty.get(0).empty());

        List<Aggregator.Totals> none = Aggregator.combine(List.of(), List.of());
        eq("零个槽位 → 出一条 air", 1, none.size());
        eq("零个槽位 → air", Sample.EMPTY_ITEM, none.get(0).itemId());

        // 数组版本与 List 版本必须完全一致（采集层每 tick 走的是数组版本）
        String[] ids = {"minecraft:iron_ingot", "minecraft:gold_ingot"};
        Val[] counts = {Val.of(5), Val.of(7)};
        String[] outIds = new String[4];
        Val[] outCounts = new Val[4];
        int m = Aggregator.combine(ids, counts, 2, outIds, outCounts);
        List<Aggregator.Totals> lst = Aggregator.combine(List.of(ids[0], ids[1]), List.of(Val.of(5), Val.of(7)));
        eq("数组版条目数", lst.size(), m);
        eq("数组版类目一致", lst.get(0).itemId(), outIds[0]);
        eq("数组版数量一致", lst.get(0).count().asDouble(), outCounts[0].asDouble(), 1e-9);
        eq("数组版第二条类目一致", lst.get(1).itemId(), outIds[1]);

        // 输出缓冲是复用的：连续调两次不能残留上一次的内容
        int m2 = Aggregator.combine(new String[]{"minecraft:stone"}, new Val[]{Val.of(3)}, 1, outIds, outCounts);
        eq("复用缓冲：第二次只有一条", 1, m2);
        eq("复用缓冲：内容正确", "minecraft:stone", outIds[0]);
        eq("复用缓冲：数量正确", 3, outCounts[0].asDouble(), 1e-9);
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
        eq("总存量最小值（7）", 7, sum.min().asDouble(), 1e-9);
        eq("总存量最大值（25）", 25, sum.max().asDouble(), 1e-9);
        eq("平均 (15+25+7)/3 = 15.67（不再截断）", 47.0 / 3, sum.average().asDouble(), 1e-9);
        eq("首 tick 总存量", 15, sum.first().asDouble(), 1e-9);
        eq("末 tick 总存量", 7, sum.last().asDouble(), 1e-9);
        eq("净变化", -8, sum.netChange().asDouble(), 1e-9);
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
        eq("min 只看数据", 10, sum.min().asDouble(), 1e-9);
        eq("max 只看数据", 30, sum.max().asDouble(), 1e-9);
        eq("avg = (10+30)/2", 20, sum.average().asDouble(), 1e-9);
        eq("净变化 = 30 - 10", 20, sum.netChange().asDouble(), 1e-9);
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
        eq("覆盖后是新值", 4, small.latestTick().get(0).value().asDouble(), 1e-9);
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
        eq("第二格 +4", 4, a.flow().get(0).value().asDouble(), 1e-9);
        eq("第三格 -2（流出）", -2, a.flow().get(1).value().asDouble(), 1e-9);
        yes("流量的第一个点是段起点", a.flow().get(0).breakBefore());
        no("后面接着", a.flow().get(1).breakBefore());
        eq("流量范围（含负数）", "-2..4", a.flowMin() + ".." + a.flowMax());
        eq("最近一次流量", -2, a.lastFlow().asDouble(), 1e-9);

        // 中间少观测一格（1 → 2 → 4）：存量线连着，但那一格的流量归不出来
        List<Sample> sparse = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 11),
                new Sample(4, "minecraft:iron_ingot", 13),
                new Sample(5, "minecraft:iron_ingot", 14));
        Series b = ChartData.build(2L, sparse).byItem("minecraft:iron_ingot");
        eq("存量线不断（4 个点）", 4, b.points().size());
        eq("流量只有 2 个点（跨格那两格没有流量）", 2, b.flow().size());
        eq("第一段流量 +1", 1, b.flow().get(0).value().asDouble(), 1e-9);
        eq("第二段流量 +1（tick 5）", 1, b.flow().get(1).value().asDouble(), 1e-9);
        yes("跨格之后的新段起点", b.flow().get(1).breakBefore());

        // no_data / gap 之后同样没有流量
        List<Sample> broken = List.of(
                new Sample(1, "minecraft:iron_ingot", 10),
                new Sample(2, "minecraft:iron_ingot", 12),
                Sample.noData(3),
                new Sample(4, "minecraft:iron_ingot", 20));
        Series c = ChartData.build(3L, broken).byItem("minecraft:iron_ingot");
        eq("断档后不产生流量", 1, c.flow().size());
        eq("断档前那格 +2", 2, c.flow().get(0).value().asDouble(), 1e-9);
    }

    // 20) 已删除：框选区间的流入 / 流出合计。UI 不再显示 in/out（Δ 已经表达同一件事），
    //     SeriesRange 里的 inflow/outflow/flowSamples 一并删掉了（第十轮⑥）。
    //     流量本身的正确性仍由 t19 兜着。

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
        eq("全局最大值仍然包含未显示的种类", 100, cap1.maxValue().asDouble(), 1e-9);

        ChartData capAll = ChartData.build(2L, s, 256);
        eq("上限够大时三种都画", 3, capAll.series().size());
        eq("没有未显示的", 0, capAll.droppedKinds());

        ChartData unlimited = ChartData.build(3L, s);
        eq("不带上限的重载 = 全画", 3, unlimited.series().size());
    }

    // 22) 缓冲容量热切换（配置改了不重启）
    // 38) AE 网络目标（第十六轮⑧）：valuePath 是标记不是路径，去重按物品 id
    private static void t38AeTargets() {
        DataTarget ae = DataTarget.aeNetwork("minecraft:stone", "terminal", null, null);
        eq("AE 判定", true, ae.isAeNetwork());
        eq("AE 值路径是标记", "ae:network", ae.valuePath());
        eq("AE 不是槽位锚点", false, ae.isSlotAnchor());
        eq("AE 显示", "ae:network[minecraft:stone]", ae.displayValue());
        eq("AE fallbackName = 物品 id", "minecraft:stone", ae.fallbackName());

        // 去重：身份 = 物品 registry id（valuePath 全是 ae:network，绝不能拿它去重）
        List<DataTarget> list = new ArrayList<>();
        list.add(DataTarget.aeNetwork("minecraft:stone", "t", null, null));
        eq("AE 同物品重复", true, DataTarget.isDuplicate(list,
                DataTarget.aeNetwork("minecraft:stone", "t", null, null)));
        eq("AE 异物品不重复", false, DataTarget.isDuplicate(list,
                DataTarget.aeNetwork("minecraft:iron_ingot", "t", null, null)));

        // 与另外两类目标互不干扰
        eq("AE 与锚点不互判", false, DataTarget.isDuplicate(list,
                DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 0, null)));
        // 用户手输 "ae:network" 当普通路径（罕见）：不与 AE 目标互判重复，
        // 但它会被读值分流当成 AE 目标 —— 已写进文档的保留路径
        DataTarget manual = new DataTarget("ae:network", null, "x", "t", null, null);
        eq("手输 ae:network 路径与 AE 目标不互判", false, DataTarget.isDuplicate(list, manual));

        // 普通路径仍按 valuePath 去重（回归）
        List<DataTarget> plain = new ArrayList<>();
        plain.add(new DataTarget("energy", null, "e", "t", null, null));
        eq("普通路径按 valuePath 去重", true, DataTarget.isDuplicate(plain,
                new DataTarget("energy", null, "e", "t", null, null)));
        eq("普通路径不同值不重复", false, DataTarget.isDuplicate(plain,
                new DataTarget("energy2", null, "e", "t", null, null)));

        // 与锚点去重（回归：同 slot 重复）
        List<DataTarget> anchors = new ArrayList<>();
        anchors.add(DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 0, null));
        eq("锚点同 slot 重复", true, DataTarget.isDuplicate(anchors,
                DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 0, null)));
        eq("锚点异 slot 不重复", false, DataTarget.isDuplicate(anchors,
                DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 1, null)));
    }

    // 39) 上升/下降事件（第十七轮①）："变更区间"的单向过滤版
    //     —— 只收增/减方向的事件；周期相应变成"相邻两次同向事件"的间隔
    private static void t39RiseFallEvents() {
        // 有升有降的一条线：1 → 3 → 2 → 5（Δ = +2, -1, +3）
        List<SeriesPoint> mixed = List.of(
                new SeriesPoint(1, 1, true),
                new SeriesPoint(2, 3, false),
                new SeriesPoint(3, 2, false),
                new SeriesPoint(4, 5, false));

        CurveMath.Events rise = CurveMath.changeRiseEvents(mixed);
        eq("上升事件 2 次（+2 / +3）", 2, rise.count());
        eq("上升量 mean 2.5", 2.5, rise.dMean(), 1e-9);
        // 偏差 ±0.5 → (0.25 + 0.25) / 2 = 0.25
        eq("上升量 var 0.25", 0.25, rise.dVar(), 1e-9);
        eq("上升周期 mean 2t（t2 → t4）", 2.0, rise.pMean(), 1e-9);
        eq("上升周期 var 0（只一次间隔）", 0.0, rise.pVar(), 1e-9);

        CurveMath.Events fall = CurveMath.changeFallEvents(mixed);
        eq("下降事件 1 次（-1）", 1, fall.count());
        eq("下降量 mean -1", -1.0, fall.dMean(), 1e-9);
        // 单事件：var = 0（"没有离散可言"）而周期无定义 → NaN —— 两者不能混
        eq("下降量 var 0（不是 NaN）", 0.0, fall.dVar(), 1e-9);
        yes("单事件 → 周期 mean NaN", Double.isNaN(fall.pMean()));
        yes("单事件 → 周期 var NaN", Double.isNaN(fall.pVar()));
        // 不变量：上 + 下 = 全部变更（同一份 Δ 只有正负两种去向，没有第三种）
        eq("上 + 下 = 全部变更", 3, rise.count() + fall.count());

        // 全升线（= 用户给的 7 7 8 8 9 例子）：Δ = 0, +1, 0, +1 —— 相等的差值不算事件
        List<SeriesPoint> onlyUp = List.of(
                new SeriesPoint(10, 7, true),
                new SeriesPoint(11, 7, false),
                new SeriesPoint(13, 8, false),
                new SeriesPoint(14, 8, false),
                new SeriesPoint(15, 9, false));
        eq("全升线：上升 2 次", 2, CurveMath.changeRiseEvents(onlyUp).count());
        eq("全升线：下降 0 次", 0, CurveMath.changeFallEvents(onlyUp).count());
        yes("零事件 → 变化量 mean NaN", Double.isNaN(CurveMath.changeFallEvents(onlyUp).dMean()));

        // 断档段：跨档的差不是事件（与 changeEvents / 变化总量同口径）
        List<SeriesPoint> broken = List.of(
                new SeriesPoint(1, 10, true),
                new SeriesPoint(2, 12, false),
                new SeriesPoint(5, 20, true),    // 断档：10 → 20 的 +8 谁也不收
                new SeriesPoint(6, 18, false));
        CurveMath.Events br = CurveMath.changeRiseEvents(broken);
        eq("断档段：上升 1 次（+2；跨档 +8 不算）", 1, br.count());
        eq("断档段：上升量 mean 2", 2.0, br.dMean(), 1e-9);
        CurveMath.Events bf = CurveMath.changeFallEvents(broken);
        eq("断档段：下降 1 次（-2）", 1, bf.count());
        eq("断档段：上 + 下 = 全部变更", 2, br.count() + bf.count());

        // 小数值（Val 的非整数形态）照样按符号过滤
        List<SeriesPoint> frac = List.of(
                new SeriesPoint(1, Val.of(1.5), true),
                new SeriesPoint(2, Val.of(1.2), false),   // Δ = -0.3
                new SeriesPoint(3, Val.of(2.0), false));  // Δ = +0.8
        eq("小数线：上升 1 次（1.2 → 2.0）", 1, CurveMath.changeRiseEvents(frac).count());
        eq("小数线：上升量 mean 0.8", 0.8, CurveMath.changeRiseEvents(frac).dMean(), 1e-9);
        eq("小数线：下降 1 次（1.5 → 1.2）", 1, CurveMath.changeFallEvents(frac).count());

        // IO 线：流量点本身就是 Δ；段起点那一笔照算（不按 breakBefore 过滤，同 flowEvents 口径）
        List<SeriesPoint> flow = List.of(
                new SeriesPoint(2, 64, true),
                new SeriesPoint(3, 64, false),
                new SeriesPoint(4, 0, false),
                new SeriesPoint(5, -10, false),
                new SeriesPoint(8, 30, true));
        CurveMath.Events fir = CurveMath.flowRiseEvents(flow);
        eq("IO 上升 3 次（64 / 64 / 30；0 不算）", 3, fir.count());
        eq("IO 上升量 mean 158/3", 158 / 3.0, fir.dMean(), 1e-9);
        // 偏差 34/3 与 -68/3 → (1156/9 × 2 + 4624/9) / 3 = 2312/9
        eq("IO 上升量 var 2312/9", 2312 / 9.0, fir.dVar(), 1e-9);
        eq("IO 上升周期 mean 3t（t2→t3 = 1，t3→t8 = 5）", 3.0, fir.pMean(), 1e-9);
        eq("IO 上升周期 var 4", 4.0, fir.pVar(), 1e-9);

        CurveMath.Events fif = CurveMath.flowFallEvents(flow);
        eq("IO 下降 1 次（-10）", 1, fif.count());
        eq("IO 下降量 mean -10", -10.0, fif.dMean(), 1e-9);
        eq("IO 上 + 下 = 全部事件", CurveMath.flowEvents(flow).count(),
                fir.count() + fif.count());

        // 全升 IO 线：下降侧是空集
        List<SeriesPoint> upOnly = List.of(
                new SeriesPoint(1, 5, true),
                new SeriesPoint(2, 8, false));
        eq("IO 全升：下降 0 次", 0, CurveMath.flowFallEvents(upOnly).count());

        // 单点序列：没有任何相邻差 —— 两个方向都是 0 事件
        List<SeriesPoint> single = List.of(new SeriesPoint(1, 42, true));
        eq("单点序列：上升 0 次", 0, CurveMath.changeRiseEvents(single).count());
        eq("单点序列：下降 0 次", 0, CurveMath.changeFallEvents(single).count());
    }

    /**
     * t40：DATA 页「强制按数值读取」的文本解析。
     *
     * 需求：有些模组把数值存成**字符串** NBT；右键标记后读取时尝试解析，
     * 不可转换（任意文本 / NaN / 超 long 的纯整数）→ null（上层记 no-data）。
     * 这里钉的是 {@link Val#parseNumericText}（纯逻辑）与 {@link DataProbe#asValForced}
     * （标签层分发；离线 classpath 里本来就有 merged jar，可以用游戏类）。
     */
    private static void t40ForcedNumeric() {
        // —— 文本解析：整数必须精确（走 long） ——
        Val a = Val.parseNumericText("64");
        yes("'64' 解析为整数形式", a != null && a.isIntegral());
        eq("'64' 的值", 64L, a == null ? null : a.longValue());
        Val b = Val.parseNumericText("  64  ");
        eq("首尾空白被 trim", 64L, b == null ? null : b.longValue());
        eq("Long.MAX 精确", 9223372036854775807L,
                Val.parseNumericText("9223372036854775807").longValue());
        eq("负数走整数", -2L, Val.parseNumericText("-2").longValue());

        // —— 小数 / 科学计数走 double ——
        eq("'3.5' 小数", 3.5, Val.parseNumericText("3.5").asDouble(), 1e-9);
        no("'3.5' 非整数形式", Val.parseNumericText("3.5").isIntegral());
        eq("'1e3' = 1000", 1000.0, Val.parseNumericText("1e3").asDouble(), 1e-9);

        // —— 诚实边界：纯整数但超出 long → 拒绝（宁 no-data，不近似成 double） ——
        eq("超 long 的纯整数 → null", null, Val.parseNumericText("12345678901234567890"));

        // —— 不可转换 ——
        eq("任意文本 → null", null, Val.parseNumericText("abc"));
        eq("空串 → null", null, Val.parseNumericText(""));
        eq("null → null", null, Val.parseNumericText(null));
        eq("'NaN' → null", null, Val.parseNumericText("NaN"));
        eq("'Infinity' → null", null, Val.parseNumericText("Infinity"));

        // —— 标签层：强制模式 = 数值标签原样 + 字符串尝试解析；其余类型读不到 ——
        eq("StringTag(\"64\") 强制转换", 64L,
                DataProbe.asValForced(StringTag.valueOf("64")).longValue());
        eq("StringTag(\"abc\") → null", null, DataProbe.asValForced(StringTag.valueOf("abc")));
        eq("IntTag 照常（本来就是数值）", 7L, DataProbe.asValForced(IntTag.valueOf(7)).longValue());
        eq("ListTag → null（类型不可转换）", null, DataProbe.asValForced(new ListTag()));
    }

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
        eq("保留的是最新的两条", "3..4", st.toList().get(0).value().i() + ".." + st.toList().get(1).value().i());
        yes("版本号变了（界面会重算）", st.version() != v0);

        st.resize(6);
        eq("放大后容量", 6, st.capacity());
        eq("放大后条数不变", 2, st.size());
        st.add(new Sample(5, "minecraft:iron_ingot", 5));
        eq("继续可写", 3, st.size());
        eq("最新一条", 5, st.latestTick().get(0).value().asDouble(), 1e-9);

        st.resize(2);
        eq("再缩小仍然只留最新", 2, st.size());
        eq("最新还是那两条", "4..5", st.toList().get(0).value().i() + ".." + st.toList().get(1).value().i());
    }

    // 23) 波形标记（G4）：升序 / 同 tick 去重 / 删除
    private static void t23Markers() {
        MarkerList m = new MarkerList();
        yes("空表无标记", m.toList().isEmpty());
        yes("加 300", m.add(300));
        yes("加 100", m.add(100));
        yes("加 200", m.add(200));
        no("同 tick 再加被忽略", m.add(200));
        eq("始终升序 3 个", "100,200,300",
                m.toList().get(0) + "," + m.toList().get(1) + "," + m.toList().get(2));
        yes("contains 命中", m.contains(200));
        no("contains 不命中", m.contains(250));
        yes("删 200", m.remove(200));
        no("删不存在的返回 false", m.remove(200));
        eq("删完剩 2 个", 2, m.size());
        m.clear();
        eq("clear 后为空", 0, m.size());
    }

    // 24) 存档格式（G5）：CSV 的标记行 / JSON 的往返
    private static void t24ArchiveFormat() {
        List<Sample> samples = List.of(
                new Sample(100, "minecraft:iron_ingot", 64),
                Sample.noData(101),
                new Sample(102, Sample.EMPTY_ITEM, 0),
                Sample.gap(103));
        ArchiveData d = new ArchiveData("2026-09-18_22-43-05", "2026-09-18 22:43:05", "Test World",
                "barrel", "槽 0 · overworld 10,64,10", 100, 103,
                List.of(101L, 102L), samples);

        // CSV：数据行照常；no_data / gap 是标记行，count 留空（绝不写成 0）
        // 头部 5 行：存档名 / created / probe / 列说明 / no_data 说明
        List<String> csv = ArchiveFormat.csvLines(d);
        eq("CSV 数据行", "100,minecraft:iron_ingot,64", csv.get(5));
        eq("CSV no_data 标记行 count 留空", "101,logiscope:no_data,", csv.get(6));
        eq("CSV 空槽行 count=0 照写", "102,minecraft:air,0", csv.get(7));
        eq("CSV gap 标记行 count 留空", "103,logiscope:gap,", csv.get(8));
        eq("CSV 标记注释行 1", "# marker 101", csv.get(9));
        eq("CSV 标记注释行 2", "# marker 102", csv.get(10));

        // JSON：写出去再读回来，逐字段相等（可扩展性的基础：读方逐字段取值 + 默认值）
        ArchiveData back = ArchiveFormat.fromJson(ArchiveFormat.toJson(d));
        yes("JSON 往返不为空", back != null);
        if (back != null) {
            eq("name 往返", d.name(), back.name());
            eq("world 往返", d.world(), back.world());
            eq("probeName 往返", d.probeName(), back.probeName());
            eq("first/last 往返", d.firstTick() + ".." + d.lastTick(),
                    back.firstTick() + ".." + back.lastTick());
            eq("markers 往返 2 个", 2, back.markers().size());
            eq("samples 往返 4 条", 4, back.samples().size());
            eq("数据样本往返", "100,minecraft:iron_ingot,64",
                    back.samples().get(0).tick() + "," + back.samples().get(0).itemId()
                            + "," + back.samples().get(0).value().i());
            yes("no_data 往返保种类", back.samples().get(1).isNoData());
            yes("gap 往返保种类", back.samples().get(3).isGap());
        }
    }


    // 25) 回放计算（第十一轮②）：极值 / 简单统计 / 变化总量 / 变更区间
    private static void t25CurveMath() {
        // 数量线：7 7 8 8 9（用户给的例子：变化量 mean=1 var=0）
        List<SeriesPoint> q = List.of(
                new SeriesPoint(10, 7, true),
                new SeriesPoint(11, 7, false),
                new SeriesPoint(13, 8, false),   // 间隔 2 tick，但没断 → 真实变化 +1
                new SeriesPoint(14, 8, false),
                new SeriesPoint(15, 9, false));

        CurveMath.Extremes e = CurveMath.extremes(q);
        eq("min 7 @t10", "7@10", e.min() + "@" + e.minTick());
        eq("max 9 @t15", "9@15", e.max() + "@" + e.maxTick());

        CurveMath.Stats st = CurveMath.stats(q);
        eq("mean 7.8", 7.8, st.mean(), 1e-9);
        eq("med 8", 8.0, st.med(), 1e-9);
        // 偏差 -0.8 -0.8 +0.2 +0.2 +1.2 → (0.64*2 + 0.04*2 + 1.44) / 5 = 0.56
        eq("var 0.56", 0.56, st.var(), 1e-9);

        CurveMath.TotalChange t = CurveMath.totalChange(q);
        eq("上升 +2", 2, t.rise().asDouble(), 1e-9);
        eq("下降 0", 0, t.fall().asDouble(), 1e-9);
        eq("合计 Δ+2 == 首尾差", 2, t.net().asDouble(), 1e-9);
        eq("无断档", 0, t.gaps());

        CurveMath.Events ev = CurveMath.changeEvents(q);
        eq("变更 2 次", 2, ev.count());
        eq("变化量 mean 1", 1.0, ev.dMean(), 1e-9);
        eq("变化量 var 0", 0.0, ev.dVar(), 1e-9);
        eq("周期 mean 2t（t13 → t15）", 2.0, ev.pMean(), 1e-9);
        eq("周期 var 0", 0.0, ev.pVar(), 1e-9);

        // 断档：变化不计入，缺口单独报出（Σ升+Σ降 故意 ≠ Δ —— 这是诚实，不是 bug）
        List<SeriesPoint> broken = List.of(
                new SeriesPoint(1, 10, true),
                new SeriesPoint(2, 12, false),
                new SeriesPoint(5, 20, true),    // 断档
                new SeriesPoint(6, 18, false));
        CurveMath.TotalChange bt = CurveMath.totalChange(broken);
        eq("断档段：上升 +2（t1→t2）", 2, bt.rise().asDouble(), 1e-9);
        eq("断档段：下降 -2（t5→t6）", -2, bt.fall().asDouble(), 1e-9);
        eq("断档段：跨档的 +8 不计入", 0, bt.net().asDouble() - (bt.rise().asDouble() + bt.fall().asDouble()), 1e-9);
        eq("断档 1 处", 1, bt.gaps());

        CurveMath.Events bev = CurveMath.changeEvents(broken);
        eq("断档段：变更 2 次", 2, bev.count());

        // IO 线：流量值本身就是变化量
        // ★ 第十六轮⑤ 修正 ★ 旧断言把 bug 固化了（期望值正是"被吞掉的那一笔"）：
        //   ① `flow` 上每个点的值都成立 —— 生产者只在"相邻两格都观测到"时才产生流量点，
        //      `breakBefore` 只是**渲染**用的断线标记（画图别跨缺口连线），
        //      统计不该拿它当"值不可信"；
        //   ② 缺口数 = 真的少了几笔 Δ（段起点之后每一条断线算一次）。
        List<SeriesPoint> flow = List.of(
                new SeriesPoint(2, 64, true),    // 段起点：值是真的，只是画图要从这儿断开
                new SeriesPoint(3, 64, false),   // +64
                new SeriesPoint(4, 0, false),
                new SeriesPoint(5, -10, false),  // -10
                new SeriesPoint(8, 30, true));   // 中途断档后又接上：这一笔 Δ 同样成立
        CurveMath.TotalChange ft = CurveMath.flowTotal(flow);
        eq("IO 上升 +158（64+64+30 全算）", 158, ft.rise().asDouble(), 1e-9);
        eq("IO 下降 -10", -10, ft.fall().asDouble(), 1e-9);
        eq("IO 净 +148", 148, ft.net().asDouble(), 1e-9);
        eq("IO 缺口 1 处（只有 t8 前那次断）", 1, ft.gaps());

        CurveMath.Events fev = CurveMath.flowEvents(flow);
        eq("IO 变更 4 次（64 / 64 / -10 / 30）", 4, fev.count());
        eq("IO 变化量 mean 37（(64+64-10+30)/4）", 37.0, fev.dMean(), 1e-9);
        eq("IO 周期 mean 2t（t2→3→5→8：间隔 1/2/3）", 2.0, fev.pMean(), 1e-9);
    }




    // 26) SNBT 解析（第十一轮③）：值列表 / 路径 / 采样路径语法
    private static void t26Snbt() {
        // 典型 `data get block` 回复（前缀 + 复合标签）
        String raw = "barrel has the following block data: {Items:[{Count:1b,Slot:0b,"
                + "id:\"minecraft:iron_ingot\"}],id:\"minecraft:barrel\",energy:1234,"
                + "tank:{FluidName:\"minecraft:water\",amount:5000},x:1,y:2,z:3}";

        Snbt.Node root = Snbt.parse(raw);
        yes("解析出复合节点", root != null && root.kind() == Snbt.Kind.COMPOUND);

        List<Snbt.Entry> flat = Snbt.flatten(root);
        List<String> paths = new ArrayList<>();
        for (Snbt.Entry e : flat) {
            paths.add(e.path());
        }
        yes("列出 Items[0].Count", paths.contains("Items[0].Count"));
        yes("列出 Items[0].id", paths.contains("Items[0].id"));
        yes("列出 tank.amount", paths.contains("tank.amount"));
        yes("列出 tank.FluidName", paths.contains("tank.FluidName"));
        yes("列出 energy", paths.contains("energy"));

        Snbt.Entry energy = null;
        Snbt.Entry fluid = null;
        Snbt.Entry amount = null;
        for (Snbt.Entry e : flat) {
            if (e.path().equals("energy")) energy = e;
            if (e.path().equals("tank.FluidName")) fluid = e;
            if (e.path().equals("tank.amount")) amount = e;
        }
        yes("energy 是数值", energy != null && energy.numeric());
        eq("energy = 1234", 1234.0, energy == null ? -1 : energy.node().number(), 1e-9);
        yes("tank.amount 是数值（未知模组键名也能取到）", amount != null && amount.numeric());
        eq("amount = 5000", 5000.0, amount == null ? -1 : amount.node().number(), 1e-9);
        yes("FluidName 是文本", fluid != null && fluid.textual());
        eq("FluidName 内容", "minecraft:water", fluid == null ? "" : fluid.node().text());
        eq("1b 后缀被剥掉", "1", paths.contains("Items[0].Count")
                ? String.valueOf((long) Snbt.flatten(root).get(0).node().number()) : "?");

        // 采样路径语法
        List<Snbt.Token> t = Snbt.parsePath("tank.amount");
        eq("两段路径", 2, t.size());
        eq("第一段是键 tank", "tank", t.get(0).key());
        List<Snbt.Token> t2 = Snbt.parsePath("tanks[0].amount");
        eq("三段路径", 3, t2.size());
        yes("第二段是下标 0", t2.get(1).isIndex() && t2.get(1).index() == 0);
        eq("下标段没有键名", null, t2.get(1).key());
        eq("坏路径返回空表", 0, Snbt.parsePath("a[").size());
        eq("空路径返回空表", 0, Snbt.parsePath("   ").size());
        eq("单键路径", 1, Snbt.parsePath("energy").size());

        // 非复合 / 残缺文本：不抛异常，能解析多少算多少
        eq("没有复合标签返回 null", null, Snbt.parse("no braces here"));
        Snbt.Node truncated = Snbt.parse("{a:1,b:\"half");
        yes("残缺文本仍能解出前半段", truncated != null
                && truncated.children().containsKey("a"));
        Snbt.Node typed = Snbt.parse("{l:[I;1,2,3]}");
        yes("带类型前缀的列表可解析", typed != null
                && typed.children().get("l").items().size() == 3);
    }


    // 27) SNBT 偏移（第十一轮④②）：悬浮高亮按源偏移定位
    private static void t27Offsets() {
        String raw = "barrel has the following block data: {a:1,bb:22,c:\"xyz\"}";
        Snbt.Node root = Snbt.parse(raw);
        yes("解析成功", root != null);
        if (root == null) {
            return;
        }
        for (Snbt.Entry e : Snbt.flatten(root)) {
            String slice = raw.substring(e.node().start(), e.node().end());
            if (e.path().equals("a")) {
                eq("a 的偏移切出原值", "1", slice);
            }
            if (e.path().equals("bb")) {
                eq("bb 的偏移切出原值", "22", slice);
            }
            if (e.path().equals("c")) {
                eq("c 的偏移切出带引号原串", "\"xyz\"", slice);
            }
        }
        // 前缀里的 "block data:" 不该被当成值（offset 不会落进前缀）
        for (Snbt.Entry e : Snbt.flatten(root)) {
            yes("偏移在复合标签内", e.node().start() > raw.indexOf(':'));
        }
    }


    // 28) 数据目标合并（第十二轮③）：同名求和、保留 0 与负数（与 combine 的物品语义区别）
    private static void t28CombineAll() {
        // 两个罐同一物品：5000 + 3000 必须合并成一条 8000（否则图表震荡）
        String[] ids = {"mekanism:ethene", "mekanism:ethene"};
        Val[] counts = {Val.of(5000), Val.of(3000)};
        String[] outIds = new String[4];
        Val[] outCounts = new Val[4];
        int m = Aggregator.combineAll(ids, counts, 2, outIds, outCounts);
        eq("同名合并成 1 条", 1, m);
        eq("求和 8000", 8000, outCounts[0].asDouble(), 1e-9);

        // 0 与负数是真实读数，必须保留（combine 会把它们当"空格子"丢掉）
        String[] ids2 = {"energy", "energy", "tank"};
        Val[] counts2 = {Val.of(0), Val.of(0), Val.of(-5)};
        int m2 = Aggregator.combineAll(ids2, counts2, 3, outIds, outCounts);
        eq("零值保留（合并为 0）+ 负值独立成条", 2, m2);
        eq("energy 合计 0", 0, outCounts[0].asDouble(), 1e-9);
        eq("tank -5", -5, outCounts[1].asDouble(), 1e-9);

        // 与 combine 的行为差异：同样输入，combine 会丢掉 ≤0
        String[] outIds3 = new String[4];
        Val[] outCounts3 = new Val[4];
        int m3 = Aggregator.combine(ids2, counts2, 3, outIds3, outCounts3);
        eq("对照：combine 全跳过 → air 占位", 1, m3);
        eq("对照：combine 产出 air x0", 0, outCounts3[0].asDouble(), 1e-9);
    }


    // 29) 渲染几何（第十三轮）：游程合并 + 列聚合。离线把"画成什么样/花费多少次 fill"钉死
    private static void t29RenderGeometry() {
        // ---- RectRuns 的两条等价合并规则 ----
        RectRuns r = new RectRuns();
        r.push(0, 1, 10, 11);
        r.push(1, 2, 10, 11);
        eq("y 相同的相邻矩形并宽", "0-2@10-11", r.dump());
        r.push(2, 3, 5, 11);           // 同 x 区间、y 相接 → 包围盒
        eq("同 x 且 y 相接 → 包围盒", "0-2@10-11 2-3@5-11", r.dump());
        r.clear();
        r.push(0, 1, 10, 11);
        r.push(1, 2, 20, 21);          // x 相接但 y 不同：不能并（并了会多画像素）
        eq("对角矩形不合并", "0-1@10-11 1-2@20-21", r.dump());
        r.clear();
        r.push(0, 1, 10, 10);          // 空矩形直接丢
        eq("空矩形被丢弃", 0, r.size());
        r.clear();
        r.push(0, 1, 10, 11);
        r.push(1, 2, 10, 11);
        r.push(2, 3, 10, 11);
        eq("三连并成一条", "0-3@10-11", r.dump());
        eq("合并前计数（= 老实现的 fill 次数）", 3, r.pushes());

        // ---- WaveColumns：常数序列塌成 1 个矩形（台阶数据的极限情形）----
        List<SeriesPoint> flat = new ArrayList<>();
        for (int t = 0; t <= 1200; t++) {
            flat.add(new SeriesPoint(t, 500, t == 0));
        }
        WaveColumns wc = new WaveColumns();
        RectRuns out = new RectRuns();
        wc.emit(flat, 0, 1200, 700, 0, v -> (int) Math.round(100 - v), out);
        eq("常数序列：700 列合并成 1 个矩形", 1, out.size());
        eq("常数序列：合并前是 1399（700 列 + 699 连接）", 1399, out.pushes());
        eq("列数", 700, wc.lastColumns());

        // ---- 两段台阶：中间一条跳变竖线 ----
        List<SeriesPoint> two = new ArrayList<>();
        for (int t = 0; t <= 1200; t++) {
            two.add(new SeriesPoint(t, t < 600 ? 500 : 600, t == 0));
        }
        RectRuns o2 = new RectRuns();
        wc.emit(two, 0, 1200, 700, 0, v -> (int) Math.round(100 - (v - 500)), o2);
        eq("两段台阶 → 3 个矩形（两段底 + 一条跳变）", 3, o2.size());

        // ---- 断档不连线：breakBefore 处线是断的 ----
        List<SeriesPoint> broken = List.of(
                new SeriesPoint(0, 10, true),
                new SeriesPoint(1, 10, false),
                new SeriesPoint(2, 20, true),    // 断档
                new SeriesPoint(3, 20, false));
        RectRuns o3 = new RectRuns();
        wc.emit(broken, 0, 3, 700, 0, v -> (int) Math.round(100 - v), o3);
        eq("断档处不产生连接矩形（2 段各 1 条）", 2, o3.size());

        // ---- 同一列里的尖峰：取 min-max，竖条覆盖整段（不能被平均掉）----
        List<SeriesPoint> spike = new ArrayList<>();
        spike.add(new SeriesPoint(0, 0, true));
        spike.add(new SeriesPoint(0, 64, false));    // 同一列（同 tick）的两个值
        spike.add(new SeriesPoint(1, 0, false));
        RectRuns o4 = new RectRuns();
        wc.emit(spike, 0, 1, 700, 0, v -> (int) Math.round(100 - v), o4);
        boolean covered = false;
        for (int i = 0; i < o4.size(); i++) {
            if (o4.y0(i) <= 36 && o4.y1(i) >= 101) {
                covered = true;   // 100-64=36 到 100-0=100（+1）都在
            }
        }
        yes("同列尖峰被 min-max 竖条覆盖", covered);
    }



    // 30) NBT 槽位型容器（第十六轮）：MEK 那类"slot 号写在 NBT 里"的布局检测与反查
    private static void t30NbtSlotLayout() {
        String mek = "barrel has the following data: {Items:["
                + "{Slot:0b,Item:{id:\"minecraft:cobblestone\",Count:2}},"
                + "{Slot:5b,Item:{id:\"minecraft:iron_ingot\",Count:64}}"
                + "],Energy:1234}";
        Snbt.Node tree = Snbt.parse(mek);
        NbtSlotLayout.Layout layout = NbtSlotLayout.detect(tree);
        yes("识别出槽位列表", layout != null);
        if (layout == null) {
            return;
        }
        eq("列表路径", "Items", layout.listPath());
        eq("元素个数", 2, layout.entries().size());
        eq("第 1 个 slot", 0, layout.entries().get(0).slot());
        eq("第 1 个物品", "minecraft:cobblestone", layout.entries().get(0).itemId());
        eq("第 1 个数量", 2, layout.entries().get(0).count());
        eq("第 2 个 slot", 5, layout.entries().get(1).slot());
        eq("第 2 个数量", 64, layout.entries().get(1).count());

        // 按 slot 找（采样的读法）
        eq("slot=5 的数量", 64, NbtSlotLayout.bySlot(tree, "Items", 5).count());
        eq("slot=0 的数量", 2, NbtSlotLayout.bySlot(tree, "Items", 0).count());
        eq("slot=3 不存在（空格子）", null, NbtSlotLayout.bySlot(tree, "Items", 3));

        // 按内容反查（拾取时把"点到的格子"翻成 slot 号）
        List<NbtSlotLayout.Entry> hit = NbtSlotLayout.byContent(tree, "Items", "minecraft:iron_ingot", 64);
        eq("唯一命中", 1, hit.size());
        eq("命中 slot", 5, hit.get(0).slot());
        eq("数量不符 → 不命中", 0, NbtSlotLayout.byContent(tree, "Items", "minecraft:iron_ingot", 63).size());
        eq("空格子的物品 → 不命中", 0, NbtSlotLayout.byContent(tree, "Items", "", 0).size());

        // 不是槽位列表的东西不该被误判
        eq("流体列表不误判", null, NbtSlotLayout.detect(Snbt.parse("{tanks:[{FluidName:\"minecraft:water\",amount:5000}]}")));
        eq("只有 Count 没有 Slot 也不误判", null, NbtSlotLayout.detect(Snbt.parse("{Items:[{Count:1,id:\"minecraft:stone\"}]}")));
        eq("空列表不误判", null, NbtSlotLayout.detect(Snbt.parse("{Items:[]}")));
        // 扁平形状（id/Count 直接写在元素上）也要认
        NbtSlotLayout.Layout flat = NbtSlotLayout.detect(Snbt.parse("{Items:[{Slot:7,id:\"minecraft:stone\",Count:3}]}"));
        yes("扁平元素也能识别", flat != null && flat.entries().size() == 1);
        eq("扁平元素数量", 3, flat == null ? -1 : flat.entries().get(0).count());
    }


    // 31) 槽位匹配按物品 id（第十六轮②）：数量漂移不影响；同物品多格全都要
    private static void t31SlotMatchById() {
        String two = "{Items:["
                + "{Slot:0b,Item:{id:\"minecraft:cobblestone\",Count:2}},"
                + "{Slot:9b,Item:{id:\"minecraft:cobblestone\",Count:8}},"
                + "{Slot:5b,Item:{id:\"minecraft:iron_ingot\",Count:64}}"
                + "]}";
        Snbt.Node tree = Snbt.parse(two);

        // 同一种物品占了两格：两格都要（各建锚点 → 采样按名称合并求和）
        List<NbtSlotLayout.Entry> cobble = NbtSlotLayout.byItem(tree, "Items", "minecraft:cobblestone");
        eq("同物品命中两格", 2, cobble.size());
        eq("第 1 格 slot", 0, cobble.get(0).slot());
        eq("第 2 格 slot", 9, cobble.get(1).slot());

        // 数量漂移不影响命中（按 id 匹配）：这就是"点了输入输出却漏掉输出格"的修法
        eq("数量变了照样命中", 2, NbtSlotLayout.byItem(tree, "Items", "minecraft:cobblestone").size());

        // 不在列表里的物品 = 空格子 → 不命中（用户规则：取消监测）
        eq("空物品不命中", 0, NbtSlotLayout.byItem(tree, "Items", "").size());
        eq("列表里没有的物品不命中", 0, NbtSlotLayout.byItem(tree, "Items", "minecraft:stone").size());

        // 两格合计 = 10（采样的合并求和；Aggregator.combineAll 已在 t28 钉死）
        String[] ids = {"minecraft:cobblestone", "minecraft:cobblestone"};
        Val[] counts = {Val.of(cobble.get(0).count()), Val.of(cobble.get(1).count())};
        String[] outIds = new String[4];
        Val[] outCounts = new Val[4];
        int m = Aggregator.combineAll(ids, counts, 2, outIds, outCounts);
        eq("两格合并成一条", 1, m);
        eq("合计 10", 10, outCounts[0].asDouble(), 1e-9);
    }


    // 33) 选格 → slot 号的判据链（第十六轮④）
    //     **修的就是实测那条**：MEK 上点 2 个原料格 + 2 个输出格 → 只跟到 1 个原料格 + 全部输出格。
    private static void t33SlotResolve() {
        final String X = "minecraft:cobblestone";
        final String Y = "minecraft:iron_ingot";

        // 场景 = Mekanism 终极熔炼工厂（9 进 9 出），菜单机器格顺序：
        //   0,1 = **空着的**虚拟升级槽；2..10 = 数据 slot 0..8（输入）；11..19 = slot 9..17（输出）；20 = 能量槽
        // 数据（`items:[{id,count,slot}]`，空槽不写）里：输入 slot 0..3 是 X，输出 slot 9..12 是 Y
        List<NbtSlotLayout.Entry> entries = new ArrayList<>();
        for (int s = 0; s <= 3; s++) {
            entries.add(new NbtSlotLayout.Entry(s, X, 1));
        }
        for (int s = 9; s <= 12; s++) {
            entries.add(new NbtSlotLayout.Entry(s, Y, 64));
        }
        // 菜单里的非空格（按菜单顺序）—— 两个空着的虚拟槽不进这个序列（这正是能对齐的关键）
        List<SlotAlign.Cell> cells = new ArrayList<>();
        for (int ord = 2; ord <= 5; ord++) {
            cells.add(new SlotAlign.Cell(ord, ord, X));
        }
        for (int ord = 11; ord <= 14; ord++) {
            cells.add(new SlotAlign.Cell(ord, ord, Y));
        }

        SlotAlign.Result r = SlotAlign.align(cells, entries);
        eq("配对数 = 8", 8, r.pairs());
        eq("没有分不出的格", 0, r.unresolved());
        eq("统一位移 = 2", Integer.valueOf(2), r.shift());

        // ★ 回归点 1：两个原料格 → **两个不同的 slot**（旧规则两个都撞上 slot 0，塌成一格）★
        eq("第 1 个原料格 → slot 0", Integer.valueOf(0), r.byOrdinal().get(2));
        eq("第 2 个原料格 → slot 1", Integer.valueOf(1), r.byOrdinal().get(3));
        // ★ 回归点 2：两个输出格 → 各自的 slot（旧规则会把全部同物品格子都纳入）★
        eq("第 1 个输出格 → slot 9", Integer.valueOf(9), r.byOrdinal().get(11));
        eq("第 2 个输出格 → slot 10", Integer.valueOf(10), r.byOrdinal().get(12));
        eq("反向表可用（回填勾选）", Integer.valueOf(14), r.bySlot().get(12));

        // ★ 回归点 3：Mekanism 每格都报容器下标 0 —— 判定为"不可靠"，那条判据直接不用 ★
        no("下标全是 0 → 不可靠", SlotAlign.containerIndexReliable(List.of(0, 0, 0, 0)));
        yes("下标互不相同 → 可靠（NeoForge 那类）", SlotAlign.containerIndexReliable(List.of(0, 1, 2, 9)));
        yes("下标为负的忽略（无 GUI 容器）", SlotAlign.containerIndexReliable(List.of(-1, -1)));

        // 空格子：数据里没有元素，只能靠统一位移外推
        Integer emptySlot = SlotAlign.resolve(new SlotAlign.Pick(6, 0, "minecraft:air", true),
                entries, r, false);
        eq("空格子靠位移推到 slot 4", Integer.valueOf(4), emptySlot);
        // 位移推不出来时，空格子**不猜**（旧行为是靠容器下标 0 —— 那是错的）
        eq("位移缺失 + 下标不可靠 → 拒绝", null,
                SlotAlign.resolve(new SlotAlign.Pick(6, 0, "minecraft:air", true), entries,
                        SlotAlign.Result.EMPTY, false));

        // 非空格：对齐失败时，容器下标只在"内容对得上"时才算数
        eq("对齐失败 + 下标可用 + 内容对得上 → 用下标", Integer.valueOf(1),
                SlotAlign.resolve(new SlotAlign.Pick(9, 1, X, false), entries,
                        SlotAlign.Result.EMPTY, true));
        eq("对齐失败 + 下标撞上别的物品 → 不认", null,
                SlotAlign.resolve(new SlotAlign.Pick(9, 9, X, false), entries,
                        SlotAlign.Result.EMPTY, true));

        // ---- 分不出来就不猜（旧规则是"全纳入"）----
        List<NbtSlotLayout.Entry> three = List.of(
                new NbtSlotLayout.Entry(0, X, 1),
                new NbtSlotLayout.Entry(5, X, 1),
                new NbtSlotLayout.Entry(7, X, 1));
        SlotAlign.Result amb = SlotAlign.align(List.of(new SlotAlign.Cell(0, 0, X)), three);
        eq("同名 3 格只勾 1 格 → 分不出", 1, amb.unresolved());
        eq("分不出时不给 slot", null,
                SlotAlign.resolve(new SlotAlign.Pick(0, -1, X, false), three, amb, false));

        // ---- 快照对不齐（物品刚被换掉）：坏的那格拒绝，好的那格照样对得上 ----
        List<NbtSlotLayout.Entry> onlyX = List.of(new NbtSlotLayout.Entry(0, X, 1));
        List<SlotAlign.Cell> stale = List.of(
                new SlotAlign.Cell(0, 0, X),
                new SlotAlign.Cell(1, 1, "minecraft:diamond"));
        SlotAlign.Result sr = SlotAlign.align(stale, onlyX);
        eq("好的那格仍然对得上", Integer.valueOf(0), sr.byOrdinal().get(0));
        eq("对不上的那格被拒绝", 1, sr.unresolved());
        eq("样本不足（只有 1 个配对）→ 不给位移", null, sr.shift());

        // ---- 位移不统一（多余槽夹在中间）→ 不给位移，空格子不猜 ----
        List<NbtSlotLayout.Entry> two = List.of(
                new NbtSlotLayout.Entry(0, X, 1),
                new NbtSlotLayout.Entry(1, Y, 1));
        SlotAlign.Result uneven = SlotAlign.align(List.of(
                new SlotAlign.Cell(2, 2, X),
                new SlotAlign.Cell(5, 5, Y)), two);
        eq("位移不统一 → null", null, uneven.shift());
        eq("但非空格照样定得下来", Integer.valueOf(1), uneven.byOrdinal().get(5));
    }

    // 34) IO 统计口径（第十六轮⑤）
    //     **修的就是全项目审查里最重的那条**：段首那一笔真实流量被当"不可信"吞掉、
    //     还谎报一次断档（存量 10→14→12 时 IO 线报 上升=0 断档=1）。
    private static void t34FlowStats() {
        // 连续三格：都观测到了 ⇒ 两笔真实 Δ（+4 / −2），一个缺口都不该有
        List<Sample> cont = new ArrayList<>();
        cont.add(new Sample(100, "iron", 10));
        cont.add(new Sample(101, "iron", 14));
        cont.add(new Sample(102, "iron", 12));
        Series s = ChartData.build(1, cont, 16).series().get(0);

        eq("样本点的断点标记（首点=段起点）", java.util.List.of(true, false, false),
                java.util.List.of(s.points().get(0).breakBefore(), s.points().get(1).breakBefore(),
                        s.points().get(2).breakBefore()));
        // 流量点：第一个带"渲染用"的断线标记（画图别跨缺口连线），但它的值是真实的
        eq("流量点数", 2, s.flow().size());
        yes("首流量点带渲染断线标记", s.flow().get(0).breakBefore());

        CurveMath.TotalChange ft = CurveMath.flowTotal(s.flow());
        eq("IO 上升 = 4（不再被吞）", 4, ft.rise().asDouble(), 1e-9);
        eq("IO 下降 = -2", -2, ft.fall().asDouble(), 1e-9);
        eq("IO 缺口 = 0（不再谎报）", 0, ft.gaps());
        eq("IO 净变化 = +2", 2, ft.net().asDouble(), 1e-9);
        eq("IO 变更事件 = 2", 2, CurveMath.flowEvents(s.flow()).count());
        // 对照：存量线的口径本来就是对的（同一批数据）
        CurveMath.TotalChange st = CurveMath.totalChange(s.points());
        eq("存量上升 = 4", 4, st.rise().asDouble(), 1e-9);
        eq("存量缺口 = 0", 0, st.gaps());

        // 中间断了一次：104 那一格没观测到 ⇒ Δ(104) 真的缺了（IO 缺口 1 处），
        // 而 105 那一笔 Δ 是真实的（20→18），必须照算
        List<Sample> brk = new ArrayList<>(cont);
        brk.add(Sample.noData(103));
        brk.add(new Sample(104, "iron", 20));
        brk.add(new Sample(105, "iron", 18));
        Series s2 = ChartData.build(2, brk, 16).series().get(0);
        eq("断档后：流量点数", 3, s2.flow().size());
        CurveMath.TotalChange ft2 = CurveMath.flowTotal(s2.flow());
        eq("断档后 IO 上升 = 4", 4, ft2.rise().asDouble(), 1e-9);
        eq("断档后 IO 下降 = -4（-2 与 -2）", -4, ft2.fall().asDouble(), 1e-9);
        eq("断档后 IO 缺口 = 1（只有真的缺了的那一笔）", 1, ft2.gaps());
        eq("断档后 IO 事件 = 3", 3, CurveMath.flowEvents(s2.flow()).count());
        CurveMath.TotalChange st2 = CurveMath.totalChange(s2.points());
        eq("同一条断档，存量缺口也是 1", 1, st2.gaps());

        // 只有首点带断线标记（窗口边界那种）→ 不算缺口
        List<SeriesPoint> only = java.util.List.of(new SeriesPoint(500, 7, true));
        eq("只有首点 → 缺口 0", 0, CurveMath.flowTotal(only).gaps());
        eq("只有首点 → 值照样计入", 7, CurveMath.flowTotal(only).rise().asDouble(), 1e-9);
    }

    // 35) 存档头部流式读取（第十六轮⑤）：取到的小结必须与写进去的一致，
    //     坏文件要抛 IOException（调用方跳过它），不认识的键要忽略
    private static void t35ArchiveHeader() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("logiscope-t35");
        try {
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                samples.add(new Sample(1000 + i, i % 7 == 0 ? Sample.EMPTY_ITEM : "minecraft:iron_ingot", i % 64));
            }
            samples.add(Sample.noData(9000));
            samples.add(Sample.gap(9001));
            ArchiveData data = new ArchiveData("t35", "2026-09-19 20:00:00", "TestWorld",
                    "barrel", "槽 0,1 · overworld 10,64,10", 1000, 9001,
                    java.util.List.of(1500L, 2500L, 3500L), samples);
            java.nio.file.Path file = dir.resolve("recording.json");
            java.nio.file.Files.writeString(file, ArchiveFormat.toJson(data),
                    java.nio.charset.StandardCharsets.UTF_8);

            ArchiveFormat.Header h = ArchiveFormat.readHeader(file);
            eq("头部 created", "2026-09-19 20:00:00", h.created());
            eq("头部 world", "TestWorld", h.world());
            eq("头部 探针名", "barrel", h.probeName());
            eq("头部 探针描述", "槽 0,1 · overworld 10,64,10", h.probeDesc());
            eq("头部 first", 1000L, h.firstTick());
            eq("头部 last", 9001L, h.lastTick());
            eq("头部 标注数", 3, h.markerCount());
            eq("头部 样本数（= 写进去的条数）", 2002, h.sampleCount());

            // 不认识的键 / 缺字段 → 忽略与默认值，不因此丢掉整个存档
            java.nio.file.Path odd = dir.resolve("odd.json");
            java.nio.file.Files.writeString(odd,
                    "{\"format\":9,\"kind\":\"logiscope-archive\",\"future\":{\"a\":[1,2,3]},"
                            + "\"probe\":\"oops\",\"range\":5,\"samples\":[{\"t\":1,\"n\":1},{\"t\":2,\"n\":2}]}",
                    java.nio.charset.StandardCharsets.UTF_8);
            ArchiveFormat.Header h2 = ArchiveFormat.readHeader(odd);
            eq("坏类型 → 默认 world", "unknown", h2.world());
            eq("坏类型 → 探针名空", "", h2.probeName());
            eq("坏类型 → samples 照样数得出来", 2, h2.sampleCount());
            eq("坏类型 → markers 当 0", 0, h2.markerCount());

            // 不是 JSON → IOException（列表页会跳过并记日志，不炸）
            java.nio.file.Path bad = dir.resolve("bad.json");
            java.nio.file.Files.writeString(bad, "not json at all",
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean thrown = false;
            try {
                ArchiveFormat.readHeader(bad);
            } catch (java.io.IOException e) {
                thrown = true;
            }
            yes("坏存档抛 IOException", thrown);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(dir.resolve("recording.json"));
                java.nio.file.Files.deleteIfExists(dir.resolve("odd.json"));
                java.nio.file.Files.deleteIfExists(dir.resolve("bad.json"));
                java.nio.file.Files.delete(dir);
            } catch (Exception ignored) {
                // 临时目录清不掉也无所谓
            }
        }
    }

    // 36) 别把"装不下的数"变成错值（第十六轮⑤）：NaN / Infinity 不当数字，
    //     超 int 的 slot 号不当 slot 号
    private static void t36NumberGuards() {
        eq("NaN 不是数字", null, Snbt.parseNumber("NaN"));
        eq("Infinity 不是数字", null, Snbt.parseNumber("Infinity"));
        eq("-Infinity 不是数字", null, Snbt.parseNumber("-Infinity"));
        eq("普通数照旧", 1.5, Snbt.parseNumber("1.5"));
        eq("带后缀照旧", 1.0, Snbt.parseNumber("1b"));

        // slot 写成 NaN / 超出 int → 这个列表不算槽位列表（宁可识别不出来，也不给错号）
        eq("slot=NaN 不认", null, NbtSlotLayout.detect(
                Snbt.parse("{items:[{id:\"minecraft:stone\",count:1,slot:NaN}]}")));
        eq("slot 超 int 不认", null, NbtSlotLayout.detect(
                Snbt.parse("{items:[{id:\"minecraft:stone\",count:1,slot:99999999999}]}")));
        // 正常的小数值仍然认（有些模组写 1.0）
        NbtSlotLayout.Layout ok = NbtSlotLayout.detect(
                Snbt.parse("{items:[{id:\"minecraft:stone\",count:1,slot:3.0}]}"));
        yes("正常 slot 照样认", ok != null && ok.entries().size() == 1 && ok.entries().get(0).slot() == 3);
    }

    // 37) 自适应数值 Val（第十六轮⑥ G1）
    //     起因 = 用户实测：追创造能量立方的 2^63 → 旧实现 (int) 截断画出 -1 的假线。
    private static void t37Val() {
        // 形式与规范化
        Val a = Val.of(42L);
        yes("整数是整数形式", a.isIntegral());
        Val b = Val.of(1.5);
        no("小数不是整数形式", b.isIntegral());
        Val c = Val.of(3.0);            // 数学上是整数的 double → 规范化成整数形式
        yes("3.0 规范化成整数形式", c.isIntegral());
        eq("3.0 的读数 = 3", 3, c.asDouble(), 1e-9);
        eq("NaN 拒绝（= 读不到）", null, Val.of(Double.NaN));
        eq("Infinity 拒绝", null, Val.of(Double.POSITIVE_INFINITY));

        // 运算：同为整数 → 精确；有小数 → double
        eq("整数加法", 15, Val.of(10).plus(Val.of(5)).asDouble(), 1e-9);
        eq("整数减法", -2, Val.of(12).minus(Val.of(14)).asDouble(), 1e-9);
        no("小数相加不再是整数形式", Val.of(1.5).plus(Val.of(1.0)).isIntegral());
        eq("小数相加的值", 2.5, Val.of(1.5).plus(Val.of(1.0)).asDouble(), 1e-9);

        // ★ 溢出回退：Long.MAX_VALUE + 1 绝不能回绕成负数 ★
        Val big = Val.of(Long.MAX_VALUE).plus(Val.of(1L));
        no("long 求和溢出 → 回退 double 形式", big.isIntegral());
        eq("回退后的值 = 9.223372036854776E18", 9.223372036854776E18, big.asDouble(), 0);

        // ★ 全链路回归：2^63 的能量读数在曲线上保持精确 ★
        List<Sample> energy = List.of(
                new Sample(100, "energy", Val.of(9223372036854775806L)),
                new Sample(101, "energy", Val.of(9223372036854775807L)));
        Series se = ChartData.build(7L, energy).series().get(0);
        eq("2^63-1 的存量点保持精确", 9223372036854775806L, se.points().get(0).value().i());
        eq("跨 2^63 的流量 = +1（整数精确）", 1, se.flow().get(0).value().asDouble(), 1e-9);
        CurveMath.TotalChange t = CurveMath.totalChange(se.points());
        eq("2^63 的变化合计 = +1（不回绕）", 1, t.rise().asDouble(), 1e-9);

        // 小数系列：流量与统计都走 double
        List<Sample> fluid = List.of(
                new Sample(100, "minecraft:water", Val.of(1.5)),
                new Sample(101, "minecraft:water", Val.of(2.5)));
        Series sf = ChartData.build(8L, fluid).series().get(0);
        eq("小数存量保持小数", 1.5, sf.points().get(0).value().asDouble(), 1e-9);
        eq("小数流量 = +1.0", 1.0, sf.flow().get(0).value().asDouble(), 1e-9);
        CurveMath.Stats st = CurveMath.stats(sf.points());
        eq("小数的均值", 2.0, st.mean(), 1e-9);

        // 存档往返：整数写 n / 小数写 d（向后兼容：旧文件只有 n）
        List<Sample> both = List.of(
                new Sample(100, "minecraft:iron_ingot", Val.of(64L)),
                new Sample(101, "minecraft:water", Val.of(5.5)));
        ArchiveData d = new ArchiveData("t37", "2026-09-19 21:00:00", "W", "p", "d", 100, 101,
                List.of(), new ArrayList<>(both));
        List<Sample> back = ArchiveFormat.fromJson(ArchiveFormat.toJson(d)).samples();
        yes("整数往返后仍是整数形式", back.get(0).value().isIntegral());
        eq("整数往返值不变", 64, back.get(0).value().asDouble(), 1e-9);
        no("小数往返后仍是小数形式", back.get(1).value().isIntegral());
        eq("小数往返值不变", 5.5, back.get(1).value().asDouble(), 1e-9);
    }

    // ---- 断言 ----

    private static void eq(String what, double expect, double actual, double eps) {
        if (Math.abs(expect - actual) <= eps) {
            System.out.println("  ok   " + what + " = " + actual);
        } else {
            failed++;
            System.out.println("  FAIL " + what + " 期望 " + expect + " 实际 " + actual);
        }
    }

    // 41) 选格页筛选框的匹配规则（第十九轮①）
    private static void t41PickerFilter() {
        // 空查询 / 纯空白 = 不过滤（少一次误操作）
        yes("筛选 空串全过", PickerFilter.matches("", 3, "minecraft:iron_ingot", "iron_ingot"));
        yes("筛选 纯空白全过", PickerFilter.matches("   ", 3, "minecraft:iron_ingot"));
        yes("筛选 null 全过", PickerFilter.matches(null, 3));

        // 名称子串、大小写不敏感、id 与短名都匹配
        yes("筛选 短名子串", PickerFilter.matches("iron", 3, "minecraft:iron_ingot", "iron_ingot"));
        yes("筛选 大写也能中", PickerFilter.matches("IRON", 3, "minecraft:iron_ingot", "iron_ingot"));
        yes("筛选 命名空间也能中", PickerFilter.matches("minecraft", 3, "minecraft:iron_ingot"));
        no("筛选 不相关不中", PickerFilter.matches("gold", 3, "minecraft:iron_ingot"));

        // 序号：精确匹配，不是子串
        yes("筛选 #3 命中 3 号", PickerFilter.matches("#3", 3, "minecraft:iron_ingot"));
        no("筛选 #1 不命中 10 号", PickerFilter.matches("#1", 10, "minecraft:iron_ingot"));
        no("筛选 #1 不命中 21 号", PickerFilter.matches("#1", 21, "minecraft:iron_ingot"));
        yes("筛选 #007 也算 7 号", PickerFilter.matches("#007", 7, "minecraft:iron_ingot"));

        // 多词 = 与（需求原文的例子：#1 iron_ingot）
        yes("筛选 #1+名称 都中", PickerFilter.matches("#1 iron_ingot", 1, "minecraft:iron_ingot", "iron_ingot"));
        no("筛选 #1+名称 序号不对", PickerFilter.matches("#1 iron_ingot", 2, "minecraft:iron_ingot"));
        no("筛选 #1+名称 名称不对", PickerFilter.matches("#1 iron_ingot", 1, "minecraft:gold_ingot"));

        // 裸 # 没有数字 → 退回普通子串（当用户就想搜带 # 的文本）
        yes("筛选 裸 # 退回子串", PickerFilter.matches("#", 12, "minecraft:iron_ingot"));
        // 空行（(empty)）也按显示文字参与匹配
        yes("筛选 空格名也能筛（空）", PickerFilter.matches("空", 4, "minecraft:air", "（空）"));
        no("筛选 有内容的行不带 （空）", PickerFilter.matches("空", 4, "minecraft:iron_ingot", "iron_ingot"));
    }

    // 42) AE 键的字符串身份（第十九轮②）：AE 的 SNBT 与老式物品 id 要能共存
    private static void t42AeKeyText() {
        String fluid = "ae:{#t:\"ae2:f\",id:\"minecraft:water\"}";
        String item = "ae:{#t:\"ae2:i\",id:\"minecraft:iron_ingot\",components:{\"minecraft:damage\":5}}";

        // 老格式（0.1.2 之前的 AE 目标）不变
        no("AE 键 普通物品不算 ae 键", AeKeyText.isAe("minecraft:iron_ingot"));
        eq("AE 键 普通物品显示短名", "iron_ingot", AeKeyText.label("minecraft:iron_ingot"));

        // 新格式：身份是整段 SNBT，显示名取里头的 id
        yes("AE 键 流体是 ae 键", AeKeyText.isAe(fluid));
        eq("AE 键 流体显示名", "water", AeKeyText.label(fluid));
        eq("AE 键 流体类型", "ae2:f", AeKeyText.typeOf(fluid));

        // 带数据组件的物品键：同样只取 id（组件不参与显示）
        yes("AE 键 组件物品是 ae 键", AeKeyText.isAe(item));
        eq("AE 键 组件物品显示名", "iron_ingot", AeKeyText.label(item));
        eq("AE 键 组件物品类型", "ae2:i", AeKeyText.typeOf(item));

        // 解析不出来时不编名字：露原文（前导 ~ 是"这是原文"的标记）
        yes("AE 键 坏文本不抛异常", AeKeyText.label("ae:{broken").startsWith("~"));
        eq("AE 键 空串", "", AeKeyText.label(""));
        eq("AE 键 null 安全", "", AeKeyText.label(null));
    }

    // 43) RS（精致存储）键的字符串身份：无组件物品与 AE / 容器槽位共用裸 id，
    //     流体与带组件物品挂 rs: 前缀。纯文本层，无游戏依赖。
    private static void t43RsKeyText() {
        // 无组件物品：**故意不挂前缀**（见 RsKeyText 类注释的三个理由）
        eq("RS 键 无组件物品 = 裸 id", "minecraft:iron_ingot", RsKeyText.item("minecraft:iron_ingot"));
        no("RS 键 裸 id 不算 rs 键", RsKeyText.isRs("minecraft:iron_ingot"));
        eq("RS 键 裸 id 显示短名", "iron_ingot", RsKeyText.label("minecraft:iron_ingot"));

        // 流体
        String water = RsKeyText.fluid("minecraft:water");
        eq("RS 键 流体形状", "rs:f:minecraft:water", water);
        yes("RS 键 流体是 rs 键", RsKeyText.isRs(water));
        eq("RS 键 流体显示短名", "water", RsKeyText.label(water));
        eq("RS 键 流体类型", 'f', RsKeyText.parse(water).type());
        eq("RS 键 流体 registry id", "minecraft:water", RsKeyText.parse(water).registryId());
        eq("RS 键 流体无组件", null, RsKeyText.parse(water).components());

        // 带组件物品：类型标记 i + 组件指纹（指纹形如 {a=1,b=2}，含 ':' 与 ','）
        String potion = RsKeyText.itemWithComponents("minecraft:potion", "{minecraft:potion_contents={potion=minecraft:healing}}");
        yes("RS 键 组件物品是 rs 键", RsKeyText.isRs(potion));
        eq("RS 键 组件物品类型", 'i', RsKeyText.parse(potion).type());
        eq("RS 键 组件物品 registry id 不被组件里的 ':' 干扰", "minecraft:potion",
                RsKeyText.parse(potion).registryId());
        eq("RS 键 组件物品指纹完整保留", "{minecraft:potion_contents={potion=minecraft:healing}}",
                RsKeyText.parse(potion).components());
        eq("RS 键 组件物品显示名丢掉指纹", "potion", RsKeyText.label(potion));

        // 带组件流体：同一套规则
        String tagged = RsKeyText.fluidWithComponents("minecraft:water", "{x=1}");
        eq("RS 键 组件流体形状", "rs:f:minecraft:water|{x=1}", tagged);
        eq("RS 键 组件流体显示短名", "water", RsKeyText.label(tagged));

        // 边界：不是 rs 键 / 形状坏掉 —— 一律不假装，能读到哪算哪
        no("RS 键 ae 键不算 rs 键", RsKeyText.isRs("ae:{#t:\"ae2:f\",id:\"minecraft:water\"}"));
        no("RS 键 只有前缀不算", RsKeyText.isRs("rs:"));
        no("RS 键 缺类型分隔符不算", RsKeyText.isRs("rs:f"));
        eq("RS 键 null 安全", "", RsKeyText.label(null));
        eq("RS 键 空串", "", RsKeyText.label(""));
        eq("RS 键 坏形状露原文（带 ~ 标记）", true,
                RsKeyText.label("rs:i:").startsWith("~") || RsKeyText.label("rs:i:").isEmpty());

        // 两种前缀互不冒充（AE 的 isAe / RS 的 isRs 是两条独立判定）
        no("RS 键 rs 串不算 ae 键", AeKeyText.isAe(water));
        no("RS 键 ae 串不算 rs 键（反向）", RsKeyText.isRs("ae:{id:\"minecraft:water\"}"));
    }


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
