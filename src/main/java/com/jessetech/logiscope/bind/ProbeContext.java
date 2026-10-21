package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.collect.ContainerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import com.jessetech.logiscope.compat.Dim;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.EnumHand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 绑定层：**当前"可探测的容器"是什么**。
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
 * "有没有 GUI" 不能用静态判据，要用**实际结果***
 * 第一版用 `BlockState#getMenuProvider` 判定，结果实测不成立：
 * **空手对着精妙存储箱子、MEK 熔炼工厂按快捷键，界面没打开，反而进了"无 GUI"分支。**
 * 原因（javap 反查核实）：
 *
 * | 方块 | 覆写 `getMenuProvider`？ | 界面从哪开 |
 * |---|---|---|
 * | 原版 `ChestBlock` | ✅ 覆写（`SimpleMenuProvider`） | `getMenuProvider` + `openMenu` |
 * | 原版 `AbstractFurnaceBlock` / `BarrelBlock` | ❌ **不覆写** | `useWithoutItem` 里直接 `player.openMenu(...)` |
 * | 精妙存储 `ChestBlock` | ❌ 不覆写 | 同上（客户端直接 `return SUCCESS` 做预测） |
 * | Mekanism `BlockTile` | ❌ 不覆写 | 同上（客户端按 `HasGui` 属性返回 SUCCESS / PASS） |
 *
 * 也就是说**连原版熔炉都判不出来**，更别说模组机器。所以改成先问一次
 * "这个方块右键会不会**消费**这次交互"（见 {@link #wouldConsumeClick}）——
 * 这是所有容器方块为了做客户端预测都必须回答的问题，比 `getMenuProvider` 可靠得多。
 * `getMenuProvider` 保留为**快速肯定判据**（非空就直接算"有界面"，省一次预测）。
 *
 * "是不是容器方块实体" 的判定是 `IInventory` 或 ItemHandler 能力 *   和采集层 {@code WorldProbe#readNow} 的两条读取路径严格对齐：
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
    public static final class Candidate {
        private final @Nullable Dim dimension;
        private final @Nullable BlockPos pos;
        private final boolean menuOpen;
        private final String label;

        public Candidate(@Nullable Dim dimension, @Nullable BlockPos pos, boolean menuOpen, String label) {
            this.dimension = dimension;
            this.pos = pos;
            this.menuOpen = menuOpen;
            this.label = label;
        }

        public Dim dimension() {
            return dimension;
        }

        public BlockPos pos() {
            return pos;
        }

        public boolean menuOpen() {
            return menuOpen;
        }

        public String label() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Candidate other = (Candidate) o;
            return java.util.Objects.equals(this.dimension, other.dimension) && java.util.Objects.equals(this.pos, other.pos) && java.util.Objects.equals(this.menuOpen, other.menuOpen) && java.util.Objects.equals(this.label, other.label);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(dimension, pos, menuOpen, label);
        }

        @Override
        public String toString() {
            return "Candidate[" + "dimension=" + dimension + ", " + "pos=" + pos + ", " + "menuOpen=" + menuOpen + ", " + "label=" + label + "]";
        }


        public boolean hasAnchor() {
            return dimension != null && pos != null;
        }
    }

    /** 方向查询顺序，与 WorldProbe 保持一致 */
    private static final EnumFacing[] SIDES = {
            EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };

    private ProbeContext() {
    }

    /**
     * 当前可探测的容器。容器界面开着就优先用它（有菜单 → 可视化选格可用），
     * 否则看准星；都没有返回 null。
     */
    @Nullable
    public static Candidate current(Minecraft mc) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        if (ContainerAccess.hasForeignContainerOpen()) {
            ContainerAnchor.Anchor a = ContainerAnchor.activeFor(ContainerAccess.currentMenu());
            if (a != null) {
                // 设备名从锚点坐标现取（方块名只用于显示，取不到也无所谓）
                return new Candidate(a.dimension(), a.pos(), true, blockName(mc.world, a.pos()));
            }
            // 界面开着但推不出坐标：拿不到持久身份，关界面只能暂停（已知边界，见 CP9）
            return new Candidate(null, null, true,
                    new TextComponentTranslation("logiscope.anchor.open_menu").getUnformattedText());
        }
        RayTraceResult hit = crosshairBlock(mc);
        if (hit == null) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        // 探测资格放宽到"有方块实体"（无容器的 BE 走"无容器格，可监测 NBT"）
        if (!isProbeTarget(mc.world, pos)) {
            return null;
        }
        return new Candidate(Dim.of(mc.world), pos.toImmutable(), false, blockName(mc.world, pos));
    }

    /** 准星指着的方块；没有或没指到方块返回 null */
    @Nullable
    public static RayTraceResult crosshairBlock(Minecraft mc) {
        RayTraceResult hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return null;
        }
        return hit;
    }

    /**
     * 这个方块右键之后会不会打开一个界面 —— **快速肯定判据**。
     *
     * `BlockState#getMenuProvider(World, BlockPos)` 是 public 的
     * （BlockState 继承 BlockBehaviour.BlockStateBase，那一层把 protected 的
     * `BlockBehaviour#getMenuProvider(BlockState, World, BlockPos)` 包成了 public）。
     *
     * **它只对原版箱子这类方块有效**：原版熔炉/木桶和几乎所有模组机器都不覆写它。
     * 所以本方法**只能当"非空 ⇒ 一定有界面"来用**，返回 false 不代表没界面 ——
     * 那一步要问 {@link #wouldConsumeClick}。
     */
    public static boolean hasMenuProvider(World level, BlockPos pos) {
        // 1.16.5 没有 BlockState#getMenuProvider（1.17+ 才有）：
        // 这一代只能靠下面的"会不会消费右键"行为判据，静态快速通道在本版本返回 false。
        return false;
    }

    /**
     * "这个方块会不会消费一次右键"的直接问法 —— 判断有没有界面的主判据 *
     * 做法：直接在客户端调一次 `BlockState#useWithoutItem(World, EntityPlayer, BlockRayTraceResult)`。
     * 所有容器方块都必须实现它（客户端返回 SUCCESS 以做预测，服务端才真的开菜单），
     * 所以它比任何静态判据都准：
     *
     * | 方块 | 客户端 `useWithoutItem` 返回 |
     * |---|---|
     * | 原版箱子 / 熔炉 / 木桶 | `SUCCESS`（第一行就是 `if (level.isRemote()) return SUCCESS`） |
     * | 精妙存储箱子 | `SUCCESS`（javap 核实：`getfield isClientSide` → `getstatic SUCCESS`） |
     * | Mekanism 机器 | 有 `HasGui` 属性 → `SUCCESS`；没有 → `PASS`（javap 核实） |
     * | 真的没有界面的机器 | `PASS` |
     *
     * 为什么是"直接调"而不是"发一次右键再看结果" * `PlayerController#useItemOn` 会在**方块 use 返回 PASS 之后**继续走到
     * `itemstack.useOn(...)` —— 玩家手里拿着扳手对着"没有界面的机器"按快捷键时，
     * 那一步会**真的改动机器**（用户 T15 的顾虑）。直接问一次不会发任何包，
     * 所以"判定为无界面"这条路**完全不碰物品逻辑**。
     *
     * 安全性：这些实现的第一件事就是 `if (level.isClientSide) return ...`，
     * 真正的开菜单逻辑在服务端分支里。所以客户端调用是纯查询。
     * 仍然包一层 try/catch：万一某个模组在客户端分支里做了别的（少数不守规矩的），
     * 我们宁可**当成"有界面"**（继续走发交互那条路），也不要漏掉一个真界面。
     *
     * @return true = 会消费（大概率有界面）；false = PASS/异常之外的"明确没消费"
     */
    public static boolean wouldConsumeClick(World level, BlockPos pos, RayTraceResult hit, EntityPlayer player) {
        try {
            IBlockState state = level.getBlockState(pos);
            // 1.12.2 的签名是"9 参 + 返回 boolean"（没有 EnumActionResult，也没有 RayTraceResult 参数）
            return state.getBlock().onBlockActivated(level, pos, state, player,
                    net.minecraft.util.EnumHand.MAIN_HAND, hit.sideHit,
                    (float) hit.hitVec.x, (float) hit.hitVec.y, (float) hit.hitVec.z);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 这个方块是不是**可探测目标**。
     *
     * 旧判据（{@code IInventory} / ItemHandler 能力）会把 MEK 管道这类方块挡在门外 ——
     * 实测：终极加压管道 `data get block` 明明有数据，按 F9 却没反应。原因：
     * 它的物品在 transmitter 内部网络里流动，**不对外暴露 ItemHandler 能力**，
     * BE 也不是 `IInventory` —— 但 NBT 监测（数据页 / NBT 锚点）对它完全可用。
     * 用户口径：既然 NBT 可监测，"有没有容器"就不再是探测资格的一部分。
     *
     * 普通方块（石头 / 拉杆 / 门）没有 BE → 仍然拒绝，"对非目标方块一个包都不发"
     * 的保证保持不变。
     */
    public static boolean isProbeTarget(World level, BlockPos pos) {
        return level.getTileEntity(pos) != null;
    }

    /**
     * 这个方块是不是"能读到物品的容器"（原版 IInventory 或 NeoForge ItemHandler 能力）。
     *
     * 起它只回答一个问题："值不值得**模拟右键**开 GUI"*   探测资格见 {@link #isProbeTarget}（放宽到有 BE 即可）。两者分离的原因：
     *   有 BE 但没有容器的方块（床、告示牌、MEK 管道…）如果也走"模拟右键"，
     *   对床按 F9 会真的触发睡觉 —— 右键模拟必须只对真容器做。
     */
    public static boolean isContainerLike(World level, BlockPos pos) {
        TileEntity be = level.getTileEntity(pos);
        if (be == null) {
            return false;
        }
        return be instanceof IInventory || itemHandler(level, pos) != null;
    }

    @Nullable
    private static IItemHandler itemHandler(World level, BlockPos pos) {
        // 1.20.1 无 World#getCapability(cap, pos, side)（20.4+ 的 Capabilities 注册表提供），
        // 走方块实体的 LazyOptional 通道
        TileEntity be = level.getTileEntity(pos);
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

    // ==================================================================
    // 模拟"右键这个方块"
    // ==================================================================

    /**
     * 模拟一次使用（右键），让服务端把容器界面打开。
     *
     * 为什么必须模拟真实交互：**只有服务端能开菜单**（`ServerPlayer#openMenu` +
     * `ClientboundOpenScreenPacket`）。纯客户端模组没有别的合法通道，
     * 而 `PlayerController#useItemOn` 正是 vanilla 右键走的同一条路。
     *
     * 用一个保守的前置条件把副作用挡掉 *   `performUseItemOn` 里的顺序是：`RightClickBlock 事件` → **`itemstack.onItemUseFirst`**
     *   → 方块 use → 物品 use。第一条是"物品抢在方块前面"，任何装了扳手/工具类物品的玩家
     *   在对着箱子按快捷键时都会被那条抢走（工具被用掉/机器被改设置）。
     *   所以这里先确认"手上的物品没有覆写 onItemUseFirst"：
     *   用反射读它的**声明类**（只看，不调用），是原版/Forge 的就算安全。
     *
     * @return true = 已经发出交互（界面会在 1~3 tick 后由服务端打开）；
     *         false = 手上物品可能抢走这次交互，没有发出 —— 调用方应提示用户空手再试
     */
    public static boolean tryOpenContainerGui(Minecraft mc, RayTraceResult hit) {
        if (mc.player == null || mc.playerController == null) {
            return false;
        }
        if (!isSafeToSimulateUse(mc.player)) {
            return false;
        }
        // 1.18.2 的 useItemOn 要吃 ClientWorld（1.20.1 起改成了 World）
        // 1.12.2：processRightClickBlock(EntityPlayerSP, WorldClient, BlockPos, EnumFacing, Vec3d, EnumHand)
        mc.playerController.processRightClickBlock(mc.player, (WorldClient) mc.world,
                hit.getBlockPos(), hit.sideHit, hit.hitVec, net.minecraft.util.EnumHand.MAIN_HAND);
        return true;
    }

    /** 手上物品会不会覆写 `onItemUseFirst`（= 会不会抢走这次右键） */
    private static boolean isSafeToSimulateUse(EntityPlayer player) {
        ItemStack held = player.getHeldItem(net.minecraft.util.EnumHand.MAIN_HAND);
        if (held.isEmpty()) {
            return true;
        }
        try {
            // 1.12.2 的 onItemUseFirst 是 8 参（没有 ItemUseContext）
            Method m = held.getItem().getClass()
                    .getMethod("onItemUseFirst", EntityPlayer.class, World.class, BlockPos.class,
                            net.minecraft.util.EnumFacing.class, float.class, float.class, float.class,
                            net.minecraft.util.EnumHand.class);
            // 没覆写时得到的是接口 IForgeItem 上的 default 方法（声明类是 net.minecraftforge.*），
            // 覆写了就落在模组自己的类上。只看声明类，不调用。
            String declaring = m.getDeclaringClass().getName();
            return declaring.startsWith("net.minecraft.")
                    || declaring.startsWith("net.minecraftforge.")
                    || m.getDeclaringClass() == Item.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ==================================================================
    // 显示
    // ==================================================================

    /** 方块名（用于选格页头部显示"我们在配置哪台设备"） */
    public static String blockName(World level, BlockPos pos) {
        try {
            return level.getBlockState(pos).getBlock().getLocalizedName();
        } catch (Throwable t) {
            return "?";
        }
    }
}
