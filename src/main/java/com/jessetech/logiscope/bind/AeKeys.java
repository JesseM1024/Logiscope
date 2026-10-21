package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.AeKeyText;
import net.minecraft.fluid.Fluid;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * **AE 键的反射层**（1.16.5 线 / AE2 8.4.x）：把网络里的"一种东西"变成 LogiScope 的字符串身份。
 *
 * 与 {@link com.jessetech.logiscope.collect.AeNetworkReader} 分工：
 * 那个类负责"这台设备在哪个网络、网络活不活、从哪拿清单"；
 * 这个类只管**键本身**（终端显示格的存量 / 身份 / 显示名）。
 *
 * 【这一代与 15.x / 19.x 的根本差别（逐字节码核实过，勿照抄高版本）】
 * <pre>
 * 15.x / 19.x                          8.x（本分支）
 * ---------------------------------------------------------------------------------
 * AEKey + toTagGeneric/fromTagGeneric  没有 AEKey 这套体系
 * GenericStack（挂在数据组件里）        IAEItemStack / IAEFluidStack 两套并行
 * MEStorage.getAvailableStacks()       IStorageGrid.getInventory(通道).getAvailableItems()
 * KeyCounter                            IItemList（一遍遍历里自己比对）
 * RepoSlot.getStoredAmount()           RepoSlot.getStoredAmount()  ← 这一条意外地对上了
 * </pre>
 * 所以这里没有 encode/decode（没有 SNBT 可编），只有两件实实在在的事：
 *   1. **终端显示格**（{@code RepoSlot}）的存量与"条目在不在"；
 *   2. **显示格背后的那个 AE 栈**是物品还是流体 → 身份串 + 游戏内名字。
 *
 * 【为什么流体要自己编一个身份串】8.x 的流体终端把流体渲染成一个**占位物品**
 * （{@code ae2:fluid_dummy} + 流体 NBT），直接拿物品 id 当身份会让**所有流体塌成同一个 id**
 * （水、岩浆、原油全是 {@code ae2:fluid_dummy}）—— 与 19.x 的
 * {@code wrapped_generic_stack} 是同一类陷阱。这里改成编出与高版本**同形状**的键串：
 * <pre>ae:{#t:"ae2:f",id:"minecraft:water"}</pre>
 * 好处：四条版本线的身份串形状一致（{@link AeKeyText} 不用分叉），
 * 而且这个串天生带着流体自己的命名空间，反查注册表时按 {@link AeKeyText#idOf} 取回即可。
 *
 * 【反射的边界：只反射 AE，不反射 MC】（2026-10-21 实机踩坑后定的铁律）
 *   · AE2 不是编译期依赖 → 它的类只能反射；
 *   · **原版 / Forge 的方法一律直接调**。生产运行时是 SRG 命名，按名字反射 MC 方法
 *     dev 能过、装进实例必扑空（{@code TagParser#parseTag} = {@code m_129359_}）。
 *     所以 {@code FluidStack#getFluid}、{@code ForgeRegistries.FLUIDS} 这些都是直接调。
 *
 * 【失败策略】AE2 不在 → 全部返回 null/false（上层自然退化成"不是 AE 格子"）；
 * 单次反射异常 → 记一次日志后按"读不到"处理，绝不猜一个值出来。
 */
public final class AeKeys {

    /**
     * 流体键的**类型 id**。AE2 8.x 自己并没有这个概念（{@code ae2:f} 是 15.x 之后
     * {@code AEKeyType} 的命名），这里刻意沿用高版本的字样，
     * 目的是让四条版本线产出的身份串**长得一样**，归档 / 文档 / 排错时不用换脑子。
     */
    public static final String FLUID_TYPE = "ae2:f";

    private static boolean initDone;
    private static boolean available;

    /** 终端显示格（{@code appeng.client.gui.me.items.RepoSlot}），按名字沿父类链找 */
    @Nullable
    private static Class<?> repoSlotClass;
    /** {@code RepoSlot#getStoredAmount()}：真实存量（格子里的 ItemStack 数量恒为 1，它只是图标） */
    @Nullable
    private static Method repoStoredAmount;
    /**
     * {@code RepoSlot#getEntry()}：网络列表条目（null = 这一格现在没有条目）。
     * 不用 {@code Slot#hasItem()} —— 那是原版方法，生产环境是 SRG 名，反射必扑空（见类注释）。
     */
    @Nullable
    private static Method repoGetEntry;
    /** {@code GridInventoryEntry#getStack()}：条目背后的 AE 栈（物品 / 流体） */
    @Nullable
    private static Method entryGetStack;

    /** {@code appeng.api.storage.data.IAEFluidStack}：判断"这个栈是流体还是物品" */
    @Nullable
    private static Class<?> fluidStackIface;
    /** {@code IAEFluidStack#getFluidStack()} → Forge 的 {@code FluidStack} */
    @Nullable
    private static Method getFluidStack;
    /** {@code IAEItemStack#getDefinition()} → 原版 {@code ItemStack} */
    @Nullable
    private static Method getDefinition;

    private static boolean loggedFailure;

    private AeKeys() {
    }

    // ==================================================================
    // 终端显示格（RepoSlot）
    // ==================================================================

    /**
     * 这个菜单槽是不是 AE 终端的"网络列表显示格"。
     *
     * 8.x 与 19.x 在这一点上出乎意料地一致：终端界面 init 时会把
     * {@code RepoSlot} **塞进菜单的 inventorySlots**，所以"读菜单 = 读网络列表"这条路成立，
     * 选格页不用另开一条数据源。判据仍是沿父类链比类名（零加载假设，见
     * {@code AeNetworkReader#isAeStorageMenu} 的长注释）。
     */
    public static boolean isRepoSlot(@Nullable Object slot) {
        if (slot == null) {
            return false;
        }
        if (repoSlotClass != null && repoSlotClass.isInstance(slot)) {
            return true;
        }
        for (Class<?> c = slot.getClass(); c != null; c = c.getSuperclass()) {
            if ("appeng.client.gui.me.items.RepoSlot".equals(c.getName())) {
                repoSlotClass = c;
                return true;
            }
        }
        return false;
    }

    /**
     * 显示格背后**真正的存量**（long）。
     *
     * @return null = 不是 AE 显示格（调用方按普通槽位读栈数量）
     */
    @Nullable
    public static Long repoAmount(@Nullable Object slot) {
        if (!isRepoSlot(slot) || !init()) {
            return null;
        }
        try {
            if (repoStoredAmount == null) {
                repoStoredAmount = repoSlotClass.getMethod("getStoredAmount");
            }
            Object v = repoStoredAmount.invoke(slot);
            return v instanceof Long ? (Long) v : null;
        } catch (Throwable t) {
            warnOnce("repoAmount", t);
            return null;
        }
    }

    /**
     * 显示格上"有没有这个条目"。
     *
     * 与 {@code getStoredAmount() == 0} **不是一回事**：可合成但没存货的条目存量就是 0，
     * 但它是网络里真实存在的一种东西（监测它就是"现在 0 个"这个真值），不能用数量判空。
     */
    public static boolean repoHasEntry(@Nullable Object slot) {
        if (!isRepoSlot(slot) || !init()) {
            return false;
        }
        try {
            if (repoGetEntry == null) {
                repoGetEntry = repoSlotClass.getMethod("getEntry");
            }
            return repoGetEntry.invoke(slot) != null;
        } catch (Throwable t) {
            // 拿不到 getEntry：退回"存量不是 0"（宁可把 0 存量当空，也不要虚报有条目）
            Long amount = repoAmount(slot);
            return amount != null && amount > 0;
        }
    }

    // ==================================================================
    // 显示格 → 身份串 + 显示名
    // ==================================================================

    /** 一行 AE 显示格的身份：{@code keyId} 是 LogiScope 的字符串身份，{@code label} 可为 null */
    public static final class KeyName {
        private final String keyId;
        private final String label;

        public KeyName(String keyId, @Nullable String label) {
            this.keyId = keyId;
            this.label = label;
        }

        /** 身份串：物品 = 裸 registry id；流体 = {@code ae:{#t:"ae2:f",id:"…"}} */
        public String keyId() {
            return keyId;
        }

        /** 游戏内名字（本地化，如"水"）；null = 让界面用身份串的短名 */
        @Nullable
        public String label() {
            return label;
        }
    }

    /**
     * 读一个显示格背后"这是什么东西"。
     *
     * @return null = 不是 AE 显示格 / 这一格没有条目 / 认不出类型（调用方按普通格子处理）
     */
    @Nullable
    public static KeyName describe(@Nullable Object slot) {
        if (!isRepoSlot(slot) || !init()) {
            return null;
        }
        try {
            if (repoGetEntry == null) {
                repoGetEntry = repoSlotClass.getMethod("getEntry");
            }
            Object entry = repoGetEntry.invoke(slot);
            if (entry == null) {
                return null;
            }
            if (entryGetStack == null) {
                entryGetStack = entry.getClass().getMethod("getStack");
            }
            Object stack = entryGetStack.invoke(entry);
            if (stack == null) {
                return null;
            }
            return describeStack(stack);
        } catch (Throwable t) {
            warnOnce("describe", t);
            return null;
        }
    }

    /** 一个 AE 栈（{@code IAEItemStack} / {@code IAEFluidStack}）→ 身份 + 名字 */
    @Nullable
    private static KeyName describeStack(Object stack) throws Exception {
        if (fluidStackIface != null && fluidStackIface.isInstance(stack)) {
            return describeFluid(stack);
        }
        Object def = getDefinition.invoke(stack);
        if (!(def instanceof ItemStack)) {
            return null;
        }
        ItemStack itemStack = (ItemStack) def;
        if (itemStack.isEmpty()) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
        return id == null ? null : new KeyName(id.toString(), null);
    }

    /**
     * 流体栈 → {@code ae:{#t:"ae2:f",id:"minecraft:water"}} + 游戏内名（"水"）。
     *
     * 名字取 {@code FluidStack#getDisplayName()}（Forge 直接调，见类注释）——
     * 它已经处理了本地化与"装了 NBT 的流体"这些细节，比自己拼强。
     */
    @Nullable
    private static KeyName describeFluid(Object aeFluidStack) throws Exception {
        Object fsObj = getFluidStack.invoke(aeFluidStack);
        if (!(fsObj instanceof FluidStack)) {
            return null;
        }
        FluidStack fs = (FluidStack) fsObj;
        if (fs.isEmpty()) {
            return null;   // 空栈不是一种"东西"，别给它编身份
        }
        Fluid fluid = fs.getFluid();
        ResourceLocation id = fluid == null ? null : ForgeRegistries.FLUIDS.getKey(fluid);
        if (id == null) {
            return null;
        }
        String label = null;
        ITextComponent name = fs.getDisplayName();
        if (name != null) {
            String text = name.getString();
            if (text != null && !text.trim().isEmpty()) {
                label = text;
            }
        }
        return new KeyName(fluidId(id.toString()), label);
    }

    /**
     * 流体的 registry id → 身份串（**唯一的编码点**，解码在 {@link AeKeyText#idOf}）。
     * 用 SNBT 复合的形状是为了与 15.x / 19.x 线的 {@code AEKey#toTagGeneric} 对齐。
     */
    public static String fluidId(String fluidRegistryId) {
        return AeKeyText.PREFIX + "{#t:\"" + FLUID_TYPE + "\",id:\"" + fluidRegistryId + "\"}";
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    private static boolean init() {
        if (initDone) {
            return available;
        }
        initDone = true;
        try {
            fluidStackIface = Class.forName("appeng.api.storage.data.IAEFluidStack");
            Class<?> itemStackIface = Class.forName("appeng.api.storage.data.IAEItemStack");
            getFluidStack = fluidStackIface.getMethod("getFluidStack");
            getDefinition = itemStackIface.getMethod("getDefinition");
            available = true;
        } catch (Throwable t) {
            available = false;
            LogiScopeMod.LOGGER.info("[LogiScope] AE2 key API not present, AE keys stay as plain item ids");
        }
        return available;
    }

    private static void warnOnce(String what, Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            LogiScopeMod.LOGGER.warn("[LogiScope] AE key {} failed (won't spam): {}", what, t.toString());
        }
    }
}
