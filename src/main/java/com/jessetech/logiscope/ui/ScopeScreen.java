package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.Analyzer;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.collect.ContainerAccess;
import com.jessetech.logiscope.collect.Sampler;
import com.jessetech.logiscope.store.Sample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 呈现层：示波器主界面。
 *
 * 它【只读】Sampler / SampleStore，不修改任何采样状态，也不能直接写数据。
 * 这是刻意的：把"看"和"记"分开，界面出错不会污染数据。
 *
 * 渲染只用原版 GuiGraphics#fill + drawString，不引入任何图表库，
 * 避免依赖地狱（NeoForge 生态的渲染库版本很容易打架）。
 *
 * 已知局限（见 docs/ARCHITECTURE.md「设计权衡」）：
 *   本界面是 setScreen 替换式打开，会把原设备 GUI 盖掉。
 *   要"边看机器边看波形"，后续需要改成 ScreenEvent.Render.Post 叠加绘制模式。
 */
public final class ScopeScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 216;
    private static final int ROW_H = 11;
    private static final int MAX_ROWS = 11;

    private final Screen parent;
    private List<ContainerAccess.SlotView> slots = List.of();

    private int px;
    private int py;

    public ScopeScreen(Screen parent) {
        super(Component.translatable("logiscope.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.px = (this.width - PANEL_W) / 2;
        this.py = (this.height - PANEL_H) / 2;
        refreshSlots();

        // 还没接探头就进入"待选格"状态，让状态机自洽
        Sampler sampler = LogiScopeMod.sampler();
        if (sampler.target() == null) {
            sampler.arm();
        }
    }

    private void refreshSlots() {
        this.slots = ContainerAccess.snapshot();
    }

    /** 单机也不暂停游戏 —— 否则 tick 停了，采样也就停了 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Sampler sampler = LogiScopeMod.sampler();

        // 压暗整个背景 + 面板
        g.fill(0, 0, this.width, this.height, 0x88000000);
        g.fill(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, 0xFF3C3C44);
        g.fill(px, py, px + PANEL_W, py + PANEL_H, 0xFF101016);

        g.drawString(this.font, this.title.getString(), px + 8, py + 8, 0xFFFFFF);
        g.drawString(this.font, statusText(sampler), px + 8, py + 20, statusColor(sampler));
        g.drawString(this.font,
                Component.translatable("logiscope.hint.close").getString(),
                px + PANEL_W - 8 - this.font.width(Component.translatable("logiscope.hint.close").getString()),
                py + 8, 0x888888);

        if (sampler.target() == null) {
            renderSlotPicker(g, mouseX, mouseY);
        } else {
            renderProbe(g, sampler);
        }
    }

    /** 未接探头：列出当前容器的所有槽位供选择 */
    private void renderSlotPicker(GuiGraphics g, int mouseX, int mouseY) {
        int y = py + 40;
        g.drawString(this.font,
                Component.translatable("logiscope.hint.pick_slot").getString(),
                px + 8, y, 0xAAAAAA);
        y += 14;

        if (slots.isEmpty()) {
            g.drawString(this.font, "- no container open -", px + 8, y, 0x777777);
            return;
        }

        int shown = Math.min(slots.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            ContainerAccess.SlotView v = slots.get(i);
            int ry = y + i * ROW_H;
            if (isIn(mouseX, mouseY, px + 4, ry - 1, PANEL_W - 8, ROW_H)) {
                g.fill(px + 4, ry - 1, px + PANEL_W - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            String label = "#" + v.menuSlotIndex()
                    + "  " + (v.empty() ? "(empty)" : v.shortName() + " x" + v.count());
            g.drawString(this.font, label, px + 8, ry, v.empty() ? 0x707070 : 0xE0E0E0);
        }

        if (slots.size() > shown) {
            g.drawString(this.font, "... +" + (slots.size() - shown) + " more",
                    px + 8, y + shown * ROW_H, 0x707070);
        }
    }

    /** 已接探头：实时值 + 统计摘要 + 火花线 */
    private void renderProbe(GuiGraphics g, Sampler sampler) {
        ProbeTarget target = sampler.target();
        List<Sample> samples = sampler.store().toList();
        Analyzer.Summary sum = Analyzer.summarize(samples);

        int y = py + 38;
        g.drawString(this.font, "probe: " + target.describe(), px + 8, y, 0xBFBFBF);
        y += 12;

        ContainerAccess.SlotView now = ContainerAccess.read(target.slotIndex());
        String nowText = now == null
                ? "- source lost -"
                : (now.empty() ? "(empty)" : now.shortName() + " x" + now.count());
        g.drawString(this.font, "now:   " + nowText, px + 8, y, 0x9BE1CB);
        y += 12;
        g.drawString(this.font, "samples: " + sum.sampleCount() + "   (tick " + sampler.currentTick() + ")",
                px + 8, y, 0x9BE1CB);
        y += 14;

        // 统计摘要
        g.drawString(this.font, "first " + sum.first() + "  ->  last " + sum.last()
                + "   net " + signed(sum.netChange()), px + 8, y, 0xE0E0E0);
        y += 12;
        g.drawString(this.font, "min " + sum.min() + "  max " + sum.max()
                + "  avg " + sum.average(), px + 8, y, 0xE0E0E0);
        y += 12;
        g.drawString(this.font, "type changes " + sum.typeChanges()
                + "   up " + sum.rises() + "  down " + sum.falls(), px + 8, y, 0xE0E0E0);
        y += 16;

        renderSparkline(g, samples, sum, px + 8, y, PANEL_W - 16, 56);
    }

    /**
     * 火花线：把 count 序列压成宽度 W 的柱状图。
     * 采样点比像素多时按列取该列的最大值（峰值优先），
     * 这样尖锐的瞬时波动不会被平均掉。
     */
    private void renderSparkline(GuiGraphics g, List<Sample> samples, Analyzer.Summary sum,
                                 int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF1A1A22);
        if (samples.isEmpty()) {
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
                peak = Math.max(peak, samples.get(i).count());
            }
            if (peak == Integer.MIN_VALUE) {
                continue;
            }
            int barH = (int) Math.round((peak - lo) / (double) span * (h - 2)) + 1;
            int top = y + h - 1 - barH;
            g.fill(x + col, top, x + col + 1, y + h - 1, 0x9BE1CB);
        }

        // 中线参考
        g.fill(x, y + h / 2, x + w, y + h / 2 + 1, 0x30FFFFFF);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        Sampler sampler = LogiScopeMod.sampler();

        // 已接探头时，右键区域暂不处理（后续放图表缩放/标点）
        if (sampler.target() != null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int y = py + 54;
        int shown = Math.min(slots.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            int ry = y + i * ROW_H;
            if (isIn((int) mouseX, (int) mouseY, px + 4, ry - 1, PANEL_W - 8, ROW_H)) {
                ContainerAccess.SlotView v = slots.get(i);
                sampler.attach(new ProbeTarget(
                        ContainerAccess.currentContainerId(),
                        v.menuSlotIndex(),
                        v.itemId()));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        // 还原被盖住的设备界面。containerMenu 一直是有效的，采样不会受影响。
        Minecraft.getInstance().setScreen(this.parent);
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

    private static String statusText(Sampler sampler) {
        String key = switch (sampler.state()) {
            case IDLE -> "logiscope.status.idle";
            case ARMED -> "logiscope.status.armed";
            case RECORDING -> "logiscope.status.recording";
            case PAUSED -> "logiscope.status.paused";
        };
        return "[ " + Component.translatable(key).getString() + " ]";
    }

    private static int statusColor(Sampler sampler) {
        return switch (sampler.state()) {
            case RECORDING -> 0x5DCAA5;
            case PAUSED -> 0xEF9F27;
            case ARMED -> 0x85B7EB;
            case IDLE -> 0x888780;
        };
    }
}
