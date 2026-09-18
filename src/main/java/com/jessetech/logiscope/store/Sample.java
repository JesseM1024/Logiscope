package com.jessetech.logiscope.store;

/**
 * 一次采样：在第 tick 个游戏刻，被探测的槽位里是 itemId × count。
 *
 * 刻意不引用 ItemStack / 世界对象，只保留可序列化的最小信息 ——
 * 这样存储层和分析层都能脱离游戏运行（方便写单元测试、导出、离线画图）。
 *
 * 【为什么需要 NO_DATA 哨兵】
 * 玩家关掉容器界面之后转为"坐标跟踪"：单人能从集成服务端读到真实内容，
 * 多人则读不到（容器内容只在菜单打开期间由服务端同步下来）。
 * 这种情况下**不跳过这一 tick**，而是写入一条 noData 样本，
 * 让时间轴保持连续 —— 这样"两人次交互之间的差值"依然是有效信息，
 * 图上也能明确画出一段"无数据"的区间，而不是让人误以为数值一直是 0。
 */
public record Sample(long tick, String itemId, int count) {

    public static final String EMPTY_ITEM = "minecraft:air";

    /** 无数据哨兵。不是物品，只是"这一 tick 没有取到值"。 */
    public static final String NO_DATA_ITEM = "logiscope:no_data";

    /** 造一条"无数据"样本 */
    public static Sample noData(long tick) {
        return new Sample(tick, NO_DATA_ITEM, 0);
    }

    /** 这一 tick 是否没取到值（与"格子是空的"是两件事，不要混） */
    public boolean isNoData() {
        return NO_DATA_ITEM.equals(itemId);
    }

    /** 格子是空的（有数据，但内容为空） */
    public boolean isEmpty() {
        return !isNoData() && (count <= 0 || EMPTY_ITEM.equals(itemId));
    }

    public String shortName() {
        if (isNoData()) {
            return "(no data)";
        }
        int i = itemId.indexOf(':');
        return i < 0 ? itemId : itemId.substring(i + 1);
    }
}
