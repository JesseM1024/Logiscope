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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 采集层：界面关掉之后，从「方块实体」继续取值的通道。
 *
 * ★ 第九轮：从"全局单例"改成"每个探针实例一份" ★
 *   多探针并存之后，读值缓存、待处理标志、下标验证结果都必须是**每实例独立**的
 *   （否则两个探针会互相覆盖对方的读数）。但"现在世界走到第几 tick"是全世界唯一的，
 *   已经抽到 {@link ServerClock}。所以本类现在只剩实例状态，时钟从 ServerClock 取。
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
 *
 * ★ 管道有延迟，所以"取回来的值"必须带身份（见 {@link Read}）★
 *   服务端的任务队列是在**服务端 tick** 上排空的，客户端的请求却是按**客户端 tick**
 *   发的，两者不锁相。于是偶尔会出现"这一 tick 还没有新结果"，
 *   此时缓存里躺着的是上一次的观测。**把它当成本 tick 的新值写下去就是造假**：
 *   曲线会出现一个 1 tick 的平台（同一个值贴了两个 tick 标签）。
 *   所以 {@link #read} 会把这次观测的身份一并交出去，由调用方去重。
 */
public final class WorldProbe {

    /** 缓存结果 + 它是哪一客户端 tick 提交的，用来判断新鲜度 */
    private record Snapshot(List<ContainerAccess.SlotView> views, long atClientTick) {
    }

    /**
     * 一次世界侧读取的**结论**（第十轮第三次修复新增）。
     *
     * | 状态 | 含义 | 调用方该做什么 |
     * |---|---|---|
     * | {@link Status#OK} | 读到了内容 | 写样本 —— **但同一个 {@code seq} 只写一次**（见下） |
     * | {@link Status#UNAVAILABLE} | 读了，但这台机器现在读不到（区块卸载 / 方块没了 / 多人） | 记一条 `no_data` |
     * | {@link Status#NOT_READY} | 还没有任何一次读取完成，或结果太旧 | **这一 tick 什么都不记** —— 我们确实没观测到，但这不是"读失败" |
     *
     * ★ 为什么要带 {@code seq}（同一个观测不能写两遍）★
     *   值是从服务端线程异步取回来的，`maxAge` 允许它在缓存里多待几个客户端 tick。
     *   如果每个 tick 都把这个缓存原样写成新样本，就会出现"**同一个观测值贴两个不同的
     *   tick 标签**"—— 曲线上表现为一个 1 tick 的平台（实测反馈："断线后还有一个 t 的
     *   曲线平台"），而那个平台对应的那个 tick 我们其实什么都没看到。
     *   带上门牌号（= 提交读取时的客户端 tick，每一次请求都不同）之后，
     *   调用方对同一个观测只会写一次；没拿到新观测的 tick 就空着。
     *
     * @param views  读到的槽位快照（与 target.slots() 同序）；仅 OK 时非 null
     * @param seq    这次观测的身份（提交读取的客户端 tick）
     * @param status 见上表
     */
    public record Read(@Nullable List<ContainerAccess.SlotView> views, long seq, Status status) {

        public enum Status {
            OK, UNAVAILABLE, NOT_READY
        }

        public static final Read NOT_READY = new Read(null, -1L, Status.NOT_READY);
    }

    /** 下标验证结果：与 {@code target.slots()} **同序**的容器内下标；-1 = 这一格没验证出来 */
    private record Mapping(ProbeTarget target, int[] indexes, MappingStatus status) {
    }

    private volatile Snapshot cached;
    private volatile boolean pending;
    private volatile Mapping mapping;

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
        /** 全部选中格的下标都可信 */
        VERIFIED,
        /** 至少有一格的下标不可信（内容匹配有歧义，例如对着空格子挂探头） */
        UNVERIFIED,
        /** 这台机器既不是 Container 也没有 item handler，关界面后必然读不到 */
        UNAVAILABLE
    }

    /** 丢掉全部状态（读值缓存 + 下标验证）。重新配置 / 销毁探针时调用。 */
    public void reset() {
        cached = null;
        pending = false;
        mapping = null;
    }

    /**
     * 只丢读值缓存，**保留下标验证**。
     * 关界面转 FOLLOWING 时用：验证是挂探头时做的（GUI 开着才能做），
     * 之后每次开关界面都不能丢 —— 否则模组机器关界面后立刻退化回不可信下标。
     */
    public void resetReadCache() {
        cached = null;
        pending = false;
    }

    /**
     * 请求一次世界侧读取。**每客户端 tick 调一次即可**，不要在同一 tick 里反复调。
     * 上一发请求还没被服务端线程处理完时会直接跳过，避免任务堆积。
     */
    public void request(@Nullable ProbeTarget target) {
        if (target == null || !target.hasPersistentId() || target.slots().isEmpty() || pending) {
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
        long atClient = ServerClock.clientTick();
        try {
            server.execute(() -> {
                List<ContainerAccess.SlotView> views = readNow(server, target);
                cached = new Snapshot(views, atClient);
                pending = false;
            });
        } catch (Throwable t) {
            // 服务器正在关闭时会拒绝任务。忽略即可，下一 tick 会重试。
            pending = false;
        }
    }

    /**
     * 取最近一次读取的结论（与 {@code target.slots()} 同序的内容在 {@link Read#views()}）。
     *
     * @param maxAgeClientTicks 最多容忍多少个**客户端 tick** 的陈旧度。超龄一律当
     *                          {@link Read.Status#NOT_READY}（这一 tick 不记样本），
     *                          避免服务端卡顿时把好几 tick 之前的旧值当成新值写进时间序列。
     */
    public Read read(int maxAgeClientTicks) {
        Snapshot snap = cached;
        if (snap == null) {
            return Read.NOT_READY;
        }
        if ((ServerClock.clientTick() - snap.atClientTick()) > maxAgeClientTicks) {
            return Read.NOT_READY;
        }
        if (snap.views() == null) {
            return new Read(null, snap.atClientTick(), Read.Status.UNAVAILABLE);
        }
        return new Read(snap.views(), snap.atClientTick(), Read.Status.OK);
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
     * 之后内容怎么变都不影响。vanilla Container 路径直接跳过匹配（vanilla 保证下标）。
     *
     * @param menuViews 与 {@code target.slots()} 同序的菜单快照；无菜单时为 null
     */
    public void verifyIndex(ProbeTarget target, @Nullable List<ContainerAccess.SlotView> menuViews) {
        if (target == null || !target.hasPersistentId() || target.slots().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<ContainerAccess.SlotView> copy = menuViews == null ? null : List.copyOf(menuViews);
        try {
            server.execute(() -> mapping = verifyNow(server, target, copy));
        } catch (Throwable ignored) {
            // 服务器正在关闭：FOLLOWING 也跑不了，无所谓
        }
    }

    /** ★ 必须在服务端线程执行 ★ */
    private static Mapping verifyNow(MinecraftServer server, ProbeTarget target,
                                     @Nullable List<ContainerAccess.SlotView> menuViews) {
        int n = target.slots().size();
        int[] idx = new int[n];
        Arrays.fill(idx, -1);

        ServerLevel level = server.getLevel(target.dimension());
        if (level == null || !level.isLoaded(target.pos())) {
            return new Mapping(target, idx, MappingStatus.UNAVAILABLE);
        }
        BlockEntity be = level.getBlockEntity(target.pos());

        // vanilla Container：getContainerSlot() 就是容器内下标，vanilla 保证，直接可信
        if (be instanceof Container container) {
            int size = container.getContainerSize();
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
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
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

    /** readNow 用的"真下标"：验证过的优先，否则退回该格自己的 containerSlotIndex */
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
     * ★ 必须在服务端线程执行 ★
     *
     * 返回与 {@code target.slots()} **同序**的快照；只要有一格读不到就整体返回 null。
     *
     * ★ 为什么"有一格读不到就整体算读不到" ★
     *   聚合值是"所有格子的和"。少读一格 → 和偏小 → 图上的总存量/总吞吐静默偏小，
     *   而这种偏小在波形上完全看不出来（它看起来和正常的下降一模一样）。
     *   相比之下 no_data 是**看得出来**的断档。两害相权，宁可断档。
     *   （真读不到时照样记 no_data —— "该断的地方要断"，见 ARCHITECTURE.md CP9。）
     */
    @Nullable
    private List<ContainerAccess.SlotView> readNow(MinecraftServer server, ProbeTarget target) {
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null || !level.isLoaded(target.pos())) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(target.pos());
        if (be == null) {
            return null;
        }

        Container container = be instanceof Container c ? c : null;
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
            // ---- 路径 1：原版 Container（原版箱子/熔炉、漏斗，以及实现该接口的模组）----
            // 下标语义由 vanilla 保证：菜单槽的 getContainerSlot() 就是容器内下标
            if (container != null) {
                if (idx >= container.getContainerSize()) {
                    return null;
                }
                out.add(ContainerAccess.make(-1, idx, container.getItem(idx)));
                continue;
            }
            // ---- 路径 2：NeoForge 的物品 capability（模组机器的主通道）----
            // 实测（javap 反查，第五轮）：Sophisticated Storage 的 StorageBlockEntity 与
            // Mekanism 的 TileEntityEnergizedSmelter 都**不实现原版 Container**——
            // 它们的容器内容只在界面打开期间由菜单包同步，界面一关客户端手里就没了。
            // 但为了能和漏斗/管道交互，模组机器几乎都会注册 ItemHandler.BLOCK 能力。
            // 下标用 verifyIndex 验证过的（第六轮 M4）；验证不了时退回 getContainerSlot()。
            if (idx >= handler.getSlots()) {
                return null;
            }
            out.add(ContainerAccess.make(-1, idx, handler.getStackInSlot(idx)));
        }
        return List.copyOf(out);
    }

    /**
     * 取"这个方块里的物品槽"的视图。
     *
     * 先问无方向（null = 内部视角，通常是完整库存）；
     * 问不到再按六个方向逐个试 —— 有些模组只给"面向某一面"的分视图。
     * 查询命中 NeoForge 的能力缓存，每 tick 七次的成本可以忽略。
     */
    @Nullable
    static IItemHandler itemHandler(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
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
