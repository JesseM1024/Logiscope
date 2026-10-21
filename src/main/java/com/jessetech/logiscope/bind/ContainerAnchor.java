package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import com.jessetech.logiscope.compat.Dim;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 绑定层：把"玩家刚刚打开的界面"对应到一个「维度 + 方块坐标」锚点上。
 *
 * 【为什么需要它】
 * vanilla 的 Container 不保存方块坐标。客户端能拿到的只有：
 *   · ClientboundOpenScreenPacket —— 只有菜单类型和标题，没有坐标
 *   · Minecraft#hitResult —— 屏幕打开期间不更新，不可靠
 * 唯一可靠的时机是**右键方块的那一瞬间**：
 *   PlayerInteractEvent.RightClickBlock 在客户端也会触发（PlayerController#processRightClickBlock
 *   里调 ForgeHooks#onRightClickBlock，已核实），它带着 event.getPos()。
 *
 * 【时序】
 *   右键方块 → RightClickBlock（记 pending，带 tick 时间戳）
 *            → 服务端回 OpenScreen → GuiScreenEvent.InitGuiEvent.Post（把 pending 提升为 active）
 *
 * 【有效期】
 *   pending 只在 FRESH_TICKS 内有效。超过就认为是"上一次无关的右键"，
 *   宁可拿不到锚点（退化成暂停），也不要张冠李戴挂到别的方块上。
 *
 * 【★ 1.12.2 的致命时序坑：不能用 windowId 当锚点的钥匙】
 *   这一代客户端开窗的顺序是（javap 核实）：
 *
 *     NetHandlerPlayClient#handleOpenWindow
 *       └─ EntityPlayerSP#displayGUIChest → Minecraft#displayGuiScreen(new GuiChest(...))
 *            └─ GuiScreen#setWorldAndResolution
 *                 ├─ initGui()                ← GuiContainer#initGui 里 mc.player.openContainer = this.inventorySlots
 *                 └─ InitGuiEvent.Post        ← ★ 我们在这一刻记锚点
 *       └─ player.openContainer.windowId = packet.getWindowId()   ★ 窗口号在这一刻才被赋值
 *
 *   也就是说：{@code InitGuiEvent.Post} 触发时，那个 Container 的 {@code windowId} **还是 0**
 *   （{@code public int windowId} 默认值），真实窗口号在 {@code displayGuiScreen} 返回**之后**才写进去。
 *   而 {@code ContainerAccess#currentContainerId()} 读的是**后来**的值 —— 于是
 *   "打开时记 0、之后比 1"永远对不上，锚点恒为 null。
 *
 *   实测症状（1.12.2 首轮实机）：所有探针都显示成
 *   {@code open container (no coords)} + {@code runtime identity}（= 没有持久身份）⇒
 *   关掉容器界面必定 paused、点「继续」必报 source unavailable、
 *   「Probe data」按钮永远置灰（它也要持久身份）。
 *
 *   修法：**锚点挂在 Container 的「对象身份」上**，不比窗口号。
 *   windowId 会在原地被改，对象身份不会变 —— 这才是这一代唯一稳定的钥匙。
 */
public final class ContainerAnchor {

    /** 右键到界面打开之间允许的最大间隔（tick）。100 tick = 5 秒 */
    private static final int FRESH_TICKS = 100;

    /** 一个持久锚点：维度 + 方块坐标 */
    public static final class Anchor {
        private final Dim dimension;
        private final BlockPos pos;

        public Anchor(Dim dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }

        public Dim dimension() {
            return dimension;
        }

        public BlockPos pos() {
            return pos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Anchor other = (Anchor) o;
            return java.util.Objects.equals(this.dimension, other.dimension) && java.util.Objects.equals(this.pos, other.pos);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(dimension, pos);
        }

        @Override
        public String toString() {
            return "Anchor[" + "dimension=" + dimension + ", " + "pos=" + pos + "]";
        }

    }

    private static Anchor pending;
    private static long pendingAt = Long.MIN_VALUE / 2;

    private static Anchor active;
    /**
     * 锚点所属的容器 —— **对象身份**，不是窗口号（见类注释的时序坑）。
     * 拿 {@code mc.player.openContainer == activeContainer} 判"现在开着的还是不是那台设备"。
     */
    private static Container activeContainer;

    /** 客户端 tick 计数，只用来判断 pending 是否还新鲜 */
    private static long clk;

    private ContainerAnchor() {
    }

    /** 由 ProbeManager 的客户端 tick 调用 */
    public static void tick() {
        clk++;
    }

    /**
     * 由主类注册到游戏总线。
     * 单人下集成服务端也会触发同一个事件，必须用 isRemote 过滤掉服务端那次。
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        World level = event.getWorld();
        if (level == null || !level.isRemote) {
            return;
        }
        pending = new Anchor(Dim.of(level), event.getPos().toImmutable());
        pendingAt = clk;
    }

    /**
     * 由 GuiScreenEvent.InitGuiEvent.Post 调用：容器界面刚初始化完，把 pending 提升为 active。
     * 这时的 pending 就是"玩家为了打开这个界面而右键的那个方块"。
     *
     * 幂等保护 * GuiScreen#rebuildWidgets 也会发 Init.Post（窗口缩放、原版背包界面切配方书都会重建控件）。
     * 那种情况下 pending 是空的，若无脑覆盖就会把已经拿到的锚点抹掉。
     * 所以"同一个容器对象"时保留原有锚点。
     *
     * 兜底：pending 不新鲜（例如容器不是玩家右键开的：命令、插件、别的模组代开）时，
     * 试着**从容器自己反推**方块实体（见 {@link #anchorFromContainer}）——
     * vanilla 箱子/熔炉/漏斗的槽位 inventory 就是那个 TileEntity，坐标是白拿的。
     *
     * @param container 这个界面的 Container（{@code GuiContainer#inventorySlots}）；null 直接忽略
     */
    public static void onContainerScreenOpened(@Nullable Container container) {
        if (container == null) {
            return;
        }
        if (container == activeContainer && active != null) {
            pending = null;
            return;   // 同一个容器的重复初始化（缩放 / 重建控件）
        }
        activeContainer = container;
        if (isPendingFresh()) {
            active = pending;
        } else {
            // 兜底：从容器自己的槽位反推（右键事件没抓到时的唯一活路）
            active = anchorFromContainer(container);
        }
        pending = null;
        if (active == null) {
            LogiScopeMod.LOGGER.info(
                    "[LogiScope] 打开容器但拿不到锚点（无坐标）：containerId={} class={}",
                    container.windowId, container.getClass().getSimpleName());
            return;
        }
        LogiScopeMod.LOGGER.info("[LogiScope] 锚点已绑定：dim={} pos={} containerId={} class={}",
                active.dimension().path(), active.pos(), container.windowId,
                container.getClass().getSimpleName());
    }

    private static boolean isPendingFresh() {
        return pending != null && (clk - pendingAt) <= FRESH_TICKS;
    }

    /**
     * 从容器反推"它是哪台设备"。判据只有一条：某个槽位的 inventory **自己就是方块实体**。
     *
     * | 容器 | 槽位 inventory | 反推结果 |
     * |---|---|---|
     * | 箱子 / 熔炉 / 漏斗 / 发射器 / 酿造台 | 那个 TileEntity 本身 | ✅ 坐标 + 维度 |
     * | 双联箱子 | {@code InventoryLargeChest}（包装器，不是 TE） | ❌ 返回 null（仍可靠右键那条主路） |
     * | 工作台 / 玩家背包 | {@code InventoryCrafting} / {@code InventoryPlayer} | ❌ 本来就没有方块 |
     * | 模组机器 | 视模组而定（MEK 用自定义 inventory 包装器） | 多数拿不到 |
     *
     * 只在 pending 失效时才用，所以永远不会覆盖"玩家右键的那台设备"这个更准的答案。
     */
    @Nullable
    private static Anchor anchorFromContainer(Container container) {
        try {
            for (Slot slot : container.inventorySlots) {
                IInventory inv = slot.inventory;
                if (!(inv instanceof TileEntity)) {
                    continue;
                }
                TileEntity be = (TileEntity) inv;
                World level = be.getWorld();
                if (level == null) {
                    continue;
                }
                return new Anchor(Dim.of(level), be.getPos());
            }
        } catch (Throwable t) {
            // 某些模组的槽位实现会在这里抛（inventory 为 null 等）—— 兜底路径失败不影响主路径
            LogiScopeMod.LOGGER.debug("[LogiScope] anchorFromContainer failed: {}", t.toString());
        }
        return null;
    }

    /**
     * 某个容器对应的锚点。
     *
     * 用**对象身份**比较：玩家重新打开同一台设备时，客户端会新建一个 Container 实例 →
     * 这里返回 null（需要重新绑）；窗口缩放/重开同一个界面时实例不变 → 照旧返回锚点。
     *
     * @param container 通常是 {@code mc.player.openContainer}
     */
    @Nullable
    public static Anchor activeFor(@Nullable Container container) {
        if (container == null || container != activeContainer) {
            return null;
        }
        return active;
    }

    /** 换世界时清干净（进游戏的 LoggingIn 里调）：锚点、"上一次的右键"与容器一起清 */
    public static void clearActive() {
        active = null;
        pending = null;
        pendingAt = 0;
        activeContainer = null;
    }
}
