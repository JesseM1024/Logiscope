package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.bind.RsKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * **精致存储（Refined Storage 2）网络读值器**。与 {@link AeNetworkReader} 结构对称：
 * 都是"这台设备所在网络里，这种东西现在有多少"。
 *
 * 【为什么也要特例】RS 的网格界面和 AE 终端是同一个故事 —— 格子不是容器内容，
 * 是网络存储的显示窗口。所以 RS 目标同样不走 NBT / 菜单两条既有路。
 *
 * 【服务端读值链（RS 2.0.9，全部反射，零编译依赖）】
 * <pre>
 * BlockEntity
 *   → instanceof com.refinedmods.refinedstorage.common.api.grid.Grid
 *        （实现类 AbstractGridBlockEntity，字节码核实；按类名沿接口链找，不 forName）
 *   → Grid#isGridActive()          false = 网络没供电 / 没接上 → no_data
 *   → Grid#getItemStorage()        → api.storage.Storage
 *        名字里带 Item，但字节码核实它返回的是
 *        network.getComponent(StorageNetworkComponent.class) —— **所有资源类型的根存储**
 *        （RS 自己的 GridData#of 也走同一个组件），所以流体不用第二次调用。
 *   → StorageView#getAll()         → Collection&lt;ResourceAmount&gt;（copyState 出来的快照）
 *   → 逐条 encode(resourceAmount.resource()) 与目标身份串比对，命中就累加 amount
 * </pre>
 *
 * 【为什么是"拉全量再比对"而不是"按 key 精确查"】
 * {@code RootStorage#get(ResourceKey)} 更快，但它要一个 RS 的资源键**对象**；
 * 而这个对象只能从"物品/流体 + 数据组件补丁"重建 —— 组件补丁在反射层没有可靠的
 * 往返手段（见 {@code RsKeys#fingerprint} 的已知限制）。用**自己的编码**去比对
 * 就把"读值"和"身份"绑在同一条规则上：编得出来就读得到，编不出来那一行根本没被列出。
 *
 * 【每次采样只走一遍网络】
 * 同一 tick 内多个 RS 目标共用同一张网络的清单（按 (storage, gameTime) 缓存）。
 * 缓存的是**编码后的 Map**（身份串 → 数量），不是 RS 的对象集合：
 * 这样比对是字符串比较，且不会把 RS 的内部对象图长期挂住（弱引用同 {@code AeNetworkReader}）。
 *
 * 【三种语义，与"读不到 / 空格子 / 没在记录"的纪律一致】
 *   · 返回 null = 读不到（不是 RS 网格设备 / 网络断电 / RS 未安装 / 反射链断了）
 *     → 上层留空走 no_data；
 *   · 返回 0    = 网络在线，但**没有这种东西**（真值，不是 no_data）；
 *   · 返回 n    = 网络中这种东西的数量（long，交 Val 精确承载）。
 */
public final class RsNetworkReader {

    private static final String GRID = "com.refinedmods.refinedstorage.common.api.grid.Grid";

    private static boolean rsUnavailable;   // RS 不在 / 接口链建不起来（只记一次）
    private static boolean loggedFailure;   // 单次反射异常（只记一次）

    @Nullable
    private static Class<?> gridClass;
    @Nullable
    private static Method mIsGridActive;
    @Nullable
    private static Method mGetItemStorage;
    @Nullable
    private static Method mGetAll;          // StorageView#getAll()
    @Nullable
    private static Method mResource;        // ResourceAmount#resource()
    @Nullable
    private static Method mAmount;          // ResourceAmount#amount()

    // 同一 tick 内复用同一张网络的汇总结果（见类注释）
    @Nullable
    private static WeakReference<Object> cachedStorage;
    private static long cachedTick = Long.MIN_VALUE;
    @Nullable
    private static Map<String, Long> cachedAmounts;

    private RsNetworkReader() {
    }

    /**
     * 读"pos 上的 RS 网格设备所在网络中，keyId 这种东西的数量"。
     *
     * @param keyId 见 {@link com.jessetech.logiscope.analysis.RsKeyText}（裸物品 id 或 {@code rs:} 串）
     * @return null = 读不到（记 no_data）；否则 = 数量（可为 0，网络在线就是真值）
     */
    @Nullable
    public static Long read(ServerLevel level, BlockPos pos, String keyId) {
        try {
            if (rsUnavailable) {
                return null;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !chain(be)) {
                return null;
            }
            if (gridClass == null || !gridClass.isInstance(be)) {
                return null;   // 不是 RS 网格设备（也可能这块地方已经换成别的东西）
            }
            if (!(Boolean) mIsGridActive.invoke(be)) {
                return null;   // 断电 / 网络未接上 → no_data（不是 0）
            }
            Object storage = mGetItemStorage.invoke(be);
            if (storage == null) {
                return null;
            }
            Map<String, Long> amounts = amountsFor(storage, level.getGameTime());
            if (amounts == null) {
                return null;
            }
            Long v = amounts.get(keyId);
            return v == null ? 0L : v;
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LogiScopeMod.LOGGER.warn("[LogiScope] RS network read failed (won't spam)", t);
            }
            return null;
        }
    }

    /**
     * 本 tick 这张网络的汇总清单（身份串 → 数量）。
     * 同一张网络在同一 tick 内只走一遍（多目标 / 多种探针共用）；换网络或换 tick 就重算。
     */
    @Nullable
    private static Map<String, Long> amountsFor(Object storage, long tick) throws Exception {
        if (cachedTick == tick && cachedStorage != null && cachedAmounts != null) {
            Object s = cachedStorage.get();
            if (s == storage) {
                return cachedAmounts;
            }
        }
        Object all = mGetAll.invoke(storage);
        if (!(all instanceof Collection<?> collection)) {
            return null;
        }
        Map<String, Long> map = new HashMap<>();
        for (Object resourceAmount : collection) {
            if (resourceAmount == null) {
                continue;
            }
            if (mResource == null || mAmount == null) {
                // 首次见到 ResourceAmount：从运行时对象拿句柄（record，全表同一个类）
                mResource = resourceAmount.getClass().getMethod("resource");
                mAmount = resourceAmount.getClass().getMethod("amount");
            }
            String id = RsKeys.encode(mResource.invoke(resourceAmount));
            if (id == null) {
                continue;   // 编不出身份的资源类型：既不列也不读（见 RsKeys#encode）
            }
            Object amount = mAmount.invoke(resourceAmount);
            long n = amount instanceof Long l ? l : 0L;
            // 正常情况下 RS 的汇总表里同一个键只有一条；求和是为了"万一"也不丢数量
            map.merge(id, n, Long::sum);
        }
        cachedStorage = new WeakReference<>(storage);
        cachedAmounts = map;
        cachedTick = tick;
        return map;
    }

    /**
     * 解析反射链（只做一次）。
     *
     * 起点是**这个方块实体自己的类**：沿接口链找 {@code Grid}。这样既不 forName，
     * 也不假设加载器 —— RS 的类一定在 RS 对象的类加载器里。
     */
    private static boolean chain(BlockEntity be) {
        if (gridClass != null && mGetAll != null) {
            return true;
        }
        Class<?> grid = interfaceNamed(be.getClass(), GRID);
        if (grid == null) {
            return false;   // 这块地方不是 RS 网格设备：常态，不该记日志
        }
        try {
            gridClass = grid;
            mIsGridActive = grid.getMethod("isGridActive");
            mGetItemStorage = grid.getMethod("getItemStorage");
            Class<?> storage = mGetItemStorage.getReturnType();
            mGetAll = storage.getMethod("getAll");
            return true;
        } catch (Throwable t) {
            rsUnavailable = true;
            gridClass = null;
            LogiScopeMod.LOGGER.warn("[LogiScope] RS API chain not resolvable (targets read as no-data)", t);
            return false;
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
