package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.Analyzer;
import com.jessetech.logiscope.analysis.ChartData;
import com.jessetech.logiscope.analysis.Series;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 呈现层：示波器面板。
 *
 * ★ 它是一个"可以拖动、可以缩放、不遮挡原界面"的浮层，不是一个全屏界面 ★
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
 * 【本类只做三件事】
 *   1. 布局：把面板切成标题 / 信息 / 工具栏 / 波形 / 图例 / 按钮几块
 *   2. 交互：鼠标命中、拖动、缩放、框选、图例开关
 *   3. 转交：面板之外的鼠标事件交给被压住的容器界面
 * 画波形的事全在 ChartRenderer 里；布局与命中判定共用 layout() 一份结果，
 * 避免"渲染按一套坐标、点击按另一套坐标"这类最难查的错位 bug。
 */
public final class ScopeScreen extends Screen {

    // ==================================================================
    // 面板几何（跨"关闭-重开"保持 —— 这是使用偏好，不是数据）
    // ==================================================================

    private static final int DEFAULT_W = 372;
    private static final int DEFAULT_H = 258;
    /** 最小尺寸压到这个 —— 再小波形就没法看了（会显示"面板太小"的提示） */
    private static final int MIN_W = 250;
    private static final int MIN_H = 168;

    private static int panelX = Integer.MIN_VALUE;
    private static int panelY = Integer.MIN_VALUE;
    private static int panelW = DEFAULT_W;
    private static int panelH = DEFAULT_H;

    /** 可见窗口宽度（tick）。默认 400 tick = 20 秒，是看"产线节奏"比较舒服的尺度 */
    private static int windowTicks = 400;
    private static final int MIN_WINDOW = 40;
    private static final int MAX_WINDOW = 20 * 60 * 30;

    /** 图例里被关掉的物品线。跨开合保持：它是"看图的偏好"，不是数据 */
    private static final Set<String> hiddenItems = new HashSet<>();

    // ==================================================================
    // 布局常量
    // ==================================================================

    private static final int TITLE_H = 14;
    private static final int ROW_H = 11;
    private static final int CHIP_H = 12;
    private static final int CHIP_GAP = 3;
    private static final int PAD = 5;
    private static final int RESIZE_GRIP = 12;
    private static final int MAX_LEGEND_ROWS = 3;
    /** 波形区低于这个高度就不画了 —— 画出来的东西没有信息量，只会误导 */
    private static final int MIN_CHART_H = 44;

    private static final String SERIES_PREFIX = "series:";

    // ==================================================================
    // 视图状态（每次打开重置）
    // ==================================================================

    /** 实时滚动 / 冻结。框选会自动切成冻结，见 mouseReleased */
    private boolean live = true;
    private long frozenEnd;
    @Nullable
    private ChartRenderer.Selection selection;
    private boolean selecting;
    private int selAnchorX;
    private int selCurrentX;
    /** 框选期间锁住的可见窗口 —— 不锁的话波形会在拖的过程中往前跑，选不准 */
    private long selectWindowStart;
    private long selectWindowEnd;

    private boolean dragging;
    private int dragOffX;
    private int dragOffY;
    private boolean resizing;
    private int resizeGripX;
    private int resizeGripY;
    private int scrollRow;

    private final ChartRenderer chart = new ChartRenderer();

    // 派生数据的缓存：只在 SampleStore 的 version 变化后重算（见 SampleStore#version）
    private ChartData chartData = ChartData.EMPTY;
    private Analyzer.Summary summary = Analyzer.Summary.EMPTY;
    private List<ChartData.SeriesRange> selectionStats = List.of();
    private long builtVersion = Long.MIN_VALUE;

    /** 本帧鼠标悬停到的「背景容器槽位」。render 开头刷新，末尾用它画悬浮提示 */
    @Nullable
    private Slot backgroundHoveredSlot;

    private List<ContainerAccess.SlotView> slots = List.of();
    private String flash = "";
    private long flashUntil;

    @Nullable
    private final Screen parent;
    private final boolean overlayMode;

    public ScopeScreen(@Nullable Screen parent) {
        super(Component.translatable("logiscope.title"));
        this.parent = parent;
        this.overlayMode = parent != null;
    }

    // ==================================================================
    // 打开 / 关闭
    // ==================================================================

    /** 压在当前容器界面上打开 */
    public static void openOverlay(Minecraft mc) {
        ScopeScreen scope = new ScopeScreen(mc.screen);
        ClientHooks.pushGuiLayer(mc, scope);
        // ★ pushGuiLayer 不会替你释放鼠标（那是 Minecraft#setScreen 干的活），
        //   不手动释放的话界面上根本看不到光标，面板就没法点、没法拖、没法框选。
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
            panelX = Math.max(2, this.width - panelW - 6);
            panelY = 6;
        }
        // ★ 每次打开都做一次"收回屏幕内"★
        // 拖动/缩放时是允许把面板推出屏幕外的（大 GUI scale 下有用，见 clampDuringDrag），
        // 但那可能让它正好压在别的按钮底下再也点不到。所以收起再打开就是一次救援。
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

