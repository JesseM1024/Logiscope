package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.analysis.NbtSlotLayout;
import com.jessetech.logiscope.analysis.Snbt;
import com.jessetech.logiscope.bind.DataTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 服务端侧的取值器（第十一轮③）：**直接读方块实体的 NBT**，按路径取出那个数。
 *
 * ★ 采样不走命令 ★
 *   `data get block` 只在"看一眼有什么"时用（界面上的实时显示，见 {@code DataCommand}）。
 *   真正的逐 tick 采样是**服务端线程直接读方块实体**：
 *     · 不发命令、不依赖权限（单人下这是硬需求）
 *     · 没有聊天回执那一路的异步与丢包问题（值和时间戳天然同源，同 CP15 的做法）
 *   "只取需要的那个子标签、别把整个 NBT 都构建出来"是**已知的优化方向**（记录在
 *   PROGRESS.md），本轮先做成"整份 NBT → 按路径取"的直白版本。
 *
 * 路径语法与界面里点出来的一致：{@code a.b} / {@code a[0].b}（见 {@link Snbt#parsePath}）。
 */
public final class DataProbe {

    /** 按路径取数值。null = 没有方块实体 / 路径不存在 / 那个值不是数字 */
    @Nullable
    public Double readNumber(ServerLevel level, DataTarget target) {
        Tag tag = navigate(readRoot(level, target.pos()), Snbt.parsePath(target.valuePath()));
        return asDouble(tag);
    }

    /**
     * **NBT 槽位目标**的读数（第十六轮）：
     * 读方块本体 NBT → 解析 → 在 {@code valuePath} 那个列表里找 {@code slot == nbtSlot} 的元素。
     *
     * @return null = 这台设备现在读不到（方块实体没了 / 区块不在）→ 上层记 no_data；
     *         非 null = 这一刻的值。元素**不存在**时返回一条 {@code count = 0} 的空条目 ——
     *         "这一格是空的"就该是 0（而不是 no_data，因为设备本身读得到）。
     */
    @Nullable
    public NbtSlotLayout.Entry readSlotAnchor(ServerLevel level, DataTarget target) {
        CompoundTag root = readRoot(level, target.pos());
        if (root == null) {
            return null;
        }
        Snbt.Node tree = Snbt.parse(root.getAsString());
        NbtSlotLayout.Entry e = NbtSlotLayout.bySlot(tree, target.valuePath(), target.nbtSlot());
        if (e == null) {
            // 格子空了（机器把元素从列表里删掉了）：数量 0，名称沿用选择时记下的那个
            return new NbtSlotLayout.Entry(target.nbtSlot(), null, 0);
        }
        return e;
    }

    /** 按路径取字符串（给"名称来自 NBT"的场景用） */
    @Nullable
    public String readString(ServerLevel level, String path, BlockPos pos) {
        Tag tag = navigate(readRoot(level, pos), Snbt.parsePath(path));
        return tag instanceof StringTag s ? s.getAsString() : null;
    }

    /**
     * 方块实体的整份 NBT。
     *
     * 用 `saveWithoutMetadata`（不含 id/x/y/z 之外的元数据）而不是 `saveWithFullMetadata`：
     * 前者是"数据内容"，后者还带方块实体的额外元信息 —— 取值只要前者。
     * registryAccess 是 1.21 的新要求（`Level#registryAccess()`，已核实）。
     */
    @Nullable
    private CompoundTag readRoot(ServerLevel level, BlockPos pos) {
        // 第十四轮的"方块 tick 判据"已回退（用户判定不需要）：仍按原样读；
        // 区块真卸载时 getBlockEntity 拿不到方块实体 → 返回 null。
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        return be.saveWithoutMetadata(level.registryAccess());
    }

    /** 沿路径走。任一段不存在就返回 null（不猜、不返回 0） */
    @Nullable
    public static Tag navigate(@Nullable Tag root, List<Snbt.Token> path) {
        Tag cur = root;
        for (Snbt.Token tok : path) {
            if (cur == null) {
                return null;
            }
            if (tok.isIndex()) {
                if (cur instanceof ListTag list) {
                    cur = tok.index() >= 0 && tok.index() < list.size() ? list.get(tok.index()) : null;
                } else if (cur instanceof CompoundTag c) {
                    // 少数模组把数组存成 {"0":..,"1":..}：容忍这种情况
                    cur = c.get(String.valueOf(tok.index()));
                } else {
                    cur = null;
                }
            } else {
                cur = cur instanceof CompoundTag c && c.contains(tok.key()) ? c.get(tok.key()) : null;
            }
        }
        return cur;
    }

    /** 数值标签 → double（byte/short/int/long/float/double 都算数字） */
    @Nullable
    public static Double asDouble(@Nullable Tag tag) {
        if (tag instanceof NumericTag n) {
            return n.getAsDouble();
        }
        return null;
    }
}
