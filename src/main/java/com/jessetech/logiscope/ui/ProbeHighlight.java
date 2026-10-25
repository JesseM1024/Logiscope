package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.LogiScopeMod;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 探针追踪目标的**世界内高亮框**：一个能**穿墙**看见的红黄闪烁方块边框。
 *
 * 入口在根页探针列表的十字准星按钮（{@link ScopeScreen}），一次只高亮一个目标。
 *
 * 【本线为什么自己开 GL 状态（与 1.21.1 不同）】
 * 1.16.5 的 {@code RenderType.create} 还是老签名（setup/clear 两个 Runnable），
 * 拼一个"关深度"的线框类型要写一堆 GL 回调；而这里只是一次性的调试线框，
 * 直接用 {@code Tessellator} 立即绘制更直接：开状态 → 塞顶点 → {@code draw()} → 还原。
 * 立即绘制不经过 RenderType 的状态机，关掉的深度测试不会被它重新打开。
 *
 * 【闪烁】用系统时钟而不是 tick：要求是 1 Hz，tick 会随 {@code /tick rate} 漂移。
 * 每 500 ms 换一次颜色（红 ↔ 黄），一个完整周期 = 1 s。
 *
 * 【可见性条件】每次渲染都重新判定：维度相同 + 玩家三轴距离都 < 128 格
 * （按需求：**不**算欧氏距离）。离开范围/换维度只是**不画**，高亮状态保留 —— 走回去还能看见。
 */
public final class ProbeHighlight {

    /** 可见范围（每轴，格）。需求：三轴差值绝对值均 < 128 */
    public static final int RANGE = 128;

    /** 当前高亮的探针 id（-1 = 没有） */
    private static int probeId = -1;
    @Nullable
    private static net.minecraft.util.RegistryKey<World> dim;
    @Nullable
    private static BlockPos pos;

    /** 渲染失败是否已经记过日志（坏在渲染帧里的异常会每帧刷屏） */
    private static boolean warned;

    private ProbeHighlight() {
    }

    /** 现在高亮的是这个探针吗（列表按钮据此变色） */
    public static boolean isActiveFor(int id) {
        return probeId != -1 && probeId == id;
    }

    /** 开始高亮某个探针的目标（调用方负责先做维度/距离校验并提示） */
    public static void set(int id, net.minecraft.util.RegistryKey<World> dimension, BlockPos target) {
        probeId = id;
        dim = dimension;
        pos = target.toImmutable();
    }

    /** 关掉高亮（再次点击 / 探针被删除 / 换世界） */
    public static void clear() {
        probeId = -1;
        dim = null;
        pos = null;
    }

    /** 渲染入口（注册在 {@code MinecraftForge.EVENT_BUS} 上） */
    public static void onRenderLevel(RenderWorldLastEvent event) {
        try {
            render(event);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    private static void render(RenderWorldLastEvent event) {
        if (probeId == -1 || dim == null || pos == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.world == null || mc.player == null
                || !mc.world.getDimensionKey().equals(dim)) {
            return;
        }
        BlockPos p = mc.player.getPosition();
        if (Math.abs(pos.getX() - p.getX()) >= RANGE
                || Math.abs(pos.getY() - p.getY()) >= RANGE
                || Math.abs(pos.getZ() - p.getZ()) >= RANGE) {
            return;   // 超出范围只是不画，状态留着（走回来还能看见）
        }

        Vector3d cam = mc.gameRenderer.getActiveRenderInfo().getProjectedView();
        MatrixStack ms = event.getMatrixStack();
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = ms.getLast().getMatrix();

        boolean red = (System.currentTimeMillis() / 500L) % 2L == 0L;
        float r = 1.0F;
        float g = red ? 0.18F : 0.86F;
        float b = red ? 0.18F : 0.20F;

        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();    // ← 穿墙
        RenderSystem.depthMask(false);      // 不写深度，免得挡住后面的半透明
        RenderSystem.lineWidth(2.0F);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(org.lwjgl.opengl.GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        box(buf, mat, pos, r, g, b);
        tess.draw();   // 立即上传并绘制

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableTexture();
        ms.pop();
    }

    /** 一个方块的 12 条棱：先按 0.002 外扩，避免与方块表面 z-fighting */
    private static void box(BufferBuilder buf, Matrix4f mat, BlockPos pos, float r, float g, float b) {
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(0.002D);
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        // 下底
        line(buf, mat, x1, y1, z1, x2, y1, z1, r, g, b);
        line(buf, mat, x2, y1, z1, x2, y1, z2, r, g, b);
        line(buf, mat, x2, y1, z2, x1, y1, z2, r, g, b);
        line(buf, mat, x1, y1, z2, x1, y1, z1, r, g, b);
        // 上顶
        line(buf, mat, x1, y2, z1, x2, y2, z1, r, g, b);
        line(buf, mat, x2, y2, z1, x2, y2, z2, r, g, b);
        line(buf, mat, x2, y2, z2, x1, y2, z2, r, g, b);
        line(buf, mat, x1, y2, z2, x1, y2, z1, r, g, b);
        // 四条竖棱
        line(buf, mat, x1, y1, z1, x1, y2, z1, r, g, b);
        line(buf, mat, x2, y1, z1, x2, y2, z1, r, g, b);
        line(buf, mat, x2, y1, z2, x2, y2, z2, r, g, b);
        line(buf, mat, x1, y1, z2, x1, y2, z2, r, g, b);
    }

    private static void line(BufferBuilder buf, Matrix4f mat,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b) {
        buf.pos(mat, (float) x1, (float) y1, (float) z1).color(r, g, b, 1.0F).endVertex();
        buf.pos(mat, (float) x2, (float) y2, (float) z2).color(r, g, b, 1.0F).endVertex();
    }

    /** 渲染失败只记一次日志：坏在渲染帧里的异常会每帧刷屏，一次就够定位了 */
    private static void warnOnce(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        LogiScopeMod.LOGGER.warn("[LogiScope] highlight box render failed (will not log again)", t);
    }
}
