package com.jessetech.logiscope.analysis;

/**
 * 图表上的一个采样点。
 *
 * @param tick        游戏刻
 * @param count       该刻这一格里的物品数量
 * @param breakBefore 这一点必须与**上一个点断开**（不要连线）
 */
public record SeriesPoint(long tick, int count, boolean breakBefore) {
}
