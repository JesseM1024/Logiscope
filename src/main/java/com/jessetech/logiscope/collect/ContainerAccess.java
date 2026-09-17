package com.jessetech.logiscope.collect;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★★ 本项目的技术基石 ★★★
 *
 * 结论：容器内容【已经同步到客户端】了，纯客户端模组可以直接读。
 *
 * 原理：玩家打开任意容器时，服务端会通过
 *   ClientboundContainerSetContentPacket / ClientboundContainerSetSlotPacket
 * 把槽位内容同步下来，落在 AbstractContainerMenu.slots（public 字段）里。
 * 所以本模组【不需要发任何网络包、不需要服务端配合】，
 * 直接读 player.containerMenu.slots.get(i).getItem() 就是当前真实内容。
 *
 * 代价（必须写清楚，否则会误判成 bug）：
 *   1. 只能看到容器界面【打开着】的这段时间。界面一关，菜单对象被替换，数据源消失。
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

    /** 列出所有槽位的快照，供界面展示与选择 */
    public static List<SlotView> snapshot() {
        List<SlotView> out = new ArrayList<>();
        AbstractContainerMenu menu = currentMenu();
        if (menu == null) {
            return out;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            SlotView v = toView(menu, i);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /** 读单个槽位；越界或没有菜单时返回 null */
    public static SlotView read(int slotIndex) {
        AbstractContainerMenu menu = currentMenu();
        if (menu == null || slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return null;
        }
        return toView(menu, slotIndex);
    }

    private static SlotView toView(AbstractContainerMenu menu, int index) {
        Slot slot = menu.slots.get(index);
        ItemStack stack = slot.getItem();
        boolean empty = stack.isEmpty();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new SlotView(
                index,                              // 菜单内下标，用于定位
                slot.getContainerSlot(),            // 容器内下标，用于界面显示
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
