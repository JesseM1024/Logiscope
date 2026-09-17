package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.store.Sample;
import com.jessetech.logiscope.store.SampleStore;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 采集层：整个模组唯一「主动跑」的部分。
 *
 * 它刻意不认识任何 UI / 渲染类 —— 界面只是它的一个观察者。
 * 这样分层的好处：采样逻辑可以脱离界面单独验证（例如用日志输出），
 * 界面的崩溃也不会污染数据。
 *
 * 状态机：
 *
 *   IDLE ──arm()──► ARMED ──attach()──► RECORDING ◄──togglePause()──► PAUSED
 *     ▲                                    │                              │
 *     └──────────────detach()──────────────┴──────────────────────────────┘
 *
 * 自动转 PAUSED 的三种情况（都是"数据源失效"，不是错误）：
 *   1. 玩家关掉了容器界面
 *   2. 玩家换了另一个容器（containerId 变了）
 *   3. 槽位下标越界（换了不同类型的菜单，槽位数变少）
 * 这样做比直接报错好：玩家关界面看一眼别的东西再回来，记录不会断掉或乱掉，
 * 而是明确地停在 PAUSED，可以手动继续。
 */
public final class Sampler {

    public enum State {
        IDLE, ARMED, RECORDING, PAUSED
    }

    /** 容量 = 20 tick/秒 × 60 秒 × 30 分 = 半小时 */
    private static final int CAPACITY = 20 * 60 * 30;

    private final SampleStore store = new SampleStore(CAPACITY);

    private ProbeTarget target;
    private State state = State.IDLE;
    private long tick;
    private int watchedContainerId = -1;

    public SampleStore store() {
        return store;
    }

    public ProbeTarget target() {
        return target;
    }

    public State state() {
        return state;
    }

    public long currentTick() {
        return tick;
    }

    // ------------------------------------------------------------------
    // 状态迁移
    // ------------------------------------------------------------------

    /** 进入"待选格"状态：界面里提示用户点一个格子 */
    public void arm() {
        if (state == State.RECORDING || state == State.PAUSED) {
            return;
        }
        state = State.ARMED;
    }

    /** 接上探头并开始记录 */
    public void attach(ProbeTarget newTarget) {
        this.target = newTarget;
        this.watchedContainerId = newTarget.menuContainerId();
        this.store.clear();
        this.state = State.RECORDING;
    }

    public void detach() {
        this.target = null;
        this.watchedContainerId = -1;
        this.state = State.IDLE;
    }

    public void togglePause() {
        if (state == State.RECORDING) {
            state = State.PAUSED;
        } else if (state == State.PAUSED) {
            // 恢复前重新确认数据源还在，否则会记一堆空样本
            if (ContainerAccess.hasForeignContainerOpen()
                    && ContainerAccess.currentContainerId() == watchedContainerId) {
                state = State.RECORDING;
            }
        }
    }

    // ------------------------------------------------------------------
    // 采样
    // ------------------------------------------------------------------

    /** 由主类注册到游戏总线，签名固定为 (event) */
    public static void onClientTick(ClientTickEvent.Post event) {
        LogiScopeMod.sampler().sampleIfRecording();
    }

    private void sampleIfRecording() {
        tick++;

        if (state != State.RECORDING) {
            return;
        }

        // 数据源校验 ------------------------------------------------
        if (!ContainerAccess.hasForeignContainerOpen()) {
            state = State.PAUSED;
            return;
        }
        if (ContainerAccess.currentContainerId() != watchedContainerId) {
            state = State.PAUSED;
            return;
        }

        ContainerAccess.SlotView view = ContainerAccess.read(target.slotIndex());
        if (view == null) {
            state = State.PAUSED;
            return;
        }

        store.add(new Sample(tick, view.itemId(), view.count()));
    }
}
