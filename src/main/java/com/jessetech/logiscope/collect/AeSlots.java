package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 采集层：**AE 终端槽位的真值通道**（AE2 rv6，纯反射，不引入编译期依赖）。
 *
 * 【为什么需要它】
 * vanilla 的 `Slot#getStack()` 对 AE 终端给出的东西是**假的**：
 *
 * | 界面 | `getStack()` 返回 | 真值在哪 |
 * |---|---|---|
 * | 物品终端 | 那个物品，**数量恒 1** | `SlotME#getAEStack()` → `IAEStack#getStackSize()` |
 * | 流体终端 | **空**（流体不是物品） | `SlotFluidME#getAEFluidStack()` |
 *
 * 实测（1.12.2 首轮 AE 验证，2026-10-22）：物品终端里 64 个圆石显示成 `x1`；
 * 流体终端里 **一条都列不出来**（"连通用包裹堆栈都不显示"——rv6 没有那个占位物品，
 * 那套 `ae2:wrapped_generic_stack` 是 AE2 10 才有的）。
 *
 * 两条接口都是 **public**（`javap` 核实，rv6-stable-7）：
 * <pre>
 * appeng.client.me.SlotME#getAEStack()          → IAEItemStack
 * appeng.client.me.SlotFluidME#getAEFluidStack() → IAEFluidStack
 * IAEStack#getStackSize() → long
 * IAEItemStack#createItemStack() → ItemStack
 * IAEFluidStack#getFluidStack() → FluidStack
 * </pre>
 *
 * 【为什么是反射】
 * 本线不把 AE2 当作编译期依赖（用户最初的要求是"本轮不做 AE"），
 * 而 AE2 又是**可选**的（没装 AE 的整合包必须照常工作）。
 * 反射 + 一次初始化，没装 AE 时全部退化成 null，零成本。
 *
 * ★ 取 Class 一律用 `Class.forName(name, false, loader)`：
 * 单参版本会在解析那一刻就跑 `<clinit>`，AE 的静态初始化会建注册表/挂总线 ——
 * 离线探针里这就是当场炸的原因（见 PORT-1.12.2 §五 与本项目 MEMORY）。
 */
public final class AeSlots {

    /** AE 的物品槽（终端里那一块网格）；子类也认 */
    private static final String CLS_SLOT_ME = "appeng.client.me.SlotME";
    /** AE 的流体槽 */
    private static final String CLS_SLOT_FLUID_ME = "appeng.client.me.SlotFluidME";

    /** 流体这一侧合成的身份前缀：流体没有 registry id，只能拿流体名当键 */
    public static final String FLUID_ID_PREFIX = "ae:fluid:";

    private static boolean initTried;
    private static boolean available;

    private static Class<?> slotMeClass;
    private static Class<?> slotFluidMeClass;
    private static Method mGetAeStack;
    private static Method mGetStackSize;
    private static Method mCreateItemStack;
    private static Method mGetAeFluidStack;
    private static Method mGetFluidStack;

    private AeSlots() {
    }

    /** AE 是否在位（只用于日志与界面提示，不参与逻辑判定） */
    public static boolean available() {
        init();
        return available;
    }

    private static synchronized void init() {
        if (initTried) {
            return;
        }
        initTried = true;
        try {
            ClassLoader cl = AeSlots.class.getClassLoader();
            slotMeClass = tryLoad(CLS_SLOT_ME, cl);
            slotFluidMeClass = tryLoad(CLS_SLOT_FLUID_ME, cl);
            if (slotMeClass != null) {
                mGetAeStack = slotMeClass.getMethod("getAEStack");
                Class<?> iaeStack = mGetAeStack.getReturnType();
                mGetStackSize = iaeStack.getMethod("getStackSize");
                Class<?> iaeItem = Class.forName("appeng.api.storage.data.IAEItemStack", false, cl);
                mCreateItemStack = iaeItem.getMethod("createItemStack");
            }
            if (slotFluidMeClass != null) {
                mGetAeFluidStack = slotFluidMeClass.getMethod("getAEFluidStack");
                Class<?> iaeFluid = mGetAeFluidStack.getReturnType();
                mGetFluidStack = iaeFluid.getMethod("getFluidStack");
                if (mGetStackSize == null) {
                    mGetStackSize = iaeFluid.getMethod("getStackSize");
                }
            }
            available = mGetAeStack != null || mGetAeFluidStack != null;
        } catch (Throwable t) {
            // 没装 AE / 版本不对：静默退化，不当成错误（AE 是可选依赖）
            LogiScopeMod.LOGGER.debug("[LogiScope] AE slot API not usable: {}", t.toString());
            slotMeClass = null;
            slotFluidMeClass = null;
            available = false;
        }
        if (available) {
            LogiScopeMod.LOGGER.info("[LogiScope] AE 槽位读值已启用（物品={} 流体={}）",
                    mGetAeStack != null, mGetAeFluidStack != null);
        }
    }

