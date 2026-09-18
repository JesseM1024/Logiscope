package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 输入层：判断"玩家此刻是不是正在打字"。
 *
 * ★ 为什么需要单独一个类 ★
 * 快捷键的默认键位哪怕选得再小心，只要它是**能被打出来的字符**，
 * 就一定会和搜索框打架。所以必须主动判断"当前有没有输入框在接手键盘"。
 *
 * 这里做了**三层**守卫，从准到糙，任何一层命中就不抢键：
 *
 *   1) 原版契约 —— Screen#getFocused() / children() 里的 EditBox
 *      覆盖所有走 Screen 焦点机制的文本框（原版背包搜索框、大多数模组输入框）
 *
 *   2) 第三方浮层搜索框的公开 API —— 目前接 JEI
 *      JEI 的搜索框（mezz.jei.gui.input.GuiTextFieldFilter）**本身是 EditBox**，
 *      但它不挂进 screen.children()，而是自己管输入（createInputHandler），
 *      所以第 1 层看不见它。好在 JEI 暴露了公开 API：
 *        mezz.jei.api.runtime.IIngredientListOverlay#hasKeyboardFocus()
 *      用反射调用，**零编译期依赖**：没装 JEI 就静静跳过。
 *
 *   3) 通用兜底 —— "上一个字符事件被界面吃掉了"
 *      核实过 KeyboardHandler#charTyped 的实现：
 *        if (ClientHooks.onScreenCharTypedPre(...)) return;   // 被 cancel → 不发 Post
 *        if (screen.charTyped(...)) return;                   // 被控件吃掉 → 不发 Post
 *        ClientHooks.onScreenCharTypedPost(...);              // 只有没人接手才发 Post
 *      所以 **Pre 比 Post 多一次 ⇒ 上一个字符被某处消费了 ⇒ 有输入框在接手**。
 *      这条对任何模组都成立，代价是"刚打完字想开面板"时要多按一次（会自愈，见下）。
 *
 * ★ 第 3 层为什么能自愈 ★
 *   KeyPressed 在 charTyped **之前**触发。所以：
 *     · 在输入框里：`]` 的字符会被吃掉 → 计数差保持 → 永远拦得住
 *     · 不在输入框里：`]` 的字符没人吃 → 计数差归零 → 下一次按键就正常了
 *   再叠一个 60 tick 的时效窗口，避免"很久以前打过一次字"永久抑制快捷键。
 */
public final class TextInputGuard {

    /** 第 3 层的时效窗口（tick）。60 tick = 3 秒 */
    private static final int CONSUMED_CHAR_TTL = 60;

    /** CharacterTyped 的 Pre/Post 计数。差值 > 0 ⇔ 上一个字符被消费 */
    private static int charPreCount;
    private static int charPostCount;
    private static long charPreTick;

    private TextInputGuard() {
    }

    // ------------------------------------------------------------------
    // 事件入口（由主类注册到游戏总线）
    // ------------------------------------------------------------------

    public static void onCharTypedPre(ScreenEvent.CharacterTyped.Pre event) {
        charPreCount++;
        charPreTick = LogiScopeMod.sampler().currentTick();
    }

    public static void onCharTypedPost(ScreenEvent.CharacterTyped.Post event) {
        charPostCount++;
    }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /** 玩家此刻是不是在打字？是的话快捷键要让路。 */
    public static boolean isTypingNow(@Nullable Screen screen) {
        return hasVanillaTextFocus(screen) || JeiSearchProbe.hasKeyboardFocus() || lastCharWasConsumed();
    }

