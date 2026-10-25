package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.RsKeyText;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **精致存储 1.x 的反射层**：把网格界面上看得见的条目读成可勾选的行，
 * 并把 {@code ItemStack} / {@code FluidStack} 编成 LogiScope 的字符串身份。
 *
 * 与 {@link com.jessetech.logiscope.collect.RsNetworkReader} 分工：
 * 那个类负责"这台设备在哪个网络、网络活不活、怎么问数量"；
 * 这个类只管**键的编码**与**客户端界面**。
 *
 * 【反射的边界：只反射 RS，不反射 MC】（与 {@code AeKeys} 同一条铁律）
 * RS 不是编译期依赖，它的类只能反射；而 {@code ItemStack} / {@code FluidStack} /
 * {@code NBTTagCompound} / 注册表这些**原版与加载器类型一律直接调**。
 * 好处在这一版特别明显：RS 1.x 的"键"**就是** {@code ItemStack} / {@code FluidStack}，
 * 所以 {@link #encode} 里一个反射都没有（只有 {@code instanceof}）。
 *
 * 【行从哪来 —— 和 1.21.1 线（RS 2.x）不一样】
 * RS 2.x 把仓库视图挂在**菜单**上（{@code AbstractGridContainerMenu#getRepository}）；
 * RS 1.x 的清单挂在**界面**上：{@code GridScreen#getView()} → {@code IGridView} →
 * {@code getAllStacks()} → {@code List<IGridStack>}。所以本类的入口是
 * {@link #rows(Screen)}（由 {@code ScopeScreen} 把父界面传进来），而不是菜单。
 * 这也是为什么 {@code ContainerAccess#snapshot} 对 RS 网格**必须返回空**：
 * 网格菜单里的槽位是合成格 / 样板格，不是"网络里的东西"。
 *
 * 【连 Class.forName 都不用】
 * 全部从**运行时的真实对象**出发：界面 → 沿父类链比类名找 {@code GridScreen}；条目 →
 * {@code gridStack.getClass()} 上取方法。没有类加载器假设（Forge 下每个模组在
 * 自己的模块加载器里，{@code Class.forName} 用的是调用方的加载器）。
 *
 * 【失败策略】RS 不在 → 界面判不出来，本类其余入口根本不会被调用；
 * 单次反射异常 → 记一次日志后返回空表（界面显示"没有可选的格子"，不会崩）。
 */
public final class RsKeys {

    /** RS 1.6 的网格容器。老包名 com.raoulvdberge，且叫 ContainerGrid */
    private static final String GRID_MENU = "com.raoulvdberge.refinedstorage.container.ContainerGrid";
    /** RS 1.x 网格界面：清单就挂在它身上 */
    private static final String GRID_SCREEN = "com.raoulvdberge.refinedstorage.gui.grid.GuiGrid";

    private static boolean loggedFailure;

    @Nullable
    private static Class<?> screenClass;
    @Nullable
    private static Method mGetView;
    @Nullable
    private static Method mGetAllStacks;
    @Nullable
    private static Method mGetStacks;

    /** 反射句柄缓存（键 = "类名#方法名"）。**必须并发安全**：客户端与服务端两个线程都在用 */
    private static final Map<String, Method> HANDLES = new ConcurrentHashMap<>();

    private RsKeys() {
    }

    /**
     * 一行资源：身份串 + 数量 + 游戏内名字。
     *
     * @param index  RS 清单里的下标（只用于界面序号，不参与身份）
     * @param id     LogiScope 身份串（见 {@link RsKeyText}）
     * @param amount 客户端清单里记的数量
     * @param label  游戏内显示名（RS 自己算好的，如"水" / "铁锭"）
     */
    public static final class Row {
        private final int index;
        private final String id;
        private final long amount;
        private final @Nullable String label;

        public Row(int index, String id, long amount, @Nullable String label) {
            this.index = index;
            this.id = id;
            this.amount = amount;
            this.label = label;
        }

        public int index() {
            return index;
        }

        public String id() {
            return id;
        }

        public long amount() {
            return amount;
        }

        public @Nullable String label() {
            return label;
        }
    }

    // ==================================================================
    // 键 → 身份
    // ==================================================================

    /**
     * RS 的"一种东西" → 字符串身份。
     *
     * | 键 | 身份 |
     * |---|---|
     * | {@code ItemStack}，无 NBT | 裸 registry id（{@code minecraft:iron_ingot}） |
     * | {@code ItemStack}，有 NBT | {@code rs:i:<id>|<SNBT>} |
     * | {@code FluidStack}，无 NBT | {@code rs:f:<id>} |
     * | {@code FluidStack}，有 NBT | {@code rs:f:<id>|<SNBT>} |
     * | 其它 | **null** —— 调用方不要把这一行列出来 |
     *
     * 最后一行是刻意的：给一个"看着像身份、其实读不回来"的串，会让用户勾上一行
     * 永远 no-data 的东西；不列出来至少是诚实的。RS 1.x 只有物品与流体两种。
     */
    @Nullable
    public static String encode(@Nullable Object ingredient) {
        try {
            if (ingredient instanceof ItemStack) {
                ItemStack stack = (ItemStack) ingredient;
                if (stack.isEmpty()) {
                    return null;
                }
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (rl == null) {
                    return null;   // 注册表里没有这个物品 → 编不出身份（采样会记 no_data）
                }
                String nbt = nbtOf(stack.getTagCompound());
                return nbt == null ? RsKeyText.item(rl.toString())
                        : RsKeyText.itemWithNbt(rl.toString(), nbt);
            }
            if (ingredient instanceof FluidStack) {
                FluidStack stack = (FluidStack) ingredient;
                if (stack.getFluid() == null || stack.amount <= 0) {
                    return null;
                }
                ResourceLocation rl = fluidIdOf(stack.getFluid());
                if (rl == null) {
                    return null;
                }
                String nbt = nbtOf(stack.tag);   // 1.12.2 的 FluidStack 用 public tag 字段
                return nbt == null ? RsKeyText.fluid(rl.toString())
                        : RsKeyText.fluidWithNbt(rl.toString(), nbt);
            }
            return null;   // 不认识的类型：不列（见方法注释）
        } catch (Throwable t) {
            warnOnce("encode", t);
            return null;
        }
    }

    /**
     * 流体 → registry id。**本线没有 {@code ForgeRegistries.FLUIDS}**：
     * 1.12.2 的流体注册表是 {@code FluidRegistry}，而它**按字符串名**注册
     * （原版流体就是 {@code water} / {@code lava}，不带命名空间）—— 所以还要补一个
     * {@code minecraft:} 前缀，否则和别的线的身份串对不上。
     */
    @Nullable
    private static ResourceLocation fluidIdOf(@Nullable Fluid fluid) {
        if (fluid == null) {
            return null;
        }
        String name = FluidRegistry.getFluidName(fluid);
        if (name == null || name.isEmpty()) {
            return null;
        }
        return new ResourceLocation(name.indexOf(':') < 0 ? "minecraft:" + name : name);
    }

    /**
     * NBT → 身份载荷。
     *
     * 直接用 {@code toString()}：它是**真实 SNBT**，读取端能用 {@code JsonToNBT}
     * 原样解回来重建 {@code ItemStack} —— 这一点比 1.21.1 线舒服得多（那条线的
     * {@code DataComponentPatch} 没有可靠的反射往返，只能拉全量再比对）。
     * 代价是键顺序来自 HashMap 的桶顺序（见 {@link RsKeyText} 的已知限制）。
     *
     * @return null = 没有 NBT（调用方走裸 id，不挂 {@code |} 尾巴）
     */
    @Nullable
    private static String nbtOf(@Nullable NBTTagCompound tag) {
        if (tag == null || tag.hasNoTags()) {
            return null;
        }
        return tag.toString();
    }

    // ==================================================================
    // 界面 → 行
    // ==================================================================

    /**
     * 这个菜单是不是 RS 的网格界面家族。
     *
     * 判定方式 = 沿父类链比对**类名**（同 {@code AeNetworkReader#isAeStorageMenu} 的理由：
     * 不用 {@code Class.forName}，零加载器假设）。RS 未安装时永远对不上 → false。
     *
     * 注意：**行不是从这个菜单取的**（见类注释），这里只用来决定"要不要进 RS 模式"
     * 以及"菜单槽位要不要全部忽略"。
     */
    public static boolean isRsGridMenu(@Nullable Object menu) {
        if (menu == null) {
            return false;
        }
        for (Class<?> c = menu.getClass(); c != null; c = c.getSuperclass()) {
            if (GRID_MENU.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从**网格界面**取整份清单（RS 1.x 的清单挂在界面上，不在菜单里）。
     *
     * @return null = 这个界面不是 RS 网格（调用方不要动它）；否则是当期清单（可能为空）
     */
    @Nullable
    public static List<Row> rows(@Nullable GuiScreen screen) {
        if (screen == null) {
            return null;
        }
        Object view = viewOf(screen);
        if (view == null) {
            return null;
        }
        try {
            Object raw = allStacks(view);
            if (!(raw instanceof Collection)) {
                return Collections.emptyList();
            }
            Collection<?> collection = (Collection<?>) raw;
            List<Row> out = new ArrayList<Row>(collection.size());
            int i = 0;
            for (Object gridStack : collection) {
                if (gridStack == null) {
                    continue;
                }
                Class<?> cls = gridStack.getClass();
                String id = encode(method(cls, "getIngredient").invoke(gridStack));
                if (id == null) {
                    continue;   // 不认识的类型（见 encode）
                }
                Object name = method(cls, "getName").invoke(gridStack);
                Object qty = method(cls, "getQuantity").invoke(gridStack);
                long amount = qty instanceof Integer ? ((Integer) qty).longValue() : 0L;
                out.add(new Row(i++, id, amount, name instanceof String ? (String) name : null));
            }
            return out;
        } catch (Throwable t) {
            warnOnce("rows", t);
            return Collections.emptyList();
        }
    }

    /** 在界面的父类链上找 {@code GridScreen}，找到就返回它的 {@code getView()} */
    @Nullable
    private static Object viewOf(GuiScreen screen) {
        if (screenClass == null || !screenClass.isInstance(screen)) {
            Class<?> found = null;
            for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
                if (GRID_SCREEN.equals(c.getName())) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            screenClass = found;
            mGetView = null;      // 换了一个实现类 → 重新解析
            mGetAllStacks = null;
            mGetStacks = null;
        }
        try {
            if (mGetView == null) {
                mGetView = screenClass.getMethod("getView");
                Class<?> viewType = mGetView.getReturnType();
                // 清单在 IGridView 上：getAllStacks() 是全量，getStacks() 是"当前视图"。
                // 取全量 —— 用户要的是"整个网络有什么"，不是"这一屏显示什么"。
                mGetAllStacks = tryMethod(viewType, "getAllStacks");
                if (mGetAllStacks == null) {
                    mGetStacks = viewType.getMethod("getStacks");
                }
            }
            return mGetView.invoke(screen);
        } catch (Throwable t) {
            warnOnce("getView", t);
            return null;
        }
    }

    private static Object allStacks(Object view) throws Exception {
        return mGetAllStacks != null ? mGetAllStacks.invoke(view) : mGetStacks.invoke(view);
    }

    @Nullable
    private static Method tryMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
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
