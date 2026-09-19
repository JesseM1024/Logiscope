package com.jessetech.logiscope.analysis;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"选格页点到的格子"翻译成"**数据里的 slot 号**"（第十六轮④）。
 *
 * 【为什么不能靠 {@code Slot#getContainerSlot()}】
 * 第十六轮③曾经把它当**最高优先级**判据（"容器下标恰好等于某个 NBT slot → 就选那一格"）。
 * 实机把它否了 —— 两条字节码事实：
 *   · NeoForge 的 {@code SlotItemHandler} 是 {@code super(emptyInventory, index, x, y)}，
 *     传进去的**就是 handler 下标**（正好等于 NBT 里的 {@code Slot}）→ 这条路对这类机器是对的；
 *   · **Mekanism 的 {@code InventoryContainerSlot} 是 {@code super(emptyInventory, 0, x, y)}**
 *     —— 容器下标**硬编码 0**，于是它那台机器上每一格都报 0。
 * 后果（实测）：点 2 个原料格 + 2 个输出格，得到"**1 个原料格 + 全部输出格**" ——
 * 撞上 slot 0 的格互相覆盖（两格塌成一格），撞不上的格退回"全纳入"（两格炸成全部）。
 *
 * 【本类用的判据：**顺序 + 内容**，两个条件同时成立才认】
 * 两个序列天生同序 —— 都在"机器物品栏的下标序"上：
 *   · A = 菜单里**非空**的机器格，按菜单顺序（模组就是按物品栏顺序 addSlot 的）；
 *   · B = 数据里的元素，按 slot 升序（模组就是按物品栏顺序写下标的）。
 * 于是 A / B 的**最长公共子序列**（按物品 id 比）把"点到的格"钉到 slot 号上，并且：
 *   · 只有**唯一解**才采用（多解 = 真的分不出来，宁可拒绝也不猜）；
 *   · 空格子不进 A、也不进 B —— 两侧同时消失，所以**中间夹着多少空槽都不影响对齐**
 *     （这正是 Mekanism 那两个虚拟升级槽、以及能量槽空着时的情形）；
 *   · 物品刚被抽走 / 刚被填上导致两个快照对不齐时，会退化成"多解"或"无解" → 拒绝。
 *
 * 纯逻辑（不引用任何游戏类），离线可断言（TestChart t33）。
 */
public final class SlotAlign {

    /**
     * 菜单里的一个**机器格**（可以是空的）。
     *
     * @param ordinal       它在"机器格序列"里的位置 —— 菜单型容器 = 该格在菜单机器格里的顺序号；
     *                      无 GUI 型容器（服务端读出来的 handler）= 它自己的下标
     * @param menuSlotIndex 菜单槽下标（无 GUI 型容器为 -1），用来把结果对回界面上的勾选状态
     * @param itemId        物品 id；空格子传 null 或空串
     */
    public record Cell(int ordinal, int menuSlotIndex, String itemId) {
    }

    /**
     * 对齐结果。
     *
     * @param byOrdinal  能**唯一确定**的格：ordinal → 数据里的 slot 号
     * @param bySlot     反向表：slot 号 → ordinal（"回选格页时把已跟踪的格子勾上"用）
     * @param shift      统一位移（所有已确认的配对都满足 {@code ordinal - slot} 相同）。
     *                   **至少 2 个配对**且完全一致时才给出 —— 有了它，**空格子**也能定位
     *                   （空格子在数据里没有元素，只能靠位移推）。不一致或样本不足 = null。
     * @param pairs      确认的配对数
     * @param unresolved 无法唯一确定的格数（多解 / 不在任何最优对齐里）
     */
    public record Result(Map<Integer, Integer> byOrdinal, Map<Integer, Integer> bySlot,
                         Integer shift, int pairs, int unresolved) {
        public static final Result EMPTY = new Result(Map.of(), Map.of(), null, 0, 0);
    }

    /**
     * 空格子也能用"统一位移"外推所需的**最少配对数**。
     * 1 个配对不足以证明位移是常数（可能只是巧合），所以要求 2 个以上。
     */
    private static final int MIN_PAIRS_FOR_SHIFT = 2;

    /**
     * 选格页点到的**一个格子**（判据链的输入）。
     *
     * @param ordinal            见 {@link Cell#ordinal()}
     * @param containerSlotIndex {@code Slot#getContainerSlot()}；不可信时上层会关掉那条判据
     * @param itemId             格子里的物品；空格子传 null / 空串
     * @param empty              是不是空格子（**必须显式给**：空格子的 itemId 常被填成
     *                           {@code minecraft:air}，用"字符串空不空"判不出来）
     */
    public record Pick(int ordinal, int containerSlotIndex, String itemId, boolean empty) {
    }

    /**
     * **一个格子该监测哪个 slot**（判据链，可信度从高到低；全部要求"内容对得上"）：
     *
     * | # | 判据 | 条件 |
     * |---|---|---|
     * | 1 | 顺序对齐 | {@link Result#byOrdinal()} 里有这一格（唯一解，且靠物品 id 验过） |
     * | 2 | 容器下标 | `containerIndexReliable` **且**那个 slot 的元素就是这一格的物品 |
     * | 3 | 按物品 id 唯一命中 | 候选恰好 1 个 |
     * | 4 | 空格子 | 只能靠**统一位移**外推（{@link Result#shift()}）；推不出来就拒绝 |
     *
     * @return 数据里的 slot 号；**null = 拒绝**（宁可少测一格，也不给一个错的号）
     */
    public static Integer resolve(Pick pick, List<NbtSlotLayout.Entry> entries,
                                  Result align, boolean containerIndexReliable) {
        if (pick.empty()) {
            Integer shift = align.shift();
            if (shift != null && pick.ordinal() >= 0) {
                int slot = pick.ordinal() - shift;
                return slot >= 0 ? slot : null;
            }
            return containerIndexReliable && pick.containerSlotIndex() >= 0
                    ? pick.containerSlotIndex() : null;
        }
        String want = pick.itemId();
        if (want == null || want.isBlank()) {
            return null;
        }
        Integer byOrdinal = align.byOrdinal().get(pick.ordinal());
        if (byOrdinal != null) {
            return byOrdinal;
        }
        if (containerIndexReliable && pick.containerSlotIndex() >= 0) {
            NbtSlotLayout.Entry e = entryAt(entries, pick.containerSlotIndex());
            if (e != null && want.equals(e.itemId())) {
                return pick.containerSlotIndex();
            }
        }
        int hits = 0;
        int only = -1;
        for (NbtSlotLayout.Entry e : entries) {
            if (want.equals(e.itemId())) {
                hits++;
                only = e.slot();
            }
        }
        return hits == 1 ? only : null;
    }

    @Nullable
    private static NbtSlotLayout.Entry entryAt(List<NbtSlotLayout.Entry> entries, int slot) {
        for (NbtSlotLayout.Entry e : entries) {
            if (e.slot() == slot) {
                return e;
            }
        }
        return null;
    }

    /**
     * 这个清单的"容器下标"能不能当身份用：**同一份清单里出现重复下标 ⇒ 不能**。
     * 一个 handler 下标只可能对应一格，报重复就说明它另有含义 ——
     * Mekanism 的 `InventoryContainerSlot` 给 super 传的是硬编码 0，全机器槽都报 0，
     * 拿它去撞数据里的 slot 0 就会把两格原料认成同一格（实测踩过）。
     */
    public static boolean containerIndexReliable(List<Integer> containerIndices) {
        List<Integer> seen = new ArrayList<>();
        for (Integer ci : containerIndices) {
            if (ci == null || ci < 0) {
                continue;
            }
            if (seen.contains(ci)) {
                return false;
            }
            seen.add(ci);
        }
        return true;
    }


    private SlotAlign() {
    }

    /**
     * 对齐"菜单机器格序列"与"数据元素序列"。
     *
     * @param cells   A：**只放非空格**，按菜单顺序（空格子请自行过滤掉）
     * @param entries B：数据里的元素，按 slot 升序
     */
    public static Result align(List<Cell> cells, List<NbtSlotLayout.Entry> entries) {
        int n = cells == null ? 0 : cells.size();
        int m = entries == null ? 0 : entries.size();
        if (n == 0 || m == 0) {
            return new Result(Map.of(), Map.of(), null, 0, n);
        }

        boolean[][] eq = new boolean[n][m];
        for (int i = 0; i < n; i++) {
            String a = cells.get(i).itemId();
            for (int j = 0; j < m; j++) {
                String b = entries.get(j).itemId();
                eq[i][j] = a != null && !a.isBlank() && a.equals(b);
            }
        }

        // pre[i][j] = LCS(cells[0..i-1], entries[0..j-1])
        int[][] pre = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                pre[i][j] = eq[i - 1][j - 1]
                        ? pre[i - 1][j - 1] + 1
                        : Math.max(pre[i - 1][j], pre[i][j - 1]);
            }
        }
        // suf[i][j] = LCS(cells[i..n-1], entries[j..m-1])
        int[][] suf = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                suf[i][j] = eq[i][j]
                        ? suf[i + 1][j + 1] + 1
                        : Math.max(suf[i + 1][j], suf[i][j + 1]);
            }
        }
        int best = pre[n][m];

        Map<Integer, Integer> byOrdinal = new LinkedHashMap<>();
        Map<Integer, Integer> bySlot = new LinkedHashMap<>();
        List<Integer> shifts = new ArrayList<>();
        int pairs = 0;
        int unresolved = 0;

        for (int i = 0; i < n; i++) {
            int hit = -1;
            int hits = 0;
            for (int j = 0; j < m; j++) {
                if (!eq[i][j]) {
                    continue;
                }
                // 这一对能出现在某个最优对齐里 ⟺ 前缀 + 它 + 后缀 恰好等于最优长度
                if (pre[i][j] + 1 + suf[i + 1][j + 1] == best) {
                    if (hits == 0) {
                        hit = j;
                    }
                    hits++;
                }
            }
            if (hits == 1) {
                int ordinal = cells.get(i).ordinal();
                int slot = entries.get(hit).slot();
                byOrdinal.put(ordinal, slot);
                bySlot.put(slot, ordinal);
                shifts.add(ordinal - slot);
                pairs++;
            } else {
                // hits == 0：这一格不在任何最优对齐里（两边的快照对不齐）
                // hits > 1 ：好几格同名物品都可能，真的分不出来 —— 都不猜
                unresolved++;
            }
        }

        Integer shift = null;
        if (pairs >= MIN_PAIRS_FOR_SHIFT) {
            int first = shifts.get(0);
            boolean same = true;
            for (int s : shifts) {
                if (s != first) {
                    same = false;
                    break;
                }
            }
            if (same) {
                shift = first;
            }
        }
        return new Result(byOrdinal, bySlot, shift, pairs, unresolved);
    }
}