    /** 打开时把面板收回屏幕内（完全可见），越界就贴边；尺寸也跟着夹一下 */
    private void snapInsideOnOpen() {
        panelW = Math.max(MIN_W, Math.min(panelW, Math.max(MIN_W, this.width - 4)));
        panelH = Math.max(MIN_H, Math.min(panelH, Math.max(MIN_H, this.height - 4)));
        panelX = Math.max(0, Math.min(panelX, Math.max(0, this.width - panelW)));
        panelY = Math.max(0, Math.min(panelY, Math.max(0, this.height - panelH)));
    }

    /**
     * 拖动中的边界：**允许把面板推出屏幕左右和下方**，但必须留一段能抓住的"拖动条"。
     * 为什么要允许推出去：GUI scale 调大时逻辑分辨率很小（可能只有 320 宽），
     * 不推出去就永远挡着容器界面。留多少：左右至少 width/8（最少 8px），下方只留标题栏。
     */
    private void clampDuringDrag() {
        int marginX = Math.max(8, this.width / 8);
        panelX = Math.max(-(panelW - marginX), Math.min(panelX, this.width - marginX));
        panelY = Math.max(0, Math.min(panelY, this.height - TITLE_H));
    }

    @Nullable
    private AbstractContainerScreen<?> backgroundContainer() {
        return parent instanceof AbstractContainerScreen<?> container ? container : null;
    }

    // ==================================================================
    // 派生数据（每 tick 最多重算一次）
    // ==================================================================

    private void rebuildIfNeeded(Sampler sampler) {
        long v = sampler.store().version();
        if (v == builtVersion) {
            return;
        }
        builtVersion = v;
        List<Sample> samples = sampler.store().toList();
        chartData = ChartData.build(v, samples);
        summary = Analyzer.summarize(samples);
        pruneHidden();
        refreshSelectionStats();
    }

    /**
     * 丢掉"已经不存在的数据线"的隐藏记录。
     * 不做的话，某个物品消失又出现时会莫名其妙地保持隐藏，玩家找不到原因。
     */
    private void pruneHidden() {
        if (hiddenItems.isEmpty()) {
            return;
        }
        for (String id : List.copyOf(hiddenItems)) {
            if (chartData.byItem(id) == null) {
                hiddenItems.remove(id);
            }
        }
    }

    private void refreshSelectionStats() {
        selectionStats = selection == null ? List.of() : chartData.ranges(selection.lo(), selection.hi());
    }

    // ==================================================================
    // 布局：渲染与命中判定共用这一份
    // ==================================================================

    private record Item(String id, String label, boolean enabled) {
    }

