package com.jessetech.logiscope.ui;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;

/**
 * 1.16.5 的绘制入口（1.20+ 的 {@code GuiGraphics} 的替身）。
 *
 * 1.20 起 MC 把「矩阵 + 一组绘制方法」收成了 {@code GuiGraphics}；
 * 1.16.5 更早：矩阵是 {@link MatrixStack}，静态绘制在 {@link AbstractGui}，
 * 文字在 {@link FontRenderer}，物品在 {@code ItemRenderer}。
 *
 * 本类只提供上层真正用到的那一小撮方法，签名与 1.20 的 {@code GuiGraphics} 保持一致 ——
 * 于是 ScopeScreen / ChartRenderer 里的绘制调用一行都不用改，只要换这个类型。
 *
 * 只允许放"绘制原语"，不要在这里加业务语义。
 */
public final class Draw {

    private final MatrixStack pose;

    public Draw(MatrixStack pose) {
        this.pose = pose;
    }

    /** 供确实需要直接操作矩阵的地方使用（对应 1.20 的 GuiGraphics#pose） */
    public MatrixStack pose() {
        return pose;
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        AbstractGui.fill(pose, x1, y1, x2, y2, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color) {
        font.drawStringWithShadow(pose, text, x, y, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawStringWithShadow(pose, text, x, y, color);
        } else {
            font.drawString(pose, text, x, y, color);
        }
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color) {
        font.drawStringWithShadow(pose, text.getString(), x, y, color);
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color, boolean shadow) {
        drawString(font, text.getString(), x, y, color, shadow);
    }

    public void renderItem(ItemStack stack, int x, int y) {
        Minecraft.getInstance().getItemRenderer().renderItemAndEffectIntoGUI(stack, x, y);
    }

    public void renderItemDecorations(FontRenderer font, ItemStack stack, int x, int y) {
        Minecraft.getInstance().getItemRenderer().renderItemOverlayIntoGUI(font, stack, x, y, null);
    }
}
