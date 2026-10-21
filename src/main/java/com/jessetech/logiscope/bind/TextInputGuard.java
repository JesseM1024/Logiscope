package com.jessetech.logiscope.bind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;

/**
 * 输入层：判断"玩家此刻是不是在打字"，决定快捷键要不要让路。
 *
 * 【1.12.2 只能做到这个精度，原因写在下面】
 * 另外三条线有 {@code GuiScreenEvent.KeyboardKeyPressedEvent} 与 {@code KeyboardCharTypedEvent}，
 * 于是能做到三层守卫（焦点 API + JEI 探针 + "上一个字符被吃掉"的计数差）。
 * **1.12.2 这两类事件都没有**（`GuiScreenEvent` 家族只有 7 个成员，`KeyboardInputEvent`
 * 连按键号都不带），而且 `GuiScreen` 在这一代**没有焦点概念**（没有 `getFocused`），
 * 所以"有没有输入框在接手键盘"没有通用判据 —— 只能按两类可观测事实来判：
 *
 *   1. **功能键（F1–F25）永远不挡**：它们不产生字符，不可能是"打字"的一部分。
 *      这一条与 1.16.5 线后期加的规则一致，而且是本线的主要判据
 *      （默认快捷键就是 F9）。
 *   2. **会打字的键 + 有界面开着 ⇒ 让路**：保守但不会误吞玩家的输入。
 *      有界面时想开面板，请用功能键（默认就是）。
 *
 * 另外专门挡掉聊天/命令输入（{@link GuiChat}）——那一支连光标都在闪，
 * 任何会打字的键都不该被抢。
 *
 * 【为什么不用反射去读 buttonList 找 GuiTextField】
 * 1.12.2 生产运行时是 SRG 命名：`GuiScreen.buttonList` 在 jar 里叫 `field_146292_n`、
 * `GuiTextField.isFocused` 也有映射名。**按名字反射 MC 的字段/方法 dev 能过、装进实例必扑空**
 * （本项目在 1.20.1 线为此栽过两次）。所以宁可用上面两条粗判据，也不去反射 MC 的私有细节。
 */
public final class TextInputGuard {

    private TextInputGuard() {
    }

    /**
     * 这个键要不要让路。
     *
     * @param keyCode LWJGL 的键码（{@code OPEN_SCOPE.getKeyCode()}）
     */
    public static boolean blocksHotkey(int keyCode) {
        if (isFunctionKey(keyCode)) {
            return false;   // 功能键不产生字符，永不让路
        }
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        if (screen == null) {
            return false;
        }
        // 聊天 / 命令输入：一定是打字
        if (screen instanceof GuiChat) {
            return true;
        }
        // 其它界面：快捷键是"会打字的键"就保守让路（换回功能键即恢复正常）
        return true;
    }

    /** 功能键：LWJGL2 的 F1..F25 是连续的（KEY_F1=59 … KEY_F25=0x3E+?），用 F1/F12 之外的范围也一并算上 */
    private static boolean isFunctionKey(int keyCode) {
        // Keyboard.KEY_F1 = 59, KEY_F12 = 88；F13–F25 在 LWJGL2 里没有独立常量，
        // 但键盘上确实存在，作为"不产生字符的键"同样不该被挡。
        return (keyCode >= Keyboard.KEY_F1 && keyCode <= Keyboard.KEY_F12)
                || keyCode == Keyboard.KEY_NONE;
    }

    /** 保留给可能有调用方的签名（1.12.2 无 GuiScreen 参数版本） */
    @Deprecated
    public static boolean isTypingNow(@Nullable Object ignored) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen instanceof GuiChat;
    }
}
