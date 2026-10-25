package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeConfig;
import com.jessetech.logiscope.analysis.AeKeyText;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 界面上**所有名字**的统一出口 —— 一个开关决定写成「注册名」还是「实际名」。
 *
 * 【为什么需要它】同一个东西在界面各处原来有两种写法：
 * <pre>
 *   选格页：流体 / 化学品显示游戏内真名（水、熔岩、氢），物品显示短 id（iron_ingot）
 *   图表图例 / 计算页：一律显示短 id（water、iron_ingot）
 * </pre>
 * 同一份数据在两个页面上名字不一样，对不上号。现在统一走这里，选格页 / 图表页 / 计算页
 * 各有一个「名称: …」按钮（见 {@code ScopeScreen#buttonItems}），点一下三处一起变，
 * 并且写回 {@code config/logiscope-client.toml} 的 {@code display.nameDisplay}（下次启动保持）。
 *
 * 【只影响显示，不影响身份】探针记录 / 存档 / 导出里的名字永远是**键身份**：
 * 物品 = registry id，AE 的非物品键 = {@code ae:} + AEKey 的 SNBT
 * （见 {@link AeKeyText}）。所以切这个开关不会让旧存档对不上新曲线，也不会改变聚合
 * （同物品多格求和靠的是身份字符串，不是显示名）。
 *
 * 【三种名字的来源】
 * | 身份 | 注册名（REGISTRY） | 实际名（DISPLAY） |
 * |---|---|---|
 * | {@code minecraft:iron_ingot} | {@code iron_ingot}（去命名空间） | 物品的 {@code getDescription()}，如「铁锭」 |
 * | {@code ae:{#t:"ae2:f",id:"minecraft:water"}} | {@code water}（SNBT 里的 id 短名） | {@code AEKey#getDisplayName()}，如「水」 |
 * | 其它（数据路径 / 占位名） | 原样短名 | 原样短名（解析不出来就**不编名字**） |
 *
 * 【缓存】DISPLAY 模式要解 SNBT + 建 AEKey + 取本地化名，图例每帧都要调用，
 * 所以按身份串缓存；切换开关时 {@link #invalidate()} 清空。语言热重载后旧名字会留到
 * 下次切换 —— 这是刻意的取舍（每帧查一次语言表的代价不值得）。
 *
 * 【失败策略】AE2 不在 / 注册表没就绪（离线断言）/ 键解不出来 → 退回注册名短 id，
 * **绝不编一个看起来像名字的东西**（与项目一贯的"读不到就说读不到"一致）。
 */
public final class NameText {

    /** 两种写法。配置里存的是布尔（{@code nameDisplay}），这里只是给界面与调用方一个好读的名字 */
    public enum Style {
        /** 注册名：{@code iron_ingot} / {@code water} */
        REGISTRY,
        /** 实际名：游戏内本地化名，{@code 铁锭} / {@code 水} */
        DISPLAY
    }

    /** 身份串 → 当前风格下的名字。切换风格时整表作废（见 {@link #of(String, String)}） */
    private static final Map<String, String> CACHE = new HashMap<>();
    /** 缓存是按哪种风格建的：切换开关后旧条目一律作废 */
    @Nullable
    private static Style cacheStyle;

    private NameText() {
    }

    /** 当前风格（读客户端配置）。配置类没加载起来（离线断言）时按默认 DISPLAY */
    public static Style style() {
        try {
            return LogiScopeConfig.nameDisplay() ? Style.DISPLAY : Style.REGISTRY;
        } catch (Throwable t) {
            // 连 NoClassDefFoundError（离线环境没有 NeoForge 配置类）也要兜住：
            // 这个名字函数在渲染帧里被调用，绝不能因为它把一帧画崩
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
     *             已经拿到过 {@code getDisplayName()}，不必再解一次 SNBT）；
     *             **只在 DISPLAY 模式下生效**，REGISTRY 模式一律按身份串算。
     */
    public static String of(@Nullable String id, @Nullable String hint) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        boolean display = showDisplay();
        if (display && hint != null && !hint.isBlank()) {
            return hint;
        }
        // 两种写法都缓存：选格页每帧每行都要算一次（AE 行的注册名要解 SNBT），
        // 而图例/计算页每帧调用次数与系列数成正比 —— 纯文本解析不该落在渲染帧上。
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

    /** 真正的解析：AE 键 → AEKey 的显示名；物品 id → 物品显示名；其它 → 短 id */
    private static String resolveDisplay(String id) {
        try {
            if (AeKeyText.isAe(id)) {
                Object key = AeKeys.decodeKey(id);   // 本线：AEKey#fromTagGeneric 不需要 registries
                String name = AeKeys.displayName(key);
                if (name != null && !name.isBlank()) {
                    return name;
                }
                // 解不出来（键类型没注册 / SNBT 坏了 / AE2 不在）→ 短 id，不编名字
                return AeKeyText.label(id);
            }
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                Optional<Item> item = BuiltInRegistries.ITEM.getOptional(rl);
                if (item.isPresent()) {
                    return item.get().getDescription().getString();
                }
            }
            // 不是物品 id（数据页的路径名 / "slot 3" 这类占位名）→ 原样短名
            return AeKeyText.label(id);
        } catch (Throwable t) {
            // 注册表没就绪（离线断言）/ 客户端没进世界 —— 退回注册名，绝不炸渲染帧
            return AeKeyText.label(id);
        }
    }
}
