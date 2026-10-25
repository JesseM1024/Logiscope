package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.RsKeyText;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **精致存储（Refined Storage 2）的反射层**：把它的"一种东西"变成 LogiScope 的字符串身份，
 * 并把网格界面里的资源列表读成可勾选的行。
 *
 * 与 {@link com.jessetech.logiscope.collect.RsNetworkReader} 分工：
 * 那个类负责"这台设备在哪个网络、网络活不活、怎么把整张网络的清单拉出来"；
 * 这个类只管**键本身**与**客户端菜单**（编码 / 菜单里的行）。
 *
 * 【反射的边界：只反射 RS，不反射 MC】（与 {@code AeKeys} 同一条铁律）
 * RS 不是编译期依赖，它的类只能反射；而 {@code Item} / {@code Fluid} /
 * {@code DataComponentPatch} / 注册表这些**原版类型一律直接调** ——
 * 它们的对象是反射拿到的，但方法调用本身走编译器，生产环境的名字不会错。
 *
 * 【一个 Class.forName 都没有】
 * RS 的资源键是运行时的真实对象，所以本类全部从**对象自己的 Class** 出发：
 *   · 菜单 → 沿父类链比类名找 {@code AbstractGridContainerMenu}（同
 *     {@code AeNetworkReader#isAeStorageMenu} 的理由：零加载器假设）；
 *   · 资源键 → 读 {@code getClass().getName()} 分辨 {@code ItemResource} / {@code FluidResource}；
 *   · 其余方法句柄 → {@code obj.getClass().getMethod(...)}，按 "类名#方法名" 缓存。
 * NeoForge 下每个模组在自己的模块加载器里，{@code Class.forName("com.refinedmods.…")}
 * 用的是**调用方**的加载器，不一定看得见 RS —— 所以这条路是刻意避开的。
 *
 * 【失败策略】RS 不在 → 菜单判不出来（false），其余入口根本不会被调用；
 * 单次反射异常 → 记一次日志后按"这一行读不到"处理，绝不编一个身份出来。
 */
public final class RsKeys {

    /** RS 网格菜单家族的根类（终端 / 合成终端 / 便携网格…，字节码核实过继承链） */
    private static final String GRID_MENU = "com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu";
    /** RS 的两种资源键实现。其它类型（附属模组自定义的）一律不列 —— 见 {@link #encode} */
    private static final String ITEM_RESOURCE = "com.refinedmods.refinedstorage.common.support.resource.ItemResource";
    private static final String FLUID_RESOURCE = "com.refinedmods.refinedstorage.common.support.resource.FluidResource";
    /** RS 的资源键根接口（只用于取 {@code getAmount} 的形参类型） */
    private static final String RESOURCE_KEY = "com.refinedmods.refinedstorage.api.resource.ResourceKey";

    private static boolean loggedFailure;

    // 菜单那一侧：菜单类 → getRepository → 仓库接口 → getViewList
    @Nullable
    private static Class<?> menuClass;
    @Nullable
    private static Method mGetRepository;
    @Nullable
    private static Method mGetViewList;

    /**
     * 反射句柄缓存（键 = "类名#方法名"）。RS 的资源键只有两个实现类，表极小。
     *
     * **必须是并发安全的**：本类两边都在用 —— 客户端读界面行（渲染线程）、
     * 服务端读网络存量（服务端 tick 线程，单人游戏里是另一个线程）。
     */
    private static final Map<String, Method> HANDLES = new ConcurrentHashMap<>();

    private RsKeys() {
    }

    // ==================================================================
    // 键 → 身份
    // ==================================================================

    /**
     * 一行资源：身份串 + 数量 + 游戏内名字。
     *
     * @param index  RS 视图里的下标（只用于界面序号，不参与身份）
     * @param id     LogiScope 身份串（见 {@link RsKeyText}）
     * @param amount 客户端仓库里记的数量
     * @param label  游戏内显示名（RS 自己算好的，如"水" / "铁锭"）
     */
    public record Row(int index, String id, long amount, String label) {
    }

    /**
     * RS 资源键 → 字符串身份。
     *
     * | 键 | 身份 |
     * |---|---|
     * | {@code ItemResource}，空组件 | 裸 registry id（{@code minecraft:iron_ingot}） |
     * | {@code ItemResource}，有组件 | {@code rs:i:<id>|<组件指纹>} |
     * | {@code FluidResource}，空组件 | {@code rs:f:<id>} |
     * | {@code FluidResource}，有组件 | {@code rs:f:<id>|<组件指纹>} |
     * | 其它（附属模组自定义资源类型） | **null** —— 调用方不要把这一行列出来 |
     *
     * 最后一行是刻意的：给一个"看着像身份、其实读不回来"的串，会让用户勾上一行
     * 永远 no-data 的东西；不列出来至少是诚实的。RS 2.0 只有物品与流体两种。
     */
    @Nullable
    public static String encode(@Nullable Object resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        try {
            String cls = resourceKey.getClass().getName();
            if (ITEM_RESOURCE.equals(cls)) {
                Item item = (Item) method(resourceKey.getClass(), "item").invoke(resourceKey);
                ResourceLocation rl = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
                if (rl == null) {
                    return null;   // 注册表里没有这个物品 → 编不出身份（采样会记 no_data）
                }
                String fp = fingerprint(components(resourceKey));
                return fp == null ? RsKeyText.item(rl.toString())
                        : RsKeyText.itemWithComponents(rl.toString(), fp);
            }
            if (FLUID_RESOURCE.equals(cls)) {
                Fluid fluid = (Fluid) method(resourceKey.getClass(), "fluid").invoke(resourceKey);
                ResourceLocation rl = fluid == null ? null : BuiltInRegistries.FLUID.getKey(fluid);
                if (rl == null) {
                    return null;
                }
                String fp = fingerprint(components(resourceKey));
                return fp == null ? RsKeyText.fluid(rl.toString())
                        : RsKeyText.fluidWithComponents(rl.toString(), fp);
            }
            return null;   // 不认识的资源类型：不列（见方法注释）
        } catch (Throwable t) {
            warnOnce("encode", t);
            return null;
        }
    }

    @Nullable
    private static DataComponentPatch components(Object resourceKey) {
        try {
            Object v = method(resourceKey.getClass(), "components").invoke(resourceKey);
            return v instanceof DataComponentPatch p ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 组件指纹：**排序后**的 {@code 组件类型id=值} 拼接。
     *
     * 【为什么要排序】{@code DataComponentPatch.toString()} 按**插入顺序**打印
     * （字节码核实：{@code Reference2ObjectMaps.fastIterable}），两个相等的补丁可能
     * 打出不同的字符串 —— 那样的指纹会让同一条曲线每 tick 换一个身份。
     *
     * 【为什么要指纹】RS 自己就按组件区分资源（{@code ItemResource.equals} 比 item + 组件）。
     * 塌成一个身份 = 两瓶不同的药水合成一行，违反"同名不同物"。
     *
     * 【已知限制】{@code toString()} 不是 Mojang 承诺的稳定序列化格式。同一 MC 版本内
     * "同一个补丁对象算出来的字符串恒定"（采样必需的性质成立），但跨版本 / 两个模组
     * 手写的 toString 有可能撞车 —— 撞车的后果是两行合并成一行（数量相加），
     * **不会**读出一个假值。这个降级方向是可接受的。
     *
     * @return null = 没有组件（调用方走裸 id，不挂 `|` 尾巴）
     */
    @Nullable
    private static String fingerprint(@Nullable DataComponentPatch patch) {
        if (patch == null || patch.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<DataComponentType<?>, Optional<?>> e : patch.entrySet()) {
            ResourceLocation key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(e.getKey());
            String name = key == null ? "?" : key.toString();
            // 空 Optional = "这个组件被显式删掉了"，与"没有这个组件"是两回事，所以给个哨兵
            String value = e.getValue().isPresent() ? String.valueOf(e.getValue().get()) : "!";
            parts.add(name + '=' + value);
        }
        Collections.sort(parts);
        return "{" + String.join(",", parts) + "}";
    }

    // ==================================================================
    // 菜单 → 行
    // ==================================================================

    /**
     * 这个菜单是不是 RS 的网格界面家族。
     *
     * 判定方式 = 沿父类链比对**类名**（同 {@code AeNetworkReader#isAeStorageMenu} 的理由：
     * 不用 {@code Class.forName}，零加载器假设）。RS 未安装时永远对不上 → false。
     */
    public static boolean isRsGridMenu(@Nullable Object menu) {
        if (menu == null) {
            return false;
        }
        if (menuClass != null && menuClass.isInstance(menu)) {
            return true;
        }
        for (Class<?> c = menu.getClass(); c != null; c = c.getSuperclass()) {
            if (GRID_MENU.equals(c.getName())) {
                menuClass = c;
                return true;
            }
        }
        return false;
    }

    /**
     * RS 网格界面里**客户端已知的全部资源行**（RS 自己筛过 / 排过序的那一份视图）。
     *
     * 为什么用仓库而不是菜单槽位：RS 2.0 的网格不是"一格一个 Slot"的容器 ——
     * 界面按仓库视图逐格画（{@code AbstractGridScreen#renderCell}），所以"用户看到的
     * 条目"就等于 {@code getRepository().getViewList()}。这也顺带解决了翻页问题：
     * AE 那条路只能选到当前页的格子，RS 这条路能选到全部。
     *
     * @return null = 不是 RS 网格菜单（调用方不要动它）；否则是当期视图（可能为空）
     */
    @Nullable
    public static List<Row> rows(@Nullable Object menu) {
        if (!isRsGridMenu(menu)) {
            return null;
        }
        try {
            if (!menuChain()) {
                return List.of();
            }
            Object repo = mGetRepository.invoke(menu);
            if (repo == null) {
                return List.of();
            }
            Object view = mGetViewList.invoke(repo);
            if (!(view instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<Row> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object gridResource = list.get(i);
                Class<?> grClass = gridResource.getClass();
                Object key = method(grClass, "getResourceForRecipeMods").invoke(gridResource);
                String id = encode(key);
                if (id == null) {
                    continue;   // 不认识的资源类型（见 encode）
                }
                Method amountOf = amountMethod(key);
                if (amountOf == null) {
                    continue;
                }
                String name = (String) method(grClass, "getName").invoke(gridResource);
                Object amount = amountOf.invoke(repo, key);
                out.add(new Row(i, id, amount instanceof Long l ? l : 0L, name));
            }
            return out;
        } catch (Throwable t) {
            warnOnce("rows", t);
            return List.of();
        }
    }

    /** 菜单侧方法句柄（只解析一次；RS 不在时 isRsGridMenu 已挡在前面） */
    private static boolean menuChain() {
        if (mGetRepository != null) {
            return true;
        }
        if (menuClass == null) {
            return false;
        }
        try {
            mGetRepository = menuClass.getMethod("getRepository");
            mGetViewList = mGetRepository.getReturnType().getMethod("getViewList");
            return true;
        } catch (Throwable t) {
            warnOnce("menuChain", t);
            mGetRepository = null;
            return false;
        }
    }

    /**
     * {@code ResourceRepository#getAmount(ResourceKey)} 的句柄。
     * 形参类型从**运行时资源键**的接口链里找（{@link #RESOURCE_KEY}）——
     * 这样不必写死 {@code ResourceRepository} 的完整签名。
     */
    @Nullable
    private static Method amountMethod(Object key) {
        Class<?> repo = mGetRepository == null ? null : mGetRepository.getReturnType();
        if (repo == null) {
            return null;
        }
        String cacheKey = repo.getName() + "#getAmount";
        Method cached = HANDLES.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Class<?> resourceKey = interfaceNamed(key.getClass(), RESOURCE_KEY);
        if (resourceKey == null) {
            return null;
        }
        try {
            Method m = repo.getMethod("getAmount", resourceKey);
            HANDLES.put(cacheKey, m);
            return m;
        } catch (Throwable t) {
            warnOnce("getAmount", t);
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

    // ==================================================================
    // 杂项
    // ==================================================================

    /** 取（并按 "类名#方法名" 缓存）某个运行时对象上的公开访问器 */
    private static Method method(Class<?> owner, String name) throws NoSuchMethodException {
        String cacheKey = owner.getName() + '#' + name;
        Method m = HANDLES.get(cacheKey);
        if (m == null) {
            m = owner.getMethod(name);
            HANDLES.put(cacheKey, m);
        }
        return m;
    }

    private static void warnOnce(String what, Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            LogiScopeMod.LOGGER.warn("[LogiScope] RS {} failed (won't spam): {}", what, t.toString());
        }
    }
}
