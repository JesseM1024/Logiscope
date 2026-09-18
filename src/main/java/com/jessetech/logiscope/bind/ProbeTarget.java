package com.jessetech.logiscope.bind;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 探头的连接目标：接在「哪个容器的哪几格上」。
 *
 * ★ 第九轮（多格监控）之前只有一格，现在是一组格子 ★
 *   需求："支持多格容器同时监控，记录的结果是聚合的（加在一起）"。
 *   所以一个探针实例 = 一台设备 + 一组被监控的格子，每 tick 只写**一条**样本
 *   （多条槽位的内容聚合成一条，规则见 {@code analysis/Aggregator}）。
 *   好处是下游（存储 / 分组 / 波形 / 框选统计）一行都不用改 —— 它们看到的
 *   仍然是一条扁平的时间序列，语义从"这一格的内容"变成"这几个格子加起来的内容"。
 *
 * 【两种身份，同时持有】（与单格时代一致，只是变成"每格各有一份"）
 *
 * 1. 运行时身份（menuSlotIndex）
 *    菜单里的槽位下标。只要界面开着就一定可用，但它只在本次界面存活期间有效。
 *    **无 GUI 的容器（准星直接创建的那种）没有菜单 → 恒为 -1。**
 *
 * 2. 持久身份（dimension + pos + containerSlotIndex）
 *    维度 + 方块坐标 + 容器内下标。它让"关掉界面之后还能继续跟踪"成为可能：
 *      · 单人：直接去集成服务端那台方块实体上读，数据是真的
 *      · 多人：读不到（客户端方块实体没有内容），于是记 no_data，
 *              等玩家重新打开界面时再拿真实值 —— 两段之间的差值依然有效
 *
 * 注意：vanilla 的 AbstractContainerMenu **不保存方块坐标**，
 * 持久身份是从右键方块事件（PlayerInteractEvent.RightClickBlock）或准星射线推出来的，
 * 见 ContainerAnchor / ProbeContext。推不出来时它就是 null，此时关掉界面只能暂停。
 *
 * @param slots     被监控的格子（至少一个；还没选格时为空表）
 * @param dimension 维度 key；拿不到为 null
 * @param pos       方块坐标；拿不到为 null
 */
public record ProbeTarget(
        List<SlotRef> slots,
        @Nullable ResourceKey<Level> dimension,
        @Nullable BlockPos pos) {

    /**
     * 一个被监控的格子。
     *
     * @param menuSlotIndex      菜单槽下标（读菜单用）；无菜单路径时为 -1
     * @param containerSlotIndex 容器内下标（读方块实体用）
     * @param itemId             挂上探头那一刻的内容，用于界面显示 + 服务端下标指纹验证
     * @param count              同上
     */
    public record SlotRef(int menuSlotIndex, int containerSlotIndex, String itemId, int count) {

        public boolean empty() {
            return count <= 0;
        }

        /** 给人看的槽号：优先显示菜单下标（界面开着时它就是用户点的那个格子） */
        public String shortLabel() {
            return menuSlotIndex >= 0 ? String.valueOf(menuSlotIndex) : ("c" + containerSlotIndex);
        }
    }

    public ProbeTarget {
        slots = List.copyOf(slots);
    }

    /** 还没选格的空目标 */
    public static final ProbeTarget EMPTY = new ProbeTarget(List.of(), null, null);

    /** 是否具备"关掉界面之后还能找回来"的持久身份 */
    public boolean hasPersistentId() {
        return dimension != null && pos != null;
    }

    /** 是否至少有一格能走"读菜单"这条路（无 GUI 容器全都是 -1，只能走世界侧读取） */
    public boolean hasMenuPath() {
        for (SlotRef s : slots) {
            if (s.menuSlotIndex() >= 0) {
                return true;
            }
        }
        return false;
    }

    public int slotCount() {
        return slots.size();
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
        return slotSummary() + " · " + anchor;
    }

    /** 被监控的槽号列表，形如 {@code 槽 0,1,2}；太长时收成 {@code 槽 0,1,2,+5} */
    public String slotSummary() {
        if (slots.isEmpty()) {
            return "（未选格）";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (SlotRef s : slots) {
            if (shown == 3) {
                sb.append(",+").append(slots.size() - shown);
                break;
            }
            if (shown > 0) {
                sb.append(',');
            }
            sb.append(s.shortLabel());
            shown++;
        }
        return "槽 " + sb;
    }

    /** 目标容器的一行式定位（不含槽位），列表页用 */
    public String anchorText() {
        if (!hasPersistentId()) {
            return "运行时身份";
        }
        BlockPos p = pos;
        return dimension.location().getPath() + " " + p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
