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

    /**
     * 进入"画面板"的干净状态。**必须**在整段面板绘制之前调一次。
     *
     * 【为什么必须要关深度 —— 1.12.2 的图层优先级陷阱】
     * 这一代 {@code RenderItem#setupGuiTransform} 把物品固定画在
     *   {@code translate(x, y, 100.0F + zLevel)}
     * 而面板的方块/{@code Gui#drawRect} 与文字都画在 **z = 0**。
     * 深度测试开着时，z=100 的那一层**永远赢**（它更靠近相机）——
     * 于是"先画父界面（含物品）→ 再画面板"这个顺序**不起作用**：
     * 物品会直接从面板里穿出来，看起来就是"物品被绘制在界面之上"。
     * 实测实机症状正是这个（C 组同批反馈的第 1 条）。
     *
     * 面板是最后画的，按语义就该在最上层 ⇒ 关掉深度，让**绘制顺序**决定层次。
     * 顺带把光照/缩放归零：面板只画方块和文字，不该继承父界面留下的物品光照。
     *
     * 收尾见 {@link #endPanel()} —— 不还原会把 HUD / 别的模组覆盖层一起带坏。
     */
    public void beginPanel() {
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        net.minecraft.client.renderer.GlStateManager.disableLighting();
        net.minecraft.client.renderer.GlStateManager.disableRescaleNormal();
        net.minecraft.client.renderer.GlStateManager.disableDepth();
        // ★ 纹理状态的"缓存重同步"：
        //   GlStateManager 在 Java 侧缓存 GL 状态。只要别的绘制者用**原始 GL** 改过
        //   （模组的 TEISR 里直接调 GL11.glEnable 很常见），缓存就和真实状态脱钩 ——
        //   此后 Gui#drawRect 里的 disableTexture2D() 会退化成空操作，方块被当成**贴图**画，
        //   采样到上一个 texcoord 的单个 texel ⇒ 整片纯色（实机症状：面板变成一整块灰）。
        //   先 enable 再 disable 能把两侧重新对齐（enable 只在"缓存=已启用"时才真正下发）。
        syncTexture2D(false);
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(
                net.minecraft.client.renderer.GlStateManager.SourceFactor.SRC_ALPHA,
                net.minecraft.client.renderer.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                net.minecraft.client.renderer.GlStateManager.SourceFactor.ONE,
                net.minecraft.client.renderer.GlStateManager.DestFactor.ZERO);
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * 把 `GL_TEXTURE_2D` 的**真实状态**与 `GlStateManager` 的缓存对齐到目标值。
     * 见 {@link #beginPanel()} 的注释：两个方向都要"反着来一次"才能覆盖两种脱钩。
     */
    private static void syncTexture2D(boolean want) {
        if (want) {
            net.minecraft.client.renderer.GlStateManager.disableTexture2D();
            net.minecraft.client.renderer.GlStateManager.enableTexture2D();
        } else {
            net.minecraft.client.renderer.GlStateManager.enableTexture2D();
            net.minecraft.client.renderer.GlStateManager.disableTexture2D();
        }
    }

    /**
     * 退出面板绘制，把状态还原成**别的绘制者期望的样子**。
     * vanilla {@code GuiContainer#drawScreen} 的收尾就是 disableLighting + enableDepth，
     * 这里照抄，免得"画完我们的面板之后，HUD / 其它模组覆盖层全都变暗或错位"。
     */
    public void endPanel() {
        net.minecraft.client.renderer.GlStateManager.disableBlend();
        net.minecraft.client.renderer.GlStateManager.disableLighting();
        net.minecraft.client.renderer.GlStateManager.disableRescaleNormal();
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        net.minecraft.client.renderer.GlStateManager.enableDepth();
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * 单个物品图标。
     *
     * 物品需要 **GUI 标准光照**才有立体感，否则是一团死黑；但光照**必须**当场关掉：
     * 开着它，后面所有普通绘制（文字 / 方块）都会被当成受光物体处理 → 整体发暗。
     * 这是 1.12.2 的经典坑（vanilla 是在整段物品循环外面统一开关的，
     * 我们只有一个图标要画，所以逐次开关最省心）。
     */
    public void renderItem(ItemStack stack, int x, int y) {
        net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        net.minecraft.client.renderer.GlStateManager.disableLighting();
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public void renderItemDecorations(FontRenderer font, ItemStack stack, int x, int y) {
        Minecraft.getMinecraft().getRenderItem().renderItemOverlayIntoGUI(font, stack, x, y, null);
    }
}
