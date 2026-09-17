package com.jessetech.logiscope.store;

/**
 * 一次采样：在第 tick 个游戏刻，被探测的槽位里是 itemId × count。
 *
 * 刻意不引用 ItemStack / 世界对象，只保留可序列化的最小信息 ——
 * 这样存储层和分析层都能脱离游戏运行（方便写单元测试、导出、离线画图）。
 */
public record Sample(long tick, String itemId, int count) {

    public static final String EMPTY_ITEM = "minecraft:air";

    public boolean isEmpty() {
        return count <= 0 || EMPTY_ITEM.equals(itemId);
    }

    public String shortName() {
        int i = itemId.indexOf(':');
        return i < 0 ? itemId : itemId.substring(i + 1);
    }
}
