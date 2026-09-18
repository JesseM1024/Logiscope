package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.Analyzer;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.collect.ContainerAccess;
import com.jessetech.logiscope.collect.Sampler;
import com.jessetech.logiscope.collect.WorldProbe;
import com.jessetech.logiscope.store.Sample;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 呈现层：示波器面板。
 *
 * ★ 它是一个"可以拖动、不遮挡原界面"的浮层，不是一个全屏界面 ★
 *
 * 实现方式是 NeoForge 的 GUI 图层栈：
 *   ClientHooks.pushGuiLayer(mc, this)   —— 把当前容器界面压进栈里当背景，自己当顶层
 *   ClientHooks.popGuiLayer(mc)          —— 弹回容器界面
 *
 * 核实要点（这一条在本项目文档里曾被写错，见 docs/ARCHITECTURE.md 第四节）：
 * ClientHooks 里的 guiLayers 是货真价实的 {@code Stack<Screen>}，
 * 源码注释写着 "Contains the *extra* GUI layers. The current top layer stays in
 * Minecraft#currentScreen, and the rest serve as a background for it."
 * 渲染时 ClientHooks#drawScreen 会先把栈里的每一层画一遍（鼠标坐标传 MAX_VALUE），
 * 再画最顶层 —— 所以被压住的容器界面**仍然会被完整绘制**，这才是"不覆盖"的来源。
 *
 * 两个模式：
 *   浮层模式  parent != null  压在容器界面上，关掉面板会还原容器界面
 *   独立模式  parent == null  没有容器界面可压（例如关掉界面后在跟踪），直接当普通界面开
 *
 * 它【只读】Sampler / SampleStore，不修改任何采样状态。
 * 渲染只用原版 GuiGraphics#fill + drawString，不引入任何图表库，避免依赖地狱。
 */
public final class ScopeScreen extends Screen {

    private static final int PANEL_W = 272;
    private static final int PANEL_H = 190;
    private static final int TITLE_H = 14;
    private static final int ROW_H = 11;
    private static final int BTN_H = 13;
    private static final int BTN_GAP = 4;

    /** 面板位置跨"关闭-重开"保持住，默认放在屏幕右上角，避开居中的容器界面 */
    private static int panelX = Integer.MIN_VALUE;
    private static int panelY = Integer.MIN_VALUE;

    @Nullable
    private final Screen parent;
    private final boolean overlayMode;

    private boolean dragging;
    private int dragOffX;
    private int dragOffY;
    private int scrollRow;

    /** 本帧鼠标悬停到的「背景容器槽位」。render 开头刷新，末尾用它画悬浮提示。 */
    @Nullable
    private Slot backgroundHoveredSlot;

    private List<ContainerAccess.SlotView> slots = List.of();
    private String flash = "";
    private long flashUntil;

    public ScopeScreen(@Nullable Screen parent) {
        super(Component.translatable("logiscope.title"));
        this.parent = parent;
        this.overlayMode = parent != null;
    }

    // ------------------------------------------------------------------
    // 打开 / 关闭
    // ------------------------------------------------------------------

    /** 压在当前容器界面上打开 */
    public static void openOverlay(Minecraft mc) {
        ScopeScreen scope = new ScopeScreen(mc.screen);
        ClientHooks.pushGuiLayer(mc, scope);
        // ★ pushGuiLayer 不会替你释放鼠标（那是 Minecraft#setScreen 干的活），
        //   不手动释放的话界面上根本看不到光标，面板就没法点、没法拖。
        mc.mouseHandler.releaseMouse();
        KeyMapping.releaseAll();
    }

    /** 没有容器界面可压时，当普通界面打开（例如关掉界面后只看波形） */
    public static void openStandalone(Minecraft mc) {
        mc.setScreen(new ScopeScreen(null));
    }

    /** 关闭面板：浮层模式弹栈还原容器界面，独立模式回到游戏 */
    public void closeScope() {
        Minecraft mc = Minecraft.getInstance();
        if (overlayMode) {
            ClientHooks.popGuiLayer(mc);
        } else {
            mc.setScreen(null);
        }
    }

    @Override
    public void onClose() {
        closeScope();
    }

