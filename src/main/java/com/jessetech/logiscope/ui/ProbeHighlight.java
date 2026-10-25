package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.compat.Dim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 探针追踪目标的**世界内高亮框**：一个能**穿墙**看见的红黄闪烁方块边框。
 *
 * 入口在根页探针列表的十字准星按钮（{@link ScopeScreen}），一次只高亮一个目标。
 *
 * 【本线为什么直接开 GL 状态】1.12.2 没有 RenderType 那一套；这里只是一次性的调试线框，
 * 用 {@code Tessellator} 立即绘制最直接：开状态 → 塞顶点 → {@code draw()} → 还原。
 * 关掉的深度测试不会被任何状态机重新打开（"穿墙"就是这么来的）。
 *
 * 【闪烁】用系统时钟而不是 tick：要求是 1 Hz，tick 会随加速漂移。
 * 每 500 ms 换一次颜色（红 ↔ 黄），一个完整周期 = 1 s。
 *
 * 【可见性条件】每次渲染都重新判定：维度相同 + 玩家三轴距离都 < 128 格
 * （按需求：**不**算欧氏距离）。离开范围/换维度只是**不画**，高亮状态保留。
 *
 * 【坐标】1.12.2 没有 PoseStack：先 {@code GlStateManager.translate(-相机位置)} 再喂世界坐标，
 * 与 {@code RenderGlobal.drawBoundingBox} 之类的老写法一致。
 */
public final class ProbeHighlight {

    /** 可见范围（每轴，格）。需求：三轴差值绝对值均 < 128 */
    public static final int RANGE = 128;

    /** 当前高亮的探针 id（-1 = 没有） */
    private static int probeId = -1;
    @Nullable
    private static Dim dim;
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
    public static void set(int id, Dim dimension, BlockPos target) {
        probeId = id;
        dim = dimension;
        pos = target;
    }

    /** 关掉高亮（再次点击 / 探针被删除 / 换世界） */
    public static void clear() {
        probeId = -1;
        dim = null;
        pos = null;
    }

    /** 渲染入口（{@code MinecraftForge.EVENT_BUS.register(ProbeHighlight.class)} 注册） */
    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        try {
            render(event);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /**
     * 换世界/退存档时清掉：本线没有"登录"事件可挂（1.12.2 的 FML 事件分发不一样），
     * 用客户端世界的卸载事件兜底 —— 否则高亮框会被带进下一个存档。
     * 探针本身也会被 ProbeManager 清掉，这个框不该活得比它久。
     */
    @SubscribeEvent
    public static void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (event.getWorld() != null && event.getWorld().isRemote) {
            clear();
        }
    }

    private static void render(RenderWorldLastEvent event) {
        if (probeId == -1 || dim == null || pos == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || !Dim.of(mc.world).equals(dim)) {
            return;
        }
        BlockPos p = mc.player.getPosition();
        if (Math.abs(pos.getX() - p.getX()) >= RANGE
                || Math.abs(pos.getY() - p.getY()) >= RANGE
                || Math.abs(pos.getZ() - p.getZ()) >= RANGE) {
            return;   // 超出范围只是不画，状态留着（走回来还能看见）
        }

        // 相机位置：渲染视图实体在上一 tick 与本 tick 之间插值（1.12.2 没有现成的 getProjectedView）
        Entity view = mc.getRenderViewEntity();
        float pt = event.getPartialTicks();
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        boolean red = (System.currentTimeMillis() / 500L) % 2L == 0L;
        float r = 1.0F;
        float g = red ? 0.18F : 0.86F;
        float b = red ? 0.18F : 0.20F;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.disableDepth();      // ← 穿墙
        GlStateManager.depthMask(false);    // 不写深度，免得挡住后面的半透明
        GlStateManager.glLineWidth(2.0F);
        GlStateManager.translate(-cx, -cy, -cz);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(org.lwjgl.opengl.GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        box(buf, pos, r, g, b);
        tess.draw();   // 立即上传并绘制

        GlStateManager.glLineWidth(1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /** 一个方块的 12 条棱：先按 0.002 外扩，避免与方块表面 z-fighting */
    private static void box(BufferBuilder buf, BlockPos pos, float r, float g, float b) {
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(0.002D);
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        // 下底
        line(buf, x1, y1, z1, x2, y1, z1, r, g, b);
        line(buf, x2, y1, z1, x2, y1, z2, r, g, b);
        line(buf, x2, y1, z2, x1, y1, z2, r, g, b);
        line(buf, x1, y1, z2, x1, y1, z1, r, g, b);
        // 上顶
        line(buf, x1, y2, z1, x2, y2, z1, r, g, b);
        line(buf, x2, y2, z1, x2, y2, z2, r, g, b);
        line(buf, x2, y2, z2, x1, y2, z2, r, g, b);
        line(buf, x1, y2, z2, x1, y2, z1, r, g, b);
        // 四条竖棱
        line(buf, x1, y1, z1, x1, y2, z1, r, g, b);
        line(buf, x2, y1, z1, x2, y2, z1, r, g, b);
        line(buf, x2, y1, z2, x2, y2, z2, r, g, b);
        line(buf, x1, y1, z2, x1, y2, z2, r, g, b);
    }

    private static void line(BufferBuilder buf,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b) {
        buf.pos(x1, y1, z1).color(r, g, b, 1.0F).endVertex();
        buf.pos(x2, y2, z2).color(r, g, b, 1.0F).endVertex();
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
