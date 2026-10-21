package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.AeKeyText;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * **AE 键的反射层**：把网络里的"一种东西"变成 LogiScope 的字符串身份，再变回去。
 *
 * 与 {@link com.jessetech.logiscope.collect.AeNetworkReader} 分工：
 * 那个类负责"这台设备在哪个网络、网络活不活、从哪拿 KeyCounter"；
 * 这个类只管**键本身**（编码 / 解码 / 显示名 / 终端显示格的存量）。
 *
 * 【1.20.1 与 1.21.1 的唯一 API 差别】这一代的键序列化**不带注册表参数**：
 * {@code AEKey#toTagGeneric()} 无参、{@code AEKey#fromTagGeneric(CompoundTag)} 单参
 * （19.x 才改成要 {@code HolderLookup.Provider}，1.20.1 更没有 HolderLookup 这个类型）。
 * 所以本分支的 encode/decode 都不传注册表 —— 方法名与 1.21.1 那条线保持一致，
 * 只有参数表不同，方便对照。
 *
 * 【反射的边界：只反射 AE，不反射 MC】（2026-10-21 实机踩坑后定）
 *   · AE2 不是编译期依赖，它的类只能反射；
 *   · **原版（MC）的方法一律直接调**。1.20.1 的生产运行时是 SRG 命名 ——
 *     `TagParser#parseTag` 在 jar 里叫 `m_129359_`，按名字反射**dev 能过、装进实例必扑空**。
 *     实测后果：init() 整条链失败 → 所有 AE 键退回物品 id → 流体显示成
 *     `wrapped_generic_stack x2147483647`，而且点一行会把所有包裹堆栈一起选中。
 *     直接调则编译器 / 重混淆器会把它写成正确的生产名。
 *   · 纯文本那一半在 {@code analysis/AeKeyText}（无任何游戏依赖，离线断言覆盖）。
 *
 * 【失败策略】AE2 不在 → 全部返回 null/false（上层自然退化成"不是 AE 格子"）；
 * 单次反射异常 → 记一次日志后按"读不到"处理，绝不猜一个值出来。
 */
public final class AeKeys {

    private static boolean initDone;
    private static boolean available;

    @Nullable
    private static Method toTagGeneric;
    @Nullable
    private static Method fromTagGeneric;
    @Nullable
    private static Method isWrapped;
    @Nullable
    private static Method unwrapItemStack;
    @Nullable
    private static Method genericWhat;
    @Nullable
    private static Method keyDisplayName;

    /** 终端显示格的类（{@code appeng.client.gui.me.common.RepoSlot}），按名字沿父类链找 */
    @Nullable
    private static Class<?> repoSlotClass;
    /** {@code RepoSlot#getStoredAmount()} */
    @Nullable
    private static Method repoStoredAmount;
    /**
     * {@code RepoSlot#getEntry()}：返回网络列表条目（null = 这一格现在没有条目）。
     *
     * 为什么不用 {@code Slot#hasItem()}：1.20.1 的生产运行时是 **SRG 命名**，
     * MC 自己的方法在 jar 里叫 {@code m_6657_} 之类，按 "hasItem" 反射在生产环境会扑空；
     * 而 {@code getEntry()} 是 AE 自己的方法，dev / 生产同名。语义也正好是我们要的
     * "条目在不在"（可合成但零存货的条目 getEntry != null、存量是 0）。
     */
    @Nullable
    private static Method repoGetEntry;

    private static boolean loggedFailure;

    private AeKeys() {
    }

    // ==================================================================
    // 编码 / 解码
    // ==================================================================

    /**
     * AE 键 → 字符串身份：{@code "ae:" + AEKey#toTagGeneric 的 SNBT}。
     *
     * @param aeKey {@code appeng.api.stacks.AEKey}（调用方按 Object 传，避免编译期依赖）
     * @return null = 编不出来（AE2 不在 / 这个键的 codec 抛异常）—— 调用方应把该行当普通格子处理
     */
    @Nullable
    public static String encodeKey(@Nullable Object aeKey) {
        if (aeKey == null || !init()) {
            return null;
        }
        try {
            Object tag = toTagGeneric.invoke(aeKey);
            return tag == null ? null : AeKeyText.PREFIX + tag;
        } catch (Throwable t) {
            warnOnce("encode", t);
            return null;
        }
    }

    /**
     * 字符串身份 → AE 键（{@code AEKey#fromTagGeneric}）。
     *
     * @return null = 解不出来（不是 ae 键 / SNBT 坏了 / 这种键类型没注册 → 上层记 no_data）
     */
    @Nullable
    public static Object decodeKey(@Nullable String id) {
        if (!AeKeyText.isAe(id) || !init()) {
            return null;
        }
        try {
            CompoundTag tag = TagParser.parseTag(id.substring(AeKeyText.PREFIX.length()));
            if (tag == null) {
                return null;
            }
            return fromTagGeneric.invoke(null, tag);
        } catch (Throwable t) {
            warnOnce("decode", t);
            return null;
        }
    }

    /**
     * 一种非物品键：身份串 + 游戏内显示名（如"水"）。
     *
     * {@code keyId} 可能为 null（编码失败，只会发生在"键类型连类型 id 都拿不到"的极端情况）——
     * 那时调用方应给一个**解不开**的身份（采样记 no_data），只把 {@code label} 用在显示上。
     */
    public record Wrapped(@Nullable String keyId, @Nullable String label) {
    }

    /**
     * 客户端物品栈 → 非物品键（流体 / 化学品 / 电力…）。
     *
     * AE 终端的显示格对这类键给的是 {@code ae2:wrapped_generic_stack} 包着一个
     * {@code GenericStack} 数据组件 —— 名字和数量都在组件里，**不在** ItemStack 上。
     * 普通物品（{@code AEItemKey}）返回 null：那条路继续用 registry id。
     *
     * @param itemStack {@code ItemStack}（可为 null / 空）
     * @return null = 不是"包装过的通用堆栈"
     */
    @Nullable
    public static Wrapped unwrap(@Nullable Object itemStack) {
        if (itemStack == null || !init()) {
            return null;
        }
        try {
            if (!Boolean.TRUE.equals(isWrapped.invoke(null, itemStack))) {
                return null;
            }
            Object generic = unwrapItemStack.invoke(null, itemStack);
            if (generic == null) {
                return null;
            }
            Object what = genericWhat.invoke(generic);
            if (what == null) {
                return null;
            }
            String keyId = encodeKey(what);
            if (keyId == null) {
                // 编码失败也不能把这一行当普通物品：那会把"水 2.1M mB"显示成
                // "wrapped_generic_stack x2100000"。给一个**明确读不出来**的身份串
                // （带 ?），解码必然失败 → 采样记 no_data，而不是编一个 0 出来。
                keyId = opaqueId(what);
            }
            String label = displayName(what);
            return new Wrapped(keyId, label);
        } catch (Throwable t) {
            warnOnce("unwrap", t);
            return null;
        }
    }

    /**
     * 编码失败时的**兜底身份**：{@code ae:?<类型 id>|<键 id>}。
     * 前导 {@code ?} 是刻意的：它不属于任何合法 SNBT 复合（必须以 `{` 开头），
     * 所以 {@link #decodeKey} 必然失败 → 采样记 no_data。界面仍能显示键自己的名字。
     */
    @Nullable
    private static String opaqueId(Object what) {
        try {
            Object type = what.getClass().getMethod("getType").invoke(what);
            Object typeId = type.getClass().getMethod("getId").invoke(type);
            Object keyId = what.getClass().getMethod("getId").invoke(what);
            return typeId == null || keyId == null ? null
                    : AeKeyText.PREFIX + "?" + typeId + "|" + keyId;
        } catch (Throwable t) {
            return null;
        }
    }

    /** AE 键的游戏内显示名（本地化），拿不到返回 null */
    @Nullable
    public static String displayName(@Nullable Object aeKey) {
        if (aeKey == null || !init()) {
            return null;
        }
        try {
            Object component = keyDisplayName.invoke(aeKey);
            return component instanceof Component c ? c.getString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // 终端显示格（RepoSlot）
    // ==================================================================

    /** 这个菜单槽是不是 AE 终端的"网络列表显示格"（按类名沿父类链判，零加载假设） */
    public static boolean isRepoSlot(@Nullable Object slot) {
        if (slot == null) {
            return false;
        }
        if (repoSlotClass != null && repoSlotClass.isInstance(slot)) {
            return true;
        }
        for (Class<?> c = slot.getClass(); c != null; c = c.getSuperclass()) {
            if ("appeng.client.gui.me.common.RepoSlot".equals(c.getName())) {
                repoSlotClass = c;
                return true;
            }
        }
        return false;
    }

    /**
     * 显示格背后**真正的存量**（long）。AE 的终端格子里 {@code ItemStack.getCount()}
     * 恒为 1（它只是个图标），真实数量在 {@code GridInventoryEntry#getStoredAmount()}。
     *
     * @return null = 不是 AE 显示格（调用方按普通槽位读栈数量）
     */
    @Nullable
    public static Long repoAmount(@Nullable Object slot) {
        if (!isRepoSlot(slot)) {
            return null;
        }
        try {
            if (repoStoredAmount == null) {
                repoStoredAmount = repoSlotClass.getMethod("getStoredAmount");
            }
            Object v = repoStoredAmount.invoke(slot);
            return v instanceof Long l ? l : null;
        } catch (Throwable t) {
            warnOnce("repoAmount", t);
            return null;
        }
    }

    /**
     * 显示格上"有没有这个条目"。
     *
     * 与 {@code getStoredAmount() == 0} **不是一回事**：搜索得到的"可合成但没存货"条目
     * 存量就是 0，但它是网络里真实存在的一种东西（监测它就是"现在 0 个"这个真值），
     * 所以不能用数量判空。
     */
    public static boolean repoHasEntry(@Nullable Object slot) {
        if (!isRepoSlot(slot)) {
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
    // 初始化
    // ==================================================================

    private static boolean init() {
        if (initDone) {
            return available;
        }
        initDone = true;
        try {
            Class<?> aeKey = Class.forName("appeng.api.stacks.AEKey");
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
            Class<?> itemStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> generic = Class.forName("appeng.api.stacks.GenericStack");

            // 1.20.1：无参序列化（见类注释）
            toTagGeneric = aeKey.getMethod("toTagGeneric");
            fromTagGeneric = aeKey.getMethod("fromTagGeneric", compound);
            keyDisplayName = aeKey.getMethod("getDisplayName");
            // TagParser / Component 是原版类，直接调（见类注释）—— 不在这里反射

            isWrapped = generic.getMethod("isWrapped", itemStack);
            unwrapItemStack = generic.getMethod("unwrapItemStack", itemStack);
            genericWhat = generic.getMethod("what");

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