    private record Chip(String id, String label, int x, int y, int w, int h, boolean enabled, int accent) {

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        boolean hit(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Layout(
            int x, int y, int w, int h,
            int infoTop,
            int slotListTop, int slotListRows,
            int chartTop, int chartH,
            List<Chip> toolbar, List<Chip> legend, List<Chip> buttons) {

        boolean hasChart() {
            return chartH >= MIN_CHART_H;
        }
    }

    private Layout layout() {
        Sampler sampler = LogiScopeMod.sampler();
        boolean probe = sampler.target() != null;
        int cw = Math.max(40, panelW - 2 * PAD);
        int left = panelX + PAD;

        // ---- 底部按钮（贴底）----
        List<List<Item>> btnRows = wrap(buttonItems(), cw, 44, 12);
        int buttonsTop = panelY + panelH - PAD - blockHeight(btnRows);
        List<Chip> buttons = place(btnRows, left, buttonsTop, 44, 12);

        // ---- 图例（方块按钮之上）----
        List<List<Item>> legendRows = List.of();
        int legendH = 0;
        if (probe && !chartData.series().isEmpty()) {
            legendRows = capRows(wrap(legendItems(), cw, 0, 20), MAX_LEGEND_ROWS);
            legendH = blockHeight(legendRows);
        }
        int legendTop = buttonsTop - (legendH > 0 ? legendH + 3 : 0);
        List<Chip> legend = legendH > 0 ? place(legendRows, left, legendTop, 0, 20) : List.of();

        // ---- 图表工具栏 + 波形（自上而下）----
        int infoTop = panelY + TITLE_H + 2;
        int toolbarTop = infoTop + (probe ? 3 * ROW_H : ROW_H) + 1;
        List<List<Item>> tbRows = probe ? wrap(toolbarItems(), cw, 22, 10) : List.of();
        int toolbarH = blockHeight(tbRows);
        List<Chip> toolbar = place(tbRows, left, toolbarTop, 22, 10);

        int chartTop = toolbarTop + toolbarH + 2;
        int chartH = Math.max(0, legendTop - 2 - chartTop);

        // ---- 未接探头时的槽位列表 ----
        int slotListTop = infoTop + ROW_H + 4;
        int slotListRows = Math.max(1, (buttonsTop - 4 - slotListTop - 10) / ROW_H);

        return new Layout(panelX, panelY, panelW, panelH,
                infoTop, slotListTop, slotListRows,
                chartTop, chartH, toolbar, legend, buttons);
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

    /** 图表工具栏。分两行放不下时会自动折行（见 wrap） */
    private List<Item> toolbarItems() {
        List<Item> out = new ArrayList<>();
        out.add(new Item("live", live ? tr("logiscope.tool.live") : tr("logiscope.tool.frozen"), true));
        out.add(new Item("zoom_out", "-", true));
        out.add(new Item("zoom_in", "+", true));
        out.add(new Item("fit", tr("logiscope.tool.fit"), chartData.hasData()));
        out.add(new Item("clear_sel", tr("logiscope.tool.clear_sel"), selection != null));
        return out;
    }

    private List<Item> legendItems() {
        List<Item> out = new ArrayList<>();
        for (Series s : chartData.series()) {
            out.add(new Item(SERIES_PREFIX + s.itemId(),
                    ChartRenderer.label(chartData.indexOf(s.itemId()), s.itemId()), true));
        }
        return out;
    }

    private String followLabel() {
        return LogiScopeMod.sampler().followAfterClose()
                ? tr("logiscope.btn.follow_on")
                : tr("logiscope.btn.follow_off");
    }

    // ---- 折行与摆放（按钮、工具栏、图例共用）----

    private List<List<Item>> wrap(List<Item> items, int avail, int minW, int padW) {
        List<List<Item>> rows = new ArrayList<>();
        List<Item> row = new ArrayList<>();
        int used = 0;
        for (Item it : items) {
            int w = Math.max(minW, this.font.width(it.label()) + padW);
            if (!row.isEmpty() && used + CHIP_GAP + w > avail) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : CHIP_GAP) + w;
            row.add(it);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private List<Chip> place(List<List<Item>> rows, int left, int top, int minW, int padW) {
        List<Chip> out = new ArrayList<>();
        int y = top;
        for (List<Item> row : rows) {
            int x = left;
            for (Item it : row) {
                int w = Math.max(minW, this.font.width(it.label()) + padW);
                out.add(new Chip(it.id(), it.label(), x, y, w, CHIP_H, it.enabled(), accentFor(it.id())));
                x += w + CHIP_GAP;
            }
            y += CHIP_H + 2;
        }
        return out;
    }

    private static int blockHeight(List<List<Item>> rows) {
        return rows.isEmpty() ? 0 : rows.size() * CHIP_H + (rows.size() - 1) * 2;
    }

    private int accentFor(String id) {
        if ("live".equals(id)) {
            return live ? 0xFF5DCAA5 : 0xFFEF9F27;
        }
        return 0;
    }

    /** 图例行数超限时，把最后一行末尾换成 "+N" */
    private List<List<Item>> capRows(List<List<Item>> rows, int max) {
        if (rows.size() <= max) {
            return rows;
        }
        int total = 0;
        for (List<Item> r : rows) {
            total += r.size();
        }
        List<List<Item>> out = new ArrayList<>();
        int kept = 0;
        for (int i = 0; i < max - 1; i++) {
            out.add(rows.get(i));
            kept += rows.get(i).size();
        }
        List<Item> last = new ArrayList<>(rows.get(max - 1));
        if (!last.isEmpty()) {
            last.remove(last.size() - 1);
        }
        int shown = kept + last.size();
        last.add(new Item("legend_more", "+" + Math.max(0, total - shown), false));
        out.add(last);
        return out;
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Sampler sampler = LogiScopeMod.sampler();
        rebuildIfNeeded(sampler);
        Layout L = layout();
        int x = L.x();
        int y = L.y();

        // 先补画背景容器界面的 hover 高亮（原理见方法注释），并记下命中的槽位
        renderBackgroundHover(g, mouseX, mouseY, L);

        // 只有面板本身，不再整屏压黑 —— 被压住的容器界面要能看见
        g.fill(x + 2, y + 3, x + L.w() + 3, y + L.h() + 4, 0x66000000);
        g.fill(x - 1, y - 1, x + L.w() + 1, y + L.h() + 1, 0xFF5A5A68);
        g.fill(x, y, x + L.w(), y + L.h(), 0xF2101017);

        renderTitle(g, L, sampler, mouseX, mouseY);
        renderInfo(g, L, sampler);

        if (sampler.target() == null) {
            renderSlotPicker(g, L, mouseX, mouseY);
        } else {
            if (L.hasChart()) {
                chart.render(g, this.font, chartData,
                        x + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), chartView(L, mouseX, mouseY));
            }
            renderToolbar(g, L.toolbar(), mouseX, mouseY);
            renderLegend(g, L.legend(), mouseX, mouseY);
        }

        renderButtons(g, L.buttons(), mouseX, mouseY);
        renderResizeGrip(g, L, mouseX, mouseY);
        renderFlash(g, y);

        // 最后两样必须压在最上层，和 vanilla 的顺序一致：
        // 手持物品永远跟着光标可见，悬浮提示在它下面。
        renderCarriedItem(g, mouseX, mouseY);
        renderBackgroundTooltip(g, mouseX, mouseY);
    }

    /** 组装这一帧要画的东西：可见窗口 + 隐藏集合 + 框选状态 */
    private ChartRenderer.View chartView(Layout L, int mouseX, int mouseY) {
        long[] win = window();
        long start = win[0];
        long end = win[1];

        ChartRenderer.Selection sel = selection;
        if (selecting) {
            sel = draftSelection(L, start, end);
        }
        return new ChartRenderer.View(start, end, hiddenItems, sel, selecting, selectionStats, mouseX, mouseY);
    }

    /**
     * 当前该显示哪一段 tick 区间。
     *
     * 三条规则叠在一起：
     *   1. 框选中 → 用拖动开始时锁住的那一段（否则波形跟着手跑）
     *   2. 实时   → 右端跟着最新数据走；冻结 → 右端定在 frozenEnd
     *   3. 数据还不够铺满一个窗口时，从第一点开始画
     *      —— 不这样处理的话，刚接上探头那几秒波形会挤在右边一小条里，看着像坏了
     */
    private long[] window() {
        if (selecting) {
            return new long[]{selectWindowStart, selectWindowEnd};
        }
        long end = live ? Math.max(chartData.lastTick(), chartData.firstTick()) : frozenEnd;
        long start = end - windowTicks;
        if (chartData.firstTick() > start) {
            start = chartData.firstTick();
        }
        if (end <= start) {
            end = start + 1;
        }
        return new long[]{start, end};
    }

    private void renderTitle(GuiGraphics g, Layout L, Sampler sampler, int mouseX, int mouseY) {
        int x = L.x();
        int y = L.y();
        boolean overTitle = isIn(mouseX, mouseY, x, y, L.w(), TITLE_H);
        g.fill(x, y, x + L.w(), y + TITLE_H, dragging ? 0xFF3B3B55 : (overTitle ? 0xFF2C2C3E : 0xFF22222E));
        g.fill(x, y + TITLE_H - 1, x + L.w(), y + TITLE_H, 0xFF5A5A68);
        g.drawString(this.font, "LogiScope", x + 4, y + 4, 0xFFE6E6F0, false);

        if (dragging || overTitle) {
            g.drawString(this.font, tr("logiscope.hint.drag"), x + 62, y + 4, 0xFF8A8AA0, false);
        }

        String status = statusText(sampler);
        g.drawString(this.font, status, x + L.w() - 4 - this.font.width(status), y + 4,
                statusColor(sampler), false);
    }

    private void renderInfo(GuiGraphics g, Layout L, Sampler sampler) {
        int left = L.x() + PAD;
        int right = L.x() + L.w() - PAD;
        int y = L.infoTop();
        ProbeTarget target = sampler.target();

        if (target == null) {
            g.drawString(this.font, tr("logiscope.hint.pick_slot"), left, y, 0xAAAAAA, false);
            return;
        }

        g.drawString(this.font, cut(target.describe(), L.w() - 2 * PAD), left, y, 0xBFBFBF, false);
        y += ROW_H;

        String now = "now " + currentValueText(sampler, target);
        g.drawString(this.font, now, left, y, 0x9BE1CB, false);
        String counts = "samples " + summary.dataCount() + "  no-data " + summary.noDataCount();
        g.drawString(this.font, counts, right - this.font.width(counts), y, 0x808088, false);
        y += ROW_H;

        String mm = "min " + summary.min() + "  max " + summary.max() + "  avg " + summary.average();
        g.drawString(this.font, mm, left, y, 0xC8C8D4, false);
        String sw = "switch " + chartData.switches() + "  series " + chartData.series().size();
        g.drawString(this.font, sw, right - this.font.width(sw), y, 0x808088, false);
    }

    /** 面板上那行 "now"。RECORDING 直接读菜单，其余从最近一条样本取。 */
    private String currentValueText(Sampler sampler, ProbeTarget target) {
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

        Sample last = sampler.store().latest();
        if (last == null) {
            return "-";
        }
        return last.isNoData() ? tr("logiscope.nodata")
                : (last.count() <= 0 ? tr("logiscope.slot.empty") : last.shortName() + " x" + last.count());
    }

    // ------------------------------------------------------------------
    // 未接探头：槽位列表
    // ------------------------------------------------------------------

    private void renderSlotPicker(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int x = L.x();

        if (slots.isEmpty()) {
            g.drawString(this.font, tr("logiscope.hint.no_container"), x + PAD, L.slotListTop(), 0xAAA6A0, false);
            return;
        }

        int listTop = L.slotListTop();
        int maxRows = L.slotListRows();
        int total = slots.size();
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, total - maxRows)));

