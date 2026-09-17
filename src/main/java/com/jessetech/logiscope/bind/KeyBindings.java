package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.ui.ScopeScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 绑定层：把「按键」翻译成「打开示波器的意图」。
 *
 * ★ 关键逻辑路径 CP2 ★
 * vanilla 的 KeyMapping 只在【没有界面打开】时才会被 tick 消费。
 * 而本模组的核心场景恰恰是「玩家的容器界面开着」——
 * 此时按键被当前 Screen 吃掉，KeyMapping 完全收不到。
 *
 * 解法：监听 ScreenEvent.KeyPressed.Pre。
 * 这个事件在 Screen 处理按键【之前】触发，且可以 cancel，
 * 因此我们既能拿到按键，又能阻止它被原界面继续处理。
 */
public final class KeyBindings {

    private KeyBindings() {
    }

    public static final String CATEGORY = "key.categories.logiscope";

    public static final KeyMapping OPEN_SCOPE = new KeyMapping(
            "logiscope.key.open_scope",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY);

    /** 由主类在模组总线上调用 */
    public static void register(IEventBus modBus) {
        modBus.addListener(KeyBindings::onRegisterKeyMappings);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SCOPE);
    }

    /**
     * ★ 关键逻辑路径 CP2 的实现 ★
     *
     * 必须是 public：主类在另一个包，要用方法引用注册到事件总线。
     */
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!OPEN_SCOPE.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // 只有在「模组设备的容器界面」打开时才响应。
        // inventoryMenu 是玩家自己的背包，不算外部设备，排除掉。
        if (mc.player.containerMenu == mc.player.inventoryMenu) {
            return;
        }

        // 已经开着示波器了就别再叠一层
        if (mc.screen instanceof ScopeScreen) {
            return;
        }

        openScope(mc);

        // 阻止按键继续传给原界面（否则可能触发原模组的快捷键）
        event.setCanceled(true);
    }

    /**
     * 打开示波器界面。
     *
     * ★ 关键逻辑路径 CP3 ★
     * 用 setScreen 替换当前界面，而不是关闭它。
     * 要点：setScreen 会调用旧 Screen 的 removed()，但【不会】调用 onClose()，
     * 因此 player.containerMenu 依然有效 —— 采样不会中断。
     * 关闭示波器时用 setScreen(parent) 还原，原设备界面就回来了。
     */
    private static void openScope(Minecraft mc) {
        Screen parent = mc.screen;
        LogiScopeMod.LOGGER.debug("[LogiScope] opening scope over {}", parent == null ? "null" : parent.getClass().getSimpleName());
        mc.setScreen(new ScopeScreen(parent));
    }
}
