package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.LogiScopeMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.RenderLevelLastEvent;
import org.jetbrains.annotations.Nullable;
import com.mojang.math.Matrix4f;

/**
 * 探针追踪目标的**世界内高亮框**：一个能**穿墙**看见的红黄闪烁方块边框。
 *
 * 入口在根页探针列表的十字准星按钮（{@link ScopeScreen}），一次只高亮一个目标。
 *
 * 【本线为什么不用自定义 RenderType（与 1.21.1 不同）】
 * 那一套要靠 {@code RenderStateShard} 里的 {@code NO_DEPTH_TEST} / {@code COLOR_WRITE} 等常量，
 * 而 1.20.1 里它们是 **protected**（`RenderType` 同包才看得见），外部拿不到；
 * 硬拼一个 CompositeState 又没有 shader state 可用（builder 会拒绝）。
 * 所以这里走**立即模式**：自己开 GL 状态 → 塞顶点 → {@code Tesselator.end()} 直接画 →
 * 立刻还原状态。绕过 RenderType 的状态机，关掉的深度测试不会被它重新打开。
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
    private static ResourceKey<Level> dim;
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
    public static void set(int id, ResourceKey<Level> dimension, BlockPos target) {
        probeId = id;
        dim = dimension;
        pos = target.immutable();
    }

    /** 关掉高亮（再次点击 / 探针被删除 / 换世界） */
    public static void clear() {
        probeId = -1;
        dim = null;
        pos = null;
    }

    /**
     * 渲染入口（注册在 {@code MinecraftForge.EVENT_BUS} 上）。
     *
     * 本线的事件只有"跟着地形图层发"的那几档（{@code AFTER_TRANSLUCENT_BLOCKS} 等，
     * 没有 NeoForge 的 {@code AFTER_ENTITIES}）。选半透明之后：此时地形都已画完，
     * 而我们是立即模式 + 关深度，不会被后画的方块挡住。
     */
    public static void onRenderLevel(RenderLevelLastEvent event) {
        try {
            render(event);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    private static void render(RenderLevelLastEvent event) {
        if (probeId == -1 || dim == null || pos == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.dimension().equals(dim)) {
            return;
        }
        BlockPos p = mc.player.blockPosition();
        if (Math.abs(pos.getX() - p.getX()) >= RANGE
                || Math.abs(pos.getY() - p.getY()) >= RANGE
                || Math.abs(pos.getZ() - p.getZ()) >= RANGE) {
            return;   // 超出范围只是不画，状态留着（走回来还能看见）
        }

        PoseStack pose = event.getPoseStack();
        Camera cam = mc.gameRenderer.getMainCamera();
        pose.pushPose();
        // 与原版方块选中框同一套坐标约定：世界坐标减去相机位置
        pose.translate(-cam.getPosition().x, -cam.getPosition().y, -cam.getPosition().z);
        Matrix4f mat = pose.last().pose();

        boolean red = (System.currentTimeMillis() / 500L) % 2L == 0L;
        float r = 1.0F;
        float g = red ? 0.18F : 0.86F;
        float b = red ? 0.18F : 0.20F;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();    // ← 穿墙
        RenderSystem.depthMask(false);      // 不写深度，免得挡住后面的半透明
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.0F);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        box(buf, mat, pos, r, g, b);
        tess.end();   // 立即上传并绘制（不经过 RenderType 的状态机）

        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /** 一个方块的 12 条棱：先按 0.002 外扩，避免与方块表面 z-fighting */
    private static void box(BufferBuilder buf, Matrix4f mat, BlockPos pos, float r, float g, float b) {
        AABB box = new AABB(pos).inflate(0.002D);
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
        buf.vertex(mat, (float) x1, (float) y1, (float) z1).color(r, g, b, 1.0F).endVertex();
        buf.vertex(mat, (float) x2, (float) y2, (float) z2).color(r, g, b, 1.0F).endVertex();
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
