package com.jessetech.logiscope.bind;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 数据探针的目标（第十一轮③）：**从方块实体的 NBT 里按路径取一个数** ——
 * 用来追踪物品之外的东西：FE 能量、流体量、化学品的量，以及任何能在 NBT 里
 * 找到的数字（"物品"这个概念套不住它们，所以走独立的一条采样路）。
 *
 * 【三段信息】
 *   · {@code valuePath}  采样值的路径（如 {@code energy} / {@code tanks[0].amount}）
 *   · {@code namePath}   **可选**：名称也来自 NBT 的某个路径 —— 用于"可变物品"
 *     （流体 / 化学品：罐里装的东西会变，名称就得跟着变）。每 tick 解析一次，
 *     于是"换了一种流体"在图上是**换了一条线**，语义是对的。
 *   · {@code literalName} 名称的字面量（用户手输，例如 {@code energy}）——
 *     namePath 为空或解析不到时用它兜底
 *
 * 【为什么名称要有两条来源】用户的原话：可变物品（流体、化学品）可以"选择一个值"
 * 当名称，而电力消耗"可能没名字"，就得手输。两者都要支持，所以这里是三条信息
 * 而不是一条。
 *
 * @param blockLabel 设备名（方块名），只用于显示
 */
public record DataTarget(
        String valuePath,
        @Nullable String namePath,
        String literalName,
        String blockLabel,
        ResourceKey<Level> dimension,
        BlockPos pos,
        int nbtSlot) {

    /** 普通路径目标（第十一轮③那套） */
    public DataTarget(String valuePath, @Nullable String namePath, String literalName,
                      String blockLabel, ResourceKey<Level> dimension, BlockPos pos) {
        this(valuePath, namePath, literalName, blockLabel, dimension, pos, -1);
    }

    /**
     * **NBT 槽位目标**（第十六轮）：{@code valuePath} = 物品列表在 NBT 里的路径
     * （如 {@code Items}），{@code nbtSlot} = 元素自带 {@code slot} 号。
     * 读数 = "列表里 slot == nbtSlot 的元素的 Item.Count"。
     *
     * 这一路**完全不碰菜单**，所以 GUI 开不开读数都一样 —— 正是 MEK 那类
     * "槽位号写在 NBT 里"的机器需要的语义。
     */
    public static DataTarget slotAnchor(String listPath, String itemId, String blockLabel,
                                        ResourceKey<Level> dimension, BlockPos pos, int slot) {
        return new DataTarget(listPath, null, itemId, blockLabel, dimension, pos, slot);
    }

    /** 是不是 NBT 槽位目标 */
    public boolean isSlotAnchor() {
        return nbtSlot >= 0;
    }

    public boolean hasNamePath() {
        return namePath != null && !namePath.isBlank();
    }

    /** 名称兜底：手输的名字 → 没有就用值路径（认得出是哪个数） */
    public String fallbackName() {
        if (literalName != null && !literalName.isBlank()) {
            return literalName;
        }
        return valuePath == null || valuePath.isBlank() ? "value" : valuePath;
    }

    public String anchorText() {
        return dimension.location().getPath() + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 面板上的一行描述 */
    public String describe() {
        String label = blockLabel == null || blockLabel.isBlank() ? "block" : blockLabel;
        if (isSlotAnchor()) {
            return label + " · " + valuePath + "[slot=" + nbtSlot + "] · " + anchorText();
        }
        return label + " · " + valuePath + " · " + anchorText();
    }
}
