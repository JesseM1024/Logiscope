package com.jessetech.logiscope.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 1.18.2 的绘制入口（1.20+ 的 {@code GuiGraphics} 的替身）。
 *
 * 1.20 起 MC 把「PoseStack + 一组绘制方法」收成了 {@code GuiGraphics}；
 * 1.18.2 还没有这个类，绘制方法散在 {@link GuiComponent}、{@link Font}、
 * {@code ItemRenderer} 上。
 *
 * 本类只提供上层真正用到的那一小撮方法，签名与 1.20 的 {@code GuiGraphics} 保持一致 ——
 * 于是 ScopeScreen / ChartRenderer 里 160 多处 {@code g.fill} / {@code g.drawString}
 * 一行都不用改，只要把参数类型换成 {@link Draw}。
 *
 * 只允许放"绘制原语"，不要在这里加业务语义（保持它随时可以再换一次版本）。
 */
public final class Draw {

    private final PoseStack pose;

    public Draw(PoseStack pose) {
        this.pose = pose;
    }

    /** 供确实需要直接操作矩阵的地方使用（对应 Draw#pose） */
    public PoseStack pose() {
        return pose;
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill(pose, x1, y1, x2, y2, color);
    }

    public void drawString(Font font, String text, int x, int y, int color) {
        font.drawShadow(pose, text, x, y, color, true);
    }

    public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        font.drawShadow(pose, text, x, y, color, shadow);
    }

    public void drawString(Font font, Component text, int x, int y, int color) {
        font.drawShadow(pose, text.getVisualOrderText(), x, y, color);
    }

    public void drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        // 1.18.2 的 drawShadow 没有 boolean 重载：带阴影走 drawShadow，不带阴影走 draw
        if (shadow) {
            font.drawShadow(pose, text.getVisualOrderText(), x, y, color);
        } else {
            font.draw(pose, text.getVisualOrderText(), x, y, color);
        }
    }

    public void renderItem(ItemStack stack, int x, int y) {
        Minecraft.getInstance().getItemRenderer().renderGuiItem(stack, x, y);
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
        Minecraft.getInstance().getItemRenderer().renderGuiItemDecorations(font, stack, x, y);
    }
}
