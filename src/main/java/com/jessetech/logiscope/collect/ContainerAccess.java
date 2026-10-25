package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.analysis.RsKeyText;
import com.jessetech.logiscope.bind.RsKeys;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
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
        Minecraft mc = Minecraft.getMinecraft();
        return mc.player != null && mc.player.openContainer != mc.player.inventoryContainer;
    }

    public static Container currentMenu() {
        Minecraft mc = Minecraft.getMinecraft();
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
     * 判定依据有两条，缺一不可：
     *   1. vanilla 给玩家背包建的槽位是 {@code new Slot(player.getInventory(), i, x, y)}，
     *      也就是 {@code slot.inventory} 恰好是玩家的 InventoryPlayer（`Slot.container` 是 public 字段）。
     *   2. ★ **模组经常把玩家背包包成 IItemHandler 再建槽**（AE2 rv6 的终端就是
     *      `new PlayerInvWrapper(player.inventory)` + `SlotPlayerInv`/`SlotPlayerHotBar`，
     *      而 `AppEngSlot` **连访问器都不给**），这时 {@code slot.inventory} 是内部那个
     *      **占位空 InventoryBasic**，第 1 条必然判不出来 —— 实测症状：打开 AE 终端，
     *      选格页把玩家背包 36 格全列了出来（用户 2026-10-22 的反馈）。
     *      所以再往下看一眼它包的是谁（见 {@link #itemHandlerOf}）。
     */
    public static boolean isPlayerSlot(Slot slot) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return false;
        }
        InventoryPlayer inv = mc.player.inventory;
        if (slot.inventory == inv) {
            return true;
        }
        // ★ 模组普遍把玩家背包包成 IItemHandler 再建槽（AE2 rv6 的终端就是
        //   `new PlayerInvWrapper(player.inventory)` + `SlotPlayerInv`/`SlotPlayerHotBar`）。
        //   这时 `slot.inventory` 是那个**占位空 InventoryBasic**，第 1 条判不出来 ——
        //   实测症状：打开 AE 终端，选格页把玩家背包 36 格全列了出来。
        IItemHandler h = itemHandlerOf(slot);
        if (h == null) {
            return false;
        }
        // AE 用的是 PlayerInvWrapper（它继承 CombinedInvWrapper，**不是** InvWrapper，两样都要判）
        if (h instanceof net.minecraftforge.items.wrapper.PlayerInvWrapper) {
            return true;
        }
        return h instanceof net.minecraftforge.items.wrapper.InvWrapper
                && ((net.minecraftforge.items.wrapper.InvWrapper) h).getInv() == inv;
    }

    /**
     * 取这个槽位背后的 `IItemHandler`（**通用探测**，不改第三方代码）。
     *
     * 两条路：
     *   1. `SlotItemHandler` —— 它有公开的 `getItemHandler()`，直接问；
     *   2. 其它实现（**AE2 的 `AppEngSlot` 就是这样**：构造器收一个 `IItemHandler`，
     *      字段 `private final IItemHandler itemHandler`，**没有任何访问器**）——
     *      在类层级里找第一个 `IItemHandler` 类型的字段，反射读它。
     *
     * 反射在这里是安全的：只读第三方模组自己的字段（不是原版方法，不受 SRG 改名影响），
     * 而且**按类缓存**，每个类只解析一次。读不到就当"没有 handler"，绝不影响其它判定。
     */
    @Nullable
    private static IItemHandler itemHandlerOf(Slot slot) {
        if (slot instanceof net.minecraftforge.items.SlotItemHandler) {
            return ((net.minecraftforge.items.SlotItemHandler) slot).getItemHandler();
        }
        Class<?> cls = slot.getClass();
        if (HANDLER_FIELD_CACHE.containsKey(cls)) {
            return readHandler(slot, HANDLER_FIELD_CACHE.get(cls));
        }
        Field found = null;
        for (Class<?> k = cls; k != null && k != Slot.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (IItemHandler.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        found = f;
                    } catch (Throwable ignored) {
                        // 打不开就放弃这个类（不影响判定，最多是这一格判不出来）
                    }
                    break;
                }
            }
            if (found != null) {
                break;
            }
        }
        HANDLER_FIELD_CACHE.put(cls, found);
        return readHandler(slot, found);
    }

    @Nullable
    private static IItemHandler readHandler(Slot slot, @Nullable Field f) {
        if (f == null) {
            return null;
        }
        try {
            Object v = f.get(slot);
            return v instanceof IItemHandler ? (IItemHandler) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 槽位类 → 它那个 IItemHandler 字段（null = 这个类没有）。每种槽位类只解析一次。
     *
     * 用 `HashMap` 而不是 `ConcurrentHashMap`：后者**不允许 null 值**，
     * 而"这个类没有 IItemHandler 字段"正是最常走的结果（所有原版格子）。
     * 只在客户端（渲染线程 / 客户端 tick）用，不需要并发容器。
     */
    private static final java.util.Map<Class<?>, Field> HANDLER_FIELD_CACHE = new java.util.HashMap<>();

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
        // RS 网格：**行不在菜单里**（在界面的 IGridView 上，见 RsKeys 的类注释），
        // 而菜单里的槽位是合成格 / 样板格 —— 一行都不能列，否则"点了个合成格"会被当成
        // "网络里的东西"。所以这里直接返回空表，行由 ScopeScreen 从界面取。
        if (RsKeys.isRsGridMenu(menu)) {
            return out;
        }
        for (int i = 0; i < menu.inventorySlots.size(); i++) {
            Slot slot = menu.inventorySlots.get(i);
            if (!isSelectable(slot)) {
                continue;
            }
            out.add(viewOf(i, slot));
        }
        return out;
    }

    /**
     * 一个槽位的快照 —— **先问 AE，问不到再走 vanilla**。
     *
     * 为什么需要这一层：AE 终端的 `Slot#getStack()` 给出的数量恒为 1（物品）或直接是空（流体），
     * 真值只在 `SlotME#getAEStack()` / `SlotFluidME#getAEFluidStack()` 里。
     * 详见 {@link AeSlots}。没装 AE 时它恒为 null，这一层等于直通。
     */
    private static SlotView viewOf(int menuSlotIndex, Slot slot) {
        SlotView ae = AeSlots.view(slot, menuSlotIndex);
        return ae != null ? ae : make(menuSlotIndex, slot.getSlotIndex(), slot.getStack());
    }

    /** 读单个菜单槽位；越界或没有菜单时返回 null */
    public static SlotView read(int slotIndex) {
        Container menu = currentMenu();
        if (menu == null || slotIndex < 0 || slotIndex >= menu.inventorySlots.size()) {
            return null;
        }
        return viewOf(slotIndex, menu.inventorySlots.get(slotIndex));
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
            return viewOf(i, slot);
        }
        return null;
    }

    private static SlotView toView(Container menu, int index) {
        return viewOf(index, menu.inventorySlots.get(index));
    }

    /**
     * 物品栈 → 身份串（拿不到就 "unknown"，绝不编一个像样的假 id）。
     *
     * **必须带子类型（meta）**：1.12.2 的 AE2 把线缆 / 终端 / 各类 part 全注册成同一个
     * {@code appliedenergistics2:part}，靠 metadata 区分。只取 registry id 会让它们
     * **塌成一个身份**，实测症状（2026-10-25 用户反馈）：
     * <pre>
     *   注册名模式：线缆、终端全显示 "part"
     *   实际名模式：全显示成 meta=0 那一种的名字（"ME Glass Cable - White"）
     *   图表 / 聚合：不同线缆合成同一条线；点一行把同 item 的变体全选中
     * </pre>
     * 规则：{@code meta != 0} 且**不是耐久物品**时才带 {@code :meta}
     * —— 工具的 metadata 是损伤值不是"种类"，进了身份会让一把镐每掉一点耐久就换一条线。
     * 拼 {@code :} 而不是 {@code #}，与 1.12.2 的书写习惯（{@code mod:item:meta}）一致，
     * 且 {@link com.jessetech.logiscope.analysis.AeKeyText#label} 去掉命名空间后正好是
     * {@code part:11} 这种可读形式。
     */
    public static String itemIdOf(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return "unknown";
        }
        int meta = stack.getMetadata();
        if (meta != 0 && !stack.isItemStackDamageable()) {
            return id + ":" + meta;
        }
        return id.toString();
    }

    /**
     * RS 网格的行 → 槽位快照。行的 `menuSlotIndex` 固定 -1：RS 1.6 的清单挂在界面上、
     * 不对应任何菜单槽位，选中 / 去重全靠身份串。
     */
    public static List<SlotView> fromRsRows(List<RsKeys.Row> rows) {
        List<SlotView> out = new ArrayList<SlotView>(rows.size());
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


        /** 给界面显示用的短名称，去掉命名空间前缀；RS 身份串走 RsKeyText */
        public String shortName() {
            if (RsKeyText.isRs(itemId)) {
                return RsKeyText.label(itemId);
            }
            int i = itemId.indexOf(':');
            return i < 0 ? itemId : itemId.substring(i + 1);
        }
    }
}
