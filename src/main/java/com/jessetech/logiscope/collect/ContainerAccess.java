package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.analysis.AeKeyText;
import com.jessetech.logiscope.bind.AeKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

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
     * 这个槽位能否被选作监控目标（设备槽）。
     *
     * 1.20.1 已知限制：Slot 无 isFake()（20.4+ 才引入），模组假槽/幽灵槽无统一标记、
     * 无法识别；仅保留「排除玩家背包」一条判据。详见 docs/PORT-1.20.1.md。
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
        // AE 终端才有"网络列表显示格"：先判一次菜单家族，别的容器一格反射都不做
        boolean aeMenu = AeNetworkReader.isAeStorageMenu(menu);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isSelectable(slot)) {
                continue;
            }
            out.add(aeMenu ? viewOfAe(i, slot) : make(i, slot.getContainerSlot(), slot.getItem()));
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
        if (AeNetworkReader.isAeStorageMenu(menu)) {
            return viewOfAe(index, slot);
        }
        return make(index, slot.getContainerSlot(), slot.getItem());
    }

    /**
     * **AE 终端显示格**的快照（第十九轮②）。
     *
     * 与普通槽位的两点差别：
     *   1. 数量：AE 的格子里 `ItemStack.getCount()` 恒为 1（它只是个图标），
     *      真实存量在 `RepoSlot#getStoredAmount()`；
     *   2. 身份与名字：非物品键（流体 / 化学品 / 电力）在客户端被包成一个
     *      「通用物品堆栈」物品，直接读物品 id 会让所有流体塌成
     *      `ae2:wrapped_generic_stack`。这里解包成 AE 键身份（见 {@link AeKeys}）。
     */
    private static SlotView viewOfAe(int menuSlotIndex, Slot slot) {
        Long amount = AeKeys.repoAmount(slot);
        if (amount == null) {
            return make(menuSlotIndex, slot.getContainerSlot(), slot.getItem());
        }
        AeKeys.Wrapped wrapped = AeKeys.unwrap(slot.getItem());
        boolean hasEntry = AeKeys.repoHasEntry(slot);
        if (wrapped == null) {
            // 物品键：身份仍用 registry id（与 0.1.2 之前的目标同格式，老目标靠它才能继续勾上）
            return new SlotView(menuSlotIndex, slot.getContainerSlot(),
                    itemIdOf(slot.getItem()), amount, !hasEntry, null);
        }
        // 非物品键：身份是 AE 键串；名字用游戏内真名（拿不到就退回身份串里的 id）。
        // 连身份都编不出来时**故意给一个解不开的串**（ae:?unknown）：采样会记 no_data。
        // 绝退回 `ae2:wrapped_generic_stack` —— 那会读成"这个包装物品有 0 个"，
        // 把"读不到"伪装成"确实是 0"（本项目的头号纪律）。
        String id = wrapped.keyId() != null ? wrapped.keyId() : AeKeyText.PREFIX + "?unknown";
        String label = wrapped.label() != null ? wrapped.label() : AeKeyText.label(id);
        return new SlotView(menuSlotIndex, slot.getContainerSlot(), id, amount, !hasEntry, label);
    }

    private static String itemIdOf(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    /**
     * 从任意 ItemStack 造一个快照。WorldProbe 走方块实体读值时也用它，
     * 这样"界面打开"和"坐标跟踪"两条路径产出的数据类型完全一致。
     *
     * 注意：这里的数量是**栈数量**。AE 终端的显示格要走 {@link #viewOfAe}，
     * 那里的数量来自网络条目本身（long），别拿栈数量顶替。
     */
    public static SlotView make(int menuSlotIndex, int containerSlotIndex, ItemStack stack) {
        boolean empty = stack.isEmpty();
        return new SlotView(
                menuSlotIndex,
                containerSlotIndex,
                itemIdOf(stack),
                empty ? 0 : stack.getCount(),
                empty,
                null);
    }

    /**
     * 槽位的一次只读快照。
     * 刻意不持有 ItemStack 引用 —— 存储层与游戏对象解耦，方便离线分析与导出。
     */
    public record SlotView(int menuSlotIndex, int containerSlotIndex, String itemId, long count,
                           boolean empty, @Nullable String label) {

        /** 给界面显示用的短名称，去掉命名空间前缀；AE 键走 {@link AeKeyText#label} */
        public String shortName() {
            return AeKeyText.label(itemId);
        }

        /**
         * 列表上真正画出来的那个名字（**筛选框也用它**，保证"看到什么就能筛什么"）。
         *
         * AE 的非物品键（流体 / 化学品）显示游戏内真名（{@code label}，如"水"）；
         * 其余一切（金物品 / 普通容器）显示 id 的短名 —— 与 0.1.2 之前一致，
         * 免得老截图、老习惯全部对不上。
         */
        public String displayName() {
            return label != null && !label.isBlank() ? label : shortName();
        }
    }
}
