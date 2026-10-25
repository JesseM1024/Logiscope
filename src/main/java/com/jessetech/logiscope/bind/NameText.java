package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeConfig;
import com.jessetech.logiscope.analysis.AeKeyText;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 界面上**所有名字**的统一出口 —— 一个开关决定写成「注册名」还是「实际名」。
 *
 * 【为什么需要它】同一个东西在界面各处原来有两种写法：
 * <pre>
 *   选格页：AE 流体显示游戏内真名（水、熔岩），物品显示短 id（iron_ingot）
 *   图表图例 / 计算页：一律显示短 id（water、iron_ingot）
 * </pre>
 * 同一份数据在两个页面上名字不一样，对不上号。现在统一走这里，选格页 / 图表页 / 计算页
 * 各有一个「名称: …」按钮（见 {@code ScopeScreen#buttonItems}），点一下三处一起变，
 * 并且写回 {@code config/logiscope-client.cfg} 的 {@code nameDisplay}（下次启动保持）。
 *
 * 【只影响显示，不影响身份】探针记录 / 存档 / 导出里的名字永远是**键身份**：
 * 物品 = registry id，AE 的非物品键 = {@code ae:} + 键的 SNBT（见 {@link AeKeyText}）。
 * 所以切这个开关不会让旧存档对不上新曲线，也不会改变聚合（同物品多格求和靠的是身份串）。
 *
 * 【三种名字的来源】
 * | 身份 | 注册名（REGISTRY） | 实际名（DISPLAY） |
 * |---|---|---|
 * | {@code minecraft:iron_ingot} | {@code iron_ingot}（去命名空间） | {@code ItemStack#getDisplayName()}，如「铁锭」 |
 * | {@code appliedenergistics2:part:11}（带子类型，见 {@code ContainerAccess#itemIdOf}） | {@code part:11} | 该变体自己的名字，如「ME Glass Cable - White」 |
 * | {@code ae:{#t:"ae2:f",id:"minecraft:water"}} | {@code water} | {@code FluidStack#getLocalizedName()}，如「水」 |
 * | 其它（数据路径 / 占位名） | 原样短名 | 原样短名（解析不出来就**不编名字**） |
 *
 * ★ **本线没有 AEKey 解码器**（AE2 rv6 的键是 IAEItemStack / IAEFluidStack，见 {@code collect/AeSlots}）：
 * 所以 DISPLAY 模式是"按身份串里的键类型 + 原版/Forge 注册表还原"，不是问 AE 要显示名。
 * 拿不到（键类型不认识 / 注册表里没有）就退回注册名，绝不编一个看起来像名字的东西。
 *
 * 【缓存】两种写法都缓存（选格页每帧每行、图例每帧每系列都要算），切换风格时整表作废。
 *
 * 【失败策略】注册表没就绪（离线断言）/ 解析异常 → 退回注册名短 id，绝不炸渲染帧。
 */
public final class NameText {

    /** 两种写法。配置里存的是布尔（{@code nameDisplay}），这里只是给界面与调用方一个好读的名字 */
    public enum Style {
        /** 注册名：{@code iron_ingot} / {@code water} */
        REGISTRY,
        /** 实际名：游戏内本地化名，{@code 铁锭} / {@code 水} */
        DISPLAY
    }

    /** AE 键类型里"物品"与"流体"两种（见 {@link AeKeyText#typeOf}） */
    private static final String TYPE_ITEM = "ae2:i";
    private static final String TYPE_FLUID = "ae2:f";

    /** 身份串 → 当前风格下的名字。切换风格时整表作废（见 {@link #of(String, String)}） */
    private static final Map<String, String> CACHE = new HashMap<>();
    /** 缓存是按哪种风格建的：切换开关后旧条目一律作废 */
    @Nullable
    private static Style cacheStyle;

    private NameText() {
    }

    /** 当前风格（读配置）。配置类没加载起来（离线断言）时按默认 DISPLAY */
    public static Style style() {
        try {
            return LogiScopeConfig.nameDisplay() ? Style.DISPLAY : Style.REGISTRY;
        } catch (Throwable t) {
            // 连 NoClassDefFoundError（离线环境没有 Forge 配置类）也要兜住
            return Style.DISPLAY;
        }
    }

    /** 现在是「实际名」吗（按钮配色 / 文案用） */
    public static boolean showDisplay() {
        return style() == Style.DISPLAY;
    }

    /** 切换风格并**写回配置**。调用方（按钮）之后重新 say 一句提示即可 */
    public static void toggle() {
        LogiScopeConfig.setNameDisplay(!LogiScopeConfig.nameDisplay());
        invalidate();
    }

    /** 清缓存：切换开关后必须调（语言热重载也建议调一次） */
    public static void invalidate() {
        CACHE.clear();
        cacheStyle = null;
    }

    /** 身份串 → 该显示的名字 */
    public static String of(@Nullable String id) {
        return of(id, null);
    }

    /**
     * 身份串 → 该显示的名字，带一个「已知的实际名」提示。
     *
     * @param hint 调用方手上已经有实际名时传进来（AE 终端格在 {@code SlotView#label()} 里
     *             已经拿到过显示名，不必再查一遍注册表）；**只在 DISPLAY 模式下生效**
     */
    public static String of(@Nullable String id, @Nullable String hint) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        boolean display = showDisplay();
        if (display && hint != null && !hint.trim().isEmpty()) {
            return hint;
        }
        // 两种写法都缓存：选格页每帧每行、图例每帧每系列都要算一次
        if (cacheStyle == null || cacheStyle != (display ? Style.DISPLAY : Style.REGISTRY)) {
            CACHE.clear();
            cacheStyle = display ? Style.DISPLAY : Style.REGISTRY;
        }
        String hit = CACHE.get(id);
        if (hit != null) {
            return hit;
        }
        String name = display ? resolveDisplay(id) : AeKeyText.label(id);
        CACHE.put(id, name);
        return name;
    }

    /** 真正的解析：非物品键按类型走注册表；物品 id 走物品注册表；其它原样短名 */
    private static String resolveDisplay(String id) {
        try {
            if (AeKeyText.isAe(id)) {
                String type = AeKeyText.typeOf(id);
                String inner = AeKeyText.idOf(id);
                if (inner != null && !inner.trim().isEmpty()) {
                    String name = TYPE_FLUID.equals(type) ? fluidName(inner)
                            : TYPE_ITEM.equals(type) ? itemName(inner) : null;
                    if (name != null) {
                        return name;
                    }
                }
                return AeKeyText.label(id);   // 认不出的键类型 → 短 id，不编名字
            }
            String name = itemName(id);
            return name != null ? name : AeKeyText.label(id);
        } catch (Throwable t) {
            // 注册表没就绪（离线断言）/ 客户端没进世界 —— 退回注册名，绝不炸渲染帧
            return AeKeyText.label(id);
        }
    }

    /**
     * 物品 id → 游戏内显示名；不是物品 / 取不到 → null。
     *
     * 身份串可能带子类型（{@code appliedenergistics2:part:11}，见
     * {@code ContainerAccess#itemIdOf}）—— 带 meta 才有正确的变体名，
     * 否则线缆 / 终端会全显示成 meta=0 那一种的名字。
     */
    @Nullable
    private static String itemName(String id) {
        ItemStack stack = stackOf(id);
        if (stack == null) {
            return null;
        }
        // 1.12.2 的 ItemStack#getDisplayName() 直接返回 String（1.13+ 才是 ITextComponent）
        String text = stack.getDisplayName();
        return text == null || text.trim().isEmpty() ? null : text;
    }

    /**
     * 身份串 → 一个数量 1 的 ItemStack（只为取它的显示名）。
     *
     * 先按整串查；查不到再把"最后一段纯数字"当成 meta —— 这就是带子类型的身份形式
     * （{@code appliedenergistics2:part:11}）。两种来源（老存档的裸 id、新的带 meta）
     * 都能取到名字。
     */
    @Nullable
    private static ItemStack stackOf(String id) {
        Item item = null;
        int meta = 0;
        try {
            item = Item.getByNameOrId(id);   // 1.12.2 的查法：注册名直接给，找不到返回 null
        } catch (Throwable ignored) {
            // 形如 a:b:11 的串不是合法 ResourceLocation（1.12.2 只认 a:b）→ 走下面的拆分
        }
        if (item == null) {
            int cut = id.lastIndexOf(':');
            if (cut > 0 && cut < id.length() - 1 && isDigits(id.substring(cut + 1))) {
                try {
                    item = Item.getByNameOrId(id.substring(0, cut));
                    if (item != null) {
                        meta = Integer.parseInt(id.substring(cut + 1));
                    }
                } catch (Throwable ignored) {
                    item = null;
                }
            }
        }
        if (item == null) {
            return null;
        }
        ItemStack stack = new ItemStack(item, 1, meta);
        return stack.isEmpty() ? null : stack;
    }

    /** 纯数字（meta 只有 0~15 有意义，留到 4 位容忍异常值） */
    private static boolean isDigits(String s) {
        if (s.isEmpty() || s.length() > 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** 流体 id → 游戏内显示名（{@code getLocalizedName()} 已处理本地化）；取不到 → null */
    @Nullable
    private static String fluidName(String id) {
        Fluid fluid = FluidRegistry.getFluid(id);
        if (fluid == null) {
            // 1.12.2 的 FluidRegistry 里原版流体按不带命名空间的名字注册（water / lava）
            fluid = FluidRegistry.getFluid(AeKeyText.shortId(id));
        }
        if (fluid == null) {
            return null;
        }
        String text = new FluidStack(fluid, 1).getLocalizedName();
        return text == null || text.trim().isEmpty() ? null : text;
    }
}
