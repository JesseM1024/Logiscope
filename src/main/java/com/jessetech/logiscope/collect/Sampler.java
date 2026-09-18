package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.store.Sample;
import com.jessetech.logiscope.store.SampleStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 采集层：整个模组唯一「主动跑」的部分。
 *
 * 它刻意不认识任何 UI / 渲染类 —— 界面只是它的一个观察者。
 * 这样分层的好处：采样逻辑可以脱离界面单独验证（例如用日志输出），
 * 界面的崩溃也不会污染数据。
 *
 * 状态机：
 *
 *   IDLE ──arm()──► ARMED ──attach()──► RECORDING ◄──────────┐
 *     ▲                                    │  ▲               │
 *     │                                    │  └── FOLLOWING ──┘
 *     │                                    │        │
 *     └──────────detach()──────────────────┴────────┴──► PAUSED
 *
 * 【三条取值路径】
 *   RECORDING  界面开着 → 直接读菜单（ContainerAccess），这是最准的一条
 *   FOLLOWING  界面关了 → 单人从方块实体读（WorldProbe）；多人读不到，记 no_data
 *   PAUSED     不取值
 *
 * 【为什么 FOLLOWING 要单独成一个状态】
 *   以前关掉界面直接转 PAUSED，记录就此中断。但玩家关界面往往是为了
 *   腾出手去干别的，而不是想停止观测。所以：
 *     · 用户开了"关界面后继续检测" 且 拿得到持久身份（维度+坐标）→ FOLLOWING
 *     · 否则 → 照旧 PAUSED（宁可明确停住，也不要记一堆没有意义的样本）
 *
 * 【PAUSED 的两种来源，行为不同】
 *   manualPause = true   用户按了「暂停」→ 不自动恢复，只能手动继续
 *   manualPause = false  数据源自己失效了 → 一旦数据源回来（重开同一台设备）自动恢复
 */
public final class Sampler {

    public enum State {
        IDLE, ARMED, RECORDING, FOLLOWING, PAUSED
    }

    /** 容量 = 20 tick/秒 × 60 秒 × 30 分 = 半小时 */
    private static final int CAPACITY = 20 * 60 * 30;

    /** 世界侧读值的最大可容忍延迟（tick）。超了就算没读到。 */
    private static final int WORLD_READ_MAX_AGE = 5;

    private final SampleStore store = new SampleStore(CAPACITY);

    @Nullable
    private ProbeTarget target;
    private State state = State.IDLE;
    private long tick;
    private int watchedContainerId = -1;

    /** 用户可选：关掉容器界面之后是否继续检测 */
    private boolean followAfterClose = true;

    /** 暂停是不是用户主动按的（决定能否自动恢复） */
    private boolean manualPause;

    /** 上一次给玩家发动作栏提示的 tick，用于去抖 */
    private long lastNotifyTick = Long.MIN_VALUE / 2;

    public SampleStore store() {
        return store;
    }

    @Nullable
    public ProbeTarget target() {
        return target;
    }

    public State state() {
        return state;
    }

    public long currentTick() {
        return tick;
    }

    public boolean followAfterClose() {
        return followAfterClose;
    }

    public void setFollowAfterClose(boolean value) {
        this.followAfterClose = value;
    }

    public boolean isManuallyPaused() {
        return manualPause;
    }

    // ------------------------------------------------------------------
    // 状态迁移
    // ------------------------------------------------------------------

    /** 进入"待选格"状态：界面里提示用户点一个格子 */
    public void arm() {
        if (state == State.RECORDING || state == State.FOLLOWING || state == State.PAUSED) {
            return;
        }
        state = State.ARMED;
    }

    /** 接上探头并开始记录 */
    public void attach(ProbeTarget newTarget) {
        this.target = newTarget;
        this.watchedContainerId = ContainerAccess.currentContainerId();
        this.store.clear();
        this.manualPause = false;
        WorldProbe.reset();
        this.state = State.RECORDING;
    }

    /** 拔掉探头，回到未连接状态 */
    public void detach() {
        this.target = null;
        this.watchedContainerId = -1;
        this.manualPause = false;
        WorldProbe.reset();
        this.state = State.IDLE;
    }

    /** 留下探头，但回到"重新选格"（= 面板上的「返回选格」） */
    public void backToPicker() {
        detach();
    }

    /** 清空已记录的数据，但不动探针和状态 */
    public void clearData() {
        store.clear();
    }

    /** 主动暂停（用户按的） */
    public void pause() {
        if (state == State.RECORDING || state == State.FOLLOWING) {
            manualPause = true;
            state = State.PAUSED;
        }
    }

    /** 手动继续。数据源不可用时保持 PAUSED，不假装在记录。 */
    public void resume() {
        manualPause = false;
        if (state == State.PAUSED) {
            resumeFromPause();
        } else if (state == State.ARMED && target == null) {
            // 还没选格子，无事可做
        }
    }

    /** 面板上的一个按钮即可完成暂停/继续切换 */
    public void togglePause() {
        if (state == State.RECORDING || state == State.FOLLOWING) {
            pause();
        } else {
            resume();
        }
    }

