package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * **AE2 网络读值器**（第十六轮⑧，用户点名的特例覆盖）。
 *
 * 【为什么是特例】AE 终端的"格子"不是容器内容 —— 它是**网络存储列表的显示窗口**
 * （客户端从同步包拿到的 repo），点击它选中的是"一个物品"，不是"一个槽位"。
 * 所以 AE 目标不能走 NBT / 菜单两条既有路，读值语义是：
 * <blockquote>"这台 AE 设备所在的网络里，这种物品现在有多少"</blockquote>
 *
 * 【服务端读值链（AE2 19.2.x，全部反射，零编译依赖 —— 本项目刻意不引入第三方 jar）】
 * <pre>
 * BlockEntity
 *   → GridHelper.getNodeHost(level, pos)        → IInWorldGridNodeHost（null = 不是 AE 设备）
 *   → getGridNode(dir) 遍历 6 个面取第一个非 null
 *       ★ 不能只试一个方向：CableBusBlockEntity（终端挂在电缆上）的 default 实现
 *         会检查 isExposedOnSide(dir) —— 节点被其它部件遮住的面返回 null。
 *       ★ 也不能用 getActionableNode()：CableBus 不实现 IActionHost（字节码核实过）。
 *   → node.isActive()（false = 网络没供电 / 未成型 → no_data）
 *   → node.getGrid() → IGrid.getStorageService() → IStorageService.getInventory()
 *   → MEStorage.getAvailableStacks() → KeyCounter
 *   → AEItemKey.of(物品堆) → counter.get(key) → long
 * </pre>
 *
 * 【三种语义，与"读不到 / 空格子 / 没在记录"的纪律一致】
 *   · 返回 null  = 设备读不到（不是 AE 设备 / 网络断电 / AE2 未安装 / 反射链断了）
 *     → 上层记 no_data；
 *   · 返回 0    = 网络在线，但**没有这种物品**（craftable 条目也不在 availableStacks 里）
 *     → 这就是"这物品现在是 0 个"的真值；
 *   · 返回 n    = 网络中该物品的可用数量（long，交 Val 精确承载）。
 *
 * 【失败策略】AE2 未安装 → 静默降级（记一次日志，之后恒返回 null，不刷屏）；
 * 单次反射异常 → 记一次日志后本次返回 null（下次采样重试 —— 折腾的是形态异常的
 * 第三方方块，不让它毁掉整条采样）。
 */
public final class AeNetworkReader {

    private static boolean aeUnavailable;    // AE2 不在（只记一次）
    private static boolean loggedFailure;    // 反射链异常（只记一次）

    private AeNetworkReader() {
    }

    /**
     * 读"pos 上的 AE 设备所在网络中，itemId 的可用数量"。
     *
     * @return null = 读不到（记 no_data）；否则 = 数量（可为 0，网络在线就是真值）
     */
    @Nullable
    public static Long read(ServerLevel level, BlockPos pos, String itemId) {
        try {
            if (aeUnavailable) {
                return null;
            }
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(new ResourceLocation(itemId));
            if (item.isEmpty()) {
                return null;   // 注册表里已经没有这个物品（模组被移除）→ 读不到
            }

            // 1) 设备是不是 AE 网络节点宿主
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                return null;
            }
            Class<?> gridHelper = Class.forName("appeng.api.networking.GridHelper");
            Method getNodeHost = gridHelper.getMethod("getNodeHost",
                    Class.forName("net.minecraft.world.level.Level"), BlockPos.class);
            Object host = getNodeHost.invoke(null, level, pos);
            if (host == null) {
                return null;   // 不是 AE 设备（或方块刚被换掉）
            }

            // 2) 拿节点：遍历 6 个面（见类注释——cable bus 的面暴露陷阱）
            Class<?> hostCls = Class.forName("appeng.api.networking.IInWorldGridNodeHost");
            if (!hostCls.isInstance(host)) {
                return null;
            }
            Method getGridNode = hostCls.getMethod("getGridNode", Direction.class);
            Object node = null;
            for (Direction dir : Direction.values()) {
                Object n = getGridNode.invoke(host, dir);
                if (n != null) {
                    node = n;
                    break;
                }
            }
            if (node == null) {
                return null;   // 节点在所有面都不可见（极端遮挡）→ 读不到
            }

            // 3) 网络是否在供电（isActive 是 IGridNode 的 default 方法，走接口拿更稳）
            Method isActive = Class.forName("appeng.api.networking.IGridNode").getMethod("isActive");
            if (!(Boolean) isActive.invoke(node)) {
                return null;   // 断电 / 网络未成型 → no_data（不是 0）
            }

            // 4) grid → storageService → inventory → availableStacks
            Method getGrid = Class.forName("appeng.api.networking.IGridNode").getMethod("getGrid");
            Object grid = getGrid.invoke(node);
            Method getStorageService = Class.forName("appeng.api.networking.IGrid")
                    .getMethod("getStorageService");
            Object storage = getStorageService.invoke(grid);
            Method getInventory = Class.forName("appeng.api.networking.storage.IStorageService")
                    .getMethod("getInventory");
            Object inv = getInventory.invoke(storage);
            Method getAvailableStacks = Class.forName("appeng.api.storage.MEStorage")
                    .getMethod("getAvailableStacks");
            Object counter = getAvailableStacks.invoke(inv);

            // 5) AEItemKey.of(stack) → KeyCounter.get(key)
            Class<?> aeItemKey = Class.forName("appeng.api.stacks.AEItemKey");
            Method of = aeItemKey.getMethod("of", ItemStack.class);
            Object key = of.invoke(null, new ItemStack(item.get()));
            Method get = Class.forName("appeng.api.stacks.KeyCounter").getMethod("get",
                    Class.forName("appeng.api.stacks.AEKey"));
            return (Long) get.invoke(counter, key);
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
     * 菜单是不是 AE 的 ME 存储界面家族（终端 / 合成终端 / 单元仓…，字节码核实过继承链）。
     *
     * ★ 判定方式 = 沿父类链比对**类名**，不用 {@code Class.forName} ★
     *   模组环境里每个模组在自己的类加载器里，{@code Class.forName("appeng.…")} 用的是
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
