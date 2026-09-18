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
 *
 * 【时间轴连续性：RECORDING → FOLLOWING 的接缝】
 *   关界面的那一 tick 记的是 lastKnown（上一 tick 的观测值），并把它垫进 WorldProbe 缓存，
 *   于是整个切换过程**一格都不缺、也不会冒出假 no_data**。
 *   真读不到的时候才会记 no_data —— 该断的地方照样断。
 *
 * 【时间轴的刻度：世界 tick，不是客户端 tick（第七轮 N12）】
 *   样本的时间戳用 {@code Level#getGameTime()}（世界 tick 计数），不用客户端节拍。
 *   为什么：`/tick rate` 改的是**服务端**流速，客户端 tick 仍是每秒 20 个 ——
 *   按客户端节拍采样会得到"每 0.05s 一条、其中一大半是世界没动时的重复值"，
 *   数据全错（第七轮实测）。按世界 tick 采样后：
 *     · 世界没走 tick（流速调慢 / ESC 暂停）→ 跳过，不记重复样本
 *     · 横轴单位恒为"世界 tick"，统计块速率恒为 n per tick
 *   WorldProbe 的年龄判定、seed/request 也全部用同一刻度（世界没走 → 缓存不算陈旧）。
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

    /**
     * 最近一次真正读到的值。
     *
     * 只为一件事存在：**补住"关掉容器界面的那一 tick"**。
     * 那一 tick 菜单已经不在了（读不到），而世界侧的异步读取又还没回来，
     * 于是时间轴上会凭空出现一个空洞 + 一条假 no_data。
     * 手里这个值最多 1 tick（50 ms）旧，拿它续上比记"无数据"诚实得多。
     * 详见 WorldProbe#seed 的注释。
     */
    @Nullable
    private ContainerAccess.SlotView lastKnown;

    /** 上一次给玩家发动作栏提示的 tick，用于去抖 */
    private long lastNotifyTick = Long.MIN_VALUE / 2;

    /**
     * 当前世界 tick（Level#getGameTime()，见类注释）。
     * 主菜单等没有世界的场合退回客户端节拍 —— 此时采样照旧，但语义退化为"客户端 tick"。
     */
    private long curGameTime;

    /** 上一次采样的世界 tick。世界没走 tick（流速调慢 / 暂停）就不记重复样本 */
    private long lastSampledGameTime = Long.MIN_VALUE;

    /** "下标不可信"是否已经警告过。每个探头只警告一次，别跑马灯。 */
    private boolean warnedUnverifiedIndex;

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
        this.lastKnown = null;
        this.warnedUnverifiedIndex = false;
        this.lastSampledGameTime = Long.MIN_VALUE;
        WorldProbe.reset();
        this.state = State.RECORDING;
    }

    /** 拔掉探头，回到未连接状态 */
    public void detach() {
        this.target = null;
        this.watchedContainerId = -1;
        this.manualPause = false;
        this.lastKnown = null;
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
        WorldProbe.resetReadCache();
        // 把最后一次已知值垫进缓存，抹掉异步管道的启动延迟（见 WorldProbe#seed）
        WorldProbe.seed(lastKnown, curGameTime);
        // ★ 立刻发出第一次世界侧读取 ★
        // 不发的话，下一 tick 的 latest() 只能拿到 seed（上一 GUI 值）——
        // 旧值被续命 1~2 tick，1t IO 测试里表现为切换瞬间脉冲被拉宽（第六轮 M1）。
        // 提前发，异步真值最早在下一 tick 就接管；seed 退化成"异步失败时的兜底"。
        // 过渡 tick 本身仍记 lastKnown：那一 tick 我们手里只有它，
        // 记空洞或假 no_data 都更糟（这是物理极限，见 Sampler 类注释的接缝说明）。
        WorldProbe.request(target, curGameTime);
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
     * 不该把动作栏刷成跑马灯。**force = 重要警告，绕过去抖**
     * （"下标不可信"紧跟在"转为坐标跟踪"后面，普通去抖会把它吞掉 —— 第七轮 N3）。
     */
    private void notifyPlayer(String key) {
        notifyPlayer(key, false);
    }

    private void notifyPlayer(String key, boolean force) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (!force && tick - lastNotifyTick < 40)) {
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
        // 时间刻度 = 世界 tick（第七轮 N12，见类注释）。主菜单等无世界场合退回客户端节拍。
        Minecraft mc = Minecraft.getInstance();
        curGameTime = mc.level != null ? mc.level.getGameTime() : tick;
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

        // 世界这一 tick 还没走（`/tick rate` 调慢、ESC 暂停）→ 菜单同步也没动，
        // 没有任何新信息。记重复样本只会让 tick rate ≠ 20 时数据全错（第七轮 N12）。
        if (curGameTime == lastSampledGameTime) {
            return;
        }
        lastSampledGameTime = curGameTime;

        if (!ContainerAccess.hasForeignContainerOpen()) {
            // 玩家关掉界面了
            if (followAfterClose && target.hasPersistentId()) {
                // ★ 先补住"关界面的这一 tick"★
                // 这一 tick 菜单已经没了，但上一 tick 读到的值还在手上（最多 1 tick 旧）。
                // 不补的话时间轴在这里断一格，而下一 tick 又会因为异步读取还没回来
                // 记一条 no_data —— 合起来就是实机反馈的"关界面时冒出 1t 的 no-data"。
                if (lastKnown != null) {
                    store.add(new Sample(curGameTime, lastKnown.itemId(), lastKnown.count()));
                }
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

        lastKnown = view;
        store.add(new Sample(curGameTime, view.itemId(), view.count()));
    }

    /** 界面关着：按坐标读，读不到就记 no_data */
    private void tickFollowing() {
        if (target == null) {
            state = State.IDLE;
            return;
        }

        // 同 tickRecording：世界没走 tick 就没有新信息，跳过（第七轮 N12）
        if (curGameTime == lastSampledGameTime) {
            return;
        }
        lastSampledGameTime = curGameTime;

        // ★ 下标映射不可信的机器（MEK 这类自写槽位类的模组），必须明说 ★
        // 验证是挂探头那 1~2 tick 内在服务端做的（内容指纹匹配），
        // 匹配有歧义（例如对着空格子挂探头）就到此为止 —— 不瞎猜，让用户知道。
        // force：警告紧跟"转为坐标跟踪"，普通去抖会把它吞掉（第七轮 N3）。
        if (!warnedUnverifiedIndex
                && WorldProbe.mappingStatus(target) == WorldProbe.MappingStatus.UNVERIFIED) {
            warnedUnverifiedIndex = true;
            notifyPlayer("logiscope.msg.index_unverified", true);
        }

        if (ContainerAccess.hasForeignContainerOpen()) {
            // 玩家又把界面打开了 —— 优先回到最准的那条路径
            if (ContainerAccess.currentContainerId() == watchedContainerId || tryRebind()) {
                // ★ 补住切换 tick ★
                // 世界侧缓存里还留着切换前一刻的真值；不垫的话这一 tick 什么都不记，
                // 时间轴断一格，波形在这里凭空断开（与关界面方向对称）。
                ContainerAccess.SlotView bridge = WorldProbe.latest(curGameTime, WORLD_READ_MAX_AGE);
                if (bridge != null) {
                    store.add(new Sample(curGameTime, bridge.itemId(), bridge.count()));
                }
                state = State.RECORDING;
                return;
            }
            // 开的是别的容器：继续按坐标跟踪，不打断
        }

        WorldProbe.request(target, curGameTime);
        ContainerAccess.SlotView view = WorldProbe.latest(curGameTime, WORLD_READ_MAX_AGE);
        if (view == null) {
            store.add(Sample.noData(curGameTime));
        } else {
            lastKnown = view;
            store.add(new Sample(curGameTime, view.itemId(), view.count()));
        }
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