    /**
     * 从 PAUSED 尝试恢复：优先回到"界面还在"的 RECORDING，否则回到 FOLLOWING。
     * 两者都不成立就停在 PAUSED —— 这是刻意的，不要为了"看起来在工作"而记假数据。
     */
    private void resumeFromPause() {
        if (menuSourceReady()) {
            state = State.RECORDING;
            return;
        }
        if (followAfterClose && target != null && target.hasPersistentId()) {
            enterFollowing();
        }
    }

    /**
     * 数据源是否就绪（界面开着 + 容器没换 + 槽位还在）。
     * 三个条件缺一不可，见 ARCHITECTURE.md 的 CP5。
     */
    private boolean menuSourceReady() {
        if (target == null || !ContainerAccess.hasForeignContainerOpen()) {
            return false;
        }
        if (ContainerAccess.currentContainerId() != watchedContainerId && !tryRebind()) {
            return false;
        }
        return ContainerAccess.read(target.slotIndex()) != null;
    }

    /**
     * 玩家重新打开了容器界面时，尝试按「维度 + 坐标」把它认成同一台设备。
     * 认出来就把 watchedContainerId 更新到新的容器 ID，实现跨界面重连。
     */
    private boolean tryRebind() {
        if (target == null || !target.hasPersistentId()) {
            return false;
        }
        int id = ContainerAccess.currentContainerId();
        ContainerAnchor.Anchor anchor = ContainerAnchor.activeFor(id);
        if (anchor == null) {
            return false;
        }
        if (!anchor.dimension().equals(target.dimension()) || !anchor.pos().equals(target.pos())) {
            return false;
        }
        watchedContainerId = id;
        return true;
    }

    private void enterFollowing() {
        WorldProbe.reset();
        state = State.FOLLOWING;
        notifyPlayer("logiscope.msg.following");
    }

    private void pauseAuto() {
        manualPause = false;
        state = State.PAUSED;
        notifyPlayer("logiscope.msg.paused_auto");
    }

    /**
     * 状态自己变了，必须让玩家知道 —— 否则"记录到底还在不在跑"只能靠猜，
     * 这对一个观测工具是致命的。用动作栏（第二参数 true）不刷屏。
     *
     * 加 2 秒去抖：状态在边界附近来回跳时（例如玩家反复开关界面），
     * 不该把动作栏刷成跑马灯。
     */
    private void notifyPlayer(String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || tick - lastNotifyTick < 40) {
            return;
        }
        lastNotifyTick = tick;
        mc.player.displayClientMessage(Component.translatable(key), true);
    }

    // ------------------------------------------------------------------
    // 采样
    // ------------------------------------------------------------------

    /** 由主类注册到游戏总线，签名固定为 (event) */
    public static void onClientTick(ClientTickEvent.Post event) {
        ContainerAnchor.tick();
        LogiScopeMod.sampler().tick();
    }

    public void tick() {
        tick++;
        switch (state) {
            case RECORDING -> tickRecording();
            case FOLLOWING -> tickFollowing();
            case PAUSED -> tickPaused();
            default -> {
                // IDLE / ARMED 什么都不做，只走一次整数比较
            }
        }
    }

    /** 界面开着：读菜单 */
    private void tickRecording() {
        if (target == null) {
            state = State.IDLE;
            return;
        }

        if (!ContainerAccess.hasForeignContainerOpen()) {
            // 玩家关掉界面了
            if (followAfterClose && target.hasPersistentId()) {
                enterFollowing();
            } else {
                pauseAuto();
            }
            return;
        }

        if (ContainerAccess.currentContainerId() != watchedContainerId && !tryRebind()) {
            // 换了另一台设备，且认不出是我们跟踪的那台
            pauseAuto();
            return;
        }

        ContainerAccess.SlotView view = ContainerAccess.read(target.slotIndex());
        if (view == null) {
            // 槽位下标越界：换了不同类型的菜单
            pauseAuto();
            return;
        }

        store.add(new Sample(tick, view.itemId(), view.count()));
    }

    /** 界面关着：按坐标读，读不到就记 no_data */
    private void tickFollowing() {
        if (target == null) {
            state = State.IDLE;
            return;
        }

        if (ContainerAccess.hasForeignContainerOpen()) {
            // 玩家又把界面打开了 —— 优先回到最准的那条路径
            if (ContainerAccess.currentContainerId() == watchedContainerId || tryRebind()) {
                state = State.RECORDING;
                return;
            }
            // 开的是别的容器：继续按坐标跟踪，不打断
        }

        WorldProbe.request(target, tick);
        ContainerAccess.SlotView view = WorldProbe.latest(tick, WORLD_READ_MAX_AGE);
        store.add(view == null
                ? Sample.noData(tick)
                : new Sample(tick, view.itemId(), view.count()));
    }

    /** 暂停中：只有"自动暂停"才允许数据源自己回来时恢复 */
    private void tickPaused() {
        if (manualPause || target == null) {
            return;
        }
        if (menuSourceReady()) {
            state = State.RECORDING;
            return;
        }
        if (followAfterClose && target.hasPersistentId() && !ContainerAccess.hasForeignContainerOpen()) {
            enterFollowing();
        }
    }
}
