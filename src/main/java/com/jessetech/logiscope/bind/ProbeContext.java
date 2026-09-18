package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.collect.ContainerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 绑定层：**当前"可探测的容器"是什么**（第九轮新增）。
 *
 * 需求里的第 3 条把这件事讲得很清楚：没有容器界面时按快捷键，先看准星。
 * 于是"探针要接在哪台设备上"有了三个来源，本类负责把它们统一成一个答案：
 *
 * | 场景 | 得到什么 | 可视化选格 |
 * |---|---|---|
 * | 容器界面开着 | 菜单槽（最准）+ 右键锚点（维度+坐标） | ✅ 可用 |
 * | 准星指着**有 GUI** 的容器方块 | 模拟一次使用把界面打开，于是变成上一行 | ✅ 可用（界面打开后） |
 * | 准星指着**无 GUI** 的容器方块 | 只有维度+坐标，槽位清单得问服务端 | ❌ 只能按下标列表选 |
 * | 都没有 | 没有候选 —— 界面就只显示"已创建的探针列表" | — |
 *
 * ★ "有没有 GUI" 的判定用 {@link BlockState#getMenuProvider} ★
 *   这是 vanilla 自己用来决定"右键这个方块要不要开界面"的入口
 *   （`ServerPlayer#openMenu` 那条路上游就是它），所以语义完全一致，
 *   不需要维护一张"哪些方块有界面"的表。
 *
 * ★ "是不是容器方块实体" 的判定是 `Container` 或 ItemHandler 能力 ★
 *   和采集层 {@code WorldProbe#readNow} 的两条读取路径严格对齐：
 *   能读到值的方块，才算"可探测的容器"。这样不会出现"界面说能探测、实际读不到"。
 */
public final class ProbeContext {

    /**
     * 一个可探测的候选目标。
     *
     * @param dimension 维度；界面开着但推不出坐标时为 null（modded GUI 用快捷键打开的情况）
     * @param pos       方块坐标；同上
     * @param menuOpen  此刻是不是有容器界面开着（决定选格页能不能走"可视化选格"）
     * @param label     给人看的来源描述
     */
    public record Candidate(@Nullable ResourceKey<Level> dimension, @Nullable BlockPos pos,
                            boolean menuOpen, String label) {

        public boolean hasAnchor() {
            return dimension != null && pos != null;
        }
    }

    /** 方向查询顺序，与 WorldProbe 保持一致 */
    private static final Direction[] SIDES = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private ProbeContext() {
    }

    /**
     * 当前可探测的容器。容器界面开着就优先用它（有菜单 → 可视化选格可用），
     * 否则看准星；都没有返回 null。
     */
    @Nullable
    public static Candidate current(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        if (ContainerAccess.hasForeignContainerOpen()) {
            ContainerAnchor.Anchor a = ContainerAnchor.activeFor(ContainerAccess.currentContainerId());
            if (a != null) {
                return new Candidate(a.dimension(), a.pos(), true, "已打开的容器");
            }
            // 界面开着但推不出坐标：拿不到持久身份，关界面只能暂停（已知边界，见 CP9）
            return new Candidate(null, null, true, "已打开的容器（无坐标）");
        }
        BlockHitResult hit = crosshairBlock(mc);
        if (hit == null) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (!isContainerLike(mc.level, pos)) {
            return null;
        }
        return new Candidate(mc.level.dimension(), pos.immutable(), false, blockName(mc.level, pos));
    }

    /** 准星指着的方块；没有或没指到方块返回 null */
    @Nullable
    public static BlockHitResult crosshairBlock(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return (BlockHitResult) hit;
    }

    /**
     * 这个方块右键之后会不会打开一个界面。
     * `BlockState#getMenuProvider(Level, BlockPos)` 是 public 的
     * （BlockState 继承 BlockBehaviour.BlockStateBase，那一层把 protected 的
     * `BlockBehaviour#getMenuProvider(BlockState, Level, BlockPos)` 包成了 public）。
     */
    public static boolean hasMenuProvider(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        MenuProvider provider = state.getMenuProvider(level, pos);
        return provider != null;
    }

    /** 这个方块是不是"能读到物品的容器"（原版 Container 或 NeoForge ItemHandler 能力） */
    public static boolean isContainerLike(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        return be instanceof Container || itemHandler(level, pos) != null;
    }

    @Nullable
    private static IItemHandler itemHandler(Level level, BlockPos pos) {
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

    // ==================================================================
    // 模拟"右键这个方块"
    // ==================================================================

    /**
     * 模拟一次使用（右键），让服务端把容器界面打开。
     *
     * 为什么必须模拟真实交互：**只有服务端能开菜单**（`ServerPlayer#openMenu` +
     * `ClientboundOpenScreenPacket`）。纯客户端模组没有别的合法通道，
     * 而 `MultiPlayerGameMode#useItemOn` 正是 vanilla 右键走的同一条路。
     *
     * ★ 用一个保守的前置条件把副作用挡掉 ★
     *   `performUseItemOn` 里的顺序是：`RightClickBlock 事件` → **`itemstack.onItemUseFirst`**
     *   → 方块 use → 物品 use。第一条是"物品抢在方块前面"，任何装了扳手/工具类物品的玩家
     *   在对着箱子按快捷键时都会被那条抢走（工具被用掉/机器被改设置）。
     *   所以这里先确认"手上的物品没有覆写 onItemUseFirst"：
     *   用反射读它的**声明类**（只看，不调用），是原版/NeoForge 的就算安全。
     *
     * @return true = 已经发出交互（界面会在 1~3 tick 后由服务端打开）；
     *         false = 手上物品可能抢走这次交互，没有发出 —— 调用方应提示用户空手再试
     */
    public static boolean tryOpenContainerGui(Minecraft mc, BlockHitResult hit) {
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        if (!isSafeToSimulateUse(mc.player)) {
            return false;
        }
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        return true;
    }

    /** 手上物品会不会覆写 `onItemUseFirst`（= 会不会抢走这次右键） */
    private static boolean isSafeToSimulateUse(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return true;
        }
        try {
            Method m = held.getItem().getClass()
                    .getMethod("onItemUseFirst", ItemStack.class, UseOnContext.class);
            // 没覆写时得到的是接口 IItemExtension 上的 default 方法（声明类是 net.neoforged.*），
            // 覆写了就落在模组自己的类上。只看声明类，不调用。
            String declaring = m.getDeclaringClass().getName();
            return declaring.startsWith("net.minecraft.")
                    || declaring.startsWith("net.neoforged.")
                    || m.getDeclaringClass() == Item.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ==================================================================
    // 显示
    // ==================================================================

    /** 方块名（用于选格页头部显示"我们在配置哪台设备"） */
    public static String blockName(Level level, BlockPos pos) {
        try {
            return level.getBlockState(pos).getBlock().getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
