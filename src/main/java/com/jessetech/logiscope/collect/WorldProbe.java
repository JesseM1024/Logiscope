package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.bind.ProbeTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /** 丢掉缓存。切换探针 / 重新开始记录时调用，避免读到上一个目标的残值。 */
    public static void reset() {
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
        BlockEntity be = level.getBlockEntity(target.pos());
        if (!(be instanceof Container container)) {
            return null;
        }
        int idx = target.containerSlotIndex();
        if (idx < 0 || idx >= container.getContainerSize()) {
            return null;
        }
        ItemStack stack = container.getItem(idx);
        return ContainerAccess.make(-1, idx, stack);
    }
}
