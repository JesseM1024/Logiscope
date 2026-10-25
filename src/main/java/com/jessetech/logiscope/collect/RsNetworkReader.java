package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.RsKeyText;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.fluid.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * **精致存储 1.x 网络读值器**。与 {@link AeNetworkReader} 结构对称：
 * 都是"这台设备所在网络里，这种东西现在有多少"。
 *
 * 【服务端读值链（RS 1.9.x，全部反射，零编译依赖）】
 * <pre>
 * TileEntity
 *   → 接口链里有 INetworkNodeProxy（= 这块是 RS 的网络方块）
 *   → INetworkNodeProxy#getNode()        → INetworkNode
 *   → INetworkNode#getNetwork()          null = 设备没接上网络 → no_data
 *   → INetwork#canRun()                  false = 没电 / 没控制器 → no_data
 *   → INetwork#getItemStorageCache() / #getFluidStorageCache() → IStorageCache
 *   → IStorageCache#getList()            → IStackList（网络里全部东西的汇总表）
 *   → IStackList#getCount(stack)         → int
 * </pre>
 * 后面几个 Class 都是**从上一个方法的返回类型推出来的**（`getNetwork()` 的返回类型就是
 * `INetwork`，`getItemStorageCache()` 的返回类型就是 `IStorageCache`），所以整条链
 * 只写死一个类名（`INetworkNodeProxy`）—— 版本间挪了别的类也不影响。
 *
 * 【和 1.21.1 线（RS 2.x）最大的差别：键能精确重建】
 * RS 2.x 的键是 `ItemResource` + `DataComponentPatch`，反射层没有可靠的往返手段，
 * 只能"拉全量再按自己的编码比对"。RS 1.x 的键**就是** `ItemStack` / `FluidStack`，
 * 身份串里的 SNBT 用 {@link JsonToNBT#getTagFromJson} 解回来就能重建一个一模一样的栈，
 * 于是这里可以**直接问一条键的数量**（`getCount`），不必扫全表。
 *
 * 【`getCount(T)` 到底比什么】它转调 `getCount(stack, IComparer.COMPARE_NBT)`
 * （字节码核实：默认方法塞的常量是 `iconst_1`，而 {@code IComparer.COMPARE_NBT = 1}）
 * ⇒ **物品 id + NBT 一起比**。所以"附魔书"和"同名白书"各自算各自的，
 * 不会出现"读出来是两者之和"的假值。
 *
 * 【三种语义，与"读不到 / 空格子 / 没在记录"的纪律一致】
 *   · 返回 null = 读不到（不是 RS 网络方块 / 没接网络 / 没电 / 反射链断了）→ 上层记 no_data；
 *   · 返回 0    = 网络在跑，但**没有这种东西**（真值，不是 no_data）；
 *   · 返回 n    = 网络中这种东西的数量（long，交 Val 精确承载）。
 */
public final class RsNetworkReader {

    /** RS 1.x 网络方块实体的根接口 —— 整条反射链唯一写死的类名 */
    private static final String NODE_PROXY = "com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy";

    private static boolean rsUnavailable;   // 反射链建不起来（只记一次）
    private static boolean loggedFailure;   // 单次反射异常（只记一次）

    @Nullable
    private static Method mGetNode;         // INetworkNodeProxy#getNode()
    @Nullable
    private static Method mGetNetwork;      // INetworkNode#getNetwork()
    @Nullable
    private static Method mCanRun;          // INetwork#canRun()
    @Nullable
    private static Method mGetItemCache;    // INetwork#getItemStorageCache()
    @Nullable
    private static Method mGetFluidCache;   // INetwork#getFluidStorageCache()
    @Nullable
    private static Method mGetList;         // IStorageCache#getList()
    @Nullable
    private static Method mGetCount;        // IStackList#getCount(T)

    private RsNetworkReader() {
    }

    /**
     * 读"pos 上的 RS 网格设备所在网络中，keyId 这种东西的数量"。
     *
     * @param keyId 见 {@link RsKeyText}（裸物品 id 或 {@code rs:} 串）
     * @return null = 读不到（记 no_data）；否则 = 数量（可为 0，网络在跑就是真值）
     */
    @Nullable
    public static Long read(ServerWorld level, BlockPos pos, String keyId) {
        try {
            if (rsUnavailable) {
                return null;
            }
            TileEntity be = level.getTileEntity(pos);
            if (be == null) {
                return null;
            }
            Object node = nodeOf(be);
            if (node == null) {
                return null;   // 不是 RS 网络方块（也可能这块地方已经换成别的东西）
            }
            Object network = mGetNetwork.invoke(node);
            if (network == null) {
                return null;   // 没接上网络 → no_data（不是 0）
            }
            if (!(Boolean) mCanRun.invoke(network)) {
                return null;   // 没电 / 没控制器 → no_data
            }
            Object key = rebuild(keyId);
            if (key == null) {
                return null;   // 注册表里没有这种东西 / SNBT 坏了 → 读不到
            }
            RsKeyText.Parsed parsed = RsKeyText.parse(keyId);
            boolean fluid = parsed != null && parsed.type() == RsKeyText.FLUID;
            Object cache = (fluid ? mGetFluidCache : mGetItemCache).invoke(network);
            if (cache == null) {
                return null;
            }
            Object list = mGetList.invoke(cache);
            if (list == null) {
                return null;
            }
            Object n = mGetCount.invoke(list, key);
            return n instanceof Integer ? (long) ((Integer) n).intValue() : null;
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LogiScopeMod.LOGGER.warn("[LogiScope] RS network read failed (won't spam)", t);
            }
            return null;
        }
    }

    /**
     * 这个方块实体是不是 **RS 网络方块** —— 就是 {@link #read} 的第一步判据。
     *
     * 客户端也能用（纯类检查，不碰 {@code getNode()} 的返回值）：提交网络目标前
     * 用它拦掉"锚点落在一块泥土 / 一个箱子上"的情况。与读值器**同一个判据**，
     * 所以不会出现"这里说能读、那边读不到"。
     *
     * 注意它比"是不是网格"更宽：磁盘驱动器 / 接口这些**同一张网络上的任何方块**都算。
     * 这是刻意的 —— 读的是"这块方块所在网络的汇总"，与方块本身是不是网格无关。
     */
    public static boolean isNetworkBlock(@Nullable TileEntity be) {
        return be != null && interfaceNamed(be.getClass(), NODE_PROXY) != null;
    }

    // ==================================================================
    // 反射链
    // ==================================================================

    /**
     * 方块实体 → 网络节点；不是 RS 网络方块时返回 null（常态，不该记日志）。
     *
     * 第一次遇到 RS 方块时顺手把整条链解析完（**只做一次**）。
     */
    @Nullable
    private static Object nodeOf(TileEntity be) throws Exception {
        Class<?> proxy = interfaceNamed(be.getClass(), NODE_PROXY);
        if (proxy == null) {
            return null;
        }
        ensureChain(proxy);
        if (mGetNode == null) {
            return null;
        }
        return mGetNode.invoke(be);
    }

    private static synchronized void ensureChain(Class<?> proxy) {
        if (mGetNetwork != null || rsUnavailable) {
            return;
        }
        try {
            mGetNode = proxy.getMethod("getNode");
            // 剩下的类型全部由上一个方法的返回类型推出来 —— 不写死类名，版本挪类也不怕
            Class<?> nodeType = mGetNode.getReturnType();
            mGetNetwork = nodeType.getMethod("getNetwork");
            Class<?> networkType = mGetNetwork.getReturnType();
            mCanRun = networkType.getMethod("canRun");
            mGetItemCache = networkType.getMethod("getItemStorageCache");
            mGetFluidCache = networkType.getMethod("getFluidStorageCache");
            Class<?> cacheType = mGetItemCache.getReturnType();
            mGetList = cacheType.getMethod("getList");
            Class<?> listType = mGetList.getReturnType();
            mGetCount = listType.getMethod("getCount", Object.class);
        } catch (Throwable t) {
            rsUnavailable = true;
            LogiScopeMod.LOGGER.warn("[LogiScope] RS API chain not resolvable (targets read as no-data)", t);
        }
    }

    // ==================================================================
    // 键
    // ==================================================================

    /**
     * 身份串 → 真实的 {@code ItemStack} / {@code FluidStack}（RS 1.x 能精确重建键）。
     *
     * @return null = 解不出来（注册表里没有 / SNBT 坏了 / 类型不认识）→ 上层记 no_data
     */
    @Nullable
    private static Object rebuild(String keyId) {
        RsKeyText.Parsed parsed = RsKeyText.parse(keyId);
        if (parsed == null) {
            // 裸 registry id = 无 NBT 的物品（老格式，也是绝大多数条目）
            ResourceLocation rl = ResourceLocation.tryCreate(keyId);
            Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            return item == null ? null : new ItemStack(item);
        }
        ResourceLocation rl = ResourceLocation.tryCreate(parsed.registryId());
        if (rl == null) {
            return null;
        }
        CompoundNBT tag = null;
        if (parsed.payload() != null && !parsed.payload().isEmpty()) {
            tag = parseNbt(parsed.payload());
            if (tag == null) {
                return null;   // 载荷坏了 → 读不到，绝不退回"同 id 全算"
            }
        }
        if (parsed.type() == RsKeyText.ITEM) {
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null) {
                return null;
            }
            ItemStack stack = new ItemStack(item);
            if (tag != null) {
                stack.setTag(tag);
            }
            return stack;
        }
        if (parsed.type() == RsKeyText.FLUID) {
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(rl);
            if (fluid == null) {
                return null;
            }
            FluidStack stack = new FluidStack(fluid, 1);   // 数量不参与比较（只比 id + NBT）
            if (tag != null) {
                stack.setTag(tag);
            }
            return stack;
        }
        return null;   // 不认识类型标记
    }

    /**
     * SNBT → NBT。解析失败返回 null。
     *
     * 1.20.1 的 {@code JsonToNBT#getTagFromJson} 抛**受检**的 {@code CommandSyntaxException}，
     * 所以这里包一层；上层把 null 当"读不到"（no_data）—— 绝不退回"同 id 全算"。
     */
    @Nullable
    private static CompoundNBT parseNbt(String snbt) {
        try {
            return JsonToNBT.getTagFromJson(snbt);
        } catch (Exception e) {
            return null;
        }
    }

    /** 在类自身与它的接口链里按名字找接口 */
    @Nullable
    private static Class<?> interfaceNamed(@Nullable Class<?> from, String name) {
        if (from == null) {
            return null;
        }
        if (name.equals(from.getName())) {
            return from;
        }
        for (Class<?> itf : from.getInterfaces()) {
            Class<?> hit = interfaceNamed(itf, name);
            if (hit != null) {
                return hit;
            }
        }
        return interfaceNamed(from.getSuperclass(), name);
    }
}
