package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.bind.ProbeTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 采集层：从「方块实体」读真值的通道（界面关掉之后继续跟踪的唯一依靠）。
 *
 * 这里**不再有异步管道** *   以前的做法是"客户端 tick 提交任务到服务端线程、结果丢进 volatile 缓存、
 *   下一 tick 取用"。它能工作，但有两笔账：
 *     · 缓存会被复用（`maxAge` 允许），复用时贴上新 tick 标签 → 曲线上假的 1t 平台；
 *     · 客户端的观测节拍（20/s）与服务端的 tick 节拍（20/s 或更高）不锁相 →
 *     时间戳偶尔跳 2（少观测一格）→ 旧断线规则把它剪成断线。
 *   现在改成**由服务端 tick 事件驱动、在服务端线程同步读**（见
 *   {@code ProbeInstance#sampleOnServerTick}）：一个世界 tick 读一次、读到的就是
 *   那一刻的真值、时间戳就是那个世界 tick —— 既没有延迟也没有缓存复用，
 *   顺便把"每 tick 一条"从"尽量"变成了"必然"（`/tick rate 200` 就是 200 条/秒）。
 *
 *   本类因此只剩下两件事：
 *     1. **下标映射验证**（模组机器的菜单槽下标不可信，见下）
 *     2. **一次同步读取**（{@link #readOnServer}，只能在服务端线程调）
 *
 * 为什么只能单人用 *   多人服务器上，客户端手里的 ClientWorld 虽然有那个方块，但方块实体的内容
 *   根本不在客户端 —— 容器内容只会通过菜单包同步，界面一关就没了。
 *   所以多人下调用方会退化成记录 no_data（这是设计，不是 bug）。
 */
public final class WorldProbe {

    /** 下标验证结果：与 {@code target.slots()} **同序**的容器内下标；-1 = 这一格没验证出来 */
    private static final class Mapping {
        private final ProbeTarget target;
        private final int[] indexes;
        private final MappingStatus status;

        public Mapping(ProbeTarget target, int[] indexes, MappingStatus status) {
            this.target = target;
            this.indexes = indexes;
            this.status = status;
        }

        public ProbeTarget target() {
            return target;
        }

        public int[] indexes() {
            return indexes;
        }

        public MappingStatus status() {
            return status;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Mapping other = (Mapping) o;
            return java.util.Objects.equals(this.target, other.target) && java.util.Objects.equals(this.indexes, other.indexes) && java.util.Objects.equals(this.status, other.status);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(target, indexes, status);
        }

        @Override
        public String toString() {
            return "Mapping[" + "target=" + target + ", " + "indexes=" + indexes + ", " + "status=" + status + "]";
        }

    }

    private volatile Mapping mapping;

    // ==================================================================
    // 下标映射验证
    // ==================================================================

    /**
     * 菜单槽下标 → 方块实体真实槽位下标 的映射状态。
     *
     * 为什么需要它：菜单槽的 {@code getContainerSlot()} 只有对 vanilla 才有
     * "容器内下标"的保证。实测（javap 反查）：Mekanism 的
     * InventoryContainerSlot 构造是 {@code super(emptyInventory, 0, x, y)} ——
     * **下标硬编码 0**，且它包装的 IInventorySlot 是单槽接口、不带位置信息，
     * 客户端从槽位对象里**根本拿不到**真下标。实测：MEK 机器所有槽显示 #0，
     * 关界面后跟踪的全是别的格子。
     */
    public enum MappingStatus {
        /** 验证还没回来（异步，1~2 tick） */
        PENDING,
        /** 全部选中格的下标都可信 */
        VERIFIED,
        /** 至少有一格的下标不可信（内容匹配有歧义，例如对着空格子挂探头） */
        UNVERIFIED,
        /** 这台机器既不是 IInventory 也没有 item handler，关界面后必然读不到 */
        UNAVAILABLE
    }

    /** 丢掉全部状态（下标验证 + 目标）。重新配置 / 销毁探针时调用。 */
    public void reset() {
        mapping = null;
    }

    /**
     * 挂探头的瞬间调用（此时 GUI 还开着，菜单内容与服务端真值同步）。
     * 在服务端线程做一次"内容指纹匹配"：拿着菜单槽当前内容去能力 handler 里找，
     * 唯一命中 → 那个下标就是真下标。
     *
     * 匹配策略（宽松到严格回退），**逐格独立判定**：
     *   1. 物品 + 数量都相同的槽位若唯一 → 采用
     *   2. 仅物品相同的槽位若唯一 → 采用（容忍挂探头瞬间 1 tick 的同步差）
     *   3. 否则这一格标记为未验证 —— 宁可明确告诉用户"不可信"，也不要瞎猜
     *
     * 注意匹配只在挂探头这一刻做一次：下标是结构性信息，内容只是用来"指认"它，
     * 之后内容怎么变都不影响。vanilla IInventory 路径直接跳过匹配（vanilla 保证下标）。
     *
     * @param menuViews 与 {@code target.slots()} 同序的菜单快照；无菜单时为 null
     */
    public void verifyIndex(ProbeTarget target, @Nullable List<ContainerAccess.SlotView> menuViews) {
        if (target == null || !target.hasPersistentId() || target.slots().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        List<ContainerAccess.SlotView> copy = menuViews == null ? null : new java.util.ArrayList<>(menuViews);
        try {
            server.addScheduledTask(() -> mapping = verifyNow(server, target, copy));
        } catch (Throwable ignored) {
            // 服务器正在关闭：FOLLOWING 也跑不了，无所谓
        }
    }

    /** 必须在服务端线程执行 */
    private static Mapping verifyNow(MinecraftServer server, ProbeTarget target,
                                     @Nullable List<ContainerAccess.SlotView> menuViews) {
        int n = target.slots().size();
        int[] idx = new int[n];
        Arrays.fill(idx, -1);

        WorldServer level = server.getWorld(target.dimension().id());
        if (level == null || !level.isBlockLoaded(target.pos())) {
            return new Mapping(target, idx, MappingStatus.UNAVAILABLE);
        }
        TileEntity be = level.getTileEntity(target.pos());

        // vanilla IInventory：getContainerSlot() 就是容器内下标，vanilla 保证，直接可信
        if (be instanceof IInventory) {
            IInventory container = (IInventory) be;
            int size = container.getSizeInventory();
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                int c = target.slots().get(i).containerSlotIndex();
                if (c >= 0 && c < size) {
                    idx[i] = c;
                } else {
                    ok = false;
                }
            }
            return new Mapping(target, idx, ok ? MappingStatus.VERIFIED : MappingStatus.UNVERIFIED);
        }

        IItemHandler handler = itemHandler(level, target.pos(), be);
        if (handler == null) {
            return new Mapping(target, idx, MappingStatus.UNAVAILABLE);
        }

        boolean all = true;
        for (int i = 0; i < n; i++) {
            ContainerAccess.SlotView view = menuViews != null && i < menuViews.size() ? menuViews.get(i) : null;
            int found = matchOne(handler, view);
            if (found >= 0) {
                idx[i] = found;
            } else {
                all = false;
            }
        }
        return new Mapping(target, idx, all ? MappingStatus.VERIFIED : MappingStatus.UNVERIFIED);
    }

    /** 拿一格的内容去 handler 里指认它的下标；指认不出来返回 -1 */
    private static int matchOne(IItemHandler handler, @Nullable ContainerAccess.SlotView view) {
        if (view == null) {
            return -1;
        }
        int exact = -1;
        int byItem = -1;
        int exactHits = 0;
        int itemHits = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (view.empty()) {
                // 对着空格子挂探头：只有"机器里唯一一个空格子"时才能指认
                if (s.isEmpty()) {
                    exactHits++;
                    exact = i;
                }
                continue;
            }
            if (s.isEmpty()) {
                continue;
            }
            String id = ForgeRegistries.ITEMS.getKey(s.getItem()).toString();
            if (!id.equals(view.itemId())) {
                continue;
            }
            itemHits++;
            byItem = i;
            if (s.getCount() == view.count()) {
                exactHits++;
                exact = i;
            }
        }
        if (exactHits == 1) {
            return exact;
        }
        if (itemHits == 1) {
            return byItem;
        }
        return -1;
    }

    /** 这个探头的下标映射状态（探头不匹配时视为 PENDING） */
    public MappingStatus mappingStatus(@Nullable ProbeTarget target) {
        Mapping m = mapping;
        return m != null && m.target() == target ? m.status() : MappingStatus.PENDING;
    }

    /** readOnServer 用的"真下标"：验证过的优先，否则退回该格自己的 containerSlotIndex */
    private int effectiveIndex(ProbeTarget target, int i) {
        Mapping m = mapping;
        if (m != null && m.target() == target && m.indexes() != null && i < m.indexes().length) {
            int v = m.indexes()[i];
            if (v >= 0) {
                return v;
            }
        }
        return target.slots().get(i).containerSlotIndex();
    }

    // ==================================================================
    // 读值
    // ==================================================================

    /**
     * 只能在**服务端线程**执行（由服务端 tick 事件驱动）*
     * 返回与 {@code target.slots()} **同序**的快照；只要有一格读不到就整体返回 null。
     *
     * 为什么"有一格读不到就整体算读不到" *   聚合值是"所有格子的和"。少读一格 → 和偏小 → 图上的总存量/总吞吐静默偏小，
     *   而这种偏小在波形上完全看不出来（它看起来和正常的下降一模一样）。
     *   相比之下 no_data 是**看得出来**的断档。两害相权，宁可断档。
     */
    @Nullable
    public List<ContainerAccess.SlotView> readOnServer(WorldServer level, ProbeTarget target) {
        if (level == null || target == null || !target.hasPersistentId()) {
            return null;
        }
        if (!level.isBlockLoaded(target.pos())) {
            // 区块真卸载了才读不到。试过更严的"方块 tick 范围"判据，用户判定
            // **不需要**：那会让"走出模拟距离"也变成 no_data，而"关掉 GUI 继续采"
            // 才是要的行为 —— 已回退（见 PROGRESS 的对应小节）。
            return null;
        }
        TileEntity be = level.getTileEntity(target.pos());
        if (be == null) {
            return null;
        }

        IInventory container = be instanceof IInventory ? (IInventory) be : null;
        IItemHandler handler = container == null ? itemHandler(level, target.pos(), be) : null;
        if (container == null && handler == null) {
            return null;
        }

        int n = target.slots().size();
        List<ContainerAccess.SlotView> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int idx = effectiveIndex(target, i);
            if (idx < 0) {
                return null;
            }
            // ---- 路径 1：原版 IInventory（原版箱子/熔炉、漏斗，以及实现该接口的模组）----
            // 下标语义由 vanilla 保证：菜单槽的 getContainerSlot() 就是容器内下标
            if (container != null) {
                if (idx >= container.getSizeInventory()) {
                    return null;
                }
                out.add(ContainerAccess.make(-1, idx, container.getStackInSlot(idx)));
                continue;
            }
            // ---- 路径 2：Forge 的物品 capability（模组机器的主通道）----
            // 实测（javap 反查）：Sophisticated Storage 的 StorageBlockEntity 与
            // Mekanism 的 TileEntityEnergizedSmelter 都**不实现原版 IInventory**——
            // 它们的容器内容只在界面打开期间由菜单包同步，界面一关客户端手里就没了。
            // 但为了能和漏斗/管道交互，模组机器几乎都会注册 ItemHandler.BLOCK 能力。
            // 下标用 verifyIndex 验证过的；验证不了时退回 getContainerSlot()。
            if (idx >= handler.getSlots()) {
                return null;
            }
            out.add(ContainerAccess.make(-1, idx, handler.getStackInSlot(idx)));
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * 取"这个方块里的物品槽"的视图。
     *
     * 先问无方向（null = 内部视角，通常是完整库存）；
     * 问不到再按六个方向逐个试 —— 有些模组只给"面向某一面"的分视图。
     *
     * 刻意**每次读取都重新问**（不缓存 IItemHandler）：方块实体可能被换掉，
     * 而这条查询走 Forge 的能力缓存，成本可以忽略。
     */
    @Nullable
    static IItemHandler itemHandler(WorldServer level, BlockPos pos, @Nullable TileEntity be) {
        // 1.20.1 走方块实体的 LazyOptional 通道（见 ProbeContext#itemHandler 注释）
        if (be == null) {
            return null;
        }
        IItemHandler direct = be.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (direct != null) {
            return direct;
        }
        for (EnumFacing side : SIDES) {
            IItemHandler sided = be.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side);
            if (sided != null) {
                return sided;
            }
        }
        return null;
    }

    /** 方向查询顺序。从底面开始：多数机器的自动化接口在底面/顶面 */
    private static final EnumFacing[] SIDES = {
            EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };
}
