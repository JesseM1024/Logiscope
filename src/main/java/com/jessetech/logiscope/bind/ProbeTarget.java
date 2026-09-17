package com.jessetech.logiscope.bind;

/**
 * 探头的连接目标：接在「哪个容器的哪个格子上」。
 *
 * 【设计权衡 —— 重要】
 * 这里用的是「运行时身份」：菜单容器 ID + 槽位下标。
 *   优点：一定能拿到，只要界面开着就可用。
 *   缺点：玩家关掉界面再打开，containerId 会变，探头就失效了。
 *
 * 另一个方案是「持久身份」：维度 + 方块坐标 + 槽位下标。
 *   优点：跨界面开关稳定，能做到"重新打开时自动重连"。
 *   缺点：vanilla 的 AbstractContainerMenu 不保存方块坐标，
 *         需要额外手段反推（右键事件 / 屏幕类型判断），可靠性因模组而异。
 *
 * 决策：第一阶段用运行时身份，能覆盖核心场景；
 *       持久身份列为后续目标（见 docs/GOALS.md 的 G3）。
 *
 * @param menuContainerId 打开时的菜单容器 ID，用于检测界面是否被换掉
 * @param slotIndex       menu.slots 里的下标（不是 containerSlotIndex）
 * @param itemId          接上探头瞬间该格子的物品 ID，仅用于界面显示
 */
public record ProbeTarget(int menuContainerId, int slotIndex, String itemId) {

    public String describe() {
        return "slot #" + slotIndex + " (" + itemId + ")";
    }
}
