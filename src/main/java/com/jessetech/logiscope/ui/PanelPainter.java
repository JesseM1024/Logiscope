package com.jessetech.logiscope.ui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 渲染层：把面板**推迟到 `GuiScreenEvent.DrawScreenEvent.Post`** 再画。
 *
 * 【为什么必须这样 —— 1.12.2 的覆盖层顺序】
 * 实测（`javap` 核实 {@code ForgeHooksClient#drawScreen}）：
 *
 * <pre>
 * DrawScreenEvent.Pre  →  screen.drawScreen(...)  →  DrawScreenEvent.Post
 * </pre>
 *
 * 而 JEI 这类"物品列表覆盖层"正是挂在 **Post** 上画的。
 * 我们在 `ScopeScreen#drawScreen` 里画面板 ⇒ 面板先画、JEI 后画 ⇒
 * **物品列表压在面板之上**（用户 2026-10-22 的截图：选格页右侧整片物品盖住列表行）。
 * 这不是深度/Z 序问题，是**先后顺序**问题 —— 光调 z 治不了。
 *
 * 【怎么保证排在其他模组之后】
 * Forge 1.12.2 的事件总线按**注册顺序**派发。我们在**第一次打开面板时**才注册本监听器
 * （其余模组都在启动阶段注册完了）⇒ 我们被追加到列表末尾 ⇒ **最后执行** ⇒ 面板在最上层。
 * 注册动作发生在按键/开屏路径（客户端 tick）里，注册的是 `DrawScreenEvent` 的监听器，
 * 与当时正在派发的 tick 事件不是同一个列表，不会触发并发修改。
 *
 * 代价：面板晚一帧都不行 —— `drawScreen` 只负责"替父界面作画 + 准备数据"，
 * 面板的每个像素都由本类回调的 {@link ScopeScreen#renderPanel} 画。两处必须在同一帧内配对。
 */
public final class PanelPainter {

    private static boolean registered;

    private PanelPainter() {
    }

    /** 由 {@link ScopeScreen#openOverlay} / {@link ScopeScreen#openStandalone} 调一次 */
    static void ensureRegistered() {
        if (registered) {
            return;
        }
        try {
            MinecraftForge.EVENT_BUS.register(PanelPainter.class);
            registered = true;
        } catch (Throwable t) {
            // 注册失败（极端情况：总线正在派发）→ registered 保持 false，
            // 于是 ScopeScreen 会退回"在 drawScreen 里直接画"的老路径，面板不会消失。
            com.jessetech.logiscope.LogiScopeMod.LOGGER.warn(
                    "[LogiScope] 面板延后绘制注册失败，退回即时绘制：{}", t.toString());
        }
    }

    /** 监听器是否已就位（未就位时由 ScopeScreen 自己画，见 {@link #ensureRegistered}） */
    static boolean isReady() {
        return registered;
    }

    /**
     * ★ `priority = LOWEST`：这是"面板永远在最上层"的**保证**，不是可选项。
     *
     * Forge 按**优先级**派发（同优先级才轮到注册顺序），JEI 等模组用的都是默认的
     * `NORMAL` ⇒ 我们用 `LOWEST` 就一定排在它们之后。
     * 原先只靠"我们注册得晚"，实测会被反超（选格页整片物品/灰块压住面板）——
     * 而且我们 `endPanel()` 之后若还有别人要画，他们还会继承我们改过的 GL 状态。
     * 用 LOWEST 之后这两件事一起解决：面板是最后一个画的。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        ScopeScreen panel = ScopeScreen.activePanel();
        if (panel == null) {
            return;
        }
        // 浮层模式挂在**父界面**的 Post 上（父界面才是 currentScreen），
        // 独立模式挂在自己身上 —— 由 ScopeScreen#renderTarget 统一回答。
        if (event.getGui() != panel.renderTarget()) {
            return;
        }
        panel.renderPanel(event.getMouseX(), event.getMouseY(), event.getRenderPartialTicks());
    }
}