    @Override
    protected void init() {
        if (panelX == Integer.MIN_VALUE) {
            panelX = Math.max(2, this.width - PANEL_W - 6);
            panelY = 6;
        }
        // ★ 每次打开都做一次"收回屏幕内"★
        // 拖动时是允许把面板推出屏幕外的（大 GUI scale 下很有用，见 clampDuringDrag），
        // 但那可能让它正好压在别的按钮底下再也点不到。所以收起再打开就是一次救援：
        // 只要越界了就贴回屏幕边缘。
        snapInsideOnOpen();
        refreshSlots();

        Sampler sampler = LogiScopeMod.sampler();
        if (sampler.target() == null) {
            sampler.arm();
        }
    }

    /** 单机也不暂停游戏 —— 否则 tick 停了，采样也就停了 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        refreshSlots();
    }

    private void refreshSlots() {
        this.slots = ContainerAccess.snapshot();
    }

    /**
     * 拖动中的边界：**允许把面板推出屏幕左右和下方**，但必须留一段能抓住的"拖动条"。
     *
     * 为什么要允许推出去：GUI scale 调大时逻辑分辨率很小（可能只有 320 宽），
     * 而面板固定 272 宽 —— 不推出去就永远挡着容器界面。
     *
     * 留多少：
     *   · 左右 —— 至少 width/8（最少 8px）留在屏幕内
     *   · 下方 —— 只留标题栏的高度，也就是只剩那一条"拖动条"
     *   · 上方 —— 保持贴顶，不允许推到屏幕上方（拖上去没有实用价值）
     */
    private void clampDuringDrag() {
        int marginX = Math.max(8, this.width / 8);
        int minX = -(PANEL_W - marginX);
        int maxX = this.width - marginX;
        panelX = Math.max(minX, Math.min(panelX, maxX));

        int maxY = this.height - TITLE_H;
        panelY = Math.max(0, Math.min(panelY, maxY));
    }

    /** 打开时把面板收回屏幕内（完全可见），越界就贴边 */
    private void snapInsideOnOpen() {
        int maxX = Math.max(0, this.width - PANEL_W);
        int maxY = Math.max(0, this.height - PANEL_H);
        panelX = Math.max(0, Math.min(panelX, maxX));
        panelY = Math.max(0, Math.min(panelY, maxY));
    }

    /** 被压住的容器界面（浮层模式下才有） */
    @Nullable
    private AbstractContainerScreen<?> backgroundContainer() {
        return parent instanceof AbstractContainerScreen<?> container ? container : null;
    }

    /** 鼠标是不是落在面板上。落在外面的就一律让给背景容器界面。 */
    private boolean overPanel(double mouseX, double mouseY) {
        return isIn((int) mouseX, (int) mouseY, panelX, panelY, PANEL_W, PANEL_H);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Sampler sampler = LogiScopeMod.sampler();
        List<Btn> buttons = layoutButtons();

        int x = panelX;
        int y = panelY;

        // 先补画背景容器界面的 hover 高亮，并记下命中的槽位。
        // 为什么必须自己画：背景图层是由 ClientHooks#drawScreen 渲染的，
        // 它给背景层传的鼠标坐标是 Integer.MAX_VALUE（为了不让背景层的控件误高亮），
        // 而 AbstractContainerScreen 恰恰是在 render 里用这两个坐标算出 hoveredSlot 的
        // → 背景层的 hoveredSlot 永远是 null，格子不会亮、悬浮提示也不会出。
        // 我们在最顶层，手里有真实鼠标坐标，所以自己补上。
        renderBackgroundHover(g, mouseX, mouseY);

        // 只有面板本身，不再整屏压黑 —— 被压住的容器界面要能看见
        g.fill(x + 2, y + 3, x + PANEL_W + 3, y + PANEL_H + 4, 0x66000000);
        g.fill(x - 1, y - 1, x + PANEL_W + 1, y + PANEL_H + 1, 0xFF5A5A68);
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0xF2101017);

        // 标题栏 = 拖动把手
        boolean overTitle = isIn(mouseX, mouseY, x, y, PANEL_W, TITLE_H);
        g.fill(x, y, x + PANEL_W, y + TITLE_H, dragging ? 0xFF3B3B55 : (overTitle ? 0xFF2C2C3E : 0xFF22222E));
        g.fill(x, y + TITLE_H - 1, x + PANEL_W, y + TITLE_H, 0xFF5A5A68);
        g.drawString(this.font, "LogiScope", x + 4, y + 4, 0xFFE6E6F0);

