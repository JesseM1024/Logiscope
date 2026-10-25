package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

/**
 * 精致存储（Refined Storage 2）资源的**字符串身份**（纯文本层，无游戏依赖 ——
 * 离线断言覆盖，TestChart t50）。
 *
 * 【为什么需要另一套前缀】
 * RS 的每种存储物都是一个 {@code ResourceKey}。它和 AE 是两套彼此无关的类型系统，
 * 身份串必须能一眼分出"这是哪个模组的东西"，否则同一个 {@code minecraft:water}
 * 在两套网络里会互相冒充（AE 的水是 {@code ae:{#t:"ae2:f",...}}，RS 的水是另一支）。
 *
 * 【三种身份，与"物品共用裸 id"这条约定】
 * | 字符串 | 含义 | 显示名（注册名档） |
 * |---|---|---|
 * | {@code minecraft:iron_ingot} | **无数据组件的物品** —— 与 AE 物品、容器槽位共用同一个身份 | {@code iron_ingot} |
 * | {@code rs:i:minecraft:potion|{...}} | 带数据组件的物品（附魔书 / 药水 / 有 lore 的物品…） | {@code potion} |
 * | {@code rs:f:minecraft:water} | 流体 | {@code water} |
 * | {@code rs:f:minecraft:water|{...}} | 带组件的流体（罕见） | {@code water} |
 *
 * **为什么无组件物品不挂前缀**：RS 的 {@code ItemResource(item, 空组件)} 和
 * "原版的一件铁锭"是同一个东西。共用裸 registry id 有三个直接好处 ——
 * ① 名称解析（{@code NameText}）不必认识 RS；② 同一件东西在 AE 网络与 RS 网络里
 * 天然是同一条曲线名（一个探针只对准一台设备，两套网络不会在同一探针里撞车）；
 * ③ 旧存档 / 旧目标不受影响。
 *
 * **为什么组件要进身份**：RS 自己就按 {@code ItemResource.equals} 区分组件
 * （{@code item} + {@code DataComponentPatch}）。把两瓶不同的药水塌成一个身份，
 * 就是项目第一条纪律里的"同名不同物"——读出来的数字会是两瓶相加，而界面上只有一行。
 *
 * 【已知限制，已接受】组件指纹用 {@code 组件类型=值.toString()} 的排序拼接
 * （见 {@code bind/RsKeys#fingerprint}）。{@code toString()} 不是 Mojang 承诺的
 * 稳定序列化格式：同一 MC 版本内**由同一个补丁对象算出来的字符串恒定**（这正是
 * 采样需要的），但跨版本 / 两个不同模组手写的 toString 有可能撞车。撞车的后果是
 * 两行合成一行（数量相加），**不会**读出一个假值 —— 这是能接受的降级方向。
 */
public final class RsKeyText {

    /** RS 身份串的前缀。物品 id 形如 {@code ns:path}（恰好一个冒号），不会与之冲突 */
    public static final String PREFIX = "rs:";

    /** 类型标记：物品 */
    public static final char ITEM = 'i';
    /** 类型标记：流体 */
    public static final char FLUID = 'f';

    /** 组件指纹与 registry id 之间的分隔符 */
    private static final char COMPONENTS = '|';

    /**
     * 组件指纹的长度上限（字符）。
     *
     * 【为什么必须有上限】指纹会进**身份串**，而身份串是每个采样点都存一份的
     * （{@code Sample#itemId}）。大多数组件很短（`{minecraft:damage=5}`），但
     * `minecraft:container` / `minecraft:bees` / `minecraft:custom_data` 这类能到几 KB ——
     * 20 样本/秒 × 1 小时 × 5 KB ≈ 360 MB，存档会被撑爆。
     *
     * 超限就换成短形式 `{…<条目数>:<哈希>}`：**仍然只由内容决定**（同内容 → 同串，
     * 采样的必要条件不变），代价是不同内容可能撞车 —— 与"指纹用 toString"同一类
     * 降级，撞车只会把两行合并（数量相加），不会读出假值。
     */
    public static final int FINGERPRINT_MAX = 160;

