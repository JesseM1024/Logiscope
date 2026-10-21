package com.jessetech.logiscope.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;

/**
 * 1.12.2 的绘制入口。
 *
 * 与上游分支同一个套路：把 MC 各代的绘制原语收成**一小撮方法**，
 * 上层（{@code ChartRenderer} / {@code ScopeScreen}）只认这些方法，换代时只改本类。
 *
 * 【1.12.2 与 1.16.5 的根本差别：立即模式，没有矩阵栈参数】
 *   1.16.5：{@code fill(MatrixStack, ...)} / {@code font.drawStringWithShadow(MatrixStack, ...)}
 *   1.12.2：**没有 {@code MatrixStack}**，绘制直接用当前 GL 状态 ——
 *           {@link Gui#drawRect(int,int,int,int,int)}、{@link FontRenderer} 的老签名。
 * 所以本类**不持有任何状态**，方法签名里也没有矩阵参数；
 * 调用点从 {@code new Draw(pose)} 换成 {@code new Draw()} 就完事。
 *
 * 只允许放"绘制原语"，不要在这里加业务语义。
 */
public final class Draw {

    /**
     * 实心矩形（半透明色也走这里）。
     *
     * 1.12.2 的 {@code Gui#drawRect} 内部已经做了
     * {@code disableTexture2D + enableBlend + tryBlendFuncSeparate}，
     * 所以 ARGB 里的 alpha 会被正确混合 —— 1.16.5 线那套"手动开 blend"在这里不需要。
     */
    public void fill(int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y2, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color) {
        font.drawStringWithShadow(text, x, y, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawStringWithShadow(text, x, y, color);
        } else {
            font.drawString(text, x, y, color);
        }
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color) {
        // 1.12.2 没有 getString()，等价物是 getUnformattedText()
        font.drawStringWithShadow(text.getUnformattedText(), x, y, color);
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color, boolean shadow) {
        drawString(font, text.getUnformattedText(), x, y, color, shadow);
    }

    /**
     * 临时抬高绘制层次（对应 1.16.5 的 {@code pose.push + translate(0,0,z)}）。
     * 立即模式下就是 GL 的 pushMatrix + Z 平移 —— 工具提示/物品要压在面板之上时用。
     */
    public void pushZ(float z) {
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(0.0F, 0.0F, z);
    }

    public void popZ() {
        net.minecraft.client.renderer.GlStateManager.popMatrix();
    }

    public void renderItem(ItemStack stack, int x, int y) {
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
    }

    public void renderItemDecorations(FontRenderer font, ItemStack stack, int x, int y) {
        Minecraft.getMinecraft().getRenderItem().renderItemOverlayIntoGUI(font, stack, x, y, null);
    }
}
