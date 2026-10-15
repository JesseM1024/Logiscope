package com.jessetech.logiscope.collect;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 本项目的技术基石 *
 * 结论：容器内容【已经同步到客户端】了，纯客户端模组可以直接读。
 *
 * 原理：玩家打开任意容器时，服务端会通过
 *   ClientboundContainerSetContentPacket / ClientboundContainerSetSlotPacket
 *   把槽位内容同步下来，落在 AbstractContainerMenu.slots（public 字段）里。
 *   所以本模组【不需要发任何网络包、不需要服务端配合】，
 *   直接读 player.containerMenu.slots.get(i).getItem() 就是当前真实内容。
 *
 * 代价（必须写清楚，否则会误判成 bug）：
 *   1. 同步只在**界面打开着**的这段时间有效。界面一关，客户端这边就没有内容了。
 *      ——关掉界面之后的取值走 WorldProbe（集成服务端），见那个类。
 *   2. 若某个模组不用 AbstractContainerMenu 承载内容（例如自绘界面 + 自定义包），
 *      它的槽位在本模组看来可能是空的。这是"只读客户端同步数据"的固有边界。
 *   3. 与真实值存在一个网络往返的延迟（约 1 个 tick 量级），做速率分析时要记住这点。
 */
public final class ContainerAccess {

    private ContainerAccess() {
    }

    /** 当前是否有「外部设备容器」打开（排除玩家自己的背包） */
    public static boolean hasForeignContainerOpen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu;
    }

    public static AbstractContainerMenu currentMenu() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.containerMenu;
    }

    /** 当前菜单的容器 ID；没有菜单时返回 -1 */
    public static int currentContainerId() {
        AbstractContainerMenu menu = currentMenu();
        return menu == null ? -1 : menu.containerId;
    }

    /**
     * 这个槽位是不是**玩家自己背包**里的格子。
     *
     * 一处实现与文档不一致的修正。文档（ARCHITECTURE.md CP1）称"ContainerAccess#snapshot 会把玩家自己的背包
     * 整体排除掉"，但代码只判了 "整个菜单 == inventoryMenu" 这一种情况 ——
     * 于是**打开箱子时**，列表里其实混着下面 36 格玩家背包。
     * 单格时代它只是"列表有点长"；多格监控 + 可视化选格开始之后，
     * 它变成"点到自己背包的格子也会被当成设备槽"，语义就错了。
     *
     * 判定依据：vanilla 给玩家背包建的槽位是 {@code new Slot(player.getInventory(), i, x, y)}，
     * 也就是 {@code slot.container} 恰好是玩家的 Inventory 实例（`Slot.container` 是 public 字段）。
     */
    public static boolean isPlayerSlot(Slot slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        Inventory inv = mc.player.getInventory();
        return slot.container == inv;
    }

    /**
     * 这个槽位能不能被选作监控目标（设备槽）。
     *
     * ★ 1.20.1 已知限制：Forge 的 Slot 没有 isFake()（NeoForge 20.4+ 才加）★
     * 1.20.1 生态里模组的假槽/幽灵槽没有统一标记，无法识别 ——
     * 只保留"排除玩家背包"这一条判据。见 docs/PORT-1.20.1.md。
     */
    public static boolean isSelectable(Slot slot) {
        return !isPlayerSlot(slot);
    }

    /**
     * 列出所有**设备槽位**的快照，供界面展示与选择。
     *
     * 只列「外部设备」的槽位 *   · 没有外部容器界面时直接返回空（面板可以在关掉容器界面后单独打开，
     *     那时 containerMenu 是玩家自己的背包，列出来会让人误以为那是机器）
     *   · 有外部容器界面时，跳过玩家背包那 36 格与模组的假槽位（见 isSelectable）
     */
    public static List<SlotView> snapshot() {
        List<SlotView> out = new ArrayList<>();
        if (!hasForeignContainerOpen()) {
            return out;
        }
        AbstractContainerMenu menu = currentMenu();
        if (menu == null) {
            return out;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isSelectable(slot)) {
                continue;
            }
            out.add(make(i, slot.getContainerSlot(), slot.getItem()));
        }
        return out;
    }

    /** 读单个菜单槽位；越界或没有菜单时返回 null */
    public static SlotView read(int slotIndex) {
        AbstractContainerMenu menu = currentMenu();
        if (menu == null || slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return null;
        }
        return toView(menu, slotIndex);
    }

    /** 按容器内下标反查菜单槽；找不到返回 null */
    public static SlotView findByContainerIndex(int containerSlotIndex) {
        AbstractContainerMenu menu = currentMenu();
        if (menu == null) {
            return null;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isSelectable(slot) || slot.getContainerSlot() != containerSlotIndex) {
                continue;
            }
            return toView(menu, i);
        }
        return null;
    }

    private static SlotView toView(AbstractContainerMenu menu, int index) {
        Slot slot = menu.slots.get(index);
        return make(index, slot.getContainerSlot(), slot.getItem());
    }

    /**
     * 从任意 ItemStack 造一个快照。WorldProbe 走方块实体读值时也用它，
     * 这样"界面打开"和"坐标跟踪"两条路径产出的数据类型完全一致。
     */
    public static SlotView make(int menuSlotIndex, int containerSlotIndex, ItemStack stack) {
        boolean empty = stack.isEmpty();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new SlotView(
                menuSlotIndex,
                containerSlotIndex,
                id == null ? "unknown" : id.toString(),
                empty ? 0 : stack.getCount(),
                empty);
    }

    /**
     * 槽位的一次只读快照。
     * 刻意不持有 ItemStack 引用 —— 存储层与游戏对象解耦，方便离线分析与导出。
     */
    public record SlotView(int menuSlotIndex, int containerSlotIndex, String itemId, int count, boolean empty) {

        /** 给界面显示用的短名称，去掉命名空间前缀 */
        public String shortName() {
            int i = itemId.indexOf(':');
            return i < 0 ? itemId : itemId.substring(i + 1);
        }
    }
}
