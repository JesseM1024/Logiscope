package com.jessetech.logiscope.store;

/**
 * 一次采样：在第 tick 个游戏刻，被探测的槽位里是 itemId × count。
 *
 * 刻意不引用 ItemStack / 世界对象，只保留可序列化的最小信息 ——
 * 这样存储层和分析层都能脱离游戏运行（方便写单元测试、导出、离线画图）。
 *
 * 【三种样本：数据 / 无数据 / 中断】
 *
 * | 种类 | itemId | 含义 |
 * |---|---|---|
 * | 数据 | 真实物品 id（或 {@link #EMPTY_ITEM} = 格子里是空的） | 这一刻**观测到了**内容 |
 * | 无数据 {@link #noData} | `logiscope:no_data` | 这一刻**试着读了但读不到**（区块卸载 / 多人 / 方块没了） |
 * | 中断 {@link #gap} | `logiscope:gap` | 从这一刻起**没在记录**（用户暂停 / 数据源失效自动暂停） |
 *
 * ★ 为什么要区分"无数据"和"中断"（第十轮第三次修复新增）★
 *   两者在**统计**上行为相同（都不是数据，都不进 min/max/avg，都让连线断开），
 *   但在**读数**上必须分开：
 *     · `no-data N` 是给用户看的"读不到的次数"——把用户自己按的暂停也算进去，
 *       会让人以为读取出了问题；
 *     · 反过来，"暂停"在波形上又**必须**断开，否则恢复之后那一段会被连起来，
 *       看起来就像"东西一直在"（编造数据）。
 *   所以中断单独记一种样本：统计上透明，画图上断电。
 *
 * 【为什么"没观测到"要写一条样本，而不是干脆跳过】
 *   时间轴必须留痕。跳过的话，分析层无法区分"什么都没发生"和"我们没看着"，
 *   跨断档的差值会被算成真实变化 —— 那正是这个工具最不能犯的错。
 */
public record Sample(long tick, String itemId, int count) {

    /** 空槽位（不是物品，是"这个格子里什么都没有"） */
    public static final String EMPTY_ITEM = "minecraft:air";

    /** 无数据哨兵：这一 tick 试着读了，但读不到 */
    public static final String NO_DATA_ITEM = "logiscope:no_data";

    /** 中断哨兵：从这一 tick 起没在记录（暂停 / 数据源失效） */
    public static final String GAP_ITEM = "logiscope:gap";

    /** 造一条"无数据"样本 */
    public static Sample noData(long tick) {
        return new Sample(tick, NO_DATA_ITEM, 0);
    }

    /** 造一条"中断"样本（记录被暂停的那一刻） */
    public static Sample gap(long tick) {
        return new Sample(tick, GAP_ITEM, 0);
    }

    /** 这一 tick 是不是"读了但读不到"（与"格子是空的"、"暂停了"都是两件事） */
    public boolean isNoData() {
        return NO_DATA_ITEM.equals(itemId);
    }

    /** 这一 tick 是不是"中断标记"（记录停下来了） */
    public boolean isGap() {
        return GAP_ITEM.equals(itemId);
    }

    /** 标记类样本：既不是数据、也不是空槽。统计与画图都按"这里什么都没有"处理 */
    public boolean isMarker() {
        return isNoData() || isGap();
    }

    /** 格子是空的（有数据，但内容为空） */
    public boolean isEmpty() {
        return !isMarker() && (count <= 0 || EMPTY_ITEM.equals(itemId));
    }

    public String shortName() {
        if (isNoData()) {
            return "(no data)";
        }
        if (isGap()) {
            return "(gap)";
        }
        int i = itemId.indexOf(':');
        return i < 0 ? itemId : itemId.substring(i + 1);
    }
}