        for (int i = 0; i < maxRows; i++) {
            int idx = scrollRow + i;
            if (idx >= total) {
                break;
            }
            ContainerAccess.SlotView v = slots.get(idx);
            int ry = listTop + i * ROW_H;
            if (isIn(mouseX, mouseY, x + 4, ry - 1, L.w() - 8, ROW_H)) {
                g.fill(x + 4, ry - 1, x + L.w() - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            String label = "#" + v.containerSlotIndex()
                    + "  " + (v.empty() ? tr("logiscope.slot.empty") : v.shortName() + " x" + v.count());
            g.drawString(this.font, label, x + 8, ry, v.empty() ? 0x707070 : 0xE0E0E0, false);
        }

        if (total > maxRows) {
            String more = (scrollRow + maxRows) + " / " + total + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.font, more, x + PAD, listTop + maxRows * ROW_H + 1, 0x707070, false);
        }
    }

    // ------------------------------------------------------------------
    // 图例 / 工具栏 / 按钮
    // ------------------------------------------------------------------

    private void renderToolbar(GuiGraphics g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            renderChip(g, c, mouseX, mouseY, c.enabled() && c.hit(mouseX, mouseY), c.accent());
        }
    }

    /**
     * 图例：一行一个色块 + 名字，点一下开关这条数据线的渲染。
     * 关掉的用灰底 + 灰字表示，一眼能看出"这条线是我自己关的"而不是"它没数据"。
     */
    private void renderLegend(GuiGraphics g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            if (!c.id().startsWith(SERIES_PREFIX)) {
                renderChip(g, c, mouseX, mouseY, false, 0);
                continue;
            }
            String itemId = c.id().substring(SERIES_PREFIX.length());
            boolean off = hiddenItems.contains(itemId);
            boolean hover = c.hit(mouseX, mouseY);
            int bg = off ? 0xFF17171E : (hover ? 0xFF2A2A38 : 0xFF1C1C26);
            int border = off ? 0xFF252530 : (hover ? 0xFF8A8AC8 : 0xFF3A3A4A);
            g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), bg);
            g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + 1, border);
            g.fill(c.x(), c.y() + c.h() - 1, c.x() + c.w(), c.y() + c.h(), border);
            g.fill(c.x(), c.y(), c.x() + 1, c.y() + c.h(), border);
            g.fill(c.x() + c.w() - 1, c.y(), c.x() + c.w(), c.y() + c.h(), border);

            int color = ChartRenderer.colorOf(chartData.indexOf(itemId), itemId);
            g.fill(c.x() + 4, c.y() + 3, c.x() + 10, c.y() + 9, off ? 0xFF3A3A44 : color);
            g.drawString(this.font, c.label(), c.x() + 13, c.y() + 2, off ? 0xFF70707A : 0xFFD8D8E4, false);
        }
    }

    private void renderButtons(GuiGraphics g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            renderChip(g, c, mouseX, mouseY, c.enabled() && c.hit(mouseX, mouseY), 0);
        }
    }

    private void renderChip(GuiGraphics g, Chip c, int mouseX, int mouseY, boolean hover, int accent) {
        int bg = !c.enabled() ? 0xFF1A1A22 : (hover ? 0xFF41415A : 0xFF2A2A38);
        int border = !c.enabled() ? 0xFF2A2A34 : (hover ? 0xFF8A8AC8 : 0xFF4A4A5E);
        int fg = !c.enabled() ? 0xFF6A6A78 : (accent != 0 ? accent : (hover ? 0xFFF0F0FF : 0xFFC8C8D8));
        g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), bg);
        g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + 1, border);
        g.fill(c.x(), c.y() + c.h() - 1, c.x() + c.w(), c.y() + c.h(), border);
        g.fill(c.x(), c.y(), c.x() + 1, c.y() + c.h(), border);
        g.fill(c.x() + c.w() - 1, c.y(), c.x() + c.w(), c.y() + c.h(), border);
        int tw = this.font.width(c.label());
        g.drawString(this.font, c.label(), c.x() + (c.w() - tw) / 2, c.y() + (c.h() - 8) / 2 + 1, fg, false);
    }

    /** 右下角的缩放把手。画三道嵌套的直角线，比画"◢"可靠（不依赖字体有没有那个字形） */
    private void renderResizeGrip(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int gx = L.x() + L.w() - RESIZE_GRIP;
        int gy = L.y() + L.h() - RESIZE_GRIP;
        boolean hover = isIn(mouseX, mouseY, gx, gy, RESIZE_GRIP, RESIZE_GRIP);
        int c = hover ? 0xFFC8C8E0 : 0xFF5A5A6A;
        int bx = L.x() + L.w() - 1;
        int by = L.y() + L.h() - 1;
        for (int i = 0; i < 3; i++) {
            int d = 3 + i * 3;
            g.fill(bx - d, by - 1, bx, by, c);
            g.fill(bx - 1, by - d, bx, by, c);
        }
        if (hover) {
            String hint = tr("logiscope.hint.resize");
            g.drawString(this.font, hint,
                    L.x() + L.w() - RESIZE_GRIP - 4 - this.font.width(hint),
                    L.y() + L.h() - RESIZE_GRIP + 3, 0xFF8A8AA0, false);
        }
    }

    /** 面板上/下方的一句临时提示（"已清空"这类操作反馈） */
    private void renderFlash(GuiGraphics g, int panelTop) {
        if (flash.isEmpty() || System.currentTimeMillis() > flashUntil) {
            return;
        }
        int w = this.font.width(flash);
        int boxW = w + 8;
        int x = panelX + (panelW - boxW) / 2;
        // 面板贴着屏幕顶时（默认位置就是右上角），把提示挪到面板下方，别画到屏幕外
        int boxY = panelTop - 13 >= 0 ? panelTop - 13 : panelTop + panelH + 2;
        g.fill(x, boxY, x + boxW, boxY + 12, 0xE0202030);
        g.drawString(this.font, flash, x + 4, boxY + 2, 0xFFE6E6F0, false);
    }

    private void say(String text) {
        this.flash = text;
        this.flashUntil = System.currentTimeMillis() + 1800L;
    }

    // ------------------------------------------------------------------
    // 背景容器界面的补画（高亮 / 悬浮提示 / 手持物品）
    // ------------------------------------------------------------------

    /**
     * 自己给被压住的容器界面补 hover 高亮，并把命中的槽位记下来给 tooltip 用。
     *
     * ★ 为什么必须自己画 ★
     * 背景层是由 ClientHooks#drawScreen 渲染的，它给背景层传的鼠标坐标是
     * Integer.MAX_VALUE（为了不让背景层的控件误高亮），而 AbstractContainerScreen
     * 恰恰是在 render 里用这两个坐标算出 hoveredSlot 的
     * → 背景层的 hoveredSlot 永远是 null，格子不会亮、悬浮提示也不出来。
     * 我们在最顶层，手里有真实鼠标坐标，所以自己补上。
     *
     * 需要的全是公开 API（都已核实）：
     *   getGuiLeft() / getGuiTop() / getSlotColor(int)
     *   Slot.x / Slot.y / isActive() / isHighlightable()
     *   renderSlotHighlight(GuiGraphics,int,int,int,int)（public static）
     */
    private void renderBackgroundHover(GuiGraphics g, int mouseX, int mouseY, Layout L) {
        backgroundHoveredSlot = null;
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null || overPanel(mouseX, mouseY, L)) {
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
     * 的代码把它画到了屏幕外 —— 表现就是实机反馈的
     * "东西确实拿起来了，但跟着鼠标的图标和数字不见了"。
     *
     * 这段复刻 vanilla 的 `renderFloatingItem`（**它是 private，只能照着写**）：
     *   pose.translate(0,0,232) → renderItem(stack,x,y) → renderItemDecorations(font,stack,x,y)
     * 位置取 `(mouseX-8, mouseY-8)`；vanilla 是在平移了 `(leftPos,topPos)` 的 pose 里
     * 传 `(mouseX-leftPos-8, mouseY-topPos-8)`，两者等价。
     *
     * 已知差异：vanilla 在做「拖拽逐格分配」时会把数字画成剩余待分配量
     * （`quickCraftingRemainder`，private，我们读不到），这里只能画完整数量。
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

    // ==================================================================
    // 交互
    // ==================================================================

    private boolean overPanel(double mouseX, double mouseY, Layout L) {
        return isIn((int) mouseX, (int) mouseY, L.x(), L.y(), L.w(), L.h());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout L = layout();

        // ★ 面板之外一律让给被压住的容器界面 ★
        // 这样"面板打开时机器还能操作"。可行性依据：AbstractContainerScreen#mouseClicked
        // 内部用 findSlot(mouseX, mouseY) 现算坐标、**不依赖 hoveredSlot**
        // （而背景层的 hoveredSlot 恰好恒为 null），所以转发是有效的。
        if (!overPanel(mouseX, mouseY, L)) {
            AbstractContainerScreen<?> bg = backgroundContainer();
            return bg != null && bg.mouseClicked(mouseX, mouseY, button);
        }

        if (button != 0) {
            return true;
        }

        int mx = (int) mouseX;
        int my = (int) mouseY;

        // 1) 右下角缩放把手（优先于标题栏，不然永远点不到）
        if (isIn(mx, my, L.x() + L.w() - RESIZE_GRIP, L.y() + L.h() - RESIZE_GRIP, RESIZE_GRIP, RESIZE_GRIP)) {
            resizing = true;
            resizeGripX = L.x() + L.w() - mx;
            resizeGripY = L.y() + L.h() - my;
            return true;
        }

        // 2) 标题栏 → 开始拖动
        if (isIn(mx, my, L.x(), L.y(), L.w(), TITLE_H)) {
            dragging = true;
            dragOffX = mx - L.x();
            dragOffY = my - L.y();
            return true;
        }

        // 3) 波形区 → 开始框选
        Sampler sampler = LogiScopeMod.sampler();
        if (sampler.target() != null && L.hasChart()
                && ChartRenderer.inPlot(L.x() + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), mx, my)) {
            beginSelection(mx);
            return true;
        }

        // 4) 工具栏 / 图例 / 按钮
        for (Chip c : allChips(L)) {
            if (c.enabled() && c.hit(mx, my)) {
                onChip(c.id());
                return true;
            }
        }

        // 5) 槽位列表（只有未接探头时）
        if (sampler.target() == null && !slots.isEmpty()) {
            for (int i = 0; i < L.slotListRows(); i++) {
                int idx = scrollRow + i;
                if (idx >= slots.size()) {
                    break;
                }
                int ry = L.slotListTop() + i * ROW_H;
                if (isIn(mx, my, L.x() + 4, ry - 1, L.w() - 8, ROW_H)) {
                    attachSlot(slots.get(idx), sampler);
                    return true;
                }
            }
        }

        // 点在面板上但没打中任何控件：吃掉，别透传给底下
        return true;
    }

    private List<Chip> allChips(Layout L) {
        List<Chip> out = new ArrayList<>(L.buttons().size() + L.toolbar().size() + L.legend().size());
        out.addAll(L.legend());
        out.addAll(L.toolbar());
        out.addAll(L.buttons());
        return out;
    }

    private void beginSelection(int mx) {
        selecting = true;
        selAnchorX = mx;
        selCurrentX = mx;
        // 锁住窗口：否则波形在拖的过程中持续往前滚，"框住的那段"会一直变
        long[] win = window();
        selectWindowStart = win[0];
        selectWindowEnd = win[1];
    }

    private ChartRenderer.Selection draftSelection(Layout L, long start, long end) {
        int cx = L.x() + PAD;
        int cw = L.w() - 2 * PAD;
        long a = ChartRenderer.tickAtX(cx, cw, start, end, selAnchorX);
        long b = ChartRenderer.tickAtX(cx, cw, start, end, selCurrentX);
        return new ChartRenderer.Selection(a, b);
    }

    private void endSelection(Layout L) {
        if (!selecting) {
            return;
        }
        selecting = false;

        // 按"拖动时锁住的那个窗口"把像素换算成 tick —— 不能重算窗口，
        // 否则松开手的那一帧窗口已经变了，选框会跳一下
        ChartRenderer.Selection sel = draftSelection(L, selectWindowStart, selectWindowEnd);
        if (Math.abs(selCurrentX - selAnchorX) < 3 || !sel.isValid()) {
            // 只是点了一下（没有拖动）→ 当作"取消选择"，其余状态不动
            selection = null;
            selectionStats = List.of();
            return;
        }

        // ★ 建立选择就冻结视图 ★
        // 否则波形会继续往前滚，刚框住的那段几秒后就跑出屏幕了。
        // 想回到实时就点工具栏的「实时」（它会一并清掉选择）—— 规则只有这一条，好记。
        live = false;
        frozenEnd = selectWindowEnd;
        selection = sel;
        refreshSelectionStats();
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
        // 换了目标 = 换了一套数据线，上一套的隐藏记录没有意义了
        hiddenItems.clear();
        selection = null;
        selectionStats = List.of();
        live = true;
        say(anchor == null ? tr("logiscope.flash.runtime_only") : tr("logiscope.flash.attached"));
    }

    private void onChip(String id) {
        Sampler sampler = LogiScopeMod.sampler();
        if (id.startsWith(SERIES_PREFIX)) {
            toggleSeries(id.substring(SERIES_PREFIX.length()));
            return;
        }
        switch (id) {
            case "back" -> {
                sampler.backToPicker();
                hiddenItems.clear();
                selection = null;
                selectionStats = List.of();
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
                selection = null;
                selectionStats = List.of();
                live = true;
                say(tr("logiscope.flash.cleared"));
            }
            case "follow" -> {
                sampler.setFollowAfterClose(!sampler.followAfterClose());
                say(followLabel());
            }
            case "close" -> closeScope();
            case "live" -> {
                // 回到实时 = 放弃选择。两者不并存（选择会冻结视图，见 endSelection）
                live = true;
                selection = null;
                selectionStats = List.of();
                say(tr("logiscope.flash.live"));
            }
            case "zoom_in" -> zoom(true);
            case "zoom_out" -> zoom(false);
            case "fit" -> {
                long span = Math.max(MIN_WINDOW, chartData.spanTicks() + 1);
                windowTicks = (int) Math.min(MAX_WINDOW, span);
                live = false;
                frozenEnd = Math.max(chartData.lastTick(), chartData.firstTick());
                say(tr("logiscope.flash.fit"));
            }
            case "clear_sel" -> {
                selection = null;
                selectionStats = List.of();
                say(tr("logiscope.flash.sel_cleared"));
            }
            default -> {
            }
        }
    }

    private void toggleSeries(String itemId) {
        if (!hiddenItems.remove(itemId)) {
            hiddenItems.add(itemId);
        }
    }

    /**
     * 缩放。冻结时以窗口中心为锚（你想细看的就是当前视野中间那段）；
     * 实时时以右端为锚（最新数据必须一直在视野里）。
     */
    private void zoom(boolean in) {
        int next = in
                ? Math.max(MIN_WINDOW, windowTicks / 2)
                : Math.min(MAX_WINDOW, windowTicks * 2);
        if (next == windowTicks) {
            return;
        }
        if (!live) {
            long center = frozenEnd - windowTicks / 2;
            frozenEnd = center + next / 2;
        }
        windowTicks = next;
        if (selection != null) {
            refreshSelectionStats();
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (resizing && button == 0) {
            panelW = (int) mouseX + resizeGripX - panelX;
            panelH = (int) mouseY + resizeGripY - panelY;
            panelW = Math.max(MIN_W, Math.min(panelW, Math.max(MIN_W, this.width - panelX)));
            panelH = Math.max(MIN_H, Math.min(panelH, Math.max(MIN_H, this.height - panelY)));
            return true;
        }
        if (dragging && button == 0) {
            panelX = (int) mouseX - dragOffX;
            panelY = (int) mouseY - dragOffY;
            clampDuringDrag();
            return true;
        }
        if (selecting && button == 0) {
            selCurrentX = (int) mouseX;
            return true;
        }
        // 面板外：拖拽也是容器的交互（原版用它做快速合成/批量摆放），要转交
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (resizing && button == 0) {
            resizing = false;
            return true;
        }
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        if (selecting && button == 0) {
            selCurrentX = (int) mouseX;
            endSelection(layout());
            return true;
        }
        // 松手同样要转交，否则容器的拖拽状态会卡住（AbstractContainerScreen 里
        // 用 mouseReleased 收尾 quick-craft）
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout L = layout();
        if (overPanel(mouseX, mouseY, L)) {
            Sampler sampler = LogiScopeMod.sampler();
            int dir = (int) Math.signum(scrollY);
            if (dir == 0) {
                return true;
            }
            if (sampler.target() == null && !slots.isEmpty()) {
                scrollRow -= dir;
                scrollRow = Math.max(0, scrollRow);
                return true;
            }
            if (sampler.target() != null && L.hasChart()) {
                if (live) {
                    // 实时模式：滚轮 = 缩放（右端锚定）
                    zoom(dir > 0);
                } else {
                    // 冻结模式：滚轮 = 左右平移，方便回头细看某一段
                    long step = Math.max(1, windowTicks / 8);
                    long min = chartData.firstTick();
                    long max = Math.max(chartData.lastTick(), chartData.firstTick());
                    frozenEnd = Math.max(min + 1, Math.min(max, frozenEnd - (long) dir * step));
                }
                return true;
            }
            return true;
        }
        // 面板外滚轮让给容器（部分模组的容器界面用滚轮翻页）
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static boolean isIn(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 按像素宽度截断，超出加省略号（探测目标那行可能带很长的维度名） */
    private String cut(String s, int maxW) {
        if (this.font.width(s) <= maxW) {
            return s;
        }
        String ell = "...";
        int budget = maxW - this.font.width(ell);
        int i = s.length();
        while (i > 0 && this.font.width(s.substring(0, i)) > budget) {
            i--;
        }
        return s.substring(0, Math.max(0, i)) + ell;
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
