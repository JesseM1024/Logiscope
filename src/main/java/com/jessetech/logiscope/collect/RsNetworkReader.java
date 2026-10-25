package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.RsKeyText;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * **精致存储 1.6 网络读值器**。与 {@link AeNetworkReader} 结构对称：
 * 都是"这台设备所在网络里，这种东西现在有多少"。
 *
 * 【服务端读值链（RS 1.6.16，全部反射，零编译依赖）】
 * <pre>
 * TileEntity
 *   → 接口链里有 INetworkNodeProxy（= 这块是 RS 的网络方块）
 *   → INetworkNodeProxy#getNode()        → INetworkNode
 *   → INetworkNode#getNetwork()          null = 设备没接上网络 → no_data
 *   → INetwork#canRun()                  false = 没电 / 没控制器 → no_data
 *   → INetwork#getItemStorageCache() / #getFluidStorageCache() → IStorageCache
 *   → IStorageCache#getList()            → IStackList（网络里全部东西的汇总表）
 *   → IStackList#getStacks()             → Collection&lt;ItemStack|FluidStack&gt;
 *   → 用 RS 自己的 IComparer.isEqual(…, COMPARE_NBT) 找那一条 → 取它的数量
 * </pre>
 * 后面几个 Class 都是**从上一个方法的返回类型推出来的**（`getNetwork()` 的返回类型就是
 * `INetwork`，`getItemStorageCache()` 的返回类型就是 `IStorageCache`）。
 *
 * 【和 1.9~1.12 那三代最大的差别：没有 `IStackList#getCount(T)`】
 * 那三代能"按一条键直接问数量"；1.6 的 `IStackList` 只有 `getStacks()`，
 * 所以这里**遍历汇总表**、用 **RS 自己的比较器**（`apiimpl.util.Comparer`，
 * 用 `COMPARE_NBT` 标志）判断是不是同一条，命中就取它的数量。
 * 用 RS 的比较器而不是自己拼判据，是为了让"读到的那一条"与 RS 界面上的分组**完全一致**。
 *
 * ★ 比较器实例要用 `Class.forName(name, false, 网络对象的类加载器)` 拿：
 * 本线是唯一需要 forName 的地方（`Comparer` 没有静态实例），
 * 但**loader 取自运行时对象**，不假设调用方的加载器 —— 与项目一贯的做法一致。
 *
 * 【三种语义，与"读不到 / 空格子 / 没在记录"的纪律一致】
 *   · 返回 null = 读不到（不是 RS 网络方块 / 没接网络 / 没电 / 反射链断了）→ 上层记 no_data；
 *   · 返回 0    = 网络在跑，但**没有这种东西**（真值，不是 no_data）；
 *   · 返回 n    = 网络中这种东西的数量（long，交 Val 精确承载）。
 */
public final class RsNetworkReader {

    /** RS 1.6 网络方块实体的根接口 —— 整条反射链唯一写死的类名（包名是老的那套） */
    private static final String NODE_PROXY = "com.raoulvdberge.refinedstorage.api.network.node.INetworkNodeProxy";
    /** RS 自己的栈比较器实现（用来判断"汇总表里哪一条是它"） */
    private static final String COMPARER = "com.raoulvdberge.refinedstorage.apiimpl.util.Comparer";

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
    private static Method mGetStacks;       // IStackList#getStacks()
    @Nullable
    private static Method mIsEqualItem;     // IComparer#isEqual(ItemStack, ItemStack, int)
    @Nullable
    private static Method mIsEqualFluid;    // IComparer#isEqual(FluidStack, FluidStack, int)
    @Nullable
    private static Object comparer;         // Comparer 的实例
    private static int compareNbt;          // IComparer.COMPARE_NBT

    private RsNetworkReader() {
    }

