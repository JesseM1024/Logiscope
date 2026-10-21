package com.jessetech.logiscope.analysis;

import java.util.Locale;

/**
 * 选格页筛选框的匹配规则（纯逻辑，无游戏依赖 —— 离线断言覆盖，TestChart t41）。
 *
 * 【需求原文】"在物品选格页，新增一个搜索框，用于多容器格时的物品筛选，
 * 筛选可以支持物品名称或容器序号，按显示的来就行，如 {@code #1 iron_ingot}"。
 *
 * 【规则】一句话：**按列表上显示的那几段文字筛，多个条件写在一起是与**。
 *
 * | 查询 | 含义 |
 * |---|---|
 * | （空） | 全部显示 |
 * | `iron` | 名称子串（大小写不敏感），命中任一被筛字段 |
 * | `#1` | **序号精确等于 1** —— 不是子串，所以不会命中 `#10` / `#21` |
 * | `#1 iron_ingot` | 两个词都要命中 = 序号 1 且名称含 iron_ingot |
 * | `#` | 没有数字 → 退回普通子串（当用户就想搜带 # 的文本时也说得过去） |
 *
 * 【为什么序号要精确匹配】`#1` 若按子串匹配，`#10`~`#19` 会一起跳出来；
 * 多容器动辄几十格，"筛一个格子"就变成了"筛一堆格子"，筛选框就白加了。
 *
 * 【为什么不是整串子串匹配】显示文本是 `#1  iron_ingot x32`（两空格 + 数量段），
 * 用户按需求原文敲 `#1 iron_ingot`（单空格）**匹配不上**。按词拆开分别判才符合直觉。
 */
public final class PickerFilter {

    private PickerFilter() {
    }

    /**
     * @param query  用户输入的原文（可为空 / 多词）
     * @param slot   这一行的容器序号（显示成 {@code #N} 的那个 N）
     * @param fields 这一行上显示出来的其他文字（物品 id / 短名 / AE 显示名…），逐个做子串匹配
     * @return 这一行该不该显示
     */
    public static boolean matches(String query, int slot, String... fields) {
        if (query == null) {
            return true;
        }
        String q = query.trim();
        if (q.isEmpty()) {
            return true;
        }
        String slotText = "#" + slot;
        for (String token : q.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.charAt(0) == '#' && token.length() > 1 && isDigits(token.substring(1))) {
                // 序号条件：按数值精确相等（见类注释，子串会让 #1 命中 #10）。
                // 按数值而不是按文本比，是为了容忍前导零：#007 也能筛到 7 号格。
                if (slot != Integer.parseInt(token.substring(1))) {
                    return false;
                }
                continue;
            }
            if (!containsAny(token, slotText, fields)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String token, String slotText, String... fields) {
        if (slotText.contains(token)) {
            return true;
        }
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 只认纯数字（`#007` 也当 7 处理 —— 判据是数值；长度上限挡住溢出的垃圾输入） */
    private static boolean isDigits(String s) {
        if (s.isEmpty() || s.length() > 9) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
