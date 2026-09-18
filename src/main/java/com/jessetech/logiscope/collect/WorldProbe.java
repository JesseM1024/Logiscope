package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.bind.ProbeTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 采集层：界面关掉之后，从「方块实体」继续取值的通道。
 *
 * ★ 为什么只能单人用 ★
 * 多人服务器上，客户端手里的 ClientLevel 虽然有那个方块，但方块实体的内容
 * 根本不在客户端 —— 容器内容只会通过菜单包同步，界面一关就没了。
 * 所以多人下这里永远拿不到值，调用方会退化成记录 no_data（这是设计，不是 bug）。
 *
 * ★ 为什么要绕到集成服务端 ★
 * 单人存档里 ServeLevel 才是真正持有容器内容的地方。
 *
 * ★ 为什么用 server.execute 而不是直接读 ★
 * ServerLevel / 方块实体属于服务端 tick 线程，客户端线程直接读是数据竞争
 * （虽然实践中大多是原子的，但没必要留这种隐患）。
 * 把读取任务提交到服务端线程，结果丢进一个 volatile 缓存，
 * 客户端下一 tick 取用 —— 最多差 1 tick，对一个观测工具完全够。
 */
public final class WorldProbe {

    /** 缓存结果 + 它是哪一 tick 提交的，用来判断新鲜度 */
    private record Snapshot(@Nullable ContainerAccess.SlotView view, long requestedAtTick) {
    }

    private static volatile Snapshot cached;
    private static volatile boolean pending;

    private WorldProbe() {
    }

    // ==================================================================
    // 下标映射验证（第六轮 M4：MEK 等模组机器的槽位下标不可信）
    // ==================================================================

    /**
     * 菜单槽下标 → 方块实体真实槽位下标 的映射状态。
     *
     * 为什么需要它：菜单槽的 {@code getContainerSlot()} 只有对 vanilla 才有
     * "容器内下标"的保证。实测（javap 反查，第六轮）：Mekanism 的
     * InventoryContainerSlot 构造是 {@code super(emptyInventory, 0, x, y)} ——
     * **下标硬编码 0**，且它包装的 IInventorySlot 是单槽接口、不带位置信息，
     * 客户端从槽位对象里**根本拿不到**真下标。用户实测：MEK 机器所有槽显示 #0，
     * 关界面后跟踪的全是别的格子。
     */
    public enum MappingStatus {
        /** 验证还没回来（异步，1~2 tick） */
        PENDING,
        /** 下标可信：vanilla Container（vanilla 保证），或服务端内容匹配唯一命中 */
        VERIFIED,
        /** 下标不可信：内容匹配有歧义（例如对着空格子挂探头，机器里空格子不止一个） */
        UNVERIFIED,
        /** 这台机器既不是 Container 也没有 item handler，关界面后必然读不到 */
        UNAVAILABLE
    }

    private record Mapping(ProbeTarget target, int index, MappingStatus status) {
    }

    /** 最近一次验证结果。同一时刻只有一个探头，单个槽位足够。 */
    private static volatile Mapping mapping;

