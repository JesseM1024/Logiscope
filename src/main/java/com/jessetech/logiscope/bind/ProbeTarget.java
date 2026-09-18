package com.jessetech.logiscope.bind;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 探头的连接目标：接在「哪个容器的哪个格子上」。
 *
 * 【两种身份，同时持有】
 *
 * 1. 运行时身份（slotIndex）
 *    菜单里的槽位下标。只要界面开着就一定可用，但它只在本次界面存活期间有效。
 *
 * 2. 持久身份（dimension + pos + containerSlotIndex）
 *    维度 + 方块坐标 + 容器内下标。它让"关掉界面之后还能继续跟踪"成为可能：
 *      · 单人：直接去集成服务端那台方块实体上读，数据是真的
 *      · 多人：读不到（客户端方块实体没有内容），于是记 no_data，
 *              等玩家重新打开界面时再拿真实值 —— 两段之间的差值依然有效
 *
 * 注意：vanilla 的 AbstractContainerMenu **不保存方块坐标**，
 * 持久身份是从右键方块事件（PlayerInteractEvent.RightClickBlock）推出来的，
 * 见 ContainerAnchor。推不出来时（例如某些模组用快捷键开界面）它就是 null，
 * 此时关掉界面只能暂停，不会瞎记。
 *
 * @param slotIndex          menu.slots 里的下标（用于界面打开时读值）
 * @param containerSlotIndex 容器内部下标（用于界面关闭后从方块实体读值，也是给人看的槽号）
 * @param itemId             接上探头瞬间该格子的物品 ID，仅用于界面显示
 * @param dimension          维度 key；拿不到为 null
 * @param pos                方块坐标；拿不到为 null
 */
public record ProbeTarget(
        int slotIndex,
        int containerSlotIndex,
        String itemId,
        @Nullable ResourceKey<Level> dimension,
        @Nullable BlockPos pos) {

    /** 是否具备"关掉界面之后还能找回来"的持久身份 */
    public boolean hasPersistentId() {
        return dimension != null && pos != null;
    }

    /** 一行式描述，给面板用 */
    public String describe() {
        String anchor;
        if (hasPersistentId()) {
            BlockPos p = pos;
            String dim = dimension.location().getPath();
            anchor = dim + " " + p.getX() + "," + p.getY() + "," + p.getZ();
        } else {
            anchor = "运行时身份";
        }
        return "槽 " + containerSlotIndex + " · " + anchor + " · " + shortItem();
    }

    private String shortItem() {
        if (itemId == null) {
            return "-";
        }
        int i = itemId.indexOf(':');
        return i < 0 ? itemId : itemId.substring(i + 1);
    }
}
