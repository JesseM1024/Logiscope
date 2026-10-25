package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.bind.AeKeys;
import com.jessetech.logiscope.analysis.RsKeyText;
import com.jessetech.logiscope.bind.RsKeys;
import net.minecraft.client.Minecraft;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 本项目的技术基石 *
 * 结论：容器内容【已经同步到客户端】了，纯客户端模组可以直接读。
 *
 * 原理：玩家打开任意容器时，服务端会通过
 *   ClientboundContainerSetContentPacket / ClientboundContainerSetSlotPacket
 *   把槽位内容同步下来，落在 Container.slots（public 字段）里。
 *   所以本模组【不需要发任何网络包、不需要服务端配合】，
 *   直接读 player.containerMenu.inventorySlots.get(i).getStackInSlot() 就是当前真实内容。
 *
 * 代价（必须写清楚，否则会误判成 bug）：
 *   1. 同步只在**界面打开着**的这段时间有效。界面一关，客户端这边就没有内容了。
 *      ——关掉界面之后的取值走 WorldProbe（集成服务端），见那个类。
 *   2. 若某个模组不用 Container 承载内容（例如自绘界面 + 自定义包），
 *      它的槽位在本模组看来可能是空的。这是"只读客户端同步数据"的固有边界。
 *   3. 与真实值存在一个网络往返的延迟（约 1 个 tick 量级），做速率分析时要记住这点。
 */
public final class ContainerAccess {

    private ContainerAccess() {
    }