        if (dragging || overTitle) {
            String hint = tr("logiscope.hint.drag");
            g.drawString(this.font, hint, x + 62, y + 4, 0xFF8A8AA0);
        }

        String status = statusText(sampler);
        g.drawString(this.font, status, x + PANEL_W - 4 - this.font.width(status), y + 4, statusColor(sampler));

        int contentTop = y + TITLE_H + 3;
        int buttonsTop = buttons.isEmpty() ? y + PANEL_H - 4 : buttons.get(0).y() - 4;

        if (sampler.target() == null) {
            renderSlotPicker(g, mouseX, mouseY, contentTop, buttonsTop);
        } else {
            renderProbe(g, sampler, contentTop, buttonsTop);
        }

        renderButtons(g, buttons, mouseX, mouseY);
        renderFlash(g, y);

        // 最后两样必须压在最上层，和 vanilla 的顺序一致：
        // 手持物品永远跟着光标可见，悬浮提示在它下面。
        renderCarriedItem(g, mouseX, mouseY);
        renderBackgroundTooltip(g, mouseX, mouseY);
    }

    /**
     * 自己给被压住的容器界面补 hover 高亮与悬浮提示，见 render 里的说明。
     * 顺便把命中的槽位记在 backgroundHoveredSlot，供末尾画 tooltip 用。
     *
     * 能用公开 API 拿到全部需要的信息（都已核实）：
     *   AbstractContainerScreen#getGuiLeft() / getGuiTop() / getSlotColor(int)
     *   Slot#x / Slot#y / isActive() / isHighlightable()
     *   AbstractContainerScreen#renderSlotHighlight(GuiGraphics,int,int,int,int)（public static）
     */
    private void renderBackgroundHover(GuiGraphics g, int mouseX, int mouseY) {
        backgroundHoveredSlot = null;
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null || overPanel(mouseX, mouseY)) {
            return;
        }
        int left = bg.getGuiLeft();
        int top = bg.getGuiTop();
        for (Slot slot : bg.getMenu().slots) {
            // 与 vanilla 的判定保持一致：isActive 决定"能不能交互"，
            // isHighlightable 决定"要不要画高亮"（部分模组用假槽位占位，两样都要看）
            if (!slot.isActive() || !slot.isHighlightable()) {
                continue;
            }
            int sx = left + slot.x;
            int sy = top + slot.y;
            if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) {
                backgroundHoveredSlot = slot;
                AbstractContainerScreen.renderSlotHighlight(g, sx, sy, 0, bg.getSlotColor(slot.index));
                return;
            }
        }
    }

    /**
     * 自己把「鼠标手持的物品」画到真实鼠标位置上。
     *
     * ★ 为什么必须自己画 ★
     * 背景容器界面是用 `Integer.MAX_VALUE` 当鼠标坐标渲染的，
     * 于是 `AbstractContainerScreen.render` 末尾那段"把 carried item 画在光标旁边"
     * 的代码把它画到了屏幕外 —— 表现就是第三轮 B7/B8 反馈的
     * "东西确实拿起来了，但跟着鼠标的图标和数字不见了"。
     *
     * 这段复刻 vanilla 的 `renderFloatingItem`（**它是 private，只能照着写**）：
     *   pose.translate(0,0,232) → renderItem(stack,x,y) → renderItemDecorations(font,stack,x,y)
     * 位置取 `(mouseX-8, mouseY-8)`；vanilla 是在平移了 `(leftPos,topPos)` 的 pose 里
     * 传 `(mouseX-leftPos-8, mouseY-topPos-8)`，两者等价。
     *
     * 已知差异：vanilla 在做「拖拽逐格分配」时会把数字画成剩余待分配量
     * （`quickCraftingRemainder`，private，我们读不到），这里只能画完整数量。
     * 只影响按住左键拖过一排格子的那一瞬间显示的数字。
     */
    private void renderCarriedItem(GuiGraphics g, int mouseX, int mouseY) {
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null) {
            return;
        }
        ItemStack carried = bg.getMenu().getCarried();
        if (carried.isEmpty()) {
            return;
        }
        int x = mouseX - 8;
        int y = mouseY - 8;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 232.0F);
        g.renderItem(carried, x, y);
        Font countFont = IClientItemExtensions.of(carried)
                .getFont(carried, IClientItemExtensions.FontContext.ITEM_COUNT);
        g.renderItemDecorations(countFont == null ? this.font : countFont, carried, x, y);
        g.pose().popPose();
    }

    /**
     * 被压下容器的槽位悬浮提示。
     * 同样是因为 `hoveredSlot` 在背景层恒为 null，vanilla 的 `renderTooltip` 也不干活。
     * 手持物品时按 vanilla 的规矩不显示。
     */
    private void renderBackgroundTooltip(GuiGraphics g, int mouseX, int mouseY) {
        Slot slot = backgroundHoveredSlot;
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (slot == null || bg == null || !slot.hasItem()) {
            return;
        }
        if (!bg.getMenu().getCarried().isEmpty()) {
            return;
        }
        g.renderTooltip(this.font, slot.getItem(), mouseX, mouseY);
    }

    /** 未接探头：列出当前容器的所有槽位供选择 */
    private void renderSlotPicker(GuiGraphics g, int mouseX, int mouseY, int top, int bottom) {
        int x = panelX;

        if (slots.isEmpty()) {
            g.drawString(this.font, tr("logiscope.hint.no_container"), x + 6, top, 0xAAA6A0);
            return;
        }

        g.drawString(this.font, tr("logiscope.hint.pick_slot"), x + 6, top, 0xAAAAAA);
        int listTop = top + 13;
        int maxRows = Math.max(1, (bottom - listTop - 10) / ROW_H);
        int total = slots.size();
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, total - maxRows)));

        for (int i = 0; i < maxRows; i++) {
            int idx = scrollRow + i;
            if (idx >= total) {
                break;
            }
            ContainerAccess.SlotView v = slots.get(idx);
            int ry = listTop + i * ROW_H;
            if (isIn(mouseX, mouseY, x + 4, ry - 1, PANEL_W - 8, ROW_H)) {
                g.fill(x + 4, ry - 1, x + PANEL_W - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            String label = "#" + v.containerSlotIndex()
                    + "  " + (v.empty() ? tr("logiscope.slot.empty") : v.shortName() + " x" + v.count());
            g.drawString(this.font, label, x + 8, ry, v.empty() ? 0x707070 : 0xE0E0E0);
        }

        if (total > maxRows) {
            String more = (scrollRow + maxRows) + " / " + total + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.font, more, x + 6, listTop + maxRows * ROW_H + 1, 0x707070);
        }
    }

    /** 已接探头：实时值 + 统计摘要 + 火花线 */
    private void renderProbe(GuiGraphics g, Sampler sampler, int top, int bottom) {
        ProbeTarget target = sampler.target();
        List<Sample> samples = sampler.store().toList();
        Analyzer.Summary sum = Analyzer.summarize(samples);

        int x = panelX;
        int y = top;
        g.drawString(this.font, "probe  " + target.describe(), x + 6, y, 0xBFBFBF);
        y += 11;

        String nowText = currentValueText(sampler, target, samples);
        g.drawString(this.font, "now    " + nowText, x + 6, y, 0x9BE1CB);
        y += 11;

        String counts = "samples " + sum.dataCount()
                + "   no-data " + sum.noDataCount()
                + "   tick " + sampler.currentTick();
        g.drawString(this.font, counts, x + 6, y, 0x9BE1CB);
        y += 11;

        g.drawString(this.font, "first " + sum.first() + "  ->  last " + sum.last()
                + "   net " + signed(sum.netChange()), x + 6, y, 0xE0E0E0);
        y += 11;
        g.drawString(this.font, "min " + sum.min() + "  max " + sum.max()
                + "  avg " + sum.average(), x + 6, y, 0xE0E0E0);
        y += 11;
        g.drawString(this.font, "type changes " + sum.typeChanges()
                + "   up " + sum.rises() + "  down " + sum.falls(), x + 6, y, 0xE0E0E0);
        y += 13;

        int chartH = bottom - y;
        if (chartH >= 12) {
            renderSparkline(g, samples, sum, x + 6, y, PANEL_W - 12, chartH);
        }
    }

    /** 面板上那行 "now"。RECORDING 直接读菜单，其余从最近一条样本取。 */
    private String currentValueText(Sampler sampler, ProbeTarget target, List<Sample> samples) {
        if (sampler.state() == Sampler.State.RECORDING) {
            ContainerAccess.SlotView now = ContainerAccess.read(target.slotIndex());
            if (now == null) {
                return "- source lost -";
            }
            return now.empty() ? tr("logiscope.slot.empty") : now.shortName() + " x" + now.count();
        }

        if (sampler.state() == Sampler.State.FOLLOWING) {
            ContainerAccess.SlotView world = WorldProbe.latest(sampler.currentTick(), 5);
            if (world != null) {
                return world.empty() ? tr("logiscope.slot.empty") : world.shortName() + " x" + world.count();
            }
            return tr("logiscope.nodata");
        }

        Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        if (last == null) {
            return "-";
        }
        return last.isNoData() ? tr("logiscope.nodata")
                : (last.count() <= 0 ? tr("logiscope.slot.empty") : last.shortName() + " x" + last.count());
    }

    /**
     * 火花线：把 count 序列压成宽度 W 的柱状图。
     * 采样点比像素多时按列取该列的最大值（峰值优先），
     * 这样尖锐的瞬时波动不会被平均掉。
     *
     * no_data 的列**什么都不画** —— 断档就该看得出来是断档。
     */
    private void renderSparkline(GuiGraphics g, List<Sample> samples, Analyzer.Summary sum,
                                 int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF1A1A22);
        g.fill(x, y + h / 2, x + w, y + h / 2 + 1, 0x24FFFFFF);
        if (samples.isEmpty() || !sum.hasData()) {
            return;
        }

        int lo = sum.min();
        int hi = sum.max();
        int span = Math.max(1, hi - lo);

        int n = samples.size();
        for (int col = 0; col < w; col++) {
            int from = (int) ((long) col * n / w);
            int to = (int) ((long) (col + 1) * n / w);
            if (to <= from) {
                to = from + 1;
            }
            if (from >= n) {
                break;
            }
            int peak = Integer.MIN_VALUE;
            for (int i = from; i < to && i < n; i++) {
                Sample s = samples.get(i);
                if (s.isNoData()) {
                    continue;
                }
                peak = Math.max(peak, s.count());
            }
            if (peak == Integer.MIN_VALUE) {
                continue;   // 这一列全是 no_data → 留白
            }
            int barH = (int) Math.round((peak - lo) / (double) span * (h - 2)) + 1;
            int top = y + h - 1 - barH;
            g.fill(x + col, top, x + col + 1, y + h - 1, 0x9BE1CB);
        }
    }

    private void renderButtons(GuiGraphics g, List<Btn> buttons, int mouseX, int mouseY) {
        for (Btn b : buttons) {
            boolean hover = b.enabled() && isIn(mouseX, mouseY, b.x(), b.y(), b.w(), b.h());
            int bg = !b.enabled() ? 0xFF1A1A22 : (hover ? 0xFF41415A : 0xFF2A2A38);
            int border = !b.enabled() ? 0xFF2A2A34 : (hover ? 0xFF8A8AC8 : 0xFF4A4A5E);
            int fg = !b.enabled() ? 0xFF6A6A78 : (hover ? 0xFFF0F0FF : 0xFFC8C8D8);
            g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), bg);
            g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + 1, border);
            g.fill(b.x(), b.y() + b.h() - 1, b.x() + b.w(), b.y() + b.h(), border);
            g.fill(b.x(), b.y(), b.x() + 1, b.y() + b.h(), border);
            g.fill(b.x() + b.w() - 1, b.y(), b.x() + b.w(), b.y() + b.h(), border);
            int tw = this.font.width(b.label());
            g.drawString(this.font, b.label(), b.x() + (b.w() - tw) / 2, b.y() + (b.h() - 8) / 2 + 1, fg);
        }
    }

    /** 面板上/下方的一句临时提示（"已清空"这类操作反馈） */
    private void renderFlash(GuiGraphics g, int panelTop) {
        if (flash.isEmpty() || System.currentTimeMillis() > flashUntil) {
            return;
        }
        int w = this.font.width(flash);
        int boxW = w + 8;
        int x = panelX + (PANEL_W - boxW) / 2;
        // 面板贴着屏幕顶时（默认位置就是右上角），把提示挪到面板下方，别画到屏幕外
        int boxY = panelTop - 13 >= 0 ? panelTop - 13 : panelTop + PANEL_H + 2;
        g.fill(x, boxY, x + boxW, boxY + 12, 0xE0202030);
        g.drawString(this.font, flash, x + 4, boxY + 2, 0xFFE6E6F0);
    }

    private void say(String text) {
        this.flash = text;
        this.flashUntil = System.currentTimeMillis() + 1800L;
    }

    // ------------------------------------------------------------------
    // 按钮布局
    // ------------------------------------------------------------------

    private record Item(String id, String label, boolean enabled) {
    }

    private record Btn(String id, String label, int x, int y, int w, int h, boolean enabled) {

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private List<Item> buttonItems() {
        Sampler sampler = LogiScopeMod.sampler();
        List<Item> out = new ArrayList<>();

        if (sampler.target() == null) {
            out.add(new Item("close", tr("logiscope.btn.close"), true));
            out.add(new Item("follow", followLabel(), true));
            return out;
        }

        boolean running = sampler.state() == Sampler.State.RECORDING
                || sampler.state() == Sampler.State.FOLLOWING;

        // 没有容器界面在开的时候，"返回选格"会让人无格可选 —— 直接禁掉，别挖坑
        out.add(new Item("back", tr("logiscope.btn.back"), ContainerAccess.hasForeignContainerOpen()));
        out.add(new Item("pause", tr("logiscope.btn.pause"), running));
        out.add(new Item("resume", tr("logiscope.btn.resume"), sampler.state() == Sampler.State.PAUSED));
        out.add(new Item("clear", tr("logiscope.btn.clear"), sampler.store().size() > 0));
        out.add(new Item("follow", followLabel(), true));
        return out;
    }

    private String followLabel() {
        return LogiScopeMod.sampler().followAfterClose()
                ? tr("logiscope.btn.follow_on")
                : tr("logiscope.btn.follow_off");
    }

    private List<Btn> layoutButtons() {
        List<Item> items = buttonItems();
        if (items.isEmpty()) {
            return List.of();
        }

        int avail = PANEL_W - 8;
        List<List<Item>> rows = new ArrayList<>();
        List<Item> row = new ArrayList<>();
        int used = 0;
        for (Item it : items) {
            int w = buttonWidth(it.label());
            if (!row.isEmpty() && used + BTN_GAP + w > avail) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : BTN_GAP) + w;
            row.add(it);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }

        int totalH = rows.size() * BTN_H + (rows.size() - 1) * 2;
        int y = panelY + PANEL_H - 5 - totalH;

        List<Btn> out = new ArrayList<>();
        for (List<Item> r : rows) {
            int x = panelX + 4;
            for (Item it : r) {
                int w = buttonWidth(it.label());
                out.add(new Btn(it.id(), it.label(), x, y, w, BTN_H, it.enabled()));
                x += w + BTN_GAP;
            }
            y += BTN_H + 2;
        }
        return out;
    }

    private int buttonWidth(String label) {
        return Math.max(44, this.font.width(label) + 12);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ★ 面板之外一律让给被压住的容器界面 ★
        // 这样"面板打开时机器还能操作"，也是以后做图形化拖动/框选待测格的基础。
        // AbstractContainerScreen#mouseClicked 内部用 findSlot(mouseX, mouseY) 找格子，
        // 坐标是现算的，所以不依赖 hoveredSlot（背景层的 hoveredSlot 恰好是 null）。
        if (!overPanel(mouseX, mouseY)) {
            AbstractContainerScreen<?> bg = backgroundContainer();
            return bg != null && bg.mouseClicked(mouseX, mouseY, button);
        }

        if (button != 0) {
            return false;
        }

        int x = panelX;
        int y = panelY;

        // 1) 标题栏 → 开始拖动
        if (isIn((int) mouseX, (int) mouseY, x, y, PANEL_W, TITLE_H)) {
            dragging = true;
            dragOffX = (int) mouseX - x;
            dragOffY = (int) mouseY - y;
            return true;
        }

        // 2) 按钮
        for (Btn b : layoutButtons()) {
            if (b.enabled() && b.hit(mouseX, mouseY)) {
                onButton(b.id());
                return true;
            }
        }

        // 3) 槽位列表（只有未接探头时）
        Sampler sampler = LogiScopeMod.sampler();
        if (sampler.target() == null && !slots.isEmpty()) {
            int listTop = y + TITLE_H + 3 + 13;
            int buttonsTop = layoutButtonsTop();
            int maxRows = Math.max(1, (buttonsTop - 4 - listTop - 10) / ROW_H);
            for (int i = 0; i < maxRows; i++) {
                int idx = scrollRow + i;
                if (idx >= slots.size()) {
                    break;
                }
                int ry = listTop + i * ROW_H;
                if (isIn((int) mouseX, (int) mouseY, x + 4, ry - 1, PANEL_W - 8, ROW_H)) {
                    attachSlot(slots.get(idx), sampler);
                    return true;
                }
            }
        }

        // 点在面板上但没打中任何控件：吃掉，别透传给底下
        return true;
    }

    private int layoutButtonsTop() {
        List<Btn> buttons = layoutButtons();
        return buttons.isEmpty() ? panelY + PANEL_H - 5 : buttons.get(0).y();
    }

    private void attachSlot(ContainerAccess.SlotView view, Sampler sampler) {
        // 持久身份（维度 + 坐标）来自"玩家右键的那个方块"，见 ContainerAnchor。
        // 拿不到就退化成纯运行时身份：关掉界面只能暂停，不会瞎记。
        ContainerAnchor.Anchor anchor = ContainerAnchor.activeFor(ContainerAccess.currentContainerId());
        sampler.attach(new ProbeTarget(
                view.menuSlotIndex(),
                view.containerSlotIndex(),
                view.itemId(),
                anchor == null ? null : anchor.dimension(),
                anchor == null ? null : anchor.pos()));
        say(anchor == null ? tr("logiscope.flash.runtime_only") : tr("logiscope.flash.attached"));
    }

    private void onButton(String id) {
        Sampler sampler = LogiScopeMod.sampler();
        switch (id) {
            case "back" -> {
                sampler.backToPicker();
                refreshSlots();
                say(tr("logiscope.flash.detached"));
            }
            case "pause" -> {
                sampler.pause();
                say(tr("logiscope.flash.paused"));
            }
            case "resume" -> {
                sampler.resume();
                say(sampler.state() == Sampler.State.PAUSED
                        ? tr("logiscope.flash.resume_failed")
                        : tr("logiscope.flash.resumed"));
            }
            case "clear" -> {
                sampler.clearData();
                say(tr("logiscope.flash.cleared"));
            }
            case "follow" -> {
                sampler.setFollowAfterClose(!sampler.followAfterClose());
                say(followLabel());
            }
            case "close" -> closeScope();
            default -> {
            }
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            panelX = (int) mouseX - dragOffX;
            panelY = (int) mouseY - dragOffY;
            clampDuringDrag();
            return true;
        }
        // 面板外：拖拽也是容器的交互（原版用它做快速合成/批量摆放），要转交
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        // 松手同样要转交，否则容器的拖拽状态会卡住（AbstractContainerScreen 里
        // 用 mouseReleased 收尾 quick-craft）
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overPanel(mouseX, mouseY)
                && LogiScopeMod.sampler().target() == null
                && !slots.isEmpty()) {
            scrollRow -= (int) Math.signum(scrollY);
            scrollRow = Math.max(0, scrollRow);
            return true;
        }
        // 面板外滚轮让给容器（部分模组的容器界面用滚轮翻页）
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean isIn(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static String statusText(Sampler sampler) {
        String key = switch (sampler.state()) {
            case IDLE -> "logiscope.status.idle";
            case ARMED -> "logiscope.status.armed";
            case RECORDING -> "logiscope.status.recording";
            case FOLLOWING -> "logiscope.status.following";
            case PAUSED -> sampler.isManuallyPaused()
                    ? "logiscope.status.paused"
                    : "logiscope.status.paused_auto";
        };
        return "[ " + tr(key) + " ]";
    }

    private static int statusColor(Sampler sampler) {
        return switch (sampler.state()) {
            case RECORDING -> 0x5DCAA5;
            case FOLLOWING -> 0x6FB6E8;
            case PAUSED -> 0xEF9F27;
            case ARMED -> 0x85B7EB;
            case IDLE -> 0x888780;
        };
    }
}
