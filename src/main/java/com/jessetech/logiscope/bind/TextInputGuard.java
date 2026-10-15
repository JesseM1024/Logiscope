package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 输入层：判断"玩家此刻是不是正在打字"。
 *
 * 为什么需要单独一个类 * 快捷键的默认键位哪怕选得再小心，只要它是**能被打出来的字符**，
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
 *        if (ForgeHooksClient.onScreenCharTypedPre(...)) return;   // 被 cancel → 不发 Post
 *        if (screen.charTyped(...)) return;                   // 被控件吃掉 → 不发 Post
 *        ForgeHooksClient.onScreenCharTypedPost(...);              // 只有没人接手才发 Post
 *      所以 **Pre 发过、Post 没跟上来 ⇒ 上一个字符被某处消费了 ⇒ 有输入框在接手**。
 *      这条对任何模组都成立，代价是"刚打完字想开面板"时要多按一次（会自愈，见下）。
 *
 * 第 3 层的正确写法（曾因此出错，勿改回）*   **不要用累计计数**。曾经写过 `preCount++ / postCount++`，靠 `preCount > postCount`
 *   判断"上一个字符被吃了" —— 那是错的：一个被吃掉的字符会在差值上留下**永久的 +1**，
 *   之后每个未被吃掉的字符同时给两个计数各 +1，差值只会原地保持、**永远不会归零**。
 *   实测后果：在任何输入框里打过一次字之后，快捷键就**永久失效**
 *   。
 *
 *   正确写法是一个布尔量：Pre 时置 true，Post 真的来了就置回 false。
 *   因为 Pre/Post 在同一次调用里同步跑完，所以一个字符事件结束时它恰好等于
 *   "这个字符有没有被吃掉"。而 KeyPressed 在 charTyped **之前**触发，
 *   于是按键那一刻读到的是"上一个字符的结果"。
 *
 * 为什么能自愈 *     · 在输入框里：`]` 的字符会被吃掉 → Post 不来 → 布尔量保持 true → 一直拦得住
 *     · 不在输入框里：`]` 的字符没人吃 → Post 把它置回 false → 下一次按键就正常
 *   再叠一个 TTL 时效窗口，避免"很久以前打过一次字"抑制快捷键。
 *
 * 默认键位已经换成 F9（见 KeyBindings）*   F 键不产生字符，所以这一层对默认键位其实用不上 —— 它保的是"用户把快捷键
 *   改成字母/符号键"以及"某些模组把所有按键都吃掉"这两种情况。
 */
public final class TextInputGuard {

    /** 第 3 层的时效窗口（tick）。60 tick = 3 秒 */
    private static final int CONSUMED_CHAR_TTL = 60;

    /**
     * 上一个字符事件有没有被界面消费掉。
     * Pre 时先置 true（假设被吃掉），Post 真的发出来了就说明没人接手，置回 false。
     */
    private static boolean lastCharConsumed;

    /** 上一次字符事件发生在哪一 tick，用来做时效窗口 */
    private static long lastCharTick;

    private TextInputGuard() {
    }

    // ------------------------------------------------------------------
    // 事件入口（由主类注册到游戏总线）
    // ------------------------------------------------------------------

    public static void onCharTypedPre(ScreenEvent.CharacterTyped.Pre event) {
        lastCharConsumed = true;
        lastCharTick = LogiScopeMod.probes().currentTick();
    }

    public static void onCharTypedPost(ScreenEvent.CharacterTyped.Post event) {
        // Post 发出来了 = 这个字符没有人接手
        lastCharConsumed = false;
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
     * 见类注释里对 KeyboardHandler#charTyped 的核实结论，以及"为什么不能用累计计数"。
     */
    private static boolean lastCharWasConsumed() {
        if (!lastCharConsumed) {
            return false;
        }
        return LogiScopeMod.probes().currentTick() - lastCharTick <= CONSUMED_CHAR_TTL;
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
     * 要给别的搜索类模组（REI / EMI 等）加支持，就在这里再写一个同样的小探针 */
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