    /**
     * 挂探头的瞬间调用（此时 GUI 还开着，菜单内容与服务端真值同步）。
     * 在服务端线程做一次"内容指纹匹配"：拿着菜单槽当前内容去能力 handler 里找，
     * 唯一命中 → 那个下标就是真下标。
     *
     * 匹配策略（宽松到严格回退）：
     *   1. 物品 + 数量都相同的槽位若唯一 → 采用
     *   2. 仅物品相同的槽位若唯一 → 采用（容忍挂探头瞬间 1 tick 的同步差）
     *   3. 否则 UNVERIFIED —— 宁可明确告诉用户"不可信"，也不要瞎猜
     *
     * 注意匹配只在挂探头这一刻做一次：下标是结构性信息，内容只是用来"指认"它，
     * 之后内容怎么变都不影响。vanilla Container 路径直接跳过匹配（vanilla 保证下标）。
     */
    public static void verifyIndex(ProbeTarget target, @Nullable ContainerAccess.SlotView view) {
        if (target == null || !target.hasPersistentId()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        try {
            server.execute(() -> mapping = verifyNow(server, target, view));
        } catch (Throwable ignored) {
            // 服务器正在关闭：FOLLOWING 也跑不了，无所谓
        }
    }

    /** ★ 必须在服务端线程执行 ★ */
    private static Mapping verifyNow(MinecraftServer server, ProbeTarget target,
                                     @Nullable ContainerAccess.SlotView view) {
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null || !level.isLoaded(target.pos())) {
            return new Mapping(target, -1, MappingStatus.UNAVAILABLE);
        }
        BlockEntity be = level.getBlockEntity(target.pos());

        // vanilla Container：getContainerSlot() 就是容器内下标，vanilla 保证，直接可信
        if (be instanceof Container container) {
            int idx = target.containerSlotIndex();
            boolean ok = idx >= 0 && idx < container.getContainerSize();
            return new Mapping(target, ok ? idx : -1, ok ? MappingStatus.VERIFIED : MappingStatus.UNVERIFIED);
        }

        IItemHandler handler = itemHandler(level, target.pos(), be);
        if (handler == null) {
            return new Mapping(target, -1, MappingStatus.UNAVAILABLE);
        }
        if (view == null) {
            return new Mapping(target, -1, MappingStatus.UNVERIFIED);
        }

        String wantItem = view.itemId();
        int wantCount = view.count();
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
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (!id.equals(wantItem)) {
                continue;
            }
            itemHits++;
            byItem = i;
            if (s.getCount() == wantCount) {
                exactHits++;
                exact = i;
            }
        }
        if (exactHits == 1) {
            return new Mapping(target, exact, MappingStatus.VERIFIED);
        }
        if (itemHits == 1) {
            return new Mapping(target, byItem, MappingStatus.VERIFIED);
        }
        return new Mapping(target, -1, MappingStatus.UNVERIFIED);
    }

    /** 这个探头的下标映射状态（探头不匹配时视为 PENDING） */
    public static MappingStatus mappingStatus(@Nullable ProbeTarget target) {
        Mapping m = mapping;
        return m != null && m.target() == target ? m.status() : MappingStatus.PENDING;
    }

    /** readNow 用的"真下标"：验证过的优先，否则退回菜单槽的 getContainerSlot() */
    private static int effectiveIndex(ProbeTarget target) {
        Mapping m = mapping;
        if (m != null && m.target() == target && m.status() == MappingStatus.VERIFIED && m.index() >= 0) {
            return m.index();
        }
        return target.containerSlotIndex();
    }

    /** 丢掉全部缓存（读值缓存 + 下标验证）。切换探针 / 拔探头时调用。 */
    public static void reset() {
        cached = null;
        pending = false;
        mapping = null;
    }

    /**
     * 只丢读值缓存，**保留下标验证**。
     * 关界面转 FOLLOWING 时用：验证是挂探头时做的（GUI 开着才能做），
     * 之后每次开关界面都不能丢 —— 否则模组机器关界面后立刻退化回不可信下标。
     */
    public static void resetReadCache() {
        cached = null;
        pending = false;
    }

    /**
     * ★ 用"最后一次已知值"垫一下，专治"刚关掉界面那 1 tick 的假 no_data" ★
     *
     * 问题出在时序上，不是读取失败：
     *   第 N   tick  客户端发现界面关了 → 提交不了请求（这一 tick 还没进 FOLLOWING）
     *   第 N+1 tick  WorldProbe.request() 把任务丢给服务端线程，
     *               而**同一 tick 内** latest() 立刻就要取值 —— 服务端线程还没轮到
     *               → 必然返回 null → 记下一条 no_data
     * 所以那条 no_data 是"异步管道的启动延迟"，不是"这台机器读不到"，纯属误导。
     *
     * 修法：进入 FOLLOWING 时把上一 tick 刚读到的值（最多 50 ms 旧）当作一次快照塞进缓存，
     * 让时间轴续上；真正的异步结果在 1–2 tick 内到达并覆盖它。
     * 若确实读不到（多人 / 区块卸载 / 方块被拆），这个垫值会在 maxAge 之后自然过期，
     * 之后照样记 no_data —— 该断的地方还是会断。
     *
     * @param tick 这次垫值算作哪一 tick 的观测（一般传当前 tick）
     */
    public static void seed(@Nullable ContainerAccess.SlotView view, long tick) {
        if (view == null) {
            return;
        }
        cached = new Snapshot(view, tick);
        // 刻意不动 pending：垫值只是"缓一下"，不该阻止真正的异步请求发出去
    }

    /**
     * 请求一次世界侧读取。**每 tick 调一次即可**，不要在同一 tick 里反复调。
     * 上一发请求还没被服务端线程处理完时会直接跳过，避免任务堆积。
     */
    public static void request(@Nullable ProbeTarget target, long tick) {
        if (target == null || !target.hasPersistentId() || pending) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isLocalServer()) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }

        pending = true;
        try {
            server.execute(() -> {
                ContainerAccess.SlotView view = readNow(server, target);
                cached = new Snapshot(view, tick);
                pending = false;
            });
        } catch (Throwable t) {
            // 服务器正在关闭时会拒绝任务。忽略即可，下一 tick 会重试。
            pending = false;
        }
    }

    /**
     * 取最近一次读到的值。
     *
     * @param nowTick 当前 tick
     * @param maxAge  最多容忍多少 tick 的陈旧度。超龄返回 null（记 no_data），
     *                避免服务端卡顿时把一堆陈旧值当成新值写进时间序列。
     */
    @Nullable
    public static ContainerAccess.SlotView latest(long nowTick, int maxAge) {
        Snapshot snap = cached;
        if (snap == null || snap.view() == null) {
            return null;
        }
        return (nowTick - snap.requestedAtTick()) <= maxAge ? snap.view() : null;
    }

    /** ★ 必须在服务端线程执行 ★ */
    @Nullable
    private static ContainerAccess.SlotView readNow(MinecraftServer server, ProbeTarget target) {
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null || !level.isLoaded(target.pos())) {
            return null;
        }
        int idx = effectiveIndex(target);
        if (idx < 0) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(target.pos());

        // ---- 路径 1：原版 Container（原版箱子/熔炉、漏斗，以及实现该接口的模组）----
        // 下标语义由 vanilla 保证：菜单槽的 getContainerSlot() 就是容器内下标
        if (be instanceof Container container && idx < container.getContainerSize()) {
            return ContainerAccess.make(-1, idx, container.getItem(idx));
        }

        // ---- 路径 2：NeoForge 的物品 capability（模组机器的主通道）----
        // 实测（javap 反查，第五轮）：Sophisticated Storage 的 StorageBlockEntity 与
        // Mekanism 的 TileEntityEnergizedSmelter 都**不实现原版 Container**——
        // 它们的容器内容只在界面打开期间由菜单包同步，界面一关客户端手里就没了。
        // 但为了能和漏斗/管道交互，模组机器几乎都会注册 ItemHandler.BLOCK 能力，
        // 这条路在服务端永远可用。
        //
        // ★ 下标用 verifyIndex 验证过的（第六轮 M4）★
        // Mekanism 的 InventoryContainerSlot 构造是 super(emptyInventory, 0, x, y)，
        // 下标硬编码 0 —— 直接用 getContainerSlot() 会跟踪到别的格子。
        // 验证不了（内容匹配有歧义）时退回 getContainerSlot()，
        // 但 Sampler 会向用户弹"下标不可信"的提示。
        IItemHandler handler = itemHandler(level, target.pos(), be);
        if (handler != null && idx < handler.getSlots()) {
            return ContainerAccess.make(-1, idx, handler.getStackInSlot(idx));
        }
        return null;
    }

    /**
     * 取"这个方块里的物品槽"的视图。
     *
     * 先问无方向（null = 内部视角，通常是完整库存）；
     * 问不到再按六个方向逐个试 —— 有些模组只给"面向某一面"的分视图。
     * 查询命中 NeoForge 的能力缓存，每 tick 七次的成本可以忽略。
     */
    @Nullable
    private static IItemHandler itemHandler(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        IItemHandler direct = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (direct != null) {
            return direct;
        }
        for (Direction side : SIDES) {
            IItemHandler sided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (sided != null) {
                return sided;
            }
        }
        return null;
    }

    /** 方向查询顺序。从底面开始：多数机器的自动化接口在底面/顶面 */
    private static final Direction[] SIDES = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
}
