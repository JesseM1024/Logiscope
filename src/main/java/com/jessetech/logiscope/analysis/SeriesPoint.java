package com.jessetech.logiscope.analysis;

/**
 * 图表上的一个采样点。
 *
 * @param tick        游戏刻
 * @param value       该刻这一格里的数量（**自适应数值**：整数 long 精确 / 小数 double，见 {@link Val}）
 * @param breakBefore 这一点必须与**上一个点断开**（不要连线）
 */
public final class SeriesPoint {
    private final long tick;
    private final Val value;
    private final boolean breakBefore;

    public SeriesPoint(long tick, Val value, boolean breakBefore) {
        this.tick = tick;
        this.value = value;
        this.breakBefore = breakBefore;
    }

    public long tick() {
        return tick;
    }

    public Val value() {
        return value;
    }

    public boolean breakBefore() {
        return breakBefore;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SeriesPoint other = (SeriesPoint) o;
        return java.util.Objects.equals(this.tick, other.tick) && java.util.Objects.equals(this.value, other.value) && java.util.Objects.equals(this.breakBefore, other.breakBefore);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(tick, value, breakBefore);
    }

    @Override
    public String toString() {
        return "SeriesPoint[" + "tick=" + tick + ", " + "value=" + value + ", " + "breakBefore=" + breakBefore + "]";
    }


    /** 整数值样本的便利构造（绝大多数点是整数） */
    public SeriesPoint(long tick, long value, boolean breakBefore) {
        this(tick, Val.of(value), breakBefore);
    }
}
