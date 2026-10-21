package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

/**
 * AE 键的**字符串身份**（纯文本层，无游戏依赖 —— 离线断言覆盖，TestChart t42）。
 *
 * 【为什么 AE 的非物品键需要一套身份】
 * 物品可以用 registry id 当身份（{@code minecraft:iron_ingot}），但 AE 网络里还存着
 * **流体 / 化学品 / 电力**这些"不是物品的东西"。它们在原版物品栏里没有 id，
 * 在客户端只以「通用物品堆栈」（{@code ae2:wrapped_generic_stack} + 一个数据组件）的形式出现 ——
 * 若直接拿 {@code Item} 的 registry id 当身份，**所有流体都会塌成同一个 id**，
 * 读值也就永远只能读到"这个包装物品有 0 个"。
 *
 * 【身份 = {@code "ae:" + AEKey 的 SNBT}】
 * AE 自己有一套完整的键序列化（{@code AEKey#toTagGeneric}），形如
 * <pre>{#t:"ae2:f", id:"minecraft:water"}</pre>
 * 其中 {@code #t} 是**键类型 id**（{@code ae2:i} 物品 / {@code ae2:f} 流体 /
 * 附属模组自己的类型，如化学品与电力），{@code id} 是那种东西自己的 registry id。
 * 直接把这段 SNBT 当字符串身份的好处：**任何附属模组的键都能原样往返**
 * （本模组不需要认识它们），而且天然带上了"同 id 不同数据组件"的区分。
 *
 * 【老目标兼容】物品键仍然用**裸 registry id**（{@code minecraft:iron_ingot}）：
 * 0.1.2 之前建的 AE 目标就是这个格式，它们必须继续能读、继续能勾上。
 * 于是这里的规则是：
 *
 * | 字符串 | 身份类型 | 显示名 |
 * |---|---|---|
 * | {@code minecraft:iron_ingot} | 物品（老格式） | {@code iron_ingot} |
 * | {@code ae:{#t:"ae2:f",id:"minecraft:water"}} | AE 键（新格式，全类型） | {@code water} |
 * | 其它 | 原样 | 去掉命名空间 |
 */
public final class AeKeyText {

    /** AE 键字符串的前缀。物品 id 里不可能出现 `ae:` 开头 + `{`，不冲突 */
    public static final String PREFIX = "ae:";

    private AeKeyText() {
    }

    public static boolean isAe(@Nullable String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /**
     * 给人看的名字：AE 键取里头的 {@code id} 字段，普通 id 去掉命名空间。
     * 解析不出来时**不假装**，退回一段能认出来的原文字样（带前导 {@code ~}）。
     */
    public static String label(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        if (!isAe(id)) {
            return shortId(id);
        }
        String raw = idOf(id);
        if (raw != null) {
            return shortId(raw);
        }
        // 解析不出来（键格式变了 / 文本被截断）：宁可露出原文，也不要编一个名字
        String snbt = id.substring(PREFIX.length());
        return "~" + (snbt.length() > 24 ? snbt.substring(0, 24) : snbt);
    }

    /**
     * 键串里 {@code id} 字段的**原文**（保留命名空间，如 {@code minecraft:water}）。
     *
     * 与 {@link #label} 的区别：label 是给人看的（去掉命名空间），这个是拿来**反查注册表**的
     * （读值链要按它去 {@code ForgeRegistries.FLUIDS} 找流体，1.16.5 线用）。
     *
     * @return null = 不是 AE 键串 / 没有 id 字段 / 解析不出来
     */
    @Nullable
    public static String idOf(@Nullable String id) {
        if (!isAe(id)) {
            return null;
        }
        Snbt.Node root = Snbt.parse(id.substring(PREFIX.length()));
        if (root == null) {
            return null;
        }
        Snbt.Node idNode = root.children().get("id");
        String text = idNode == null ? null : idNode.text();
        if (text == null || text.trim().isEmpty()) {   // Java 8：String#isBlank 是 11 才有的
            return null;
        }
        return text;
    }

    /**
     * 键类型 id（{@code ae2:f} 之类），解析不出来返回 null。
     * 只用于诊断与离线断言；界面不显示它（流体名 / 化学品名本身就够区分）。
     */
    @Nullable
    public static String typeOf(@Nullable String id) {
        if (!isAe(id)) {
            return null;
        }
        Snbt.Node root = Snbt.parse(id.substring(PREFIX.length()));
        if (root == null) {
            return null;
        }
        Snbt.Node type = root.children().get("#t");
        return type == null ? null : type.text();
    }

    /** 去掉命名空间；没有命名空间就原样返回 */
    public static String shortId(String id) {
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }
}
