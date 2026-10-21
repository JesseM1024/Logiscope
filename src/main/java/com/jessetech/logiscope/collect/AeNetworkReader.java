package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.AeKeyText;
import com.jessetech.logiscope.bind.AeKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * **AE2 网络读值器**。
 *
 * 【为什么是特例】AE 终端的"格子"不是容器内容 —— 它是**网络存储列表的显示窗口**
 * （客户端从同步包拿到的 repo），点击它选中的是"一个物品"，不是"一个槽位"。
 * 所以 AE 目标不能走 NBT / 菜单两条既有路，读值语义是：
 * <blockquote>"这台 AE 设备所在的网络里，这种东西现在有多少"</blockquote>
 *
 * 【服务端读值链（AE2 19.2.x，全部反射，零编译依赖 —— 本项目刻意不引入第三方 jar）】
 * <pre>
 * BlockEntity
 *   → GridHelper.getNodeHost(level, pos)        → IInWorldGridNodeHost（null = 不是 AE 设备）
 *   → getGridNode(dir) 遍历 6 个面取第一个非 null
 *       不能只试一个方向：CableBusBlockEntity（终端挂在电缆上）的 default 实现
 *         会检查 isExposedOnSide(dir) —— 节点被其它部件遮住的面返回 null。
 *       也不能用 getActionableNode()：CableBus 不实现 IActionHost（字节码核实过）。
 *   → node.isActive()（false = 网络没供电 / 未成型 → no_data）
 *   → node.getGrid() → IGrid.getStorageService() → IStorageService.getInventory()
 *   → MEStorage.getAvailableStacks() → KeyCounter
 *   → counter.get(AEKey) → long
 * </pre>
 *
 * 【键从哪来（第十九轮②）】目标里存的是一个**字符串身份**（见 {@link AeKeyText}）：
 *   · 物品 → 裸 registry id（`minecraft:iron_ingot`，0.1.2 之前就是这个格式）；
 *   · 流体 / 化学品 / 电力 → `ae:` + AEKey 的 SNBT（`ae:{#t:"ae2:f",id:"minecraft:water"}`）。
 * 后者用 {@code AEKey#fromTagGeneric} 还原成键对象 —— 这样**任何附属模组的键类型**
 * 都能读，本模组不需要认识它们。
 *
 * 【每次采样只走一遍网络】
 * `getAvailableStacks()` 会把网络里所有存储的清单**汇总一遍**（AE 自己的终端也这么干）。
 * 一个探针挂 5 个 AE 目标时，同一 tick 内只算一次，结果按 (MEStorage, gameTime) 缓存 ——
 * 否则 5 个目标会把同一张网络走 5 遍。
 *
 * 【三种语义，与"读不到 / 空格子 / 没在记录"的纪律一致】
 *   · 返回 null  = 设备读不到（不是 AE 设备 / 网络断电 / AE2 未安装 / 反射链断了 /
 *     键串解不出来）→ 上层记 no_data；
 *   · 返回 0    = 网络在线，但**没有这种东西**（craftable 条目也不在 availableStacks 里）
 *     → 这就是"这东西现在是 0"的真值；
 *   · 返回 n    = 网络中这种东西的可用数量（long，交 Val 精确承载）。
 *
 * 【失败策略】AE2 未安装 → 静默降级（记一次日志，之后恒返回 null，不刷屏）；
 * 单次反射异常 → 记一次日志后本次返回 null（下次采样重试 —— 折腾的是形态异常的
 * 第三方方块，不让它毁掉整条采样）。
 */
public final class AeNetworkReader {

    private static boolean aeUnavailable;    // AE2 不在 / 反射链建不起来（只记一次）
    private static boolean loggedFailure;    // 单次反射异常（只记一次）

    // 反射链：只解析一次。以前每次读值都 Class.forName + getMethod 一遍，
    // 5 个 AE 目标 × 20 tick/s 就是每秒几百次类查找（纯浪费）。
    private static boolean chainReady;
    @Nullable
    private static Method mGetNodeHost;
    @Nullable
    private static Method mGetGridNode;
    @Nullable
    private static Method mIsActive;
    @Nullable
    private static Method mGetGrid;
    @Nullable
    private static Method mGetStorageService;
    @Nullable
    private static Method mGetInventory;
    @Nullable
    private static Method mGetAvailableStacks;
    @Nullable
    private static Method mItemKeyOf;
    @Nullable
    private static Method mCounterGet;

    // 同一 tick 内复用同一张网络的汇总结果（见类注释）。
    // 用弱引用：这张 KeyCounter 背后是整张网络的存储与方块实体，
    // 退出存档后若还强引用着，等于把一个世界的对象图钉在内存里（静态字段持有 server 侧对象）。
    @Nullable
    private static WeakReference<Object> cachedStorage;
    private static long cachedTick = Long.MIN_VALUE;
    @Nullable
    private static WeakReference<Object> cachedCounter;

    private AeNetworkReader() {
    }

    /**
     * 读"pos 上的 AE 设备所在网络中，keyId 这种东西的可用数量"。
     *
     * @param keyId 物品 registry id（老格式）或 `ae:` 键串（见 {@link AeKeyText}）
     * @return null = 读不到（记 no_data）；否则 = 数量（可为 0，网络在线就是真值）
     */
    @Nullable
    public static Long read(ServerLevel level, BlockPos pos, String keyId) {
        try {
            if (aeUnavailable) {
                return null;
            }
            if (!chain()) {
                return null;
            }

            // 1) 设备是不是 AE 网络节点宿主
            //    先判设备再解键：断电 / 不是 AE 设备是常态（机器闲置），
            //    而解键（TagParser + codec）比"问一句节点在不在"贵得多。
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                return null;
            }
            Object host = mGetNodeHost.invoke(null, level, pos);
            if (host == null) {
                return null;   // 不是 AE 设备（或方块刚被换掉）
            }

            // 3) 拿节点：遍历 6 个面（见类注释——cable bus 的面暴露陷阱）
            Class<?> hostCls = Class.forName("appeng.api.networking.IInWorldGridNodeHost");
            if (!hostCls.isInstance(host)) {
                return null;
            }
            Object node = null;
            for (Direction dir : Direction.values()) {
                Object n = mGetGridNode.invoke(host, dir);
                if (n != null) {
                    node = n;
                    break;
                }
            }
            if (node == null) {
                return null;   // 节点在所有面都不可见（极端遮挡）→ 读不到
            }

            // 4) 网络是否在供电（isActive 是 IGridNode 的 default 方法，走接口拿更稳）
            if (!(Boolean) mIsActive.invoke(node)) {
                return null;   // 断电 / 网络未成型 → no_data（不是 0）
            }

            // 5) 键对象（物品 / 流体 / 化学品…）—— 网络活着才值得解它
            Object key = resolveKey(keyId);
            if (key == null) {
                return null;   // 注册表里没有 / 键串解不出来 → 读不到（不是 0）
            }

            // 6) grid → storageService → inventory → availableStacks（同 tick 只算一次）
            Object grid = mGetGrid.invoke(node);
            Object storage = mGetStorageService.invoke(grid);
            Object inv = mGetInventory.invoke(storage);
            Object counter = counterFor(inv, level.getGameTime());
            if (counter == null) {
                return null;
            }

            // 7) KeyCounter#get(AEKey) → long
            return (Long) mCounterGet.invoke(counter, key);
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

    /**
     * 键身份 → AE 键对象。
     * 物品走 {@code AEItemKey.of(new ItemStack(item))}（老路，保证旧目标不变）；
     * `ae:` 键串走 {@code AEKey#fromTagGeneric}（新路，覆盖所有键类型）。
     * 1.20.1 的 fromTagGeneric 只吃 CompoundTag（19.x 才要注册表），见 {@link AeKeys}。
     */
    @Nullable
    private static Object resolveKey(String keyId) throws Exception {
        if (AeKeyText.isAe(keyId)) {
            return AeKeys.decodeKey(keyId);
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(new ResourceLocation(keyId));
        if (item.isEmpty()) {
            return null;   // 注册表里已经没有这个物品（模组被移除）→ 读不到
        }
        return mItemKeyOf.invoke(null, new ItemStack(item.get()));
    }

    /**
     * 本 tick 这张网络的汇总清单。同一张网络在同一 tick 内只走一遍
     * （多目标 / 多种探针共用）；换网络或换 tick 就重算。
     */
    @Nullable
    private static Object counterFor(Object storage, long tick) throws Exception {
        if (cachedTick == tick && cachedStorage != null && cachedCounter != null) {
            Object s = cachedStorage.get();
            Object c = cachedCounter.get();
            if (s == storage && c != null) {
                return c;
            }
        }
        Object counter = mGetAvailableStacks.invoke(storage);
        if (counter == null) {
            return null;
        }
        cachedStorage = new WeakReference<>(storage);
        cachedCounter = new WeakReference<>(counter);
        cachedTick = tick;
        return counter;
    }

    /** 解析反射链（只做一次）；失败即视为 AE2 不在 */
    private static boolean chain() {
        if (chainReady) {
            return mGetNodeHost != null;
        }
        chainReady = true;
        try {
            Class<?> gridHelper = Class.forName("appeng.api.networking.GridHelper");
            Class<?> iGridNode = Class.forName("appeng.api.networking.IGridNode");
            Class<?> iGrid = Class.forName("appeng.api.networking.IGrid");
            Class<?> storageService = Class.forName("appeng.api.networking.storage.IStorageService");
            Class<?> meStorage = Class.forName("appeng.api.storage.MEStorage");
            Class<?> aeItemKey = Class.forName("appeng.api.stacks.AEItemKey");
            Class<?> aeKey = Class.forName("appeng.api.stacks.AEKey");
            Class<?> keyCounter = Class.forName("appeng.api.stacks.KeyCounter");

            mGetNodeHost = gridHelper.getMethod("getNodeHost",
                    Class.forName("net.minecraft.world.level.Level"), BlockPos.class);
            mGetGridNode = Class.forName("appeng.api.networking.IInWorldGridNodeHost")
                    .getMethod("getGridNode", Direction.class);
            mIsActive = iGridNode.getMethod("isActive");
            mGetGrid = iGridNode.getMethod("getGrid");
            mGetStorageService = iGrid.getMethod("getStorageService");
            mGetInventory = storageService.getMethod("getInventory");
            mGetAvailableStacks = meStorage.getMethod("getAvailableStacks");
            mItemKeyOf = aeItemKey.getMethod("of", ItemStack.class);
            mCounterGet = keyCounter.getMethod("get", aeKey);
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
     * 菜单是不是 AE 的 ME 存储界面家族（终端 / 合成终端 / 单元仓…，字节码核实过继承链）。
     *
     * 判定方式 = 沿父类链比对**类名**，不用 {@code Class.forName} *   模组环境里每个模组在自己的类加载器里，{@code Class.forName("appeng.…")} 用的是
     *   **调用方**的加载器 —— 在 NeoForge 的分模块加载下不一定能看到 AE2（实测 A1 失败
     *   正是死在这里）。而 {@code menu.getClass()} 是运行时的真实对象，沿
     *   {@code getSuperclass()} 往上走总能拿到 AE2 那个类，比对名字即可 —— 零加载、
     *   零可见性假设。AE2 未安装时菜单类名永远对不上 → false，行为正确。
     */
    public static boolean isAeStorageMenu(@Nullable Object menu) {
        for (Class<?> c = menu == null ? null : menu.getClass(); c != null; c = c.getSuperclass()) {
            if ("appeng.menu.me.common.MEStorageMenu".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
