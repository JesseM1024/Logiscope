package com.jessetech.logiscope.collect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 两只**全局**时钟（第八轮 N13 定的分工，第九轮从 WorldProbe 里抽出来）。
 *
 * 为什么必须抽出来单独放：第九轮之后"世界侧读取器"（{@link WorldProbe}）
 * 变成**每个探针实例一份**，但"现在世界走到第几 tick""客户端 tick 数到几了"
 * 是全世界唯一的一份。如果还留在实例里，删掉一个实例就会把时钟一起清掉，
 * 其余实例的采样节奏会跟着乱。
 *
 * | 钟 | 用途 | 为什么不能用另一只 |
 * |---|---|---|
 * | {@link #worldGameTime} 权威世界 tick | 样本时间戳、"世界没走 tick 就跳过采样" | 客户端 {@code Level#getGameTime()} 是错拍阶梯：客户端每客户端 tick 本地 +1，服务端每 20 个**服务端** tick 才砸一次绝对值纠正。tick rate 20 时两者恰好重合（所以早期测不出来），tick rate ≠ 20 时采样节奏与数值全错（第八轮实测） |
 * | {@link #clientTick} 客户端 tick | 异步读值的"新鲜度"（最多几名客户端 tick 前的值算新） | 读值延迟是**真实时间量**（提交到服务端线程，1~2 个客户端 tick 后回来），与世界流速无关。tick rate 200 时按世界 tick 判新鲜会把刚回来的真值判成超龄 |
 *
 * 单人存档：每客户端 tick 往服务端线程丢一个任务读 {@code ServerLevel#getGameTime()}，
 * 结果写进 volatile —— 权威、随 {@code /tick rate} 正确伸缩、ESC 冻结时也冻结。
 * 多人 / 主菜单：没有集成服务端，退回客户端值（语义退化为"客户端近似"）。
 */
public final class ServerClock {

    /**
     * 客户端 tick 计数：异步读取的"新鲜度"用它判定，**不用世界 tick**。
     * 读值延迟是真实时间量，与世界流速无关（见类注释）。
     */
    private static volatile long clientTick;

    /**
     * 权威世界 tick：服务端线程读出的 {@code ServerLevel#getGameTime()}，-1 = 不可用。
     */
    private static volatile long serverGameTime = -1;

    private ServerClock() {
    }

    /** 客户端 tick 数（只读） */
    public static long clientTick() {
        return clientTick;
    }

    /**
     * 由 {@code ProbeManager#onClientTick} 每客户端 tick 调一次：
     * 推进客户端 tick 计数 + 刷新权威世界 tick。
     */
    public static void tickClient() {
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            // 离开单人存档（断线 / 回主菜单）：权威值作废，防止跨世界串值
            serverGameTime = -1;
            return;
        }
        try {
            server.execute(() -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                if (level != null) {
                    serverGameTime = level.getGameTime();
                }
            });
        } catch (Throwable ignored) {
            // 服务器正在关闭：下一客户端 tick 会重试
        }
    }

    /**
     * 权威世界 tick。有集成服务端就用服务端线程读出的真值；
     * 还没读到过 / 多人 → 退回 fallback（客户端 {@code Level#getGameTime()}）。
     */
    public static long worldGameTime(long fallback) {
        long t = serverGameTime;
        return t >= 0 ? t : fallback;
    }
}
