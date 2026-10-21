package com.jessetech.logiscope.bind;

import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.math.BlockPos;
import com.jessetech.logiscope.compat.Dim;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 探头的连接目标：接在「哪个容器的哪几格上」。
 *
 * 早期只有一格，现在是一组格子 *   需求："支持多格容器同时监控，记录的结果是聚合的（加在一起）"。
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
 * 注意：vanilla 的 Container **不保存方块坐标**，
 * 持久身份是从右键方块事件（PlayerInteractEvent.RightClickBlock）或准星射线推出来的，
 * 见 ContainerAnchor / ProbeContext。推不出来时它就是 null，此时关掉界面只能暂停。
 *
 * @param slots      被监控的格子（至少一个；还没选格时为空表）
 * @param deviceName 这台设备（方块）的名字，例如"熔炉""MEK 熔炼工厂"。
 *                   只用于界面显示 —— 实测反馈："探针实例名称里前缀加一个这个方块的名字"，
 *                   否则列表里只有"槽 0,1 · overworld 10,64,10"，认不出是哪台机器。
 *                   它**不参与任何身份判定**（身份仍然是维度+坐标），所以刷新不及时也无所谓。
 * @param dimension  维度 key；拿不到为 null
 * @param pos        方块坐标；拿不到为 null
 */
public final class ProbeTarget {
    private final List<SlotRef> slots;
    private final @Nullable String deviceName;
    private final @Nullable Dim dimension;
    private final @Nullable BlockPos pos;

    public List<SlotRef> slots() {
        return slots;
    }

    public String deviceName() {
        return deviceName;
    }

    public Dim dimension() {
        return dimension;
    }

    public BlockPos pos() {
        return pos;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProbeTarget other = (ProbeTarget) o;
        return java.util.Objects.equals(this.slots, other.slots) && java.util.Objects.equals(this.deviceName, other.deviceName) && java.util.Objects.equals(this.dimension, other.dimension) && java.util.Objects.equals(this.pos, other.pos);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(slots, deviceName, dimension, pos);
    }

    @Override
    public String toString() {
        return "ProbeTarget[" + "slots=" + slots + ", " + "deviceName=" + deviceName + ", " + "dimension=" + dimension + ", " + "pos=" + pos + "]";
    }


    /**
     * 一个被监控的格子。
     *
     * @param menuSlotIndex      菜单槽下标（读菜单用）；无菜单路径时为 -1
     * @param containerSlotIndex 容器内下标（读方块实体用）
     * @param itemId             挂上探头那一刻的内容，用于界面显示 + 服务端下标指纹验证
     * @param count              同上
     */
    public static final class SlotRef {
        private final int menuSlotIndex;
        private final int containerSlotIndex;
        private final String itemId;
        private final int count;

        public SlotRef(int menuSlotIndex, int containerSlotIndex, String itemId, int count) {
            this.menuSlotIndex = menuSlotIndex;
            this.containerSlotIndex = containerSlotIndex;
            this.itemId = itemId;
            this.count = count;
        }

        public int menuSlotIndex() {
            return menuSlotIndex;
        }

        public int containerSlotIndex() {
            return containerSlotIndex;
        }

        public String itemId() {
            return itemId;
        }

        public int count() {
            return count;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SlotRef other = (SlotRef) o;
            return menuSlotIndex == other.menuSlotIndex
                    && containerSlotIndex == other.containerSlotIndex
                    && count == other.count
                    && java.util.Objects.equals(itemId, other.itemId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(menuSlotIndex, containerSlotIndex, itemId, count);
        }

        @Override
        public String toString() {
            return "SlotRef[menuSlotIndex=" + menuSlotIndex + ", containerSlotIndex=" + containerSlotIndex + "]";
        }

        public boolean empty() {
            return count <= 0;
        }

        /** 给人看的槽号：优先显示菜单下标（界面开着时它就是用户点的那个格子） */
        public String shortLabel() {
            return menuSlotIndex >= 0 ? String.valueOf(menuSlotIndex) : ("c" + containerSlotIndex);
        }
    }

    public ProbeTarget(List<SlotRef> slots, @Nullable String deviceName, @Nullable Dim dimension, @Nullable BlockPos pos) {
        this.slots = new java.util.ArrayList<>(slots);
        this.deviceName = deviceName;
        this.dimension = dimension;
        this.pos = pos;
    }

    /** 还没选格的空目标 */
    public static final ProbeTarget EMPTY = new ProbeTarget(java.util.Collections.emptyList(), null, null, null);

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
            String dim = dimension.path();
            anchor = dim + " " + p.getX() + "," + p.getY() + "," + p.getZ();
        } else {
            anchor = new TextComponentTranslation("logiscope.anchor.runtime").getUnformattedText();
        }
        return slotSummary() + " · " + anchor;
    }

    /** 被监控的槽号列表，形如 {@code 槽 0,1,2}；太长时收成 {@code 槽 0,1,2,+5} */
    public String slotSummary() {
        if (slots.isEmpty()) {
            return new TextComponentTranslation("logiscope.slots.none").getUnformattedText();
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
        return new TextComponentTranslation("logiscope.slots.summary", sb.toString()).getUnformattedText();
    }

    /** 目标容器的一行式定位（不含槽位），列表页用 */
    public String anchorText() {
        if (!hasPersistentId()) {
            return new TextComponentTranslation("logiscope.anchor.runtime").getUnformattedText();
        }
        BlockPos p = pos;
        return dimension.path() + " " + p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
