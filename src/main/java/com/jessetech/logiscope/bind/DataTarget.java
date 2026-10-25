package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.analysis.AeKeyText;
import com.jessetech.logiscope.analysis.RsKeyText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 数据探针的目标：**从方块实体的 NBT 里按路径取一个数** ——
 * 用来追踪物品之外的东西：FE 能量、流体量、化学品的量，以及任何能在 NBT 里
 * 找到的数字（"物品"这个概念套不住它们，所以走独立的一条采样路）。
 *
 * 【三段信息】
 *   · {@code valuePath}  采样值的路径（如 {@code energy} / {@code tanks[0].amount}）
 *   · {@code namePath}   **可选**：名称也来自 NBT 的某个路径 —— 用于"可变物品"
 *     （流体 / 化学品：罐里装的东西会变，名称就得跟着变）。每 tick 解析一次，
 *     于是"换了一种流体"在图上是**换了一条线**，语义是对的。
 *   · {@code literalName} 名称的字面量（用户手输，例如 {@code energy}）——
 *     namePath 为空或解析不到时用它兜底
 *
 * 【为什么名称要有两条来源】用户的原话：可变物品（流体、化学品）可以"选择一个值"
 * 当名称，而电力消耗"可能没名字"，就得手输。两者都要支持，所以这里是三条信息
 * 而不是一条。
 *
 * @param blockLabel 设备名（方块名），只用于显示
 * @param beType     建档时那台**方块实体的类型 id**（如 {@code mekanism:factory}，可空）。
 *                   只给 NBT 槽位锚点用：采样时拿它跟"现在那块地方是什么方块实体"比，
 *                   不一样就说明"这个 slot 号已经没有意义了"，该记 `no_data` 而不是记 0
 *                   （见 {@code DataProbe#readSlotAnchor} 与 ARCHITECTURE C24-10 的"读不到 vs 空格子"）。
 * @param forceNumeric **强制按数值读取**（DATA 页在值列表上**右键填入值格**时标记的）：
 *                   读取时若标签是字符串，尝试把它解析成数值；不可转换照常记
 *                   {@code no_data}。用于"数值被模组存成字符串"的键
 *                   （如 {@code "energy": "1000"}）。只影响值的读取，不影响名称。
 */
public record DataTarget(
        String valuePath,
        @Nullable String namePath,
        String literalName,
        String blockLabel,
        ResourceKey<Level> dimension,
        BlockPos pos,
        int nbtSlot,
        @Nullable String beType,
        boolean forceNumeric) {

    /** 普通路径目标（非强制数值） */
    public DataTarget(String valuePath, @Nullable String namePath, String literalName,
                      String blockLabel, ResourceKey<Level> dimension, BlockPos pos) {
        this(valuePath, namePath, literalName, blockLabel, dimension, pos, -1, null, false);
    }

    // ------------------------------------------------------------------
    // AE 网络目标
    // ------------------------------------------------------------------

    /**
     * AE 网络目标的 {@code valuePath} 固定值 —— **一个标记，不是路径**：
     * AE 终端的"格子"不是容器内容（是网络存储列表的显示窗口），所以这类目标
     * 不走 NBT / 菜单，走 {@code AeNetworkReader} 的反射链读"网络中该物品的可用数量"。
     */
    public static final String AE_NETWORK_PATH = "ae:network";

    /**
     * 精致存储（RS）网络目标的 {@code valuePath} 固定值 —— 同样是**标记不是路径**。
     *
     * 与 {@link #AE_NETWORK_PATH} 分开两个标记（而不是共用一个）：两类网络的读值链
     * 完全不同（AE 走 {@code GridHelper} + KeyCounter，RS 走 {@code Grid#getItemStorage}
     * + 自己的编码比对），共用标记会让"这台设备到底是哪家的"在数据层丢失 ——
     * 而采样分派必须知道该调哪个读值器。
     */
    public static final String RS_NETWORK_PATH = "rs:network";

    /**
     * **AE 网络目标**：监测 {@code itemId}（registry id）在探针所对准的那台
     * AE 设备所在网络里的可用数量。
     * 读值 = 服务端从设备拿 IGrid → StorageService → availableStacks（KeyCounter）
     * → 按 AEItemKey 取数量；物品不在网内 = 0（网络在线），设备不可用 = no_data。
     */
    public static DataTarget aeNetwork(String itemId, String blockLabel,
                                       ResourceKey<Level> dimension, BlockPos pos) {
        return new DataTarget(AE_NETWORK_PATH, null, itemId, blockLabel, dimension, pos, -1, null, false);
    }

    /**
     * **RS 网络目标**：监测 {@code keyId}（见 {@code RsKeyText}：无组件物品用裸
     * registry id，其余用 {@code rs:} 串）在探针所对准的那台 RS 网格设备所在网络里的数量。
     * 读值 = 服务端从设备拿 Grid → 根存储的全量清单 → 按身份串比对求和；
     * 不在网内 = 0（网络在线），设备不可用 / 断电 = no_data。
     */
    public static DataTarget rsNetwork(String keyId, String blockLabel,
                                       ResourceKey<Level> dimension, BlockPos pos) {
        return new DataTarget(RS_NETWORK_PATH, null, keyId, blockLabel, dimension, pos, -1, null, false);
    }

    /** 是不是 AE 网络目标 */
    public boolean isAeNetwork() {
        return AE_NETWORK_PATH.equals(valuePath);
    }

    /** 是不是 RS 网络目标 */
    public boolean isRsNetwork() {
        return RS_NETWORK_PATH.equals(valuePath);
    }

    /**
     * 是不是**网络类**目标（AE 或 RS）。
     * 两类共用三件事：采样分派的入口、去重规则、"没有容器格"这个前提。
     * 需要区分是哪一家时用 {@link #isAeNetwork()} / {@link #isRsNetwork()}。
     */
    public boolean isNetwork() {
        return isAeNetwork() || isRsNetwork();
    }

    /**
     * **NBT 槽位目标**：{@code valuePath} = 物品列表在 NBT 里的路径
     * （如 {@code items}），{@code nbtSlot} = 元素自带 {@code slot} 号。
     * 读数 = "列表里 slot == nbtSlot 的元素的 Item.Count"。
     *
     * 这一路**完全不碰菜单**，所以 GUI 开不开读数都一样 —— 正是 MEK 那类
     * "槽位号写在 NBT 里"的机器需要的语义。
     *
     * @param beType 建档时的方块实体类型 id；给不出就传 null（那就退回"读不到就记 0"的老行为）
     */
    public static DataTarget slotAnchor(String listPath, String itemId, String blockLabel,
                                        ResourceKey<Level> dimension, BlockPos pos, int slot,
                                        @Nullable String beType) {
        return new DataTarget(listPath, null, itemId, blockLabel, dimension, pos, slot, beType, false);
    }

    /** 是不是 NBT 槽位目标 */
    public boolean isSlotAnchor() {
        return nbtSlot >= 0;
    }

    public boolean hasNamePath() {
        return namePath != null && !namePath.isBlank();
    }

    /** 名称兜底：手输的名字 → 没有就用值路径（认得出是哪个数） */
    public String fallbackName() {
        if (literalName != null && !literalName.isBlank()) {
            return literalName;
        }
        return valuePath == null || valuePath.isBlank() ? "value" : valuePath;
    }

    /**
     * **目标去重规则**。
     *
     * | 目标类型 | 重复判据 | 为什么 |
     * |---|---|---|
     * | NBT 槽位锚点（{@code isSlotAnchor}） | (列表路径, slot 号) 相同 | 同一 slot 只该有一条 |
     * | **网络目标**（AE / RS） | 标记相同 **且** {@code literalName}（身份串）相同 | 读的是"网络中这种东西"，名字就是身份；valuePath 全是标记值，**绝不能拿它去重**（不然所有网络目标互判重复）。标记也要比：AE 与 RS 的同名身份是两种东西 |
     * | 普通路径 | valuePath 相同 | 一个路径一个数 |
     *
     * 普通路径的强制数值标记（{@code forceNumeric}）**不参与**去重 —— 同一路径
     * 仍然只保留一个目标；换口径（自动 ⇄ 强制）或改名称走 DATA 页「确认添加」的
     * 替换（{@code ProbeInstance#replaceDataTarget}）。
     */
    public static boolean isDuplicate(List<DataTarget> list, DataTarget t) {
        for (DataTarget d : list) {
            boolean dup;
            if (t.isNetwork() && d.isNetwork()) {
                dup = d.valuePath().equals(t.valuePath()) && d.literalName().equals(t.literalName());
            } else if (d.isSlotAnchor() && t.isSlotAnchor()) {
                dup = d.nbtSlot() == t.nbtSlot() && d.valuePath().equals(t.valuePath());
            } else {
                dup = !d.isSlotAnchor() && !t.isSlotAnchor()
                        && !d.isNetwork() && !t.isNetwork()
                        && d.valuePath().equals(t.valuePath());
            }
            if (dup) {
                return true;
            }
        }
        return false;
    }

    /**
     * 锚点的显示写法（{@code overworld 10,64,10}）。
     *
     * **空锚点是合法状态，不是异常**：模组界面用无线终端 / 快捷键打开时，客户端没有
     * "右键方块"那一刻，维度与坐标都拿不到（见 {@code ProbeContext} 的第三种来源）。
     * 这种目标在显示层只能标成"运行时锚点" —— 0.1.3 的 AE 监测闪退就是这里直接解引用
     * {@code dimension.location()}（dimension 为 null）。"AE 网络目标必须有锚点才能读值"
     * 这条约束由 {@code ScopeScreen#commitNetworkTargets} 拦，不归显示层管。
     */
    public String anchorText() {
        if (dimension == null || pos == null) {
            return Component.translatable("logiscope.anchor.runtime").getString();
        }
        return dimension.location().getPath() + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 目标列表里那一行的"值"部分：锚点带上它自己的 slot 号。
     * 只影响**显示**（以及用户按格删改），采样用的名字仍是物品 id —— 所以
     * "同种物品多格求和"不受这个后缀影响（需求：聚合计算不要被后缀干扰）。*/
    public String displayValue() {
        if (isAeNetwork()) {
            // 物品目标原样显示 registry id（老样子）；非物品键是一整段 SNBT，只显示人能认的名字
            String name = AeKeyText.isAe(fallbackName())
                    ? AeKeyText.label(fallbackName())
                    : fallbackName();
            return AE_NETWORK_PATH + "[" + name + "]";
        }
        if (isRsNetwork()) {
            // RS 身份串：裸物品 id 走短名，rs: 串走 RsKeyText（去掉命名空间 + 丢掉组件指纹）
            return RS_NETWORK_PATH + "[" + RsKeyText.label(fallbackName()) + "]";
        }
        return isSlotAnchor() ? valuePath + "[slot=" + nbtSlot + "]" : valuePath;
    }

    /** 面板上的一行描述 */
    public String describe() {
        String label = blockLabel == null || blockLabel.isBlank() ? "block" : blockLabel;
        if (isNetwork()) {
            return label + " · " + displayValue() + " · " + anchorText();
        }
        if (isSlotAnchor()) {
            return label + " · " + valuePath + "[slot=" + nbtSlot + "] · " + anchorText();
        }
        return label + " · " + valuePath + " · " + anchorText();
    }
}