    @Nullable
    private static Class<?> tryLoad(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 这个槽位是不是 AE 终端槽；是的话返回**真实身份 + 真值**，不是返回 null。
     *
     * 返回 null 的两类情况（都会让调用方退回 `Slot#getStack()` 的普通路径）：
     *   · 根本不是 AE 槽（普通容器 / 玩家背包）
     *   · 是 AE 槽但这一格是空的（`getAEStack()` 为 null）—— 这时普通路径给出的"空"是对的
     *
     * @param menuSlotIndex 菜单槽下标（界面上那一格的位置，与平台无关）
     */
    @Nullable
    public static ContainerAccess.SlotView view(@Nullable Slot slot, int menuSlotIndex) {
        if (slot == null) {
            return null;
        }
        init();
        if (!available) {
            return null;
        }
        try {
            if (slotMeClass != null && slotMeClass.isInstance(slot)) {
                return itemView(slot, menuSlotIndex);
            }
            if (slotFluidMeClass != null && slotFluidMeClass.isInstance(slot)) {
                return fluidView(slot, menuSlotIndex);
            }
        } catch (Throwable t) {
            // 单个槽位读失败（AE 正在重排 / 网络掉电）→ 当作"这格读不到"，不抛穿渲染
            LogiScopeMod.LOGGER.debug("[LogiScope] AE slot read failed: {}", t.toString());
        }
        return null;
    }

    @Nullable
    private static ContainerAccess.SlotView itemView(Slot slot, int menuSlotIndex) throws Exception {
        Object aeStack = mGetAeStack.invoke(slot);
        if (aeStack == null) {
            return null;   // 空格子：让普通路径给出"空"
        }
        long size = toLong(mGetStackSize.invoke(aeStack));
        ItemStack def = (ItemStack) mCreateItemStack.invoke(aeStack);
        if (def == null || def.isEmpty()) {
            return null;
        }
        String id = ContainerAccess.itemIdOf(def);
        // label 传 null：**身份和显示都用 registry id**，与普通槽位完全一致 ——
        // 这样"同一个物品从终端里拿出来"不会变成两条线，选格页的筛选框
        // 也仍然按 id 匹配（换成中文显示名之后敲 `iron` 就搜不到了）。
        return new ContainerAccess.SlotView(menuSlotIndex, slot.getSlotIndex(), id, size, false);
    }

    @Nullable
    private static ContainerAccess.SlotView fluidView(Slot slot, int menuSlotIndex) throws Exception {
        Object aeFluid = mGetAeFluidStack.invoke(slot);
        if (aeFluid == null) {
            return null;   // 空格子
        }
        long size = toLong(mGetStackSize.invoke(aeFluid));
        Object fsObj = mGetFluidStack.invoke(aeFluid);
        if (!(fsObj instanceof FluidStack)) {
            return null;
        }
        FluidStack fs = (FluidStack) fsObj;
        if (fs.getFluid() == null) {
            return null;
        }
        // 流体没有 registry id ⇒ 身份用 `ae:fluid:<流体名>`（流体名是稳定的注册名）
        String id = FLUID_ID_PREFIX + fs.getFluid().getName();
        String label = fs.getLocalizedName();
        return new ContainerAccess.SlotView(menuSlotIndex, slot.getSlotIndex(), id, size, false, label);
    }

    /** 反射拿到的整数可能是 Integer / Long / Short —— 统一成 long，别在这里截断 */
    private static long toLong(@Nullable Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** 这个 identity 是不是流体（界面用它决定要不要去找物品图标） */
    public static boolean isFluidId(@Nullable String itemId) {
        return itemId != null && itemId.startsWith(FLUID_ID_PREFIX);
    }
}
