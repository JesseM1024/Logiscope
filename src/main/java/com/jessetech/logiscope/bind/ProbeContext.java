package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.collect.ContainerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
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
 * "有没有 GUI"的判据换过三代，两代教训都留着 *
 * 第一代：`BlockState#getMenuProvider`（方块级）—— 对下面这些方块判不出来，
 * 会进"无 GUI"分支（实测：空手对着精妙存储箱子、MEK 熔炼工厂按快捷键，界面没打开）：
 *
 * | 方块 | 覆写方块级 `getMenuProvider`？ | 界面从哪开 |
 * |---|---|---|
 * | 原版 `ChestBlock` | ✅ 覆写（`SimpleMenuProvider`） | `getMenuProvider` + `openMenu` |
 * | 原版 `AbstractFurnaceBlock` / `BarrelBlock` | ❌ 不覆写 | 走方块实体：`BaseContainerBlockEntity` **实现 MenuProvider** |
 * | 精妙存储 `StorageBlockEntity` | ❌ 不覆写 | 自建通道，不占标准两条 |
 * | Mekanism `BlockTile` | ❌ 不覆写 | 自建通道：`AttributeGui` 现造 MenuProvider + `NetworkHooks.openScreen` |
 *
 * 第二代：问一次"这个方块右键会不会**消费**"（客户端预测返回 SUCCESS）——
 * 对上面的机器有效，但 1.20.1 的 `use()` 把**物品交互**也算作消费：Create 机械手
 * 空手右键会真的存取物品、同样返回 SUCCESS，于是对着它按 F9 会**误发一次真实
 * 右键**（实测：触发一次物品交互）。两类含义从返回值上无法区分 —— 1.21 线能区分，
 * 是因为 `use()` 拆分成了 `useItemOn` / `useWithoutItem`（main 分支继续用
 * "空手会不会消费"；两地差异见 `PORT-1.20.1.md` §七）。
 *
 * 第三代（现行）：只认**标准界面通道** —— {@link #hasMenuChannel}：方块级非空
 * **或** 方块实体实现 `MenuProvider`（原版熔炉 / 木桶经由 `BaseContainerBlockEntity`
 * 天然命中）。{@link #wouldConsumeClick} 降级为"提示"用途：只决定要不要告诉用户
 * "这台设备可能其实有界面"。
 *
 * 代价（已接受的取舍）：自建界面通道的机器（上表后两行）不再被识别 —— F9 不模拟
 * 右键、直接进创建流程（列表选格），附一条手动路径提示（先右键打开界面再按 F9）。
 * 宁可多一步手动，也不向设备误发交互 —— 误交互会真的改动物品 / 机器状态。
 *
 * "是不是容器方块实体" 的判定是 `Container` 或 ItemHandler 能力 *   和采集层 {@code WorldProbe#readNow} 的两条读取路径严格对齐：
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
                // 设备名从锚点坐标现取（方块名只用于显示，取不到也无所谓）
                return new Candidate(a.dimension(), a.pos(), true, blockName(mc.level, a.pos()));
            }
            // 界面开着但推不出坐标：拿不到持久身份，关界面只能暂停（已知边界，见 CP9）
            return new Candidate(null, null, true,
                    Component.translatable("logiscope.anchor.open_menu").getString());
        }
        BlockHitResult hit = crosshairBlock(mc);
        if (hit == null) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        // 探测资格放宽到"有方块实体"（无容器的 BE 走"无容器格，可监测 NBT"）
        if (!isProbeTarget(mc.level, pos)) {
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
     * 方块级界面通道：`BlockState#getMenuProvider(Level, BlockPos)` 非空。
     *
     * 该方法 public 的来路：BlockState 继承 BlockBehaviour.BlockStateBase，
     * 那一层把 protected 的 `BlockBehaviour#getMenuProvider(BlockState, Level, BlockPos)`
     * 包成了 public。
     *
     * **只对覆写它的方块有效**（原版箱子这类）；原版熔炉/木桶与几乎所有模组机器
     * 都不覆写。所以本方法**只能当"非空 ⇒ 一定有界面"来用**，返回 false 不代表
     * 没界面 —— 还有方块实体级通道，合并判据见 {@link #hasMenuChannel}。
     */
    public static boolean hasMenuProvider(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        MenuProvider provider = state.getMenuProvider(level, pos);
        return provider != null;
    }

    /**
     * 这个方块有没有**标准界面通道** —— 1.20.1 线 F9 判定"有界面"的唯一判据。
     *
     * 两条通道任取其一：
     *   ① 方块级：{@link #hasMenuProvider}（原版箱子这类覆写它的方块）；
     *   ② 方块实体级：`BlockEntity instanceof MenuProvider`（原版熔炉 / 木桶等
     *      经由 `BaseContainerBlockEntity` 天然实现）。
     *
     * 为什么不问"会不会消费右键"：见类注释的三代判据（机械手空手右键会存取
     * 物品、同样返回"消费"，会把设备误判成有界面）。代价 = 自建界面通道的机器
     * （MEK / 精妙存储 / AE2）按无界面处理 —— 多一步手动，不误发交互。
     */
    public static boolean hasMenuChannel(Level level, BlockPos pos) {
        if (hasMenuProvider(level, pos)) {
            return true;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof MenuProvider;
    }

    /**
     * "这个方块会不会消费一次右键" —— **只用于提示**，不再用于判定有没有界面
     * （判界面用 {@link #hasMenuChannel}）。
     *
     * 返回 true 同时覆盖两种东西：有界面的客户端预测（箱子 / 熔炉 / MEK 带 GUI
     * 的机器），和"会做动作的设备"（Create 机械手：空手右键会存取物品）——
     * 在 1.20.1 上无法从返回值区分，所以不能拿它决定"要不要模拟一次真实右键"。
     * 现行用法：无界面通道 + 会消费 ⇒ 提示"这台设备可能其实有界面"。
     *
     * | 方块 | 客户端 `use` 返回 |
     * |---|---|
     * | 原版箱子 / 熔炉 / 木桶 | `SUCCESS`（第一行就是 `if (level.isClientSide()) return SUCCESS`） |
     * | 精妙存储箱子 | `SUCCESS`（javap 核实：`getfield isClientSide` → `getstatic SUCCESS`） |
     * | Mekanism 机器 | 有 `HasGui` 属性 → `SUCCESS`；没有 → `PASS`（javap 核实） |
     * | Create 机械手（**反例**） | 大多朝向 `SUCCESS`（空手右键会存取物品，见类注释） |
     *
     * 为什么是"直接调"而不是"发一次右键再看结果" * `MultiPlayerGameMode#useItemOn` 会在**方块 use 返回 PASS 之后**继续走到
     * `itemstack.useOn(...)` —— 玩家手里拿着扳手对着"没有界面的机器"按快捷键时，
     * 那一步会**真的改动机器**（用户 T15 的顾虑）。直接问一次不会发任何包。
     *
     * 安全性：兼容的方块在客户端分支第一行就 `return ...`，真正的开菜单 / 状态
     * 改动在服务端分支里，所以客户端调用是纯查询。仍然包一层 try/catch：
     * 万一某个模组在客户端分支里做了别的（少数不守规矩的），宁可当成"会消费"
     * （多提示一句），也不要漏报。
     *
     * @return true = 会消费（可能是有界面、也可能只是会做动作）
     */
    public static boolean wouldConsumeClick(Level level, BlockPos pos, BlockHitResult hit, Player player) {
        try {
            return level.getBlockState(pos).use(level, player, InteractionHand.MAIN_HAND, hit).consumesAction();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 这个方块是不是**可探测目标**。
     *
     * 旧判据（{@code Container} / ItemHandler 能力）会把 MEK 管道这类方块挡在门外 ——
     * 实测：终极加压管道 `data get block` 明明有数据，按 F9 却没反应。原因：
     * 它的物品在 transmitter 内部网络里流动，**不对外暴露 ItemHandler 能力**，
     * BE 也不是 `Container` —— 但 NBT 监测（数据页 / NBT 锚点）对它完全可用。
     * 用户口径：既然 NBT 可监测，"有没有容器"就不再是探测资格的一部分。
     *
     * 普通方块（石头 / 拉杆 / 门）没有 BE → 仍然拒绝，"对非目标方块一个包都不发"
     * 的保证保持不变。
     */
    public static boolean isProbeTarget(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) != null;
    }

    /**
     * 这个方块是不是"能读到物品的容器"（原版 Container 或 NeoForge ItemHandler 能力）。
     *
     * 起它只回答一个问题："值不值得**模拟右键**开 GUI"*   探测资格见 {@link #isProbeTarget}（放宽到有 BE 即可）。两者分离的原因：
     *   有 BE 但没有容器的方块（床、告示牌、MEK 管道…）如果也走"模拟右键"，
     *   对床按 F9 会真的触发睡觉 —— 右键模拟必须只对真容器做。
     */
    public static boolean isContainerLike(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        return be instanceof Container || itemHandler(level, pos) != null;
    }

    @Nullable
    private static IItemHandler itemHandler(Level level, BlockPos pos) {
        // 1.20.1 无 Level#getCapability(cap, pos, side)（20.4+ 的 Capabilities 注册表提供），
        // 走方块实体的 LazyOptional 通道
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        IItemHandler direct = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (direct != null) {
            return direct;
        }
        for (Direction side : SIDES) {
            IItemHandler sided = be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
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
     * 用一个保守的前置条件把副作用挡掉 *   `performUseItemOn` 里的顺序是：`RightClickBlock 事件` → **`itemstack.onItemUseFirst`**
     *   → 方块 use → 物品 use。第一条是"物品抢在方块前面"，任何装了扳手/工具类物品的玩家
     *   在对着箱子按快捷键时都会被那条抢走（工具被用掉/机器被改设置）。
     *   所以这里先确认"手上的物品没有覆写 onItemUseFirst"：
     *   用反射读它的**声明类**（只看，不调用），是原版/Forge 的就算安全。
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
    public static String blockName(Level level, BlockPos pos) {
        try {
            return level.getBlockState(pos).getBlock().getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
