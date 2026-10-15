package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

/**
 * 采样值：**能精确用整数表示就用整数（long），小数 / 超长整数用 double** —— 自适应。
 *
 * 【为什么要有它】
 * 旧实现的样本值是 {@code int}（±21 亿），两个后果：
 *   · 能量类读数（FE / J，MEK 创造能量立方是 2^63）装不下，{@code (int)} 截断会**回绕成负数**
 *     —— 图上画出一根贴在 -1 的假线；
 *   · 浮点量（流体 mB/t、温度、AE2 的 double 能量）没法表达。
 * 但也不能一改成 double 了事：**整数读数必须保持精确**（需求：只有纵轴可以用科学计数法），
 * 而 {@code long → double} 在 2^53 以上会丢精度（2^53+1 会变 2^53）。
 *
 * 所以自适应的规则是：**同一个值，装得进 long 就以 long 保存（integral = true），
 * 否则以 double 保存（integral = false）**。两份都在（{@code i} 与 {@code d} 互为影子），
 * 画图/统计一律走 {@link #asDouble()}（够用、零分配），读数在 integral 时走
 * {@link #i} 精确打印。
 *
 * 纯逻辑（不引用任何游戏类），离线可断言（TestChart t37）*
 * 【两条诚实边界】
 *   · **求和溢出**：long 求和溢出时回退成 double 并把结果标成非整数
 *     —— 宁可丢精度，不回绕成负数；
 *   · **double 非有限**（NaN / Infinity）：{@link #of(double)} 返回 null，
 *     调用方按"读不到"处理（留空），绝不把 NaN 变成 0。
 */
public record Val(boolean integral, long i, double d) implements Comparable<Val> {

    public static final Val ZERO = of(0L);

    /** 整数值（永不为 null） */
    public static Val of(long v) {
        return new Val(true, v, (double) v);
    }

    /**
     * 小数值；**非有限（NaN / Infinity）返回 null** —— 调用方按"读不到"处理。
     * 数学上是整数的 double（3.0）会被规范化成整数形式（读数打 "3" 而不是 "3.0"）。
     */
    @Nullable
    public static Val of(double v) {
        if (!Double.isFinite(v)) {
            return null;
        }
        if (v == Math.rint(v) && v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
            return new Val(true, (long) v, v);
        }
        return new Val(false, 0L, v);
    }

    /** 是不是整数形式（读数用它决定"精确整数"还是"短格式小数"） */
    public boolean isIntegral() {
        return integral;
    }

    /**
     * **文本 → 数值**（尽力精确）：给 DATA 页「强制按数值读取」用 ——
     * 有些模组把数值存成**字符串** NBT（如 {@code "energy": "1000"}），
     * 这个入口把那段文本转回数值；转不出来按"读不到"处理（上层记 no_data）。
     *
     * 规则（与 {@link #of(double)} 的两条诚实边界同一口径）：
     *   · 先试 {@code Long.parseLong} —— 整数读数必须保持精确；
     *   · **纯整数形式但超出 long** → 返回 null（宁 no-data，不近似成 double）；
     *   · 其余走 {@code Double.parseDouble}，非有限（NaN / Infinity）由
     *     {@link #of(double)} 过滤成 null；
     *   · 空串 / 任意文本 → null。
     */
    @Nullable
    public static Val parseNumericText(@Nullable String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return of(Long.parseLong(t));
        } catch (NumberFormatException ignored) {
            // 不是 long 范围内的整数：继续往下试
        }
        if (isPureInteger(t)) {
            return null;   // 纯整数但超 long 范围：拒绝近似
        }
        try {
            return of(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 纯整数形式（可带正负号，其余全是数字）—— 给"超 long 的整数拒绝近似"那一步用 */
    private static boolean isPureInteger(String t) {
        int start = t.charAt(0) == '+' || t.charAt(0) == '-' ? 1 : 0;
        if (start >= t.length()) {
            return false;
        }
        for (int i = start; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 整数形式的原值（仅当 {@link #isIntegral()} 时有意义） */
    public long longValue() {
        return i;
    }

    /** 画图 / 统计用的浮点值（零分配、零分支开销） */
    public double asDouble() {
        return integral ? (double) i : d;
    }

    /** 是不是 0（两种形式都覆盖） */
    public boolean isZero() {
        return integral ? i == 0L : d == 0.0;
    }

    /**
     * 加：同为整数且**和不溢出** → 整数和（精确）；否则 double 和。
     * 溢出判定用经典的符号检查：若结果与两个操作数符号都不同，说明回绕了。
     */
    public Val plus(Val o) {
        if (integral && o.integral) {
            long r = i + o.i;
            if (((i ^ r) & (o.i ^ r)) >= 0) {
                return new Val(true, r, (double) r);
            }
        }
        // 溢出回退必须是"非整数形式"：不能走 of(double)——它的规范化会把
        // 2^63 折回 Long.MAX_VALUE，等于把溢出悄悄抹掉
        return new Val(false, 0L, asDouble() + o.asDouble());
    }

    /** 减：IO（净流量）= 存量之差。溢出口径同 {@link #plus} */
    public Val minus(Val o) {
        if (integral && o.integral) {
            long r = i - o.i;
            if (((i ^ o.i) & (r ^ i)) >= 0) {
                return new Val(true, r, (double) r);
            }
        }
        return new Val(false, 0L, asDouble() - o.asDouble());
    }

    /** 比较：同为整数按 long 比（精确），否则按 double */
    @Override
    public int compareTo(Val o) {
        if (integral && o.integral) {
            return Long.compare(i, o.i);
        }
        return Double.compare(asDouble(), o.asDouble());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Val v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        long bits = Double.doubleToLongBits(asDouble());
        return (int) (bits ^ (bits >>> 32));
    }

    @Override
    public String toString() {
        return integral ? Long.toString(i) : Double.toString(d);
    }
}