    /**
     * 读"pos 上的 RS 网格设备所在网络中，keyId 这种东西的数量"。
     *
     * @param keyId 见 {@link RsKeyText}（裸物品 id 或 {@code rs:} 串）
     * @return null = 读不到（记 no_data）；否则 = 数量（可为 0，网络在跑就是真值）
     */
    @Nullable
    public static Long read(WorldServer level, BlockPos pos, String keyId) {
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
            ensureComparer(network);
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
            if (list == null || mGetStacks == null) {
                return null;
            }
            Object stacks = mGetStacks.invoke(list);
            if (!(stacks instanceof Collection)) {
                return null;
            }
            for (Object candidate : (Collection<?>) stacks) {
                if (candidate == null) {
                    continue;
                }
                if (same(candidate, key, fluid)) {
                    return amountOf(candidate);
                }
            }
            return 0L;   // 网络在跑，但没这种东西（真值）
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
     * 客户端也能用（纯类检查）：提交网络目标前用它拦掉"锚点落在泥土 / 箱子上"的情况。
     */
    public static boolean isNetworkBlock(@Nullable TileEntity be) {
        return be != null && interfaceNamed(be.getClass(), NODE_PROXY) != null;
    }

    // ==================================================================
    // 反射链
    // ==================================================================

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
            mGetStacks = listType.getMethod("getStacks");
        } catch (Throwable t) {
            rsUnavailable = true;
            LogiScopeMod.LOGGER.warn("[LogiScope] RS API chain not resolvable (targets read as no-data)", t);
        }
    }

    /**
     * 拿到 RS 的比较器（只做一次）。
     *
     * 用**网络对象自己的类加载器**去 forName：RS 的类一定在它的加载器里，
     * 而调用方的加载器不一定看得见（项目里"别用单参 Class.forName"的规矩就是为这个）。
     */
    private static synchronized void ensureComparer(Object network) {
        if (comparer != null) {
            return;
        }
        try {
            ClassLoader loader = network.getClass().getClassLoader();
            Class<?> cls = Class.forName(COMPARER, false, loader);
            comparer = cls.newInstance();
            compareNbt = cls.getField("COMPARE_NBT").getInt(null);
            mIsEqualItem = cls.getMethod("isEqual", ItemStack.class, ItemStack.class, int.class);
            mIsEqualFluid = cls.getMethod("isEqual", FluidStack.class, FluidStack.class, int.class);
        } catch (Throwable t) {
            rsUnavailable = true;
            LogiScopeMod.LOGGER.warn("[LogiScope] RS comparer not resolvable (targets read as no-data)", t);
        }
    }

    /** 汇总表里的这一条是不是我们要找的那一条（用 RS 自己的比较器，带 COMPARE_NBT） */
    private static boolean same(Object candidate, Object key, boolean fluid) throws Exception {
        if (fluid) {
            return candidate instanceof FluidStack && key instanceof FluidStack
                    && (Boolean) mIsEqualFluid.invoke(comparer, candidate, key, compareNbt);
        }
        return candidate instanceof ItemStack && key instanceof ItemStack
                && (Boolean) mIsEqualItem.invoke(comparer, candidate, key, compareNbt);
    }

    /** 一条栈的数量（物品看 count，流体看 amount —— 汇总表里的数就是全网的量） */
    private static long amountOf(Object stack) {
        if (stack instanceof ItemStack) {
            return ((ItemStack) stack).getCount();
        }
        if (stack instanceof FluidStack) {
            return ((FluidStack) stack).amount;
        }
        return 0L;
    }

    // ==================================================================
    // 键
    // ==================================================================

    /**
     * 身份串 → 真实的 {@code ItemStack} / {@code FluidStack}（RS 1.6 能精确重建键）。
     *
     * @return null = 解不出来（注册表里没有 / SNBT 坏了 / 类型不认识）→ 上层记 no_data
     */
    @Nullable
    private static Object rebuild(String keyId) {
        RsKeyText.Parsed parsed = RsKeyText.parse(keyId);
        if (parsed == null) {
            // 裸 registry id = 无 NBT 的物品（老格式，也是绝大多数条目）
            ResourceLocation rl = rlOrNull(keyId);
            Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            return item == null ? null : new ItemStack(item);
        }
        ResourceLocation rl = rlOrNull(parsed.registryId());
        if (rl == null) {
            return null;
        }
        NBTTagCompound tag = null;
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
                stack.setTagCompound(tag);
            }
            return stack;
        }
        if (parsed.type() == RsKeyText.FLUID) {
            Fluid fluid = FluidRegistry.getFluid(parsed.registryId());
            if (fluid == null) {
                // 1.12.2 的 FluidRegistry 里原版流体按不带命名空间的名字注册（water / lava）
                fluid = FluidRegistry.getFluid(RsKeyText.label(parsed.registryId()));
            }
            if (fluid == null) {
                return null;
            }
            FluidStack stack = new FluidStack(fluid, 1);   // 数量不参与比较（只比流体 + NBT）
            if (tag != null) {
                stack.tag = tag;
            }
            return stack;
        }
        return null;   // 不认识类型标记
    }

    /**
     * 字符串 → ResourceLocation。**1.12.2 没有 {@code tryCreate}**（1.13+ 才有），
     * 而构造函数在格式不合法时抛异常 —— 包一层把失败转成 null（上层记 no_data）。
     */
    @Nullable
    private static ResourceLocation rlOrNull(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new ResourceLocation(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SNBT → NBT。解析失败返回 null。
     *
     * 本线的 {@code JsonToNBT#getTagFromJson} 抛**受检**的 {@code NBTException}，
     * 所以这里包一层；上层把 null 当"读不到"（no_data）—— 绝不退回"同 id 全算"。
     */
    @Nullable
    private static NBTTagCompound parseNbt(String snbt) {
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
