package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.analysis.NbtSlotLayout;
import com.jessetech.logiscope.analysis.Snbt;
import com.jessetech.logiscope.analysis.Val;
import com.jessetech.logiscope.bind.DataTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 服务端侧的取值器：**直接读方块实体的 NBT**，按路径取出那个数。
 *
 * 采样不走命令 *   `data get block` 只在"看一眼有什么"时用（界面上的实时显示，见 {@code DataCommand}）。
 *   真正的逐 tick 采样是**服务端线程直接读方块实体**：
 *     · 不发命令、不依赖权限（单人下这是硬需求）
 *     · 没有聊天回执那一路的异步与丢包问题（值和时间戳天然同源，同 CP15 的做法）
 *   "只取需要的那个子标签、别把整个 NBT 都构建出来"是**已知的优化方向**（记录在
 *   PROGRESS.md），先做成"整份 NBT → 按路径取"的直白版本。
 *
 * 路径语法与界面里点出来的一致：{@code a.b} / {@code a[0].b}（见 {@link Snbt#parsePath}）。
 */
public final class DataProbe {

    /**
     * 按路径取数值（**自适应**：NBT 的 byte/short/int/long 走精确 long，
     * float/double 走 double）。null = 没有方块实体 / 路径不存在 / 非数字。
     *
     * {@code target.forceNumeric()}（DATA 页右键标记）时走 {@link #asValForced}：
     * 字符串标签会**尝试解析成数值**（有些模组把数值存成字符串）；不可转换
     * 照常返回 null —— 上层记 no_data，不假装是 0。
     */
    @Nullable
    public Val readNumber(ServerLevel level, DataTarget target) {
        Tag tag = navigate(readRoot(level, target.pos()), Snbt.parsePath(target.valuePath()));
        return target.forceNumeric() ? asValForced(tag) : asVal(tag);
    }

    /**
     * **NBT 槽位目标**的读数：读方块本体 NBT → 解析 →
     * 在 {@code valuePath} 那个列表里找 {@code slot == nbtSlot} 的元素。
     *
     * 元素不在有两种原因，不能混成一件 *   · **还是同一台设备** → 这一格空了 ⇒ `count = 0`
     *     （"这格是空的"就该是 0，不是 no_data —— 设备本身读得到）；
     *   · **这地方换了设备**（换成别的机器 / 箱子 / 空气）→ 那个 slot 号已经**没有意义**了
     *     ⇒ 返回 null 走上层 no_data。
     * 旧实现一律写 0，于是"设备早被拆了"会表现为**一条永远为 0 的假曲线**。
     *
     * 判据 = 方块实体类型：锚点建档时从 `data get block` 的 SNBT 根上记下 `id`，
     * 这里用 `BlockEntityType.getKey(be.getType())` 比 —— 两处是同一个字符串
     * （`BlockEntity#saveId` 写的正是它）。记不到类型（beType 为空）就退回老行为。
     *
     * @return null = 这台设备现在读不到（方块实体没了 / 区块不在 / 换了设备）→ 上层记 no_data；
     *         非 null = 这一刻的值（元素不存在时是 `count = 0` 的空条目）
     */
    @Nullable
    public NbtSlotLayout.Entry readSlotAnchor(ServerLevel level, DataTarget target) {
        BlockEntity be = level.getBlockEntity(target.pos());
        if (be == null) {
            return null;
        }
        if (!sameBlockEntityType(be, target.beType())) {
            return null;
        }
        CompoundTag root = be.saveWithoutMetadata(level.registryAccess());
        Snbt.Node tree = Snbt.parse(root.getAsString());
        NbtSlotLayout.Entry e = NbtSlotLayout.bySlot(tree, target.valuePath(), target.nbtSlot());
        if (e == null) {
            // 格子空了（机器把元素从列表里删掉了）：数量 0，名称沿用选择时记下的那个
            return new NbtSlotLayout.Entry(target.nbtSlot(), null, 0);
        }
        return e;
    }

    /** 现在这块地方的方块实体，还是锚点建档时那一种吗（记不到类型时一律当作"是"） */
    private static boolean sameBlockEntityType(BlockEntity be, @Nullable String want) {
        if (want == null || want.isBlank()) {
            return true;
        }
        ResourceLocation now = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return now != null && want.equals(now.toString());
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
        // 曾试过的"方块 tick 判据"已回退：仍按原样读；
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

    /**
     * 数值标签 → 自适应值。整数标签走 long（精确）：
     * 2^63 的能量读数经 double 会丢精度（2^53 以上），所以必须按标签类型分流，
     * 这正是"追踪创造能量立方"那条实测需求的根。
     */
    @Nullable
    public static Val asVal(@Nullable Tag tag) {
        if (!(tag instanceof NumericTag n)) {
            return null;
        }
        if (tag instanceof FloatTag || tag instanceof DoubleTag) {
            return Val.of(n.getAsDouble());
        }
        return Val.of(n.getAsLong());
    }

    /**
     * **强制数值模式**的读数：数值标签照 {@link #asVal}；失败时再试
     * **字符串解析**（{@link Val#parseNumericText}）。任意文本 / 列表 / 复合 /
     * 空值 → null = 读不到（上层记 no_data）。
     */
    @Nullable
    public static Val asValForced(@Nullable Tag tag) {
        Val v = asVal(tag);
        if (v != null) {
            return v;
        }
        return tag instanceof StringTag s ? Val.parseNumericText(s.getAsString()) : null;
    }
}