    /**
     * 第 1 层：原版契约。
     *
     * 主判据是 Screen#getFocused（Screen#mouseClicked 会把被点的子控件设成 focused），
     * 覆盖原版 EditBox。仍然保留类名兜底：少数模组的文本框不是 EditBox、
     * 也不一定走 Screen 焦点机制 —— 宁可漏触发一次快捷键，也不要在玩家打字时弹面板。
     */
    private static boolean hasVanillaTextFocus(@Nullable Screen screen) {
        if (screen == null) {
            return false;
        }
        if (isTextLike(screen.getFocused())) {
            return true;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget.isFocused() && isTextLike(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextLike(@Nullable GuiEventListener listener) {
        if (listener == null) {
            return false;
        }
        if (listener instanceof EditBox) {
            return true;
        }
        String name = listener.getClass().getName();
        return name.contains("EditBox")
                || name.contains("TextField")
                || name.contains("SearchField")
                || name.contains("InputField")
                || name.contains("SearchBox");
    }

    /**
     * 第 3 层：上一个字符被界面吃掉了，且发生在时效窗口内。
     * 见类注释里对 KeyboardHandler#charTyped 的核实结论。
     */
    private static boolean lastCharWasConsumed() {
        if (charPreCount <= charPostCount) {
            return false;
        }
        return LogiScopeMod.sampler().currentTick() - charPreTick <= CONSUMED_CHAR_TTL;
    }

    // ------------------------------------------------------------------
    // 第 2 层：第三方浮层搜索框探针
    // ------------------------------------------------------------------

    /**
     * JEI 搜索框焦点探针。
     *
     * 只用反射，不引入 compileOnly 依赖 —— 这样"没装 JEI 也能跑"，
     * 也不会因为 JEI 换版本而在编译期就崩。JEI 用的是公开 API
     * （IIngredientListOverlay#hasKeyboardFocus），只有入口
     * mezz.jei.common.Internal#getJeiRuntime 属于内部类，所以整段包在 try/catch 里，
     * 失败就永久关闭这一层，最多损失一点鲁棒性。
     *
     * ★ 要给别的搜索类模组（REI / EMI 等）加支持，就在这里再写一个同样的小探针 ★
     */
    private static final class JeiSearchProbe {

        private static boolean initialised;
        private static boolean available;
        @Nullable
        private static Method getRuntime;
        @Nullable
        private static Method getIngredientListOverlay;
        @Nullable
        private static Method hasKeyboardFocus;

        private JeiSearchProbe() {
        }

        static boolean hasKeyboardFocus() {
            if (!initialised) {
                init();
            }
            if (!available) {
                return false;
            }
            try {
                Object runtime = getRuntime.invoke(null);
                if (runtime == null) {
                    return false;      // JEI 还没加载完 / 已卸载
                }
                Object overlay = getIngredientListOverlay.invoke(runtime);
                if (overlay == null) {
                    return false;
                }
                return Boolean.TRUE.equals(hasKeyboardFocus.invoke(overlay));
            } catch (Throwable t) {
                // 反射调用出问题就关掉这一层，绝不因为守卫本身把游戏搞崩
                available = false;
                LogiScopeMod.LOGGER.debug("[LogiScope] JEI 搜索框探针调用失败，已停用", t);
                return false;
            }
        }

        private static void init() {
            initialised = true;
            try {
                Class<?> internal = Class.forName("mezz.jei.common.Internal");
                Class<?> runtimeType = Class.forName("mezz.jei.api.runtime.IJeiRuntime");
                Class<?> overlayType = Class.forName("mezz.jei.api.runtime.IIngredientListOverlay");

                getRuntime = internal.getMethod("getJeiRuntime");
                getIngredientListOverlay = runtimeType.getMethod("getIngredientListOverlay");
                hasKeyboardFocus = overlayType.getMethod("hasKeyboardFocus");
                available = true;
                LogiScopeMod.LOGGER.debug("[LogiScope] 已接入 JEI 搜索框焦点探针");
            } catch (Throwable t) {
                available = false;
                LogiScopeMod.LOGGER.debug("[LogiScope] 未检测到 JEI 搜索框 API，跳过该守卫");
            }
        }
    }
}
