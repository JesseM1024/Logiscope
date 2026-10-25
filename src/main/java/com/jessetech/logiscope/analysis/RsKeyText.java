package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

/**
 * 精致存储（Refined Storage 1.6）资源的**字符串身份**（纯文本层，无游戏依赖 ——
 * 离线断言覆盖，TestChart t43）。
 *
 * 【为什么需要另一套前缀】
 * RS 的每种存储物在 1.x 这条线上就是一个 {@code ItemStack} / {@code FluidStack}。
 * 它和 AE 是两套彼此无关的类型系统，身份串必须能一眼分出"这是哪个模组的东西"，
 * 否则同一个 {@code minecraft:water} 在两套网络里会互相冒充
 * （AE 的水是 {@code ae:{#t:"ae2:f",...}}，RS 的水是另一支）。
 *
 * 【三种身份，与"物品共用裸 id"这条约定】
 * | 字符串 | 含义 | 显示名（注册名档） |
 * |---|---|---|
 * | {@code minecraft:iron_ingot} | **没有 NBT 的物品** —— 与 AE 物品、容器槽位共用同一个身份 | {@code iron_ingot} |
 * | {@code rs:i:minecraft:potion|{...}} | 带 NBT 的物品（附魔 / 命名 / 有 contents 的物品…） | {@code potion} |
 * | {@code rs:f:minecraft:water} | 流体 | {@code water} |
 * | {@code rs:f:minecraft:water|{...}} | 带 NBT 的流体（罕见） | {@code water} |
 *
 * **为什么无 NBT 物品不挂前缀**：RS 网络里存的那件铁锭和"原版的一件铁锭"是同一个东西。
 * 共用裸 registry id 有三个直接好处 —— ① 名称解析（{@code NameText}）不必认识 RS；
 * ② 同一件东西在 AE 网络与 RS 网络里天然是同一条曲线名（一个探针对准一台设备，
 * 两套网络不会在同一探针里撞车）；③ 旧存档 / 旧目标不受影响。
 *
 * **为什么 NBT 要进身份**：RS 自己就按 NBT 区分条目（{@code IStackList} 用
 * {@code IComparer} 比 NBT / 损伤值）。把附魔书和同名白书塌成一个身份，就是项目
 * 第一条纪律里的"同名不同物"——读出来的数字会是两者相加，而界面上只有一行。
 *
 * 【已知限制，已接受】NBT 载荷用 {@code CompoundTag#getAsString()}（真实 SNBT，
 * 读取端能用 {@code TagParser} 原样解回来）。它的**键顺序来自 HashMap 的桶顺序**，
 * 不是规范化的排序输出：同一个 {@code ItemStack} 每 tick 算出来的串恒定（采样需要的
 * 正是这一条），但两个"内容相等、构造方式不同"的 tag 极少数情况下可能打出不同串 ——
 * 后果是同一条曲线被拆成两条（**不会**读出假值）。两个方向的降级都可接受。
 *
 * 【与 1.21.1 线（RS 2.x）的差别】那条线的键是 {@code ItemResource}/{@code FluidResource}
 * + {@code DataComponentPatch}，载荷只能自己拼指纹；这条线是 {@code ItemStack} +
 * {@code CompoundTag}，**载荷本身就是可解析的 SNBT**，所以没有指纹压缩那一套。
 * 形状（前缀 / 类型标记 / {@code |} 分隔）刻意保持一致 —— 两个版本的探针数据长得一样。
 */
public final class RsKeyText {

    /** RS 身份串的前缀。物品 id 形如 {@code ns:path}（恰好一个冒号），不会与之冲突 */
    public static final String PREFIX = "rs:";

    /** 类型标记：物品 */
    public static final char ITEM = 'i';
    /** 类型标记：流体 */
    public static final char FLUID = 'f';

    /** NBT 载荷与 registry id 之间的分隔符 */
    private static final char PAYLOAD = '|';

    private RsKeyText() {
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

    /** 无 NBT 物品的身份 = 裸 registry id（见类注释） */
    public static String item(String itemId) {
        return itemId;
    }

    /** 带 NBT 物品的身份 */
    public static String itemWithNbt(String itemId, String snbt) {
        return encode(ITEM, itemId, snbt);
    }

    /** 无 NBT 流体的身份 */
    public static String fluid(String fluidId) {
        return encode(FLUID, fluidId, null);
    }

    /** 带 NBT 流体的身份 */
    public static String fluidWithNbt(String fluidId, String snbt) {
        return encode(FLUID, fluidId, snbt);
    }

    private static String encode(char type, String registryId, @Nullable String snbt) {
        String head = PREFIX + type + ':' + registryId;
        return snbt == null || snbt.isEmpty() ? head : head + PAYLOAD + snbt;
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
        int bar = rest.indexOf(PAYLOAD);
        if (bar < 0) {
            return new Parsed(type, rest, null);
        }
        return new Parsed(type, rest.substring(0, bar), rest.substring(bar + 1));
    }

    /**
     * 注册名档的写法：拿身份串里的 registry id 去命名空间，**不含 NBT 载荷**。
     * SNBT 是一段机器可读的标签文本，写进界面只会把那一行挤爆。
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
     * @param type       {@link #ITEM} 或 {@link #FLUID}
     * @param registryId 物品 / 流体的 registry id，如 {@code minecraft:water}
     * @param payload    NBT 载荷的 SNBT，可为 null（无 NBT）
     */
    public static final class Parsed {
        private final char type;
        private final String registryId;
        private final @Nullable String payload;

        public Parsed(char type, String registryId, @Nullable String payload) {
            this.type = type;
            this.registryId = registryId;
            this.payload = payload;
        }

        public char type() {
            return type;
        }

        public String registryId() {
            return registryId;
        }

        public @Nullable String payload() {
            return payload;
        }
    }
}
