package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.analysis.NbtSlotLayout;
import com.jessetech.logiscope.analysis.Snbt;
import com.jessetech.logiscope.analysis.Val;
import com.jessetech.logiscope.bind.DataTarget;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.tileentity.TileEntity;
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
    public Val readNumber(WorldServer level, DataTarget target) {
        NBTBase tag = navigate(readRoot(level, target.pos()), Snbt.parsePath(target.valuePath()));
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
     * （`TileEntity#saveId` 写的正是它）。记不到类型（beType 为空）就退回老行为。
     *
     * @return null = 这台设备现在读不到（方块实体没了 / 区块不在 / 换了设备）→ 上层记 no_data；
     *         非 null = 这一刻的值（元素不存在时是 `count = 0` 的空条目）
     */
    @Nullable
    public NbtSlotLayout.Entry readSlotAnchor(WorldServer level, DataTarget target) {
        TileEntity be = level.getTileEntity(target.pos());
        if (be == null) {
            return null;
        }
        if (!sameBlockEntityType(be, target.beType())) {
            return null;
        }
        NBTTagCompound root = be.writeToNBT(new NBTTagCompound());
        Snbt.Node tree = Snbt.parse(root.toString());
        NbtSlotLayout.Entry e = NbtSlotLayout.bySlot(tree, target.valuePath(), target.nbtSlot());
        if (e == null) {
            // 格子空了（机器把元素从列表里删掉了）：数量 0，名称沿用选择时记下的那个
            return new NbtSlotLayout.Entry(target.nbtSlot(), null, 0);
        }
        return e;
    }

    /** 现在这块地方的方块实体，还是锚点建档时那一种吗（记不到类型时一律当作"是"） */
    private static boolean sameBlockEntityType(TileEntity be, @Nullable String want) {
        if (want == null || want.trim().isEmpty()) {
            return true;
        }
        ResourceLocation now = TileEntity.getKey(be.getClass());
        return now != null && want.equals(now.toString());
    }

    /** 按路径取字符串（给"名称来自 NBT"的场景用） */
    @Nullable
    public String readString(WorldServer level, String path, BlockPos pos) {
        NBTBase tag = navigate(readRoot(level, pos), Snbt.parsePath(path));
        return tag instanceof NBTTagString ? ((NBTTagString) tag).getString() : null;
    }

    /**
     * 方块实体的整份 NBT。
     *
     * 用 `saveWithoutMetadata`（不含 id/x/y/z 之外的元数据）而不是 `saveWithFullMetadata`：
     * 前者是"数据内容"，后者还带方块实体的额外元信息 —— 取值只要前者。
     * registryAccess 是 1.21 的新要求（`World#registryAccess()`，已核实）。
     */
    @Nullable
    private NBTTagCompound readRoot(WorldServer level, BlockPos pos) {
        // 曾试过的"方块 tick 判据"已回退：仍按原样读；
        // 区块真卸载时 getBlockEntity 拿不到方块实体 → 返回 null。
        TileEntity be = level.getTileEntity(pos);
        if (be == null) {
            return null;
        }
        return be.writeToNBT(new NBTTagCompound());
    }

    /** 沿路径走。任一段不存在就返回 null（不猜、不返回 0） */
    @Nullable
    public static NBTBase navigate(@Nullable NBTBase root, List<Snbt.Token> path) {
        NBTBase cur = root;
        for (Snbt.Token tok : path) {
            if (cur == null) {
                return null;
            }
            if (tok.isIndex()) {
                if (cur instanceof NBTTagList) {
                    NBTTagList list = (NBTTagList) cur;
                    cur = tok.index() >= 0 && tok.index() < list.tagCount() ? list.get(tok.index()) : null;
                } else if (cur instanceof NBTTagCompound) {
                    NBTTagCompound c = (NBTTagCompound) cur;
                    // 少数模组把数组存成 {"0":..,"1":..}：容忍这种情况
                    cur = c.getTag(String.valueOf(tok.index()));
                } else {
                    cur = null;
                }
            } else {
                cur = cur instanceof NBTTagCompound && ((NBTTagCompound) cur).hasKey(tok.key())
                        ? ((NBTTagCompound) cur).getTag(tok.key()) : null;
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
    public static Val asVal(@Nullable NBTBase tag) {
        if (!(tag instanceof NBTPrimitive)) {
            return null;
        }
        NBTPrimitive n = (NBTPrimitive) tag;
        if (tag instanceof NBTTagFloat || tag instanceof NBTTagDouble) {
            return Val.of(n.getDouble());
        }
        return Val.of(n.getLong());
    }

    /**
     * **强制数值模式**的读数：数值标签照 {@link #asVal}；失败时再试
     * **字符串解析**（{@link Val#parseNumericText}）。任意文本 / 列表 / 复合 /
     * 空值 → null = 读不到（上层记 no_data）。
     */
    @Nullable
    public static Val asValForced(@Nullable NBTBase tag) {
        Val v = asVal(tag);
        if (v != null) {
            return v;
        }
        return tag instanceof NBTTagString ? Val.parseNumericText(((NBTTagString) tag).getString()) : null;
    }
}
