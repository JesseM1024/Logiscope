package com.jessetech.logiscope.ui;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * 探针追踪目标的**世界内高亮框**：一个能**穿墙**看见的红黄闪烁方块边框。
 *
 * 入口在根页探针列表的十字准星按钮（{@link ScopeScreen}），一次只高亮一个目标。
 *
 * 【为什么是自定义 RenderType】
 * 原版的 {@code RenderType.lines()} 带 {@code LEQUAL} 深度测试 —— 框在墙后面就看不见了。
 * 这里用 {@link RenderType#create} 复制一份 lines 的状态，只把深度测试换成
 * {@link RenderStateShard#NO_DEPTH_TEST}，于是"框永远画在最前面"。
 * 写入掩码用 {@code COLOR_WRITE}（不写深度）：否则这个框会把深度值留在缓冲里，
 * 后面的半透明物体可能被它挡住。
 *
 * 【闪烁】用系统时钟而不是 tick：要求是 1 Hz，tick 会随 {@code /tick rate} 漂移。
 * 每 500 ms 换一次颜色（红 ↔ 黄），一个完整周期 = 1 s。
 *
 * 【可见性条件】每次渲染都重新判定：维度相同 + 玩家三轴距离都 < 128 格
 * （按需求：**不**算欧氏距离，三轴差值单独比）。离开范围/换维度只是**不画**，
 * 高亮状态保留 —— 玩家走回去还能看见。真正清空由探针删除 / 换世界负责。
 */
public final class ProbeHighlight {

    /** 可见范围（每轴，格）。需求：三轴差值绝对值均 < 128 */
    public static final int RANGE = 128;

    /** 懒建的自定义 RenderType：第一次渲染时才建（避免类初始化早于渲染系统就绪） */
    @Nullable
    private static RenderType lineType;

    /** 渲染失败是否已经记过日志（坏在渲染帧里的异常会每帧刷屏） */
    private static boolean warned;

    /** 当前高亮的探针 id（-1 = 没有） */
    private static int probeId = -1;
    @Nullable
    private static ResourceKey<Level> dim;
    @Nullable
    private static BlockPos pos;

    private ProbeHighlight() {
    }

    /** 现在高亮的是这个探针吗（列表按钮据此变色） */
    public static boolean isActiveFor(int id) {
        return probeId != -1 && probeId == id;
    }

    public static boolean isActive() {
        return probeId != -1;
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
     * 渲染入口（注册在 {@code NeoForge.EVENT_BUS} 上）。
     *
     * 用 {@code AFTER_ENTITIES}（不是 {@code AFTER_TRANSLUCENT_BLOCKS}）：后者是跟着
     * 地形图层发的（{@code Stage.fromRenderType} → 在 {@code renderChunkLayer} 里派发），
     * 那一刻我们往共享的 BufferSource 里塞顶点再自己 endBatch，风险更大；
     * {@code AFTER_ENTITIES} 是 {@code renderLevel} 里直接派发的一步，地形与实体都画完了，
     * 而且深度测试已经关掉，谁先谁后都不影响"穿墙可见"。
     */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        try {
            render(event);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /** 真正的绘制：只画一个线框，所有状态由自定义 RenderType 负责 */
    private static void render(RenderLevelStageEvent event) {
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

        RenderType type = lineType();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(type);
        PoseStack pose = event.getPoseStack();
        Camera cam = event.getCamera();
        pose.pushPose();
        // 与原版方块选中框同一套坐标约定：世界坐标减去相机位置
        pose.translate(-cam.getPosition().x, -cam.getPosition().y, -cam.getPosition().z);
        boolean red = (System.currentTimeMillis() / 500L) % 2L == 0L;
        float r = 1.0F;
        float g = red ? 0.18F : 0.86F;
        float b = red ? 0.18F : 0.20F;
        LevelRenderer.renderLineBox(pose, vc, new AABB(pos).inflate(0.002D), r, g, b, 1.0F);
        pose.popPose();
        // 自定义类型不在原版的批次顺序里，必须自己冲掉（否则这一帧什么都看不到）
        buffers.endBatch(type);
    }

    /** 渲染失败只记一次日志：坏在渲染帧里的异常会每帧刷屏，一次就够定位了 */
    private static void warnOnce(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        com.jessetech.logiscope.LogiScopeMod.LOGGER.warn(
                "[LogiScope] highlight box render failed (will not log again)", t);
    }

    private static RenderType lineType() {
        if (lineType == null) {
            lineType = RenderType.create(
                    "logiscope_highlight",
                    DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES,
                    1536,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)   // ← 穿墙
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)     // 不写深度
                            .setOutputState(RenderStateShard.MAIN_TARGET)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return lineType;
    }
}
