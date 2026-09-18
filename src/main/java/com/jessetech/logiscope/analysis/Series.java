package com.jessetech.logiscope.analysis;

import com.jessetech.logiscope.store.Sample;

import java.util.List;

/**
 * 一条「物品数据线」：某个物品 ID 在被探测的那一格里的全部采样点。
 *
 * ★ 为什么一个格子会有多条线 ★
 * 探针盯的是**一个槽位**，而槽位里放的东西会变。
 * 与其把"铁锭 → 空 → 铜锭 → 铁锭"画成一条连来连去的折线（那些连线毫无意义），
 * 不如按物品分组：每种物品一条自己的线，各给一个颜色。
 * 物品切换处天然断开，颜色的变化本身就是"这里换东西了"的信号。
 *
 * @param itemId 物品 ID（`minecraft:air` 表示格子是空的，它也是一条正经的线：数量恒为 0）
 * @param points 按 tick 升序排列（保证有序是所有二分查找的前提）
 * @param min    这条线的最小值
 * @param max    这条线的最大值
 */
public record Series(String itemId, List<SeriesPoint> points, int min, int max) {

    /** 最近一次读到的数量（图例上用） */
    public int lastCount() {
        return points.isEmpty() ? 0 : points.get(points.size() - 1).count();
    }

    /** 这条线是不是"格子空着" */
    public boolean isEmptyItem() {
        return Sample.EMPTY_ITEM.equals(itemId);
    }

    /** 断点个数（= 这条线被切成了几段，段数 = breaks + 1） */
    public int breaks() {
        int n = 0;
        for (SeriesPoint p : points) {
            if (p.breakBefore()) {
                n++;
            }
        }
        return n;
    }
}
