package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.AeKeyText;
import com.jessetech.logiscope.bind.AeKeys;
import net.minecraft.fluid.Fluid;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * **AE2 网络读值器**（1.16.5 线 / AE2 8.4.x）。
 *
 * 【为什么是特例】AE 终端的"格子"不是容器内容 —— 它是**网络存储列表的显示窗口**
 * （客户端从同步包拿到的 repo），点击它选中的是"一种东西"，不是"一个槽位"。
 * 所以 AE 目标不走 NBT / 菜单两条既有路，读值语义是：
 * <blockquote>"这台 AE 设备所在的网络里，这种东西现在有多少"</blockquote>
 *
 * 【服务端读值链（AE2 8.4.7，全部反射，零编译依赖 —— 本项目刻意不引入第三方 jar）】
 * <pre>
 * TileEntity
 *   → instanceof IGridHost                     （8.x 没有 GridHelper / IInWorldGridNodeHost）
 *   → getGridNode(AEPartLocation)  INTERNAL 优先，再扫 6 个面取第一个非 null
 *       不能只试一个面：挂在电缆上的**终端部件**（Part）由 CableBusTileEntity 代理，
 *       它的 getGridNode(位置) 只在"那个面/内部位上确实有这个部件"时才返回节点
 *       （CableBusTileEntity#getGridNode 就是一句转发到 CableBusContainer#getGridNode）。
 *       反过来，满方块设备（驱动器、接口）通常只认 INTERNAL。
 *   → node.isActive()                          （false = 网络没供电 / 未成型 → no_data）
 *   → node.getGrid() → IGrid#getCache(IStorageGrid.class)
 *   → IStorageGrid.getInventory(通道) → IMEMonitor
 *       通道由 `appeng.core.Api#instance().storage().getStorageChannel(Class)` 取，
 *       物品走 IItemStorageChannel、流体走 IFluidStorageChannel —— 这一代两套通道并存，
 *       没有 19.x 那种"统一 MEStorage"。
 *   → IMEMonitor#getAvailableItems(通道.createList()) → IItemList
 *   → 遍历清单，按 registry id 找出同一种东西 → IAEStack#getStackSize()
 * </pre>
 *
 * 【键从哪来】目标里存的是一个**字符串身份**（见 {@link AeKeyText}）：
 *   · 物品 → 裸 registry id（`minecraft:iron_ingot`，与 0.1.2 之前的目标同格式）；
 *   · 流体 → `ae:{#t:"ae2:f",id:"minecraft:water"}`（形状与 15.x / 19.x 线一致，
 *     编码在 {@link AeKeys#fluidId}）。其余键类型（化学品 / 电力，来自附属模组）
 *     本分支**暂不支持** —— 读不到就记 no_data，绝不猜一个 0 出来。
 *
 * 【为什么按 registry id 比，而不是构造一个 AE 栈去比对】
 * 8.x 的 `AEItemStack.fromItemStack` 会把 NBT 一起算进 identity，
 * 而本模组的物品身份是**裸 id**（不带 NBT）。直接遍历清单按 id 比，
 * 语义和另外三条线完全一致：同一种物品的所有 NBT 变体**汇总**成一行。
 *
 * 【每次采样只走一遍网络】`getAvailableItems` 会把网络里所有存储的清单汇总一遍
 * （AE 自己的终端也这么干）。一个探针挂 5 个 AE 目标时，同一 tick 内只算一次，
 * 结果按 (IMEMonitor, 通道, gameTime) 缓存 —— 否则 5 个目标会把同一张网络走 5 遍。
 *
 * 【三种语义，与"读不到 / 空格子 / 没在记录"的纪律一致】
 *   · 返回 null  = 设备读不到（不是 AE 设备 / 网络断电 / AE2 未安装 / 反射链断了 /
 *     身份串解不出来）→ 上层记 no_data；
 *   · 返回 0    = 网络在线，但**没有这种东西**（可合成条目不在 availableItems 里）
 *     → 这就是"这东西现在是 0"的真值；
 *   · 返回 n    = 网络中这种东西的可用数量（long，交 Val 精确承载）。
 *
 * 【失败策略】AE2 未安装 → 静默降级（记一次日志，之后恒返回 null，不刷屏）；
 * 单次反射异常 → 记一次日志后本次返回 null（下次采样重试 —— 折腾的是形态异常的
 * 第三方方块，不让它毁掉整条采样）。
 */
public final class AeNetworkReader {

    /** AE 终端界面的菜单基类名（沿父类链比名字，见 {@link #isAeStorageMenu}） */
    private static final String[] STORAGE_MENU_CLASSES = {
            // 15.x / 19.x（1.20+）：ME 存储界面家族
            "appeng.menu.me.common.MEStorageMenu",
            // 8.x（1.16.5）：终端 / 合成终端 / 流体终端都在这条链下
            "appeng.container.me.common.MEMonitorableContainer",
    };

    private static boolean aeUnavailable;    // AE2 不在（只记一次）
    private static boolean loggedFailure;    // 反射链异常（只记一次）

    private static boolean chainReady;
    private static boolean chainOk;

    @Nullable
    private static Class<?> gridHostClass;
    @Nullable
    private static Class<?> storageGridClass;
    @Nullable
    private static Class<?> fluidStackIface;
    @Nullable
    private static Class<?> itemChannelClass;
    @Nullable
    private static Class<?> fluidChannelClass;

    @Nullable
    private static Method mGetGridNode;
    @Nullable
    private static Method mIsActive;
    @Nullable
    private static Method mGetGrid;
    @Nullable
    private static Method mGetCache;
    @Nullable
    private static Method mGetInventory;
    @Nullable
    private static Method mCreateList;
    @Nullable
    private static Method mGetAvailableItems;
    @Nullable
    private static Method mStackSize;
    @Nullable
    private static Method mGetFluidStack;
    @Nullable
    private static Method mGetDefinition;
    @Nullable
    private static Method mApiInstance;
    @Nullable
    private static Method mStorageHelper;
    @Nullable
    private static Method mGetStorageChannel;

    /** 待试的面向：INTERNAL 在最前（满方块设备认它，部件认各自的面） */
    @Nullable
    private static Object[] partLocations;

    /** 通道实例是单例，取一次就存着 */
    @Nullable
    private static Object itemChannel;
    @Nullable
    private static Object fluidChannel;

    private static final ListCache ITEM_CACHE = new ListCache();
    private static final ListCache FLUID_CACHE = new ListCache();

    private AeNetworkReader() {
    }

    /**
     * 读"pos 上的 AE 设备所在网络中，keyId 这种东西的可用数量"。
     *
     * @param keyId 物品 registry id，或 {@code ae:{#t:"ae2:f",id:"…"}} 形式的键串
     * @return null = 读不到（记 no_data）；否则 = 数量（可为 0，网络在线就是真值）
     */
    @Nullable
    public static Long read(ServerWorld level, BlockPos pos, String keyId) {
        try {
            if (aeUnavailable || !chain()) {
                return null;
            }
            Key key = Key.parse(keyId);
            if (key == null) {
                return null;   // 解不出来的身份（例如附属模组的化学品）→ 读不到
            }

            // 1) 设备是不是 AE 网络节点宿主
            TileEntity be = level.getTileEntity(pos);
            if (be == null || !gridHostClass.isInstance(be)) {
                return null;   // 不是 AE 设备（或方块刚被换掉）
            }

            // 2) 拿节点：INTERNAL 优先，再扫 6 个面（见类注释——部件挂电缆的面暴露陷阱）
            Object node = null;
            for (Object location : partLocations) {
                Object n = mGetGridNode.invoke(be, location);
                if (n != null) {
                    node = n;
                    break;
                }
            }
            if (node == null) {
                return null;   // 节点在所有面都不可见（极端遮挡）→ 读不到
            }

            // 3) 网络是否在供电（false = 断电 / 未成型 → no_data，不是 0）
            if (!Boolean.TRUE.equals(mIsActive.invoke(node))) {
                return null;
            }

            // 4) grid → storageGrid → monitor（按键的类型选物品通道 / 流体通道）
            Object grid = mGetGrid.invoke(node);
            if (grid == null) {
                return null;
            }
            Object storageGrid = mGetCache.invoke(grid, storageGridClass);
            if (storageGrid == null) {
                return null;
            }
            Object channel = key.fluid ? fluidChannel() : itemChannel();
            if (channel == null) {
                return null;
            }
            Object monitor = mGetInventory.invoke(storageGrid, channel);
            if (monitor == null) {
                return null;
            }

            // 5) 本 tick 这张网络的汇总清单（同 tick 只算一次）
            Object list = listFor(monitor, channel, level.getGameTime(),
                    key.fluid ? FLUID_CACHE : ITEM_CACHE);
            if (list == null) {
                return null;
            }

            // 6) 遍历找同一种东西（按 registry id，见类注释）
            for (Object stack : (Iterable<?>) list) {
                if (stack == null) {
                    continue;
                }
                if (!key.id.equals(idOfStack(stack, key.fluid))) {
                    continue;
                }
                Object size = mStackSize.invoke(stack);
                return size instanceof Long ? (Long) size : null;
            }
            return 0L;   // 网络在线但没有这种东西 —— 这就是"现在 0 个"
        } catch (ClassNotFoundException e) {
            // AE2 没装：静默降级（记一次日志，之后恒 no_data）
            if (!aeUnavailable) {
                aeUnavailable = true;
                LogiScopeMod.LOGGER.info("[LogiScope] AE2 not present, AE network targets read as no-data");
            }
            return null;
        } catch (Throwable t) {
            // 其它异常（形态异常的第三方方块等）：记一次，本次读不到，下次采样重试
            if (!loggedFailure) {
                loggedFailure = true;
                LogiScopeMod.LOGGER.warn("[LogiScope] AE network read failed (won't spam)", t);
            }
            return null;
        }
    }

    // ==================================================================
    // 键
    // ==================================================================

    /** 一个待读的键：是物品还是流体 + 它自己的 registry id */
    private static final class Key {
        private final boolean fluid;
        private final String id;

        private Key(boolean fluid, String id) {
            this.fluid = fluid;
            this.id = id;
        }

        @Nullable
        static Key parse(@Nullable String keyId) {
            if (keyId == null || keyId.isEmpty()) {
                return null;
            }
            if (!AeKeyText.isAe(keyId)) {
                return new Key(false, keyId);   // 物品：裸 registry id（老目标格式）
            }
            if (!AeKeys.FLUID_TYPE.equals(AeKeyText.typeOf(keyId))) {
                return null;   // 化学品 / 电力等附属类型：本分支暂不支持（记 no_data）
            }
            String id = AeKeyText.idOf(keyId);
            return id == null ? null : new Key(true, id);
        }
    }

    /** 清单里的一个栈 → 它自己的 registry id（流体取流体的，物品取物品的） */
    @Nullable
    private static String idOfStack(Object stack, boolean fluid) {
        try {
            if (fluid) {
                if (!fluidStackIface.isInstance(stack)) {
                    return null;
                }
                Object fs = mGetFluidStack.invoke(stack);
                if (!(fs instanceof FluidStack)) {
                    return null;
                }
                Fluid f = ((FluidStack) fs).getFluid();
                ResourceLocation id = f == null ? null : ForgeRegistries.FLUIDS.getKey(f);
                return id == null ? null : id.toString();
            }
            Object def = mGetDefinition.invoke(stack);
            if (!(def instanceof ItemStack)) {
                return null;
            }
            ItemStack is = (ItemStack) def;
            if (is.isEmpty()) {
                return null;
            }
            Item item = is.getItem();
            ResourceLocation id = item == null ? null : ForgeRegistries.ITEMS.getKey(item);
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            return null;   // 单个栈读不出来就当它不匹配，不影响同网络的其它条目
        }
    }

    // ==================================================================
    // 清单缓存（同 tick 同一张网络只走一遍）
    // ==================================================================

    private static final class ListCache {
        private WeakReference<Object> monitor = new WeakReference<>(null);
        private WeakReference<Object> list = new WeakReference<>(null);
        private long tick = Long.MIN_VALUE;
    }

    @Nullable
    private static Object listFor(Object monitor, Object channel, long tick, ListCache cache) throws Exception {
        if (cache.tick == tick) {
            Object cachedMonitor = cache.monitor.get();
            Object cachedList = cache.list.get();
            if (cachedMonitor == monitor && cachedList != null) {
                return cachedList;
            }
        }
        Object out = mCreateList.invoke(channel);   // 通道给的空清单
        if (out == null) {
            return null;
        }
        Object filled = mGetAvailableItems.invoke(monitor, out);
        if (filled == null) {
            return null;
        }
        cache.monitor = new WeakReference<>(monitor);
        cache.list = new WeakReference<>(filled);
        cache.tick = tick;
        return filled;
    }

    // ==================================================================
    // 通道
    // ==================================================================

    @Nullable
    private static Object itemChannel() {
        if (itemChannel == null) {
            itemChannel = channel(itemChannelClass);
        }
        return itemChannel;
    }

    @Nullable
    private static Object fluidChannel() {
        if (fluidChannel == null) {
            fluidChannel = channel(fluidChannelClass);
        }
        return fluidChannel;
    }

    /** {@code Api.instance().storage().getStorageChannel(通道接口)} */
    @Nullable
    private static Object channel(@Nullable Class<?> iface) {
        if (iface == null) {
            return null;
        }
        try {
            Object api = mApiInstance.invoke(null);
            Object helper = mStorageHelper.invoke(api);
            return mGetStorageChannel.invoke(helper, iface);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LogiScopeMod.LOGGER.warn("[LogiScope] AE storage channel lookup failed (won't spam)", t);
            }
            return null;
        }
    }

    // ==================================================================
    // 反射链
    // ==================================================================

    /** 解析反射链（只做一次）；失败即视为 AE2 不在 */
    private static boolean chain() {
        if (chainReady) {
            return chainOk;
        }
        chainReady = true;
        try {
            Class<?> partLocation = cls("appeng.api.util.AEPartLocation");
            gridHostClass = cls("appeng.api.networking.IGridHost");
            Class<?> gridNode = cls("appeng.api.networking.IGridNode");
            Class<?> grid = cls("appeng.api.networking.IGrid");
            storageGridClass = cls("appeng.api.networking.storage.IStorageGrid");
            Class<?> monitorable = cls("appeng.api.storage.IStorageMonitorable");
            Class<?> storageChannel = cls("appeng.api.storage.IStorageChannel");
            Class<?> meInventory = cls("appeng.api.storage.IMEInventory");
            Class<?> itemList = cls("appeng.api.storage.data.IItemList");
            Class<?> aeStack = cls("appeng.api.storage.data.IAEStack");
            fluidStackIface = cls("appeng.api.storage.data.IAEFluidStack");
            Class<?> itemStackIface = cls("appeng.api.storage.data.IAEItemStack");
            Class<?> api = cls("appeng.core.Api");
            Class<?> storageHelper = cls("appeng.api.storage.IStorageHelper");
            itemChannelClass = cls("appeng.api.storage.channels.IItemStorageChannel");
            fluidChannelClass = cls("appeng.api.storage.channels.IFluidStorageChannel");

            // 8.x 的签名（与 12+ 的 Direction、并存的 GridHelper 全都不同）：
            //   IGridHost#getGridNode(AEPartLocation)
            mGetGridNode = gridHostClass.getMethod("getGridNode", partLocation);
            mIsActive = gridNode.getMethod("isActive");
            mGetGrid = gridNode.getMethod("getGrid");
            mGetCache = grid.getMethod("getCache", Class.class);
            mGetInventory = monitorable.getMethod("getInventory", storageChannel);
            mCreateList = storageChannel.getMethod("createList");
            mGetAvailableItems = meInventory.getMethod("getAvailableItems", itemList);
            mStackSize = aeStack.getMethod("getStackSize");
            mGetFluidStack = fluidStackIface.getMethod("getFluidStack");
            mGetDefinition = itemStackIface.getMethod("getDefinition");
            mApiInstance = api.getMethod("instance");
            mStorageHelper = api.getMethod("storage");
            mGetStorageChannel = storageHelper.getMethod("getStorageChannel", Class.class);

            partLocations = orderedLocations(partLocation);
            chainOk = true;
            return true;
        } catch (ClassNotFoundException e) {
            if (!aeUnavailable) {
                aeUnavailable = true;
                LogiScopeMod.LOGGER.info("[LogiScope] AE2 not present, AE network targets read as no-data");
            }
            return false;
        } catch (Throwable t) {
            aeUnavailable = true;
            LogiScopeMod.LOGGER.warn("[LogiScope] AE API chain not resolvable (targets read as no-data)", t);
            return false;
        }
    }

    /**
     * 加载 AE 的类，**不触发静态初始化**。
     *
     * 为什么必须带 {@code initialize=false}：`appeng.core.Api` 的 {@code <clinit>} 里
     * 会 new 一整套注册表容器并往 Forge 的 EventBus 上挂监听 —— 那是 AE 自己在
     * preinit 干的事。单参 {@code Class.forName("appeng.core.Api")} 会**在解析反射链的
     * 那一刻**就把它初始化掉；游戏里碰巧没事（AE 早就初始化过了），
     * 但离线探针（dev-check/ProbeAeApi）会当场炸在
     * {@code NoSuchMethodException: LocatableEventAnnounce.<init>()} ——
     * 表现为"探针说链不通"，其实只是初始化顺序问题。
     * 真正的初始化留到 {@link #channel} 第一次真正调用 {@code Api.instance()} 时，那时 AE 早就好了。
     */
    private static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, false, AeNetworkReader.class.getClassLoader());
    }

    /** 枚举常量重新排序：INTERNAL 提到最前（见类注释：两类设备认的面不一样） */
    private static Object[] orderedLocations(Class<?> partLocation) {
        Object[] all = partLocation.getEnumConstants();
        if (all == null) {
            return new Object[0];
        }
        Object[] out = new Object[all.length];
        int n = 0;
        for (Object c : all) {
            if (c instanceof Enum && "INTERNAL".equals(((Enum<?>) c).name())) {
                out[n++] = c;
            }
        }
        for (Object c : all) {
            if (!(c instanceof Enum) || !"INTERNAL".equals(((Enum<?>) c).name())) {
                out[n++] = c;
            }
        }
        return out;
    }

    /**
     * 菜单是不是 AE 的 ME 存储界面家族（终端 / 合成终端 / 流体终端 / 单元仓…）。
     *
     * 判定方式 = 沿父类链比对**类名**，不用 {@code Class.forName}：
     * 模组环境里每个模组在自己的类加载器里，{@code Class.forName("appeng.…")} 用的是
     * **调用方**的加载器 —— 不一定能看到 AE2（1.20.1 线实测正是死在这里）。
     * 而 {@code menu.getClass()} 是运行时的真实对象，沿 {@code getSuperclass()} 往上走
     * 总能拿到 AE2 那个类，比对名字即可 —— 零加载、零可见性假设。
     * AE2 未安装时菜单类名永远对不上 → false，行为正确。
     *
     * ★ 1.16.5 的坑：8.x 的终端基类叫
     * {@code appeng.container.me.common.MEMonitorableContainer}，
     * **不是** 15.x / 19.x 的 {@code appeng.menu.me.common.MEStorageMenu}。
     * 只认后者的话，AE 终端里按 F9 会被判成"普通容器" → AE 模式不生效、列表里
     * 一个网络条目都列不出来（实测症状：F9 呼不出与 AE 相关的界面）。
     */
    public static boolean isAeStorageMenu(@Nullable Object menu) {
        for (Class<?> c = menu == null ? null : menu.getClass(); c != null; c = c.getSuperclass()) {
            for (String name : STORAGE_MENU_CLASSES) {
                if (name.equals(c.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
