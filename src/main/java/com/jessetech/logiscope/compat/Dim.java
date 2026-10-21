package com.jessetech.logiscope.compat;

import net.minecraft.world.World;

/**
 * **维度的等价物**（1.12.2 线专用）。
 *
 * 1.16.5+ 用 {@code Dim} 标识维度；1.12.2 **没有这个概念** ——
 * 维度就是一个 {@code int}（{@code world.provider.getDimension()}，
 * 0 = 主世界 / -1 = 下界 / 1 = 末地，模组维度是别的数）。
 *
 * 为什么不直接把各处改成 {@code int}：
 *   · 那一串代码里维度是要**比较相等**、**当 Map 键**、**拼成显示名**的，
 *     裸 int 会把这些地方变成散落的魔数；
 *   · {@code ProbeTarget} / {@code DataTarget} 的构造器有 {@code @Nullable} 维度，
 *     裸 int 没法表达"没有维度"（用 -999 之类的哨兵更糟）。
 * 所以包一层：语义与 {@code Dim} 一一对应，改调用点只是换类型名。
 *
 * 【持久化】本模组的探针 / 目标**只在内存里**（归档只存样本，不存目标），
 * 所以这里不需要序列化格式；真要存的话用 {@link #id()}。
 */
public final class Dim {

    /** 主世界 */
    public static final int OVERWORLD = 0;

    private final int id;

    private Dim(int id) {
        this.id = id;
    }

    public static Dim of(int id) {
        return new Dim(id);
    }

    /** 从世界对象取维度（对应 1.16.5 的 {@code Dim.of(world)}） */
    public static Dim of(World world) {
        return new Dim(world.provider.getDimension());
    }

    public int id() {
        return id;
    }

    /** 给人看的短名：主世界 / 下界 / 末地 / dim&lt;n&gt;（对应 1.16.5 的 {@code key.path()}） */
    public String path() {
        switch (id) {
            case 0:
                return "overworld";
            case -1:
                return "the_nether";
            case 1:
                return "the_end";
            default:
                return "dim" + id;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Dim)) {
            return false;
        }
        return id == ((Dim) o).id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return path();
    }
}