    /** 当前是否有「外部设备容器」打开（排除玩家自己的背包） */
    public static boolean hasForeignContainerOpen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.openContainer != mc.player.container;
    }

    public static Container currentMenu() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.openContainer;
    }

    /** 当前菜单的容器 ID；没有菜单时返回 -1 */
    public static int currentContainerId() {
        Container menu = currentMenu();
        return menu == null ? -1 : menu.windowId;
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
     * 也就是 {@code slot.inventory} 恰好是玩家的 PlayerInventory 实例（`Slot.container` 是 public 字段）。
     */
    public static boolean isPlayerSlot(Slot slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        PlayerInventory inv = mc.player.inventory;
        return slot.inventory == inv;
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
        Container menu = currentMenu();
        if (menu == null) {
            return out;
        }
        // AE 终端：菜单里的 RepoSlot 是"网络列表的显示窗口"，数量与身份都得另读
        // （见 viewOfAe）。8.x 与 19.x 在这一点上一致：终端界面 init 时把 RepoSlot
        // RS 网格：**行不在菜单里**（在界面的 IGridView 上，见 RsKeys 的类注释），
        // 而菜单里的槽位是合成格 / 样板格 —— 一行都不能列，否则"点了个合成格"会被当成
        // "网络里的东西"。所以这里直接返回空表，行由 ScopeScreen 从界面取。
        if (RsKeys.isRsGridMenu(menu)) {
            return out;
        }
        // 塞进菜单，所以选格页不用另开一条数据源。
        boolean aeMenu = AeNetworkReader.isAeStorageMenu(menu);
        for (int i = 0; i < menu.inventorySlots.size(); i++) {
            Slot slot = menu.inventorySlots.get(i);
            if (!isSelectable(slot)) {
                continue;
            }
            out.add(aeMenu ? viewOfAe(i, slot) : make(i, slot.getSlotIndex(), slot.getStack()));
        }
        return out;
    }

    /** 读单个菜单槽位；越界或没有菜单时返回 null */
    public static SlotView read(int slotIndex) {
        Container menu = currentMenu();
        if (menu == null || slotIndex < 0 || slotIndex >= menu.inventorySlots.size()) {
            return null;
        }
        return toView(menu, slotIndex);
    }

    /** 按容器内下标反查菜单槽；找不到返回 null */
    public static SlotView findByContainerIndex(int containerSlotIndex) {
        Container menu = currentMenu();
        if (menu == null) {
            return null;
        }
        for (int i = 0; i < menu.inventorySlots.size(); i++) {
            Slot slot = menu.inventorySlots.get(i);
            if (!isSelectable(slot) || slot.getSlotIndex() != containerSlotIndex) {
                continue;
            }
            return toView(menu, i);
        }
        return null;
    }

    private static SlotView toView(Container menu, int index) {
        Slot slot = menu.inventorySlots.get(index);
        if (AeNetworkReader.isAeStorageMenu(menu)) {
            return viewOfAe(index, slot);
        }
        return make(index, slot.getSlotIndex(), slot.getStack());
    }

    /**
     * **AE 终端显示格**的快照。
     *
     * 与普通槽位的两点差别：
     *   1. 数量：AE 的格子里 `ItemStack.getCount()` 恒为 1（它只是个图标），
     *      真实存量在 `RepoSlot#getStoredAmount()`；
     *   2. 身份：流体在 8.x 里被渲染成同一个占位物品（`ae2:fluid_dummy`），
     *      直接读物品 id 会让所有流体塌成一个身份 —— 这里换成 AE 键身份（见 {@link AeKeys}）。
     */
    private static SlotView viewOfAe(int menuSlotIndex, Slot slot) {
        Long amount = AeKeys.repoAmount(slot);
        if (amount == null) {
            return make(menuSlotIndex, slot.getSlotIndex(), slot.getStack());
        }
        boolean hasEntry = AeKeys.repoHasEntry(slot);
        AeKeys.KeyName known = AeKeys.describe(slot);
        if (known == null) {
            // 认不出是什么（条目为空 / 键类型没见过）→ 退回物品栈的身份，
            // 但**空判据仍用条目在不在**，不能拿"数量是不是 0"冒充。
            return new SlotView(menuSlotIndex, slot.getSlotIndex(),
                    itemIdOf(slot.getStack()), amount, !hasEntry, null);
        }
        return new SlotView(menuSlotIndex, slot.getSlotIndex(),
                known.keyId(), amount, !hasEntry, known.label());
    }

    /** 物品栈 → registry id（拿不到就 "unknown"，绝不编一个像样的假 id） */
    public static String itemIdOf(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    /**
     * RS 网格的行 → 槽位快照。
     *
     * 行的 `menuSlotIndex` 固定 -1：RS 1.x 的清单挂在界面上、不对应任何菜单槽位，
     * 选中 / 去重全靠身份串（与 AE 那条路同一套 `selectedNetIds`）。
     */
    public static List<SlotView> fromRsRows(List<RsKeys.Row> rows) {
        List<SlotView> out = new ArrayList<>(rows.size());
        for (RsKeys.Row r : rows) {
            out.add(new SlotView(-1, r.index(), r.id(), r.amount(), false, r.label()));
        }
        return out;
    }

    /**
     * 从任意 ItemStack 造一个快照。WorldProbe 走方块实体读值时也用它，
     * 这样"界面打开"和"坐标跟踪"两条路径产出的数据类型完全一致。
     */
    public static SlotView make(int menuSlotIndex, int containerSlotIndex, ItemStack stack) {
        boolean empty = stack.isEmpty();
        return new SlotView(
                menuSlotIndex,
                containerSlotIndex,
                itemIdOf(stack),
                empty ? 0 : stack.getCount(),
                empty);
    }

    /**
     * 槽位的一次只读快照。
     * 刻意不持有 ItemStack 引用 —— 存储层与游戏对象解耦，方便离线分析与导出。
     */
    public static final class SlotView {
        private final int menuSlotIndex;
        private final int containerSlotIndex;
        private final String itemId;
        // long：AE 的存量可以超过 int（创造元件单键就是 2^31-1），别在这里截断
        private final long count;
        private final boolean empty;
        /** 行上显示的名字（AE 流体用游戏内名）；null = 用 itemId 的短名 */
        private final String label;

        public SlotView(int menuSlotIndex, int containerSlotIndex, String itemId, long count, boolean empty) {
            this(menuSlotIndex, containerSlotIndex, itemId, count, empty, null);
        }

        public SlotView(int menuSlotIndex, int containerSlotIndex, String itemId, long count, boolean empty,
                        String label) {
            this.menuSlotIndex = menuSlotIndex;
            this.containerSlotIndex = containerSlotIndex;
            this.itemId = itemId;
            this.count = count;
            this.empty = empty;
            this.label = label;
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

        public long count() {
            return count;
        }

        public String label() {
            return label;
        }

        /** 列表上画出来的名字：有 label 就用它（AE 流体显示真名），否则用 itemId 的短名 */
        public String displayName() {
            return label != null && !label.trim().isEmpty() ? label : shortName();
        }

        public boolean empty() {
            return empty;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SlotView other = (SlotView) o;
            return java.util.Objects.equals(this.menuSlotIndex, other.menuSlotIndex) && java.util.Objects.equals(this.containerSlotIndex, other.containerSlotIndex) && java.util.Objects.equals(this.itemId, other.itemId) && this.count == other.count && java.util.Objects.equals(this.empty, other.empty) && java.util.Objects.equals(this.label, other.label);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(menuSlotIndex, containerSlotIndex, itemId, count, empty, label);
        }

        @Override
        public String toString() {
            return "SlotView[" + "menuSlotIndex=" + menuSlotIndex + ", " + "containerSlotIndex=" + containerSlotIndex + ", " + "itemId=" + itemId + ", " + "count=" + count + ", " + "empty=" + empty + ", " + "label=" + label + "]";
        }


        /** 给界面显示用的短名称，去掉命名空间前缀 */
        public String shortName() {
            int i = itemId.indexOf(':');
            return i < 0 ? itemId : itemId.substring(i + 1);
        }
    }
}
