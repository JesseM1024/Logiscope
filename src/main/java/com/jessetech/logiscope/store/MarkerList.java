package com.jessetech.logiscope.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 波形标记（G4）：用户在波形上打的"这一点值得记住"记号。
 *
 * ★ 独立于环形缓冲 ★
 * {@link SampleStore} 是滚动覆盖的（写满就丢最旧的），标记不能跟着陪葬 ——
 * 用户标记"这里开始出问题了"，十分钟后缓冲滚过这段，标记还在才对得起那个右键。
 * 所以标记放在自己的小结构里，生命周期跟探针实例（或回放会话）走。
 *
 * 不引用任何游戏类，纯逻辑层 —— 离线断言直接覆盖（TestChart）。
 *
 * 【语义】
 *   · 一个 tick 最多一个标记（同一格右键两次不会叠出两个）
 *   · 始终按 tick 升序（画图与导出都不用再排）
 *   · "加还是删"由界面层判定（右键点在已有标记附近 = 删），本类只存真相
 */
public final class MarkerList {

    private final ArrayList<Long> ticks = new ArrayList<>();

    /** 加入一个标记（按 tick 升序插入；这个 tick 已有标记时什么都不做）。@return 是否真的加了 */
    public boolean add(long tick) {
        int idx = lowerBound(tick);
        if (idx < ticks.size() && ticks.get(idx) == tick) {
            return false;
        }
        ticks.add(idx, tick);
        return true;
    }

    /** 删除一个标记。@return 是否真的删了（没有这个标记时返回 false） */
    public boolean remove(long tick) {
        int idx = lowerBound(tick);
        if (idx < ticks.size() && ticks.get(idx) == tick) {
            ticks.remove(idx);
            return true;
        }
        return false;
    }

    public boolean contains(long tick) {
        int idx = lowerBound(tick);
        return idx < ticks.size() && ticks.get(idx) == tick;
    }

    public void clear() {
        ticks.clear();
    }

    public int size() {
        return ticks.size();
    }

    /** 快照（升序、不可变）—— 渲染与导出都拿这份，遍历期间不会被并发改坏 */
    public List<Long> toList() {
        return List.copyOf(ticks);
    }

    /** 第一个 >= tick 的下标（列表按 tick 升序，二分） */
    private int lowerBound(long tick) {
        int lo = 0;
        int hi = ticks.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ticks.get(mid) < tick) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
