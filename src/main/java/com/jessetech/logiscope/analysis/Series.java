package com.jessetech.logiscope.analysis;

import com.jessetech.logiscope.store.Sample;

import java.util.List;

/**
 * 一条「物品数据线」：某个物品 ID 在被监控的那些槽位里的全部采样点。
 *
 * 为什么一个实例会有多条线 * 探针盯的是**若干格子**（多格监控），而格子里放的东西会变、也可能同时装好几种。
 * 与其把"铁锭 → 空 → 铜锭 → 铁锭"画成一条连来连去的折线（那些连线毫无意义），
 * 不如按物品分组：每种物品一条自己的线，各给一个颜色。
 *
 * 每条线其实有两层数据：存量（level）与单 tick 流量（flow）*
 * | 层 | 含义 | 单位 |
 * |---|---|---|
 * | {@link #points()} | 这一刻被监控格子里这种物品**一共有多少个**（存量） | 个 |
 * | {@link #flow()} | 这一刻相对**上一个世界 tick** 净变了多少（`count − 上一 tick 的 count`） | 个/tick |
 *
 * 存量的线回答"现在有多少"，流量的线回答"现在进/出多快"——
 * 后者在**存量很大且基本不变**的场合是唯一看得出来的信息
 * （例如一台机器 64/t 抽走、另一条管道 64/t 补进来，存量是平的，流量却是满的）。
 * 两条线各自独立开关：开关状态在界面层（图例里"物品 → IO"两级），
 * 分析层只负责把数据算出来。
 *
 * @param itemId   物品 ID（`minecraft:air` 表示格子是空的，它也是一条正经的线：数量恒为 0）
 * @param points   存量的点，按 tick 升序（保证有序是所有二分查找的前提）
 * @param flow     流量的点，按 tick 升序；**只在相邻两格之间**产生（段起点、跨格、断档后都没有）
 * 值是**自适应数值**（{@link Val}）：整数 long 精确 / 小数 double。
 *
 * @param min      存量线的最小值
 * @param max      存量线的最大值
 * @param flowMin  流量线的最小值（没流量时为 0）
 * @param flowMax  流量线的最大值（没流量时为 0）
 */
public record Series(String itemId, List<SeriesPoint> points, List<SeriesPoint> flow,
                     Val min, Val max, Val flowMin, Val flowMax) {

    /** 最近一次读到的数量（图例上用） */
    public Val lastCount() {
        return points.isEmpty() ? Val.ZERO : points.get(points.size() - 1).value();
    }

    /** 最近一次的净流量；没有流量数据时返回 0 */
    public Val lastFlow() {
        return flow.isEmpty() ? Val.ZERO : flow.get(flow.size() - 1).value();
    }

    /** 这条线是不是"格子空着" */
    public boolean isEmptyItem() {
        return Sample.EMPTY_ITEM.equals(itemId);
    }

    /** 存量线的断点个数（= 这条线被切成了几段，段数 = breaks + 1） */
    public int breaks() {
        return countBreaks(points);
    }

    /** 流量线的断点个数 */
    public int flowBreaks() {
        return countBreaks(flow);
    }

    /** 这条线有没有可画的流量点 */
    public boolean hasFlow() {
        return !flow.isEmpty();
    }

    private static int countBreaks(List<SeriesPoint> pts) {
        int n = 0;
        for (SeriesPoint p : pts) {
            if (p.breakBefore()) {
                n++;
            }
        }
        return n;
    }
}
