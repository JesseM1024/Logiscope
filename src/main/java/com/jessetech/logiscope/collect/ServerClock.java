package com.jessetech.logiscope.collect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 全局时钟：**权威世界 tick**（单人下由服务端线程读出）。
 *
 * | 用途 | 谁在用 | 为什么不能用客户端 `Level#getGameTime()` |
 * |---|---|---|
 * | 状态机的时间刻度（"世界有没有走 tick"） | `ProbeManager#tick` → `ProbeInstance#tick` | 客户端 gameTime 是**错拍阶梯**：客户端每客户端 tick 本地 +1，服务端每 20 个服务端 tick 才砸一次绝对值纠正。tick rate 20 时两者恰好重合（所以早期测不出来），tick rate ≠ 20 时采样节奏与数值全错（第八轮 N13） |
 *
 * ★ 第十轮第四次修复：采样本身**不再用这只钟** ★
 *   单人下的样本时间戳直接取服务端 tick 那一刻的 `ServerLevel#getGameTime()`
 *   （见 `ProbeInstance#sampleOnServerTick`）—— 那只钟是采样点自己的钟，
 *   比这里"异步读回来"的值更准。本类现在只服务**回退路径**
 *   （多人/无坐标时由客户端 tick 驱动的菜单采样）与状态机。
 *
 * 单人存档：每客户端 tick 往服务端线程丢一个任务读 {@code ServerLevel#getGameTime()}，
 * 结果写进 volatile —— 权威、随 {@code /tick rate} 正确伸缩、ESC 冻结时也冻结。
 * 多人 / 主菜单：没有集成服务端，退回客户端值（语义退化为"客户端近似"）。
 */
public final class ServerClock {

    /**
     * 权威世界 tick：服务端线程读出的 {@code ServerLevel#getGameTime()}，-1 = 不可用。
     */
    private static volatile long serverGameTime = -1;

    private ServerClock() {
    }

    /**
     * 由 {@code ProbeManager#onClientTick} 每客户端 tick 调一次：刷新权威世界 tick。
     */
    public static void tickClient() {
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
