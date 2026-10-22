package com.jessetech.logiscope.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * 输入层：浮层面板的**鼠标 / 键盘 / 每 tick 驱动**。
 *
 * 【为什么需要它 —— 1.12.2 的"真图层"】
 * `openOverlay` **不换 `mc.currentScreen`**（原因见那里：AE 的网络更新包只投递给
 * "当前界面是 `GuiMEMonitorable`"的客户端）。父界面继续当 currentScreen，
 * 于是输入得从 Forge 的 GUI 事件里拦：
 *
 * | 事件 | 我们怎么用 |
 * |---|---|
 * | {@code GuiScreenEvent.MouseInputEvent.Pre} | 光标落在面板上 → 转给面板并**吃掉**；落在面板外 → 放行给容器 |
 * | {@code GuiScreenEvent.KeyboardInputEvent.Pre} | 有输入框聚焦 / 面板要用这个键 → 吃掉；否则放行 |
 * | {@code TickEvent.ClientTickEvent} | 浮层模式下由这里驱动面板的 `updateScreen`（我们不是 currentScreen，vanilla 不会 tick 我们） |
 *
 * 【"吃掉"= 取消事件，**不要自己调 `next()`**】
 * 1.12.2 的 `GuiScreen#handleInput()`（javap 核实）长这样：
 * <pre>
 * while (Mouse.next()) {                                  // ← 队列由它推进
 *     this.mouseHandled = false;
 *     if (EVENT_BUS.post(new MouseInputEvent.Pre(this))) continue;   // ← 取消 = 跳过**这一条**
 *     this.handleMouseInput();                            // ← 只处理当前这一条
 *     ...
 * }
 * while (Keyboard.next()) {
 *     if (EVENT_BUS.post(new KeyboardInputEvent.Pre(this))) continue;
 *     this.handleKeyboardInput();
 * }
 * </pre>
 * 也就是说：事件是**逐条**发的，取消一条只是 `continue` 到下一 条 —— 队列照常推进。
 * 所以这里**绝不能**再调 `Mouse.next()/Keyboard.next()`（那会白白吞掉下一条事件）。
 * 这是 1.12.2 与"每个 tick 发一次的事件"最大的区别，踩过一次。
 *
 * 顺带修好的老限制：面板外的点击/滚轮现在**能正常转交给容器**了
 * （以前换屏幕的写法下，`GuiContainer#mouseClicked` 是 protected、转交不了）。
 */
public final class PanelInput {

    private static boolean registered;

    /** 上一次被面板吃掉的鼠标键（LWJGL 的"移动"事件里按钮号是 -1，拖拽要靠它） */
    private static int heldButton = -1;

    private PanelInput() {
    }

    static void ensureRegistered() {
        if (registered) {
            return;
        }
        try {
            MinecraftForge.EVENT_BUS.register(PanelInput.class);
            registered = true;
        } catch (Throwable t) {
            com.jessetech.logiscope.LogiScopeMod.LOGGER.warn(
                    "[LogiScope] 面板输入层注册失败：{}", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // 每 tick
    // ------------------------------------------------------------------

    /**
     * 浮层模式下面板不是 currentScreen ⇒ vanilla 不会调它的 `updateScreen`，
     * 由这里补上（选格页会在这里拉方块 NBT / 世界槽位清单，DATA 页在这里刷新取数）。
     *
     * 同时做一件保命的事：**父界面没了就把面板一起收掉**。
     * （玩家用 E/ESC 关掉容器、或切到别处 —— 面板不能挂在一个已经死掉的界面上。）
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ScopeScreen p = ScopeScreen.activePanel();
        if (p == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != p.renderTarget()) {
            // 父界面被关掉 / 被换屏（独立模式下就是自己被人换掉）⇒ 面板跟着收，
            // 否则会留下一个点不动的残影，而且下次按 F9 会先去"关"这个死面板。
            p.closeScope();
            return;
        }
        if (!p.isOverlay()) {
            return;   // 独立模式：vanilla 已经在 tick 它
        }
        p.updateScreen();
    }

    // ------------------------------------------------------------------
    // 鼠标
    // ------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
        ScopeScreen p = ScopeScreen.activePanel();
        if (p == null || !p.isOverlay() || event.getGui() != p.renderTarget()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int mouseX = scaledX(mc, p);
        int mouseY = scaledY(mc, p);
        int button = Mouse.getEventButton();

        // ---- 按下 / 松开 ----
        if (button >= 0) {
            boolean down = Mouse.getEventButtonState();
            boolean inside = p.panelContains(mouseX, mouseY);
            // 面板外、面板没在拖东西、而且不是选格模式 → 完全放行（容器照常能点）；
            // 选格模式下必须吃掉：面板外就是容器格子，放行等于"拿起来/放下去"，选格就废了。
            if (!inside && !p.panelDragging() && !p.panelBlocksOuterClicks()) {
                return;
            }
            boolean consumed = down
                    ? p.feedMouseDown(mouseX, mouseY, button)
                    : p.feedMouseUp(mouseX, mouseY, button);
            if (!consumed && !inside && !p.panelBlocksOuterClicks()) {
                return;   // 没吃到，又在面板外 → 还是给容器
            }
            heldButton = down ? button : -1;
            event.setCanceled(true);      // 吃掉这一条（for 循环会 continue 到下一条）
            return;
        }

        // ---- 滚轮 ----
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            if (!p.panelContains(mouseX, mouseY) && !p.panelBlocksOuterClicks()) {
                return;   // 面板外滚轮交给容器（部分模组的界面用滚轮翻页）
            }
            p.feedScroll(mouseX, mouseY, wheel > 0 ? 1 : -1);
            event.setCanceled(true);
            return;
        }

        // ---- 拖拽（移动事件没有按钮号，用记住的那个）----
        if (p.panelDragging()) {
            int btn = heldButton >= 0 ? heldButton : 0;
            p.feedMouseMove(mouseX, mouseY, btn);
            event.setCanceled(true);
        }
    }

    private static int scaledX(Minecraft mc, ScopeScreen p) {
        return Mouse.getEventX() * p.width / Math.max(1, mc.displayWidth);
    }

    private static int scaledY(Minecraft mc, ScopeScreen p) {
        return p.height - Mouse.getEventY() * p.height / Math.max(1, mc.displayHeight) - 1;
    }

    // ------------------------------------------------------------------
    // 键盘
    // ------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        ScopeScreen p = ScopeScreen.activePanel();
        if (p == null || !p.isOverlay() || event.getGui() != p.renderTarget()) {
            return;
        }
        if (!Keyboard.getEventKeyState()) {
            return;   // 只处理按下；松开交给父界面（它对松开什么都不做）
        }
        int key = Keyboard.getEventKey();
        char ch = Keyboard.getEventCharacter();

        boolean consumed = false;
        if (ch != 0 && !Character.isISOControl(ch) && p.feedChar(ch)) {
            consumed = true;   // 输入框吃掉了一个字符
        } else {
            consumed = p.feedKey(key);
        }
        // 没吃到、也没有输入框在聚焦 → 放行（例如 E 关容器、方向键……）
        if (!consumed && !p.panelWantsKeys()) {
            return;
        }
        event.setCanceled(true);   // 吃掉这一条（for 循环会 continue 到下一条）
    }
}
