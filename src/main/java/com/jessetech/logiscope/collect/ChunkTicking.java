package com.jessetech.logiscope.collect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * "这台设备现在到底跑没跑"的判定（第十三轮①修：B5 失效的根因）。
 *
 * ★ 为什么不能用 {@code Level#isLoaded()} ★
 * `isLoaded(pos)` 只回答"这个区块在区块表里吗" —— **边界区块**（渲染距离内、
 * 模拟距离外）也在表里，但**不做方块 tick**。那些区块里的机器是停的，
 * 方块实体的 NBT 停在最后一次 tick 的状态。于是我们每 tick 读到的都是
 * **同一个冻结值**：曲线看起来像"设备稳定运行在这个数"，实际是**已经卸载了**。
 * 用户实测的现象正是这个（原版容器 / 模组容器 / 方块实体 NBT 三路都在写旧值）。
 *
 * ★ 正解：与 vanilla 同源 ★
 * 服务端决定"这一格要不要 tick 方块"用的是
 * `ServerLevel#shouldTickBlocksAt(chunkPosLong)`（内部 = 距离管理器的
 * block-ticking 范围，即模拟距离）。我们照抄这一条，语义与游戏完全一致：
 *   · 在范围内 → 机器在跑，读到的是真值；
 *   · 出范围（走得远 / 换维度 / 区块卸载）→ 机器不跑，**如实记 no_data**，
 *     不写冻结值（写作 0 或旧值都是在编数据）。
 *
 * 顺带的收益：这条判断只看距离管理器，**不会去加载区块**（`getBlockEntity`
 * 会经 `getChunkAt` 强制加载，那既慢又会把区块留在内存里）。
 */
public final class ChunkTicking {

    private ChunkTicking() {
    }

    /** 这一格所在的区块是否在"方块 tick"范围里（= 设备真的在运行） */
    public static boolean ticking(ServerLevel level, BlockPos pos) {
        long chunk = ChunkPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return level.shouldTickBlocksAt(chunk);
    }
}