    private RsKeyText() {
    }

    /**
     * 把**已排序**的组件条目拼成指纹。
     *
     * @param sortedParts 形如 {@code minecraft:damage=5} 的条目，**调用方负责排序**
     *                    （{@code DataComponentPatch} 的迭代顺序是插入顺序，直接拼会抖动）
     */
    public static String componentFingerprint(java.util.List<String> sortedParts) {
        String joined = "{" + String.join(",", sortedParts) + "}";
        if (joined.length() <= FINGERPRINT_MAX) {
            return joined;
        }
        return "{…" + sortedParts.size() + ':' + Integer.toHexString(joined.hashCode()) + '}';
    }

    public static boolean isRs(@Nullable String id) {
        return id != null && id.startsWith(PREFIX) && id.length() > PREFIX.length() + 2
                && id.charAt(PREFIX.length() + 1) == ':';
    }

    /**
     * 只认前缀、不验形状。
     *
     * 与 {@link #isRs} 的分工：判定"这是不是一条 RS 身份"用 {@code isRs}（形状必须对，
     * 否则不该拿去比对采样）；**取显示名**用这个 —— 形状坏掉的 {@code rs:} 串也要按
     * RS 的规则露原文，绝不能掉进"去命名空间"那条路编出一个像名字的东西
     * （{@code rs:i:} 会变成 {@code i:}，看着像个名字，其实什么都不是）。
     */
    public static boolean hasPrefix(@Nullable String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** 无组件物品的身份 = 裸 registry id（见类注释） */
    public static String item(String itemId) {
        return itemId;
    }

    /** 带组件物品的身份 */
    public static String itemWithComponents(String itemId, String fingerprint) {
        return encode(ITEM, itemId, fingerprint);
    }

    /** 无组件流体的身份 */
    public static String fluid(String fluidId) {
        return encode(FLUID, fluidId, null);
    }

    /** 带组件流体的身份 */
    public static String fluidWithComponents(String fluidId, String fingerprint) {
        return encode(FLUID, fluidId, fingerprint);
    }

    private static String encode(char type, String registryId, @Nullable String fingerprint) {
        String head = PREFIX + type + ':' + registryId;
        return fingerprint == null || fingerprint.isEmpty() ? head : head + COMPONENTS + fingerprint;
    }

    /**
     * 解析身份串。
     *
     * @return null = 不是 {@code rs:} 身份，或形状不对（类型标记缺失 / 空 id）
     */
    @Nullable
    public static Parsed parse(@Nullable String id) {
        if (!isRs(id)) {
            return null;
        }
        int typeAt = PREFIX.length();
        char type = id.charAt(typeAt);
        String rest = id.substring(typeAt + 2);
        if (rest.isEmpty()) {
            return null;
        }
        int bar = rest.indexOf(COMPONENTS);
        if (bar < 0) {
            return new Parsed(type, rest, null);
        }
        return new Parsed(type, rest.substring(0, bar), rest.substring(bar + 1));
    }

    /**
     * 注册名档的写法：拿身份串里的 registry id 去命名空间，**不含组件指纹**。
     * 组件指纹是一段机器可读的补丁文本，写进界面只会把那一行挤爆。
     */
    public static String label(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        if (!hasPrefix(id)) {
            return AeKeyText.shortId(id);
        }
        Parsed p = parse(id);
        return p == null ? "~" + tail(id) : AeKeyText.shortId(p.registryId());
    }

    /** 解析失败时露出原文（截断），不编一个看起来像名字的东西 */
    private static String tail(String id) {
        return id.length() > 24 ? id.substring(0, 24) : id;
    }

    /**
     * 拆开的身份。
     *
     * @param type        {@link #ITEM} 或 {@link #FLUID}（将来 RS 若引入别的资源类型，
     *                    这里会出现别的字符 —— 读取端一律按"不认识的类型 = 读不到"处理）
     * @param registryId  物品 / 流体的 registry id，如 {@code minecraft:water}
     * @param components  组件指纹，可为 null（无组件）
     */
    public record Parsed(char type, String registryId, @Nullable String components) {
    }
}
