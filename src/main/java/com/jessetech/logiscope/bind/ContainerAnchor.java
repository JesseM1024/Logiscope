package com.jessetech.logiscope.bind;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 绑定层：把"玩家刚刚打开的界面"对应到一个「维度 + 方块坐标」锚点上。
 *
 * 【为什么需要它】
 * vanilla 的 AbstractContainerMenu 不保存方块坐标。客户端能拿到的只有：
 *   · ClientboundOpenScreenPacket —— 只有菜单类型和标题，没有坐标
 *   · Minecraft#hitResult —— 屏幕打开期间不更新，不可靠
 * 唯一可靠的时机是**右键方块的那一瞬间**：
 *   PlayerInteractEvent.RightClickBlock 在客户端也会触发（MultiPlayerGameMode#useItemOn 里
 *   调 CommonHooks#onRightClickBlock，已核实），它带着 event.getPos()。
 *
 * 【时序】
 *   右键方块 → RightClickBlock（记 pending，带 tick 时间戳）
 *            → 服务端回 OpenScreen → ScreenEvent.Init.Post（把 pending 提升为 active）
 *
 * 【有效期】
 * pending 只在 FRESH_TICKS 内有效。超过就认为是"上一次无关的右键"，
 * 宁可拿不到锚点（退化成暂停），也不要张冠李戴挂到别的方块上。
 */
public final class ContainerAnchor {

    /** 右键到界面打开之间允许的最大间隔（tick）。20 tick = 1 秒 */
    private static final int FRESH_TICKS = 100;

    /** 一个持久锚点：维度 + 方块坐标 */
    public record Anchor(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private static Anchor pending;
    private static long pendingAt = Long.MIN_VALUE / 2;

    private static Anchor active;
    private static int activeContainerId = -1;

    /** 客户端 tick 计数，只用来判断 pending 是否还新鲜 */
    private static long clk;

    private ContainerAnchor() {
    }

    /** 由 Sampler 的客户端 tick 调用 */
    public static void tick() {
        clk++;
    }

    /**
     * 由主类注册到游戏总线。
     * 单人下集成服务端也会触发同一个事件，必须用 isClientSide 过滤掉服务端那次。
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level == null || !level.isClientSide()) {
            return;
        }
        pending = new Anchor(level.dimension(), event.getPos().immutable());
        pendingAt = clk;
    }

    /**
     * 由 ScreenEvent.Init.Post 调用：容器界面刚初始化完，把 pending 提升为 active。
     * 这时的 pending 就是"玩家为了打开这个界面而右键的那个方块"。
     *
     * ★ 幂等保护 ★
     * Screen#rebuildWidgets 也会发 Init.Post（例如原版背包界面切配方书时会重建成控件）。
     * 那种情况下 pending 是空的，若无脑覆盖就会把已经拿到的锚点抹掉。
     * 所以"容器 ID 没变"时保留原有锚点。
     */
    public static void onContainerScreenOpened(int containerId) {
        if (containerId == activeContainerId && active != null) {
            pending = null;
            return;
        }
        activeContainerId = containerId;
        active = isPendingFresh() ? pending : null;
        pending = null;
    }

    private static boolean isPendingFresh() {
        return pending != null && (clk - pendingAt) <= FRESH_TICKS;
    }

    /** 当前打开的容器对应的锚点；容器 ID 对不上时返回 null（说明是另一个容器）。 */
    @Nullable
    public static Anchor activeFor(int containerId) {
        if (containerId < 0 || containerId != activeContainerId) {
            return null;
        }
        return active;
    }

    public static void clearActive() {
        active = null;
        activeContainerId = -1;
    }
}
