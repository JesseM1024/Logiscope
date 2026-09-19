package com.jessetech.logiscope.analysis;

/**
 * 图表上的一个采样点。
 *
 * @param tick        游戏刻
 * @param value       该刻这一格里的数量（**自适应数值**：整数 long 精确 / 小数 double，见 {@link Val}）
 * @param breakBefore 这一点必须与**上一个点断开**（不要连线）
 */
public record SeriesPoint(long tick, Val value, boolean breakBefore) {

    /** 整数值样本的便利构造（绝大多数点是整数） */
    public SeriesPoint(long tick, long value, boolean breakBefore) {
        this(tick, Val.of(value), breakBefore);
    }
}
